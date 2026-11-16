package me.eliassanchezfernandez.puntodeventa.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Fechas con las que se compara "hoy" en la vista Hoy de Estadísticas.
 * Funciones puras, sin reloj ni base de datos (probadas en FechasComparacionTest).
 */
public final class FechasComparacion {

    private static final Locale ES_MX = new Locale("es", "MX");

    private FechasComparacion() { }

    /** El día anterior */
    public static LocalDate ayer(LocalDate hoy) {
        return hoy.minusDays(1);
    }

    /** El mismo día de la semana pasada (hoy − 7 días; siempre cae en el mismo día de la semana) */
    public static LocalDate mismoDiaSemanaPasada(LocalDate hoy) {
        return hoy.minusWeeks(1);
    }

    /**
     * La misma fecha del año pasado. Un 29 de febrero se compara con el 28 de
     * febrero del año anterior (LocalDate.minusYears ya recorta al último día válido).
     */
    public static LocalDate hoyHaceUnAnio(LocalDate hoy) {
        return hoy.minusYears(1);
    }

    /**
     * ¿La tienda ya vendía ese día? Es decir, ¿el día es igual o posterior al de la
     * primera venta? Con {@code primeraVenta} null (sin ventas) la respuesta es false.
     */
    public static boolean laTiendaYaVendia(LocalDate primeraVenta, LocalDate dia) {
        return primeraVenta != null && !dia.isBefore(primeraVenta);
    }

    /** "ayer", "el jueves pasado", "hoy hace un año" (para la etiqueta de cada fila) */
    public static String etiquetaAyer() {
        return "vs. ayer";
    }

    public static String etiquetaSemanaPasada(LocalDate hoy) {
        return "vs. el " + nombreDia(hoy.getDayOfWeek()) + " pasado";
    }

    public static String etiquetaHaceUnAnio() {
        return "vs. hoy hace un año";
    }

    /** "jueves", "sábado" (minúsculas, como va dentro de una frase) */
    public static String nombreDia(DayOfWeek dia) {
        return dia.getDisplayName(TextStyle.FULL, ES_MX).toLowerCase(ES_MX);
    }
}
