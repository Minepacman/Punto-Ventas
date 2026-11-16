package me.eliassanchezfernandez.puntodeventa.util;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;

/**
 * Cómo se dibujan TODOS los {@link Icono} del programa (Configuración → Apariencia): paquete de
 * iconos, estilo (contorno, relleno, duotono...), grosor del trazo y tamaño. Es global: al cambiar,
 * {@link #revisionProperty()} avisa y cada icono vivo se vuelve a dibujar (con un escucha débil: los
 * iconos de ventanas cerradas no quedan retenidos). Solo se toca en el hilo de JavaFX.
 */
public final class EstiloIconos {

    private EstiloIconos() { }

    private static volatile String paquete = "lucide";
    private static volatile String estilo = "contorno";
    private static volatile double grosor = 2.0;
    private static volatile double factorTamano = 1.0;
    private static final ReadOnlyIntegerWrapper REVISION = new ReadOnlyIntegerWrapper(0);

    public static String paquete() { return paquete; }
    public static String estilo() { return estilo; }
    /** Ancho del trazo en el viewBox de 24 (los paquetes de trazo fino lo escalan, ver PaquetesIconos) */
    public static double grosor() { return grosor; }
    /** 1 = normal; 1.25 = iconos grandes */
    public static double factorTamano() { return factorTamano; }

    public static ReadOnlyIntegerProperty revisionProperty() { return REVISION.getReadOnlyProperty(); }

    /** Cambia el estilo; si algo cambió, los iconos visibles se redibujan */
    public static void configurar(String nuevoPaquete, String nuevoEstilo, double nuevoGrosor, double nuevoFactor) {
        String p = nuevoPaquete == null ? "lucide" : nuevoPaquete;
        String e = nuevoEstilo == null ? "contorno" : nuevoEstilo;
        if (p.equals(paquete) && e.equals(estilo) && nuevoGrosor == grosor && nuevoFactor == factorTamano) return;
        paquete = p;
        estilo = e;
        grosor = nuevoGrosor;
        factorTamano = nuevoFactor;
        REVISION.set(REVISION.get() + 1);
    }
}
