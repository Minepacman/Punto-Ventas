package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceso móvil: recorrido guiado marcado POR USUARIO en el servidor
 * (/api/tutorial, columna cajeros.tutorial_movil_visto) y archivos estáticos
 * del icono oficial + manifiesto para "Agregar a pantalla de inicio".
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilTutorialEstaticosTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ConfiguracionService configuracionService;

    private final HttpClient http = HttpClient.newHttpClient();
    private String base;

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
    }

    @AfterEach
    void detener() {
        servidor.detener();
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private record Usuario(String login, String token) { }

    private Usuario usuario() throws Exception {
        String login = "mt" + UUID.randomUUID().toString().substring(0, 8);
        Cajero c = cajeroService.crear(login, "Móvil " + login, CLAVE, Cajero.Rol.CAJERO, Set.of());
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        HttpResponse<String> resp = enviar("POST", "/api/login",
                JSON.writeValueAsString(Map.of("usuario", login, "contrasena", CLAVE, "recordar", false)), null);
        assertThat(resp.statusCode()).isEqualTo(200);
        return new Usuario(login, JSON.readTree(resp.body()).get("token").asText());
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String cuerpoJson, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (cuerpoJson != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpoJson == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpoJson));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> bytes(String metodo, String ruta, String ifNoneMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).method(metodo, HttpRequest.BodyPublishers.noBody());
        if (ifNoneMatch != null) b.header("If-None-Match", ifNoneMatch);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private boolean vistoSegunApi(String token) throws Exception {
        HttpResponse<String> r = enviar("GET", "/api/tutorial", null, token);
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body()).get("visto").asBoolean();
    }

    // ── Tutorial ──────────────────────────────────────────────────────────

    @Test
    void elRecorridoSeMarcaPorUsuarioYQuedaEnLaBd() throws Exception {
        Usuario a = usuario();
        Usuario b = usuario();

        assertThat(vistoSegunApi(a.token())).isFalse();
        assertThat(vistoSegunApi(b.token())).isFalse();

        HttpResponse<String> marcar = enviar("POST", "/api/tutorial/visto", "{}", a.token());   // sin campo = visto
        assertThat(marcar.statusCode()).isEqualTo(200);
        JsonNode j = JSON.readTree(marcar.body());
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("visto").asBoolean()).isTrue();

        assertThat(vistoSegunApi(a.token())).isTrue();
        assertThat(vistoSegunApi(b.token())).as("la marca es por usuario, no global").isFalse();
        assertThat(cajeroRepo.findByUsuarioIgnoreCase(a.login()).orElseThrow().isTutorialMovilVisto()).isTrue();
        assertThat(cajeroRepo.findByUsuarioIgnoreCase(b.login()).orElseThrow().isTutorialMovilVisto()).isFalse();
        assertThat(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow().isTutorialMovilVisto())
                .as("la sesión de escritorio (admin) no se toca").isFalse();

        // Idempotente y reversible
        assertThat(enviar("POST", "/api/tutorial/visto", "{\"visto\":true}", a.token()).statusCode()).isEqualTo(200);
        assertThat(vistoSegunApi(a.token())).isTrue();
        HttpResponse<String> reiniciar = enviar("POST", "/api/tutorial/visto", "{\"visto\":false}", a.token());
        assertThat(reiniciar.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(reiniciar.body()).get("visto").asBoolean()).isFalse();
        assertThat(vistoSegunApi(a.token())).isFalse();
        assertThat(cajeroRepo.findByUsuarioIgnoreCase(a.login()).orElseThrow().isTutorialMovilVisto()).isFalse();
    }

    @Test
    void elTutorialExigeSesionYMetodoCorrecto() throws Exception {
        assertThat(enviar("GET", "/api/tutorial", null, null).statusCode()).isEqualTo(401);
        assertThat(enviar("GET", "/api/tutorial", null, "token-falso").statusCode()).isEqualTo(401);
        assertThat(enviar("POST", "/api/tutorial/visto", "{}", null).statusCode()).isEqualTo(401);
        assertThat(enviar("POST", "/api/tutorial/visto", "{}", "token-falso").statusCode()).isEqualTo(401);
        Usuario a = usuario();
        assertThat(enviar("POST", "/api/tutorial", "{}", a.token()).statusCode()).isEqualTo(405);
        assertThat(enviar("GET", "/api/tutorial/visto", null, a.token()).statusCode()).isEqualTo(405);
        assertThat(vistoSegunApi(a.token())).as("ninguna de las anteriores lo marcó").isFalse();
    }

    @Test
    void laSesionIncluyeElNombreDelNegocio() throws Exception {
        Usuario a = usuario();
        HttpResponse<String> r = enviar("GET", "/api/sesion", null, a.token());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("negocio").asText())
                .isEqualTo(configuracionService.obtener().getNombreTienda().trim());
    }

    // ── Archivos estáticos ────────────────────────────────────────────────

    @Test
    void sirveElIconoOficialConCacheYEtag() throws Exception {
        byte[] original;
        try (var in = getClass().getResourceAsStream("/icono/icono-256.png")) { original = in.readAllBytes(); }

        HttpResponse<byte[]> r = bytes("GET", "/icono-256.png", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("public, max-age=86400");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(r.body()).isEqualTo(original);
        assertThat(r.body()).startsWith(new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        String etag = r.headers().firstValue("ETag").orElseThrow();

        assertThat(bytes("GET", "/icono-256.png", etag).statusCode()).isEqualTo(304);

        HttpResponse<byte[]> cabeza = bytes("HEAD", "/icono-256.png", null);
        assertThat(cabeza.statusCode()).isEqualTo(200);
        assertThat(cabeza.body()).isEmpty();

        HttpResponse<byte[]> chico = bytes("GET", "/icono-32.png", null);
        assertThat(chico.statusCode()).isEqualTo(200);
        assertThat(chico.body().length).isLessThan(original.length);

        HttpResponse<byte[]> favicon = bytes("GET", "/favicon.ico", null);
        assertThat(favicon.statusCode()).isEqualTo(200);
        assertThat(favicon.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(favicon.body()).isEqualTo(chico.body());
    }

    @Test
    void elManifiestoDescribeLaAppConElColorDeMarca() throws Exception {
        HttpResponse<byte[]> r = bytes("GET", "/manifest.webmanifest", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/manifest+json");
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("public, max-age=86400");
        JsonNode m = JSON.readTree(r.body());
        assertThat(m.get("name").asText()).isEqualTo("Punto de Venta");
        assertThat(m.get("theme_color").asText()).isEqualTo("#2A4566");
        assertThat(m.get("background_color").asText()).isEqualTo("#2A4566");
        assertThat(m.get("display").asText()).isEqualTo("standalone");
        assertThat(m.get("start_url").asText()).isEqualTo("/");
        assertThat(m.get("icons")).isNotEmpty();
        for (JsonNode icono : m.get("icons")) {   // cada icono anunciado se sirve de verdad
            assertThat(icono.get("type").asText()).isEqualTo("image/png");
            assertThat(bytes("GET", icono.get("src").asText(), null).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void laPaginaEnlazaIconoYManifiestoYLaCspLosPermite() throws Exception {
        HttpResponse<String> r = enviar("GET", "/", null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        String html = r.body();
        assertThat(html).contains("rel=\"manifest\" href=\"/manifest.webmanifest\"")
                .contains("rel=\"apple-touch-icon\"").contains("href=\"/icono-256.png\"")
                .contains("<meta name=\"theme-color\" content=\"#2A4566\">");
        String csp = r.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).contains("manifest-src 'self'").contains("img-src 'self'");
        // Iconos: SVG en línea creados por DOM, sin emojis ni fuentes/CDN externos
        assertThat(html).contains("createElementNS").contains("Lucide").doesNotContain("cdn.").doesNotContain("@font-face");
        assertThat(html.codePoints().anyMatch(cp -> cp >= 0x1F300 && cp <= 0x1FAFF)).as("sin emojis en la página").isFalse();
    }

    @Test
    void soloSeSirvenLosEstaticosDeLaListaYSinRecorrerCarpetas() throws Exception {
        assertThat(bytes("GET", "/icono-64.png", null).statusCode()).isEqualTo(404);      // existe en resources, pero no está en la lista
        assertThat(bytes("GET", "/icono/icono-256.png", null).statusCode()).isEqualTo(404);
        assertThat(bytes("GET", "/icono-256.png/../movil/index.html", null).statusCode()).isEqualTo(404);
        assertThat(bytes("GET", "/application.properties", null).statusCode()).isEqualTo(404);
        assertThat(bytes("GET", "/manifest.json", null).statusCode()).isEqualTo(404);
        assertThat(bytes("POST", "/manifest.webmanifest", null).statusCode()).isEqualTo(405);
        assertThat(bytes("DELETE", "/icono-256.png", null).statusCode()).isEqualTo(405);
    }
}
