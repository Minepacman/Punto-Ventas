package me.eliassanchezfernandez.puntodeventa.controller;

import atlantafx.base.theme.NordLight;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.DatosDemoCapturas;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.ValeService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas de la ventana principal para el sitio público, con la tienda de ejemplo de
 * {@link DatosDemoCapturas}: Ventas con un ticket en captura, ventana de cobro, Promociones, Clientes (fiado, solo si está visible),
 * Productos, Inventario y «Por caducar», Corte, Estadísticas, Configuración y «Devolver artículos».
 * Ventana de 1280×800 (área de la escena 1280×720) a doble densidad. Solo a petición, porque necesita
 * una pantalla:
 * <pre>APP_CAPTURAS=true APP_CAPTURAS_DIR=/ruta ./gradlew test --tests '*CapturasSitioTest'</pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS", matches = "true")
class CapturasSitioTest {

    private static final double ANCHO = 1280, ALTO = 720, ESCALA = 2;

    @Autowired private ApplicationContext springContext;
    @Autowired private SesionService sesionService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ValeService valeService;
    @Autowired private DevolucionDialogo devolucionDialogo;

    @Test
    void capturasParaElSitio() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "sitio");
        Files.createDirectories(dir);
        // Sin la ventana de donación encima de la principal
        Field clabe = Class.forName("me.eliassanchezfernandez.puntodeventa.service.BienvenidaService")
                .getDeclaredField("clabeConfiguradaParaPruebas");
        clabe.setAccessible(true);
        clabe.set(null, Boolean.FALSE);
        try {
            DatosDemoCapturas.Sembrado datos = DatosDemoCapturas.sembrar(springContext);
            arrancarJavaFx();
            enFx(() -> Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()));

            AtomicReference<Stage> principal = new AtomicReference<>();
            AtomicReference<Object> mainCtl = new AtomicReference<>();
            enFx(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(CapturasSitioTest.class.getResource("/fxml/main.fxml"));
                    loader.setControllerFactory(springContext::getBean);
                    Parent raiz = loader.load();
                    Scene escena = new Scene(raiz, ANCHO, ALTO);
                    Ventanas.aplicarCss(escena);
                    Stage stage = new Stage();
                    stage.setTitle("Caja Libre");
                    stage.setScene(escena);
                    stage.show();
                    principal.set(stage);
                    mainCtl.set(loader.getController());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Stage stage = principal.get();
            esperar(2500);

            // ── Ventas: un ticket en captura ──
            Object ventas = campo(mainCtl.get(), "ventasViewController");
            Method agregar = ventas.getClass().getDeclaredMethod("agregarAlTicket", Producto.class, double.class);
            agregar.setAccessible(true);
            Map<String, Producto> p = datos.productos();
            enFx(() -> {
                try {
                    agregar.invoke(ventas, p.get("Coca-Cola 600 ml"), 2);
                    agregar.invoke(ventas, p.get("Sabritas Sal 45 g"), 1);
                    agregar.invoke(ventas, p.get("Leche Lala entera 1 L"), 1);
                    agregar.invoke(ventas, p.get("Aguacate"), 0.75);
                    agregar.invoke(ventas, p.get("Huevo blanco 12 pz"), 1);
                    agregar.invoke(ventas, p.get("Pan Bimbo blanco grande"), 1);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            esperar(1200);
            foto(stage.getScene(), dir.resolve("ventas.png"));

            // ── Ventana de cobro (efectivo 500) ──
            double total = 2 * 20 + 20 + 29 + 0.75 * 65 + 48 + 52;
            modal(() -> CobroDialogo.mostrar(stage, total, configuracionService.formasPagoHabilitadas(),
                    Map.of(Venta.FormaPago.EFECTIVO, 500.0), false,
                    me.eliassanchezfernandez.puntodeventa.util.FuncionesOcultas.FIADO_HABILITADO
                            ? new CobroDialogo.Fiado(w -> Optional.empty(), null) : null,
                    new CobroDialogo.ValeTienda(valeService::consultar)), dir.resolve("cobro.png"));

            // ── Pestañas ──
            TabPane pestanas = (TabPane) campo(mainCtl.get(), "tabPane");
            for (Tab tab : new ArrayList<>(pestanas.getTabs())) {
                String nombre = nombreArchivo(tab.getText().replaceAll("^F\\d+ · ", ""));
                if (nombre.equals("ventas")) continue;
                enFx(() -> pestanas.getSelectionModel().select(tab));
                esperar(3000);
                foto(stage.getScene(), dir.resolve(nombre + ".png"));
                if (nombre.equals("productos")) {
                    // Alta por código de barras: el catálogo de México incluido llena los datos
                    enFx(() -> {
                        stage.getScene().getRoot().lookupAll(".button").stream()
                                .filter(n -> n instanceof Button b && b.isVisible() && b.getText() != null && b.getText().contains("Nuevo"))
                                .map(n -> (Button) n).findFirst().orElseThrow().fire();
                        ((javafx.scene.control.TextField) stage.getScene().lookup("#txtCodigoBarras")).setText("0041789001864");
                    });
                    esperar(4000);
                    foto(stage.getScene(), dir.resolve("producto-nuevo-catalogo.png"));
                }
                if (nombre.equals("estadisticas")) {
                    // Resumen de la semana y Reabastecimiento (las pestañas internas)
                    TabPane internas = stage.getScene().getRoot().lookupAll(".tab-pane").stream()
                            .filter(n -> n != pestanas && n instanceof TabPane).map(n -> (TabPane) n).findFirst().orElseThrow();
                    enFx(() -> internas.getSelectionModel().select(1));
                    esperar(1500);
                    enFx(() -> java.util.stream.Stream.concat(stage.getScene().getRoot().lookupAll(".button").stream(),
                                    stage.getScene().getRoot().lookupAll(".toggle-button").stream())
                            .filter(n -> n instanceof javafx.scene.control.ButtonBase b && b.isVisible() && "Semana".equals(b.getText()))
                            .map(n -> (javafx.scene.control.ButtonBase) n).findFirst().ifPresent(javafx.scene.control.ButtonBase::fire));
                    esperar(3000);
                    foto(stage.getScene(), dir.resolve("estadisticas-resumen.png"));
                    enFx(() -> internas.getSelectionModel().select(5));
                    esperar(3000);
                    foto(stage.getScene(), dir.resolve("estadisticas-reabastecimiento.png"));
                }
                if (nombre.equals("inventario")) {
                    Button porCaducar = (Button) stage.getScene().lookup("#btnPorCaducar");
                    if (porCaducar != null) modal(porCaducar::fire, dir.resolve("por-caducar.png"));
                }
            }

            // ── Devolver artículos de una venta ──
            enFx(() -> pestanas.getSelectionModel().select(0));
            esperar(800);
            modal(() -> devolucionDialogo.mostrar(stage, datos.ventaParaDevolverId()), dir.resolve("devolucion.png"));

            enFx(stage::close);

            // ── Asistente inicial e inicio de sesión, con el nombre de la tienda de ejemplo ──
            fotoFxml("/fxml/asistente_inicial.fxml", 640, 700, dir.resolve("asistente.png"));
            fotoFxml("/fxml/login.fxml", 640, 700, dir.resolve("login.png"));
        } finally {
            clabe.set(null, null);
            sesionService.cerrarSesion();
        }
        System.out.println("Capturas en " + dir.toAbsolutePath());
    }

    /** Una vista suelta en su propia ventana; si no se puede armar (depende de un estado que aquí no hay) solo se avisa */
    private void fotoFxml(String fxml, double ancho, double alto, Path destino) throws Exception {
        AtomicReference<Stage> ventana = new AtomicReference<>();
        try {
            enFx(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(CapturasSitioTest.class.getResource(fxml));
                    loader.setControllerFactory(springContext::getBean);
                    Scene escena = new Scene(loader.load(), ancho, alto);
                    Ventanas.aplicarCss(escena);
                    Stage s = new Stage();
                    s.setScene(escena);
                    s.show();
                    ventana.set(s);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            esperar(2000);
            foto(ventana.get().getScene(), destino);
        } catch (Throwable t) {
            System.out.println("Capturas: no se pudo capturar " + fxml + ": " + t);
        } finally {
            if (ventana.get() != null) enFx(ventana.get()::close);
        }
    }

    /** Abre un diálogo modal (showAndWait) y, mientras está abierto, le toma la foto y lo cierra */
    private void modal(Runnable abrir, Path destino) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch listo = new CountDownLatch(1);
        Platform.runLater(() -> {
            List<Window> antes = new ArrayList<>(Window.getWindows());
            PauseTransition espera = new PauseTransition(Duration.millis(1800));
            espera.setOnFinished(e -> {
                try {
                    Window nueva = Window.getWindows().stream().filter(w -> !antes.contains(w) && w.isShowing())
                            .reduce((a, b) -> b).orElseThrow(() -> new IllegalStateException("No se abrió ninguna ventana"));
                    guardarPng(escalada(nueva.getScene().getRoot()), destino.toFile());
                    nueva.hide();
                } catch (Throwable t) {
                    error.set(t);
                }
            });
            espera.play();
            try {
                abrir.run();
            } catch (Throwable t) {
                if (error.get() == null) error.set(t);
            }
            listo.countDown();
        });
        assertThat(listo.await(60, TimeUnit.SECONDS)).as("diálogo para " + destino.getFileName()).isTrue();
        if (error.get() != null) throw new AssertionError("No se pudo capturar " + destino.getFileName(), error.get());
        esperar(500);
    }

    private void foto(Scene escena, Path destino) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        enFx(() -> {
            try {
                guardarPng(escalada(escena.getRoot()), destino.toFile());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) throw new AssertionError("No se pudo capturar " + destino.getFileName(), error.get());
    }

    private static WritableImage escalada(Node raiz) {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setTransform(Transform.scale(ESCALA, ESCALA));
        return raiz.snapshot(sp, null);
    }

    private static Object campo(Object objeto, String nombre) throws Exception {
        Field f = objeto.getClass().getDeclaredField(nombre);
        f.setAccessible(true);
        return f.get(objeto);
    }

    private static String nombreArchivo(String texto) {
        String s = java.text.Normalizer.normalize(texto == null ? "" : texto, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("(^-|-$)", "");
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

    private static void esperar(long ms) throws InterruptedException {
        Thread.sleep(ms);
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
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
