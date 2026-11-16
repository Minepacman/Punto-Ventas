package me.eliassanchezfernandez.puntodeventa.controller;

import atlantafx.base.theme.NordLight;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeTableView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Barras de botones de todas las pestañas y ventanas a 1024×768 (y 1280×800, y con la letra al
 * 115 % y 130 %): captura cada pantalla y revisa que ningún botón muestre su texto recortado con
 * «…». Un botón recortado es el que su skin dibuja con un texto distinto al suyo (el {@code Text}
 * interno de {@code LabeledSkinBase} lleva la versión recortada). Solo corre a petición, porque
 * necesita una pantalla:
 * <pre>APP_CAPTURAS=true APP_CAPTURAS_DIR=/ruta ./gradlew test --tests '*CapturasBarrasTest'</pre>
 * Escribe {@code recortes.txt} con TODO lo recortado (también etiquetas, solo de aviso) y falla si
 * hay algún botón recortado.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS", matches = "true")
class CapturasBarrasTest {

    @Autowired private ApplicationContext springContext;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroRepository cajeroRepo;

    /** Contenido de una pestaña con la ventana maximizada: ancho de pantalla menos bordes, alto menos cabecera y barra de estado */
    private static final String[] PESTANAS = {
            "/fxml/ventas.fxml", "/fxml/clientes.fxml", "/fxml/productos.fxml", "/fxml/inventario.fxml",
            "/fxml/corte.fxml", "/fxml/estadisticas.fxml", "/fxml/configuracion.fxml",
    };

    /** Ventanas modales con el tamaño que pide su controlador (se recortan a la pantalla como en Ventanas.ajustarAPantalla) */
    private static final Object[][] VENTANAS = {
            {"/fxml/proveedores.fxml", 980, 620},
            {"/fxml/ordenes_compra.fxml", 1100, 680},
            {"/fxml/movimientos.fxml", 1050, 600},
            {"/fxml/ventas_periodo.fxml", 1000, 640},
            {"/fxml/promociones.fxml", 1000, 600},
            {"/fxml/facturas.fxml", 1100, 640},
            {"/fxml/clientes_fiscales.fxml", 900, 560},
            {"/fxml/usuarios.fxml", 1000, 690},
            {"/fxml/bitacora.fxml", 1100, 640},
            {"/fxml/configuracion_opciones.fxml", 540, 520},
            {"/fxml/configuracion_negocio.fxml", 640, 660},
            {"/fxml/configuracion_ticket.fxml", 860, 640},
            {"/fxml/configuracion_logotipo.fxml", 440, 440},
            {"/fxml/configuracion_unidades.fxml", 780, 530},
            {"/fxml/configuracion_basedatos.fxml", 940, 669},
            {"/fxml/configuracion_movil.fxml", 960, 720},
            {"/fxml/configuracion_clima.fxml", 640, 620},
            {"/fxml/configuracion_facturacion.fxml", 960, 720},
            {"/fxml/configuracion_impresora.fxml", 600, 760},
            {"/fxml/configuracion_bascula.fxml", 600, 620},
            {"/fxml/asistente_inicial.fxml", 640, 700},
    };

    private final List<String> botonesRecortados = new ArrayList<>();
    private final StringBuilder informe = new StringBuilder();

    @Test
    void ningunBotonRecortadoA1024() throws Exception {
        Path base = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "barras");
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);
        sesionService.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);
        arrancarJavaFx();
        enFx(() -> Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()));

        // {pantalla ancho, alto visible (sin barra de tareas), tamaño de letra base o null}
        Object[][] casos = {
                {1024, 728, null, "1024x768"},
                {1280, 760, null, "1280x800"},
                {1024, 728, 15.0, "1024x768-letra115"},
                {1024, 728, 17.0, "1024x768-letra130"},
        };
        try {
            for (Object[] c : casos) {
                int anchoPantalla = (Integer) c[0];
                int altoVisible = (Integer) c[1];
                Double letra = (Double) c[2];
                Path dir = base.resolve((String) c[3]);
                Files.createDirectories(dir);
                informe.append("== ").append(c[3]).append(" ==\n");
                boolean cuenta = letra == null || letra < 16;   // 130 % solo se documenta
                for (String fxml : PESTANAS) {
                    // Ventana maximizada: pestañas (~42 px), cabecera (~56 px) y barra de estado (~28 px)
                    capturar(fxml, anchoPantalla - 16, altoVisible - 126, letra, dir, cuenta);
                }
                for (Object[] v : VENTANAS) {
                    double ancho = Math.min(((Number) v[1]).doubleValue(), Math.floor(anchoPantalla * 0.96));
                    double alto = Math.min(((Number) v[2]).doubleValue(), Math.floor(altoVisible * 0.92));
                    capturar((String) v[0], ancho, alto, letra, dir, cuenta);
                }
            }
        } finally {
            sesionService.cerrarSesion();
            Files.createDirectories(base);
            Files.writeString(base.resolve("recortes.txt"), informe.toString());
            System.out.println(informe);
            System.out.println("Capturas en " + base.toAbsolutePath());
        }
        assertThat(botonesRecortados).as("botones con el texto recortado (ver recortes.txt)").isEmpty();
    }

    private void capturar(String fxml, double ancho, double alto, Double letra, Path dir, boolean cuenta) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch listo = new CountDownLatch(1);
        String nombre = fxml.substring(fxml.lastIndexOf('/') + 1).replace(".fxml", "");
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(CapturasBarrasTest.class.getResource(fxml));
                loader.setControllerFactory(springContext::getBean);
                Parent raiz = loader.load();
                if (letra != null) raiz.setStyle("-fx-font-size: " + letra + "px;");
                Scene escena = new Scene(raiz, ancho, alto);
                Ventanas.aplicarCss(escena);
                Stage stage = new Stage();
                stage.setScene(escena);
                stage.show();
                PauseTransition espera = new PauseTransition(Duration.millis(700));
                espera.setOnFinished(e -> {
                    try {
                        escena.getRoot().applyCss();
                        escena.getRoot().layout();
                        revisar(nombre, raiz, cuenta);
                        guardarPng(escena.snapshot(null), dir.resolve(nombre + ".png").toFile());
                    } catch (Exception ex) {
                        error.set(ex);
                    } finally {
                        stage.close();
                        listo.countDown();
                    }
                });
                espera.play();
            } catch (Throwable t) {
                error.set(t);
                listo.countDown();
            }
        });
        assertThat(listo.await(30, TimeUnit.SECONDS)).as("captura de " + fxml).isTrue();
        if (error.get() != null) {
            informe.append(String.format("  %-26s NO SE PUDO CARGAR: %s%n", nombre, error.get()));
        }
    }

    /** Anota cada control con el texto recortado; los botones (y lo que va dentro de un botón) cuentan como fallo */
    private void revisar(String pantalla, Node n, boolean cuenta) {
        if (!n.isVisible()) return;
        if (n instanceof TableView<?> || n instanceof ListView<?> || n instanceof TreeTableView<?>) return;
        if (n instanceof Labeled l) {
            String recortado = textoMostrado(l);
            if (recortado != null) {
                ButtonBase boton = n instanceof ButtonBase b ? b : botonQueContiene(n);
                String linea = String.format("  %-26s %-14s «%s» → «%s»", pantalla,
                        boton != null ? boton.getClass().getSimpleName() : n.getClass().getSimpleName(),
                        l.getText(), recortado);
                informe.append(linea).append(boton != null ? "  ← BOTÓN" : "").append('\n');
                if (boton != null && cuenta) botonesRecortados.add(linea);
            }
        }
        if (n instanceof Parent p) for (Node h : p.getChildrenUnmodifiable()) revisar(pantalla, h, cuenta);
        if (n instanceof ScrollPane sp && sp.getContent() != null) revisar(pantalla, sp.getContent(), cuenta);
        if (n instanceof TabPane tp) for (var t : tp.getTabs()) if (t.getContent() != null && t.isSelected()) revisar(pantalla, t.getContent(), cuenta);
        if (n instanceof TitledPane tp && tp.getContent() != null && tp.isExpanded()) revisar(pantalla, tp.getContent(), cuenta);
    }

    /**
     * Texto que el skin dibuja si es distinto (recortado) del texto del control; null si se ve completo.
     * El texto interno es el {@code Text} con la clase «text» que {@code LabeledSkinBase} pone como hijo.
     */
    static String textoMostrado(Labeled l) {
        String propio = l.getText();
        if (propio == null || propio.isBlank()) return null;
        if (l.isMnemonicParsing()) propio = propio.replaceFirst("_(?=.)", "");
        for (Node h : l.getChildrenUnmodifiable()) {
            if (h instanceof Text t && t.getStyleClass().contains("text")) {
                String visto = t.getText();
                if (visto == null) return null;
                if (l.isWrapText()) visto = visto.replace("\n", " ").replaceAll("\\s+", " ").trim();
                String esperado = l.isWrapText() ? propio.replaceAll("\\s+", " ").trim() : propio;
                return visto.equals(esperado) ? null : visto;
            }
        }
        return null;
    }

    private static ButtonBase botonQueContiene(Node n) {
        for (Node p = n.getParent(); p != null; p = p.getParent()) if (p instanceof ButtonBase b) return b;
        return null;
    }

    private static void guardarPng(WritableImage img, File destino) throws Exception {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        ImageIO.write(bi, "png", destino);
    }

    private static void arrancarJavaFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException yaCorre) {
            latch.countDown();
        }
        Platform.setImplicitExit(false);
        assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
    }

    private static void enFx(Runnable r) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> { try { r.run(); } finally { latch.countDown(); } });
        assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
    }
}
