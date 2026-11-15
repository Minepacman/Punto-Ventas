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
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
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
 * Captura de la pestaña Estadísticas (vista «Hoy» arriba y «Más detalles»
 * cerrado) con ventas de muestra sobre la BD desechable de pruebas. Solo corre
 * a petición, porque necesita una pantalla:
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

            arrancarJavaFx();
            enFx(() -> Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()));
            capturar("/fxml/estadisticas.fxml", 1008, 640, dir.resolve("estadisticas-hoy.png"));
        } finally {
            ventaRepo.deleteAll(sembradas);
            productoRepo.delete(p);
            sesionService.cerrarSesion();
        }
        assertThat(dir.resolve("estadisticas-hoy.png")).exists();
        System.out.println("Captura en " + dir.resolve("estadisticas-hoy.png").toAbsolutePath());
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

    private void capturar(String fxml, double ancho, double alto, Path destino) throws Exception {
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
                // Las consultas corren en hilos aparte: un respiro para que pinten
                PauseTransition espera = new PauseTransition(Duration.millis(3000));
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
        assertThat(listo.await(40, TimeUnit.SECONDS)).as("captura de " + fxml).isTrue();
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
