package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.DatosDemoCapturas;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.ocr.MotorOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.Renglon;
import me.eliassanchezfernandez.puntodeventa.ocr.ResultadoOcr;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas a 390 px del flujo «Leer ticket de proveedor» en el teléfono, con un motor OCR falso que «lee» la
 * muestra anonimizada t14 y la foto sintética ocr/ticket-muestra.jpg (nunca fotos reales). Solo a petición:
 * <pre>APP_CAPTURAS_MOVIL=true APP_CAPTURAS_DIR=/ruta ./gradlew test --tests '*CapturasLeerTicketMovilTest'</pre>
 */
@SpringBootTest
@Import(CapturasLeerTicketMovilTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS_MOVIL", matches = "true")
class CapturasLeerTicketMovilTest {

    static volatile boolean disponible = true;
    static volatile CountDownLatch espera = null;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MotorOcr motorFalso() {
            return new MotorOcr() {
                @Override public ResultadoOcr leer(byte[] imagen) throws IOException {
                    CountDownLatch e = espera;
                    if (e != null) {
                        try { e.await(30, TimeUnit.SECONDS); } catch (InterruptedException ie) { throw new java.io.InterruptedIOException(); }
                    }
                    String texto;
                    try (InputStream in = getClass().getResourceAsStream("/tickets-proveedor/t14.txt")) {
                        texto = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    List<Renglon> rs = new ArrayList<>();
                    String[] ls = texto.split("\n");
                    for (int i = 0; i < ls.length; i++) rs.add(new Renglon(ls[i], 0.9, 40 + i * 22));
                    return new ResultadoOcr(rs, texto);
                }
                @Override public String nombre() { return "Tesseract (prueba)"; }
                @Override public boolean disponible() { return disponible; }
            };
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ApplicationContext ctx;
    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private ProductoService productoService;

    private final HttpClient http = HttpClient.newHttpClient();
    private CapturasAsistenteAvisosTest.Chrome c;

    @Test
    void capturasDelLectorDeTickets() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")));
        Files.createDirectories(dir);
        DatosDemoCapturas.sembrar(ctx);
        producto("7500000000101", "Yog Lala Beb Mango 220 gr", 13.0, 18.0);
        producto("7500000000102", "Yog Lala Beb Pina Coco 220 gr", 14.4, 19.0);
        producto("7500000000103", "Nutri Yoghurt Nuez 450 g", 15.0, 21.0);
        servidor.iniciar(0);
        String base = "http://127.0.0.1:" + servidor.getPuerto();
        String chrome = System.getenv().getOrDefault("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        byte[] foto;
        try (InputStream in = getClass().getResourceAsStream("/ocr/ticket-muestra.jpg")) { foto = in.readAllBytes(); }
        try (CapturasAsistenteAvisosTest.Chrome ch = new CapturasAsistenteAvisosTest.Chrome(chrome, false)) {
            c = ch;
            c.enviar("Emulation.setEmulatedMedia", Map.of("features", List.of(Map.of("name", "prefers-color-scheme", "value", "light"))));
            String token = token(base);
            c.foto(base, "#/inventario", CapturasAsistenteAvisosTest.UA_IPHONE, token, "", null, dir.resolve("01-inventario.png"));
            clic("Leer ticket de proveedor");
            Thread.sleep(1500);
            pantalla(dir.resolve("02-inicio.png"));
            // La foto: como si se hubiera tomado con la cámara
            js("(() => { const b = Uint8Array.from(atob('" + Base64.getEncoder().encodeToString(foto) + "'), x => x.charCodeAt(0));"
                    + "const f = new File([b], 'ticket.jpg', { type: 'image/jpeg' }); const dt = new DataTransfer(); dt.items.add(f);"
                    + "const i = document.getElementById('tk-camara'); i.files = dt.files; i.dispatchEvent(new Event('change')); })()");
            Thread.sleep(1500);
            pantalla(dir.resolve("03-previa.png"));
            espera = new CountDownLatch(1);
            clic("Leer el ticket");
            Thread.sleep(3500);
            pantalla(dir.resolve("04-leyendo.png"));
            espera.countDown();
            Thread.sleep(2500);
            c.fotoCompleta(dir.resolve("05-revision.png"));
            js("[...document.querySelectorAll('.tk-linea.rojo button')].find(b => b.textContent.includes('Cambiar')).click()");
            Thread.sleep(800);
            js("(() => { const i = document.querySelector('.hoja input[type=search]'); i.value = 'yog'; i.dispatchEvent(new Event('input')); })()");
            Thread.sleep(1500);
            pantalla(dir.resolve("06-cambiar-producto.png"));
            js("document.querySelector('.hoja .tk-cand .fila').click()");
            Thread.sleep(500);
            js("[...document.querySelectorAll('.tk-linea.rojo button')].find(b => b.textContent.includes('Crear')).click()");
            Thread.sleep(800);
            pantalla(dir.resolve("07-crear-producto.png"));
            clic("Usar este producto");
            Thread.sleep(500);
            // Quita lo que falta, confirma los amarillos y registra
            js("document.querySelectorAll('.tk-linea.rojo').forEach(l => [...l.querySelectorAll('button')].find(b => b.textContent.includes('Quitar')).click())");
            js("document.querySelectorAll('.tk-linea.amarillo').forEach(l => [...l.querySelectorAll('button')].find(b => b.textContent.includes('Está bien')).click())");
            Thread.sleep(500);
            c.fotoCompleta(dir.resolve("08-revisada.png"));
            clic("Registrar entrada");
            Thread.sleep(1000);
            pantalla(dir.resolve("09-no-cuadra.png"));
            js("[...document.querySelectorAll('.hoja button')].find(b => b.textContent.trim() === 'Registrar').click()");
            Thread.sleep(2500);
            c.fotoCompleta(dir.resolve("10-resultado.png"));
            // Computadora sin lector: se ofrece mandar la foto
            disponible = false;
            clic("Volver al inventario");
            Thread.sleep(1500);
            clic("Leer ticket de proveedor");
            Thread.sleep(1500);
            pantalla(dir.resolve("11-sin-lector.png"));
            JsonNode errores = c.enviar("Runtime.evaluate", Map.of("expression", "JSON.stringify(window.__errores || [])", "returnByValue", true));
            assertThat(errores.get("result").get("value").asText()).isEqualTo("[]");
        } finally {
            servidor.detener();
            sesionService.cerrarSesion();
        }
        System.out.println("Capturas en " + dir.toAbsolutePath());
    }

    private void producto(String codigo, String nombre, double costo, double venta) {
        Producto p = new Producto();
        p.setCodigoBarras(codigo);
        p.setDescripcion(nombre);
        p.setPrecioCosto(costo);
        p.setPrecioVenta(venta);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        p.setUsaInventario(true);
        p.setCantidadMinima(1);
        productoService.guardar(p);
    }

    private void js(String expr) throws Exception {
        JsonNode r = c.enviar("Runtime.evaluate", Map.of("expression", expr, "returnByValue", true));
        if (r.has("exceptionDetails")) {
            JsonNode e = c.enviar("Runtime.evaluate", Map.of("expression", "JSON.stringify(window.__errores || [])", "returnByValue", true));
            throw new AssertionError("JS falló: " + (expr.length() > 200 ? expr.substring(0, 200) : expr) + " → "
                    + r.get("exceptionDetails").path("exception").path("description") + " · errores de la página: " + e.path("result").path("value"));
        }
    }

    private void clic(String texto) throws Exception {
        js("[...document.querySelectorAll('button, label')].filter(b => b.offsetParent && b.textContent.includes(" + JSON.writeValueAsString(texto) + ")).pop().click()");
    }

    private void pantalla(Path destino) throws Exception {
        Files.write(destino, Base64.getDecoder().decode(c.enviar("Page.captureScreenshot", Map.of("format", "png")).get("data").asText()));
    }

    private String token(String base) throws Exception {
        HttpResponse<String> r = post(base, "/api/login", JSON.writeValueAsString(Map.of("usuario", SesionService.ADMIN_USUARIO,
                "contrasena", SesionService.ADMIN_CONTRASENA, "recordar", false)), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String t = JSON.readTree(r.body()).get("token").asText();
        post(base, "/api/tutorial/visto", "{\"visto\":true}", t);
        return t;
    }

    private HttpResponse<String> post(String base, String ruta, String json, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
