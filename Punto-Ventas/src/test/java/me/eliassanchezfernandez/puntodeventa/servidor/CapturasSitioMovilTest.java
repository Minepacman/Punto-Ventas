package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.DatosDemoCapturas;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas de la página móvil para el sitio público (marcos de iPhone), a 390 px en modo claro, con la
 * tienda de ejemplo de {@link DatosDemoCapturas}: Inicio, Ventas (historial), Inventario, Proveedores,
 * Fiado y el panel de avisos. Usa el Chrome sin ventana de {@link CapturasAsistenteAvisosTest}. Solo a petición:
 * <pre>APP_CAPTURAS_MOVIL=true APP_CAPTURAS_DIR=/ruta [CHROME=/ruta/a/chrome] ./gradlew test --tests '*CapturasSitioMovilTest'</pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS_MOVIL", matches = "true")
class CapturasSitioMovilTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ApplicationContext ctx;
    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void capturasMovilesParaElSitio() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "sitio-movil");
        Files.createDirectories(dir);
        DatosDemoCapturas.sembrar(ctx);
        servidor.puertoHttpsForzado = 0;
        servidor.iniciar(0);
        String base = "http://127.0.0.1:" + servidor.getPuerto();
        String chrome = System.getenv().getOrDefault("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        try (CapturasAsistenteAvisosTest.Chrome c = new CapturasAsistenteAvisosTest.Chrome(chrome, false)) {
            c.enviar("Emulation.setEmulatedMedia", Map.of("features", List.of(Map.of("name", "prefers-color-scheme", "value", "light"))));
            String token = token(base);
            String ua = CapturasAsistenteAvisosTest.UA_IPHONE;
            c.foto(base, "#/estadisticas", ua, token, "", null, dir.resolve("inicio.png"));
            c.enviar("Runtime.evaluate", Map.of("expression", "document.getElementById('avisos').click()"));
            Thread.sleep(1500);
            guardar(c.enviar("Page.captureScreenshot", Map.of("format", "png")), dir.resolve("avisos.png"));
            c.foto(base, "#/historial", ua, token, "", null, dir.resolve("ventas.png"));
            c.foto(base, "#/inventario", ua, token, "", null, dir.resolve("inventario.png"));
            c.foto(base, "#/proveedores", ua, token, "", null, dir.resolve("proveedores.png"));
            c.foto(base, "#/clientes", ua, token, "", null, dir.resolve("fiado.png"));
        } finally {
            servidor.detener();
            servidor.puertoHttpsForzado = null;
            sesionService.cerrarSesion();
        }
        System.out.println("Capturas en " + dir.toAbsolutePath());
    }

    private String token(String base) throws Exception {
        HttpResponse<String> r = post(base, "/api/login", JSON.writeValueAsString(Map.of("usuario", SesionService.ADMIN_USUARIO,
                "contrasena", SesionService.ADMIN_CONTRASENA, "recordar", false)), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String t = JSON.readTree(r.body()).get("token").asText();
        post(base, "/api/tutorial/visto", "{\"visto\":true}", t);   // sin el recorrido encima de las capturas
        return t;
    }

    private HttpResponse<String> post(String base, String ruta, String json, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void guardar(com.fasterxml.jackson.databind.JsonNode r, Path destino) throws Exception {
        Files.write(destino, Base64.getDecoder().decode(r.get("data").asText()));
    }
}
