package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.SesionMovil;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.SesionMovilRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Sesiones de la interfaz web móvil (tokens de acceso de los teléfonos).
 *
 *  - Token: 256 bits aleatorios (SecureRandom) en Base64url. Solo se guarda su SHA-256.
 *  - Caducidad: "No mantener abierta" = EXACTAMENTE 48 h desde que se creó la
 *    sesión; "Sí" = permanente (expira null). Se revisa EN EL SERVIDOR en cada
 *    llamada; el teléfono solo la muestra.
 *  - Una sesión deja de valer si se revoca (logout, administrador), si el
 *    usuario se desactiva/elimina, si debe cambiar su contraseña o si la
 *    cambia (CajeroService/SesionService llaman a revocarDeCajero).
 *
 * Importante: este servicio NO depende de SesionService, así que nunca toca la
 * sesión del escritorio (que es de un solo usuario). Las operaciones de
 * administración con permiso verificado están en AccesoMovilService.
 */
@Service
public class SesionMovilService {

    /** Vigencia de una sesión que NO se mantiene abierta */
    public static final Duration VIGENCIA_TEMPORAL = Duration.ofHours(48);
    /** Una sesión permanente sin ningún uso en este tiempo deja de valer (vencimiento deslizante) */
    public static final Duration INACTIVIDAD_PERMANENTE = Duration.ofDays(60);
    /** Máximo de sesiones móviles abiertas por usuario; al emitir otra se cierra la menos reciente */
    public static final int MAX_SESIONES_POR_USUARIO = 5;
    /** Las sesiones vencidas o revocadas se borran después de este tiempo */
    public static final Duration RETENCION_INUTILES = Duration.ofDays(30);
    /** Cada cuánto se actualiza ultimo_uso (evita escrituras en cada llamada) */
    static final Duration REFRESCO_ULTIMO_USO = Duration.ofSeconds(60);

    @Autowired private SesionMovilRepository sesionRepo;
    @Autowired private CajeroRepository      cajeroRepo;
    @Autowired private AuditoriaService      auditoriaService;
    @Autowired private Clock                 reloj;

    private final SecureRandom azar = new SecureRandom();

    /** Token en claro (solo existe aquí y en el teléfono) + la fila guardada */
    public record Emitida(String token, SesionMovil sesion) { }

    /** Sesión vigente con su usuario ya cargado */
    public record SesionValida(SesionMovil sesion, Cajero cajero) { }

    // ── Emitir / validar ──────────────────────────────────────────────────

    @Transactional
    public Emitida emitir(Cajero cajero, boolean permanente, String ip, String userAgent) {
        byte[] bytes = new byte[32];                                  // 256 bits
        azar.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        LocalDateTime ahora = ahora();
        purgarInutiles(ahora);
        limitarSesiones(cajero.getId(), ahora);
        SesionMovil s = new SesionMovil();
        s.setTokenHash(hash(token));
        s.setCajeroId(cajero.getId());
        s.setCreada(ahora);
        s.setExpira(permanente ? null : ahora.plus(VIGENCIA_TEMPORAL));
        s.setUltimoUso(ahora);
        s.setIp(recortar(ip, 64));
        s.setUserAgent(recortar(userAgent, 250));
        return new Emitida(token, sesionRepo.save(s));
    }

    /**
     * Valida el token: existe, no está revocado, no venció y su usuario sigue
     * activo y sin cambio de contraseña pendiente. Vacío = pedir credenciales.
     */
    @Transactional
    public Optional<SesionValida> validar(String token) {
        if (token == null || token.isEmpty() || token.length() > 128) return Optional.empty();
        Optional<SesionMovil> encontrada = sesionRepo.findByTokenHash(hash(token));
        if (encontrada.isEmpty()) return Optional.empty();
        SesionMovil s = encontrada.get();
        LocalDateTime ahora = ahora();
        if (s.isRevocada() || vencida(s, ahora)) return Optional.empty();

        Cajero c = cajeroRepo.findById(s.getCajeroId()).orElse(null);
        if (c == null || !c.isActivo() || c.isDebeCambiarContrasena()) {
            s.setRevocada(true);
            sesionRepo.save(s);
            return Optional.empty();
        }
        if (s.getUltimoUso() == null || Duration.between(s.getUltimoUso(), ahora).compareTo(REFRESCO_ULTIMO_USO) >= 0) {
            s.setUltimoUso(ahora);
            sesionRepo.save(s);
        }
        return Optional.of(new SesionValida(s, c));
    }

    /**
     * Temporal: vence exactamente a la hora de expiración (ahora >= expira).
     * Permanente: vence tras INACTIVIDAD_PERMANENTE sin uso (deslizante).
     */
    static boolean vencida(SesionMovil s, LocalDateTime ahoraUtc) {
        if (s.getExpira() != null) return !ahoraUtc.isBefore(s.getExpira());
        LocalDateTime ultimo = s.getUltimoUso() != null ? s.getUltimoUso() : s.getCreada();
        return ultimo != null && !ahoraUtc.isBefore(ultimo.plus(INACTIVIDAD_PERMANENTE));
    }

    /** Borra las filas revocadas o vencidas hace más de RETENCION_INUTILES */
    int purgarInutiles(LocalDateTime ahora) {
        return sesionRepo.purgar(ahora.minus(RETENCION_INUTILES),
                ahora.minus(INACTIVIDAD_PERMANENTE).minus(RETENCION_INUTILES));
    }

    /** Diario, 03:30: limpieza aunque nadie inicie sesión */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void trabajoDeLimpieza() {
        purgarInutiles(ahora());
    }

    /** Deja espacio para una sesión nueva: si ya hay MAX abiertas, cierra las menos recientes */
    private void limitarSesiones(Long cajeroId, LocalDateTime ahora) {
        List<SesionMovil> abiertas = new java.util.ArrayList<>(sesionRepo.findByCajeroIdAndRevocadaFalse(cajeroId).stream()
                .filter(x -> !vencida(x, ahora)).toList());
        abiertas.sort(java.util.Comparator.comparing(
                (SesionMovil x) -> x.getUltimoUso() != null ? x.getUltimoUso() : x.getCreada()));
        int sobran = abiertas.size() - (MAX_SESIONES_POR_USUARIO - 1);
        for (int i = 0; i < sobran; i++) {
            abiertas.get(i).setRevocada(true);
            sesionRepo.save(abiertas.get(i));
            auditoriaService.registrarSistema("SESION_MOVIL_REVOCADA_POR_LIMITE",
                    "Sesión móvil id " + abiertas.get(i).getId() + " del usuario id " + cajeroId
                    + " cerrada: máximo " + MAX_SESIONES_POR_USUARIO + " sesiones abiertas");
        }
    }

    public boolean vencida(SesionMovil s) {
        return vencida(s, ahora());
    }

    /**
     * "¿Mantener la sesión siempre abierta?" contestado DESPUÉS de entrar:
     * Sí = permanente; No = 48 h contadas desde que se creó la sesión.
     */
    @Transactional
    public SesionMovil fijarPermanente(Long sesionId, boolean permanente) {
        SesionMovil s = sesionRepo.findById(sesionId).orElseThrow(() -> new NegocioException("La sesión no existe."));
        if (s.isRevocada()) throw new NegocioException("La sesión ya fue cerrada.");
        s.setExpira(permanente ? null : s.getCreada().plus(VIGENCIA_TEMPORAL));
        return sesionRepo.save(s);
    }

    // ── Revocar ───────────────────────────────────────────────────────────

    /** Cierra la sesión del token (logout del teléfono). true si había una abierta. */
    @Transactional
    public boolean cerrar(String token) {
        if (token == null || token.isEmpty() || token.length() > 128) return false;
        Optional<SesionMovil> s = sesionRepo.findByTokenHash(hash(token));
        if (s.isEmpty() || s.get().isRevocada()) return false;
        s.get().setRevocada(true);
        sesionRepo.save(s.get());
        return true;
    }

    /** Revoca todas las sesiones móviles de un usuario (desactivado, contraseña cambiada, eliminado) */
    @Transactional
    public int revocarDeCajero(Long cajeroId, String motivo) {
        if (cajeroId == null) return 0;
        List<SesionMovil> abiertas = sesionRepo.findByCajeroIdAndRevocadaFalse(cajeroId);
        for (SesionMovil s : abiertas) s.setRevocada(true);
        sesionRepo.saveAll(abiertas);
        if (!abiertas.isEmpty()) {
            auditoriaService.registrarSistema("SESIONES_MOVIL_REVOCADAS",
                    abiertas.size() + " sesión(es) móvil(es) del usuario id " + cajeroId + ": " + motivo);
        }
        return abiertas.size();
    }

    /** Sesiones abiertas y no vencidas, más recientes primero (sin verificar permisos: ver AccesoMovilService) */
    @Transactional(readOnly = true)
    public List<SesionMovil> listarActivas() {
        LocalDateTime ahora = ahora();
        return sesionRepo.findByRevocadaFalseOrderByCreadaDesc().stream()
                .filter(s -> !vencida(s, ahora)).toList();
    }

    @Transactional
    public Optional<SesionMovil> revocarPorId(Long id) {
        Optional<SesionMovil> s = sesionRepo.findById(id).filter(x -> !x.isRevocada());
        s.ifPresent(x -> { x.setRevocada(true); sesionRepo.save(x); });
        return s;
    }

    @Transactional
    public int revocarTodas() {
        List<SesionMovil> abiertas = sesionRepo.findByRevocadaFalseOrderByCreadaDesc();
        for (SesionMovil s : abiertas) s.setRevocada(true);
        sesionRepo.saveAll(abiertas);
        return abiertas.size();
    }

    public Optional<Cajero> cajeroDe(SesionMovil s) {
        return cajeroRepo.findById(s.getCajeroId());
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    /** Ahora en UTC según el reloj de la aplicación (las pruebas lo fijan) */
    LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC);
    }

    public static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String recortar(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
