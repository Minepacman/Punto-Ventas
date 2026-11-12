package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;

/**
 * Usuario que ACTÚA en el hilo actual cuando no es el de la sesión de escritorio
 * (hoy: un usuario del acceso móvil). Se guarda en un ThreadLocal que solo
 * se llena con {@link SesionService#ejecutarComo} y siempre se limpia en un finally.
 *
 * Vive en su propia clase (y no dentro de SesionService) para que AuditoriaService
 * pueda consultarlo sin depender de SesionService (evita el ciclo de beans).
 */
public final class ContextoActor {

    /** Quién actúa y desde dónde (IP del teléfono) */
    public record Actor(Cajero cajero, String ip) { }

    private static final ThreadLocal<Actor> ACTUAL = new ThreadLocal<>();

    private ContextoActor() { }

    /** Actor del hilo actual, o null si el hilo trabaja con la sesión de escritorio */
    public static Actor actual() {
        return ACTUAL.get();
    }

    static Actor fijar(Actor nuevo) {
        Actor anterior = ACTUAL.get();
        ACTUAL.set(nuevo);
        return anterior;
    }

    static void restaurar(Actor anterior) {
        if (anterior == null) ACTUAL.remove();
        else ACTUAL.set(anterior);
    }

    /** Texto que se agrega a la bitácora de las acciones hechas desde el móvil */
    static String sufijoAuditoria() {
        Actor a = ACTUAL.get();
        return a == null ? "" : " [vía móvil, IP " + (a.ip() == null ? "?" : a.ip()) + "]";
    }
}
