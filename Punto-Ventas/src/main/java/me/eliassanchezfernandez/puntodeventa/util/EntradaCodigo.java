package me.eliassanchezfernandez.puntodeventa.util;

import java.util.Optional;

/**
 * Lo que se teclea en el campo "Código del producto" de Ventas. Además del
 * código solo, acepta la forma de los POS de abarrotes {@code cantidad*código}
 * (por ejemplo {@code 3*7501055300006} o {@code 1.5*PLATANO}): el cajero teclea
 * la cantidad, el asterisco y luego escanea; el lector agrega el código y el
 * Enter. También se admite {@code cantidad x código} ("3x..." / "3 X ...").
 */
public final class EntradaCodigo {

    private EntradaCodigo() { }

    /** Código a buscar y cantidad pedida (1 si no se indicó) */
    public record Captura(String codigo, double cantidad) { }

    /**
     * @return vacío si no hay nada que buscar o la cantidad no es un número
     *         válido mayor a cero
     */
    public static Optional<Captura> interpretar(String texto) {
        if (texto == null) return Optional.empty();
        String t = texto.trim();
        if (t.isEmpty()) return Optional.empty();

        int sep = separador(t);
        if (sep < 0) return Optional.of(new Captura(t, 1));

        String cantidadTxt = t.substring(0, sep).trim().replace(",", ".");
        String codigo = t.substring(sep + 1).trim();
        if (codigo.isEmpty()) return Optional.empty();
        try {
            double cantidad = Double.parseDouble(cantidadTxt);
            if (!Double.isFinite(cantidad) || cantidad <= 0) return Optional.empty();
            return Optional.of(new Captura(codigo, cantidad));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** ¿El texto trae una cantidad por delante ("3*", "2x")? */
    public static boolean traeCantidad(String texto) {
        return texto != null && separador(texto.trim()) >= 0;
    }

    /**
     * Posición del separador cantidad/código: el primer "*" cuando lo precede
     * un número, o una "x"/"X" entre un número y el resto ("3x1234"); -1 si no hay.
     */
    private static int separador(String t) {
        int i = 0;
        while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.' || t.charAt(i) == ',')) i++;
        if (i == 0) return -1;                   // no empieza con número: es un código tal cual
        int j = i;
        while (j < t.length() && t.charAt(j) == ' ') j++;
        if (j >= t.length()) return -1;
        char c = t.charAt(j);
        if (c == '*') return j;
        if ((c == 'x' || c == 'X') && j + 1 < t.length()) return j;
        return -1;
    }
}
