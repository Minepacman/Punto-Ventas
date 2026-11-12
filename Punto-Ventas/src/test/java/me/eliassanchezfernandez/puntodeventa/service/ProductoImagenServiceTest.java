package me.eliassanchezfernandez.puntodeventa.service;

import com.sun.net.httpserver.HttpServer;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoImagen;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoImagenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Imágenes de producto: normalización (reducción, aspecto, blanco bajo la
 * transparencia, orientación EXIF), rechazo de lo que no es imagen o pesa de
 * más, permiso, ida y vuelta en la base, borrado con el producto y descarga
 * por URL con tope de tamaño.
 */
public class ProductoImagenServiceTest extends BaseServicioTest {

    @Autowired private ProductoImagenService imagenService;
    @Autowired private ProductoImagenRepository imagenRepo;
    @Autowired private AuditoriaRepository auditoriaRepo;

    // ── Imágenes de prueba ────────────────────────────────────────────────

    /** PNG con transparencia: la mitad izquierda roja opaca, la derecha totalmente transparente */
    public static byte[] png(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.dispose();
        return codificar(img, "png");
    }

    public static byte[] jpg(int w, int h, Color color) {
        return jpg(w, h, color, color);
    }

    /** JPEG con la mitad izquierda de un color y la derecha de otro */
    static byte[] jpg(int w, int h, Color izquierda, Color derecha) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(izquierda);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(derecha);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return codificar(img, "jpeg");
    }

    private static byte[] codificar(BufferedImage img, String formato) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, formato, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inserta un segmento APP1 Exif (big-endian) con la etiqueta Orientation justo después del SOI */
    static byte[] conOrientacionExif(byte[] jpeg, int orientacion) {
        byte[] tiff = {
                'M', 'M', 0, 0x2A, 0, 0, 0, 8,          // cabecera TIFF, IFD0 en el desplazamiento 8
                0, 1,                                   // una entrada
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientacion, 0, 0,   // Orientation, SHORT, 1 valor
                0, 0, 0, 0                              // sin más IFD
        };
        byte[] exif = new byte[6 + tiff.length];
        System.arraycopy("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), 0, exif, 0, 6);
        System.arraycopy(tiff, 0, exif, 6, tiff.length);
        int largo = exif.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);
        out.write(0xFF); out.write(0xE1);
        out.write((largo >> 8) & 0xFF); out.write(largo & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private int rgb(byte[] jpeg, int x, int y) throws IOException {
        return ImageIO.read(new java.io.ByteArrayInputStream(jpeg)).getRGB(x, y) & 0xFFFFFF;
    }

    // ── Normalización ─────────────────────────────────────────────────────

    @Test
    void reduceA512ConservandoElAspectoYGeneraMiniatura() {
        ProductoImagenService.Normalizada n = ProductoImagenService.normalizar(png(2000, 1000));

        assertThat(n.ancho()).isEqualTo(512);
        assertThat(n.alto()).isEqualTo(256);
        assertThat(ProductoImagenService.dimensiones(n.imagen())).containsExactly(512, 256);
        int[] mini = ProductoImagenService.dimensiones(n.miniatura());
        assertThat(mini[0]).isEqualTo(96);
        assertThat(mini[1]).isEqualTo(48);
        assertThat(n.imagen().length).isLessThan(60 * 1024);
        assertThat(n.miniatura().length).isLessThan(n.imagen().length);
        assertThat(n.sha256()).hasSize(64).isEqualTo(ProductoImagenService.sha256Hex(n.imagen()));
        // JPEG: empieza con FF D8
        assertThat(n.imagen()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(n.imagen()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void noAgrandaLasImagenesPequenasYAplanaLaTransparenciaSobreBlanco() throws IOException {
        ProductoImagenService.Normalizada n = ProductoImagenService.normalizar(png(200, 100));

        assertThat(n.ancho()).isEqualTo(200);
        assertThat(n.alto()).isEqualTo(100);
        int izquierda = rgb(n.imagen(), 50, 50);
        int derecha = rgb(n.imagen(), 150, 50);
        assertThat((izquierda >> 16) & 0xFF).isGreaterThan(200);   // rojo
        assertThat(izquierda & 0xFF).isLessThan(60);
        assertThat(derecha).isEqualTo(0xFFFFFF);                   // lo transparente queda blanco
    }

    @Test
    void respetaLaOrientacionExifDeLasFotosDeTelefono() throws IOException {
        // Rojo a la izquierda, azul a la derecha; orientación 6 = girar 90° en sentido horario
        byte[] foto = conOrientacionExif(jpg(300, 100, Color.RED, Color.BLUE), 6);
        assertThat(ProductoImagenService.orientacionExif(foto)).isEqualTo(6);
        assertThat(ProductoImagenService.orientacionExif(jpg(300, 100, Color.BLUE))).isEqualTo(1);
        assertThat(ProductoImagenService.orientacionExif(png(10, 10))).isEqualTo(1);

        ProductoImagenService.Normalizada n = ProductoImagenService.normalizar(foto);
        assertThat(n.ancho()).isEqualTo(100);
        assertThat(n.alto()).isEqualTo(300);
        int arriba = rgb(n.imagen(), 50, 40), abajo = rgb(n.imagen(), 50, 260);
        assertThat((arriba >> 16) & 0xFF).as("el rojo (izquierda) queda arriba").isGreaterThan(180);
        assertThat(abajo & 0xFF).as("el azul (derecha) queda abajo").isGreaterThan(180);

        // Orientación 8 = 90° antihorario: el rojo queda abajo
        ProductoImagenService.Normalizada n8 = ProductoImagenService.normalizar(
                conOrientacionExif(jpg(300, 100, Color.RED, Color.BLUE), 8));
        assertThat((rgb(n8.imagen(), 50, 260) >> 16) & 0xFF).isGreaterThan(180);
        // Orientación 3 = 180°: el rojo pasa a la derecha
        ProductoImagenService.Normalizada n3 = ProductoImagenService.normalizar(
                conOrientacionExif(jpg(300, 100, Color.RED, Color.BLUE), 3));
        assertThat(n3.ancho()).isEqualTo(300);
        assertThat((rgb(n3.imagen(), 250, 50) >> 16) & 0xFF).isGreaterThan(180);
    }

    @Test
    void reduceSinPixelarPromediandoElArea() throws IOException {
        // Tablero fino de 1 px (negro/blanco) de 1500×1500: al reducir a 512 debe quedar gris medio, no ruido
        BufferedImage img = new BufferedImage(1500, 1500, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 1500; y++) for (int x = 0; x < 1500; x++) img.setRGB(x, y, ((x + y) & 1) == 0 ? 0 : 0xFFFFFF);
        ProductoImagenService.Normalizada n = ProductoImagenService.normalizar(codificar(img, "png"));
        assertThat(n.ancho()).isEqualTo(512);
        int p = rgb(n.imagen(), 256, 256);
        assertThat((p >> 16) & 0xFF).isBetween(100, 156);
    }

    @Test
    void rechazaLoQueNoEsImagen() {
        assertThatThrownBy(() -> ProductoImagenService.normalizar("hola mundo".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no es una imagen");
        assertThatThrownBy(() -> ProductoImagenService.normalizar(new byte[0]))
                .isInstanceOf(NegocioException.class);
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> ProductoImagenService.normalizar(webp))
                .isInstanceOf(NegocioException.class).hasMessageContaining("WEBP");
        // JPEG truncado
        byte[] roto = java.util.Arrays.copyOf(jpg(300, 300, Color.GREEN), 40);
        assertThatThrownBy(() -> ProductoImagenService.normalizar(roto))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no es una imagen");
    }

    @Test
    void rechazaArchivosDeMasDe10Mb(@TempDir Path dir) throws IOException {
        Path grande = dir.resolve("grande.jpg");
        try (var canal = java.nio.channels.FileChannel.open(grande, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE)) {
            canal.write(java.nio.ByteBuffer.wrap(new byte[] {1}), ProductoImagenService.TAMANO_MAXIMO_BYTES);  // archivo disperso
        }
        assertThat(Files.size(grande)).isEqualTo(ProductoImagenService.TAMANO_MAXIMO_BYTES + 1);

        assertThatThrownBy(() -> ProductoImagenService.leerArchivo(grande))
                .isInstanceOf(NegocioException.class).hasMessageContaining("10 MB");
        assertThatThrownBy(() -> ProductoImagenService.leerArchivo(dir.resolve("no-existe.png")))
                .isInstanceOf(NegocioException.class);
    }

    // ── Guardar / quitar / permisos ──────────────────────────────────────

    @Test
    void guardaReemplazaYQuitaConBitacora() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Sopa", 12, 5);
        assertThat(imagenService.tieneImagen(p.getId())).isFalse();
        assertThat(imagenService.imagen(p.getId())).isEmpty();

        ProductoImagen guardada = imagenService.guardar(p.getId(), png(1000, 1000), "archivo sopa.png");
        assertThat(guardada.getProductoId()).isEqualTo(p.getId());
        assertThat(guardada.getMime()).isEqualTo("image/jpeg");
        assertThat(guardada.getAncho()).isEqualTo(512);
        assertThat(guardada.getActualizado()).isNotNull();
        assertThat(imagenService.tieneImagen(p.getId())).isTrue();
        assertThat(imagenService.imagen(p.getId())).get().isEqualTo(guardada.getImagen());
        assertThat(imagenService.miniatura(p.getId())).get().isEqualTo(guardada.getMiniatura());
        assertThat(imagenService.idsConImagen(List.of(p.getId(), -5L))).isEqualTo(Set.of(p.getId()));
        String sha1 = guardada.getSha256();

        ProductoImagen otra = imagenService.guardar(p.getId(), jpg(300, 200, Color.ORANGE), "portapapeles");
        assertThat(otra.getSha256()).isNotEqualTo(sha1);
        assertThat(otra.getAncho()).isEqualTo(300);
        assertThat(imagenRepo.count()).isEqualTo(1);

        imagenService.quitar(p.getId());
        assertThat(imagenService.tieneImagen(p.getId())).isFalse();
        assertThatThrownBy(() -> imagenService.quitar(p.getId()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no tiene imagen");

        assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("PRODUCTO_IMAGEN_AGREGADA"))
                .extracting(Auditoria::getDetalle).anyMatch(d -> d.contains("sopa.png") && d.contains("512"));
        assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("PRODUCTO_IMAGEN_CAMBIADA"))
                .extracting(Auditoria::getDetalle).anyMatch(d -> d.contains("portapapeles"));
        assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("PRODUCTO_IMAGEN_QUITADA"))
                .extracting(Auditoria::getDetalle).anyMatch(d -> d.contains(p.getCodigoBarras()));
    }

    @Test
    void guardaDesdeArchivoYRechazaProductoInexistente(@TempDir Path dir) throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Galletas", 15, 5);
        Path archivo = dir.resolve("galletas.png");
        Files.write(archivo, png(640, 480));

        ProductoImagen g = imagenService.guardarDesdeArchivo(p.getId(), archivo);
        assertThat(g.getAncho()).isEqualTo(512);
        assertThat(g.getAlto()).isEqualTo(384);

        assertThatThrownBy(() -> imagenService.guardar(999_999L, png(10, 10), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya no existe");
    }

    @Test
    void exigeElPermisoDeEditarProductosParaCambiarPeroNoParaVer() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Pan", 8, 5);
        imagenService.guardar(p.getId(), png(100, 100), null);

        iniciarComoCajero("cajero_img", Set.of());
        assertThatThrownBy(() -> imagenService.guardar(p.getId(), png(50, 50), null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> imagenService.quitar(p.getId()))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> imagenService.guardarDesdeUrl(p.getId(), "https://ejemplo.com/x.png"))
                .isInstanceOf(PermisoRequeridoException.class);
        // Ventas (F6) la necesita ver aunque no pueda editar
        assertThat(imagenService.miniatura(p.getId())).isPresent();
        assertThat(imagenService.imagen(p.getId())).isPresent();

        iniciarComoCajero("editor_img", Set.of(Permiso.EDITAR_PRODUCTOS));
        imagenService.guardar(p.getId(), png(50, 50), null);
        imagenService.quitar(p.getId());
    }

    @Test
    void laImagenSeBorraConElProducto() throws Exception {
        iniciarComoAdmin();
        Producto p = new Producto();
        p.setCodigoBarras("IMG-" + java.util.UUID.randomUUID().toString().substring(0, 6));
        p.setDescripcion("Sin movimientos");
        p.setPrecioVenta(5);
        p.setUsaInventario(false);
        p = productoService.guardar(p);
        imagenService.guardar(p.getId(), png(80, 80), null);
        assertThat(imagenRepo.existsById(p.getId())).isTrue();

        productoService.eliminar(p.getId());

        assertThat(imagenRepo.existsById(p.getId())).isFalse();
        assertThat(productoRepo.existsById(p.getId())).isFalse();
    }

    // ── Descarga por URL ──────────────────────────────────────────────────

    @Test
    void descargaDesdeUrlConTopeDeTamanoYErroresClaros() throws Exception {
        byte[] imagen = png(400, 300);
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/foto.png", ex -> {
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, imagen.length);
            ex.getResponseBody().write(imagen);
            ex.close();
        });
        servidor.createContext("/redirige", ex -> {
            ex.getResponseHeaders().set("Location", "/foto.png");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        servidor.createContext("/enorme", ex -> {
            ex.sendResponseHeaders(200, ProductoImagenService.TAMANO_MAXIMO_BYTES + 1);   // solo anuncia el tamaño
            ex.getResponseBody().write(new byte[1024]);
            ex.close();
        });
        servidor.createContext("/no", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
        servidor.start();
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        ProductoImagenService.permitirRedLocalParaPruebas = true;   // en producción solo https a internet
        try {
            assertThat(imagenService.descargarDesdeUrl(base + "/foto.png")).isEqualTo(imagen);
            assertThat(imagenService.descargarDesdeUrl(base + "/redirige")).isEqualTo(imagen);
            assertThatThrownBy(() -> imagenService.descargarDesdeUrl(base + "/enorme"))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("10 MB");
            assertThatThrownBy(() -> imagenService.descargarDesdeUrl(base + "/no"))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("404");
            assertThatThrownBy(() -> imagenService.descargarDesdeUrl("ftp://x/y.png"))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("https://");
            assertThatThrownBy(() -> imagenService.descargarDesdeUrl("   "))
                    .isInstanceOf(NegocioException.class);
            assertThatThrownBy(() -> imagenService.descargarDesdeUrl("no es una url"))
                    .isInstanceOf(NegocioException.class);

            iniciarComoAdmin();
            Producto p = producto("Jugo", 20, 5);
            ProductoImagen g = imagenService.guardarDesdeUrl(p.getId(), base + "/foto.png");
            assertThat(g.getAncho()).isEqualTo(400);
            assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("PRODUCTO_IMAGEN_AGREGADA"))
                    .extracting(Auditoria::getDetalle).anyMatch(d -> d.contains("URL " + base));
        } finally {
            ProductoImagenService.permitirRedLocalParaPruebas = false;
            servidor.stop(0);
        }
    }

    /**
     * Protección contra SSRF: la URL la escribe un usuario y la descarga la hace la caja desde la
     * red de la tienda. Solo https, y nunca hacia direcciones locales/privadas (ni por redirección).
     */
    @Test
    void laDescargaPorUrlSoloAceptaHttpsHaciaInternetYRevisaLasRedirecciones() throws Exception {
        assertThat(ProductoImagenService.permitirRedLocalParaPruebas).isFalse();
        // http:// ya no se acepta
        assertThatThrownBy(() -> imagenService.descargarDesdeUrl("http://example.com/foto.png"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("https://");
        // Direcciones de esta red / loopback / enlace local / metadatos de nube
        for (String url : List.of("https://127.0.0.1/foto.png", "https://localhost/foto.png", "https://[::1]/foto.png",
                "https://10.0.0.5/foto.png", "https://192.168.1.1/foto.png", "https://172.16.0.1/foto.png",
                "https://169.254.169.254/latest/meta-data", "https://0.0.0.0/foto.png", "https://100.64.0.1/x.png",
                "https://servidor.local/foto.png", "https://usuario:clave@example.com/foto.png")) {
            assertThatThrownBy(() -> imagenService.descargarDesdeUrl(url)).as(url).isInstanceOf(NegocioException.class);
        }
        // La función pura que decide qué direcciones son públicas
        assertThat(ProductoImagenService.esDireccionPublica(java.net.InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(ProductoImagenService.esDireccionPublica(java.net.InetAddress.getByName("2606:4700::1111"))).isTrue();
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "172.31.255.255", "192.168.0.10", "169.254.1.1", "0.0.0.0",
                "224.0.0.1", "100.100.1.1", "::1", "fc00::1", "fe80::1", "::ffff:192.168.1.1", "::ffff:127.0.0.1")) {
            assertThat(ProductoImagenService.esDireccionPublica(java.net.InetAddress.getByName(ip))).as(ip).isFalse();
        }

        // Un destino de redirección se revisa igual que la URL original; además hay tope de saltos
        assertThatThrownBy(() -> ProductoImagenService.validarHostPublico("169.254.169.254"))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> ProductoImagenService.validarUrl("https://192.168.1.20/x.png"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("misma red");
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/bucle", ex -> {
            ex.getResponseHeaders().set("Location", "/bucle");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        servidor.start();
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        ProductoImagenService.permitirRedLocalParaPruebas = true;
        try {
            assertThatThrownBy(() -> imagenService.descargarDesdeUrl(base + "/bucle"))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("redirige");
        } finally {
            ProductoImagenService.permitirRedLocalParaPruebas = false;
            servidor.stop(0);
        }
    }
}
