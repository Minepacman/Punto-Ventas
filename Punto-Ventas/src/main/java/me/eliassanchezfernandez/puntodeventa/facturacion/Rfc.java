package me.eliassanchezfernandez.puntodeventa.facturacion;

import java.util.regex.Pattern;

/**
 * Validación del RFC (Registro Federal de Contribuyentes) para facturar.
 *
 *  - Persona física: 4 letras + fecha AAMMDD + homoclave de 3 (13 caracteres).
 *  - Persona moral: 3 letras + fecha AAMMDD + homoclave de 3 (12 caracteres).
 *  - Genéricos: XAXX010101000 (público en general, nacional) y
 *    XEXX010101000 (residente en el extranjero).
 *
 * Solo se revisa la forma (letras, fecha válida y homoclave); el SAT es quien
 * decide si el RFC existe y si coincide con el nombre al timbrar.
 */
public final class Rfc {

    public static final String PUBLICO_EN_GENERAL = "XAXX010101000";
    public static final String EXTRANJERO = "XEXX010101000";

    private static final Pattern FISICA = Pattern.compile("^[A-ZÑ&]{4}\\d{6}[A-Z0-9]{3}$");
    private static final Pattern MORAL  = Pattern.compile("^[A-ZÑ&]{3}\\d{6}[A-Z0-9]{3}$");

    private Rfc() { }

    /** Mayúsculas, sin espacios ni guiones; null si está vacío */
    public static String normalizar(String rfc) {
        if (rfc == null) return null;
        String limpio = rfc.trim().toUpperCase().replace(" ", "").replace("-", "");
        return limpio.isEmpty() ? null : limpio;
    }

    public static boolean esValido(String rfc) {
        String r = normalizar(rfc);
        if (r == null) return false;
        if (esGenerico(r)) return true;
        if (!(FISICA.matcher(r).matches() || MORAL.matcher(r).matches())) return false;
        int inicioFecha = r.length() - 9;
        return fechaValida(r.substring(inicioFecha, inicioFecha + 6));
    }

    public static boolean esGenerico(String rfc) {
        String r = normalizar(rfc);
        return PUBLICO_EN_GENERAL.equals(r) || EXTRANJERO.equals(r);
    }

    public static boolean esPersonaFisica(String rfc) {
        String r = normalizar(rfc);
        return r != null && r.length() == 13 && !esGenerico(r);
    }

    public static boolean esPersonaMoral(String rfc) {
        String r = normalizar(rfc);
        return r != null && r.length() == 12;
    }

    /** Normaliza y valida; lanza IllegalArgumentException con mensaje para el usuario */
    public static String validar(String rfc) {
        String r = normalizar(rfc);
        if (r == null) throw new IllegalArgumentException("El RFC es obligatorio.");
        if (!esValido(r)) {
            throw new IllegalArgumentException("El RFC \"" + r + "\" no tiene un formato válido "
                    + "(persona física: 13 caracteres, moral: 12; ej. GODE561231GR8 o ABC010101AB1).");
        }
        return r;
    }

    /** AAMMDD con mes 01-12 y día válido para el mes (el siglo no importa para la forma) */
    static boolean fechaValida(String aammdd) {
        int mes = Integer.parseInt(aammdd.substring(2, 4));
        int dia = Integer.parseInt(aammdd.substring(4, 6));
        if (mes < 1 || mes > 12 || dia < 1) return false;
        int[] dias = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return dia <= dias[mes - 1];
    }
}
