package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import me.eliassanchezfernandez.puntodeventa.service.ProductoImagenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fotos de productos para la interfaz: se piden a la base en UN hilo aparte
 * (nunca en el hilo de JavaFX) y se guardan decodificadas en dos cachés LRU
 * pequeñas (miniaturas para tablas y resultados; imágenes grandes para el
 * detalle). "Sin imagen" también se recuerda para no volver a consultar.
 * Lo comparten Productos, Ventas (F6) e Inventario.
 */
@Component
public class ImagenProductoCache {

    static final int MAX_MINIATURAS = 300;
    static final int MAX_GRANDES = 12;
    /** Lado en píxeles al que se decodifica la miniatura (las tablas la muestran a ~30 px) */
    static final double LADO_MINIATURA = 64;

    @Autowired private ProductoImagenService servicio;

    private final Map<Long, Optional<Image>> miniaturas = lru(MAX_MINIATURAS);
    private final Map<Long, Optional<Image>> grandes = lru(MAX_GRANDES);
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "imagenes-producto");
        t.setDaemon(true);
        return t;
    });

    private static Map<Long, Optional<Image>> lru(int maximo) {
        return new LinkedHashMap<>(32, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Optional<Image>> e) {
                return size() > maximo;
            }
        };
    }

    /** Miniatura del producto; {@code alListo} corre en el hilo de JavaFX con la imagen o null si no tiene */
    public void miniatura(Long productoId, Consumer<Image> alListo) {
        pedir(productoId, miniaturas, true, alListo);
    }

    /** Imagen grande (hasta 512 px) del producto; {@code alListo} corre en el hilo de JavaFX */
    public void imagen(Long productoId, Consumer<Image> alListo) {
        pedir(productoId, grandes, false, alListo);
    }

    /** Olvida lo cacheado de un producto (tras cambiar o quitar su foto) */
    public void invalidar(Long productoId) {
        if (productoId == null) return;
        synchronized (this) {
            miniaturas.remove(productoId);
            grandes.remove(productoId);
        }
    }

    private void pedir(Long id, Map<Long, Optional<Image>> cache, boolean mini, Consumer<Image> alListo) {
        if (id == null) { alListo.accept(null); return; }
        Optional<Image> enCache;
        synchronized (this) { enCache = cache.get(id); }
        if (enCache != null) { alListo.accept(enCache.orElse(null)); return; }
        hilo.execute(() -> {
            Optional<Image> img;
            try {
                Optional<byte[]> bytes = mini ? servicio.miniatura(id) : servicio.imagen(id);
                img = bytes.map(b -> mini ? decodificar(b, LADO_MINIATURA) : decodificar(b, 0));
            } catch (RuntimeException e) {
                img = Optional.empty();      // sin conexión a la base, etc.: la tabla simplemente no muestra foto
            }
            synchronized (this) { cache.put(id, img); }
            Optional<Image> listo = img;
            Platform.runLater(() -> alListo.accept(listo.orElse(null)));
        });
    }

    /** Decodifica un JPEG/PNG en memoria; {@code lado} > 0 lo reduce al leer (ahorra memoria) */
    public static Image decodificar(byte[] bytes, double lado) {
        Image img = lado > 0
                ? new Image(new ByteArrayInputStream(bytes), lado, lado, true, true)
                : new Image(new ByteArrayInputStream(bytes));
        return img.isError() ? null : img;
    }

    /**
     * Columna de miniaturas para una tabla cuyo valor de celda es el id del producto.
     * La celda pide la miniatura al caché y solo la pinta si la fila sigue siendo la misma.
     */
    public <S> TableCell<S, Long> celdaMiniatura(TableColumn<S, Long> col, double lado) {
        return new TableCell<>() {
            private final ImageView vista = new ImageView();
            {
                vista.setFitWidth(lado);
                vista.setFitHeight(lado);
                vista.setPreserveRatio(true);
                vista.setSmooth(true);
            }
            @Override protected void updateItem(Long id, boolean empty) {
                super.updateItem(id, empty);
                vista.setImage(null);
                if (empty || id == null) { setGraphic(null); return; }
                setGraphic(vista);
                miniatura(id, img -> { if (Objects.equals(getItem(), id)) vista.setImage(img); });
            }
        };
    }

    /**
     * Imagen de JavaFX (portapapeles o arrastre) → PNG en bytes, para pasar por la misma
     * normalización que un archivo. Sin SwingFXUtils (javafx.swing no va en el instalador).
     */
    public static byte[] aPng(Image imagen) {
        if (imagen == null || imagen.isError()) return null;
        int w = (int) imagen.getWidth(), h = (int) imagen.getHeight();
        if (w <= 0 || h <= 0) return null;
        PixelReader lector = imagen.getPixelReader();
        if (lector == null) return null;
        BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] fila = new int[w];
        for (int y = 0; y < h; y++) {
            lector.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), fila, 0, w);
            buf.setRGB(0, y, w, 1, fila, 0, w);
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try {
            ImageIO.write(buf, "png", salida);
        } catch (IOException e) {
            return null;
        }
        return salida.toByteArray();
    }
}
