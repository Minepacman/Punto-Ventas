package me.eliassanchezfernandez.puntodeventa.util;

import java.util.Optional;

/**
 * Códigos de barras GTIN (EAN-8, UPC-A, EAN-13) tal como los normaliza el
 * catálogo {@code codigos-barras-mx}: solo dígitos; UPC-A (12) se escribe
 * como EAN-13 anteponiendo un 0; EAN-8 se deja en 8; un GTIN-14 que empieza
 * con 0 (relleno) se reduce a 13. Se comprueba el dígito de control, así
 * los códigos internos de la tienda ("PRE-0001", "T-1234") y los mal
 * tecleados no generan consultas.
 *
 * Todo es puro y estático: se prueba sin base de datos ni red.
 */
public final class CodigoBarras {

    private CodigoBarras() { }

    /**
     * Código normalizado (8 o 13 dígitos con dígito de control válido) o vacío si
     * el texto no es un GTIN que valga la pena buscar.
     */
    public static Optional<String> normalizar(String texto) {
        if (texto == null) return Optional.empty();
        String limpio = texto.trim();
        if (limpio.isEmpty()) return Optional.empty();
        // Solo espacios, guiones y puntos como separadores; letras u otros símbolos = código interno
        StringBuilder digitos = new StringBuilder(limpio.length());
        for (int i = 0; i < limpio.length(); i++) {
            char c = limpio.charAt(i);
            if (c >= '0' && c <= '9') digitos.append(c);
            else if (c != ' ' && c != '-' && c != '.') return Optional.empty();
        }
        String d = digitos.toString();
        if (d.length() == 14 && d.charAt(0) == '0') d = d.substring(1);
        if (d.length() == 12) d = "0" + d;
        if (d.length() != 8 && d.length() != 13) return Optional.empty();
        if (!digitoDeControlValido(d)) return Optional.empty();
        return Optional.of(d);
    }

    /** Dígito de control GS1 (módulo 10 con pesos 3 y 1 desde la derecha) de un código solo con dígitos */
    public static boolean digitoDeControlValido(String digitos) {
        if (digitos == null || digitos.length() < 8) return false;
        int suma = 0;
        int peso = 3;
        for (int i = digitos.length() - 2; i >= 0; i--) {
            int v = digitos.charAt(i) - '0';
            if (v < 0 || v > 9) return false;
            suma += v * peso;
            peso = peso == 3 ? 1 : 3;
        }
        int esperado = (10 - (suma % 10)) % 10;
        return digitos.charAt(digitos.length() - 1) - '0' == esperado;
    }

    /** Nombre del fragmento de la API (últimos tres dígitos del código normalizado), p. ej. "075" */
    public static String fragmento(String codigoNormalizado) {
        return codigoNormalizado.substring(codigoNormalizado.length() - 3);
    }
}
