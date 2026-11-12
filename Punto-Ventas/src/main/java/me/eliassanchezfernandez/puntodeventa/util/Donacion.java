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

    public static final String PENDIENTE = "PENDIENTE";

    /** Lo que muestra la ventana de donación y la sección de soporte */
    public record Datos(String clabe, String banco, String beneficiario, String soporteWhatsapp, String soporteCorreo) {

        /** Compatibilidad: sin datos de soporte */
        public Datos(String clabe, String banco, String beneficiario) {
            this(clabe, banco, beneficiario, PENDIENTE, PENDIENTE);
        }

        /** true solo si la CLABE del recurso es válida (largo, dígitos, verificador y banco real) */
        public boolean clabeConfigurada() {
            return clabeValida(clabe);
        }

        public boolean whatsappConfigurado() { return whatsappValido(soporteWhatsapp); }
        public boolean correoConfigurado()   { return correoValido(soporteCorreo); }
        /** Hay al menos un medio de contacto real */
        public boolean soporteConfigurado()  { return whatsappConfigurado() || correoConfigurado(); }
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
                valor(p, "banco"), valor(p, "beneficiario"),
                valor(p, "soporte.whatsapp"), valor(p, "soporte.correo"));
    }

    private static String valor(Properties p, String clave) {
        String v = p.getProperty(clave, "");
        return v == null || v.isBlank() ? PENDIENTE : v.trim();
    }

    // ── Contacto de soporte ───────────────────────────────────────────────

    /** Solo dígitos del número ("55 1234 5678" → "5512345678") */
    public static String limpiarTelefono(String numero) {
        return numero == null ? "" : numero.replaceAll("\\D", "");
    }

    /** 10 dígitos (México) o entre 11 y 15 con lada de país; nunca el valor PENDIENTE */
    public static boolean whatsappValido(String numero) {
        String d = limpiarTelefono(numero);
        return d.length() >= 10 && d.length() <= 15;
    }

    public static boolean correoValido(String correo) {
        return correo != null && correo.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    /** Número internacional para wa.me: 10 dígitos → se antepone 52 (México); con lada se respeta */
    public static String whatsappInternacional(String numero) {
        String d = limpiarTelefono(numero);
        return d.length() == 10 ? "52" + d : d;
    }

    /** https://wa.me/52XXXXXXXXXX?text=... (el texto es opcional) */
    public static String whatsappUrl(String numero, String mensaje) {
        String url = "https://wa.me/" + whatsappInternacional(numero);
        if (mensaje != null && !mensaje.isBlank()) {
            url += "?text=" + java.net.URLEncoder.encode(mensaje, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        }
        return url;
    }

    /** mailto: con asunto opcional */
    public static String correoUrl(String correo, String asunto) {
        return correoUrl(correo, asunto, null);
    }

    /** mailto: con asunto y cuerpo opcionales (el cuerpo va codificado; los saltos de línea se conservan) */
    public static String correoUrl(String correo, String asunto, String cuerpo) {
        StringBuilder url = new StringBuilder("mailto:" + correo.trim());
        char sep = '?';
        if (asunto != null && !asunto.isBlank()) {
            url.append(sep).append("subject=").append(codificar(asunto));
            sep = '&';
        }
        if (cuerpo != null && !cuerpo.isBlank()) {
            url.append(sep).append("body=").append(codificar(cuerpo));
        }
        return url.toString();
    }

    private static String codificar(String texto) {
        return java.net.URLEncoder.encode(texto, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
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
