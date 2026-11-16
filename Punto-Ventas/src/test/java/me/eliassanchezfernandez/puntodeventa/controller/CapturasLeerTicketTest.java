package me.eliassanchezfernandez.puntodeventa.controller;

import atlantafx.base.theme.NordLight;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas de "Leer ticket de proveedor" a 1024×768 (área visible 1024×728). Solo a petición:
 * <pre>APP_CAPTURAS=true APP_CAPTURAS_DIR=/ruta [APP_CAPTURAS_FOTO=/ruta/foto.jpg] ./gradlew test --tests '*CapturasLeerTicketTest'</pre>
 * La foto es opcional y nunca se versiona (las fotos reales traen datos de clientes).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS", matches = "true")
class CapturasLeerTicketTest {

    @Autowired private SesionService sesionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private ProveedorService proveedorService;
    @Autowired private LeerTicketDialogo dialogo;

    @Test
    void capturasDelLectorDeTickets() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "leer-ticket");
        Files.createDirectories(dir);
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);
        sesionService.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);

        String sufijo = UUID.randomUUID().toString().substring(0, 4);
        try {
            proveedorService.guardar(null, "Distribuidora de Dulces y Chicles Ramírez " + sufijo, null, null, null, Set.of(), null);
        } catch (RuntimeException ignorada) { /* ya existía */ }
        producto("Chicle Orbit surtido " + sufijo, 7.5, 10);
        producto("Mazapán gigante " + sufijo, 3.2, 5);

        String texto;
        try (InputStream in = getClass().getResourceAsStream("/tickets-proveedor/t06.txt")) {
            texto = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String rutaFoto = System.getenv("APP_CAPTURAS_FOTO");
        byte[] foto = rutaFoto == null ? null : Files.readAllBytes(Path.of(rutaFoto));

        arrancarJavaFx();
        enFx(() -> Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()));
        capturar(() -> dialogo.abrirParaCaptura(), dir.resolve("1-elegir-foto.png"));
        capturar(() -> dialogo.abrirEnRevisionParaCaptura(texto, foto), dir.resolve("2-revision.png"));
        capturar(() -> {
            var res = new TicketProveedorService.Resultado(1L, 3, 3, 324, List.of(
                    TicketProveedorService.cambioCosto(1L, "Chicle Orbit surtido", 7.5, 8.9, 10),
                    TicketProveedorService.cambioCosto(2L, "Mazapán gigante", 3.2, 3.63, 5)),
                    List.of(TicketProveedorService.cambioCosto(3L, "Mazapán de chocolate", 4.4, 4.2, 6)), List.of(), true, 0);
            Stage s = new Stage();
            Scene escena = new Scene(new StackPane(dialogo.contenidoResultado(res)), 860, 420);
            Ventanas.aplicarCss(escena);
            s.setScene(escena);
            s.show();
            return s;
        }, dir.resolve("3-subieron-de-costo.png"));
        sesionService.cerrarSesion();
        System.out.println("Capturas en " + dir.toAbsolutePath());
    }

    private void producto(String nombre, double costo, double precio) {
        Producto p = new Producto();
        p.setCodigoBarras("CAP-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioCosto(costo);
        p.setPrecioVenta(precio);
        p.setUsaInventario(true);
        p.setActivo(true);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        productoRepo.save(p);
    }

    private void capturar(Supplier<Stage> abrir, Path destino) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch listo = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Stage stage = abrir.get();
                stage.setWidth(1024);
                stage.setHeight(728);
                PauseTransition espera = new PauseTransition(Duration.millis(900));
                espera.setOnFinished(e -> {
                    try {
                        guardarPng(stage.getScene().snapshot(null), destino);
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
        assertThat(listo.await(30, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError("No se pudo capturar " + destino, error.get());
    }

    private static void guardarPng(WritableImage img, Path destino) throws Exception {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        ImageIO.write(bi, "png", destino.toFile());
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
