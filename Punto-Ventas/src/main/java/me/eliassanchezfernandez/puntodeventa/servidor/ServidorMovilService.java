package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush;
import me.eliassanchezfernandez.puntodeventa.service.NotificacionesPushService;
import jakarta.annotation.PreDestroy;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.util.FuncionesOcultas;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoImagen;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Totales;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoImagenService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.TutorialesService;
import me.eliassanchezfernandez.puntodeventa.service.AuditoriaService;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService;
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
import java.time.DayOfWeek;
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
import java.time.LocalDateTime;
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
 *   POST /api/sesion/traspaso {destino: seguro|inicio} (Bearer) -> código de UN solo uso (3 / 15 min) para pasar la sesión a
 *                                                    la página segura (url https con #traspaso=...) o al icono de inicio del iPhone
 *                                                    (manifiesto con start_url propio). Ver TraspasosSesion.
 *   POST /api/sesion/canjear  {codigo}  (sin token)            -> canjea el código: sesión NUEVA con la misma vigencia; la
 *                                                    original se cierra (la sesión «se muda»). Fallos cuentan como intento de login.
 *
 *   GET  /api/estadisticas?periodo=dia|semana|mes   (VER_HISTORIAL)
 *   GET  /api/ventas?desde=YYYY-MM-DD&hasta=YYYY-MM-DD (VER_HISTORIAL, máx. 93 días y 500 ventas)
 *   GET  /api/inventario?q=                          (cualquier usuario; costos solo con VER_GANANCIAS)
 *   GET  /api/productos/{id}/imagen[?mini=1]         (cualquier usuario) -> JPEG con ETag; 404 si no tiene
 *   POST /api/inventario/ajuste {productoId, tipo: entrada|ajuste|merma, cantidad, motivo, proveedor?, costo?}
 *                                                    (AJUSTAR_INVENTARIO)
 *   GET  /api/clientes/saldos                         (VENDER_A_CREDITO) -> "Quién me debe": clientes con saldo, total por cobrar
 *   GET  /api/proveedores                             (cualquier usuario) -> agenda: días de entrega/preventa, próxima visita
 *                                                    y nota media (esta solo con VER_GANANCIAS o GESTIONAR_PROVEEDORES)
 *   POST /api/proveedores/guardar {id?, nombre, telefono?, contacto?, diasEntrega:[1..7], diasPreventa:[1..7], notas?}
 *                                                    (GESTIONAR_PROVEEDORES) -> crea o edita; el RFC solo se captura en el escritorio
 *   GET  /api/avisos                                  (cualquier usuario) -> avisos del día: proveedores que entregan o pasan a
 *                                                    preventa hoy/mañana, productos que se acaban, hora pico (importes solo con
 *                                                    VER_GANANCIAS o GESTIONAR_PROVEEDORES); el servicio los guarda 5 min en memoria
 *   GET  /api/caducidades                             (cualquier usuario) -> "Por caducar": entradas con fecha de caducidad que
 *                                                    vencen en los próximos N días (o ya vencieron) con la cantidad estimada
 *   GET  /api/tutorial                                (cualquier usuario) -> {visto}: recorrido móvil ya visto por ESTE usuario
 *   POST /api/tutorial/visto {visto: true|false}       (cualquier usuario) -> lo marca/reinicia (columna cajeros.tutorial_movil_visto)
 *
 *   Notificaciones (Web Push; la página solo las ofrece abierta por HTTPS):
 *   GET  /api/push/clave                              (cualquier usuario) -> {clave}: clave pública VAPID de esta instalación
 *   POST /api/push/suscribir {endpoint, keys:{p256dh, auth}}  -> guarda la suscripción de ESTE teléfono para el usuario
 *   POST /api/push/desuscribir {endpoint}             -> la quita (idempotente)
 *   POST /api/push/estado {endpoint}                  -> preferencias de la suscripción, 404 si no existe
 *   POST /api/push/preferencias {endpoint, activa, proveedores, stock, horaPico, caducidad?, silencioDesde, silencioHasta}
 *   POST /api/push/prueba {endpoint}                  -> manda una notificación de prueba ahora
 *
 * HTTPS: junto al servidor HTTP se abre otro en el puerto HTTP + 1 (o el fijado en Opciones avanzadas)
 * con un certificado firmado por la CA LOCAL de esta instalación (CertificadosLocales). Mismos
 * manejadores. Los teléfonos instalan la CA UNA vez desde la página pública /certificado (HTTP):
 *   GET /certificado                      -> guía con detección de Android / iPhone y la huella de la CA
 *   GET /certificado/caja-libre.crt       -> DER (application/x-x509-ca-cert), para Android
 *   GET /certificado/caja-libre.mobileconfig -> perfil de Apple sin firmar, para iPhone/iPad
 * Sin HSTS (red local; el HTTP sigue funcionando sin notificaciones).
 *
 * Archivos estáticos (lista cerrada, sin acceso al disco): /favicon.ico, /icono-32.png, /icono-192.png, /icono-256.png,
 * /icono-512.png (icono oficial de resources/icono; 192 y 512 se escalan en memoria), /manifest.webmanifest (generado)
 * para "Agregar a pantalla de inicio" y /sw.js (service worker: solo se registra desde HTTPS).
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

    /** La sección "Escanear ticket" del móvil (OCR) aún es una demostración: se oculta en producción */
    static final boolean ESCANEAR_TICKET_HABILITADO = false;

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
                + "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'; "
                + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    }

    @Autowired private SesionService        sesionService;
    @Autowired private SesionMovilService   sesionMovilService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AuditoriaService     auditoriaService;
    @Autowired private Clock                reloj;
    @Autowired private EstadisticasService  estadisticasService;
    @Autowired private ProductoService      productoService;
    @Autowired private ProductoImagenService productoImagenService;
    @Autowired private ProveedorService     proveedorService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.CuentaClienteService cuentaClienteService;
    @Autowired private AnunciadorMdns       anunciador;
    @Autowired private TutorialesService    tutorialesService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.AparienciaService aparienciaService;
    @Autowired private AvisosService        avisosService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.CaducidadService caducidadService;
    @Autowired private CertificadosLocales  certificados;
    @Autowired private NotificacionesPushService pushService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.ConsultasMovilService consultasMovil;

    /** Altas de suscripción push por IP (una por teléfono; el límite frena a quien quiera llenar la tabla) */
    static final int MAX_SUSCRIPCIONES_POR_IP = 20;
    static final Duration VENTANA_SUSCRIPCIONES = Duration.ofMinutes(10);
    /** Puertos HTTPS que se prueban después del preferido (HTTP + 1): hasta +10 */
    static final int INTENTOS_PUERTO_HTTPS = 10;

    /** Puertos de respaldo (después del preferido) cuando el elegido está ocupado; 0 = lo asigna el sistema */
    static final List<Integer> PUERTOS_RESPALDO = candidatosRespaldo();

    private static List<Integer> candidatosRespaldo() {
        List<Integer> l = new ArrayList<>();
        for (int p = 8085; p <= 8095; p++) l.add(p);
        l.add(18085);
        l.add(0);
        return List.copyOf(l);
    }

    /** Nombre local configurado (sin ".local"); su Host se acepta aunque mDNS no esté disponible */
    private volatile String nombreLocal;

    /** Pruebas: poner en false para que no arranque solo aunque la BD diga "activo" */
    @Value("${app.movil.autoarranque:true}")
    private boolean autoarranque;

    /** Jackson se carga al primer uso (unas 400 clases): no hace falta para arrancar */
    private volatile ObjectMapper json;

    private ObjectMapper json() {
        ObjectMapper j = json;
        if (j == null) {
            synchronized (this) {
                j = json;
                if (j == null) json = j = new ObjectMapper();
            }
        }
        return j;
    }
    private volatile HttpServer servidor;
    private volatile HttpsServer servidorHttps;
    private volatile ExecutorService hilos;
    private volatile LimitadorIntentos limitador;
    private volatile LimitadorIntentos limitadorUsuarios;
    private volatile LimitadorIntentos limitadorSuscripciones;
    /** Pruebas: puerto HTTPS a usar (0 = libre; null = según configuración) */
    volatile Integer puertoHttpsForzado;
    private volatile Pagina paginaCache;
    /** Pruebas: se puede cambiar por uno con reloj propio */
    volatile TraspasosSesion traspasos;

    private TraspasosSesion traspasos() {
        TraspasosSesion t = traspasos;
        if (t == null) {
            synchronized (this) {
                t = traspasos;
                if (t == null) traspasos = t = new TraspasosSesion(reloj);
            }
        }
        return t;
    }
    private volatile String nombreEquipo;

    /** La página lista para servir: normal, comprimida, su ETag y la CSP con el hash de su script */
    record Pagina(byte[] normal, byte[] gzip, String etag, String csp) { }

    // ── Ciclo de vida ─────────────────────────────────────────────────────

    @Autowired private me.eliassanchezfernandez.puntodeventa.config.ArranqueDiferido arranqueDiferido;

    /**
     * Con el contexto listo: encola el arranque en el hilo diferido (corre en cuanto la primera
     * ventana está dibujada, el primero de la cola). Antes abría el puerto, enumeraba las interfaces
     * de red y escribía en la base ANTES de que apareciera el login.
     */
    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void arrancarSiEstaActivo() {
        if (!autoarranque) return;
        arranqueDiferido.ejecutar("acceso móvil", this::arrancarAhoraSiEstaActivo);
    }

    /** Arranca si el administrador lo dejó activado. Nunca tumba la app. */
    public void arrancarAhoraSiEstaActivo() {
        try {
            Configuracion c = configuracionService.obtener();
            if (c.isMovilActivo()) {
                if (c.isMovilPuertoManual()) iniciar(c.getMovilPuerto());
                else iniciarAutomatico(c.getMovilPuerto());
                configuracionService.registrarPuertoMovilEnlazado(getPuerto());
            }
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
        arrancar(abrir(puerto));
    }

    /**
     * Arranque automático: prueba el puerto preferido (el último que funcionó o el 8085), luego
     * 8085-8095 y 18085, y por último uno que asigne el sistema. Devuelve el puerto realmente usado.
     */
    public synchronized int iniciarAutomatico(int preferido) {
        List<Integer> candidatos = new ArrayList<>();
        candidatos.add(preferido);
        candidatos.addAll(PUERTOS_RESPALDO);
        return iniciarConCandidatos(candidatos);
    }

    /** Prueba los puertos en orden y se queda con el primero libre */
    synchronized int iniciarConCandidatos(List<Integer> candidatos) {
        if (servidor != null) return getPuerto();
        for (int puerto : new java.util.LinkedHashSet<>(candidatos)) {
            try {
                arrancar(abrir(puerto));
                return getPuerto();
            } catch (NegocioException ocupado) {
                // siguiente candidato
            }
        }
        throw new NegocioException("No se pudo abrir ningún puerto para el acceso móvil. Revisa el antivirus o el firewall.");
    }

    private static HttpServer abrir(int puerto) {
        try {
            return HttpServer.create(new InetSocketAddress(puerto), 0);
        } catch (BindException e) {
            throw new NegocioException("El puerto " + puerto + " ya está en uso por otro programa. Elige otro puerto.");
        } catch (IOException | RuntimeException e) {
            throw new NegocioException("No se pudo abrir el puerto " + puerto + ": " + e.getMessage());
        }
    }

    private void arrancar(HttpServer nuevo) {
        ExecutorService pool = crearPool();
        limitador = new LimitadorIntentos(reloj, MAX_FALLOS_POR_IP, VENTANA_FALLOS);
        limitadorUsuarios = new LimitadorIntentos(reloj, MAX_FALLOS_POR_USUARIO, VENTANA_FALLOS_USUARIO);
        limitadorSuscripciones = new LimitadorIntentos(reloj, MAX_SUSCRIPCIONES_POR_IP, VENTANA_SUSCRIPCIONES);
        nuevo.createContext("/", this::atender);
        nuevo.setExecutor(pool);
        nuevo.start();
        servidor = nuevo;
        hilos = pool;
        System.out.println("📱 Acceso móvil activo en el puerto " + nuevo.getAddress().getPort());
        aplicarNombreLocal();
        iniciarHttps();
    }

    // ── HTTPS (certificado de la CA local) ────────────────────────────────

    /**
     * Abre el servidor HTTPS con el certificado de la CA local. Nunca tumba el HTTP: si falla
     * (puerto ocupado, sin red) queda apagado y la página sigue funcionando sin notificaciones.
     */
    synchronized void iniciarHttps() {
        if (servidor == null || servidorHttps != null) return;
        try {
            CertificadosLocales.Servidor cert = certificados.servidor(nombresDnsCertificado(), ipsCertificado());
            javax.net.ssl.SSLContext tls = CertificadosLocales.contextoTls(cert);
            for (int puerto : candidatosHttps()) {
                try {
                    HttpsServer h = HttpsServer.create(new InetSocketAddress(puerto), 0);
                    h.setHttpsConfigurator(new HttpsConfigurator(tls) {
                        @Override public void configure(HttpsParameters p) {
                            javax.net.ssl.SSLParameters sp = getSSLContext().getDefaultSSLParameters();
                            sp.setProtocols(new String[] { "TLSv1.3", "TLSv1.2" });
                            p.setSSLParameters(sp);
                        }
                    });
                    h.createContext("/", this::atender);
                    if (hilosHttps == null) hilosHttps = crearPoolHttps();
                    h.setExecutor(hilosHttps);
                    h.start();
                    servidorHttps = h;
                    System.out.println("🔒 Acceso móvil seguro (HTTPS) en el puerto " + h.getAddress().getPort());
                    return;
                } catch (IOException ocupado) {
                    // siguiente candidato
                }
            }
            System.err.println("⚠ No se encontró un puerto libre para el acceso móvil seguro (HTTPS): sin notificaciones.");
        } catch (RuntimeException e) {
            System.err.println("⚠ Acceso móvil seguro (HTTPS) no disponible: " + e.getMessage());
        }
    }

    private synchronized void detenerHttps() {
        HttpsServer h = servidorHttps;
        if (h == null) return;
        servidorHttps = null;
        h.stop(0);
        ThreadPoolExecutor p = hilosHttps;
        hilosHttps = null;
        if (p != null) p.shutdownNow();
    }

    /** Puertos HTTPS a probar: el fijado por el administrador, o el HTTP + 1 y los siguientes */
    private List<Integer> candidatosHttps() {
        List<Integer> l = new ArrayList<>();
        if (puertoHttpsForzado != null) { l.add(puertoHttpsForzado); return l; }
        Integer fijo = null;
        try {
            fijo = configuracionService.obtener().getMovilPuertoHttps();
        } catch (RuntimeException ignorada) { /* sin configuración: automático */ }
        if (fijo != null) { l.add(fijo); return l; }
        int base = getPuerto();
        for (int i = 1; i <= INTENTOS_PUERTO_HTTPS; i++) if (base + i <= 65535) l.add(base + i);
        return l;
    }

    /** Nombres que debe cubrir el certificado: localhost, el equipo y el nombre .local configurado */
    List<String> nombresDnsCertificado() {
        List<String> r = new ArrayList<>();
        r.add("localhost");
        String equipo = nombreDeEquipo();
        if (!equipo.isEmpty() && equipo.matches("[a-z0-9.-]+")) {
            r.add(equipo);
            if (!equipo.endsWith(".local")) r.add(equipo + ".local");
        }
        String local = nombreLocal;
        if (local != null) r.add(local + ".local");
        return r;
    }

    List<String> ipsCertificado() {
        List<String> r = new ArrayList<>();
        r.add("127.0.0.1");
        for (Inet4Address a : ipsLan()) r.add(a.getHostAddress());
        return r;
    }

    /**
     * Si cambiaron las direcciones o el certificado está por vencer, lo vuelve a emitir (misma CA)
     * y reabre el HTTPS. Cada 10 minutos y al cambiar el nombre local; también sirve tras
     * "Regenerar certificado".
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 600_000, initialDelay = 300_000)
    public synchronized void revisarCertificado() {
        if (servidor == null) return;
        if (servidorHttps != null && certificados.servidorVigentePara(nombresDnsCertificado(), ipsCertificado())) return;
        detenerHttps();
        iniciarHttps();
    }

    /** Reabre el HTTPS (puerto o certificado cambiados) */
    public synchronized void reiniciarHttps() {
        detenerHttps();
        iniciarHttps();
    }

    public boolean httpsActivo() {
        return servidorHttps != null;
    }

    /** Puerto HTTPS en uso (0 si no está abierto) */
    public int getPuertoHttps() {
        HttpsServer h = servidorHttps;
        return h == null ? 0 : h.getAddress().getPort();
    }

    /** Direcciones seguras para abrir desde el teléfono (vacío si el HTTPS no está abierto) */
    public List<String> urlsHttps() {
        List<String> urls = new ArrayList<>();
        int p = getPuertoHttps();
        if (p == 0) return urls;
        for (Inet4Address a : ipsLan()) urls.add("https://" + a.getHostAddress() + ":" + p + "/");
        return urls;
    }

    /** Dirección (HTTP) que abre el asistente «Recibir avisos en este teléfono» (pide iniciar sesión si hace falta) */
    public List<String> urlsAvisos(int puertoHttp) {
        List<String> urls = new ArrayList<>();
        for (Inet4Address a : ipsLan()) urls.add("http://" + a.getHostAddress() + ":" + puertoHttp + "/#/avisos");
        return urls;
    }

    /** Página pública con la guía para instalar el certificado, en cada dirección HTTP */
    public List<String> urlsCertificado(int puertoHttp) {
        List<String> urls = new ArrayList<>();
        for (Inet4Address a : ipsLan()) urls.add("http://" + a.getHostAddress() + ":" + puertoHttp + "/certificado");
        return urls;
    }

    /**
     * Hilos del HTTPS: el HttpsServer del JDK lee el saludo TLS y la petición con lecturas BLOQUEANTES
     * en el hilo de trabajo, y los navegadores abren conexiones «por si acaso» (Chrome pre-conecta)
     * que no mandan nada hasta maxReqTime (10 s). Con 2 hilos fijos, dos de esas conexiones dejaban
     * esperando 10 s a la petición real (el asistente de avisos se quedaba en «Revisando…»). Aquí los
     * HILOS_HTTPS se crean desde el principio (no solo con la cola llena) y mueren tras 30 s sin uso;
     * las consultas pesadas siguen limitadas por su semáforo.
     */
    static final int HILOS_HTTPS = 8;
    private volatile ThreadPoolExecutor hilosHttps;

    static ThreadPoolExecutor crearPoolHttps() {
        AtomicInteger n = new AtomicInteger();
        ThreadPoolExecutor p = new ThreadPoolExecutor(HILOS_HTTPS, HILOS_HTTPS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(COLA_MAXIMA), r -> {
                    Thread t = new Thread(r, "acceso-movil-seguro-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        p.allowCoreThreadTimeOut(true);
        return p;
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
        anunciador.detener();
        detenerHttps();
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

    /** IPv4 privadas no loopback de las interfaces activas */
    public static List<Inet4Address> ipsLan() {
        List<Inet4Address> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return ips;
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address v4 && !a.isLoopbackAddress() && a.isSiteLocalAddress()) ips.add(v4);
                }
            }
        } catch (IOException ignorada) { /* sin red: lista vacía */ }
        return ips;
    }

    /** Direcciones para abrir desde el teléfono */
    public List<String> urlsLan(int puerto) {
        List<String> urls = new ArrayList<>();
        for (Inet4Address a : ipsLan()) urls.add("http://" + a.getHostAddress() + ":" + puerto + "/");
        return urls;
    }

    // ── Nombre local (mDNS) ───────────────────────────────────────────────

    /** Fija el nombre local aceptado en la cabecera Host (sin publicarlo) */
    void fijarNombreLocal(String nombre) {
        this.nombreLocal = nombre == null || nombre.isBlank() ? null : nombre.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Relee el nombre local de la configuración y (re)publica mDNS en segundo plano si el servidor
     * está encendido. Nunca lanza: si falla, el acceso por IP sigue igual.
     */
    public void aplicarNombreLocal() {
        try {
            fijarNombreLocal(configuracionService.obtener().getMovilNombre());
            if (servidor == null || nombreLocal == null) {
                anunciador.detener();
                return;
            }
            anunciador.iniciar(nombreLocal, getPuerto(), new ArrayList<>(ipsLan()));
            if (servidorHttps != null && !certificados.servidorVigentePara(nombresDnsCertificado(), ipsCertificado())) reiniciarHttps();
        } catch (RuntimeException e) {
            System.err.println("⚠ Nombre local no disponible: " + e.getMessage());
        }
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
        String local = nombreLocal;
        if (local != null && h.equals(local + ".local")) return true;
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
            Long productoImagenId = idDeRutaImagen(ruta);
            if (productoImagenId != null) {
                exigir(metodo, "GET");
                imagenProducto(ex, productoImagenId);
                return;
            }
            switch (ruta) {
                case "/", "/index.html" -> pagina(ex, metodo);
                case "/api/login"           -> { exigir(metodo, "POST"); login(ex); }
                case "/api/sesion"          -> { exigir(metodo, "GET");  sesion(ex); }
                case "/api/sesion/recordar" -> { exigir(metodo, "POST"); recordar(ex); }
                case "/api/logout"          -> { exigir(metodo, "POST"); logout(ex); }
                case "/api/sesion/traspaso" -> { exigir(metodo, "POST"); crearTraspaso(ex); }
                case "/api/sesion/canjear"  -> { exigir(metodo, "POST"); canjearTraspaso(ex); }
                case "/api/estadisticas"    -> { exigir(metodo, "GET");  estadisticas(ex); }
                case "/api/ventas"          -> { exigir(metodo, "GET");  ventas(ex); }
                case "/api/inventario"      -> { exigir(metodo, "GET");  inventario(ex); }
                case "/api/inventario/ajuste" -> { exigir(metodo, "POST"); ajusteInventario(ex); }
                case "/api/proveedores"     -> { exigir(metodo, "GET");  proveedores(ex); }
                case "/api/clientes/saldos" -> {
                    // Fiado oculto (FuncionesOcultas): la ruta no existe, igual que cualquier otra desconocida
                    if (!FuncionesOcultas.FIADO_HABILITADO) { error(ex, 404, "NO_ENCONTRADO", "No encontrado."); return; }
                    exigir(metodo, "GET");  saldosClientes(ex);
                }
                case "/api/proveedores/guardar" -> { exigir(metodo, "POST"); guardarProveedor(ex); }
                case "/api/avisos"          -> { exigir(metodo, "GET");  avisos(ex); }
                case "/api/caducidades"     -> { exigir(metodo, "GET");  caducidades(ex); }
                case "/api/tutorial"        -> { exigir(metodo, "GET");  tutorial(ex); }
                case "/api/apariencia"      -> { exigir(metodo, "GET");  apariencia(ex); }
                case "/api/apariencia/publica" -> { exigir(metodo, "GET"); aparienciaPublica(ex); }
                case "/api/tutorial/visto"  -> { exigir(metodo, "POST"); tutorialVisto(ex); }
                case "/api/push/clave"      -> { exigir(metodo, "GET");  pushClave(ex); }
                case "/api/push/suscribir"  -> { exigir(metodo, "POST"); pushSuscribir(ex); }
                case "/api/push/desuscribir" -> { exigir(metodo, "POST"); pushDesuscribir(ex); }
                case "/api/push/estado"     -> { exigir(metodo, "POST"); pushEstado(ex); }
                case "/api/push/preferencias" -> { exigir(metodo, "POST"); pushPreferencias(ex); }
                case "/api/push/prueba"     -> { exigir(metodo, "POST"); pushPrueba(ex); }
                case "/certificado"         -> paginaCertificado(ex, metodo);
                case "/certificado/caja-libre.crt", "/certificado/caja-libre.mobileconfig" -> descargaCertificado(ex, metodo, ruta);
                case "/favicon.ico", "/icono-32.png", "/icono-192.png", "/icono-256.png", "/icono-512.png", "/manifest.webmanifest", "/sw.js"
                        -> estatico(ex, metodo, ruta);
                case "/fuentes/Inter-Regular.ttf", "/fuentes/Inter-Bold.ttf",
                     "/fuentes/AtkinsonHyperlegible-Regular.ttf", "/fuentes/AtkinsonHyperlegible-Bold.ttf",
                     "/fuentes/Nunito-Regular.ttf", "/fuentes/Nunito-Bold.ttf"
                        -> estatico(ex, metodo, ruta);
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
        h.set("Content-Security-Policy", cspPagina(ex, p.csp()));
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

    /**
     * Abierta por http, la página puede «tocar» la dirección segura de ESTA misma computadora
     * (mismo host, puerto https) para saber si el teléfono ya acepta el certificado de la tienda
     * (asistente de avisos). Solo se agrega ese origen a connect-src; nada externo.
     */
    String cspPagina(HttpExchange ex, String base) {
        if (ex instanceof com.sun.net.httpserver.HttpsExchange) return base;
        String origen = origenSeguro(ex);
        return origen == null ? base : base.replace("connect-src 'self'", "connect-src 'self' " + origen);
    }

    private static final java.util.regex.Pattern HOST_SIMPLE = java.util.regex.Pattern.compile("[a-z0-9.-]{1,253}|\\[[0-9a-f:.]{2,45}\\]");

    /** https://<host de la petición>:<puerto https>, o null si el HTTPS está apagado o el host es raro */
    private String origenSeguro(HttpExchange ex) {
        int puerto = getPuertoHttps();
        if (puerto == 0) return null;
        String host = hostSinPuerto(ex).toLowerCase(Locale.ROOT);
        if (!HOST_SIMPLE.matcher(host).matches()) return null;
        return "https://" + host + ":" + puerto;
    }

    // ── Archivos estáticos (icono y manifiesto) ───────────────────────────

    /** Un archivo estático listo para servir: cuerpo, tipo y ETag (se calcula una vez) */
    record Estatico(byte[] cuerpo, String tipo, String etag) { }

    private final Map<String, Estatico> estaticosCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Lista CERRADA de rutas → recurso del classpath. Nunca se toca el disco ni se
     * compone una ruta con lo que pida el cliente: una ruta fuera de la lista es 404.
     */
    private static final Map<String, String> RECURSOS_ESTATICOS = Map.ofEntries(
            Map.entry("/favicon.ico",   "/icono/icono-32.png"),      // PNG servido como favicon: todos los navegadores lo aceptan
            Map.entry("/icono-32.png",  "/icono/icono-32.png"),
            Map.entry("/icono-256.png", "/icono/icono-256.png"),
            Map.entry("/icono-192.png", "/icono/icono-256.png"),     // Chrome exige 192 y 512 para instalar la app: se escalan del de 256
            Map.entry("/icono-512.png", "/icono/icono-256.png"),
            Map.entry("/sw.js",         "/movil/sw.js"),
            // Letras incluidas (Configuración → Apariencia; licencia OFL, resources/fuentes)
            Map.entry("/fuentes/Inter-Regular.ttf", "/fuentes/Inter-Regular.ttf"),
            Map.entry("/fuentes/Inter-Bold.ttf", "/fuentes/Inter-Bold.ttf"),
            Map.entry("/fuentes/AtkinsonHyperlegible-Regular.ttf", "/fuentes/AtkinsonHyperlegible-Regular.ttf"),
            Map.entry("/fuentes/AtkinsonHyperlegible-Bold.ttf", "/fuentes/AtkinsonHyperlegible-Bold.ttf"),
            Map.entry("/fuentes/Nunito-Regular.ttf", "/fuentes/Nunito-Regular.ttf"),
            Map.entry("/fuentes/Nunito-Bold.ttf", "/fuentes/Nunito-Bold.ttf"));

    /** Color de marca del icono oficial (packaging/icono/GeneradorIcono.MARINO_ARRIBA) */
    static final String COLOR_TEMA = "#2A4566";

    private Estatico cargarEstatico(String ruta) throws IOException {
        Estatico e = estaticosCache.get(ruta);
        if (e != null) return e;
        byte[] cuerpo;
        String tipo;
        if ("/manifest.webmanifest".equals(ruta)) {
            // No se guarda en caché: sus colores siguen a la apariencia de la tienda
            cuerpo = json().writeValueAsBytes(manifiesto());
            tipo = "application/manifest+json";
            String etagM = "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(cuerpo)).substring(0, 22) + "\"";
            return new Estatico(cuerpo, tipo, etagM);
        } else {
            String recurso = RECURSOS_ESTATICOS.get(ruta);
            if (recurso == null) return null;
            try (InputStream in = ServidorMovilService.class.getResourceAsStream(recurso)) {
                if (in == null) return null;
                cuerpo = in.readAllBytes();
            }
            if ("/sw.js".equals(ruta)) {
                tipo = "text/javascript; charset=utf-8";
            } else if (ruta.endsWith(".ttf")) {
                tipo = "font/ttf";
            } else {
                tipo = "image/png";
                if ("/icono-192.png".equals(ruta)) cuerpo = escalarPng(cuerpo, 192);
                if ("/icono-512.png".equals(ruta)) cuerpo = escalarPng(cuerpo, 512);
            }
        }
        String etag = "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(cuerpo)).substring(0, 22) + "\"";
        e = new Estatico(cuerpo, tipo, etag);
        estaticosCache.put(ruta, e);
        return e;
    }

    /** PNG cuadrado reescalado (bicúbico) para los tamaños que exige el manifiesto */
    static byte[] escalarPng(byte[] png, int tamano) throws IOException {
        java.awt.image.BufferedImage origen = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        if (origen == null) return png;
        java.awt.image.BufferedImage destino = new java.awt.image.BufferedImage(tamano, tamano, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = destino.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(origen, 0, 0, tamano, tamano, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(destino, "png", out);
        return out.toByteArray();
    }

    /** Manifiesto mínimo para "Agregar a pantalla de inicio" (nombre, icono y color de la app) */
    Map<String, Object> manifiesto() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "/");             // identidad fija de la app aunque cambie start_url (traspaso al icono de inicio)
        m.put("name", "Caja Libre");
        m.put("short_name", "Caja Libre");
        m.put("description", "Acceso móvil a Caja Libre (punto de venta) en la red local");
        m.put("lang", "es");
        m.put("start_url", "/");
        m.put("scope", "/");
        m.put("display", "standalone");
        m.put("orientation", "any");
        String color = colorTemaTienda();
        m.put("background_color", color);
        m.put("theme_color", color);
        List<Map<String, Object>> iconos = new ArrayList<>();
        for (int t : new int[] { 32, 192, 256, 512 }) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("src", "/icono-" + t + ".png");
            i.put("sizes", t + "x" + t);
            i.put("type", "image/png");
            i.put("purpose", "any");
            iconos.add(i);
        }
        m.put("icons", iconos);
        return m;
    }

    private void estatico(HttpExchange ex, String metodo, String ruta) throws IOException {
        if (!"GET".equals(metodo) && !"HEAD".equals(metodo)) throw new MetodoNoPermitido("GET, HEAD");
        if ("/manifest.webmanifest".equals(ruta)) {
            String inicio = parametros(ex).get("inicio");
            if (inicio != null && TraspasosSesion.formatoValido(inicio)) { manifiestoConInicio(ex, metodo, inicio); return; }
        }
        Estatico e = cargarEstatico(ruta);
        if (e == null) { error(ex, 404, "NO_ENCONTRADO", "No encontrado."); return; }
        var h = ex.getResponseHeaders();
        h.set("Content-Type", e.tipo());
        cabecerasComunes(ex);
        // Públicos y casi inmutables: el teléfono los guarda un día y después revalida por ETag.
        // El service worker siempre se revalida (el navegador busca versiones nuevas por su cuenta).
        h.set("Cache-Control", "/sw.js".equals(ruta) ? "no-cache" : "public, max-age=86400");
        if ("/sw.js".equals(ruta)) h.set("Content-Security-Policy", "default-src 'none'; img-src 'self'");
        h.set("ETag", e.etag());
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null && inm.contains(e.etag())) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        if ("HEAD".equals(metodo)) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, e.cuerpo().length);
        ex.getResponseBody().write(e.cuerpo());
    }

    /**
     * Manifiesto para «Agregar a inicio» con el código de traspaso en start_url (fragmento): al abrir
     * por primera vez desde el icono, la página canjea el código y no pide la contraseña otra vez.
     * El código solo se repite (no se valida aquí) y nunca se guarda en caché.
     */
    private void manifiestoConInicio(HttpExchange ex, String metodo, String codigo) throws IOException {
        Map<String, Object> m = manifiesto();
        m.put("start_url", "/#traspaso=" + codigo + "&ir=avisos");
        byte[] cuerpo = json().writeValueAsBytes(m);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "application/manifest+json");
        cabecerasComunes(ex);      // no-store
        if ("HEAD".equals(metodo)) { ex.sendResponseHeaders(200, -1); return; }
        ex.sendResponseHeaders(200, cuerpo.length);
        ex.getResponseBody().write(cuerpo);
    }

    /** Color de la barra del navegador y del icono de inicio: la banda de la apariencia de la tienda */
    String colorTemaTienda() {
        try {
            var a = aparienciaService.deTienda();
            if (a.coloresDeFabrica()) return COLOR_TEMA;
            return me.eliassanchezfernandez.puntodeventa.util.apariencia.Paleta.colorBanda(a);
        } catch (RuntimeException e) {
            return COLOR_TEMA;
        }
    }

    // ── Apariencia (Configuración → Apariencia del escritorio) ────────────

    /** La de la tienda, sin sesión: la pantalla de entrada ya sale con sus colores (sin iconos) */
    private void aparienciaPublica(HttpExchange ex) throws IOException {
        responder(ex, 200, AparienciaWeb.datos(aparienciaService.deTienda(), false));
    }

    /** La del usuario que entró ("solo para mí" o la de la tienda), con el paquete de iconos */
    private void apariencia(HttpExchange ex) throws IOException {
        protegido(ex, null, cajero -> AparienciaWeb.datos(aparienciaService.paraUsuario(cajero), true));
    }

    /** Nombre del negocio configurado en el escritorio (solo se entrega a quien ya tiene sesión) */
    private String nombreNegocio() {
        try {
            String n = configuracionService.obtener().getNombreTienda();
            return n == null ? "" : n.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ── Tutorial de la página móvil (marca por usuario en la BD) ──────────

    private void tutorial(HttpExchange ex) throws IOException {
        protegido(ex, null, cajero -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("visto", tutorialesService.movilVisto());
            return r;
        });
    }

    private void tutorialVisto(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        Map<String, Object> cuerpo = leerJson(ex);
        boolean visto = !Boolean.FALSE.equals(cuerpo.get("visto"));   // sin campo = marcar como visto
        protegido(ex, null, cajero -> {
            if (visto) tutorialesService.marcarMovilVisto(); else tutorialesService.reiniciarMovil();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("visto", visto);
            return r;
        });
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

    // ── Traspaso de la sesión a la página segura / al icono de inicio ──────

    private void crearTraspaso(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        String destino = texto(leerJson(ex).get("destino"), 10).trim();
        if (destino.isEmpty()) destino = "seguro";
        if (!destino.equals("seguro") && !destino.equals("inicio")) throw new CuerpoInvalido(400, "Destino inválido. Usa seguro o inicio.");
        boolean seguro = destino.equals("seguro");
        String origen = origenSeguro(ex);
        if (seguro && origen == null) {
            error(ex, 409, "SIN_HTTPS", "La conexión segura no está encendida en la computadora de la tienda.");
            return;
        }
        java.time.Duration vigencia = seguro ? TraspasosSesion.VIGENCIA_SEGURO : TraspasosSesion.VIGENCIA_INICIO;
        String codigo = traspasos().emitir(v.get().sesion().getId(), SesionMovilService.hash(tokenDe(ex)), vigencia);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("codigo", codigo);
        r.put("venceEnSegundos", vigencia.toSeconds());
        if (seguro) r.put("url", origen + "/#traspaso=" + codigo + "&ir=avisos");
        else r.put("manifiesto", "/manifest.webmanifest?inicio=" + codigo);
        responder(ex, 200, r);
    }

    private void canjearTraspaso(HttpExchange ex) throws IOException {
        String ip = ipDe(ex);
        long espera = limitador.segundosDeEspera(ip);
        if (espera > 0) { demasiadosIntentos(ex, espera); return; }
        String codigo = texto(leerJson(ex).get("codigo"), 64).trim();
        Optional<TraspasosSesion.Pendiente> p = traspasos().canjear(codigo);
        Optional<SesionValida> original = p.flatMap(x -> sesionMovilService.validarPorHash(x.tokenHash()))
                .filter(o -> o.sesion().getId().equals(p.get().sesionId()));
        if (original.isEmpty()) {
            limitador.registrarFallo(ip);
            error(ex, 401, "TRASPASO_INVALIDO", "Este enlace ya se usó o venció. Entra con tu usuario y contraseña.");
            return;
        }
        Cajero cajero = original.get().cajero();
        Long anterior = original.get().sesion().getId();
        var emitida = sesionMovilService.emitir(cajero, original.get().sesion().isPermanente(), ip, ex.getRequestHeaders().getFirst("User-Agent"));
        sesionMovilService.revocarPorId(anterior);
        auditoriaService.registrar(cajero, "LOGIN_MOVIL_TRASPASO", "ip " + ip + ": la sesión móvil id " + anterior
                + " pasó a la sesión id " + emitida.sesion().getId() + (ex instanceof com.sun.net.httpserver.HttpsExchange ? " (página segura)" : ""),
                (Cajero) null);
        Map<String, Object> r = datosSesion(new SesionValida(emitida.sesion(), cajero));
        r.put("token", emitida.token());
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
        r.put("negocio", nombreNegocio());
        // Funciones con la pastilla «Beta» (la lista vive en util/FuncionBeta)
        r.put("beta", me.eliassanchezfernandez.puntodeventa.util.FuncionBeta.nombresEnBeta());
        r.put("permisos", c.getPermisosEfectivos().stream().map(Enum::name).sorted().toList());
        Map<String, Object> secciones = new LinkedHashMap<>();
        boolean historial = c.tienePermiso(Permiso.VER_HISTORIAL);
        boolean ajustar = c.tienePermiso(Permiso.AJUSTAR_INVENTARIO);
        secciones.put("estadisticas", historial);
        secciones.put("historial", historial);
        secciones.put("inventario", true);
        secciones.put("inventarioAjustar", ajustar);
        boolean gestionarProveedores = c.tienePermiso(Permiso.GESTIONAR_PROVEEDORES);
        secciones.put("proveedores", true);
        secciones.put("clientes", FuncionesOcultas.FIADO_HABILITADO && c.tienePermiso(Permiso.VENDER_A_CREDITO));
        secciones.put("proveedoresEditar", gestionarProveedores);
        secciones.put("proveedoresCostos", gestionarProveedores || c.tienePermiso(Permiso.VER_GANANCIAS));
        // "Escanear ticket" sigue siendo una DEMO (las líneas son de ejemplo): apagada hasta tener OCR real.
        // Para probar la pantalla en desarrollo: cambiar a `ajustar`.
        secciones.put("escanearTicket", ESCANEAR_TICKET_HABILITADO && ajustar);
        secciones.put("configuracion", true);
        r.put("secciones", secciones);
        r.put("permanente", v.sesion().isPermanente());
        r.put("expira", v.sesion().getExpira() == null ? null
                : v.sesion().getExpira().toInstant(ZoneOffset.UTC).toEpochMilli());
        r.put("ahora", reloj.millis());
        Map<String, Object> seguro = new LinkedHashMap<>();   // para que la página ofrezca "Abre la página segura"
        seguro.put("activo", httpsActivo());
        seguro.put("puerto", getPuertoHttps());
        seguro.put("puertoHttp", getPuerto());
        seguro.put("huella", huellaCortaSegura());
        r.put("https", seguro);
        return r;
    }

    private String huellaCortaSegura() {
        try {
            return certificados.huellaCorta();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ── Datos reales (estadísticas, ventas, inventario) ───────────────────

    static final int MAX_DIAS_VENTAS = 93;
    /** Ventas por página si el teléfono no pide otra cantidad */
    static final int VENTAS_POR_PAGINA = 50;
    /** Máximo de ventas por página (tope del tamaño de la respuesta) */
    static final int MAX_VENTAS = me.eliassanchezfernandez.puntodeventa.service.ConsultasMovilService.MAX_VENTAS_POR_PAGINA;
    /** Renglones por venta que se mandan al teléfono (una venta enorme no infla la respuesta) */
    static final int MAX_LINEAS_POR_VENTA = 200;
    static final int MAX_PRODUCTOS = 200;

    /**
     * Consultas pesadas (ventas, estadísticas, inventario) que pueden correr a la vez. En una PC de
     * 2 núcleos con disco duro, más teléfonos consultando a la vez le quitan CPU y disco a la caja;
     * la siguiente recibe 503 «Intenta de nuevo en un momento» y la página reintenta sola.
     */
    static final int MAX_CONSULTAS_PESADAS = 2;
    final java.util.concurrent.Semaphore consultasPesadas = new java.util.concurrent.Semaphore(MAX_CONSULTAS_PESADAS);

    /**
     * Esqueleto común: token válido → permiso (comprobado aquí con el usuario recién leído de la BD
     * y vuelto a comprobar por el servicio) → servicio ejecutado COMO el usuario móvil.
     * El ámbito se limpia siempre (finally dentro de ejecutarComo).
     */
    private void protegido(HttpExchange ex, Permiso requerido, java.util.function.Function<Cajero, Object> accion)
            throws IOException {
        protegido(ex, requerido, false, accion);
    }

    /**
     * pesada = true: además ocupa uno de los MAX_CONSULTAS_PESADAS lugares (después de validar
     * sesión y permiso, así nadie sin sesión los acapara). Si no hay lugar: 503 + Retry-After.
     */
    private void protegido(HttpExchange ex, Permiso requerido, boolean pesada,
                           java.util.function.Function<Cajero, Object> accion) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        Cajero cajero = v.get().cajero();
        if (requerido != null && !cajero.tienePermiso(requerido)) { sinPermiso(ex); return; }
        if (pesada && !consultasPesadas.tryAcquire()) {
            ex.getResponseHeaders().set("Retry-After", "1");
            error(ex, 503, "OCUPADO", "Intenta de nuevo en un momento.");
            return;
        }
        Object resultado;
        try {
            Supplier<Object> trabajo = () -> accion.apply(cajero);
            resultado = sesionService.ejecutarComo(cajero, ipDe(ex), trabajo);
        } catch (PermisoRequeridoException e) {
            sinPermiso(ex);
            return;
        } finally {
            if (pesada) consultasPesadas.release();
        }
        responder(ex, 200, resultado);
    }

    /** Entero de la consulta dentro de [min, max]; ausente = porDefecto; otra cosa = 400 */
    private static int entero(String valor, int porDefecto, int min, int max, String campo) {
        if (valor == null || valor.isBlank()) return porDefecto;
        try {
            int n = Integer.parseInt(valor.trim());
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignorada) { /* abajo */ }
        throw new CuerpoInvalido(400, "El parámetro " + campo + " debe ser un número de " + min + " a " + max + ".");
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
        protegido(ex, Permiso.VER_HISTORIAL, true, cajero -> {
            LocalDate hoy = estadisticasService.hoy();
            Periodo p = switch (per) {
                case "semana" -> Periodo.semana(hoy);
                case "mes"    -> Periodo.mes(hoy);
                default       -> Periodo.dia(hoy);
            };
            String etiqueta = switch (per) { case "semana" -> "Semana"; case "mes" -> "Mes"; default -> "Hoy"; };
            boolean ganancias = cajero.tienePermiso(Permiso.VER_GANANCIAS);
            var e = consultasMovil.estadisticas(p, etiqueta, ganancias);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("periodo", per);
            r.put("desde", p.desde().toString());
            r.put("hasta", p.hasta().toString());
            r.put("totales", mapaTotales(e.totales()));

            e.comparacion().ifPresent(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("detalle", c.detalle());
                m.put("anterior", mapaTotales(c.anterior()));
                m.put("variacionPct", c.variacionPct());      // null = sin periodo anterior con ventas
                r.put("comparacion", m);
            });

            List<Map<String, Object>> top = new ArrayList<>();
            for (var t : e.top()) {
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
            e.franjas().forEach((f, t) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("franja", f.name());
                m.put("etiqueta", f.getEtiqueta());
                m.put("total", t.total());
                m.put("transacciones", t.transacciones());
                franjas.add(m);
            });
            r.put("franjas", franjas);

            List<Map<String, Object>> serie = new ArrayList<>();
            e.serie().forEach((d, total) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fecha", d.toString());
                m.put("total", total);
                serie.add(m);
            });
            r.put("serie", serie);

            r.put("gananciaVisible", ganancias);
            if (ganancias) r.put("ganancia", e.ganancia());
            return r;
        });
    }

    /**
     * Ventas del rango por páginas, más recientes primero: ?desde&hasta (máx. 93 días),
     * &pagina (0, 1, …) y &limite (1..MAX_VENTAS, 50 por defecto). Los totales («totales») son
     * del rango completo, calculados en SQL. «total» y «truncado» se conservan por compatibilidad
     * (ventas en el rango / hay más páginas).
     */
    private void ventas(HttpExchange ex) throws IOException {
        Map<String, String> q = parametros(ex);
        LocalDate hoy = estadisticasService.hoy();
        LocalDate desde = fecha(q.get("desde"), hoy);
        LocalDate hasta = fecha(q.get("hasta"), q.get("desde") == null ? hoy : desde);
        if (hasta.isBefore(desde)) throw new CuerpoInvalido(400, "La fecha final no puede ser anterior a la inicial.");
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > MAX_DIAS_VENTAS) {
            throw new CuerpoInvalido(400, "El rango máximo es de " + MAX_DIAS_VENTAS + " días.");
        }
        int pagina = entero(q.get("pagina"), 0, 0, me.eliassanchezfernandez.puntodeventa.service.ConsultasMovilService.MAX_PAGINA, "pagina");
        int limite = entero(q.get("limite"), VENTAS_POR_PAGINA, 1, MAX_VENTAS, "limite");
        protegido(ex, Permiso.VER_HISTORIAL, true, cajero -> {
            boolean ganancias = cajero.tienePermiso(Permiso.VER_GANANCIAS);
            var pag = consultasMovil.ventas(desde, hasta, pagina, limite);
            List<Map<String, Object>> lista = new ArrayList<>();
            for (Venta v : pag.ventas()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", v.getId());
                m.put("folio", v.getFolio());
                m.put("fecha", v.getFecha().toString());
                m.put("cajero", v.getCajero() == null ? "" : v.getCajero().getNombreCompleto());
                m.put("total", v.getTotal());
                m.put("formaPago", v.getFormaPago() == null ? "" : v.getFormaPago().getEtiqueta());
                m.put("cancelada", v.isCancelada());
                if (v.getTotalDevueltoOCero() > 0.004) m.put("devuelto", v.getTotalDevueltoOCero());   // devoluciones parciales
                List<Map<String, Object>> lineas = new ArrayList<>();
                for (DetalleVenta d : v.getDetalles()) {
                    if (lineas.size() >= MAX_LINEAS_POR_VENTA) break;
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("productoId", d.getProducto().getId());
                    l.put("descripcion", d.getDescripcionMostrada());   // producto común: el nombre tecleado
                    l.put("unidad", d.esProductoComun() ? (d.getCantidad() == Math.floor(d.getCantidad()) ? "pza" : "kg")
                            : unidadDe(d.getProducto()));
                    if (d.esProductoComun()) l.put("comun", true);
                    l.put("cantidad", d.getCantidad());
                    l.put("precio", d.getPrecioVenta());
                    l.put("importe", d.getImporte());
                    if (d.getCantidadDevueltaOCero() > 0) l.put("devuelta", d.getCantidadDevueltaOCero());
                    if (ganancias) l.put("costo", d.getCostoUnitarioODefault());
                    lineas.add(l);
                }
                m.put("lineas", lineas);
                if (v.getDetalles().size() > lineas.size()) m.put("lineasOmitidas", v.getDetalles().size() - lineas.size());
                lista.add(m);
            }
            Map<String, Object> totales = new LinkedHashMap<>();
            totales.put("ventas", pag.ventasEnRango());
            totales.put("canceladas", pag.canceladas());
            totales.put("importe", pag.importe());          // sin canceladas
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("desde", desde.toString());
            r.put("hasta", hasta.toString());
            r.put("pagina", pagina);
            r.put("limite", limite);
            r.put("ventas", lista);
            r.put("hayMas", pag.hayMas());
            r.put("totales", totales);
            r.put("total", pag.ventasEnRango());
            r.put("truncado", pag.hayMas());
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
        m.put("caducidad", p.isControlaCaducidad());
        return m;
    }

    private void inventario(HttpExchange ex) throws IOException {
        String q = parametros(ex).getOrDefault("q", "").trim();
        if (q.length() > 60) throw new CuerpoInvalido(400, "Búsqueda demasiado larga.");
        protegido(ex, null, true, cajero -> {
            boolean ganancias = cajero.tienePermiso(Permiso.VER_GANANCIAS);
            // Filtro, orden (existencia baja primero, luego nombre) y tope en SQL
            var encontrados = consultasMovil.inventario(q, MAX_PRODUCTOS);
            List<Producto> pagina = encontrados.productos();
            // Qué productos tienen foto (una consulta): la página solo pide la miniatura de esos
            java.util.Set<Long> conImagen = productoImagenService.idsConImagen(pagina.stream().map(Producto::getId).toList());
            List<Map<String, Object>> lista = new ArrayList<>();
            for (Producto p : pagina) {
                Map<String, Object> m = mapaProducto(p, ganancias);
                m.put("imagen", conImagen.contains(p.getId()));
                lista.add(m);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("productos", lista);
            r.put("total", encontrados.total());
            r.put("truncado", encontrados.total() > lista.size());
            r.put("puedeAjustar", cajero.tienePermiso(Permiso.AJUSTAR_INVENTARIO));
            return r;
        });
    }

    private static final java.util.regex.Pattern RUTA_IMAGEN = java.util.regex.Pattern.compile("/api/productos/(\\d{1,18})/imagen");

    /** id del producto si la ruta es /api/productos/{id}/imagen; null si no */
    static Long idDeRutaImagen(String ruta) {
        java.util.regex.Matcher m = RUTA_IMAGEN.matcher(ruta);
        return m.matches() ? Long.valueOf(m.group(1)) : null;
    }

    /**
     * Foto del producto (JPEG normalizado) o su miniatura con ?mini=1. Misma sesión que el
     * resto de la API (Bearer); cualquier usuario con sesión puede verla. El ETag es el SHA-256
     * de la imagen, así el teléfono la guarda un día y solo vuelve a bajarla si cambió.
     */
    private void imagenProducto(HttpExchange ex, long productoId) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        boolean mini = "1".equals(parametros(ex).get("mini"));
        Optional<ProductoImagen> imagen = productoImagenService.obtener(productoId);
        if (imagen.isEmpty()) { error(ex, 404, "SIN_IMAGEN", "El producto no tiene imagen."); return; }
        ProductoImagen i = imagen.get();
        String etag = "\"" + (mini ? "m-" : "i-") + i.getSha256().substring(0, 32) + "\"";
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Cache-Control", "private, max-age=86400");
        h.set("ETag", etag);
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null && inm.contains(etag)) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        byte[] cuerpo = mini ? i.getMiniatura() : i.getImagen();
        h.set("Content-Type", i.getMime());
        ex.sendResponseHeaders(200, cuerpo.length);
        ex.getResponseBody().write(cuerpo);
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
        java.time.LocalDate caducaEl = fechaOpcional(cuerpo.get("caducidad"), "caducidad");
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
                            costo == null ? 0 : costo, null, caducaEl,
                            me.eliassanchezfernandez.puntodeventa.model.Caducidad.Origen.MOVIL);
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

    // ── Proveedores (agenda semanal: entrega y preventa) ──────────────────

    private static List<Integer> numerosDias(java.util.Collection<DayOfWeek> dias) {
        List<Integer> r = new ArrayList<>();           // en orden lunes → domingo (1..7)
        for (DayOfWeek d : DayOfWeek.values()) if (dias != null && dias.contains(d)) r.add(d.getValue());
        return r;
    }

    private static Map<String, Object> mapaProveedor(ProveedorService.ResumenProveedor rp, boolean costos,
                                                     Map<Long, Long> productosPorProveedor) {
        Proveedor p = rp.proveedor();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("nombre", p.getNombre());
        m.put("productosQueSurte", productosPorProveedor.getOrDefault(p.getId(), 0L));   // proveedor habitual en Productos
        m.put("telefono", p.getTelefono() == null ? "" : p.getTelefono());
        m.put("contacto", p.getContacto() == null ? "" : p.getContacto());
        m.put("notas", p.getNotas() == null ? "" : p.getNotas());
        m.put("diasEntrega", numerosDias(p.getDias()));
        m.put("diasPreventa", numerosDias(p.getPreventa()));
        m.put("etiquetaEntrega", p.etiquetaDias());
        m.put("etiquetaPreventa", p.etiquetaPreventa());
        m.put("proximaEntrega", rp.proximaEntrega() == null ? null : rp.proximaEntrega().toString());
        m.put("proximaPreventa", rp.proximaPreventa() == null ? null : rp.proximaPreventa().toString());
        if (costos) {
            ProveedorService.NotaMedia n = rp.notaMedia();
            if (n == null) {
                m.put("notaMedia", null);        // "sin datos aún": el cliente lo distingue de "no puedes verlo" por costosVisibles
            } else {
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("promedio", n.promedio());
                nm.put("notas", n.notas());
                nm.put("ultima", n.ultima().toString());
                m.put("notaMedia", nm);
            }
        }
        return m;
    }

    private Map<String, Object> mapaAgenda(ProveedorService.Agenda a) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hoy", a.hoy().toString());
        r.put("diaSemanaHoy", a.hoy().getDayOfWeek().getValue());
        r.put("costosVisibles", a.costosVisibles());
        r.put("puedeEditar", a.puedeEditar());
        r.put("ventanaDias", ProveedorService.DIAS_NOTA_MEDIA);
        Map<Long, Long> productosPorProveedor = productoService.productosActivosPorProveedor();   // una consulta
        List<Map<String, Object>> lista = new ArrayList<>();
        for (ProveedorService.ResumenProveedor rp : a.proveedores()) {
            lista.add(mapaProveedor(rp, a.costosVisibles(), productosPorProveedor));
        }
        r.put("proveedores", lista);
        return r;
    }

    /** "Quién me debe" (solo lectura): clientes con saldo, de mayor a menor deuda, y el total por cobrar */
    private void saldosClientes(HttpExchange ex) throws IOException {
        protegido(ex, Permiso.VENDER_A_CREDITO, cajero -> {
            List<Map<String, Object>> lista = new ArrayList<>();
            double total = 0;
            for (me.eliassanchezfernandez.puntodeventa.model.Cliente c : cuentaClienteService.conSaldo()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("nombre", c.getNombre());
                m.put("telefono", c.getTelefono());
                m.put("saldo", me.eliassanchezfernandez.puntodeventa.service.VentaService.redondear(c.getSaldoDeuda()));
                m.put("limite", c.tieneLimite() ? c.getLimiteCredito() : null);
                m.put("activo", c.isActivo());
                lista.add(m);
                if (c.getSaldoDeuda() > 0) total += c.getSaldoDeuda();
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("total", me.eliassanchezfernandez.puntodeventa.service.VentaService.redondear(total));
            r.put("clientes", lista);
            return r;
        });
    }

    /** Agenda completa para cualquier usuario con sesión; el servicio decide si entrega la nota media */
    private void proveedores(HttpExchange ex) throws IOException {
        protegido(ex, null, cajero -> mapaAgenda(proveedorService.agenda()));
    }

    /** Lista JSON de días ISO (1 = lunes … 7 = domingo) → conjunto; cualquier otra cosa es 400 */
    private static java.util.Set<DayOfWeek> dias(Object o, String campo) {
        java.util.Set<DayOfWeek> r = java.util.EnumSet.noneOf(DayOfWeek.class);
        if (o == null) return r;
        if (!(o instanceof List<?> lista) || lista.size() > 7) throw new CuerpoInvalido(400, "El campo " + campo + " debe ser una lista de días (1 a 7).");
        for (Object x : lista) {
            if (!(x instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 1 || n.intValue() > 7) {
                throw new CuerpoInvalido(400, "El campo " + campo + " solo admite días del 1 (lunes) al 7 (domingo).");
            }
            r.add(DayOfWeek.of(n.intValue()));
        }
        return r;
    }

    /**
     * Crea o edita un proveedor desde el teléfono. Permiso comprobado ANTES de leer el cuerpo y de
     * nuevo en el servicio; los largos se acotan aquí y el servicio vuelve a validar todo. El RFC
     * no se captura en el móvil: al editar se conserva el que tenga.
     */
    private void guardarProveedor(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        if (!v.get().cajero().tienePermiso(Permiso.GESTIONAR_PROVEEDORES)) { sinPermiso(ex); return; }
        Map<String, Object> cuerpo = leerJson(ex);
        Long id = null;
        if (cuerpo.get("id") != null) {
            if (!(cuerpo.get("id") instanceof Number idNum) || idNum.doubleValue() != Math.rint(idNum.doubleValue()) || idNum.longValue() <= 0) {
                throw new CuerpoInvalido(400, "Proveedor inválido.");
            }
            id = idNum.longValue();
        }
        String nombre = texto(cuerpo.get("nombre"), 150);
        String telefono = texto(cuerpo.get("telefono"), 30);
        String contacto = texto(cuerpo.get("contacto"), 100);
        String notas = texto(cuerpo.get("notas"), 500);
        java.util.Set<DayOfWeek> entrega = dias(cuerpo.get("diasEntrega"), "diasEntrega");
        java.util.Set<DayOfWeek> preventa = dias(cuerpo.get("diasPreventa"), "diasPreventa");
        Long idFinal = id;
        protegido(ex, Permiso.GESTIONAR_PROVEEDORES, cajero -> {
            String rfc = idFinal == null ? null : proveedorService.obtener(idFinal).getRfc();
            Proveedor g = proveedorService.guardar(idFinal, nombre, rfc, telefono, contacto, entrega, preventa, notas);
            ProveedorService.Agenda a = proveedorService.agenda();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("creado", idFinal == null);
            Map<Long, Long> productosPorProveedor = productoService.productosActivosPorProveedor();
            a.proveedores().stream().filter(rp -> rp.proveedor().getId().equals(g.getId())).findFirst()
                    .ifPresent(rp -> r.put("proveedor", mapaProveedor(rp, a.costosVisibles(), productosPorProveedor)));
            return r;
        });
    }

    // ── Avisos (la campana de la página móvil) ────────────────────────────

    /**
     * Avisos del día para cualquier usuario con sesión. El servicio decide qué importes
     * entregar (nota media del proveedor solo a quien ve costos) y guarda el cálculo 5 minutos.
     */
    private void avisos(HttpExchange ex) throws IOException {
        protegido(ex, null, cajero -> mapaAvisos(avisosService.avisos()));
    }

    /** "Por caducar": lo que vence en los próximos días (o ya venció) con la cantidad estimada */
    private void caducidades(HttpExchange ex) throws IOException {
        protegido(ex, null, cajero -> {
            int dias = caducidadService.diasAviso();
            List<Map<String, Object>> lista = new ArrayList<>();
            for (var l : caducidadService.porCaducar(caducidadService.hoy(), dias)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", l.caducidadId());
                m.put("productoId", l.productoId());
                m.put("producto", l.producto());
                m.put("codigo", l.codigo());
                m.put("unidad", l.unidad());
                m.put("fecha", l.fechaCaducidad().toString());
                m.put("estimado", l.estimado());
                m.put("dias", l.dias());
                m.put("caducado", l.caducado());
                lista.add(m);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("dias", dias);
            r.put("lotes", lista);
            return r;
        });
    }

    private Map<String, Object> mapaAvisos(AvisosService.Avisos a) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hoy", a.hoy().toString());
        r.put("antiguedadSegundos", Math.max(0, Duration.between(a.generado(), LocalDateTime.now(reloj)).getSeconds()));
        r.put("costosVisibles", a.costosVisibles());
        r.put("total", a.avisos().size());
        List<Map<String, Object>> lista = new ArrayList<>();
        for (AvisosService.Aviso av : a.avisos()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", av.id());
            m.put("tipo", av.tipo().name());
            m.put("tipoEtiqueta", av.tipo().getEtiqueta());
            m.put("prioridad", av.prioridad());
            m.put("icono", av.icono());
            m.put("titulo", av.titulo());
            m.put("detalle", av.detalle());
            if (av.accion() == null) {
                m.put("accion", null);
            } else {
                Map<String, Object> ac = new LinkedHashMap<>();
                ac.put("seccion", av.accion().seccion());
                if (av.accion().proveedorId() != null) ac.put("proveedorId", av.accion().proveedorId());
                if (av.accion().filtro() != null) ac.put("filtro", av.accion().filtro());
                if (av.accion().buscar() != null) ac.put("buscar", av.accion().buscar());
                m.put("accion", ac);
            }
            lista.add(m);
        }
        r.put("avisos", lista);
        return r;
    }

    // ── Notificaciones push (suscripciones de este teléfono) ───────────────

    private void pushClave(HttpExchange ex) throws IOException {
        protegido(ex, null, cajero -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("clave", pushService.clavePublica());
            r.put("seguro", ex instanceof com.sun.net.httpserver.HttpsExchange);
            return r;
        });
    }

    /** Lo que la página necesita saber de la suscripción de este teléfono */
    static Map<String, Object> mapaSuscripcion(SuscripcionPush s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activa", s.isActiva());
        m.put("proveedores", s.isProveedores());
        m.put("stock", s.isStock());
        m.put("horaPico", s.isHoraPico());
        m.put("caducidad", s.isCaducidad());
        m.put("silencioDesde", s.getSilencioDesde());
        m.put("silencioHasta", s.getSilencioHasta());
        m.put("creada", s.getCreada() == null ? null : s.getCreada().toInstant(ZoneOffset.UTC).toEpochMilli());
        m.put("ultimoOk", s.getUltimoOk() == null ? null : s.getUltimoOk().toInstant(ZoneOffset.UTC).toEpochMilli());
        m.put("ultimoError", s.getUltimoError() == null ? null : s.getUltimoError().toInstant(ZoneOffset.UTC).toEpochMilli());
        m.put("errorDetalle", s.getErrorDetalle());
        return m;
    }

    private static String endpointDe(Map<String, Object> cuerpo) {
        String ep = texto(cuerpo.get("endpoint"), SuscripcionPush.MAX_ENDPOINT).trim();
        if (ep.isEmpty()) throw new CuerpoInvalido(400, "Falta el endpoint de la suscripción.");
        return ep;
    }

    private void pushSuscribir(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        String ip = ipDe(ex);
        long espera = limitadorSuscripciones.segundosDeEspera(ip);
        if (espera > 0) { demasiadosIntentos(ex, espera); return; }
        Map<String, Object> cuerpo = leerJson(ex);
        String endpoint = endpointDe(cuerpo);
        Object keys = cuerpo.get("keys");
        if (!(keys instanceof Map<?, ?> k)) throw new CuerpoInvalido(400, "Faltan las claves de la suscripción.");
        String p256dh = texto(k.get("p256dh"), 100), auth = texto(k.get("auth"), 30);
        if (!ValidadorEndpointPush.permitido(endpoint)) {
            limitadorSuscripciones.registrarFallo(ip);
            throw new CuerpoInvalido(400, "El servicio de notificaciones de este navegador no está en la lista de servicios conocidos.");
        }
        limitadorSuscripciones.registrarFallo(ip);      // cada alta cuenta para el límite por IP
        String ua = ex.getRequestHeaders().getFirst("User-Agent");
        Long sesionId = v.get().sesion().getId();
        protegido(ex, null, cajero -> {
            SuscripcionPush s = pushService.suscribir(cajero, sesionId, endpoint, p256dh, auth, ua);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("suscripcion", mapaSuscripcion(s));
            return r;
        });
    }

    private void pushDesuscribir(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        String endpoint = endpointDe(leerJson(ex));
        protegido(ex, null, cajero -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("existia", pushService.desuscribir(cajero, endpoint));
            return r;
        });
    }

    private void pushEstado(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        String endpoint = endpointDe(leerJson(ex));
        Optional<SuscripcionPush> s = pushService.propia(v.get().cajero(), endpoint);
        if (s.isEmpty()) { error(ex, 404, "SIN_SUSCRIPCION", "Este teléfono no tiene notificaciones activadas."); return; }
        responder(ex, 200, mapaSuscripcion(s.get()));
    }

    private static boolean bandera(Map<String, Object> cuerpo, String campo, boolean porDefecto) {
        Object o = cuerpo.get(campo);
        if (o == null) return porDefecto;
        if (!(o instanceof Boolean b)) throw new CuerpoInvalido(400, "El campo " + campo + " debe ser verdadero o falso.");
        return b;
    }

    private static int hora(Map<String, Object> cuerpo, String campo, int porDefecto) {
        Object o = cuerpo.get(campo);
        if (o == null) return porDefecto;
        if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 0 || n.intValue() > 23) {
            throw new CuerpoInvalido(400, "El campo " + campo + " debe ser una hora de 0 a 23.");
        }
        return n.intValue();
    }

    private void pushPreferencias(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        Map<String, Object> cuerpo = leerJson(ex);
        String endpoint = endpointDe(cuerpo);
        SuscripcionPush actual = pushService.propia(v.get().cajero(), endpoint).orElse(null);
        if (actual == null) { error(ex, 404, "SIN_SUSCRIPCION", "Este teléfono no tiene notificaciones activadas."); return; }
        boolean activa = bandera(cuerpo, "activa", actual.isActiva());
        boolean proveedores = bandera(cuerpo, "proveedores", actual.isProveedores());
        boolean stock = bandera(cuerpo, "stock", actual.isStock());
        boolean horaPico = bandera(cuerpo, "horaPico", actual.isHoraPico());
        boolean caducidad = bandera(cuerpo, "caducidad", actual.isCaducidad());
        int desde = hora(cuerpo, "silencioDesde", actual.getSilencioDesde());
        int hasta = hora(cuerpo, "silencioHasta", actual.getSilencioHasta());
        protegido(ex, null, cajero -> mapaSuscripcion(
                pushService.preferencias(cajero, endpoint, activa, proveedores, stock, horaPico, caducidad, desde, hasta)));
    }

    private void pushPrueba(HttpExchange ex) throws IOException {
        Optional<SesionValida> v = validarToken(ex);
        if (v.isEmpty()) { sesionInvalida(ex); return; }
        String ip = ipDe(ex);
        long espera = limitadorSuscripciones.segundosDeEspera(ip);
        if (espera > 0) { demasiadosIntentos(ex, espera); return; }
        limitadorSuscripciones.registrarFallo(ip);      // una prueba cuenta como un alta para el límite
        String endpoint = endpointDe(leerJson(ex));
        protegido(ex, null, cajero -> {
            NotificacionesPushService.Envio e = pushService.enviarPrueba(cajero, endpoint);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", e.ok());
            r.put("detalle", e.detalle());
            return r;
        });
    }

    // ── Certificado de la CA local (página pública de instalación) ─────────

    private volatile Pagina plantillaCertificado;

    /** Plantilla de /certificado con su CSP (hash del script); los datos se sustituyen por petición */
    private Pagina plantillaCertificado() throws IOException {
        Pagina p = plantillaCertificado;
        if (p != null) return p;
        byte[] cuerpo;
        try (InputStream in = ServidorMovilService.class.getResourceAsStream("/movil/certificado.html")) {
            if (in == null) return null;
            cuerpo = in.readAllBytes();
        }
        String html = new String(cuerpo, StandardCharsets.UTF_8);
        String hashScript = null;
        int ini = html.indexOf("<script>");
        int fin = html.lastIndexOf("</script>");
        if (ini >= 0 && fin > ini) {
            hashScript = "sha256-" + Base64.getEncoder().encodeToString(sha256(html.substring(ini + "<script>".length(), fin).getBytes(StandardCharsets.UTF_8)));
        }
        String csp = "default-src 'none'; script-src " + (hashScript == null ? "'none'" : "'" + hashScript + "'")
                + "; style-src 'unsafe-inline'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
        p = new Pagina(cuerpo, null, null, csp);
        plantillaCertificado = p;
        return p;
    }

    /** Host de la petición sin puerto (para armar la dirección https de ESTA misma computadora) */
    private static String hostSinPuerto(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        if (h == null) return "localhost";
        h = h.trim();
        if (h.startsWith("[")) { int fin = h.indexOf(']'); return fin > 0 ? h.substring(0, fin + 1) : h; }
        int dos = h.lastIndexOf(':');
        return dos > 0 ? h.substring(0, dos) : h;
    }

    private static String htmlSeguro(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Guía pública (sin sesión) para instalar la CA en el teléfono. Solo muestra lo que cualquiera
     * de la red ya podría pedir: el certificado público, su huella y la dirección https. Nada del
     * negocio salvo su nombre, nada de usuarios ni de la clave privada.
     */
    private void paginaCertificado(HttpExchange ex, String metodo) throws IOException {
        if (!"GET".equals(metodo) && !"HEAD".equals(metodo)) throw new MetodoNoPermitido("GET, HEAD");
        Pagina plantilla = plantillaCertificado();
        if (plantilla == null) { error(ex, 500, "ERROR", "Falta la página del certificado."); return; }
        String huella = certificados.huellaCa();
        int puertoHttps = getPuertoHttps();
        String host = hostSinPuerto(ex);
        String urlHttps = puertoHttps == 0 ? "" : "https://" + host + ":" + puertoHttps + "/";
        String html = new String(plantilla.normal(), StandardCharsets.UTF_8)
                .replace("{{NEGOCIO}}", htmlSeguro(nombreNegocio()))
                .replace("{{HUELLA_CORTA}}", CertificadosLocales.huellaCorta(huella))
                .replace("{{HUELLA}}", CertificadosLocales.huellaConDosPuntos(huella))
                .replace("{{URL_HTTPS}}", htmlSeguro(urlHttps))
                .replace("{{HTTPS_ACTIVO}}", puertoHttps == 0 ? "no" : "si");
        byte[] cuerpo = html.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "text/html; charset=utf-8");
        h.set("Content-Security-Policy", plantilla.csp());
        cabecerasComunes(ex);
        if ("HEAD".equals(metodo)) { ex.sendResponseHeaders(200, -1); return; }
        ex.sendResponseHeaders(200, cuerpo.length);
        ex.getResponseBody().write(cuerpo);
    }

    /** Descarga del certificado público de la CA: DER para Android, perfil .mobileconfig para iPhone */
    private void descargaCertificado(HttpExchange ex, String metodo, String ruta) throws IOException {
        if (!"GET".equals(metodo) && !"HEAD".equals(metodo)) throw new MetodoNoPermitido("GET, HEAD");
        byte[] cuerpo;
        String tipo, nombre;
        if (ruta.endsWith(".mobileconfig")) {
            cuerpo = certificados.mobileconfig(nombreNegocio()).getBytes(StandardCharsets.UTF_8);
            tipo = "application/x-apple-aspen-config";
            nombre = "caja-libre.mobileconfig";
        } else {
            cuerpo = certificados.caDer();
            tipo = "application/x-x509-ca-cert";
            nombre = "caja-libre.crt";
        }
        var h = ex.getResponseHeaders();
        h.set("Content-Type", tipo);
        h.set("Content-Disposition", "attachment; filename=\"" + nombre + "\"");
        cabecerasComunes(ex);
        if ("HEAD".equals(metodo)) { ex.sendResponseHeaders(200, -1); return; }
        ex.sendResponseHeaders(200, cuerpo.length);
        ex.getResponseBody().write(cuerpo);
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
            return json().readValue(buf.toByteArray(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new CuerpoInvalido(400, "JSON inválido.");
        }
    }

    /** Fecha AAAA-MM-DD opcional (null o "" = sin fecha) */
    static java.time.LocalDate fechaOpcional(Object o, String campo) {
        if (o == null) return null;
        if (!(o instanceof String s)) throw new CuerpoInvalido(400, "El campo " + campo + " debe ser una fecha AAAA-MM-DD.");
        if (s.isBlank()) return null;
        if (s.length() > 10) throw new CuerpoInvalido(400, "El campo " + campo + " debe ser una fecha AAAA-MM-DD.");
        try {
            return java.time.LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new CuerpoInvalido(400, "El campo " + campo + " debe ser una fecha AAAA-MM-DD.");
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
        byte[] bytes = json().writeValueAsBytes(cuerpo);
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
