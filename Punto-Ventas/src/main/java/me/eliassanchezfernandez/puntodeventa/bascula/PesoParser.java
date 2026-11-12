package me.eliassanchezfernandez.puntodeventa.bascula;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae el peso de lo que manda la báscula. Funciona con texto, así que se
 * prueba con tramas de ejemplo sin necesitar un puerto serie.
 *
 * Las tramas terminan en CR/LF (o van entre STX y ETX). Ejemplos reales:
 *   "  0.500 kg\r\n"        (Torrey)        "ST,GS,   0.500kg\r\n"   (CAS)
 *   "\u0002  1.250\u0003"    (STX/ETX)       "W: 2,50\r\n"            (coma decimal)
 */
public final class PesoParser {

    private static final Pattern SEPARADOR_TRAMAS = Pattern.compile("[\\r\\n\\u0002\\u0003]+");
    private static final int PESO_MAXIMO_KG = 10_000;

    private PesoParser() { }

    /** Peso de UNA trama con la expresión (grupo 1 si existe, si no toda la coincidencia). Vacío si no hay un peso válido. */
    public static Optional<Double> extraer(String trama, Pattern regex) {
        if (trama == null || trama.isBlank()) return Optional.empty();
        Matcher m = regex.matcher(trama);
        if (!m.find()) return Optional.empty();
        String texto = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
        return numero(texto);
    }

    /**
     * Peso más reciente de un bloque de bytes ya leídos: parte en tramas, descarta
     * la última si quedó cortada a la mitad (no terminó en salto de línea) y toma
     * la trama válida más reciente.
     */
    public static Optional<Double> ultimoPeso(String bloque, Pattern regex) {
        if (bloque == null || bloque.isEmpty()) return Optional.empty();
        List<String> tramas = new ArrayList<>(List.of(SEPARADOR_TRAMAS.split(bloque)));
        tramas.removeIf(String::isBlank);
        boolean terminaCompleto = SEPARADOR_TRAMAS.matcher(bloque.substring(bloque.length() - 1)).matches();
        if (!terminaCompleto && tramas.size() > 1) tramas.remove(tramas.size() - 1);
        for (int i = tramas.size() - 1; i >= 0; i--) {
            Optional<Double> peso = extraer(tramas.get(i), regex);
            if (peso.isPresent()) return peso;
        }
        return Optional.empty();
    }

    /** ¿El bloque ya trae al menos una trama completa (terminada en salto de línea / ETX) con peso? */
    public static boolean hayTramaCompleta(String bloque, Pattern regex) {
        if (bloque == null || bloque.isEmpty()) return false;
        char ultimo = bloque.charAt(bloque.length() - 1);
        if (ultimo != '\n' && ultimo != '\r' && ultimo != 3) return false;
        return ultimoPeso(bloque, regex).isPresent();
    }

    /**
     * "1.250", "1,250" y "1.250,5" → número. Con un solo separador se toma como
     * decimal (en una báscula "1,250" es 1.250 kg, no mil). Rechaza lo negativo,
     * lo que no es número y lo absurdo (> 10 000).
     */
    static Optional<Double> numero(String texto) {
        if (texto == null) return Optional.empty();
        String t = texto.trim().replace(" ", "");
        if (t.isEmpty() || !t.matches("[0-9.,]+")) return Optional.empty();
        int punto = t.lastIndexOf('.'), coma = t.lastIndexOf(',');
        if (punto >= 0 && coma >= 0) {
            // El último separador es el decimal; el otro es de miles
            char decimal = punto > coma ? '.' : ',';
            char miles = decimal == '.' ? ',' : '.';
            t = t.replace(String.valueOf(miles), "").replace(decimal, '.');
        } else {
            t = t.replace(',', '.');
        }
        try {
            double v = Double.parseDouble(t);
            if (!Double.isFinite(v) || v < 0 || v > PESO_MAXIMO_KG) return Optional.empty();
            return Optional.of(Math.round(v * 1000.0) / 1000.0);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
