package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoImagenService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoImagenServiceTest;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/productos/{id}/imagen del acceso móvil: misma sesión Bearer que el
 * resto, JPEG con ETag (304 si el teléfono ya la tiene), miniatura con ?mini=1,
 * 404 sin imagen, y la bandera "imagen" en /api/inventario.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilImagenTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProductoService productoService;
    @Autowired private ProductoImagenService imagenService;

    private final HttpClient http = HttpClient.newHttpClient();
    private String base;
    private Producto conFoto;
    private Producto sinFoto;

    @BeforeEach
    void arrancar() throws Exception {
        servidor.iniciar(0);
        base = "http://127.0.0.1:" + servidor.getPuerto();
        Cajero a = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        a.setDebeCambiarContrasena(false);
        a.setBloqueadoHasta(null);
        a.setIntentosFallidos(0);
        cajeroRepo.save(a);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");
        String marca = UUID.randomUUID().toString().substring(0, 6);
        conFoto = nuevoProducto("Yogur " + marca);
        sinFoto = nuevoProducto("Yogur natural " + marca);
        imagenService.guardar(conFoto.getId(), ProductoImagenServiceTest.jpg(800, 400, Color.CYAN), "prueba");
    }

    @AfterEach
    void detener() {
        servidor.detener();
        sesionService.cerrarSesion();
    }

    private Producto nuevoProducto(String nombre) {
        Producto p = new Producto();
        p.setCodigoBarras("MI-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioCosto(5);
        p.setPrecioVenta(10);
        p.setUsaInventario(true);
        p.setCantidadActual(4);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    private String token() throws Exception {
        String login = "mi" + UUID.randomUUID().toString().substring(0, 8);
        Cajero c = cajeroService.crear(login, "Móvil " + login, CLAVE, Cajero.Rol.CAJERO, Set.of());
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/api/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("usuario", login, "contrasena", CLAVE, "recordar", false))))
                .build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return JSON.readTree(resp.body()).get("token").asText();
    }

    private HttpResponse<byte[]> get(String ruta, String token, String ifNoneMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (ifNoneMatch != null) b.header("If-None-Match", ifNoneMatch);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void sirveLaImagenConEtagYMiniatura() throws Exception {
        String t = token();
        HttpResponse<byte[]> r = get("/api/productos/" + conFoto.getId() + "/imagen", t, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(r.headers().firstValue("Cache-Control")).contains("private, max-age=86400");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        String etag = r.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).startsWith("\"i-");
        assertThat(r.body()).isEqualTo(imagenService.imagen(conFoto.getId()).orElseThrow());
        assertThat(ProductoImagenService.dimensiones(r.body())).containsExactly(512, 256);

        // El teléfono ya la tiene: 304 sin cuerpo
        HttpResponse<byte[]> cacheada = get("/api/productos/" + conFoto.getId() + "/imagen", t, etag);
        assertThat(cacheada.statusCode()).isEqualTo(304);
        assertThat(cacheada.body()).isEmpty();

        HttpResponse<byte[]> mini = get("/api/productos/" + conFoto.getId() + "/imagen?mini=1", t, null);
        assertThat(mini.statusCode()).isEqualTo(200);
        assertThat(mini.headers().firstValue("ETag").orElseThrow()).startsWith("\"m-").isNotEqualTo(etag);
        assertThat(ProductoImagenService.dimensiones(mini.body())).containsExactly(96, 48);
        assertThat(mini.body().length).isLessThan(r.body().length);
    }

    @Test
    void exigeSesionYResponde404SinImagenO405ConOtroMetodo() throws Exception {
        String t = token();
        assertThat(get("/api/productos/" + conFoto.getId() + "/imagen", null, null).statusCode()).isEqualTo(401);
        assertThat(get("/api/productos/" + conFoto.getId() + "/imagen", "token-falso", null).statusCode()).isEqualTo(401);

        HttpResponse<byte[]> sin = get("/api/productos/" + sinFoto.getId() + "/imagen", t, null);
        assertThat(sin.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(sin.body()).get("codigo").asText()).isEqualTo("SIN_IMAGEN");
        assertThat(get("/api/productos/999999999/imagen", t, null).statusCode()).isEqualTo(404);
        assertThat(get("/api/productos/abc/imagen", t, null).statusCode()).isEqualTo(404);

        HttpRequest post = HttpRequest.newBuilder(URI.create(base + "/api/productos/" + conFoto.getId() + "/imagen"))
                .header("Authorization", "Bearer " + t)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
    }

    @Test
    void elInventarioDiceQueProductosTienenImagen() throws Exception {
        String t = token();
        HttpResponse<byte[]> r = get("/api/inventario?q=" + java.net.URLEncoder.encode("Yogur", "UTF-8"), t, null);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode productos = JSON.readTree(r.body()).get("productos");
        boolean conBandera = false, sinBandera = false;
        for (JsonNode p : productos) {
            if (p.get("id").asLong() == conFoto.getId()) conBandera = p.get("imagen").asBoolean();
            if (p.get("id").asLong() == sinFoto.getId()) sinBandera = !p.get("imagen").asBoolean();
        }
        assertThat(conBandera).as("producto con foto marcado").isTrue();
        assertThat(sinBandera).as("producto sin foto no marcado").isTrue();
        // Nunca viajan los bytes dentro del JSON de la lista
        assertThat(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("base64");
    }

    @Test
    void idDeRutaImagenSoloAceptaLaRutaExacta() {
        assertThat(ServidorMovilService.idDeRutaImagen("/api/productos/12/imagen")).isEqualTo(12L);
        assertThat(ServidorMovilService.idDeRutaImagen("/api/productos/12/imagen/")).isNull();
        assertThat(ServidorMovilService.idDeRutaImagen("/api/productos//imagen")).isNull();
        assertThat(ServidorMovilService.idDeRutaImagen("/api/productos/-1/imagen")).isNull();
        assertThat(ServidorMovilService.idDeRutaImagen("/api/inventario")).isNull();
    }
}
