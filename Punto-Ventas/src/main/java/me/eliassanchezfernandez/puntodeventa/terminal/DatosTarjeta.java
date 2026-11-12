package me.eliassanchezfernandez.puntodeventa.terminal;

import me.eliassanchezfernandez.puntodeventa.service.NegocioException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Datos del voucher de un pago con tarjeta: terminal que cobró, últimos 4
 * dígitos y número de autorización. NUNCA se guarda el número completo de la
 * tarjeta ni el NIP ni el CVV.
 */
public record DatosTarjeta(String terminal, String ultimos4, String autorizacion) {

    private static final Pattern ULTIMOS4 = Pattern.compile("^\\d{4}$");
    private static final Pattern AUTORIZACION = Pattern.compile("^[A-Z0-9]{4,12}$");
    public static final int MAX_LARGO_TERMINAL = 60;

    /** ¿Se capturó alguno de los tres datos? (para saber si hay algo que validar) */
    public static boolean hayAlgo(String terminal, String ultimos4, String autorizacion) {
        return !vacio(terminal) || !vacio(ultimos4) || !vacio(autorizacion);
    }

    /**
     * Valida y normaliza los tres datos. {@code terminalesPermitidas} es la
     * lista configurada; si no está vacía, la terminal debe ser una de ellas
     * (se devuelve con el nombre exacto de la lista).
     * @throws NegocioException con un mensaje claro de qué dato falla
     */
    public static DatosTarjeta validar(String terminal, String ultimos4, String autorizacion,
                                       List<String> terminalesPermitidas) {
        if (vacio(terminal)) throw new NegocioException("Elige la terminal bancaria con la que se cobró.");
        String t = terminal.trim();
        if (terminalesPermitidas != null && !terminalesPermitidas.isEmpty()) {
            t = terminalesPermitidas.stream().filter(x -> x.equalsIgnoreCase(terminal.trim())).findFirst()
                    .orElseThrow(() -> new NegocioException("La terminal \"" + terminal.trim()
                            + "\" no está en la lista de Configuración → Terminal bancaria."));
        }
        if (t.length() > MAX_LARGO_TERMINAL) {
            throw new NegocioException("El nombre de la terminal no puede pasar de " + MAX_LARGO_TERMINAL + " caracteres.");
        }
        String u = vacio(ultimos4) ? "" : ultimos4.trim().replace(" ", "");
        if (!ULTIMOS4.matcher(u).matches()) {
            throw new NegocioException("Escribe los últimos 4 dígitos de la tarjeta (solo números).");
        }
        String a = vacio(autorizacion) ? "" : autorizacion.trim().replace(" ", "").toUpperCase();
        if (!AUTORIZACION.matcher(a).matches()) {
            throw new NegocioException("El número de autorización debe tener de 4 a 12 letras o números.");
        }
        return new DatosTarjeta(t, u, a);
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
