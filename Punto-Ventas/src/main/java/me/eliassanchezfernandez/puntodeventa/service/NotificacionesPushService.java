package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush;
import me.eliassanchezfernandez.puntodeventa.repository.SuscripcionPushRepository;
import me.eliassanchezfernandez.puntodeventa.servidor.EnviadorPush;
import me.eliassanchezfernandez.puntodeventa.servidor.EnviadorPushHttp;
import me.eliassanchezfernandez.puntodeventa.servidor.ValidadorEndpointPush;
import me.eliassanchezfernandez.puntodeventa.servidor.WebPushCripto;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notificaciones Web Push a los teléfonos: suscripciones (altas, bajas, preferencias) y el
 * envío cifrado de un mensaje (RFC 8030/8291/8292) con la clave VAPID de esta instalación.
 *
 *  - Clave VAPID (P-256): archivos {@code push-vapid.key} (PKCS#8) y {@code push-vapid.pub}
 *    (X.509) en la carpeta de claves ({@link RutasApp#carpetaClaves()}), junto a la CA local.
 *    La pública se entrega a los teléfonos; la privada nunca sale ni se registra.
 *  - El endpoint que manda el teléfono solo se acepta si apunta a un servicio de push conocido
 *    ({@link ValidadorEndpointPush}); el envío real ({@link EnviadorPushHttp}) no sigue redirecciones.
 *  - Quién decide QUÉ y CUÁNDO se manda es {@link ProgramadorAvisosPush}; aquí solo se manda.
 *  - Las operaciones de usuario reciben el {@link Cajero} ya autenticado por el servidor móvil y
 *    solo tocan sus propias suscripciones; las de administración (listar todo, quitar) las expone
 *    {@link AccesoMovilService} con verificación de ADMIN.
 */
@Service
public class NotificacionesPushService {

    /** Suscripciones por usuario; al pasarse se borra la más antigua */
    public static final int MAX_POR_USUARIO = 10;
    /** El servicio de push guarda el mensaje este tiempo si el teléfono está apagado */
    public static final int TTL_SEGUNDOS = 4 * 3600;
    /** Vigencia del JWT de VAPID (máximo permitido: 24 h) y cuándo se renueva */
    static final Duration VIGENCIA_JWT = Duration.ofHours(12);
    static final Duration RENOVAR_JWT_ANTES = Duration.ofHours(1);
    /** Responsable que ve el servicio de push si hay problemas (RFC 8292 §2.1) */
    static final String CONTACTO_VAPID = "https://github.com/Minepacman/Punto-Ventas";
    static final String ARCHIVO_CLAVE = "push-vapid.key";
    static final String ARCHIVO_PUBLICA = "push-vapid.pub";

    /** Lo que se manda al teléfono (el service worker lo muestra) */
    public record Mensaje(String titulo, String cuerpo, String etiqueta, String url) { }

    /** Resultado de un envío para mostrarlo */
    public record Envio(boolean ok, String detalle) { }

    @Autowired private SuscripcionPushRepository repo;
    @Autowired private RutasApp rutas;
    @Autowired private Clock reloj;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private ConfiguracionService configuracionService;

    private volatile EnviadorPush enviador = new EnviadorPushHttp();
    private volatile KeyPair vapid;
    private final Map<String, String[]> jwts = new ConcurrentHashMap<>();   // audiencia -> {jwt, expira epoch}
    private volatile com.fasterxml.jackson.databind.ObjectMapper json;

    /** Pruebas: sustituye el envío real */
    public void usarEnviador(EnviadorPush e) {
        this.enviador = e == null ? new EnviadorPushHttp() : e;
    }

    // ── Clave VAPID ───────────────────────────────────────────────────────

    /** Clave pública VAPID en base64url (65 bytes sin comprimir), la que pide PushManager.subscribe */
    public String clavePublica() {
        return WebPushCripto.B64URL.encodeToString(WebPushCripto.publicaCruda(par().getPublic()));
    }

    private KeyPair par() {
        KeyPair k = vapid;
        if (k != null) return k;
        synchronized (this) {
            if (vapid == null) vapid = leerOCrearPar();
            return vapid;
        }
    }

    private KeyPair leerOCrearPar() {
        Path carpeta = rutas.carpetaClaves();
        Path pk = carpeta.resolve(ARCHIVO_CLAVE), pu = carpeta.resolve(ARCHIVO_PUBLICA);
        try {
            if (Files.isRegularFile(pk) && Files.isRegularFile(pu)) {
                KeyFactory kf = KeyFactory.getInstance("EC");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(pk)));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pu)));
                String prueba = WebPushCripto.jwtVapid(priv, "https://x", 1, "x");
                if (WebPushCripto.verificarJwt(prueba, pub)) return new KeyPair(pub, priv);
                System.err.println("⚠ La clave VAPID guardada no corresponde a su pública: se crea otra (los teléfonos deberán volver a activar las notificaciones).");
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            System.err.println("⚠ No se pudo leer la clave VAPID (" + e.getMessage() + "): se crea otra.");
        }
        KeyPair nuevo = WebPushCripto.nuevoPar();
        try {
            Files.createDirectories(carpeta);
            restringir(carpeta);
            escribir(pk, nuevo.getPrivate().getEncoded());
            escribir(pu, nuevo.getPublic().getEncoded());
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar la clave VAPID en " + carpeta + ": " + e.getMessage(), e);
        }
        return nuevo;
    }

    private static void escribir(Path p, byte[] datos) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, datos);
        restringir(tmp);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void restringir(Path p) {
        try {
            Files.setPosixFilePermissions(p, Files.isDirectory(p)
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignorada) { /* Windows */ }
    }

    // ── Suscripciones del usuario ─────────────────────────────────────────

    /** Valida y guarda (o actualiza) la suscripción de este teléfono para el usuario */
    @Transactional
    public SuscripcionPush suscribir(Cajero cajero, Long sesionMovilId, String endpoint, String p256dh, String auth, String userAgent) {
        String ep = validarEndpoint(endpoint);
        byte[] clave, secreto;
        try {
            clave = WebPushCripto.B64URL_DEC.decode(texto(p256dh, 100));
            WebPushCripto.publicaDeCruda(clave);                                  // punto válido de P-256
            secreto = WebPushCripto.B64URL_DEC.decode(texto(auth, 30));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new NegocioException("Las claves de la suscripción no son válidas.");
        }
        if (secreto.length != 16) throw new NegocioException("Las claves de la suscripción no son válidas.");

        LocalDateTime ahora = ahora();
        String hash = hash(ep);
        SuscripcionPush s = repo.findByEndpointHash(hash).orElse(null);
        boolean nueva = s == null;
        if (nueva) {
            s = new SuscripcionPush();
            s.setEndpoint(ep);
            s.setEndpointHash(hash);
            s.setCreada(ahora);
            List<SuscripcionPush> propias = repo.findByCajeroIdOrderByCreadaAsc(cajero.getId());
            for (int i = 0; i <= propias.size() - MAX_POR_USUARIO; i++) repo.delete(propias.get(i));
        } else if (!s.getCajeroId().equals(cajero.getId())) {
            // Otro usuario entró en el mismo teléfono: la suscripción pasa a ser suya, con sus preferencias por defecto
            s.setProveedores(true); s.setStock(true); s.setHoraPico(true); s.setCaducidad(true); s.setActiva(true);
            s.setSilencioDesde(SuscripcionPush.SILENCIO_DESDE_POR_DEFECTO);
            s.setSilencioHasta(SuscripcionPush.SILENCIO_HASTA_POR_DEFECTO);
            s.setEnviadosDia(null); s.setEnviadosIds(null);
        }
        s.setCajeroId(cajero.getId());
        s.setSesionMovilId(sesionMovilId);
        s.setP256dh(WebPushCripto.B64URL.encodeToString(clave));
        s.setAuth(WebPushCripto.B64URL.encodeToString(secreto));
        s.setUserAgent(recortar(userAgent, 250));
        s.setErrorDetalle(null);
        SuscripcionPush g = repo.save(s);
        if (nueva) auditoriaService.registrar(cajero, "PUSH_SUSCRITO", "Notificaciones activadas en un teléfono (" + resumenAgente(userAgent) + ")", (Cajero) null);
        return g;
    }

    /** Quita la suscripción si es del usuario. Idempotente. */
    @Transactional
    public boolean desuscribir(Cajero cajero, String endpoint) {
        Optional<SuscripcionPush> s = propia(cajero, endpoint);
        s.ifPresent(x -> {
            repo.delete(x);
            auditoriaService.registrar(cajero, "PUSH_DESUSCRITO", "Notificaciones desactivadas en un teléfono (" + resumenAgente(x.getUserAgent()) + ")", (Cajero) null);
        });
        return s.isPresent();
    }

    /** Suscripción de este teléfono si pertenece al usuario */
    @Transactional(readOnly = true)
    public Optional<SuscripcionPush> propia(Cajero cajero, String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > SuscripcionPush.MAX_ENDPOINT) return Optional.empty();
        return repo.findByEndpointHash(hash(endpoint.trim())).filter(s -> s.getCajeroId().equals(cajero.getId()));
    }

    /** Preferencias del teléfono: interruptor general, tipos y horas de silencio (0-23) */
    @Transactional
    public SuscripcionPush preferencias(Cajero cajero, String endpoint, boolean activa, boolean proveedores, boolean stock, boolean horaPico,
                                        int silencioDesde, int silencioHasta) {
        SuscripcionPush actual = propia(cajero, endpoint).orElse(null);
        return preferencias(cajero, endpoint, activa, proveedores, stock, horaPico,
                actual == null || actual.isCaducidad(), silencioDesde, silencioHasta);
    }

    /** Igual, con el tipo "Por caducar" */
    @Transactional
    public SuscripcionPush preferencias(Cajero cajero, String endpoint, boolean activa, boolean proveedores, boolean stock, boolean horaPico,
                                        boolean caducidad, int silencioDesde, int silencioHasta) {
        if (silencioDesde < 0 || silencioDesde > 23 || silencioHasta < 0 || silencioHasta > 23) {
            throw new NegocioException("Las horas de silencio deben estar entre 0 y 23.");
        }
        SuscripcionPush s = propia(cajero, endpoint).orElseThrow(() -> new NegocioException("Este teléfono no tiene notificaciones activadas."));
        s.setActiva(activa);
        s.setProveedores(proveedores);
        s.setStock(stock);
        s.setHoraPico(horaPico);
        s.setCaducidad(caducidad);
        s.setSilencioDesde(silencioDesde);
        s.setSilencioHasta(silencioHasta);
        return repo.save(s);
    }

    /** Notificación de prueba a este teléfono, ahora mismo (sin horas de silencio ni repetidos) */
    public Envio enviarPrueba(Cajero cajero, String endpoint) {
        SuscripcionPush s = propia(cajero, endpoint).orElseThrow(() -> new NegocioException("Este teléfono no tiene notificaciones activadas."));
        return enviar(s, mensajePrueba(), false);
    }

    /** Prueba a un teléfono elegido en el escritorio (la verificación de administrador la hace AccesoMovilService) */
    public Envio enviarPruebaASuscripcion(Long suscripcionId) {
        SuscripcionPush s = repo.findById(suscripcionId).orElseThrow(() -> new NegocioException("Ese teléfono ya no tiene avisos activados."));
        return enviar(s, mensajePrueba(), false);
    }

    private Mensaje mensajePrueba() {
        return new Mensaje("Caja Libre: avisos activos",
                "Así llegarán los avisos de " + nombreNegocioCorto() + ": proveedores, productos que se acaban, caducidades y hora pico.",
                "prueba", "/#/config");
    }

    private String nombreNegocioCorto() {
        try {
            String n = configuracionService.obtener().getNombreTienda();
            return n == null || n.isBlank() ? "la tienda" : n.trim();
        } catch (RuntimeException e) {
            return "la tienda";
        }
    }

    // ── Envío ─────────────────────────────────────────────────────────────

    /**
     * Cifra y manda el mensaje. Anota el resultado en la suscripción; si el servicio dice que ya no
     * existe (404/410) la borra. Nunca lanza: devuelve el resultado para registrarlo.
     */
    public Envio enviar(SuscripcionPush s, Mensaje m, boolean urgente) {
        byte[] cuerpo;
        String authorization;
        try {
            byte[] carga = json().writeValueAsBytes(Map.of(
                    "titulo", m.titulo() == null ? "" : m.titulo(),
                    "cuerpo", m.cuerpo() == null ? "" : m.cuerpo(),
                    "etiqueta", m.etiqueta() == null ? "" : m.etiqueta(),
                    "url", m.url() == null ? "/" : m.url()));
            cuerpo = WebPushCripto.cifrar(WebPushCripto.B64URL_DEC.decode(s.getP256dh()), WebPushCripto.B64URL_DEC.decode(s.getAuth()), carga);
            authorization = WebPushCripto.cabeceraAuthorization(jwtPara(ValidadorEndpointPush.origen(s.getEndpoint())), par().getPublic());
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            return anotar(s, false, "No se pudo cifrar el mensaje: " + e.getMessage());
        }
        if (!ValidadorEndpointPush.permitido(s.getEndpoint())) return anotar(s, false, "Servicio de push no reconocido.");
        try {
            EnviadorPush.Resultado r = enviador.enviar(s.getEndpoint(), cuerpo, authorization, TTL_SEGUNDOS, urgente);
            if (r.suscripcionMuerta()) {
                borrarMuerta(s, r.estado());
                return new Envio(false, "El teléfono ya no está suscrito (" + r.estado() + "); se quitó de la lista.");
            }
            return anotar(s, r.ok(), r.ok() ? "Entregado al servicio de push." : "El servicio de push respondió " + r.estado()
                    + (r.detalle() == null || r.detalle().isBlank() ? "" : ": " + r.detalle()));
        } catch (IOException e) {
            return anotar(s, false, "Sin conexión con el servicio de push: " + e.getMessage());
        }
    }

    void borrarMuerta(SuscripcionPush s, int estado) {
        repo.findById(s.getId()).ifPresent(repo::delete);
        auditoriaService.registrarSistema("PUSH_SUSCRIPCION_BAJA", "El servicio de push respondió " + estado + " para un teléfono del usuario id "
                + s.getCajeroId() + ": se quitó la suscripción.");
    }

    Envio anotar(SuscripcionPush s, boolean ok, String detalle) {
        repo.findById(s.getId()).ifPresent(x -> {
            if (ok) {
                x.setUltimoOk(ahora());
                x.setErrorDetalle(null);
            } else {
                x.setUltimoError(ahora());
                x.setErrorDetalle(recortar(detalle, 200));
            }
            repo.save(x);
        });
        return new Envio(ok, detalle);
    }

    /** JWT por audiencia, reutilizado hasta una hora antes de vencer */
    String jwtPara(String audiencia) throws GeneralSecurityException {
        long ahora = reloj.instant().getEpochSecond();
        String[] c = jwts.get(audiencia);
        if (c != null && Long.parseLong(c[1]) - ahora > RENOVAR_JWT_ANTES.toSeconds()) return c[0];
        long exp = ahora + VIGENCIA_JWT.toSeconds();
        String jwt = WebPushCripto.jwtVapid(par().getPrivate(), audiencia, exp, CONTACTO_VAPID);
        jwts.put(audiencia, new String[] { jwt, String.valueOf(exp) });
        return jwt;
    }

    // ── Consultas para el escritorio y el programador ─────────────────────

    @Transactional(readOnly = true)
    public List<SuscripcionPush> listarTodas() {
        return repo.findAllByOrderByCreadaDesc();
    }

    @Transactional(readOnly = true)
    public List<SuscripcionPush> activas() {
        return repo.findByActivaTrueOrderByCajeroIdAscCreadaAsc();
    }

    @Transactional
    public Optional<SuscripcionPush> quitarPorId(Long id) {
        Optional<SuscripcionPush> s = repo.findById(id);
        s.ifPresent(repo::delete);
        return s;
    }

    /** Borra todas las de un usuario (desactivado o eliminado) */
    @Transactional
    public int quitarDeCajero(Long cajeroId) {
        List<SuscripcionPush> l = repo.findByCajeroIdOrderByCreadaAsc(cajeroId);
        repo.deleteAll(l);
        return l.size();
    }

    @Transactional
    public void guardar(SuscripcionPush s) {
        repo.save(s);
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    static String validarEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new NegocioException("Falta el endpoint de la suscripción.");
        String ep = endpoint.trim();
        if (ep.length() > SuscripcionPush.MAX_ENDPOINT || !ValidadorEndpointPush.permitido(ep)) {
            throw new NegocioException("El servicio de notificaciones de este navegador no está en la lista de servicios conocidos.");
        }
        return ep;
    }

    private static String texto(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max) throw new IllegalArgumentException("inválido");
        return s.trim();
    }

    public static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "iPhone", "Android", "Windows", "Mac" u "otro": lo justo para reconocer el aparato en la lista del escritorio */
    public static String resumenAgente(String ua) {
        if (ua == null) return "dispositivo";
        String u = ua.toLowerCase(java.util.Locale.ROOT);
        if (u.contains("iphone")) return "iPhone";
        if (u.contains("ipad")) return "iPad";
        if (u.contains("android")) return "Android";
        if (u.contains("windows")) return "Windows";
        if (u.contains("mac os")) return "Mac";
        if (u.contains("linux")) return "Linux";
        return "dispositivo";
    }

    private static String recortar(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC);
    }

    private com.fasterxml.jackson.databind.ObjectMapper json() {
        com.fasterxml.jackson.databind.ObjectMapper j = json;
        if (j == null) json = j = new com.fasterxml.jackson.databind.ObjectMapper();
        return j;
    }
}
