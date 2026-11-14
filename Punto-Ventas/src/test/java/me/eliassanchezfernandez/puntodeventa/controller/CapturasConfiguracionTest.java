package me.eliassanchezfernandez.puntodeventa.controller;

import atlantafx.base.theme.NordLight;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AccesoMovilService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas de pantalla de la pestaña Configuración y sus ventanas (hub, Acceso
 * móvil, Ubicación y clima, asistente inicial, báscula) con los servicios reales
 * sobre la BD desechable de pruebas. Solo corre a petición, porque necesita una
 * pantalla:
 * <pre>APP_CAPTURAS=true APP_CAPTURAS_DIR=/ruta ./gradlew test --tests '*CapturasConfiguracionTest'</pre>
 * Sin APP_CAPTURAS la prueba se omite. No llama a internet: el QR se arma con la
 * red local y la ubicación se escribe a mano en el selector.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS", matches = "true")
class CapturasConfiguracionTest {

    @Autowired private ApplicationContext springContext;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private AccesoMovilService accesoMovilService;

    @Test
    void generaCapturasDeConfiguracion() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "capturas-configuracion");
        Files.createDirectories(dir);

        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);
        sesionService.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);

        arrancarJavaFx();
        enFx(() -> Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()));

        // Hub de Configuración tal como se ve dentro de la pestaña a 1024×768 (área de contenido)
        capturar("/fxml/configuracion.fxml", 1008, 640, dir.resolve("configuracion-hub.png"), null);

        // Acceso móvil con el servidor encendido (QR real de la red local) y luego apagado
        try {
            accesoMovilService.configurarAutomatico(true);
        } catch (RuntimeException e) {
            System.err.println("Capturas: no se pudo encender el acceso móvil: " + e.getMessage());
        }
        capturar("/fxml/configuracion_movil.fxml", 820, 720, dir.resolve("acceso-movil.png"), null);
        try {
            accesoMovilService.configurarAutomatico(false);
        } catch (RuntimeException ignorada) { /* ya estaba apagado */ }

        // Ubicación y clima: sin ubicación y con una elegida (sin llamar a internet)
        capturar("/fxml/configuracion_clima.fxml", 640, 620, dir.resolve("ubicacion-clima-vacia.png"), null);
        capturar("/fxml/configuracion_clima.fxml", 640, 620, dir.resolve("ubicacion-clima.png"), loader -> {
            SelectorUbicacion sel = (SelectorUbicacion) loader.getNamespace().get("selectorUbicacion");
            sel.setValores("Puebla", 19.0414, -98.2063);
        });

        capturar("/fxml/asistente_inicial.fxml", 640, 700, dir.resolve("asistente-inicial.png"), null);
        capturar("/fxml/configuracion_bascula.fxml", 600, 620, dir.resolve("bascula.png"), null);

        sesionService.cerrarSesion();
        try (var archivos = Files.list(dir)) {
            assertThat(archivos.filter(p -> p.toString().endsWith(".png")).count()).isGreaterThanOrEqualTo(6);
        }
        System.out.println("Capturas en " + dir.toAbsolutePath());
    }

    private void capturar(String fxml, double ancho, double alto, Path destino, Consumer<FXMLLoader> ajustar) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch listo = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(CapturasConfiguracionTest.class.getResource(fxml));
                loader.setControllerFactory(springContext::getBean);
                Parent raiz = loader.load();
                if (ajustar != null) ajustar.accept(loader);
                Scene escena = new Scene(raiz, ancho, alto);
                Ventanas.aplicarCss(escena);
                Stage stage = new Stage();
                stage.setScene(escena);
                stage.show();
                // Un respiro para que se apliquen los estilos y los hilos (lista de puertos, mDNS) actualicen
                PauseTransition espera = new PauseTransition(Duration.millis(900));
                espera.setOnFinished(e -> {
                    try {
                        WritableImage img = escena.snapshot(null);
                        guardarPng(img, destino.toFile());
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
        if (error.get() != null) throw new AssertionError("No se pudo capturar " + fxml, error.get());
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
