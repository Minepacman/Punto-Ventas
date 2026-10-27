package me.eliassanchezfernandez.puntodeventa.util;

/**
 * Formato único de dinero (CFG-02). El símbolo y el código vienen de la
 * configuración: ConfiguracionService los actualiza aquí cada vez que lee o
 * guarda la configuración, así cualquier pantalla o mensaje de servicio solo
 * llama a {@link #formato(double)}.
 */
public final class Moneda {

    public static final String SIMBOLO_POR_DEFECTO = "$";
    public static final String CODIGO_POR_DEFECTO  = "MXN";

    private static volatile String simbolo = SIMBOLO_POR_DEFECTO;
    private static volatile String codigo  = CODIGO_POR_DEFECTO;

    private Moneda() { }

    public static void configurar(String nuevoSimbolo, String nuevoCodigo) {
        simbolo = nuevoSimbolo == null || nuevoSimbolo.isBlank() ? SIMBOLO_POR_DEFECTO : nuevoSimbolo;
        codigo  = nuevoCodigo  == null || nuevoCodigo.isBlank()  ? CODIGO_POR_DEFECTO  : nuevoCodigo;
    }

    public static String simbolo() { return simbolo; }

    public static String codigo() { return codigo; }

    /** "$1234.50"; los negativos van como "-$5.00" */
    public static String formato(double monto) {
        String cifra = String.format("%.2f", Math.abs(monto));
        return (monto < 0 && !cifra.matches("[0.,]+") ? "-" : "") + simbolo + cifra;
    }
}
