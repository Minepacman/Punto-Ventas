package me.eliassanchezfernandez.puntodeventa.importacion;

import java.text.Normalizer;
import java.util.Locale;

/** Normalización de encabezados y valores para compararlos sin acentos, mayúsculas ni signos */
public final class Texto {

    private Texto() { }

    /** "Precio de Venta ($)" → "preciodeventa"; "Código" → "codigo"; null → "" */
    public static String clave(String s) {
        if (s == null) return "";
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return sinAcentos.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /** Como {@link #clave} pero conserva un espacio entre palabras: "precio venta" */
    public static String palabras(String s) {
        if (s == null) return "";
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return sinAcentos.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    public static String limpiar(String s) {
        if (s == null) return "";
        return s.replace(' ', ' ').trim();
    }

    public static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    public static String recortar(String s, int maximo) {
        if (s == null) return null;
        return s.length() <= maximo ? s : s.substring(0, maximo);
    }
}
