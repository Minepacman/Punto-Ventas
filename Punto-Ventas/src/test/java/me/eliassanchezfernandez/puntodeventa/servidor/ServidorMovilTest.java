package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AccesoMovilService;
import me.eliassanchezfernandez.puntodeventa.service.AuditoriaService;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionMovilService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El servidor HTTP real en un puerto libre aleatorio, probado por localhost con
 * HttpClient. Sin transacción envolvente (el servidor usa otros hilos y otras
 * conexiones de BD) y nunca usa el puerto por defecto.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private AccesoMovilService accesoMovilService;
    @Autowired private SesionMovilService sesionMovilService;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AuditoriaService auditoriaService;

    private final HttpClient http = HttpClient.newHttpClient();
    private Clock relojOriginal;
    private RelojMutable reloj;
    private String base;

    @BeforeEach
    void arrancar() throws Exception {
        servidor.iniciar(0);                      // puerto libre elegido por el sistema
        base = "http://127.0.0.1:" + servidor.getPuerto();
        relojOriginal = (Clock) ReflectionTestUtils.getField(sesionMovilService, "reloj");
        reloj = new RelojMutable(Instant.now());
        ReflectionTestUtils.setField(sesionMovilService, "reloj", reloj);
        entrarEscritorioComoAdmin();
    }

    @AfterEach
    void detener() {
        servidor.detener();
        ReflectionTestUtils.setField(sesionMovilService, "reloj", relojOriginal);
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private void entrarEscritorioComoAdmin() throws Exception {
        Cajero a = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        a.setDebeCambiarContrasena(false);
        a.setBloqueadoHasta(null);
        a.setIntentosFallidos(0);
        cajeroRepo.save(a);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");
    }

    private String usuario(Cajero.Rol rol, Set<Permiso> permisos) {
        String login = "u" + UUID.randomUUID().toString().substring(0, 8);
        Cajero c = cajeroService.crear(login, "Nombre " + login, CLAVE, rol, permisos);
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        return login;
    }

    private record Resp(int estado, JsonNode cuerpo, HttpResponse<String> cruda) { }

    private Resp enviar(String metodo, String ruta, String cuerpo, String tipo, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (tipo != null) b.header("Content-Type", tipo);
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = null;
        try { j = JSON.readTree(r.body()); } catch (Exception ignorada) { /* no es JSON */ }
        return new Resp(r.statusCode(), j, r);
    }

    private Resp login(String usuario, String clave, boolean recordar) throws Exception {
        return enviar("POST", "/api/login", JSON.writeValueAsString(
                java.util.Map.of("usuario", usuario, "contrasena", clave, "recordar", recordar)), "application/json", null);
    }

    private Resp sesion(String token) throws Exception {
        return enviar("GET", "/api/sesion", null, null, token);
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────

    @Test
    void sirveLaPaginaConCabecerasDeSeguridad() throws Exception {
        Resp r = enviar("GET", "/", null, null, null);

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cruda().headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/html"));
        assertThat(r.cruda().headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(r.cruda().headers().firstValue("Cache-Control")).hasValue("no-cache");   // se revalida con ETag
        String csp = r.cruda().headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).contains("default-src 'none'").doesNotContain("http:").doesNotContain("https:");
        // Los scripts: solo el de la página, identificado por su hash; nunca 'unsafe-inline'
        String scripts = java.util.Arrays.stream(csp.split(";")).map(String::trim)
                .filter(d -> d.startsWith("script-src")).findFirst().orElseThrow();
        assertThat(scripts).doesNotContain("unsafe-inline").doesNotContain("unsafe-eval").contains("'sha256-");
        String html = r.cruda().body();
        String script = html.substring(html.indexOf("<script>") + 8, html.lastIndexOf("</script>"));
        String esperado = java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(scripts).contains("'sha256-" + esperado + "'");
        assertThat(html).doesNotContain("onclick=\"").doesNotContain("javascript:");
        assertThat(r.cruda().body()).contains("<title>").contains("capture=\"environment\"")
                .doesNotContain("cdn.").doesNotContain("innerHTML =").doesNotContain("src=\"http");
    }

    @Test
    void laPaginaSeSirveComprimidaYConEtag() throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base + "/"))
                .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Encoding")).hasValue("gzip");
        String etag = r.headers().firstValue("ETag").orElseThrow();
        byte[] plano = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(r.body())).readAllBytes();
        assertThat(new String(plano, java.nio.charset.StandardCharsets.UTF_8)).contains("<title>");
        assertThat(r.body().length).isLessThan(plano.length / 2);

        HttpResponse<String> sin = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(sin.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(sin.headers().firstValue("ETag")).hasValue(etag);

        HttpResponse<String> igual = http.send(HttpRequest.newBuilder(URI.create(base + "/"))
                .header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(igual.statusCode()).isEqualTo(304);
        assertThat(igual.body()).isEmpty();
    }

    @Test
    void unHostAjenoSeRechazaContraElRebindingDeDns() throws Exception {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", servidor.getPuerto())) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(("GET /api/sesion HTTP/1.1\r\nHost: malo.example.com\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String resp = new String(s.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(resp).startsWith("HTTP/1.1 400").contains("\"codigo\":\"HOST\"");
        }
    }

    @Test
    void soloSeAceptanOrigenesDeLaRedLocal() throws Exception {
        for (String ok : new String[] {"127.0.0.1", "192.168.1.20", "10.0.0.7", "172.16.5.5", "172.31.255.1", "169.254.10.10", "fd12:3456::1", "fe80::1", "::1"}) {
            assertThat(ServidorMovilService.origenPermitido(java.net.InetAddress.getByName(ok))).as(ok).isTrue();
        }
        for (String mal : new String[] {"8.8.8.8", "172.32.0.1", "100.64.0.1", "203.0.113.9", "2001:4860:4860::8888"}) {
            assertThat(ServidorMovilService.origenPermitido(java.net.InetAddress.getByName(mal))).as(mal).isFalse();
        }
        assertThat(ServidorMovilService.origenPermitido(null)).isFalse();
    }

    @Test
    void hostPermitidoSoloIpLocalhostONombreDeLaPc() throws Exception {
        assertThat(servidor.hostPermitido("192.168.1.5:8085")).isTrue();
        assertThat(servidor.hostPermitido("localhost:8085")).isTrue();
        assertThat(servidor.hostPermitido("LOCALHOST")).isTrue();
        assertThat(servidor.hostPermitido("[fe80::1]:8085")).isTrue();
        String equipo = java.net.InetAddress.getLocalHost().getHostName().toLowerCase();
        assertThat(servidor.hostPermitido(equipo + ":8085")).isTrue();
        assertThat(servidor.hostPermitido(equipo + ".local")).isTrue();
        assertThat(servidor.hostPermitido(null)).isFalse();
        assertThat(servidor.hostPermitido("")).isFalse();
        assertThat(servidor.hostPermitido("evil.com")).isFalse();
        assertThat(servidor.hostPermitido("evil.com:8085")).isFalse();
        assertThat(servidor.hostPermitido("192.168.1.5.evil.com")).isFalse();
        assertThat(servidor.hostPermitido("192.168.1.5:abc")).isFalse();
    }

    @Test
    void elPoolEstaAcotadoYRechazaCuandoLaColaSeLlena() throws Exception {
        java.util.concurrent.ThreadPoolExecutor pool = ServidorMovilService.crearPool();
        java.util.concurrent.CountDownLatch bloqueo = new java.util.concurrent.CountDownLatch(1);
        try {
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(32);
            Runnable espera = () -> { try { bloqueo.await(); } catch (InterruptedException ignorada) { Thread.currentThread().interrupt(); } };
            for (int i = 0; i < 4 + 32; i++) pool.execute(espera);       // 4 hilos + 32 en cola
            assertThat(pool.getPoolSize()).isEqualTo(4);
            assertThatThrownBy(() -> pool.execute(espera)).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        } finally {
            bloqueo.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void losLimitesDelHttpServerEstanFijados() {
        assertThat(System.getProperty("sun.net.httpserver.maxReqTime")).isNotBlank();
        assertThat(System.getProperty("sun.net.httpserver.maxRspTime")).isNotBlank();
        assertThat(System.getProperty("sun.net.httpserver.maxConnections")).isNotBlank();
    }

    @Test
    void iniciarYDetenerEsEnVivo() throws Exception {
        assertThat(servidor.estaActivo()).isTrue();
        int puerto = servidor.getPuerto();
        assertThat(puerto).isNotEqualTo(8085).isGreaterThan(0);

        servidor.detener();

        assertThat(servidor.estaActivo()).isFalse();
        assertThat(servidor.getPuerto()).isZero();
        assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/")).build(),
                HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);

        servidor.iniciar(0);   // se puede volver a encender
        assertThat(servidor.estaActivo()).isTrue();
    }

    @Test
    void unPuertoOcupadoDaUnErrorClaroYNoTumbaNada() throws Exception {
        servidor.detener();
        try (ServerSocket ocupado = new ServerSocket(0)) {
            int puerto = ocupado.getLocalPort();
            assertThatThrownBy(() -> servidor.iniciar(puerto))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("ya está en uso");
            assertThat(servidor.estaActivo()).isFalse();

            // Por la ruta de Configuración: tampoco queda guardado como activo
            assertThatThrownBy(() -> accesoMovilService.configurar(true, puerto)).isInstanceOf(NegocioException.class);
            assertThat(configuracionService.obtener().isMovilActivo()).isFalse();
        }
    }

    @Test
    void configurarEncendidoYApagadoDesdeElEscritorioFuncionaEnVivo() throws Exception {
        servidor.detener();
        int libre;
        try (ServerSocket s = new ServerSocket(0)) { libre = s.getLocalPort(); }

        accesoMovilService.configurar(true, libre);
        assertThat(servidor.estaActivo()).isTrue();
        assertThat(servidor.getPuerto()).isEqualTo(libre);
        assertThat(configuracionService.obtener().isMovilActivo()).isTrue();
        assertThat(configuracionService.obtener().getMovilPuerto()).isEqualTo(libre);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + libre + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);

        accesoMovilService.configurar(false, libre);
        assertThat(servidor.estaActivo()).isFalse();
        assertThat(configuracionService.obtener().isMovilActivo()).isFalse();
        assertThat(auditoriaService.ultimas()).anySatisfy(a ->
                assertThat(a.getDetalle()).contains("Acceso móvil").contains("activo=true"));
    }

    // ── Login y sesión ────────────────────────────────────────────────────

    @Test
    void loginCorrectoDevuelveTokenPermisosYQuedaAuditado() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.of(Permiso.VER_HISTORIAL));

        Resp r = login(u, CLAVE, false);

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cruda().headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(r.cruda().headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(r.cuerpo().get("token").asText()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(r.cuerpo().get("permanente").asBoolean()).isFalse();
        assertThat(r.cuerpo().get("expira").asLong() - r.cuerpo().get("ahora").asLong())
                .isBetween(Duration.ofHours(48).toMillis() - 5_000, Duration.ofHours(48).toMillis() + 5_000);
        assertThat(r.cuerpo().get("permisos").toString()).contains("VER_HISTORIAL");
        assertThat(r.cuerpo().toString()).doesNotContain("contrasena").doesNotContain("hash");
        assertThat(auditoriaService.ultimas()).anySatisfy(a -> {
            assertThat(a.getAccion()).isEqualTo("LOGIN_MOVIL");
            assertThat(a.getUsuarioLogin()).isEqualTo(u);
        });
    }

    @Test
    void elLoginMovilNoCambiaLaSesionDelEscritorio() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        assertThat(sesionService.getCajeroActual().getUsuario()).isEqualTo("admin");

        assertThat(login(u, CLAVE, false).estado()).isEqualTo(200);

        assertThat(sesionService.getCajeroActual().getUsuario()).isEqualTo("admin");
        assertThat(sesionService.esAdmin()).isTrue();
    }

    @Test
    void credencialesIncorrectasDanUnSoloMensajeYNuncaBloqueanElEscritorio() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));

        Resp mal = login(u, "incorrecta1", false);
        Resp inexistente = login("noexiste" + UUID.randomUUID().toString().substring(0, 6), "x", false);
        assertThat(mal.estado()).isEqualTo(401);
        assertThat(mal.cuerpo().get("error").asText()).isEqualTo(inexistente.cuerpo().get("error").asText())
                .isEqualTo("Usuario o contraseña incorrectos.");

        // 5 fallos móviles: el usuario queda limitado SOLO en el móvil
        for (int i = 0; i < 4; i++) assertThat(login(u, "incorrecta1", false).estado()).isEqualTo(401);
        Resp limitado = login(u, CLAVE, false);                      // aun con la clave buena
        assertThat(limitado.estado()).isEqualTo(429);
        assertThat(limitado.cuerpo().get("codigo").asText()).isEqualTo("DEMASIADOS_INTENTOS");
        assertThat(limitado.cruda().headers().firstValue("Retry-After")).isPresent();

        // El escritorio NO se enteró: ni contador, ni bloqueo, y el usuario entra normal
        Cajero enBd = cajeroRepo.findByUsuarioIgnoreCase(u).orElseThrow();
        assertThat(enBd.getIntentosFallidosOCero()).isZero();
        assertThat(enBd.isBloqueado()).isFalse();
        assertThat(sesionService.verificarCredenciales(u, CLAVE).getUsuario()).isEqualTo(u);
        assertThat(auditoriaService.ultimas().stream().filter(a -> u.equals(a.getUsuarioLogin())).map(a -> a.getAccion()))
                .contains("LOGIN_MOVIL_FALLIDO").doesNotContain("USUARIO_BLOQUEADO", "LOGIN_FALLIDO");
        assertThat(auditoriaService.ultimas()).anySatisfy(a -> {
            assertThat(a.getAccion()).isEqualTo("LOGIN_MOVIL_FALLIDO");
            assertThat(a.getDetalle()).contains("ip 127.0.0.1");        // N-11: la IP del teléfono
        });
    }

    @Test
    void fallarEnElEscritorioTampocoBloqueaElMovilPeroUnBloqueoDeEscritorioSiLoRechaza() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        for (int i = 0; i < 5; i++) {
            try { sesionService.verificarCredenciales(u, "incorrecta1"); } catch (Exception esperado) { /* cuenta */ }
        }
        assertThat(cajeroRepo.findByUsuarioIgnoreCase(u).orElseThrow().isBloqueado()).isTrue();

        Resp r = login(u, CLAVE, false);   // bloqueado en el escritorio: el móvil lo rechaza, sin decir por qué
        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.cuerpo().get("error").asText()).isEqualTo("Usuario o contraseña incorrectos.");
        assertThat(r.cuerpo().toString()).doesNotContain("bloquea");

        sesionService.cerrarSesion();
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(u).orElseThrow();
        c.setBloqueadoHasta(null);
        c.setIntentosFallidos(0);
        cajeroRepo.save(c);
        assertThat(login(u, CLAVE, false).estado()).isEqualTo(200);
    }

    @Test
    void unUsuarioInactivoNoEntra() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        cajeroService.cambiarEstado(cajeroRepo.findByUsuarioIgnoreCase(u).orElseThrow().getId(), false);

        assertThat(login(u, CLAVE, false).estado()).isEqualTo(401);
    }

    @Test
    void conCambioDeContrasenaPendienteHayQueHacerloPrimeroEnElEscritorio() throws Exception {
        String u = "pend" + UUID.randomUUID().toString().substring(0, 6);
        cajeroService.crear(u, "Pendiente", CLAVE, Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));   // nace con cambio obligatorio

        Resp r = login(u, CLAVE, false);

        assertThat(r.estado()).isEqualTo(403);
        assertThat(r.cuerpo().get("codigo").asText()).isEqualTo("CAMBIO_CONTRASENA");
        assertThat(r.cuerpo().has("token")).isFalse();
    }

    @Test
    void limitePorIpTrasMuchosFallosAunConUsuariosDistintos() throws Exception {
        for (int i = 0; i < ServidorMovilService.MAX_FALLOS_POR_IP; i++) {
            assertThat(login("fantasma" + i, "x", false).estado()).isEqualTo(401);
        }
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));

        Resp r = login(u, CLAVE, false);   // ni siquiera con credenciales buenas

        assertThat(r.estado()).isEqualTo(429);
        assertThat(r.cruda().headers().firstValue("Retry-After")).isPresent();
        assertThat(r.cuerpo().get("codigo").asText()).isEqualTo("DEMASIADOS_INTENTOS");
    }

    @Test
    void sesionExigeTokenYLogoutLoRevoca() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        String token = login(u, CLAVE, false).cuerpo().get("token").asText();

        assertThat(sesion(null).estado()).isEqualTo(401);
        assertThat(sesion("inventado").estado()).isEqualTo(401);
        Resp ok = sesion(token);
        assertThat(ok.estado()).isEqualTo(200);
        assertThat(ok.cuerpo().get("usuario").asText()).isEqualTo(u);
        assertThat(ok.cuerpo().has("token")).isFalse();

        assertThat(enviar("POST", "/api/logout", "{}", "application/json", token).estado()).isEqualTo(200);

        Resp despues = sesion(token);
        assertThat(despues.estado()).isEqualTo(401);
        assertThat(despues.cuerpo().get("codigo").asText()).isEqualTo("SESION_INVALIDA");
        assertThat(auditoriaService.ultimas()).extracting(a -> a.getAccion()).contains("LOGOUT_MOVIL");
    }

    @Test
    void elServidorHaceCumplirLas48HorasAunqueElTelefonoNoLoSepa() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        String token = login(u, CLAVE, false).cuerpo().get("token").asText();

        reloj.avanzar(Duration.ofHours(48).minusSeconds(1));
        assertThat(sesion(token).estado()).isEqualTo(200);
        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(sesion(token).estado()).isEqualTo(401);
    }

    @Test
    void laSesionPermanenteCaducaPorInactividadYSubirAPermanenteExigeLaContrasena() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        String directa = login(u, CLAVE, true).cuerpo().get("token").asText();   // la contraseña acaba de escribirse
        reloj.avanzar(Duration.ofDays(59));
        Resp r = sesion(directa);
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo().get("permanente").asBoolean()).isTrue();
        assertThat(r.cuerpo().get("expira").isNull()).isTrue();
        reloj.avanzar(Duration.ofDays(59));                   // usada hace 59 días: sigue (deslizante)
        assertThat(sesion(directa).estado()).isEqualTo(200);
        reloj.avanzar(Duration.ofDays(61));                   // 61 días sin usar
        assertThat(sesion(directa).estado()).isEqualTo(401);

        // Flujo de la página: entra (48 h) y luego contesta «Sí» => debe reenviar la contraseña
        String token = login(u, CLAVE, false).cuerpo().get("token").asText();
        Resp sinClave = enviar("POST", "/api/sesion/recordar", "{\"recordar\":true}", "application/json", token);
        assertThat(sinClave.estado()).isEqualTo(401);
        assertThat(sinClave.cuerpo().get("codigo").asText()).isEqualTo("CREDENCIALES");
        Resp mal = enviar("POST", "/api/sesion/recordar", "{\"recordar\":true,\"contrasena\":\"incorrecta1\"}", "application/json", token);
        assertThat(mal.estado()).isEqualTo(401);
        assertThat(sesion(token).cuerpo().get("permanente").asBoolean()).isFalse();   // sigue en 48 h

        Resp si = enviar("POST", "/api/sesion/recordar", "{\"recordar\":true,\"contrasena\":\"" + CLAVE + "\"}", "application/json", token);
        assertThat(si.estado()).isEqualTo(200);
        assertThat(si.cuerpo().get("permanente").asBoolean()).isTrue();
        assertThat(auditoriaService.ultimas()).anySatisfy(a -> {
            assertThat(a.getAccion()).isEqualTo("SESION_MOVIL_PERMANENTE");
            assertThat(a.getDetalle()).contains("ip 127.0.0.1");
        });

        // Volver a 48 h no pide contraseña
        Resp no = enviar("POST", "/api/sesion/recordar", "{\"recordar\":false}", "application/json", token);
        assertThat(no.estado()).isEqualTo(200);
        assertThat(no.cuerpo().get("permanente").asBoolean()).isFalse();
    }

    @Test
    void elMaximoDe5SesionesPorUsuarioTambienAplicaPorElServidor() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        String primera = login(u, CLAVE, false).cuerpo().get("token").asText();
        reloj.avanzar(Duration.ofMinutes(1));
        for (int i = 0; i < 5; i++) { login(u, CLAVE, false); reloj.avanzar(Duration.ofMinutes(1)); }
        assertThat(sesion(primera).estado()).isEqualTo(401);       // la sexta desplazó a la más vieja
        assertThat(sesionMovilService.listarActivas().stream()
                .filter(x -> x.getCajeroId().equals(cajeroRepo.findByUsuarioIgnoreCase(u).orElseThrow().getId())).count())
                .isEqualTo(5);
    }

    @Test
    void desactivarOCambiarLaContrasenaRevocaLaSesionMovil() throws Exception {
        String u1 = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        String t1 = login(u1, CLAVE, true).cuerpo().get("token").asText();
        cajeroService.cambiarEstado(cajeroRepo.findByUsuarioIgnoreCase(u1).orElseThrow().getId(), false);
        assertThat(sesion(t1).estado()).isEqualTo(401);

        String u2 = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        String t2 = login(u2, CLAVE, true).cuerpo().get("token").asText();
        cajeroService.cambiarContrasena(cajeroRepo.findByUsuarioIgnoreCase(u2).orElseThrow().getId(), "otraClave88");
        assertThat(sesion(t2).estado()).isEqualTo(401);
    }

    @Test
    void elAdminCierraUnaSesionMovilDesdeElEscritorio() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        String token = login(u, CLAVE, true).cuerpo().get("token").asText();
        var info = accesoMovilService.sesionesActivas().stream()
                .filter(i -> i.usuario().startsWith(u)).findFirst().orElseThrow();

        accesoMovilService.revocar(info.sesion().getId());

        assertThat(sesion(token).estado()).isEqualTo(401);
    }

    // ── Permisos que la página usa para ocultar secciones ────────────────

    @Test
    void sesionDevuelveLasSeccionesSegunLosPermisos() throws Exception {
        String sin = usuario(Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        String hist = usuario(Cajero.Rol.CAJERO, EnumSet.of(Permiso.VER_HISTORIAL));
        String inv = usuario(Cajero.Rol.CAJERO, EnumSet.of(Permiso.AJUSTAR_INVENTARIO));

        JsonNode s = sesion(login(sin, CLAVE, false).cuerpo().get("token").asText()).cuerpo().get("secciones");
        assertThat(s.get("estadisticas").asBoolean()).isFalse();
        assertThat(s.get("historial").asBoolean()).isFalse();
        assertThat(s.get("inventario").asBoolean()).isTrue();
        assertThat(s.get("inventarioAjustar").asBoolean()).isFalse();
        assertThat(s.get("escanearTicket").asBoolean()).isFalse();
        assertThat(s.get("configuracion").asBoolean()).isTrue();

        s = sesion(login(hist, CLAVE, false).cuerpo().get("token").asText()).cuerpo().get("secciones");
        assertThat(s.get("estadisticas").asBoolean()).isTrue();
        assertThat(s.get("historial").asBoolean()).isTrue();
        assertThat(s.get("inventarioAjustar").asBoolean()).isFalse();

        s = sesion(login(inv, CLAVE, false).cuerpo().get("token").asText()).cuerpo().get("secciones");
        assertThat(s.get("estadisticas").asBoolean()).isFalse();
        assertThat(s.get("inventarioAjustar").asBoolean()).isTrue();
        assertThat(s.get("escanearTicket").asBoolean()).isTrue();

        // El admin lo ve todo
        s = sesion(login("admin", "admin123", false).cuerpo().get("token").asText()).cuerpo().get("secciones");
        s.fields().forEachRemaining(e -> assertThat(e.getValue().asBoolean()).as(e.getKey()).isTrue());
    }

    @Test
    void siSeQuitaUnPermisoLaSiguienteLlamadaYaLoRefleja() throws Exception {
        String u = usuario(Cajero.Rol.CAJERO, EnumSet.of(Permiso.VER_HISTORIAL));
        String token = login(u, CLAVE, false).cuerpo().get("token").asText();
        assertThat(sesion(token).cuerpo().get("secciones").get("historial").asBoolean()).isTrue();

        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(u).orElseThrow();
        cajeroService.actualizar(c.getId(), c.getUsuario(), c.getNombreCompleto(), c.getRol(), true, null,
                EnumSet.noneOf(Permiso.class));

        assertThat(sesion(token).cuerpo().get("secciones").get("historial").asBoolean()).isFalse();
    }

    // ── Robustez de la entrada ────────────────────────────────────────────

    @Test
    void rechazaCuerpoGrandeTipoIncorrectoMetodoYRutasAjenas() throws Exception {
        String grande = "{\"usuario\":\"" + "a".repeat(10_000) + "\"}";
        assertThat(enviar("POST", "/api/login", grande, "application/json", null).estado()).isEqualTo(413);
        assertThat(enviar("POST", "/api/login", "usuario=a", "application/x-www-form-urlencoded", null).estado()).isEqualTo(415);
        assertThat(enviar("POST", "/api/login", "{no es json", "application/json", null).estado()).isEqualTo(400);
        assertThat(enviar("GET", "/api/login", null, null, null).estado()).isEqualTo(405);
        assertThat(enviar("POST", "/", "{}", "application/json", null).estado()).isEqualTo(405);
        assertThat(enviar("GET", "/api/otra", null, null, null).estado()).isEqualTo(404);
        assertThat(enviar("GET", "/../etc/passwd", null, null, null).estado()).isIn(400, 404);
        assertThat(login("a".repeat(500), "x", false).estado()).isEqualTo(400);
    }
}
