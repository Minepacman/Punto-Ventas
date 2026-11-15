package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.NotificacionesPushService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceso móvil seguro: servidor HTTPS con el certificado de la CA local, página pública del
 * certificado (crt / mobileconfig), service worker, y los endpoints de suscripción push
 * (sesión obligatoria, validación del endpoint contra servicios conocidos, límite por IP,
 * preferencias, prueba con un enviador falso).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilHttpsPushTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Claves del teléfono del Apéndice A del RFC 8291 */
    static final String UA_PUBLICA = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String ENDPOINT_FCM = "https://fcm.googleapis.com/fcm/send/dpH5lCsTSSM:APA91bHqjZxM0VImWWqDRN7U0a3AycjUf4O-byuxb_wJsKRaKvV_iKw56s16ekq6FUqoCF7k2nICUpd8fHPxVTgqLunFeVeB9lLCQZyohyAztTH8ZQL9WCxKpA6dvTG_TUIhQUFq_n";

    @Autowired private ServidorMovilService servidor;
    @Autowired private CertificadosLocales certificados;
    @Autowired private NotificacionesPushService pushService;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;

    private final HttpClient http = HttpClient.newHttpClient();
    private String base;

    /** Enviador falso: guarda lo que se le pidió mandar y responde lo que se le diga */
    static class EnviadorFalso implements EnviadorPush {
        record Llamada(String endpoint, byte[] cuerpo, String authorization, int ttl, boolean urgente) { }
        final List<Llamada> llamadas = new ArrayList<>();
        int estado = 201;
        @Override public Resultado enviar(String endpoint, byte[] cuerpo, String authorization, int ttl, boolean urgente) {
            llamadas.add(new Llamada(endpoint, cuerpo, authorization, ttl, urgente));
            return new Resultado(estado, "");
        }
    }

    private final EnviadorFalso enviador = new EnviadorFalso();

    @BeforeEach
    void arrancar() throws Exception {
        servidor.puertoHttpsForzado = 0;
        servidor.iniciar(0);
        base = "http://127.0.0.1:" + servidor.getPuerto();
        Cajero a = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        a.setDebeCambiarContrasena(false);
        a.setBloqueadoHasta(null);
        a.setIntentosFallidos(0);
        cajeroRepo.save(a);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");
        pushService.usarEnviador(enviador);
    }

    @AfterEach
    void detener() {
        servidor.detener();
        servidor.puertoHttpsForzado = null;
        pushService.usarEnviador(null);
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private record Usuario(String login, String token) { }

    private Usuario usuario(Permiso... permisos) throws Exception {
        String login = "mp" + UUID.randomUUID().toString().substring(0, 8);
        Set<Permiso> set = permisos.length == 0 ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(List.of(permisos));
        Cajero c = cajeroService.crear(login, "Push " + login, CLAVE, Cajero.Rol.CAJERO, set);
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        HttpResponse<String> resp = enviar("POST", "/api/login",
                JSON.writeValueAsString(Map.of("usuario", login, "contrasena", CLAVE, "recordar", false)), null);
        assertThat(resp.statusCode()).isEqualTo(200);
        return new Usuario(login, JSON.readTree(resp.body()).get("token").asText());
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String cuerpoJson, String token) throws Exception {
        return enviarCon(http, base, metodo, ruta, cuerpoJson, token);
    }

    private static HttpResponse<String> enviarCon(HttpClient cliente, String base, String metodo, String ruta, String cuerpoJson, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (cuerpoJson != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpoJson == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpoJson));
        return cliente.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> bytes(String ruta) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + ruta)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String suscripcionJson(String endpoint, String p256dh, String auth) throws Exception {
        return JSON.writeValueAsString(Map.of("endpoint", endpoint, "keys", Map.of("p256dh", p256dh, "auth", auth)));
    }

    /** Cliente que confía SOLO en la CA local (como un teléfono que la instaló) */
    private HttpClient clienteQueConfiaEnLaCa() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("ca", certificados.ca());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(tls).build();
    }

    // ── HTTPS ─────────────────────────────────────────────────────────────

    @Test
    void elServidorHttpsRespondeConElCertificadoDeLaCaLocalYLosMismosManejadores() throws Exception {
        assertThat(servidor.httpsActivo()).isTrue();
        int puertoHttps = servidor.getPuertoHttps();
        assertThat(puertoHttps).isPositive().isNotEqualTo(servidor.getPuerto());
        String baseHttps = "https://127.0.0.1:" + puertoHttps;

        // Un cliente que confía en la CA (y verifica el nombre 127.0.0.1 del SAN) entra sin problemas
        HttpClient seguro = clienteQueConfiaEnLaCa();
        HttpResponse<String> pagina = enviarCon(seguro, baseHttps, "GET", "/", null, null);
        assertThat(pagina.statusCode()).isEqualTo(200);
        assertThat(pagina.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("worker-src 'self'");
        assertThat(pagina.headers().firstValue("Strict-Transport-Security")).as("sin HSTS en red local").isEmpty();
        assertThat(pagina.sslSession()).isPresent();
        assertThat(pagina.sslSession().get().getProtocol()).isIn("TLSv1.3", "TLSv1.2");
        X509Certificate presentado = (X509Certificate) pagina.sslSession().get().getPeerCertificates()[0];
        presentado.verify(certificados.ca().getPublicKey());

        // El login funciona igual por HTTPS, y la sesión informa el puerto seguro y la huella
        Usuario u = usuario();
        HttpResponse<String> sesion = enviarCon(seguro, baseHttps, "GET", "/api/sesion", null, u.token());
        assertThat(sesion.statusCode()).isEqualTo(200);
        JsonNode https = JSON.readTree(sesion.body()).get("https");
        assertThat(https.get("activo").asBoolean()).isTrue();
        assertThat(https.get("puerto").asInt()).isEqualTo(puertoHttps);
        assertThat(https.get("puertoHttp").asInt()).isEqualTo(servidor.getPuerto());
        assertThat(https.get("huella").asText()).isEqualTo(certificados.huellaCorta()).matches("[0-9A-F]{4}( [0-9A-F]{4}){3}");
        HttpResponse<String> clave = enviarCon(seguro, baseHttps, "GET", "/api/push/clave", null, u.token());
        assertThat(JSON.readTree(clave.body()).get("seguro").asBoolean()).isTrue();

        // Un cliente que NO confía en la CA (sistema por defecto) rechaza el certificado
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> enviarCon(HttpClient.newHttpClient(), baseHttps, "GET", "/", null, null))
                .isInstanceOf(javax.net.ssl.SSLHandshakeException.class);

        // El certificado cubre las IP de la red local y los nombres de esta computadora
        List<String> dns = servidor.nombresDnsCertificado(), ips = servidor.ipsCertificado();
        assertThat(dns).contains("localhost");
        assertThat(ips).contains("127.0.0.1").containsAll(ServidorMovilService.ipsLan().stream().map(java.net.Inet4Address::getHostAddress).toList());
        assertThat(certificados.servidorVigentePara(dns, ips)).isTrue();
        assertThat(servidor.urlsHttps()).allMatch(x -> x.startsWith("https://") && x.endsWith(":" + puertoHttps + "/"));
        assertThat(servidor.urlsCertificado(servidor.getPuerto())).allMatch(x -> x.startsWith("http://") && x.endsWith(":" + servidor.getPuerto() + "/certificado"));
    }

    @Test
    void laPaginaDelCertificadoEsPublicaMuestraLaHuellaYSirveCrtYMobileconfig() throws Exception {
        HttpResponse<String> r = enviar("GET", "/certificado", null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
        String csp = r.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).startsWith("default-src 'none'").contains("script-src 'sha256-").contains("frame-ancestors 'none'");
        String html = r.body();
        assertThat(html).contains(certificados.huellaCorta())
                .contains(CertificadosLocales.huellaConDosPuntos(certificados.huellaCa()))
                .contains("https://127.0.0.1:" + servidor.getPuertoHttps() + "/")
                .contains("data-https=\"si\"")
                .contains("/certificado/caja-libre.crt").contains("/certificado/caja-libre.mobileconfig")
                .contains("Cifrado y credenciales").contains("Certificado de CA")
                .contains("Ajustes de confianza de certificados").contains("Agregar a pantalla de inicio")
                .contains("No verificado").contains("Tienda de pruebas")
                .doesNotContain("{{");
        assertThat(html.codePoints().anyMatch(cp -> cp >= 0x1F300 && cp <= 0x1FAFF)).as("sin emojis").isFalse();
        // El hash de la CSP corresponde al script de la página
        int ini = html.indexOf("<script>") + "<script>".length(), fin = html.lastIndexOf("</script>");
        String hash = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(html.substring(ini, fin).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(csp).contains("'sha256-" + hash + "'");
        // No filtra nada más que lo público
        assertThat(html).doesNotContain("admin123").doesNotContain("Bearer").doesNotContain("PRIVATE KEY");
        assertThat(enviar("POST", "/certificado", "{}", null).statusCode()).isEqualTo(405);

        HttpResponse<byte[]> crt = bytes("/certificado/caja-libre.crt");
        assertThat(crt.statusCode()).isEqualTo(200);
        assertThat(crt.headers().firstValue("Content-Type")).hasValue("application/x-x509-ca-cert");
        assertThat(crt.headers().firstValue("Content-Disposition").orElseThrow()).contains("caja-libre.crt");
        assertThat(crt.body()).isEqualTo(certificados.caDer());
        assertThat(CertificadosLocales.leerCertificado(crt.body()).getBasicConstraints()).isGreaterThanOrEqualTo(0);

        HttpResponse<byte[]> mc = bytes("/certificado/caja-libre.mobileconfig");
        assertThat(mc.statusCode()).isEqualTo(200);
        assertThat(mc.headers().firstValue("Content-Type")).hasValue("application/x-apple-aspen-config");
        String perfil = new String(mc.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(perfil).contains("com.apple.security.root").contains("Tienda de pruebas").contains(certificados.huellaCorta());
        assertThat(perfil.replace("\n", "").replace(" ", "")).contains(Base64.getEncoder().encodeToString(certificados.caDer()));
        assertThat(bytes("/certificado/otra-cosa.crt").statusCode()).isEqualTo(404);
        // La clave privada nunca sale: no está en ninguna respuesta
        byte[] privada = java.nio.file.Files.readAllBytes(certificados.carpeta().resolve("movil-ca.key"));
        String privadaB64 = Base64.getEncoder().encodeToString(privada);
        assertThat(perfil).doesNotContain(privadaB64.substring(10, 40));
    }

    @Test
    void sirveElServiceWorkerLosIconosGrandesYElManifiestoLosAnuncia() throws Exception {
        HttpResponse<String> sw = enviar("GET", "/sw.js", null, null);
        assertThat(sw.statusCode()).isEqualTo(200);
        assertThat(sw.headers().firstValue("Content-Type")).hasValue("text/javascript; charset=utf-8");
        assertThat(sw.headers().firstValue("Cache-Control")).hasValue("no-cache");
        assertThat(sw.headers().firstValue("ETag")).isPresent();
        assertThat(sw.body()).contains("addEventListener('push'").contains("notificationclick").contains("showNotification")
                .contains("openWindow").contains("/icono-256.png");
        for (int t : new int[] { 192, 512 }) {
            HttpResponse<byte[]> png = bytes("/icono-" + t + ".png");
            assertThat(png.statusCode()).isEqualTo(200);
            assertThat(png.headers().firstValue("Content-Type")).hasValue("image/png");
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png.body()));
            assertThat(img.getWidth()).isEqualTo(t);
            assertThat(img.getHeight()).isEqualTo(t);
        }
        JsonNode m = JSON.readTree(bytes("/manifest.webmanifest").body());
        List<String> tamanos = new ArrayList<>();
        for (JsonNode i : m.get("icons")) tamanos.add(i.get("sizes").asText());
        assertThat(tamanos).contains("192x192", "512x512");
        assertThat(m.get("display").asText()).isEqualTo("standalone");
    }

    // ── Suscripciones push ────────────────────────────────────────────────

    @Test
    void laClaveVapidExigeSesionYEsUnaClaveP256() throws Exception {
        assertThat(enviar("GET", "/api/push/clave", null, null).statusCode()).isEqualTo(401);
        Usuario u = usuario();
        HttpResponse<String> r = enviar("GET", "/api/push/clave", null, u.token());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode j = JSON.readTree(r.body());
        byte[] clave = Base64.getUrlDecoder().decode(j.get("clave").asText());
        assertThat(clave).hasSize(65).startsWith((byte) 4);
        WebPushCripto.publicaDeCruda(clave);
        assertThat(j.get("seguro").asBoolean()).as("esta petición fue por HTTP").isFalse();
        assertThat(j.get("clave").asText()).isEqualTo(pushService.clavePublica());
        // Las claves quedan solo en la carpeta de claves, no en la BD ni en ninguna respuesta
        assertThat(certificados.carpeta().resolve("push-vapid.key")).exists();
        assertThat(certificados.carpeta().resolve("push-vapid.pub")).exists();
    }

    @Test
    void suscribirValidaElEndpointContraLosServiciosConocidosYLasClaves() throws Exception {
        Usuario u = usuario();
        assertThat(enviar("POST", "/api/push/suscribir", suscripcionJson(ENDPOINT_FCM, UA_PUBLICA, AUTH), null).statusCode()).isEqualTo(401);
        assertThat(enviar("GET", "/api/push/suscribir", null, u.token()).statusCode()).isEqualTo(405);
        // SSRF: solo https hacia servicios de push conocidos
        for (String malo : List.of(
                "http://fcm.googleapis.com/fcm/send/abc",
                "https://192.168.1.10/api/x", "https://127.0.0.1:8085/api/login", "https://10.0.0.2/x",
                "https://localhost/x", "https://fcm.googleapis.com.evil.com/x", "https://evilfcm.googleapis.com/x",
                "https://fcm.googleapis.com:8443/x", "https://user:pw@fcm.googleapis.com/x", "https://fcm.googleapis.com",
                "https://push.apple.com/x", "ftp://fcm.googleapis.com/x", "https://[::1]/x", "https://notify.windows.com/x")) {
            HttpResponse<String> r = enviar("POST", "/api/push/suscribir", suscripcionJson(malo, UA_PUBLICA, AUTH), u.token());
            assertThat(r.statusCode()).as(malo).isEqualTo(400);
            assertThat(JSON.readTree(r.body()).get("error").asText()).contains("servicios conocidos");
        }
        for (String bueno : List.of(ENDPOINT_FCM, "https://web.push.apple.com/QGxyz", "https://updates.push.services.mozilla.com/wpush/v2/abc",
                "https://db5p.notify.windows.com/w/?token=abc", "https://sin.push.apple.com/x")) {
            assertThat(ValidadorEndpointPush.permitido(bueno)).as(bueno).isTrue();
        }
        // Claves inválidas
        assertThat(enviar("POST", "/api/push/suscribir", suscripcionJson(ENDPOINT_FCM, "AAAA", AUTH), u.token()).statusCode()).isEqualTo(400);
        assertThat(enviar("POST", "/api/push/suscribir", suscripcionJson(ENDPOINT_FCM, UA_PUBLICA, "AAAA"), u.token()).statusCode()).isEqualTo(400);
        byte[] fuera = Base64.getUrlDecoder().decode(UA_PUBLICA);
        fuera[64] ^= 1;
        assertThat(enviar("POST", "/api/push/suscribir", suscripcionJson(ENDPOINT_FCM, Base64.getUrlEncoder().withoutPadding().encodeToString(fuera), AUTH), u.token()).statusCode()).isEqualTo(400);
        assertThat(enviar("POST", "/api/push/suscribir", "{\"endpoint\":\"" + ENDPOINT_FCM + "\"}", u.token()).statusCode()).isEqualTo(400);
        assertThat(pushService.propia(cajeroRepo.findByUsuarioIgnoreCase(u.login()).orElseThrow(), ENDPOINT_FCM)).isEmpty();
    }

    @Test
    void suscribirEstadoPreferenciasPruebaYDesuscribirSoloParaElDuenoDeLaSuscripcion() throws Exception {
        Usuario u = usuario();
        String endpoint = ENDPOINT_FCM + "-" + UUID.randomUUID();
        HttpResponse<String> alta = enviar("POST", "/api/push/suscribir", suscripcionJson(endpoint, UA_PUBLICA, AUTH), u.token());
        assertThat(alta.statusCode()).isEqualTo(200);
        JsonNode s = JSON.readTree(alta.body()).get("suscripcion");
        assertThat(s.get("activa").asBoolean()).isTrue();
        assertThat(s.get("proveedores").asBoolean()).isTrue();
        assertThat(s.get("stock").asBoolean()).isTrue();
        assertThat(s.get("horaPico").asBoolean()).isTrue();
        assertThat(s.get("silencioDesde").asInt()).isEqualTo(21);
        assertThat(s.get("silencioHasta").asInt()).isEqualTo(7);
        // Volver a suscribir el mismo endpoint no duplica
        assertThat(enviar("POST", "/api/push/suscribir", suscripcionJson(endpoint, UA_PUBLICA, AUTH), u.token()).statusCode()).isEqualTo(200);
        assertThat(pushService.listarTodas().stream().filter(x -> x.getEndpoint().equals(endpoint)).count()).isEqualTo(1);

        String cuerpoEp = JSON.writeValueAsString(Map.of("endpoint", endpoint));
        assertThat(enviar("POST", "/api/push/estado", cuerpoEp, u.token()).statusCode()).isEqualTo(200);
        // Otro usuario no ve ni toca esta suscripción
        Usuario otro = usuario();
        assertThat(enviar("POST", "/api/push/estado", cuerpoEp, otro.token()).statusCode()).isEqualTo(404);
        assertThat(enviar("POST", "/api/push/preferencias", JSON.writeValueAsString(Map.of("endpoint", endpoint, "activa", false)), otro.token()).statusCode()).isEqualTo(404);
        assertThat(enviar("POST", "/api/push/prueba", cuerpoEp, otro.token()).statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(enviar("POST", "/api/push/desuscribir", cuerpoEp, otro.token()).body()).get("existia").asBoolean()).isFalse();
        assertThat(enviar("POST", "/api/push/estado", cuerpoEp, u.token()).statusCode()).as("sigue viva").isEqualTo(200);

        // Preferencias
        HttpResponse<String> pref = enviar("POST", "/api/push/preferencias",
                JSON.writeValueAsString(Map.of("endpoint", endpoint, "stock", false, "silencioDesde", 22, "silencioHasta", 6)), u.token());
        assertThat(pref.statusCode()).isEqualTo(200);
        JsonNode p = JSON.readTree(pref.body());
        assertThat(p.get("stock").asBoolean()).isFalse();
        assertThat(p.get("proveedores").asBoolean()).isTrue();
        assertThat(p.get("silencioDesde").asInt()).isEqualTo(22);
        assertThat(p.get("silencioHasta").asInt()).isEqualTo(6);
        assertThat(enviar("POST", "/api/push/preferencias", JSON.writeValueAsString(Map.of("endpoint", endpoint, "silencioDesde", 24)), u.token()).statusCode()).isEqualTo(400);
        assertThat(enviar("POST", "/api/push/preferencias", JSON.writeValueAsString(Map.of("endpoint", endpoint, "stock", "no")), u.token()).statusCode()).isEqualTo(400);

        // Prueba: pasa por el enviador con VAPID, TTL de 4 h, cuerpo aes128gcm que el teléfono puede descifrar
        HttpResponse<String> prueba = enviar("POST", "/api/push/prueba", cuerpoEp, u.token());
        assertThat(prueba.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(prueba.body()).get("ok").asBoolean()).isTrue();
        assertThat(enviador.llamadas).hasSize(1);
        EnviadorFalso.Llamada ll = enviador.llamadas.get(0);
        assertThat(ll.endpoint()).isEqualTo(endpoint);
        assertThat(ll.ttl()).isEqualTo(NotificacionesPushService.TTL_SEGUNDOS);
        assertThat(ll.urgente()).isFalse();
        assertThat(ll.authorization()).startsWith("vapid t=").contains(", k=" + pushService.clavePublica());
        String jwt = ll.authorization().substring("vapid t=".length(), ll.authorization().indexOf(','));
        assertThat(WebPushCripto.verificarJwt(jwt, WebPushCripto.publicaDeCruda(Base64.getUrlDecoder().decode(pushService.clavePublica())))).isTrue();
        JsonNode carga = JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        assertThat(carga.get("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(carga.get("exp").asLong()).isGreaterThan(System.currentTimeMillis() / 1000).isLessThanOrEqualTo(System.currentTimeMillis() / 1000 + 86400);
        JsonNode mensaje = JSON.readTree(ProgramadorAvisosPushTest.descifrar(ll.cuerpo()));
        assertThat(mensaje.get("titulo").asText()).contains("notificaciones activas");
        assertThat(mensaje.get("cuerpo").asText()).contains("Tienda de pruebas");
        assertThat(mensaje.get("url").asText()).isEqualTo("/#/config");
        JsonNode estado = JSON.readTree(enviar("POST", "/api/push/estado", cuerpoEp, u.token()).body());
        assertThat(estado.get("ultimoOk").isNull()).isFalse();

        // 410 del servicio: la suscripción se olvida
        enviador.estado = 410;
        HttpResponse<String> muerta = enviar("POST", "/api/push/prueba", cuerpoEp, u.token());
        assertThat(JSON.readTree(muerta.body()).get("ok").asBoolean()).isFalse();
        assertThat(enviar("POST", "/api/push/estado", cuerpoEp, u.token()).statusCode()).isEqualTo(404);

        // Desuscribir es idempotente
        enviador.estado = 201;
        assertThat(enviar("POST", "/api/push/suscribir", suscripcionJson(endpoint, UA_PUBLICA, AUTH), u.token()).statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(enviar("POST", "/api/push/desuscribir", cuerpoEp, u.token()).body()).get("existia").asBoolean()).isTrue();
        assertThat(JSON.readTree(enviar("POST", "/api/push/desuscribir", cuerpoEp, u.token()).body()).get("existia").asBoolean()).isFalse();
        assertThat(enviar("POST", "/api/push/estado", cuerpoEp, u.token()).statusCode()).isEqualTo(404);
    }

    @Test
    void lasAltasDeSuscripcionTienenLimitePorIp() throws Exception {
        Usuario u = usuario();
        int ok = 0, limitadas = 0;
        for (int i = 0; i < ServidorMovilService.MAX_SUSCRIPCIONES_POR_IP + 3; i++) {
            HttpResponse<String> r = enviar("POST", "/api/push/suscribir", suscripcionJson(ENDPOINT_FCM + "-lim-" + UUID.randomUUID(), UA_PUBLICA, AUTH), u.token());
            if (r.statusCode() == 200) ok++;
            else if (r.statusCode() == 429) { limitadas++; assertThat(r.headers().firstValue("Retry-After")).isPresent(); }
            else throw new AssertionError("Estado inesperado " + r.statusCode() + ": " + r.body());
        }
        assertThat(ok).isEqualTo(ServidorMovilService.MAX_SUSCRIPCIONES_POR_IP);
        assertThat(limitadas).isEqualTo(3);
        // Y un usuario nunca acumula más de MAX_POR_USUARIO (se borran las más antiguas)
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(u.login()).orElseThrow();
        assertThat(pushService.listarTodas().stream().filter(x -> x.getCajeroId().equals(c.getId())).count())
                .isLessThanOrEqualTo(NotificacionesPushService.MAX_POR_USUARIO);
    }
}
