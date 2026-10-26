package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Permiso;

/**
 * El usuario en sesión no tiene el permiso necesario. La UI la atrapa para
 * ofrecer la autorización con clave de administrador (USR-19).
 */
public class PermisoRequeridoException extends NegocioException {

    private final Permiso permiso;

    public PermisoRequeridoException(Permiso permiso) {
        super("Se requiere el permiso \"" + permiso.getDescripcion()
                + "\" o la autorización de un administrador.");
        this.permiso = permiso;
    }

    /** Mismo permiso, con un mensaje que explica el caso concreto */
    public PermisoRequeridoException(Permiso permiso, String mensaje) {
        super(mensaje);
        this.permiso = permiso;
    }

    public Permiso getPermiso() {
        return permiso;
    }
}
