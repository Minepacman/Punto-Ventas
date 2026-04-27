package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;



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

    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private PasswordEncoder  passwordEncoder;

    /** Cajero que inició sesión actualmente (null = nadie autenticado) */
    private Cajero cajeroActual = null;

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

        Optional<Cajero> encontrado =
                cajeroRepo.findByUsuarioIgnoreCaseAndActivoTrue(usuario);

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

    /** Cierra la sesión actual */
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

    // ── Excepción interna ─────────────────────────────────────────────────

    public static class CredencialesInvalidasException extends Exception {
        public CredencialesInvalidasException(String msg) { super(msg); }
    }

    // ── Inicialización: crear admin por defecto si no hay cajeros ─────────

    /**
     * Crea un cajero administrador por defecto si la tabla está vacía.
     * Usuario: admin  /  Contraseña: admin123
     *
     * IMPORTANTE: cambiar la contraseña en producción.
     */
    public void crearAdminPorDefectoSiNecesario() {
        if (cajeroRepo.count() == 0) {
            Cajero admin = new Cajero();
            admin.setUsuario("admin");
            admin.setNombreCompleto("Administrador");
            admin.setContrasenaHash(passwordEncoder.encode("admin123"));
            admin.setRol(Cajero.Rol.ADMIN);
            admin.setActivo(true);
            cajeroRepo.save(admin);
            System.out.println("✅ Cajero admin creado por defecto. Usuario: admin / Contraseña: admin123");
        }
    }
}