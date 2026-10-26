package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Administración de usuarios del sistema (cajeros y administradores).
 *
 * Todas las operaciones que modifican usuarios exigen que quien está en
 * sesión sea ADMIN; de lo contrario se lanza NegocioException.
 *
 * Reglas:
 *  - El nombre de usuario es único (sin distinguir mayúsculas).
 *  - Nunca puede quedar el sistema sin un administrador activo.
 *  - Un administrador no puede desactivarse ni eliminarse a sí mismo.
 */
@Service
public class CajeroService {

    public static final int LONGITUD_MINIMA_CONTRASENA = 4;

    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private PasswordEncoder  passwordEncoder;
    @Autowired private SesionService    sesionService;

    // ── Consultas ─────────────────────────────────────────────────────────

    public List<Cajero> listar() {
        sesionService.verificarAdmin();
        return cajeroRepo.findAllByOrderByUsuarioAsc();
    }

    // ── Altas / cambios ───────────────────────────────────────────────────

    @Transactional
    public Cajero crear(String usuario, String nombreCompleto,
                        String contrasena, Cajero.Rol rol) {
        sesionService.verificarAdmin();

        usuario = normalizarUsuario(usuario);
        nombreCompleto = validarNombre(nombreCompleto);
        validarContrasena(contrasena);
        if (rol == null) throw new NegocioException("Selecciona el rol del usuario.");

        if (cajeroRepo.findByUsuarioIgnoreCase(usuario).isPresent()) {
            throw new NegocioException("Ya existe un usuario llamado \"" + usuario + "\".");
        }

        Cajero c = new Cajero();
        c.setUsuario(usuario);
        c.setNombreCompleto(nombreCompleto);
        c.setContrasenaHash(passwordEncoder.encode(contrasena));
        c.setRol(rol);
        c.setActivo(true);
        return cajeroRepo.save(c);
    }

    /** Actualiza datos generales sin tocar la contraseña */
    @Transactional
    public Cajero actualizar(Long id, String usuario, String nombreCompleto,
                             Cajero.Rol rol, boolean activo) {
        return actualizar(id, usuario, nombreCompleto, rol, activo, null);
    }

    /**
     * Actualiza datos generales y, si nuevaContrasena no es null/vacía,
     * también la contraseña. Todo en UNA transacción: si la contraseña
     * es inválida no se guarda ningún otro cambio.
     */
    @Transactional
    public Cajero actualizar(Long id, String usuario, String nombreCompleto,
                             Cajero.Rol rol, boolean activo, String nuevaContrasena) {
        sesionService.verificarAdmin();

        Cajero c = obtener(id);
        usuario = normalizarUsuario(usuario);
        nombreCompleto = validarNombre(nombreCompleto);
        if (rol == null) throw new NegocioException("Selecciona el rol del usuario.");

        boolean cambiaContrasena = nuevaContrasena != null && !nuevaContrasena.isEmpty();
        if (cambiaContrasena) validarContrasena(nuevaContrasena);

        cajeroRepo.findByUsuarioIgnoreCase(usuario)
                .filter(otro -> !otro.getId().equals(id))
                .ifPresent(otro -> {
                    throw new NegocioException("Ya existe un usuario llamado \"" + otro.getUsuario() + "\".");
                });

        boolean dejaDeSerAdminActivo = esAdminActivo(c) && !(rol == Cajero.Rol.ADMIN && activo);
        if (dejaDeSerAdminActivo) {
            verificarNoEsElMismo(c, "Un administrador no puede quitarse el rol ni desactivarse a sí mismo.");
            verificarNoEsUltimoAdmin();
        }

        c.setUsuario(usuario);
        c.setNombreCompleto(nombreCompleto);
        c.setRol(rol);
        c.setActivo(activo);
        if (cambiaContrasena) {
            c.setContrasenaHash(passwordEncoder.encode(nuevaContrasena));
        }
        return cajeroRepo.save(c);
    }

    @Transactional
    public void cambiarContrasena(Long id, String nuevaContrasena) {
        sesionService.verificarAdmin();
        validarContrasena(nuevaContrasena);

        Cajero c = obtener(id);
        c.setContrasenaHash(passwordEncoder.encode(nuevaContrasena));
        cajeroRepo.save(c);
    }

    @Transactional
    public Cajero cambiarEstado(Long id, boolean activo) {
        sesionService.verificarAdmin();

        Cajero c = obtener(id);
        if (!activo && esAdminActivo(c)) {
            verificarNoEsElMismo(c, "No puedes desactivar tu propio usuario.");
            verificarNoEsUltimoAdmin();
        }
        c.setActivo(activo);
        return cajeroRepo.save(c);
    }

    @Transactional
    public void eliminar(Long id) {
        sesionService.verificarAdmin();

        Cajero c = obtener(id);
        verificarNoEsElMismo(c, "No puedes eliminar tu propio usuario.");
        if (esAdminActivo(c)) {
            verificarNoEsUltimoAdmin();
        }
        cajeroRepo.delete(c);
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private Cajero obtener(Long id) {
        return cajeroRepo.findById(id)
                .orElseThrow(() -> new NegocioException("El usuario ya no existe."));
    }

    private boolean esAdminActivo(Cajero c) {
        return c.getRol() == Cajero.Rol.ADMIN && c.isActivo();
    }

    private void verificarNoEsElMismo(Cajero c, String mensaje) {
        Cajero actual = sesionService.getCajeroActual();
        if (actual != null && actual.getId().equals(c.getId())) {
            throw new NegocioException(mensaje);
        }
    }

    private void verificarNoEsUltimoAdmin() {
        if (cajeroRepo.countByRolAndActivoTrue(Cajero.Rol.ADMIN) <= 1) {
            throw new NegocioException(
                    "Debe existir al menos un administrador activo en el sistema.");
        }
    }

    private String normalizarUsuario(String usuario) {
        if (usuario == null || usuario.isBlank()) {
            throw new NegocioException("El nombre de usuario es obligatorio.");
        }
        usuario = usuario.trim();
        if (usuario.contains(" ")) {
            throw new NegocioException("El nombre de usuario no debe contener espacios.");
        }
        if (usuario.length() > 60) {
            throw new NegocioException("El nombre de usuario es demasiado largo (máx. 60).");
        }
        return usuario;
    }

    private String validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new NegocioException("El nombre completo es obligatorio.");
        }
        return nombre.trim();
    }

    private void validarContrasena(String contrasena) {
        if (contrasena == null || contrasena.length() < LONGITUD_MINIMA_CONTRASENA) {
            throw new NegocioException("La contraseña debe tener al menos "
                    + LONGITUD_MINIMA_CONTRASENA + " caracteres.");
        }
    }
}
