package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Datos de la cuenta del DESARROLLADOR para donaciones (no son del negocio
 * y por eso no se editan desde el programa). Viven en un solo lugar:
 * {@code src/main/resources/donacion.properties} (claves {@code clabe},
 * {@code banco}, {@code beneficiario}).
 *
 * La CLABE (Clave Bancaria Estandarizada) tiene 18 dígitos: 3 del banco,
 * 3 de la plaza, 11 de la cuenta y 1 verificador. El verificador se calcula
 * con los pesos 3, 7, 1 repetidos sobre los primeros 17 dígitos:
 * suma de (dígito × peso) mod 10, y verificador = (10 − suma mod 10) mod 10.
 * Una CLABE cuyo banco es "000" no existe: así se detecta el valor de
 * ejemplo que queda mientras el dueño no entrega la suya.
 */
public final class Donacion {

    public static final String RECURSO = "/donacion.properties";
    public static final int LARGO_CLABE = 18;
    /** Segundos que la ventana de donación no se puede cerrar */
    public static final int SEGUNDOS_BLOQUEO = 40;

    private static final int[] PESOS = {3, 7, 1};

    private Donacion() { }

    /** Lo que muestra la ventana de donación */
    public record Datos(String clabe, String banco, String beneficiario) {
        /** true solo si la CLABE del recurso es válida (largo, dígitos, verificador y banco real) */
        public boolean clabeConfigurada() {
            return clabeValida(clabe);
        }
    }

    /** Lee donacion.properties; si falta o está incompleto regresa valores pendientes (nunca lanza) */
    public static Datos cargar() {
        Properties p = new Properties();
        try (InputStream in = Donacion.class.getResourceAsStream(RECURSO)) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            // se usan los valores pendientes de abajo
        }
        return desde(p);
    }

    static Datos desde(Properties p) {
        return new Datos(limpiarClabe(p.getProperty("clabe", "")),
                valor(p, "banco"), valor(p, "beneficiario"));
    }

    private static String valor(Properties p, String clave) {
        String v = p.getProperty(clave, "");
        return v == null || v.isBlank() ? "PENDIENTE" : v.trim();
    }

    /** Quita espacios y guiones: "032 180 ..." → "032180..." */
    public static String limpiarClabe(String clabe) {
        return clabe == null ? "" : clabe.replaceAll("[\\s-]", "");
    }

    /** 18 dígitos, verificador correcto y banco distinto de 000 */
    public static boolean clabeValida(String clabe) {
        String c = limpiarClabe(clabe);
        if (c.length() != LARGO_CLABE || !c.chars().allMatch(Character::isDigit)) return false;
        if (c.startsWith("000")) return false;
        return digitoVerificador(c.substring(0, 17)) == c.charAt(17) - '0';
    }

    /** Dígito verificador de los primeros 17 dígitos (pesos 3, 7, 1) */
    public static int digitoVerificador(String primeros17) {
        if (primeros17 == null || primeros17.length() != 17 || !primeros17.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Se necesitan exactamente 17 dígitos.");
        }
        int suma = 0;
        for (int i = 0; i < 17; i++) {
            suma += ((primeros17.charAt(i) - '0') * PESOS[i % 3]) % 10;
        }
        return (10 - suma % 10) % 10;
    }

    /** "032 180 00011835971 9" (banco, plaza, cuenta, verificador) para leerla y dictarla sin errores */
    public static String formatear(String clabe) {
        String c = limpiarClabe(clabe);
        if (c.length() != LARGO_CLABE) return c;
        return c.substring(0, 3) + " " + c.substring(3, 6) + " " + c.substring(6, 17) + " " + c.substring(17);
    }
}
