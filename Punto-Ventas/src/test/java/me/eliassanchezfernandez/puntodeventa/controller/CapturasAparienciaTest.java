package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas de las pantallas principales a 1024×768 con cada apariencia (claro, oscuro, alto contraste,
 * vidrio, letra grande, otros paquetes de iconos). Usa el mecanismo real: {@link AplicadorApariencia}
 * pone la hoja en cada ventana que se abre. Solo a petición (necesita pantalla):
 * <pre>APP_CAPTURAS=true APP_CAPTURAS_DIR=/ruta ./gradlew test --tests '*CapturasAparienciaTest'</pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS", matches = "true")
class CapturasAparienciaTest {

    @Autowired private ApplicationContext springContext;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private AparienciaDialogo aparienciaDialogo;

    private final StringBuilder medidas = new StringBuilder();

    @Test
    void capturasPorApariencia() throws Exception {
        System.setProperty("app.apariencia.recordar", "false");
        Path base = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "escritorio");
        Files.createDirectories(base);
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);
        arrancarJavaFx();

        Apariencia d = Apariencia.porDefecto();
        Map<String, Apariencia> variantes = new LinkedHashMap<>();
        variantes.put("1-claro", d);
        variantes.put("2-oscuro", d.conTema(Apariencia.Tema.OSCURO));
        variantes.put("3-alto-contraste", d.conTema(Apariencia.Tema.ALTO_CONTRASTE));
        variantes.put("4-vidrio-claro", d.conVidrio(true).conAcento("#6B4BA8"));
        variantes.put("5-vidrio-oscuro", d.conTema(Apariencia.Tema.OSCURO).conVidrio(true).conAcento("#0F7C86"));
        variantes.put("6-grande-atkinson-verde", d.conEscala(115).conLetra(Apariencia.Letra.ATKINSON).conAcento("#2E7D4F"));
        variantes.put("7-muy-grande-phosphor", d.conEscala(130).conPaquete("phosphor", "duotono").conAcento("#C25E12"));
        variantes.put("8-material-relleno-naranja-oscuro", d.conTema(Apariencia.Tema.OSCURO).conPaquete("material", "relleno").conAcento("#F2C94C"));

        for (Map.Entry<String, Apariencia> v : variantes.entrySet()) {
            Path dir = base.resolve(v.getKey());
            Files.createDirectories(dir);
            enFx(() -> AplicadorApariencia.aplicar(v.getValue()));
            sesionService.cerrarSesion();
            capturarFxml("/fxml/login.fxml", 520, 640, dir.resolve("login.png"));
            sesionService.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);
            capturarFxml("/fxml/main.fxml", 1024, 728, dir.resolve("principal-ventas.png"));
            capturarFxml("/fxml/productos.fxml", 1024, 640, dir.resolve("productos.png"));
            capturarFxml("/fxml/inventario.fxml", 1024, 640, dir.resolve("inventario.png"));
            capturarFxml("/fxml/estadisticas.fxml", 1024, 640, dir.resolve("estadisticas.png"));
            capturarFxml("/fxml/corte.fxml", 1024, 640, dir.resolve("corte.png"));
            capturarFxml("/fxml/configuracion.fxml", 1024, 640, dir.resolve("configuracion.png"));
            capturar(() -> aparienciaDialogo.crear(null), dir.resolve("apariencia.png"));
            capturar(() -> {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION);
                a.setTitle("Confirmar");
                a.setHeaderText("¿Eliminar el producto?");
                a.setContentText("Esta acción no se puede deshacer.");
                Ventanas.estilizar(a);
                Stage s = (Stage) a.getDialogPane().getScene().getWindow();
                return s;
            }, dir.resolve("dialogo.png"));
        }
        // La hoja de la apariencia de verdad gana a app.css (declaraciones !important en la escena)
        enFx(() -> AplicadorApariencia.aplicar(Apariencia.porDefecto().conTema(Apariencia.Tema.OSCURO)));
        AtomicReference<Object> fondo = new AtomicReference<>();
        conEscena("/fxml/configuracion.fxml", escena -> {
            Region r = (Region) escena.getRoot();
            r.applyCss();
            fondo.set(r.getBackground() == null ? null : r.getBackground().getFills().get(0).getFill());
        });
        enFx(() -> AplicadorApariencia.aplicar(Apariencia.porDefecto()));
        sesionService.cerrarSesion();
        Files.writeString(base.resolve("medidas.txt"), medidas.toString());
        System.out.println(medidas);
        System.out.println("Capturas en " + base.toAbsolutePath() + " fondo oscuro=" + fondo.get());
        assertThat(fondo.get()).isEqualTo(Color.web("#14181E"));
    }

    /**
     * La maqueta de la ventana de Apariencia con el programa en Claro: cada tema elegido debe verse
     * con su propio fondo y texto (oscuro, alto contraste, vidrio) y sin nombres recortados.
     */
    @Test
    void vistaPreviaDeCadaTema() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "vista-previa");
        Files.createDirectories(dir);
        arrancarJavaFx();
        enFx(() -> AplicadorApariencia.aplicar(Apariencia.porDefecto()));
        Apariencia d = Apariencia.porDefecto();
        Map<String, Apariencia> variantes = new LinkedHashMap<>();
        variantes.put("claro", d);
        variantes.put("oscuro", d.conTema(Apariencia.Tema.OSCURO));
        variantes.put("alto-contraste", d.conTema(Apariencia.Tema.ALTO_CONTRASTE));
        variantes.put("vidrio-oscuro", d.conTema(Apariencia.Tema.OSCURO).conVidrio(true).conAcento("#0F7C86"));
        variantes.put("oscuro-letra130", d.conTema(Apariencia.Tema.OSCURO).conEscala(130));
        StringBuilder recortes = new StringBuilder();
        for (Map.Entry<String, Apariencia> v : variantes.entrySet()) {
            capturar(() -> {
                VistaPreviaApariencia vista = new VistaPreviaApariencia();
                vista.mostrar(v.getValue());
                Scene escena = new Scene(new javafx.scene.layout.StackPane(vista), 560, 470);
                Ventanas.aplicarCss(escena);
                Stage s = new Stage();
                s.setScene(escena);
                // Tras el primer pase de estilos: ¿algún texto del ticket de muestra con «…»?
                javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(() -> {
                    for (javafx.scene.Node n : vista.lookupAll(".table-cell")) {
                        if (n instanceof javafx.scene.control.Labeled l) {
                            String visto = CapturasBarrasTest.textoMostrado(l);
                            if (visto != null) recortes.append(v.getKey()).append(": «").append(l.getText()).append("» → «").append(visto).append("»\n");
                        }
                    }
                }));
                return s;
            }, dir.resolve(v.getKey() + ".png"));
        }
        Files.writeString(dir.resolve("recortes.txt"), recortes.toString());
        System.out.println("Vista previa, recortes:\n" + recortes);
    }

    private void conEscena(String fxml, java.util.function.Consumer<Scene> accion) throws Exception {
        CountDownLatch listo = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            try {
                FXMLLoader loader = new FXMLLoader(CapturasAparienciaTest.class.getResource(fxml));
                loader.setControllerFactory(springContext::getBean);
                Parent raiz = loader.load();
                Scene escena = new Scene(raiz, 800, 600);
                Ventanas.aplicarCss(escena);
                stage.setScene(escena);
                stage.show();
                accion.accept(escena);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                stage.close();
                listo.countDown();
            }
        });
        assertThat(listo.await(30, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private void capturarFxml(String fxml, double ancho, double alto, Path destino) throws Exception {
        capturar(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(CapturasAparienciaTest.class.getResource(fxml));
                loader.setControllerFactory(springContext::getBean);
                Parent raiz = loader.load();
                Scene escena = new Scene(raiz, ancho, alto);
                Ventanas.aplicarCss(escena);
                Stage stage = new Stage();
                stage.setScene(escena);
                return stage;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, destino);
    }

    private void capturar(Supplier<Stage> crear, Path destino) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch listo = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Stage stage = crear.get();
                stage.show();
                PauseTransition espera = new PauseTransition(Duration.millis(900));
                espera.setOnFinished(e -> {
                    try {
                        Scene escena = stage.getScene();
                        long t0 = System.nanoTime();
                        escena.getRoot().applyCss();
                        escena.getRoot().layout();
                        WritableImage img = escena.snapshot(null);
                        long ms = (System.nanoTime() - t0) / 1_000_000;
                        medidas.append(String.format("%-60s %4.0f×%-4.0f snapshot %d ms  prefAncho %.0f%n",
                                destino.getParent().getFileName() + "/" + destino.getFileName(), escena.getWidth(), escena.getHeight(),
                                ms, escena.getRoot().prefWidth(-1)));
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
        assertThat(listo.await(40, TimeUnit.SECONDS)).as("captura " + destino).isTrue();
        if (error.get() != null) {
            System.err.println("No se pudo capturar " + destino + ": " + error.get());
            error.get().printStackTrace();
        }
    }

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
