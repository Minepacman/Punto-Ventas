package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NotificacionesPushService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas del asistente «Recibir avisos en este teléfono» a 390 px con Chrome sin ventana,
 * manejado por el protocolo de DevTools (WebSocket del JDK, sin dependencias). Solo a petición:
 * <pre>APP_CAPTURAS_MOVIL=true APP_CAPTURAS_DIR=/ruta [CHROME=/ruta/a/chrome] ./gradlew test --tests '*CapturasAsistenteAvisosTest'</pre>
 * Cada paso se fuerza como lo vería un teléfono real: agente de usuario de iPhone/Android, progreso
 * guardado en el teléfono y, para los pasos de la página segura, un Chrome que acepta el certificado
 * (como un teléfono que ya instaló la llave). El estado «Listo» simula el permiso y la suscripción
 * del navegador, pero las preferencias vienen del servidor real.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS_MOVIL", matches = "true")
class CapturasAsistenteAvisosTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String UA_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    static final String UA_IPHONE_VIEJO = "Mozilla/5.0 (iPhone; CPU iPhone OS 15_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.6 Mobile/15E148 Safari/604.1";
    static final String UA_ANDROID = "Mozilla/5.0 (Linux; Android 14; SM-A146M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private NotificacionesPushService pushService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository configuracionRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void capturasDelAsistente() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")));
        Files.createDirectories(dir);
        var cfg = configuracionService.obtener();
        cfg.setNombreTienda("Abarrotes Lupita");
        configuracionRepo.save(cfg);
        servidor.puertoHttpsForzado = 0;
        servidor.iniciar(0);
        pushService.usarEnviador(new ServidorMovilHttpsPushTest.EnviadorFalso());
        String base = "http://127.0.0.1:" + servidor.getPuerto();
        String baseHttps = "https://127.0.0.1:" + servidor.getPuertoHttps();
        String chrome = System.getenv().getOrDefault("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");

        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        admin.setDebeCambiarContrasena(false);
        admin.setBloqueadoHasta(null);
        cajeroRepo.save(admin);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");
        String login = "lupita";
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(login).orElseGet(() ->
                cajeroService.crear(login, "Lupita Hernández", "clave123", Cajero.Rol.GERENTE, EnumSet.of(Permiso.VER_HISTORIAL)));
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);

        try (Chrome sinLlave = new Chrome(chrome, false); Chrome conLlave = new Chrome(chrome, true)) {
            // Por http, el teléfono aún no confía en la llave
            sinLlave.foto(base, "#/avisos", UA_ANDROID, null, null, null, dir.resolve("00-login-desde-qr.png"));
            String t1 = token(base, login);
            sinLlave.foto(base, "#/avisos", UA_IPHONE, t1, "", null, dir.resolve("01-iphone-paso1-descargar.png"));
            sinLlave.foto(base, "#/avisos", UA_IPHONE, t1, "descargada", null, dir.resolve("02-iphone-paso2-instalar.png"));
            sinLlave.foto(base, "#/avisos", UA_IPHONE, t1, "instalada", null, dir.resolve("03-iphone-paso3-confiar.png"));
            sinLlave.foto(base, "#/avisos", UA_ANDROID, t1, "", null, dir.resolve("10-android-paso1-descargar.png"));
            sinLlave.foto(base, "#/avisos", UA_ANDROID, t1, "descargada", null, dir.resolve("11-android-paso2-instalar.png"));
            sinLlave.foto(base, "#/avisos", UA_IPHONE_VIEJO, t1, "", null, dir.resolve("20-iphone-ios-viejo.png"));
            // La llave ya está instalada: la página normal ofrece «Continuar»
            conLlave.foto(base, "#/avisos", UA_ANDROID, t1, "", null, dir.resolve("12-android-paso3-continuar.png"));
            // Página segura
            String t2 = token(base, login);
            conLlave.foto(baseHttps, "#/config", UA_IPHONE, t2, "", null, dir.resolve("04-iphone-paso5-agregar-inicio.png"));
            conLlave.foto(baseHttps, "#/config", UA_ANDROID, t2, "", Map.of("permiso", "default"), dir.resolve("13-android-paso4-permitir.png"));
            conLlave.foto(baseHttps, "#/config", UA_ANDROID, t2, "", Map.of("permiso", "denied"), dir.resolve("21-android-bloqueado.png"));
            // Listo: suscripción real en el servidor, navegador simulado con ese endpoint
            String endpoint = ServidorMovilHttpsPushTest.ENDPOINT_FCM + "-capturas";
            HttpResponse<String> alta = post(base, "/api/push/suscribir", JSON.writeValueAsString(Map.of("endpoint", endpoint,
                    "keys", Map.of("p256dh", ServidorMovilHttpsPushTest.UA_PUBLICA, "auth", ServidorMovilHttpsPushTest.AUTH))), t2);
            assertThat(alta.statusCode()).isEqualTo(200);
            conLlave.foto(baseHttps, "#/config", UA_ANDROID, t2, "", Map.of("permiso", "granted", "endpoint", endpoint), dir.resolve("14-android-listo.png"));
            conLlave.fotoCompleta(dir.resolve("15-android-listo-completa.png"));
            conLlave.abrirAyuda(dir.resolve("16-android-no-me-llegan.png"));
        } finally {
            servidor.detener();
            servidor.puertoHttpsForzado = null;
            pushService.usarEnviador(null);
        }
        System.out.println("Capturas en " + dir.toAbsolutePath());
    }

    private String token(String base, String login) throws Exception {
        HttpResponse<String> r = post(base, "/api/login", JSON.writeValueAsString(Map.of("usuario", login, "contrasena", "clave123", "recordar", false)), null);
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

    // ── Chrome sin ventana por DevTools ───────────────────────────────────

    static final class Chrome implements AutoCloseable {
        private final Process proceso;
        private final Path perfil;
        private final WebSocket ws;
        private final AtomicInteger ids = new AtomicInteger();
        private final Map<Integer, CompletableFuture<JsonNode>> pendientes = new ConcurrentHashMap<>();

        Chrome(String ejecutable, boolean aceptarCertificado) throws Exception {
            perfil = Files.createTempDirectory("chrome-capturas");
            var args = new java.util.ArrayList<>(java.util.List.of(ejecutable, "--headless=new", "--remote-debugging-port=0",
                    "--user-data-dir=" + perfil, "--no-first-run", "--no-default-browser-check", "--hide-scrollbars", "about:blank"));
            if (aceptarCertificado) args.add(1, "--ignore-certificate-errors");
            proceso = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Path puertoArchivo = perfil.resolve("DevToolsActivePort");
            for (int i = 0; i < 100 && !Files.exists(puertoArchivo); i++) Thread.sleep(100);
            int puerto = Integer.parseInt(Files.readAllLines(puertoArchivo).get(0).trim());
            HttpClient h = HttpClient.newHttpClient();
            JsonNode lista = JSON.readTree(h.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/json/list")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            String url = null;
            for (JsonNode t : lista) if ("page".equals(t.get("type").asText())) url = t.get("webSocketDebuggerUrl").asText();
            StringBuilder parcial = new StringBuilder();
            ws = h.newWebSocketBuilder().buildAsync(URI.create(url), new WebSocket.Listener() {
                @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                    parcial.append(data);
                    if (last) {
                        try {
                            JsonNode m = JSON.readTree(parcial.toString());
                            if (m.has("id")) {
                                CompletableFuture<JsonNode> f = pendientes.remove(m.get("id").asInt());
                                if (f != null) f.complete(m);
                            }
                        } catch (Exception ignorada) { /* evento sin interés */ }
                        parcial.setLength(0);
                    }
                    w.request(1);
                    return null;
                }
            }).get(10, TimeUnit.SECONDS);
            enviar("Page.enable", Map.of());
            enviar("Emulation.setDeviceMetricsOverride", Map.of("width", 390, "height", 844, "deviceScaleFactor", 2, "mobile", true));
            enviar("Emulation.setTouchEmulationEnabled", Map.of("enabled", true, "maxTouchPoints", 5));
        }

        JsonNode enviar(String metodo, Map<String, Object> params) throws Exception {
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            pendientes.put(id, f);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("method", metodo);
            m.put("params", params);
            ws.sendText(JSON.writeValueAsString(m), true).get(10, TimeUnit.SECONDS);
            JsonNode r = f.get(30, TimeUnit.SECONDS);
            if (r.has("error")) throw new IllegalStateException(metodo + ": " + r.get("error"));
            return r.get("result");
        }

        private String guion;

        /**
         * Abre la página como un teléfono: agente de usuario, sesión y progreso en el almacenamiento del
         * teléfono y, si se pide, permiso/suscripción simulados (se inyectan antes de que corra la página).
         */
        void foto(String base, String hash, String ua, String token, String avance, Map<String, String> simular, Path destino) throws Exception {
            enviar("Emulation.setUserAgentOverride", Map.of("userAgent", ua, "platform", ua.contains("Android") ? "Linux armv8l" : "iPhone"));
            if (guion != null) enviar("Page.removeScriptToEvaluateOnNewDocument", Map.of("identifier", guion));
            StringBuilder js = new StringBuilder("(() => { window.__errores = []; "
                    + "addEventListener('error', e => __errores.push(String(e.message))); "
                    + "addEventListener('unhandledrejection', e => __errores.push(String(e.reason && (e.reason.stack || e.reason)))); try {");
            js.append("localStorage.clear();");
            if (token != null) js.append("localStorage.setItem('pv.sesion', JSON.stringify({token:").append(JSON.writeValueAsString(token)).append(", expira:null, permanente:true}));");
            js.append("localStorage.setItem('pv.tutorial.lupita','1');");
            if (avance != null && !avance.isEmpty()) js.append("localStorage.setItem('pv.avisos.avance',").append(JSON.writeValueAsString(avance)).append(");");
            if (simular != null) {
                js.append("Object.defineProperty(Notification, 'permission', { get: () => ").append(JSON.writeValueAsString(simular.get("permiso"))).append(" });");
                String ep = simular.get("endpoint");
                js.append("const sub = ").append(ep == null ? "null" : "{ endpoint: " + JSON.writeValueAsString(ep) + ", toJSON() { return { keys: {} }; }, unsubscribe: async () => true }").append(";");
                js.append("navigator.serviceWorker.register = async () => ({ pushManager: { getSubscription: async () => sub, subscribe: async () => sub } });");
            }
            js.append("} catch (e) {} })();");
            guion = enviar("Page.addScriptToEvaluateOnNewDocument", Map.of("source", js.toString())).get("identifier").asText();
            // Primero una página del mismo origen (sin script) para que el guion corra antes del de la app
            enviar("Page.navigate", Map.of("url", base + "/certificado"));
            Thread.sleep(600);
            enviar("Page.navigate", Map.of("url", base + "/" + hash));
            long inicio = System.currentTimeMillis();
            Thread.sleep(1500);
            while (System.currentTimeMillis() - inicio < 20000) {   // hasta que la tarjeta deja de decir «Revisando…»
                JsonNode c = enviar("Runtime.evaluate", Map.of("expression",
                        "!!document.querySelector('#notificaciones .cargando, #vista > .cargando')", "returnByValue", true));
                if (!c.get("result").get("value").asBoolean()) break;
                Thread.sleep(250);
            }
            Thread.sleep(400);
            System.out.println("Capturas " + destino.getFileName() + " lista en " + (System.currentTimeMillis() - inicio) + " ms");
            JsonNode diag = enviar("Runtime.evaluate", Map.of("expression",
                    "JSON.stringify({permiso: window.Notification && Notification.permission, seguro: isSecureContext, sw: 'serviceWorker' in navigator, push: 'PushManager' in window, errores: window.__errores, reg: navigator.serviceWorker && String(navigator.serviceWorker.register).slice(0, 30), "
                    + "red: performance.getEntriesByType('resource').map(e => e.name.replace(location.origin, '') + ' ' + (e.startTime | 0) + '+' + (e.duration | 0))})",
                    "returnByValue", true));
            System.out.println("Capturas " + destino.getFileName() + ": " + diag.get("result").get("value").asText());
            guardar(enviar("Page.captureScreenshot", Map.of("format", "png")), destino);
        }

        void fotoCompleta(Path destino) throws Exception {
            JsonNode alto = enviar("Runtime.evaluate", Map.of("expression", "document.documentElement.scrollHeight", "returnByValue", true));
            int h = alto.get("result").get("value").asInt();
            guardar(enviar("Page.captureScreenshot", Map.of("format", "png", "captureBeyondViewport", true,
                    "clip", Map.of("x", 0, "y", 0, "width", 390, "height", h, "scale", 1))), destino);
        }

        void abrirAyuda(Path destino) throws Exception {
            enviar("Runtime.evaluate", Map.of("expression",
                    "(() => { const d = document.querySelector('.asis-ayuda'); d.open = true; d.scrollIntoView({block:'center'}); })()"));
            Thread.sleep(400);
            guardar(enviar("Page.captureScreenshot", Map.of("format", "png")), destino);
        }

        private static void guardar(JsonNode r, Path destino) throws Exception {
            Files.write(destino, Base64.getDecoder().decode(r.get("data").asText()));
        }

        @Override public void close() throws Exception {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(3, TimeUnit.SECONDS); } catch (Exception ignorada) { /* ya cerrado */ }
            proceso.destroy();
            proceso.waitFor(10, TimeUnit.SECONDS);
            try (var s = Files.walk(perfil)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignorada) { } });
            }
        }
    }
}
