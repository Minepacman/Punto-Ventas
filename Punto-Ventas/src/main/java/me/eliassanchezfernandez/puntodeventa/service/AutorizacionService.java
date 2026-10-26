package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Autorización con clave de administrador (USR-19): un usuario sin permiso
 * puede completar UNA acción restringida si un administrador activo captura
 * su usuario y contraseña en ese momento.
 *
 * Las credenciales se validan por la MISMA ruta que el login
 * (SesionService.verificarCredenciales): cuentan intentos fallidos y
 * bloquean temporalmente, así el diálogo no sirve para adivinar la clave.
 */
@Service
public class AutorizacionService {

    @Autowired private SesionService    sesionService;
    @Autowired private AuditoriaService auditoriaService;

    /**
     * @return la autorización si las credenciales corresponden a un
     *         administrador activo, con contraseña ya cambiada y ese permiso
     * @throws NegocioException si no
     */
    public Autorizacion autorizar(String usuarioAdmin, String contrasena, Permiso permiso) {
        Cajero solicitante = sesionService.getCajeroActual();
        String login = solicitante != null ? solicitante.getUsuario() : "—";
        String capturado = usuarioAdmin == null ? "" : usuarioAdmin.trim();

        if (capturado.isEmpty() || contrasena == null || contrasena.isEmpty()) {
            throw new NegocioException("Captura el usuario y la contraseña del administrador.");
        }
        if (permiso == null) {
            throw new NegocioException("No se indicó qué acción se autoriza.");
        }

        Cajero admin;
        try {
            admin = sesionService.verificarCredenciales(capturado, contrasena);
        } catch (SesionService.CredencialesInvalidasException e) {
            auditoriaService.registrarIndependiente(login, "AUTORIZACION_RECHAZADA",
                    "Permiso " + permiso + " — usuario capturado: " + capturado + " — " + e.getMessage());
            throw new NegocioException(e.getMessage().startsWith("Usuario bloqueado")
                    ? e.getMessage()
                    : "Usuario o contraseña de administrador incorrectos.");
        }

        boolean esAdminValido = admin.getRol() == Cajero.Rol.ADMIN
                && admin.tienePermiso(permiso)
                && !admin.isDebeCambiarContrasena();
        if (!esAdminValido) {
            auditoriaService.registrarIndependiente(login, "AUTORIZACION_RECHAZADA",
                    "Permiso " + permiso + " — " + capturado + " no es administrador habilitado");
            throw new NegocioException(admin.isDebeCambiarContrasena()
                    ? "Ese administrador debe cambiar su contraseña antes de poder autorizar."
                    : "Usuario o contraseña de administrador incorrectos.");
        }

        auditoriaService.registrarIndependiente(login, "AUTORIZACION_CONCEDIDA",
                "Permiso " + permiso + " autorizado por " + admin.getUsuario());
        return new Autorizacion(admin, permiso, LocalDateTime.now());
    }
}
