package me.eliassanchezfernandez.puntodeventa.controller;

import atlantafx.base.theme.NordLight;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.AgregadosService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.AplicadorApariencia;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Apariencia;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas de la pestaña Estadísticas, una por cada pestaña interna («Hoy»,
 * Resumen, Horarios pico…), al tamaño del área de contenido con la ventana
 * maximizada en 1280×800 y en 1024×768, con ventas de muestra sobre la BD
 * desechable de pruebas. Anota en {@code medidas.txt} el desplazamiento de
 * cada vista y falla si alguna se desplaza a lo ancho. Solo corre a petición,
 * porque necesita una pantalla:
 * <pre>APP_CAPTURAS=true APP_CAPTURAS_DIR=/ruta ./gradlew test --tests '*CapturasEstadisticasTest'</pre>
 * Sin APP_CAPTURAS la prueba se omite. Las ventas sembradas se borran al final.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS", matches = "true")
class CapturasEstadisticasTest {

    @Autowired private ApplicationContext springContext;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private AgregadosService agregadosService;

    @Test
    void generaCapturaDeEstadisticas() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "capturas-estadisticas");
        Files.createDirectories(dir);

        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);
        sesionService.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);

        Producto p = new Producto();
        p.setCodigoBarras("CAP-" + System.nanoTime());
        p.setDescripcion("Coca Cola 600 ml");
        p.setPrecioCosto(12);
        p.setPrecioVenta(20);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        p.setUsaInventario(false);
        p = productoRepo.save(p);
        List<Producto> otros = new ArrayList<>();
        String[][] catalogo = {{"Sabritas 45 g", "18"}, {"Leche Lala 1 L", "29"}, {"Pan Bimbo grande", "52"}, {"Huevo kg", "48"}};
        for (String[] c : catalogo) {
            Producto o = new Producto();
            o.setCodigoBarras("CAP-" + System.nanoTime());
            o.setDescripcion(c[0]);
            o.setPrecioCosto(Double.parseDouble(c[1]) * 0.7);
            o.setPrecioVenta(Double.parseDouble(c[1]));
            o.setTipoVenta(Producto.TipoVenta.UNIDAD);
            o.setUsaInventario(false);
            otros.add(productoRepo.save(o));
        }

        List<Venta> sembradas = new ArrayList<>();
        try {
            LocalDate hoy = LocalDate.now();
            LocalTime ahora = LocalTime.now();
            // Hoy: varias ventas hasta ahora
            for (int i = 0; i < 9; i++) sembradas.add(venta(p, hoy.atTime(ahora.minusMinutes(5 + i * 17L).withSecond(0)), 1 + i % 3));
            // Ayer: algo menos hasta esta hora y más por la tarde
            for (int i = 0; i < 6; i++) sembradas.add(venta(p, hoy.minusDays(1).atTime(ahora.minusMinutes(10 + i * 23L)), 1 + i % 2));
            // El mismo día de la semana pasada: mejor que hoy
            for (int i = 0; i < 12; i++) sembradas.add(venta(p, hoy.minusDays(7).atTime(ahora.minusMinutes(3 + i * 11L)), 2));
            // Hace un año: la tienda ya existía, pero una sola venta
            sembradas.add(venta(p, hoy.minusYears(1).atTime(ahora.minusMinutes(30)), 1));
            // Cinco semanas de ventas variadas para las gráficas, el ranking y el mapa de calor
            for (int d = 1; d <= 35; d++) {
                for (int k = 0; k < 3 + d % 4; k++) {
                    Producto o = otros.get((d + k) % otros.size());
                    sembradas.add(venta(o, hoy.minusDays(d).atTime(8 + (k * 3 + d) % 13, (k * 7) % 60), 1 + k % 2));
                }
            }

            agregadosService.recalcularManual();
            arrancarJavaFx();
            enFx(() -> Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()));
            // Área de contenido de la pestaña con la ventana maximizada (barra de tareas, título, pestañas y barra de estado fuera)
            capturar("/fxml/estadisticas.fxml", 1280, 662, dir, "1280x800");
            capturar("/fxml/estadisticas.fxml", 1024, 630, dir, "1024x768");
            // Gráficas con datos reales en los temas oscuros y de vidrio: ejes, cifras, rejilla y barras legibles
            Apariencia[] temas = {
                    Apariencia.porDefecto().conTema(Apariencia.Tema.OSCURO),
                    Apariencia.porDefecto().conTema(Apariencia.Tema.ALTO_CONTRASTE),
                    Apariencia.porDefecto().conVidrio(true),
                    Apariencia.porDefecto().conTema(Apariencia.Tema.OSCURO).conVidrio(true),
            };
            try {
                for (Apariencia a : temas) {
                    enFx(() -> AplicadorApariencia.aplicar(a));
                    capturar("/fxml/estadisticas.fxml", 1280, 662, dir,
                            "1280x800-" + a.tema().clave() + (a.vidrio() ? "-vidrio" : ""));
                }
            } finally {
                enFx(() -> AplicadorApariencia.aplicar(Apariencia.porDefecto()));
                enFx(() -> Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()));
            }
            Files.writeString(dir.resolve("medidas.txt"), medidas.toString());
            System.out.println(medidas);
        } finally {
            ventaRepo.deleteAll(sembradas);
            productoRepo.delete(p);
            productoRepo.deleteAll(otros);
            agregadosService.recalcularManual();
            sesionService.cerrarSesion();
        }
        assertThat(medidas.toString()).doesNotContain("SCROLL-H").doesNotContain("RECORTE-H");
        System.out.println("Capturas en " + dir.toAbsolutePath());
    }

    private Venta venta(Producto p, LocalDateTime fecha, double cantidad) {
        Venta v = new Venta();
        v.setFecha(fecha);
        DetalleVenta d = new DetalleVenta();
        d.setVenta(v);
        d.setProducto(p);
        d.setCantidad(cantidad);
        d.setPrecioVenta(p.getPrecioVenta());
        v.getDetalles().add(d);
        v.setTotal(p.getPrecioVenta() * cantidad);
        v.setPagoCon(v.getTotal());
        return ventaRepo.save(v);
    }

    /** Cada vista: se espera a que pinten los hilos de carga y se toma la foto */
    private void capturar(String fxml, double ancho, double alto, Path dir, String prefijo) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch listo = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(CapturasEstadisticasTest.class.getResource(fxml));
                loader.setControllerFactory(springContext::getBean);
                Parent raiz = loader.load();
                Scene escena = new Scene(raiz, ancho, alto);
                Ventanas.aplicarCss(escena);
                Stage stage = new Stage();
                stage.setScene(escena);
                stage.show();
                List<Runnable> pasos = new ArrayList<>();
                if (prefijo.contains("-")) {
                    // Temas: periodo «Mes» para que la línea de ventas por día tenga muchos puntos
                    pasos.add(() -> raiz.lookupAll(".toggle-button").stream()
                            .filter(b -> b instanceof javafx.scene.control.ButtonBase bb && "Mes".equals(bb.getText()))
                            .map(b -> (javafx.scene.control.ButtonBase) b).findFirst()
                            .ifPresent(javafx.scene.control.ButtonBase::fire));
                }
                TabPane pestanas = buscar(raiz, TabPane.class);
                if (pestanas != null) {
                    // Una foto por pestaña (cada sección en su propia ventanita)
                    for (int i = 0; i < pestanas.getTabs().size(); i++) {
                        final int n = i;
                        Tab tab = pestanas.getTabs().get(i);
                        pasos.add(() -> pestanas.getSelectionModel().select(n));
                        pasos.add(() -> foto(escena, dir.resolve(String.format("%s-%02d-%s.png", prefijo, n, nombreArchivo(tab.getText())))));
                    }
                } else {
                    pasos.add(() -> foto(escena, dir.resolve(prefijo + "-00-inicio.png")));
                    // Diseño con secciones plegadas: se abren todas para ver el largo
                    pasos.add(() -> todos(raiz, TitledPane.class).forEach(tp -> tp.setExpanded(true)));
                    pasos.add(() -> foto(escena, dir.resolve(prefijo + "-01-todo-abierto.png")));
                }
                pasos.add(() -> {
                    stage.close();
                    listo.countDown();
                });
                // Las consultas corren en hilos aparte: un respiro para que pinten
                encadenar(pasos, 0, 2500, error, listo);
            } catch (Throwable t) {
                error.set(t);
                listo.countDown();
            }
        });
        assertThat(listo.await(90, TimeUnit.SECONDS)).as("captura de " + fxml).isTrue();
        if (error.get() != null) throw new AssertionError("No se pudo capturar " + fxml, error.get());
    }

    private void encadenar(List<Runnable> pasos, int i, long esperaMs, AtomicReference<Throwable> error, CountDownLatch listo) {
        if (i >= pasos.size()) return;
        PauseTransition espera = new PauseTransition(Duration.millis(esperaMs));
        espera.setOnFinished(e -> {
            try {
                pasos.get(i).run();
                encadenar(pasos, i + 1, 900, error, listo);
            } catch (Throwable t) {
                error.set(t);
                listo.countDown();
            }
        });
        espera.play();
    }

    private void foto(Scene escena, Path destino) {
        try {
            guardarPng(escena.snapshot(null), destino.toFile());
            medir(destino.getFileName().toString(), escena);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private final StringBuilder medidas = new StringBuilder();

    /** Anota si hay desplazamiento horizontal (no debe) y cuánto vertical queda */
    private void medir(String nombre, Scene escena) {
        StringBuilder linea = new StringBuilder(String.format("%-48s %4.0f×%-4.0f", nombre, escena.getWidth(), escena.getHeight()));
        double minAncho = escena.getRoot().minWidth(-1);
        if (minAncho > escena.getWidth() + 0.5) linea.append(String.format("  RECORTE-H raíz min %.0f", minAncho));
        for (ScrollPane sp : todos(escena.getRoot(), ScrollPane.class)) {
            if (!visible(sp) || sp.getContent() == null) continue;
            var vista = sp.getViewportBounds();
            var cont = sp.getContent().getLayoutBounds();
            linea.append(String.format("  scroll: %.0f×%.0f en %.0f×%.0f", cont.getWidth(), cont.getHeight(), vista.getWidth(), vista.getHeight()));
            if (cont.getWidth() > vista.getWidth() + 1) linea.append(" SCROLL-H");
        }
        medidas.append(linea).append('\n');
    }

    private static boolean visible(Node n) {
        for (Node x = n; x != null; x = x.getParent()) if (!x.isVisible()) return false;
        return true;
    }

    private static String nombreArchivo(String texto) {
        String s = java.text.Normalizer.normalize(texto == null ? "" : texto, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("(^-|-$)", "");
    }

    private static <T> T buscar(Node raiz, Class<T> tipo) {
        List<T> l = todos(raiz, tipo);
        return l.isEmpty() ? null : l.get(0);
    }

    private static <T> List<T> todos(Node raiz, Class<T> tipo) {
        List<T> salida = new ArrayList<>();
        juntar(raiz, tipo, salida);
        return salida;
    }

    private static <T> void juntar(Node n, Class<T> tipo, List<T> salida) {
        if (tipo.isInstance(n)) salida.add(tipo.cast(n));
        if (n instanceof TableView<?>) return;
        if (n instanceof Parent p) for (Node h : p.getChildrenUnmodifiable()) juntar(h, tipo, salida);
        if (n instanceof ScrollPane sp && sp.getContent() != null && !sp.getChildrenUnmodifiable().contains(sp.getContent())) juntar(sp.getContent(), tipo, salida);
        if (n instanceof TabPane tp) for (Tab t : tp.getTabs()) if (t.getContent() != null && t.getContent().getParent() == null) juntar(t.getContent(), tipo, salida);
        if (n instanceof TitledPane tp && tp.getContent() != null && tp.getContent().getParent() == null) juntar(tp.getContent(), tipo, salida);
    }

    /** Sin javafx.swing en el proyecto: se copian los píxeles a mano */
    private static void guardarPng(WritableImage img, File destino) throws Exception {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        }
        ImageIO.write(bi, "png", destino);
    }

    private static void arrancarJavaFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException yaArrancado) {
            latch.countDown();
        }
        Platform.setImplicitExit(false);
        assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
    }

    private static void enFx(Runnable r) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> { try { r.run(); } finally { latch.countDown(); } });
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
