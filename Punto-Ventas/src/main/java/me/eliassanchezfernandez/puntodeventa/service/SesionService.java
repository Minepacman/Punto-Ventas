package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Servicio de autenticación y sesión activa.
 *
 * Patrón: singleton de Spring (@Service) que guarda al cajero
 * autenticado en memoria durante toda la ejecución.
 * Al cerrar sesión se limpia el estado.
 */
@Service
public class SesionService {

    public static final String ADMIN_USUARIO    = "admin";
    public static final String ADMIN_CONTRASENA = "admin123";

    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private PasswordEncoder  passwordEncoder;

    /** Cajero que inició sesión actualmente (null = nadie autenticado) */
    private Cajero cajeroActual = null;

    private Double fondoDeCajaDelDia = null;
    private LocalDate fechaDelFondo = null;

    // ── Autenticación ─────────────────────────────────────────────────────

    /**
     * Intenta autenticar al usuario.
     *
     * @param usuario    nombre de usuario
     * @param contrasena contraseña en texto plano (se compara contra el hash)
     * @return el Cajero autenticado si las credenciales son correctas
     * @throws CredencialesInvalidasException si el usuario no existe,
     *         está inactivo o la contraseña no coincide
     */
    public Cajero autenticar(String usuario, String contrasena)
            throws CredencialesInvalidasException {

        if (usuario == null || usuario.isBlank() || contrasena == null || contrasena.isEmpty()) {
            throw new CredencialesInvalidasException("Usuario y contraseña son obligatorios.");
        }

        Optional<Cajero> encontrado =
                cajeroRepo.findByUsuarioIgnoreCaseAndActivoTrue(usuario.trim());

        if (encontrado.isEmpty()) {
            throw new CredencialesInvalidasException("Usuario no encontrado o inactivo.");
        }

        Cajero cajero = encontrado.get();

        if (!passwordEncoder.matches(contrasena, cajero.getContrasenaHash())) {
            throw new CredencialesInvalidasException("Contraseña incorrecta.");
        }

        cajeroActual = cajero;
        return cajero;
    }

    /** Cierra la sesión actual (el fondo de caja del día se conserva) */
    public void cerrarSesion() {
        cajeroActual = null;
    }

    // ── Consultas de sesión ───────────────────────────────────────────────

    public boolean haySesionActiva() {
        return cajeroActual != null;
    }

    public Cajero getCajeroActual() {
        return cajeroActual;
    }

    public String getNombreCajero() {
        return cajeroActual != null ? cajeroActual.getNombreCompleto() : "—";
    }

    public boolean esAdmin() {
        return cajeroActual != null &&
               cajeroActual.getRol() == Cajero.Rol.ADMIN;
    }

    /** Lanza NegocioException si quien está en sesión no es administrador */
    public void verificarAdmin() {
        if (!esAdmin()) {
            throw new NegocioException(
                    "Solo un administrador puede realizar esta operación.");
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
     * y la siembra corra dentro de UNA transacción (@PostConstruct invoca al
     * bean "crudo" y la anotación @Transactional no aplicaría).
     */
    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        crearAdminPorDefectoSiNecesario();
    }

    /**
     * Crea el administrador por defecto (usuario "admin") cuando el sistema
     * no tiene ninguno: tabla vacía, o sin ningún ADMIN y sin un usuario
     * llamado "admin". NUNCA modifica ni restablece un usuario existente.
     *
     * Es idempotente: en cuanto existe un ADMIN (o el usuario "admin")
     * no hace nada. La contraseña por defecto está en ADMIN_CONTRASENA y
     * no se imprime en consola.
     *
     * IMPORTANTE: cambiar la contraseña en producción.
     */
    @Transactional
    public void crearAdminPorDefectoSiNecesario() {

        boolean tablaVacia = cajeroRepo.count() == 0;
        boolean hayAdmin   = cajeroRepo.existsByRol(Cajero.Rol.ADMIN);
        boolean existeUsuarioAdmin = cajeroRepo.findByUsuarioIgnoreCase(ADMIN_USUARIO).isPresent();

        if (!tablaVacia && (hayAdmin || existeUsuarioAdmin)) {
            return;
        }

        Cajero admin = new Cajero();
        admin.setUsuario(ADMIN_USUARIO);
        admin.setNombreCompleto("Administrador");
        admin.setContrasenaHash(passwordEncoder.encode(ADMIN_CONTRASENA));
        admin.setRol(Cajero.Rol.ADMIN);
        admin.setActivo(true);
        cajeroRepo.save(admin);

        System.out.println("✅ Administrador por defecto creado (usuario: "
                + ADMIN_USUARIO + "). Cambia su contraseña desde Configuración → Cajeros.");
    }

    // ── Fondo de caja del día ─────────────────────────────────────────────

    public boolean requiereIngresarFondo() {
        // si no hay un fondo registrado, o si el fondo guardado no es de hoy
        return fondoDeCajaDelDia == null || !LocalDate.now().equals(fechaDelFondo);
    }

    public void registrarFondoDeCaja(double monto) {
        this.fondoDeCajaDelDia = monto;
        this.fechaDelFondo = LocalDate.now();
    }

    public Double getFondoDeCajaDelDia() {
        return fondoDeCajaDelDia;
    }
}
