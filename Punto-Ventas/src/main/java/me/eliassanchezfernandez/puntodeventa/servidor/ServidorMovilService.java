package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Totales;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import me.eliassanchezfernandez.puntodeventa.service.AuditoriaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionMovilService;
import me.eliassanchezfernandez.puntodeventa.service.SesionMovilService.SesionValida;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService.CredencialesInvalidasException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.Inet6Address;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import java.net.URLDecoder;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

/**
 * Servidor web local para la interfaz móvil (teléfonos en la misma red Wi-Fi).
 *
 * Usa el HttpServer incluido en el JDK (NO spring-boot-starter-web: la app
 * sigue siendo "no web" y JavaFX manda). Sirve la página única
 * (resources/movil/index.html) y una API JSON mínima:
 *
 *   POST /api/login           {usuario, contrasena, recordar}  -> token + usuario + permisos
 *   GET  /api/sesion          (Bearer)                          -> valida el token, devuelve permisos
 *   POST /api/sesion/recordar {recordar}  (Bearer)             -> sesión permanente / 48 h
 *   POST /api/logout          (Bearer)                          -> revoca el token
 *
 *   GET  /api/estadisticas?periodo=dia|semana|mes   (VER_HISTORIAL)
 *   GET  /api/ventas?desde=YYYY-MM-DD&hasta=YYYY-MM-DD (VER_HISTORIAL, máx. 93 días y 500 ventas)
 *   GET  /api/inventario?q=                          (cualquier usuario; costos solo con VER_GANANCIAS)
 *   POST /api/inventario/ajuste {productoId, tipo: entrada|ajuste|merma, cantidad, motivo, proveedor?, costo?}
 *                                                    (AJUSTAR_INVENTARIO)
 *
 * Cada endpoint de datos valida el token, relee al usuario de la BD y ejecuta el
 * servicio dentro de SesionService.ejecutarComo(usuarioMovil, ip, ...): los
 * servicios verifican permisos y auditan como el usuario MÓVIL, y la sesión de
 * escritorio ni se lee ni se modifica. El OCR de tickets sigue siendo un demo en la página.
 *
 * Seguridad: solo escucha si el ADMIN lo activó (por defecto apagado); límite
 * de intentos fallidos por IP además del bloqueo por usuario; cuerpos de
 * máximo 4 KB; cabeceras nosniff / no-store / CSP; el escritorio nunca cambia
 * de usuario por un login móvil (se usa SesionService.verificarCredenciales,
 * que no abre sesión).
 */
@Service
public class ServidorMovilService {

    static final int MAX_CUERPO_BYTES = 4096;
    static final int MAX_FALLOS_POR_IP = 10;
    static final Duration VENTANA_FALLOS = Duration.ofMinutes(5);

    /** Hilos y cola del servidor: acotados para que una PC con poca RAM no se ahogue */
    static final int HILOS_MIN = 2;
    static final int HILOS_MAX = 4;
    static final int COLA_MAXIMA = 32;
    /** Fallos de contraseña por usuario (solo acceso móvil) antes de pedir esperar */
    static final int MAX_FALLOS_POR_USUARIO = 5;
    static final Duration VENTANA_FALLOS_USUARIO = Duration.ofMinutes(15);

    static {
        // Límites del HttpServer del JDK (se leen una sola vez, al cargar su clase). Si el
        // lanzador ya los fijó con -D, se respetan.
        fijarPropiedad("sun.net.httpserver.maxReqTime", "10");      // segundos para recibir una petición
        fijarPropiedad("sun.net.httpserver.maxRspTime", "30");      // segundos para enviar la respuesta
        fijarPropiedad("sun.net.httpserver.maxConnections", "64");  // conexiones abiertas a la vez
    }

    private static void fijarPropiedad(String clave, String valor) {
        if (System.getProperty(clave) == null) System.setProperty(clave, valor);
    }

    /** CSP sin 'unsafe-inline' para scripts: solo corre el script cuyo SHA-256 se calcula de la página servida. */
    private static String csp(String hashScript) {
        return "default-src 'none'; script-src " + (hashScript == null ? "'none'" : "'" + hashScript + "'")
                + "; style-src 'unsafe-inline'; "
                + "img-src 'self' data: blob:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    }

    @Autowired private SesionService        sesionService;
    @Autowired private SesionMovilService   sesionMovilService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AuditoriaService     auditoriaService;
    @Autowired private Clock                reloj;
    @Autowired private EstadisticasService  estadisticasService;
    @Autowired private VentaService         ventaService;
    @Autowired private ProductoService      productoService;

    /** Pruebas: poner en false para que no arranque solo aunque la BD diga "activo" */
    @Value("${app.movil.autoarranque:true}")
    private boolean autoarranque;

    private final ObjectMapper json = new ObjectMapper();
    private volatile HttpServer servidor;
    private volatile ExecutorService hilos;
    private volatile LimitadorIntentos limitador;
    private volatile LimitadorIntentos limitadorUsuarios;
    private volatile Pagina paginaCache;
    private volatile String nombreEquipo;

    /** La página lista para servir: normal, comprimida, su ETag y la CSP con el hash de su script */
    record Pagina(byte[] normal, byte[] gzip, String etag, String csp) { }

    // ── Ciclo de vida ─────────────────────────────────────────────────────

    /** Con el contexto listo: arranca si el administrador lo dejó activado. Nunca tumba la app. */
    @EventListener(ApplicationReadyEvent.class)
    public void arrancarSiEstaActivo() {
        if (!autoarranque) return;
        try {
            Configuracion c = configuracionService.obtener();
            if (c.isMovilActivo()) iniciar(c.getMovilPuerto());
        } catch (Exception e) {
            System.err.println("⚠ No se pudo iniciar el acceso móvil: " + e.getMessage());
        }
    }

    /** Se cierra junto con el contexto de Spring */
    @PreDestroy
    public void alCerrarContexto() {
        detener();
    }

    /**
     * Inicia el servidor en todas las interfaces. puerto 0 = uno libre (pruebas).
     * @throws NegocioException si el puerto está ocupado o no se pudo abrir
     */
    public synchronized void iniciar(int puerto) {
        if (servidor != null) return;
        HttpServer nuevo;
        try {
            nuevo = HttpServer.create(new InetSocketAddress(puerto), 0);
        } catch (BindException e) {
            throw new NegocioException("El puerto " + puerto + " ya está en uso por otro programa. Elige otro puerto.");
        } catch (IOException | RuntimeException e) {
            throw new NegocioException("No se pudo abrir el puerto " + puerto + ": " + e.getMessage());
        }
        ExecutorService pool = crearPool();
        limitador = new LimitadorIntentos(reloj, MAX_FALLOS_POR_IP, VENTANA_FALLOS);
        limitadorUsuarios = new LimitadorIntentos(reloj, MAX_FALLOS_POR_USUARIO, VENTANA_FALLOS_USUARIO);
        nuevo.createContext("/", this::atender);
        nuevo.setExecutor(pool);
        nuevo.start();
        servidor = nuevo;
        hilos = pool;
        System.out.println("📱 Acceso móvil activo en el puerto " + nuevo.getAddress().getPort());
    }

    /** 2–4 hilos y cola de 32: con la cola llena se rechaza (el servidor cierra la conexión) en vez de crecer sin límite */
    static ThreadPoolExecutor crearPool() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(HILOS_MIN, HILOS_MAX, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(COLA_MAXIMA), r -> {
                    Thread t = new Thread(r, "acceso-movil-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized void detener() {
        HttpServer s = servidor;
        if (s == null) return;
        servidor = null;
        s.stop(0);
        if (hilos != null) hilos.shutdownNow();
        hilos = null;
        System.out.println("📱 Acceso móvil detenido");
    }

    public boolean estaActivo() {
        return servidor != null;
    }

    /** Puerto en el que escucha (0 si está apagado) */
    public int getPuerto() {
        HttpServer s = servidor;
        return s == null ? 0 : s.getAddress().getPort();
    }

    /** Direcciones para abrir desde el teléfono: IPv4 privadas no loopback de las interfaces activas */
    public List<String> urlsLan(int puerto) {
        List<String> urls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return urls;
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        urls.add("http://" + a.getHostAddress() + ":" + puerto + "/");
                    }
                }
            }
        } catch (IOException ignorada) { /* sin red: lista vacía */ }
        return urls;
    }

    // ── Enrutado ──────────────────────────────────────────────────────────

    /** Solo redes locales: loopback, privadas (10/8, 172.16/12, 192.168/16, fc00::/7) y de enlace local */
    static boolean origenPermitido(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;   // fc00::/7
    }

    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * Host de la petición: solo IP literal, localhost o el nombre de esta PC (con .local).
     * Un nombre de dominio ajeno apuntando a esta IP (DNS rebinding) se rechaza.
     */
    boolean hostPermitido(String cabecera) {
        if (cabecera == null || cabecera.isBlank()) return false;
        String h = cabecera.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {                       // IPv6 literal: [fe80::1]:8085
            int fin = h.indexOf(']');
            return fin > 1 && (fin == h.length() - 1 || h.charAt(fin + 1) == ':');
        }
        int dos = h.lastIndexOf(':');
        if (dos >= 0) {
            if (!h.substring(dos + 1).matches("\\d{1,5}")) return false;
            h = h.substring(0, dos);
        }
        if (h.equals("localhost") || IPV4.matcher(h).matches()) return true;
        String equipo = nombreDeEquipo();
        return !equipo.isEmpty() && (h.equals(equipo) || h.equals(equipo + ".local"));
    }

    private String nombreDeEquipo() {
        String n = nombreEquipo;
        if (n == null) {
            try {
                n = InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
            } catch (IOException | RuntimeException e) {
                n = "";
            }
            nombreEquipo = n;
        }
        return n;
    }

    private void atender(HttpExchange ex) throws IOException {
        try {
            if (!origenPermitido(ex.getRemoteAddress().getAddress())) {
                error(ex, 403, "ORIGEN", "Acceso no permitido desde esta red.");
                return;
            }
            if (!hostPermitido(ex.getRequestHeaders().getFirst("Host"))) {
                error(ex, 400, "HOST", "Dirección no permitida. Abre la página con la IP de la computadora.");
                return;
            }
            String ruta = ex.getRequestURI().getPath();
            String metodo = ex.getRequestMethod();
            switch (ruta) {
                case "/", "/index.html" -> pagina(ex, metodo);
                case "/api/login"           -> { exigir(metodo, "POST"); login(ex); }
                case "/api/sesion"          -> { exigir(metodo, "GET");  sesion(ex); }
                case "/api/sesion/recordar" -> { exigir(metodo, "POST"); recordar(ex); }
                case "/api/logout"          -> { exigir(metodo, "POST"); logout(ex); }
                case "/api/estadisticas"    -> { exigir(metodo, "GET");  estadisticas(ex); }
                case "/api/ventas"          -> { exigir(metodo, "GET");  ventas(ex); }
                case "/api/inventario"      -> { exigir(metodo, "GET");  inventario(ex); }
                case "/api/inventario/ajuste" -> { exigir(metodo, "POST"); ajusteInventario(ex); }
                default -> error(ex, 404, "NO_ENCONTRADO", "No encontrado.");
            }
        } catch (MetodoNoPermitido e) {
            ex.getResponseHeaders().set("Allow", e.permitido);
            error(ex, 405, "METODO", "Método no permitido.");
        } catch (CuerpoInvalido e) {
            error(ex, e.estado, "SOLICITUD_INVALIDA", e.getMessage());
        } catch (NegocioException e) {
            error(ex, 400, "NEGOCIO", e.getMessage());
        } catch (Throwable t) {
            t.printStackTrace();
            error(ex, 500, "ERROR", "Error interno del servidor.");
        } finally {
            ex.close();
        }
    }

    private static final class MetodoNoPermitido extends RuntimeException {
        final String permitido;
        MetodoNoPermitido(String p) { super(null, null, false, false); permitido = p; }
    }

    private static final class CuerpoInvalido extends RuntimeException {
        final int estado;
        CuerpoInvalido(int estado, String msg) { super(msg, null, false, false); this.estado = estado; }
    }

    private static void exigir(String metodo, String esperado) {
        if (!esperado.equals(metodo)) throw new MetodoNoPermitido(esperado);
    }

    // ── Página ────────────────────────────────────────────────────────────

    private Pagina cargarPagina() throws IOException {
        Pagina p = paginaCache;
        if (p != null) return p;
        byte[] cuerpo;
        try (InputStream in = ServidorMovilService.class.getResourceAsStream("/movil/index.html")) {
            if (in == null) return null;
            cuerpo = in.readAllBytes();
        }
        String html = new String(cuerpo, StandardCharsets.UTF_8);
        String hashScript = null;
        int ini = html.indexOf("<script>");
        int fin = html.lastIndexOf("</script>");
        if (ini >= 0 && fin > ini) {
            byte[] script = html.substring(ini + "<script>".length(), fin).getBytes(StandardCharsets.UTF_8);
            hashScript = "sha256-" + Base64.getEncoder().encodeToString(sha256(script));
        }
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(cuerpo);
        }
        String etag = "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(cuerpo)).substring(0, 22) + "\"";
        p = new Pagina(cuerpo, gz.toByteArray(), etag, csp(hashScript));
        paginaCache = p;
        return p;
    }

    private static byte[] sha256(byte[] datos) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(datos);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void pagina(HttpExchange ex, String metodo) throws IOException {
        if (!"GET".equals(metodo) && !"HEAD".equals(metodo)) throw new MetodoNoPermitido("GET, HEAD");
        Pagina p = cargarPagina();
        if (p == null) { error(ex, 500, "ERROR", "Falta la página móvil."); return; }
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "text/html; charset=utf-8");
        h.set("Content-Security-Policy", p.csp());
        cabecerasComunes(ex);
        // La página sí se puede guardar, pero siempre revalidando (ETag): pesa 58 KB y casi nunca cambia
        h.set("Cache-Control", "no-cache");
        h.set("ETag", p.etag());
        h.set("Vary", "Accept-Encoding");
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null && inm.contains(p.etag())) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        boolean gzip = ae != null && ae.toLowerCase(Locale.ROOT).contains("gzip");
        byte[] cuerpo = gzip ? p.gzip() : p.normal();
        if (gzip) h.set("Content-Encoding", "gzip");
        if ("HEAD".equals(metodo)) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, cuerpo.length);
        ex.getResponseBody().write(cuerpo);
    }

    // ── API ───────────────────────────────────────────────────────────────

    /** Segundos de espera por IP o por usuario (0 = puede intentar). El usuario cuenta aunque no exista. */
    private long esperaLogin(String ip, String usuario) {
        long porIp = limitador.segundosDeEspera(ip);
        long porUsuario = usuario.isEmpty() ? 0 : limitadorUsuarios.segundosDeEspera(claveUsuario(usuario));
        return Math.max(porIp, porUsuario);
    }

    private void registrarFalloLogin(String ip, String usuario) {
        limitador.registrarFallo(ip);
        if (!usuario.isEmpty()) limitadorUsuarios.registrarFallo(claveUsuario(usuario));
    }

    private static String claveUsuario(String usuario) {
        return usuario.trim().toLowerCase(Locale.ROOT);
    }

    private void demasiadosIntentos(HttpExchange ex, long espera) throws IOException {
        ex.getResponseHeaders().set("Retry-After", String.valueOf(espera));
        error(ex, 429, "DEMASIADOS_INTENTOS",
                "Demasiados intentos fallidos. Intenta de nuevo en " + ((espera + 59) / 60) + " minuto(s).");
    }

    private void login(HttpExchange ex) throws IOException {
        String ip = ipDe(ex);
        long espera = limitador.segundosDeEspera(ip);
        if (espera > 0) { demasiadosIntentos(ex, espera); return; }
        Map<String, Object> cuerpo = leerJson(ex);
        String usuario = texto(cuerpo.get("usuario"), 100);
        String contrasena = texto(cuerpo.get("contrasena"), 200);
        boolean recordar = Boolean.TRUE.equals(cuerpo.get("recordar"));
        espera = esperaLogin(ip, usuario);
        if (espera > 0) { demasiadosIntentos(ex, espera); return; }

        Cajero cajero;
        try {
            // No abre sesión en el escritorio NI toca su contador de intentos ni su bloqueo
            cajero = sesionService.verificarCredencialesMovil(usuario, contrasena, ip);
        } catch (CredencialesInvalidasException e) {
            registrarFalloLogin(ip, usuario);
            error(ex, 401, "CREDENCIALES", SesionService.MENSAJE_CREDENCIALES_MOVIL);
            return;
        }
        if (cajero.isDebeCambiarContrasena()) {
            error(ex, 403, "CAMBIO_CONTRASENA",
                    "Debes cambiar tu contraseña en el programa de escritorio antes de usar el acceso móvil.");
            return;
        }
        var emitida = sesionMovilService.emitir(cajero, recordar, ip, ex.getRequestHeaders().getFirst("User-Agent"));
        auditoriaService.registrar(cajero, "LOGIN_MOVIL",
                "ip " + ip + (recordar ? ", sesión permanente" : ", sesión de 48 h"), (Cajero) null);

        Map<String, Object> r = datosSesion(new SesionValida(emitida.sesion(), cajero));
        r.put("token", emitida.token());
        responder(ex, 200, r);
    }

    private void sesion(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        responder(ex, 200, datosSesion(v.get()));
    }

    private void recordar(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        Map<String, Object> cuerpo = leerJson(ex);
        boolean permanente = Boolean.TRUE.equals(cuerpo.get("recordar"));
        String ip = ipDe(ex);
        Cajero cajero = v.get().cajero();
        if (permanente && !v.get().sesion().isPermanente()) {
            // Subir de 48 h a "siempre abierta" exige volver a escribir la contraseña
            String contrasena = texto(cuerpo.get("contrasena"), 200);
            long espera = esperaLogin(ip, cajero.getUsuario());
            if (espera > 0) { demasiadosIntentos(ex, espera); return; }
            try {
                sesionService.verificarCredencialesMovil(cajero.getUsuario(), contrasena, ip);
            } catch (CredencialesInvalidasException e) {
                registrarFalloLogin(ip, cajero.getUsuario());
                error(ex, 401, "CREDENCIALES", "Contraseña incorrecta.");
                return;
            }
        }
        var s = sesionMovilService.fijarPermanente(v.get().sesion().getId(), permanente);
        if (permanente) {
            auditoriaService.registrar(cajero, "SESION_MOVIL_PERMANENTE",
                    "Sesión móvil id " + s.getId() + " configurada como permanente (ip " + ip + ")", (Cajero) null);
        }
        responder(ex, 200, datosSesion(new SesionValida(s, cajero)));
    }

    private void logout(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        // Cerrar es idempotente: con un token ya inválido también se responde 200
        if (v.isPresent()) {
            sesionMovilService.cerrar(tokenDe(ex));
            auditoriaService.registrar(v.get().cajero(), "LOGOUT_MOVIL", "ip " + ipDe(ex), (Cajero) null);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        responder(ex, 200, r);
    }

    private Optional<SesionValida> validarToken(HttpExchange ex) {
        return sesionMovilService.validar(tokenDe(ex));
    }

    private static String tokenDe(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return h.substring(7).trim();
    }

    private void sesionInvalida(HttpExchange ex) throws IOException {
        error(ex, 401, "SESION_INVALIDA", "Tu sesión terminó. Inicia sesión de nuevo.");
    }

    /** Lo que la página necesita: quién es, qué puede ver/hacer y cuándo vence (la decisión real es del servidor) */
    Map<String, Object> datosSesion(SesionValida v) {
        Cajero c = v.cajero();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("usuario", c.getUsuario());
        r.put("nombre", c.getNombreCompleto());
        r.put("rol", c.getRol().name());
        r.put("rolEtiqueta", c.getRol().getEtiqueta());
        r.put("permisos", c.getPermisosEfectivos().stream().map(Enum::name).sorted().toList());
        Map<String, Object> secciones = new LinkedHashMap<>();
        boolean historial = c.tienePermiso(Permiso.VER_HISTORIAL);
        boolean ajustar = c.tienePermiso(Permiso.AJUSTAR_INVENTARIO);
        secciones.put("estadisticas", historial);
        secciones.put("historial", historial);
        secciones.put("inventario", true);
        secciones.put("inventarioAjustar", ajustar);
        secciones.put("escanearTicket", ajustar);
        secciones.put("configuracion", true);
        r.put("secciones", secciones);
        r.put("permanente", v.sesion().isPermanente());
        r.put("expira", v.sesion().getExpira() == null ? null
                : v.sesion().getExpira().toInstant(ZoneOffset.UTC).toEpochMilli());
        r.put("ahora", reloj.millis());
        return r;
    }

    // ── Datos reales (estadísticas, ventas, inventario) ───────────────────

    static final int MAX_DIAS_VENTAS = 93;
    static final int MAX_VENTAS = 500;
    static final int MAX_PRODUCTOS = 200;

    /**
     * Esqueleto común: token válido → permiso (comprobado aquí con el usuario recién leído de la BD
     * y vuelto a comprobar por el servicio) → servicio ejecutado COMO el usuario móvil.
     * El ámbito se limpia siempre (finally dentro de ejecutarComo).
     */
    private void protegido(HttpExchange ex, Permiso requerido, java.util.function.Function<Cajero, Object> accion)
            throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        Cajero cajero = v.get().cajero();
        if (requerido != null && !cajero.tienePermiso(requerido)) { sinPermiso(ex); return; }
        Object resultado;
        try {
            Supplier<Object> trabajo = () -> accion.apply(cajero);
            resultado = sesionService.ejecutarComo(cajero, ipDe(ex), trabajo);
        } catch (PermisoRequeridoException e) {
            sinPermiso(ex);
            return;
        }
        responder(ex, 200, resultado);
    }

    private void sinPermiso(HttpExchange ex) throws IOException {
        error(ex, 403, "SIN_PERMISO", "Sin permiso para esta sección.");
    }

    private static Map<String, String> parametros(HttpExchange ex) {
        Map<String, String> r = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) return r;
        if (q.length() > 400) throw new CuerpoInvalido(400, "Consulta demasiado larga.");
        for (String par : q.split("&")) {
            if (par.isEmpty()) continue;
            int i = par.indexOf('=');
            String k = i < 0 ? par : par.substring(0, i);
            String val = i < 0 ? "" : par.substring(i + 1);
            try {
                r.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(val, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new CuerpoInvalido(400, "Consulta inválida.");
            }
        }
        return r;
    }

    private static LocalDate fecha(String valor, LocalDate porDefecto) {
        if (valor == null || valor.isBlank()) return porDefecto;
        try {
            return LocalDate.parse(valor.trim());
        } catch (DateTimeParseException e) {
            throw new CuerpoInvalido(400, "Fecha inválida. Usa el formato AAAA-MM-DD.");
        }
    }

    private static Map<String, Object> mapaTotales(Totales t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", t.total());
        m.put("transacciones", t.transacciones());
        m.put("piezas", t.piezas());
        m.put("ticketPromedio", Math.round(t.ticketPromedio() * 100.0) / 100.0);
        return m;
    }

    private void estadisticas(HttpExchange ex) throws IOException {
        String per = parametros(ex).getOrDefault("periodo", "dia").toLowerCase(Locale.ROOT);
        if (!per.equals("dia") && !per.equals("semana") && !per.equals("mes")) {
            throw new CuerpoInvalido(400, "Periodo inválido. Usa dia, semana o mes.");
        }
        protegido(ex, Permiso.VER_HISTORIAL, cajero -> {
            LocalDate hoy = estadisticasService.hoy();
            Periodo p = switch (per) {
                case "semana" -> Periodo.semana(hoy);
                case "mes"    -> Periodo.mes(hoy);
                default       -> Periodo.dia(hoy);
            };
            String etiqueta = switch (per) { case "semana" -> "Semana"; case "mes" -> "Mes"; default -> "Hoy"; };
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("periodo", per);
            r.put("desde", p.desde().toString());
            r.put("hasta", p.hasta().toString());
            r.put("totales", mapaTotales(estadisticasService.totales(p)));

            estadisticasService.comparaciones().stream().filter(c -> c.etiqueta().equals(etiqueta)).findFirst()
                    .ifPresent(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("detalle", c.detalle());
                        m.put("anterior", mapaTotales(c.anterior()));
                        m.put("variacionPct", c.variacionPct());      // null = sin periodo anterior con ventas
                        r.put("comparacion", m);
                    });

            List<Map<String, Object>> top = new ArrayList<>();
            for (var t : estadisticasService.topProductos(p, 5)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.producto().getId());
                m.put("descripcion", t.producto().getDescripcion());
                m.put("unidad", unidadDe(t.producto()));
                m.put("piezas", t.piezas());
                m.put("importe", t.importe());
                top.add(m);
            }
            r.put("top", top);

            List<Map<String, Object>> franjas = new ArrayList<>();
            estadisticasService.porFranja(p).forEach((f, t) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("franja", f.name());
                m.put("etiqueta", f.getEtiqueta());
                m.put("total", t.total());
                m.put("transacciones", t.transacciones());
                franjas.add(m);
            });
            r.put("franjas", franjas);

            List<Map<String, Object>> serie = new ArrayList<>();
            estadisticasService.serieDiaria(p).forEach((d, total) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fecha", d.toString());
                m.put("total", total);
                serie.add(m);
            });
            r.put("serie", serie);

            boolean ganancias = cajero.tienePermiso(Permiso.VER_GANANCIAS);
            r.put("gananciaVisible", ganancias);
            if (ganancias) {
                double g = 0;
                for (Venta v : ventaService.ventasEntre(p.desde(), p.hasta())) {
                    if (v.isCancelada()) continue;
                    for (DetalleVenta d : v.getDetalles()) g += d.getImporte() - d.getCostoUnitarioODefault() * d.getCantidad();
                }
                r.put("ganancia", Math.round(g * 100.0) / 100.0);
            }
            return r;
        });
    }

    private void ventas(HttpExchange ex) throws IOException {
        Map<String, String> q = parametros(ex);
        LocalDate hoy = estadisticasService.hoy();
        LocalDate desde = fecha(q.get("desde"), hoy);
        LocalDate hasta = fecha(q.get("hasta"), q.get("desde") == null ? hoy : desde);
        if (hasta.isBefore(desde)) throw new CuerpoInvalido(400, "La fecha final no puede ser anterior a la inicial.");
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > MAX_DIAS_VENTAS) {
            throw new CuerpoInvalido(400, "El rango máximo es de " + MAX_DIAS_VENTAS + " días.");
        }
        protegido(ex, Permiso.VER_HISTORIAL, cajero -> {
            boolean ganancias = cajero.tienePermiso(Permiso.VER_GANANCIAS);
            List<Venta> todas = ventaService.ventasEntre(desde, hasta);
            List<Map<String, Object>> lista = new ArrayList<>();
            for (Venta v : todas) {
                if (lista.size() >= MAX_VENTAS) break;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", v.getId());
                m.put("folio", v.getFolio());
                m.put("fecha", v.getFecha().toString());
                m.put("cajero", v.getCajero() == null ? "" : v.getCajero().getNombreCompleto());
                m.put("total", v.getTotal());
                m.put("formaPago", v.getFormaPago() == null ? "" : v.getFormaPago().getEtiqueta());
                m.put("cancelada", v.isCancelada());
                List<Map<String, Object>> lineas = new ArrayList<>();
                for (DetalleVenta d : v.getDetalles()) {
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("productoId", d.getProducto().getId());
                    l.put("descripcion", d.getProducto().getDescripcion());
                    l.put("unidad", unidadDe(d.getProducto()));
                    l.put("cantidad", d.getCantidad());
                    l.put("precio", d.getPrecioVenta());
                    l.put("importe", d.getImporte());
                    if (ganancias) l.put("costo", d.getCostoUnitarioODefault());
                    lineas.add(l);
                }
                m.put("lineas", lineas);
                lista.add(m);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("desde", desde.toString());
            r.put("hasta", hasta.toString());
            r.put("ventas", lista);
            r.put("total", todas.size());
            r.put("truncado", todas.size() > lista.size());
            return r;
        });
    }

    private static String unidadDe(Producto p) {
        return p.getUnidad() != null ? p.getUnidad().getAbreviatura()
                : (p.getTipoVenta() == Producto.TipoVenta.GRANEL ? "kg" : "pza");
    }

    private static Map<String, Object> mapaProducto(Producto p, boolean ganancias) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("codigo", p.getCodigoBarras());
        m.put("descripcion", p.getDescripcion());
        m.put("existencia", p.getCantidadActual());
        m.put("minimo", p.getCantidadMinima());
        m.put("stockBajo", ProductoService.tieneStockBajo(p));
        m.put("unidad", unidadDe(p));
        m.put("departamento", p.getDepartamento() == null ? "" : p.getDepartamento().getNombre());
        m.put("precio", p.getPrecioVenta());
        if (ganancias) m.put("costo", p.getPrecioCosto());
        return m;
    }

    private void inventario(HttpExchange ex) throws IOException {
        String q = parametros(ex).getOrDefault("q", "").trim();
        if (q.length() > 60) throw new CuerpoInvalido(400, "Búsqueda demasiado larga.");
        protegido(ex, null, cajero -> {
            boolean ganancias = cajero.tienePermiso(Permiso.VER_GANANCIAS);
            List<Producto> encontrados = productoService.buscar(q).stream()
                    .filter(p -> p.isActivo() && p.isUsaInventario())
                    .sorted(java.util.Comparator.comparing((Producto p) -> !ProductoService.tieneStockBajo(p))
                            .thenComparing(p -> p.getDescripcion() == null ? "" : p.getDescripcion().toLowerCase(Locale.ROOT)))
                    .toList();
            List<Map<String, Object>> lista = new ArrayList<>();
            for (Producto p : encontrados) {
                if (lista.size() >= MAX_PRODUCTOS) break;
                lista.add(mapaProducto(p, ganancias));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("productos", lista);
            r.put("total", encontrados.size());
            r.put("truncado", encontrados.size() > lista.size());
            r.put("puedeAjustar", cajero.tienePermiso(Permiso.AJUSTAR_INVENTARIO));
            return r;
        });
    }

    private static double numero(Object o, String campo) {
        if (!(o instanceof Number n)) throw new CuerpoInvalido(400, "El campo " + campo + " debe ser un número.");
        double d = n.doubleValue();
        if (!Double.isFinite(d)) throw new CuerpoInvalido(400, "El campo " + campo + " es inválido.");
        return d;
    }

    private void ajusteInventario(HttpExchange ex) throws IOException {
        // Se valida el token ANTES de leer el cuerpo (no se procesan datos de quien no tiene sesión)
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        if (!v.get().cajero().tienePermiso(Permiso.AJUSTAR_INVENTARIO)) { sinPermiso(ex); return; }
        Map<String, Object> cuerpo = leerJson(ex);
        if (!(cuerpo.get("productoId") instanceof Number idNum) || idNum.doubleValue() != Math.rint(idNum.doubleValue())) {
            throw new CuerpoInvalido(400, "Producto inválido.");
        }
        long productoId = idNum.longValue();
        String tipo = texto(cuerpo.get("tipo"), 20).toLowerCase(Locale.ROOT);
        double cantidad = numero(cuerpo.get("cantidad"), "cantidad");
        String motivo = texto(cuerpo.get("motivo"), 200).trim();
        String proveedor = texto(cuerpo.get("proveedor"), 150).trim();
        Double costo = cuerpo.get("costo") == null ? null : numero(cuerpo.get("costo"), "costo");
        if (!tipo.equals("entrada") && !tipo.equals("ajuste") && !tipo.equals("merma")) {
            throw new CuerpoInvalido(400, "Tipo inválido. Usa entrada, ajuste o merma.");
        }
        if (costo != null && costo > 0 && !v.get().cajero().tienePermiso(Permiso.VER_GANANCIAS)) {
            sinPermiso(ex);     // cambiar el costo exige poder ver costos
            return;
        }
        protegido(ex, Permiso.AJUSTAR_INVENTARIO, cajero -> {
            Producto antes = productoService.buscarPorId(productoId)
                    .orElseThrow(() -> new NegocioException("El producto ya no existe."));
            double existenciaAntes = antes.getCantidadActual();
            Producto despues;
            String detalle;
            switch (tipo) {
                case "entrada" -> {
                    despues = productoService.registrarEntrada(productoId, cantidad,
                            proveedor.isEmpty() ? "No indicado (móvil)" : proveedor,
                            costo == null ? 0 : costo, null);
                    detalle = "Entrada +" + cantidad;
                }
                case "merma" -> {
                    if (!(cantidad > 0)) throw new NegocioException("La cantidad de la merma debe ser mayor a cero.");
                    if (cantidad > existenciaAntes) throw new NegocioException("La merma no puede ser mayor a la existencia actual.");
                    despues = productoService.ajustarStock(productoId, existenciaAntes - cantidad,
                            "Merma: " + (motivo.isEmpty() ? "sin motivo" : motivo), null);
                    detalle = "Merma -" + cantidad;
                }
                default -> {
                    despues = productoService.ajustarStock(productoId, cantidad, motivo, null);
                    detalle = "Ajuste a " + cantidad;
                }
            }
            auditoriaService.registrar(cajero, "INVENTARIO_MOVIL",
                    detalle + " · " + despues.getDescripcion() + " (" + existenciaAntes + " → "
                            + despues.getCantidadActual() + ")" + (motivo.isEmpty() ? "" : " · " + motivo), (Cajero) null);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("producto", mapaProducto(despues, cajero.tienePermiso(Permiso.VER_GANANCIAS)));
            return r;
        });
    }

    // ── Utilidades HTTP ───────────────────────────────────────────────────

    private Map<String, Object> leerJson(HttpExchange ex) throws IOException {
        String tipo = ex.getRequestHeaders().getFirst("Content-Type");
        if (tipo == null || !tipo.toLowerCase().startsWith("application/json")) {
            throw new CuerpoInvalido(415, "El contenido debe ser application/json.");
        }
        String largo = ex.getRequestHeaders().getFirst("Content-Length");
        if (largo != null) {
            try {
                if (Long.parseLong(largo.trim()) > MAX_CUERPO_BYTES) throw new CuerpoInvalido(413, "Solicitud demasiado grande.");
            } catch (NumberFormatException e) {
                throw new CuerpoInvalido(400, "Cabecera Content-Length inválida.");
            }
        }
        // Lectura acotada: también protege cuando el cuerpo llega "chunked" sin Content-Length
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        try (InputStream in = ex.getRequestBody()) {
            int n;
            while ((n = in.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
                if (buf.size() > MAX_CUERPO_BYTES) throw new CuerpoInvalido(413, "Solicitud demasiado grande.");
            }
        }
        try {
            if (buf.size() == 0) return new LinkedHashMap<>();
            return json.readValue(buf.toByteArray(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new CuerpoInvalido(400, "JSON inválido.");
        }
    }

    private static String texto(Object o, int max) {
        if (o == null) return "";
        if (!(o instanceof String s)) throw new CuerpoInvalido(400, "Datos inválidos.");
        if (s.length() > max) throw new CuerpoInvalido(400, "Datos demasiado largos.");
        return s;
    }

    private static String ipDe(HttpExchange ex) {
        InetAddress a = ex.getRemoteAddress().getAddress();
        return a == null ? "?" : a.getHostAddress();
    }

    private void error(HttpExchange ex, int estado, String codigo, String mensaje) throws IOException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", mensaje);
        r.put("codigo", codigo);
        responder(ex, estado, r);
    }

    private void responder(HttpExchange ex, int estado, Object cuerpo) throws IOException {
        byte[] bytes = json.writeValueAsBytes(cuerpo);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        cabecerasComunes(ex);
        ex.sendResponseHeaders(estado, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void cabecerasComunes(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", "no-store");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
    }
}
