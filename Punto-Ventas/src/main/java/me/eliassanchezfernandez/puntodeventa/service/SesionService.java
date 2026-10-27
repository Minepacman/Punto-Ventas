package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Servicio de autenticación, sesión activa y permisos.
 *
 * Patrón: singleton de Spring (@Service) que guarda al cajero autenticado
 * en memoria durante toda la ejecución. Al cerrar sesión se limpia.
 *
 * Seguridad:
 *  - Intentos fallidos (USR-04): al 5.º fallo consecutivo el usuario queda
 *    bloqueado 15 min; al 10.º, 1 h; después se duplica (máx. 24 h). El
 *    bloqueo siempre vence solo (también para el único administrador) y un
 *    ADMIN puede quitarlo antes. Vale igual para el login y para el diálogo
 *    de autorización (verificarCredenciales es la única ruta).
 *  - Cambio de contraseña obligatorio (USR-06): mientras el usuario deba
 *    cambiarla, la sesión NO sirve para operar (requerirSesion/verificar*
 *    lo rechazan); solo puede cambiar su contraseña.
 *  - Permisos (USR-13/16/19): verificarPermiso() lanza
 *    PermisoRequeridoException salvo que exista una Autorizacion vigente de
 *    un administrador para ese permiso.
 */
@Service
public class SesionService {

    public static final String ADMIN_USUARIO    = "admin";
    public static final String ADMIN_CONTRASENA = "admin123";
    public static final int    MAX_INTENTOS_FALLIDOS = 5;

    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private PasswordEncoder  passwordEncoder;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private SesionMovilService sesionMovilService;

    /** Cajero que inició sesión actualmente (null = nadie autenticado) */
    private volatile Cajero cajeroActual = null;

    // ── Autenticación ─────────────────────────────────────────────────────

    /**
     * Inicia sesión. Si el usuario debe cambiar su contraseña, la sesión
     * queda "pendiente": solo podrá cambiarla (ver sesionPendienteDeCambio).
     */
    public Cajero autenticar(String usuario, String contrasena)
            throws CredencialesInvalidasException {
        rechazarSiEsActorMovil();
        Cajero cajero = verificarCredenciales(usuario, contrasena);
        cajeroActual = cajero;
        return cajero;
    }

    /**
     * Valida usuario + contraseña SIN abrir sesión, aplicando el conteo de
     * intentos y el bloqueo temporal. La usan el login y la autorización
     * con clave de administrador.
     *
     * IMPORTANTE: no llamarla desde dentro de un método @Transactional de
     * negocio (p. ej. cobrar). El intento fallido y su registro en la
     * bitácora se guardan con la transacción vigente; si la del llamador se
     * revierte, el LOGIN_FALLIDO/bloqueo se perdería con ella. Se diseñó
     * para invocarse desde los controladores, fuera de toda transacción.
     */
    public Cajero verificarCredenciales(String usuario, String contrasena)
            throws CredencialesInvalidasException {

        if (usuario == null || usuario.isBlank() || contrasena == null || contrasena.isEmpty()) {
            throw new CredencialesInvalidasException("Usuario y contraseña son obligatorios.");
        }
        String nombreUsuario = usuario.trim();

        Optional<Cajero> encontrado = cajeroRepo.findByUsuarioIgnoreCase(nombreUsuario);
        if (encontrado.isEmpty()) {
            auditoriaService.registrarIndependiente(nombreUsuario, "LOGIN_FALLIDO", "Usuario inexistente");
            throw new CredencialesInvalidasException("Usuario no encontrado o inactivo.");
        }
        Cajero cajero = encontrado.get();

        if (!cajero.isActivo()) {
            auditoriaService.registrarIndependiente(nombreUsuario, "LOGIN_FALLIDO", "Usuario inactivo");
            throw new CredencialesInvalidasException("Usuario no encontrado o inactivo.");
        }
        if (cajero.isBloqueado()) {
            auditoriaService.registrarIndependiente(nombreUsuario, "LOGIN_BLOQUEADO",
                    "Intento con usuario bloqueado hasta " + cajero.getBloqueadoHasta());
            throw new CredencialesInvalidasException(String.format(
                    "Usuario bloqueado por intentos fallidos. Intenta de nuevo en %d minuto(s) o pide a un administrador que lo desbloquee.",
                    Math.max(1, java.time.Duration.between(LocalDateTime.now(), cajero.getBloqueadoHasta()).toMinutes())));
        }

        if (!passwordEncoder.matches(contrasena, cajero.getContrasenaHash())) {
            registrarIntentoFallido(cajero);
            throw new CredencialesInvalidasException("Contraseña incorrecta.");
        }

        if (cajero.getIntentosFallidosOCero() > 0 || cajero.getBloqueadoHasta() != null) {
            cajero.setIntentosFallidos(0);
            cajero.setBloqueadoHasta(null);
            cajeroRepo.save(cajero);
        }
        return cajero;
    }

    /** Mensaje único de todo fallo de credenciales móviles (no revela si el usuario existe, está bloqueado, etc.) */
    public static final String MENSAJE_CREDENCIALES_MOVIL = "Usuario o contraseña incorrectos.";

    private volatile String hashFicticio;

    /**
     * Credenciales del acceso móvil. A diferencia de {@link #verificarCredenciales}:
     *  - NUNCA cuenta intentos ni bloquea al usuario en el escritorio (el conteo de
     *    la red móvil vive en ServidorMovilService: por usuario y por IP);
     *  - rechaza al usuario que esté bloqueado en el escritorio, sin decirlo;
     *  - responde siempre con el mismo mensaje y tarda igual exista o no el usuario;
     *  - audita cada fallo con la IP del teléfono.
     */
    public Cajero verificarCredencialesMovil(String usuario, String contrasena, String ip)
            throws CredencialesInvalidasException {
        String nombre = usuario == null ? "" : usuario.trim();
        Optional<Cajero> encontrado = nombre.isEmpty() || contrasena == null || contrasena.isEmpty()
                ? Optional.empty() : cajeroRepo.findByUsuarioIgnoreCase(nombre);
        String hash = encontrado.map(Cajero::getContrasenaHash).orElseGet(this::hashFicticio);
        boolean coincide = contrasena != null && !contrasena.isEmpty() && passwordEncoder.matches(contrasena, hash);

        String motivo = null;
        if (encontrado.isEmpty()) motivo = "usuario inexistente";
        else if (!encontrado.get().isActivo()) motivo = "usuario inactivo";
        else if (encontrado.get().isBloqueado()) motivo = "usuario bloqueado en el escritorio";
        else if (!coincide) motivo = "contraseña incorrecta";

        if (motivo != null) {
            auditoriaService.registrarIndependiente(nombre.length() > 60 ? nombre.substring(0, 60) : nombre,
                    "LOGIN_MOVIL_FALLIDO", "ip " + ip + ": " + motivo);
            throw new CredencialesInvalidasException(MENSAJE_CREDENCIALES_MOVIL);
        }
        return encontrado.get();
    }

    private String hashFicticio() {
        String h = hashFicticio;
        if (h == null) hashFicticio = h = passwordEncoder.encode("contrasena-ficticia-sin-usuario");
        return h;
    }

    @Transactional
    protected void registrarIntentoFallido(Cajero cajero) {
        int intentos = cajero.getIntentosFallidosOCero() + 1;
        cajero.setIntentosFallidos(intentos);
        String detalle = "Intento fallido " + intentos + " (bloqueo cada " + MAX_INTENTOS_FALLIDOS + ")";

        if (intentos % MAX_INTENTOS_FALLIDOS == 0) {
            long minutos = minutosDeBloqueo(intentos);
            cajero.setBloqueadoHasta(LocalDateTime.now().plusMinutes(minutos));
            detalle += " → usuario BLOQUEADO " + minutos + " min";
            auditoriaService.registrarIndependiente(cajero.getUsuario(), "USUARIO_BLOQUEADO", detalle);
        }
        cajeroRepo.save(cajero);
        auditoriaService.registrarIndependiente(cajero.getUsuario(), "LOGIN_FALLIDO", detalle);
    }

    /** 5 fallos → 15 min, 10 → 60 min, 15 → 120 min, ... máximo 24 h */
    static long minutosDeBloqueo(int intentos) {
        int bloqueoNumero = intentos / MAX_INTENTOS_FALLIDOS;   // 1, 2, 3, ...
        long minutos = bloqueoNumero <= 1 ? 15 : 60L << (bloqueoNumero - 2);
        return Math.min(minutos, 24 * 60);
    }

    /** Cierra la sesión actual */
    public void cerrarSesion() {
        rechazarSiEsActorMovil();
        cajeroActual = null;
    }

    /**
     * El usuario en sesión cambia SU contraseña (primer inicio obligatorio,
     * USR-06): debe conocer la actual, cumplir la política y no repetir la
     * actual, su usuario ni la contraseña por defecto.
     */
    @Transactional
    public void cambiarContrasenaPropia(String actual, String nueva) {
        rechazarSiEsActorMovil();
        Cajero c = cajeroActual;
        if (c == null) throw new NegocioException("No hay una sesión activa. Vuelve a iniciar sesión.");
        if (actual == null || !passwordEncoder.matches(actual, c.getContrasenaHash())) {
            throw new NegocioException("La contraseña actual no es correcta.");
        }
        PoliticaContrasena.validar(nueva);
        if (passwordEncoder.matches(nueva, c.getContrasenaHash())) {
            throw new NegocioException("La nueva contraseña debe ser distinta a la actual.");
        }
        if (nueva.equalsIgnoreCase(c.getUsuario()) || nueva.equals(ADMIN_CONTRASENA)) {
            throw new NegocioException("Elige una contraseña que no sea tu usuario ni la contraseña por defecto.");
        }
        Cajero enBd = cajeroRepo.findById(c.getId())
                .orElseThrow(() -> new NegocioException("El usuario ya no existe."));
        enBd.setContrasenaHash(passwordEncoder.encode(nueva));
        enBd.setDebeCambiarContrasena(false);
        cajeroRepo.save(enBd);
        cajeroActual = enBd;
        sesionMovilService.revocarDeCajero(enBd.getId(), "cambio de contraseña");
        auditoriaService.registrar(enBd, "CAMBIO_CONTRASENA_PROPIA", null, (Cajero) null);
    }

    // ── Consultas de sesión ───────────────────────────────────────────────

    /** true solo si hay alguien autenticado Y no tiene pendiente cambiar su contraseña */
    public boolean haySesionActiva() {
        Cajero c = actorEfectivo();
        return c != null && !c.isDebeCambiarContrasena();
    }

    /** Hay alguien autenticado pero debe cambiar su contraseña antes de operar */
    public boolean sesionPendienteDeCambio() {
        Cajero c = actorEfectivo();
        return c != null && c.isDebeCambiarContrasena();
    }

    public Cajero getCajeroActual() {
        return actorEfectivo();
    }

    public String getNombreCajero() {
        Cajero c = actorEfectivo();
        return c != null ? c.getNombreCompleto() : "—";
    }

    public boolean esAdmin() {
        Cajero c = actorEfectivo();
        return c != null && !c.isDebeCambiarContrasena() && c.getRol() == Cajero.Rol.ADMIN;
    }

    public boolean esGerente() {
        Cajero c = actorEfectivo();
        return c != null && !c.isDebeCambiarContrasena() && c.getRol() == Cajero.Rol.GERENTE;
    }

    /** Lanza NegocioException si quien está en sesión no es administrador */
    public void verificarAdmin() {
        Cajero c = requerirSesion();
        if (c.getRol() != Cajero.Rol.ADMIN) {
            throw new NegocioException("Solo un administrador puede realizar esta operación.");
        }
    }

    /**
     * Sesión utilizable: alguien autenticado, que sigue existiendo y activo
     * en la BD y que no tiene pendiente cambiar su contraseña. Relee la fila
     * para que desactivaciones o cambios de permisos surtan efecto de inmediato.
     */
    public Cajero requerirSesion() {
        ContextoActor.Actor movil = ContextoActor.actual();
        if (movil != null) return requerirActorMovil(movil);
        Cajero c = cajeroActual;
        if (c == null) {
            throw new NegocioException("No hay una sesión activa. Vuelve a iniciar sesión.");
        }
        Cajero enBd = cajeroRepo.findById(c.getId()).orElse(null);
        if (enBd == null || !enBd.isActivo()) {
            cajeroActual = null;
            throw new NegocioException("La sesión fue invalidada por un administrador. Vuelve a iniciar sesión.");
        }
        cajeroActual = enBd;
        if (enBd.isDebeCambiarContrasena()) {
            throw new NegocioException("Debes cambiar tu contraseña antes de continuar.");
        }
        return enBd;
    }

    // ── Permisos ──────────────────────────────────────────────────────────

    public boolean tienePermiso(Permiso permiso) {
        Cajero c = actorEfectivo();
        return c != null && !c.isDebeCambiarContrasena() && c.tienePermiso(permiso);
    }

    /** Lanza PermisoRequeridoException si el usuario en sesión no tiene el permiso */
    public void verificarPermiso(Permiso permiso) {
        verificarPermiso(permiso, (Collection<Autorizacion>) null);
    }

    /** Igual, pero acepta una autorización puntual de administrador (USR-19) */
    public void verificarPermiso(Permiso permiso, Autorizacion autorizacion) {
        verificarPermiso(permiso, autorizacion == null ? null : List.of(autorizacion));
    }

    public void verificarPermiso(Permiso permiso, Collection<Autorizacion> autorizaciones) {
        Cajero c = requerirSesion();
        if (c.tienePermiso(permiso)) return;
        if (Autorizacion.buscar(autorizaciones, permiso) != null) return;
        throw new PermisoRequeridoException(permiso);
    }

    /**
     * Autorización que cubre el permiso cuando el propio usuario NO lo tiene
     * (null si lo tiene o si no hay autorización vigente). Sirve para dejar
     * constancia de quién autorizó y para consumirla al terminar.
     */
    public Autorizacion autorizacionUsada(Permiso permiso, Collection<Autorizacion> autorizaciones) {
        if (tienePermiso(permiso)) return null;
        return Autorizacion.buscar(autorizaciones, permiso);
    }

    /** Nombre del administrador que autorizó el permiso, o null si el usuario lo tenía */
    public String autorizadorPara(Permiso permiso, Collection<Autorizacion> autorizaciones) {
        Autorizacion a = autorizacionUsada(permiso, autorizaciones);
        return a != null ? a.nombreAdministrador() : null;
    }

    /** Refresca el cajero en sesión desde la BD (p. ej. tras cambiar sus permisos) */
    public void refrescarSesion() {
        if (ContextoActor.actual() != null) return;   // el escritorio no se toca desde el móvil
        Cajero c = cajeroActual;
        if (c != null) {
            cajeroRepo.findById(c.getId()).ifPresent(x -> cajeroActual = x);
        }
    }

    // ── Usuario que actúa (acceso móvil) ──────────────────────────────────

    /**
     * Ejecuta {@code accion} haciendo que TODO lo que consulte la sesión
     * (verificarPermiso, requerirSesion, getCajeroActual, auditoría, movimientos
     * de inventario) vea a {@code cajero} como el usuario, en lugar del de la
     * sesión de escritorio. El actor vive en un ThreadLocal y se limpia SIEMPRE en
     * el finally (también si la acción lanza una excepción), restaurando el
     * anterior si hubiera anidamiento. La sesión de escritorio (cajeroActual) no
     * se lee ni se escribe dentro del ámbito. La auditoría agrega "[vía móvil, IP x]".
     *
     * Debe ejecutarse en el MISMO hilo que las llamadas a servicios (las
     * transacciones de Spring también son por hilo, así que es lo natural).
     */
    public <T> T ejecutarComo(Cajero cajero, String ip, Supplier<T> accion) {
        if (cajero == null) throw new IllegalArgumentException("Falta el usuario que actúa.");
        ContextoActor.Actor anterior = ContextoActor.fijar(new ContextoActor.Actor(cajero, ip));
        try {
            return accion.get();
        } finally {
            ContextoActor.restaurar(anterior);
        }
    }

    public void ejecutarComo(Cajero cajero, String ip, Runnable accion) {
        ejecutarComo(cajero, ip, () -> { accion.run(); return null; });
    }

    /** Usuario del ámbito móvil si lo hay; si no, el de la sesión de escritorio */
    private Cajero actorEfectivo() {
        ContextoActor.Actor a = ContextoActor.actual();
        return a != null ? a.cajero() : cajeroActual;
    }

    /** Relee al usuario móvil de la BD en cada operación: desactivarlo o quitarle permisos surte efecto de inmediato */
    private Cajero requerirActorMovil(ContextoActor.Actor actor) {
        Cajero enBd = cajeroRepo.findById(actor.cajero().getId()).orElse(null);
        if (enBd == null || !enBd.isActivo()) {
            throw new NegocioException("Tu usuario ya no está disponible. Inicia sesión de nuevo.");
        }
        if (enBd.isDebeCambiarContrasena()) {
            throw new NegocioException("Debes cambiar tu contraseña en el programa de escritorio.");
        }
        return enBd;
    }

    /** Dentro de un ámbito móvil nunca se abre, cierra ni modifica la sesión de escritorio */
    private static void rechazarSiEsActorMovil() {
        if (ContextoActor.actual() != null) {
            throw new IllegalStateException("La sesión de escritorio no se puede cambiar desde el acceso móvil.");
        }
    }

    // ── Excepción interna ─────────────────────────────────────────────────

    public static class CredencialesInvalidasException extends Exception {
        public CredencialesInvalidasException(String msg) { super(msg); }
    }

    // ── Inicialización: crear admin por defecto si no hay cajeros ─────────

    /**
     * Se ejecuta cuando el contexto ya está listo. Se usa un @EventListener en
     * lugar de @PostConstruct para que la llamada pase por el proxy de Spring
     * y la siembra corra dentro de UNA transacción.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        crearAdminPorDefectoSiNecesario();
    }

    /**
     * Crea el administrador por defecto (usuario "admin") cuando el sistema
     * no tiene ninguno: tabla vacía, o sin ningún ADMIN y sin un usuario
     * llamado "admin". NUNCA modifica la contraseña de un usuario existente.
     *
     * El admin creado nace con "debe cambiar contraseña" (USR-06). Si ya
     * existía un "admin" que todavía usa la contraseña por defecto, solo se
     * le marca que debe cambiarla al entrar.
     */
    @Transactional
    public void crearAdminPorDefectoSiNecesario() {

        boolean tablaVacia = cajeroRepo.count() == 0;
        boolean hayAdmin   = cajeroRepo.existsByRol(Cajero.Rol.ADMIN);
        Optional<Cajero> existente = cajeroRepo.findByUsuarioIgnoreCase(ADMIN_USUARIO);

        if (!tablaVacia && (hayAdmin || existente.isPresent())) {
            existente.ifPresent(admin -> {
                boolean usaClavePorDefecto = passwordEncoder.matches(ADMIN_CONTRASENA, admin.getContrasenaHash());
                if (usaClavePorDefecto && !admin.isDebeCambiarContrasena()) {
                    admin.setDebeCambiarContrasena(true);
                    cajeroRepo.save(admin);
                }
            });
            return;
        }

        Cajero admin = new Cajero();
        admin.setUsuario(ADMIN_USUARIO);
        admin.setNombreCompleto("Administrador");
        admin.setContrasenaHash(passwordEncoder.encode(ADMIN_CONTRASENA));
        admin.setRol(Cajero.Rol.ADMIN);
        admin.setActivo(true);
        admin.setDebeCambiarContrasena(true);
        cajeroRepo.save(admin);

        auditoriaService.registrarSistema("ADMIN_POR_DEFECTO_CREADO",
                "Usuario " + ADMIN_USUARIO + " creado; debe cambiar la contraseña al entrar");
        System.out.println("✅ Administrador por defecto creado (usuario: "
                + ADMIN_USUARIO + "). Deberá cambiar su contraseña al iniciar sesión.");
    }
}
