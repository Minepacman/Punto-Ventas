package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Administración de usuarios del sistema (USR-09).
 *
 *  - ADMIN:   ve, crea, modifica, cambia contraseña, activa/desactiva,
 *             desbloquea, elimina y asigna permisos.
 *  - GERENTE: ve la lista y puede activar/desactivar usuarios (no admins),
 *             pero NO crea, modifica ni elimina.
 *  - CAJERO:  nada.
 *
 * Reglas:
 *  - El nombre de usuario es único (sin distinguir mayúsculas).
 *  - Nunca puede quedar el sistema sin un administrador activo.
 *  - Un administrador no puede desactivarse ni eliminarse a sí mismo.
 *  - Las contraseñas cumplen PoliticaContrasena; los usuarios nuevos y las
 *    contraseñas restablecidas por el admin obligan a cambiarla al entrar.
 */
@Service
public class CajeroService {

    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.VentaRepository ventaRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.CorteRepository corteRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.FondoCajaRepository fondoRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository movimientoRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository auditoriaRepo;
    @Autowired private PasswordEncoder  passwordEncoder;
    @Autowired private SesionService    sesionService;
    @Autowired private AuditoriaService auditoriaService;

    // ── Consultas ─────────────────────────────────────────────────────────

    public List<Cajero> listar() {
        verificarAdminOGerente();
        return cajeroRepo.findAllByOrderByUsuarioAsc();
    }

    // ── Altas / cambios ───────────────────────────────────────────────────

    @Transactional
    public Cajero crear(String usuario, String nombreCompleto,
                        String contrasena, Cajero.Rol rol) {
        return crear(usuario, nombreCompleto, contrasena, rol, null);
    }

    /**
     * Crea un usuario. permisosEfectivos = los permisos que debe tener
     * (null = solo los del rol); se guarda la diferencia contra el rol.
     */
    @Transactional
    public Cajero crear(String usuario, String nombreCompleto, String contrasena,
                        Cajero.Rol rol, Set<Permiso> permisosEfectivos) {
        sesionService.verificarAdmin();

        usuario = normalizarUsuario(usuario);
        nombreCompleto = validarNombre(nombreCompleto);
        PoliticaContrasena.validar(contrasena);
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
        c.setDebeCambiarContrasena(true);
        aplicarPermisos(c, permisosEfectivos);
        Cajero guardado = cajeroRepo.save(c);

        auditoriaService.registrar(sesionService.getCajeroActual(), "USUARIO_CREADO",
                guardado.getUsuario() + " (" + rol + ")", (Cajero) null);
        return guardado;
    }

    /** Actualiza datos generales sin tocar la contraseña ni los permisos */
    @Transactional
    public Cajero actualizar(Long id, String usuario, String nombreCompleto,
                             Cajero.Rol rol, boolean activo) {
        return actualizar(id, usuario, nombreCompleto, rol, activo, null, null);
    }

    @Transactional
    public Cajero actualizar(Long id, String usuario, String nombreCompleto,
                             Cajero.Rol rol, boolean activo, String nuevaContrasena) {
        return actualizar(id, usuario, nombreCompleto, rol, activo, nuevaContrasena, null);
    }

    /**
     * Actualiza datos generales, permisos y, si nuevaContrasena no es
     * null/vacía, también la contraseña. Todo en UNA transacción: si algo
     * es inválido no se guarda ningún cambio.
     *
     * @param permisosEfectivos permisos que debe tener el usuario; null = no tocar
     */
    @Transactional
    public Cajero actualizar(Long id, String usuario, String nombreCompleto,
                             Cajero.Rol rol, boolean activo, String nuevaContrasena,
                             Set<Permiso> permisosEfectivos) {
        sesionService.verificarAdmin();

        Cajero c = obtener(id);
        usuario = normalizarUsuario(usuario);
        nombreCompleto = validarNombre(nombreCompleto);
        if (rol == null) throw new NegocioException("Selecciona el rol del usuario.");

        boolean cambiaContrasena = nuevaContrasena != null && !nuevaContrasena.isEmpty();
        if (cambiaContrasena) PoliticaContrasena.validar(nuevaContrasena);

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

        String cambios = "rol " + c.getRol() + "→" + rol + ", activo " + c.isActivo() + "→" + activo;
        c.setUsuario(usuario);
        c.setNombreCompleto(nombreCompleto);
        c.setRol(rol);
        c.setActivo(activo);
        if (cambiaContrasena) {
            c.setContrasenaHash(passwordEncoder.encode(nuevaContrasena));
            c.setDebeCambiarContrasena(true);   // la definió el admin: debe cambiarla al entrar
            cambios += ", contraseña restablecida";
        }
        if (permisosEfectivos != null) {
            aplicarPermisos(c, permisosEfectivos);
            cambios += ", permisos " + c.getPermisosEfectivos();
        }
        Cajero guardado = cajeroRepo.save(c);

        auditoriaService.registrar(sesionService.getCajeroActual(), "USUARIO_MODIFICADO",
                guardado.getUsuario() + ": " + cambios, (Cajero) null);
        refrescarSiEsElMismo(guardado);
        return guardado;
    }

    @Transactional
    public void cambiarContrasena(Long id, String nuevaContrasena) {
        sesionService.verificarAdmin();
        PoliticaContrasena.validar(nuevaContrasena);

        Cajero c = obtener(id);
        c.setContrasenaHash(passwordEncoder.encode(nuevaContrasena));
        c.setDebeCambiarContrasena(true);
        cajeroRepo.save(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "CONTRASENA_RESTABLECIDA",
                c.getUsuario(), (Cajero) null);
    }

    /**
     * Desactivar: ADMIN, o GERENTE sobre cajeros/gerentes (nunca sobre un
     * ADMIN ni sobre sí mismo). Reactivar: solo ADMIN.
     */
    @Transactional
    public Cajero cambiarEstado(Long id, boolean activo) {
        if (activo) {
            sesionService.verificarAdmin();
        } else {
            verificarAdminOGerente();
        }

        Cajero c = obtener(id);
        if (sesionService.esGerente()) {
            if (c.getRol() == Cajero.Rol.ADMIN) {
                throw new NegocioException("Un gerente no puede desactivar a un administrador.");
            }
            verificarNoEsElMismo(c, "No puedes desactivar tu propio usuario.");
        }
        if (!activo && esAdminActivo(c)) {
            verificarNoEsElMismo(c, "No puedes desactivar tu propio usuario.");
            verificarNoEsUltimoAdmin();
        }
        c.setActivo(activo);
        if (activo) {
            // Reactivar también desbloquea y reinicia el conteo de intentos
            c.setBloqueadoHasta(null);
            c.setIntentosFallidos(0);
        }
        Cajero guardado = cajeroRepo.save(c);
        auditoriaService.registrar(sesionService.getCajeroActual(),
                activo ? "USUARIO_ACTIVADO" : "USUARIO_DESACTIVADO", c.getUsuario(), (Cajero) null);
        return guardado;
    }

    /** Quita antes de tiempo el bloqueo por intentos fallidos (USR-04). Solo ADMIN. */
    @Transactional
    public Cajero desbloquear(Long id) {
        sesionService.verificarAdmin();
        Cajero c = obtener(id);
        c.setBloqueadoHasta(null);
        c.setIntentosFallidos(0);
        Cajero guardado = cajeroRepo.save(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "USUARIO_DESBLOQUEADO", c.getUsuario(), (Cajero) null);
        return guardado;
    }

    @Transactional
    public void eliminar(Long id) {
        sesionService.verificarAdmin();

        Cajero c = obtener(id);
        verificarNoEsElMismo(c, "No puedes eliminar tu propio usuario.");
        if (esAdminActivo(c)) {
            verificarNoEsUltimoAdmin();
        }
        // Un usuario con historial no se borra: ventas, cortes, fondos, movimientos y
        // bitácora lo referencian (SQLite no valida llaves foráneas). Se desactiva.
        if (tieneHistorial(c.getId())) {
            throw new NegocioException("No se puede eliminar a \"" + c.getUsuario()
                    + "\" porque tiene ventas, cortes, fondos o movimientos registrados. Desactívalo en su lugar.");
        }
        cajeroRepo.delete(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "USUARIO_ELIMINADO", c.getUsuario(), (Cajero) null);
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    /** ¿Alguna tabla del negocio referencia a este usuario? */
    public boolean tieneHistorial(Long cajeroId) {
        return ventaRepo.existsByCajeroId(cajeroId)
            || corteRepo.existsByCajeroId(cajeroId)
            || fondoRepo.existsByCajeroId(cajeroId)
            || movimientoRepo.existsByUsuarioId(cajeroId)
            || auditoriaRepo.existsByUsuarioIdOrAutorizadoPorId(cajeroId, cajeroId);
    }

    private void verificarAdminOGerente() {
        if (!sesionService.esAdmin() && !sesionService.esGerente()) {
            throw new NegocioException("Solo un administrador o gerente puede administrar usuarios.");
        }
    }

    /**
     * Guarda en el usuario la diferencia entre los permisos deseados y los
     * del rol: concedidos = deseados − rol; revocados = rol − deseados.
     */
    private void aplicarPermisos(Cajero c, Set<Permiso> deseados) {
        if (deseados == null) return;
        Set<Permiso> delRol = Permiso.porDefecto(c.getRol());
        Set<Permiso> concedidos = EnumSet.noneOf(Permiso.class);
        Set<Permiso> revocados  = EnumSet.noneOf(Permiso.class);
        for (Permiso p : Permiso.values()) {
            boolean quiere = deseados.contains(p);
            boolean trae   = delRol.contains(p);
            if (quiere && !trae)  concedidos.add(p);
            if (!quiere && trae)  revocados.add(p);
        }
        c.setPermisosConcedidos(new HashSet<>(concedidos));
        c.setPermisosRevocados(new HashSet<>(revocados));
    }

    private void refrescarSiEsElMismo(Cajero guardado) {
        Cajero actual = sesionService.getCajeroActual();
        if (actual != null && actual.getId().equals(guardado.getId())) {
            sesionService.refrescarSesion();
        }
    }

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
}
