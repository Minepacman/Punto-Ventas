package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Autorización puntual de un administrador (USR-19): vale para UN permiso,
 * durante pocos minutos y se CONSUME cuando la acción que la pidió termina
 * con éxito (una cancelación, una entrada, un ajuste, o el cobro del ticket
 * en el que se usó). Una autorización consumida ya no sirve para nada.
 * La emite AutorizacionService después de validar usuario y contraseña.
 */
public class Autorizacion {

    public static final Duration VIGENCIA = Duration.ofMinutes(5);

    private final Cajero administrador;
    private final Permiso permiso;
    private final LocalDateTime momento;
    private boolean consumida;

    public Autorizacion(Cajero administrador, Permiso permiso, LocalDateTime momento) {
        this.administrador = administrador;
        this.permiso = permiso;
        this.momento = momento;
    }

    public Cajero administrador()   { return administrador; }
    public Permiso permiso()        { return permiso; }
    public LocalDateTime momento()  { return momento; }
    public boolean isConsumida()    { return consumida; }

    public boolean cubre(Permiso p) {
        return permiso == p;
    }

    public boolean vigente() {
        return !consumida && momento != null && momento.plus(VIGENCIA).isAfter(LocalDateTime.now());
    }

    /** La acción autorizada ya se realizó: la autorización deja de valer */
    public void consumir() {
        consumida = true;
    }

    public String nombreAdministrador() {
        return administrador != null ? administrador.getNombreCompleto() : null;
    }

    /** Busca en una lista una autorización vigente (no consumida) para el permiso */
    public static Autorizacion buscar(Collection<Autorizacion> autorizaciones, Permiso p) {
        if (autorizaciones == null) return null;
        return autorizaciones.stream()
                .filter(a -> a != null && a.cubre(p) && a.vigente())
                .findFirst().orElse(null);
    }

    /** Consume todas las autorizaciones de la colección (fin del ticket, etc.) */
    public static void consumirTodas(Collection<Autorizacion> autorizaciones) {
        if (autorizaciones == null) return;
        autorizaciones.forEach(a -> { if (a != null) a.consumir(); });
    }
}
