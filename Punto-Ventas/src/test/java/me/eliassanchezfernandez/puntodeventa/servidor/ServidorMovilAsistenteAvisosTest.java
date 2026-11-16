package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AccesoMovilService;
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
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asistente «Recibir avisos en este teléfono»: traspaso de la sesión a la página segura con un
 * código de un solo uso, manifiesto con código para el icono de inicio del iPhone, CSP que deja
 * probar la dirección segura desde la página normal, QR del escritorio y la prueba por teléfono
 * desde Configuración → Acceso móvil.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilAsistenteAvisosTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private CertificadosLocales certificados;
    @Autowired private NotificacionesPushService pushService;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private AccesoMovilService accesoMovil;

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpClient seguro;
    private String base, baseHttps;
    private final ServidorMovilHttpsPushTest.EnviadorFalso enviador = new ServidorMovilHttpsPushTest.EnviadorFalso();

    @BeforeEach
    void arrancar() throws Exception {
        servidor.puertoHttpsForzado = 0;
        servidor.iniciar(0);
        servidor.traspasos = null;
        base = "http://127.0.0.1:" + servidor.getPuerto();
        baseHttps = "https://127.0.0.1:" + servidor.getPuertoHttps();
        Cajero a = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        a.setDebeCambiarContrasena(false);
        a.setBloqueadoHasta(null);
        a.setIntentosFallidos(0);
        cajeroRepo.save(a);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");
        pushService.usarEnviador(enviador);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("ca", certificados.ca());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, tmf.getTrustManagers(), null);
        seguro = HttpClient.newBuilder().sslContext(tls).build();
    }

    @AfterEach
    void detener() {
        servidor.detener();
        servidor.puertoHttpsForzado = null;
        servidor.traspasos = null;
        pushService.usarEnviador(null);
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private record Usuario(String login, String token) { }

    private Usuario usuario(boolean recordar) throws Exception {
        String login = "aa" + UUID.randomUUID().toString().substring(0, 8);
        Cajero c = cajeroService.crear(login, "Avisos " + login, CLAVE, Cajero.Rol.CAJERO, EnumSet.noneOf(me.eliassanchezfernandez.puntodeventa.model.Permiso.class));
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        HttpResponse<String> r = enviar(http, base, "POST", "/api/login", JSON.writeValueAsString(Map.of("usuario", login, "contrasena", CLAVE, "recordar", false)), null);
        assertThat(r.statusCode()).isEqualTo(200);
        String token = JSON.readTree(r.body()).get("token").asText();
        if (recordar) {
            HttpResponse<String> p = enviar(http, base, "POST", "/api/sesion/recordar", JSON.writeValueAsString(Map.of("recordar", true, "contrasena", CLAVE)), token);
            assertThat(p.statusCode()).isEqualTo(200);
        }
        return new Usuario(login, token);
    }

    private static HttpResponse<String> enviar(HttpClient cliente, String base, String metodo, String ruta, String cuerpoJson, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (cuerpoJson != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpoJson == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpoJson));
        return cliente.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode traspaso(String token, String destino) throws Exception {
        HttpResponse<String> r = enviar(http, base, "POST", "/api/sesion/traspaso", JSON.writeValueAsString(Map.of("destino", destino)), token);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> canjear(String codigo) throws Exception {
        return enviar(seguro, baseHttps, "POST", "/api/sesion/canjear", JSON.writeValueAsString(Map.of("codigo", codigo)), null);
    }

    // ── Traspaso de sesión ────────────────────────────────────────────────

    @Test
    void continuarPasaLaSesionALaPaginaSeguraConUnCodigoDeUnSoloUso() throws Exception {
        Usuario u = usuario(true);
        JsonNode t = traspaso(u.token(), "seguro");
        String codigo = t.get("codigo").asText();
        assertThat(codigo).matches("[A-Za-z0-9_-]{43}");
        assertThat(t.get("venceEnSegundos").asLong()).isEqualTo(TraspasosSesion.VIGENCIA_SEGURO.toSeconds());
        // El código va en el fragmento (#): el navegador no lo manda al servidor ni lo pone en el Referer
        assertThat(t.get("url").asText()).isEqualTo(baseHttps + "/#traspaso=" + codigo + "&ir=avisos");
        assertThat(t.get("url").asText()).doesNotContain(u.token());

        HttpResponse<String> r = canjear(codigo);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode s = JSON.readTree(r.body());
        String nuevo = s.get("token").asText();
        assertThat(nuevo).isNotEqualTo(u.token()).isNotEqualTo(codigo);
        assertThat(s.get("usuario").asText()).isEqualTo(u.login());
        assertThat(s.get("permanente").asBoolean()).as("conserva «mantener abierta»").isTrue();
        // La sesión nueva sirve; la original quedó cerrada (la sesión se mudó)
        assertThat(enviar(seguro, baseHttps, "GET", "/api/sesion", null, nuevo).statusCode()).isEqualTo(200);
        assertThat(enviar(http, base, "GET", "/api/sesion", null, u.token()).statusCode()).isEqualTo(401);
        // Un solo uso
        HttpResponse<String> otra = canjear(codigo);
        assertThat(otra.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(otra.body()).get("codigo").asText()).isEqualTo("TRASPASO_INVALIDO");
    }

    @Test
    void laSesionTemporalSigueSiendoTemporal() throws Exception {
        Usuario u = usuario(false);
        HttpResponse<String> r = canjear(traspaso(u.token(), "seguro").get("codigo").asText());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("permanente").asBoolean()).isFalse();
    }

    @Test
    void elCodigoVenceYNoSirveSiLaSesionOriginalSeCerro() throws Exception {
        RelojMutable reloj = new RelojMutable(Instant.now());
        servidor.traspasos = new TraspasosSesion(reloj);
        Usuario u = usuario(false);
        String c1 = traspaso(u.token(), "seguro").get("codigo").asText();
        reloj.avanzar(TraspasosSesion.VIGENCIA_SEGURO);
        assertThat(canjear(c1).statusCode()).as("vencido").isEqualTo(401);

        String c2 = traspaso(u.token(), "seguro").get("codigo").asText();
        assertThat(enviar(http, base, "POST", "/api/logout", "{}", u.token()).statusCode()).isEqualTo(200);
        assertThat(canjear(c2).statusCode()).as("la sesión original ya no existe").isEqualTo(401);
    }

    @Test
    void crearUnTraspasoExigeSesionYCanjearNoAceptaBasura() throws Exception {
        assertThat(enviar(http, base, "POST", "/api/sesion/traspaso", "{\"destino\":\"seguro\"}", null).statusCode()).isEqualTo(401);
        assertThat(enviar(http, base, "GET", "/api/sesion/traspaso", null, null).statusCode()).isEqualTo(405);
        assertThat(enviar(http, base, "GET", "/api/sesion/canjear", null, null).statusCode()).isEqualTo(405);
        Usuario u = usuario(false);
        assertThat(enviar(http, base, "POST", "/api/sesion/traspaso", "{\"destino\":\"otro\"}", u.token()).statusCode()).isEqualTo(400);
        assertThat(canjear("x".repeat(43)).statusCode()).isEqualTo(401);
        assertThat(canjear("").statusCode()).isEqualTo(401);
        assertThat(enviar(seguro, baseHttps, "POST", "/api/sesion/canjear", "{\"codigo\":5}", null).statusCode()).isEqualTo(400);
        // Los fallos cuentan como intentos de login: después de 10, se pide esperar
        for (int i = 0; i < ServidorMovilService.MAX_FALLOS_POR_IP; i++) canjear(UUID.randomUUID().toString().replace("-", "") + "abcdefghijk");
        String bueno = traspaso(u.token(), "seguro").get("codigo").asText();
        assertThat(canjear(bueno).statusCode()).isEqualTo(429);
    }

    @Test
    void sinConexionSeguraNoSeOfreceElTraspaso() throws Exception {
        Usuario u = usuario(false);
        // Solo se apaga el HTTPS (como cuando su puerto está ocupado); el HTTP sigue
        java.lang.reflect.Method m = ServidorMovilService.class.getDeclaredMethod("detenerHttps");
        m.setAccessible(true);
        m.invoke(servidor);
        assertThat(servidor.httpsActivo()).isFalse();
        HttpResponse<String> r = enviar(http, base, "POST", "/api/sesion/traspaso", "{\"destino\":\"seguro\"}", u.token());
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(r.body()).get("codigo").asText()).isEqualTo("SIN_HTTPS");
        assertThat(enviar(http, base, "GET", "/", null, null).headers().firstValue("Content-Security-Policy").orElseThrow())
                .doesNotContain("https:");
    }

    @Test
    void conexionesSegurasQueNoMandanNadaNoFrenanALasDemas() throws Exception {
        // Chrome abre conexiones «por si acaso» que no mandan nada; antes ocupaban los 2 hilos 10 s
        java.util.List<java.net.Socket> ociosas = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) ociosas.add(new java.net.Socket("127.0.0.1", servidor.getPuertoHttps()));
            Thread.sleep(300);
            long t0 = System.nanoTime();
            HttpResponse<String> r = enviar(seguro, baseHttps, "GET", "/manifest.webmanifest", null, null);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(ms).as("respuesta con 4 conexiones ociosas abiertas").isLessThan(3000);
        } finally {
            for (java.net.Socket s : ociosas) s.close();
        }
    }

    // ── iPhone: icono de inicio ───────────────────────────────────────────

    @Test
    void elManifiestoConCodigoAbreElAsistenteDesdeElIconoSinGuardarseEnCache() throws Exception {
        Usuario u = usuario(false);
        JsonNode t = traspaso(u.token(), "inicio");
        String codigo = t.get("codigo").asText();
        assertThat(t.get("venceEnSegundos").asLong()).isEqualTo(TraspasosSesion.VIGENCIA_INICIO.toSeconds());
        assertThat(t.get("manifiesto").asText()).isEqualTo("/manifest.webmanifest?inicio=" + codigo);

        HttpResponse<String> m = enviar(seguro, baseHttps, "GET", t.get("manifiesto").asText(), null, null);
        assertThat(m.statusCode()).isEqualTo(200);
        assertThat(m.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(m.headers().firstValue("Content-Type")).hasValue("application/manifest+json");
        JsonNode j = JSON.readTree(m.body());
        assertThat(j.get("start_url").asText()).isEqualTo("/#traspaso=" + codigo + "&ir=avisos");
        assertThat(j.get("id").asText()).as("la app conserva su identidad").isEqualTo("/");
        assertThat(j.get("display").asText()).isEqualTo("standalone");

        // Sin código válido: el manifiesto normal (en caché, start_url "/")
        HttpResponse<String> normal = enviar(http, base, "GET", "/manifest.webmanifest?inicio=%3Cscript%3E", null, null);
        assertThat(JSON.readTree(normal.body()).get("start_url").asText()).isEqualTo("/");
        assertThat(normal.headers().firstValue("Cache-Control")).hasValue("public, max-age=86400");

        // Desde el icono: el código se canjea una vez
        assertThat(canjear(codigo).statusCode()).isEqualTo(200);
        assertThat(canjear(codigo).statusCode()).isEqualTo(401);
    }

    // ── Página ────────────────────────────────────────────────────────────

    @Test
    void laPaginaNormalPuedeProbarSoloLaDireccionSeguraDeEstaComputadora() throws Exception {
        HttpResponse<String> r = enviar(http, base, "GET", "/", null, null);
        String csp = r.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).contains("connect-src 'self' https://127.0.0.1:" + servidor.getPuertoHttps() + ";");
        // Por HTTPS no hace falta (todo es 'self')
        String cspSeguro = enviar(seguro, baseHttps, "GET", "/", null, null).headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(cspSeguro).contains("connect-src 'self';").doesNotContain("https:");
        // Con el nombre de la PC en Host, el origen seguro usa ese nombre (el certificado lo cubre)
        HttpResponse<String> conLocalhost = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + servidor.getPuerto() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(conLocalhost.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("connect-src 'self' https://localhost:" + servidor.getPuertoHttps() + ";");
        // El asistente está en la página: pasos, textos sencillos y la ruta de ajustes de cada teléfono
        String html = r.body();
        assertThat(html).contains("PASOS-AVISOS:INICIO").contains("Recibir avisos").contains("No me llegan los avisos")
                .contains("Ajustes de confianza de certificados").contains("Certificado de CA").contains("Agregar a inicio")
                .contains("Enviar aviso de prueba").contains("/api/sesion/traspaso").contains("/api/sesion/canjear");
    }

    @Test
    void elQrDelEscritorioAbreElAsistente() {
        assertThat(servidor.urlsAvisos(servidor.getPuerto()))
                .allMatch(x -> x.startsWith("http://") && x.endsWith(":" + servidor.getPuerto() + "/#/avisos"));
        assertThat(accesoMovil.urlsAvisos(servidor.getPuerto())).isEqualTo(servidor.urlsAvisos(servidor.getPuerto()));
    }

    // ── Escritorio: estado de avisos por teléfono y «Enviar prueba» ───────

    @Test
    void laListaDeTelefonosMuestraSiTienenAvisosYElAdministradorLesMandaUnaPrueba() throws Exception {
        Usuario con = usuario(false);
        Usuario sin = usuario(false);
        String endpoint = ServidorMovilHttpsPushTest.ENDPOINT_FCM + "-" + UUID.randomUUID();
        HttpResponse<String> alta = enviar(http, base, "POST", "/api/push/suscribir", JSON.writeValueAsString(Map.of("endpoint", endpoint,
                "keys", Map.of("p256dh", ServidorMovilHttpsPushTest.UA_PUBLICA, "auth", ServidorMovilHttpsPushTest.AUTH))), con.token());
        assertThat(alta.statusCode()).isEqualTo(200);

        var filas = accesoMovil.sesionesActivas();
        var filaCon = filas.stream().filter(f -> f.usuario().startsWith(con.login() + " ")).findFirst().orElseThrow();
        var filaSin = filas.stream().filter(f -> f.usuario().startsWith(sin.login() + " ")).findFirst().orElseThrow();
        assertThat(filaCon.avisos()).isNotNull();
        assertThat(filaSin.avisos()).isNull();
        assertThat(AccesoMovilService.estadoAvisos(filaCon.avisos())).isEqualTo("Avisos activos");
        assertThat(AccesoMovilService.estadoAvisos(filaSin.avisos())).isEqualTo("Sin avisos");

        int antes = enviador.llamadas.size();
        NotificacionesPushService.Envio e = accesoMovil.enviarPrueba(filaCon.avisos().getId());
        assertThat(e.ok()).isTrue();
        assertThat(enviador.llamadas).hasSize(antes + 1);
        assertThat(enviador.llamadas.get(antes).endpoint()).isEqualTo(endpoint);

        // Falló el último envío -> se nota en la lista; en pausa también
        enviador.estado = 500;
        assertThat(accesoMovil.enviarPrueba(filaCon.avisos().getId()).ok()).isFalse();
        enviador.estado = 201;
        SuscripcionPush s = accesoMovil.sesionesActivas().stream().filter(f -> f.usuario().startsWith(con.login() + " ")).findFirst().orElseThrow().avisos();
        assertThat(AccesoMovilService.estadoAvisos(s)).isEqualTo("Avisos activos (el último no llegó)");
        s.setActiva(false);
        assertThat(AccesoMovilService.estadoAvisos(s)).isEqualTo("Avisos en pausa");

        // Solo el administrador
        sesionService.cerrarSesion();
        Long id = s.getId();
        assertThatThrownBy(() -> accesoMovil.enviarPrueba(id)).isInstanceOf(RuntimeException.class);
    }
}
