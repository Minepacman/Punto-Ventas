package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Utilidades de texto para leer tickets de proveedor: quitar acentos, normalizar,
 * convertir números con errores típicos del OCR y medir parecido entre palabras.
 * Todo puro (sin Spring ni base de datos).
 */
public final class TextoTicket {

    private TextoTicket() { }

    /** Sin acentos ni diéresis (la Ñ se conserva como N para comparar) */
    public static String sinAcentos(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "");
    }

    /** Mayúsculas, sin acentos y con espacios colapsados */
    public static String normal(String s) {
        return sinAcentos(s).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Clave para recordar una descripción: solo letras y números, sin espacios ni signos */
    public static String clave(String s) {
        return normal(s).replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Ficha que parece número pero trae letras confundidas por el OCR (O→0, l/I→1, S→5, B→8).
     * Solo convierte cuando la mayoría de los caracteres ya son cifras o signos de número;
     * una S al inicio seguida de cifras con punto se toma como el signo de pesos.
     * @return la ficha corregida, o la misma si no parece número
     */
    public static String corregirNumero(String t) {
        if (t == null || t.isEmpty()) return t;
        int digitos = 0, numericos = 0, confundibles = 0;
        for (char c : t.toCharArray()) {
            if (Character.isDigit(c)) { digitos++; numericos++; }
            else if (c == '.' || c == ',' || c == '$' || c == '-' || c == '/' || c == ':') numericos++;
            else if ("OoIlSB|".indexOf(c) >= 0) confundibles++;
        }
        if (digitos == 0 || confundibles == 0) return t;
        if (numericos + confundibles != t.length()) return t;      // trae otras letras: es palabra
        // Forma de dinero (dd.dd) o casi todo cifras: se corrige; si casi todo son letras, mejor no tocar
        boolean formaDinero = t.matches("[$S]?[\\dOoIlSB|]{1,6}[.,][\\dOoIlSB|]{2}");
        if (!formaDinero && numericos < confundibles * 2 && t.length() > 3) return t;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (i == 0 && c == 'S' && t.length() > 2 && t.indexOf('.') > 0) { sb.append('$'); continue; }
            switch (c) {
                case 'O', 'o' -> sb.append('0');
                case 'I', 'l', '|' -> sb.append('1');
                case 'S' -> sb.append('5');
                case 'B' -> sb.append('8');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Valor de una ficha numérica a la mexicana: "1,220" = 1220, "12,50" = 12.5,
     * "$17.26" = 17.26. Devuelve null si no es número.
     */
    public static Double numero(String t) {
        if (t == null) return null;
        String s = t.trim();
        while (s.startsWith("$")) s = s.substring(1);
        if (s.isEmpty()) return null;
        boolean negativo = s.startsWith("-");
        if (negativo) s = s.substring(1);
        if (!s.matches("[0-9.,]+") || !s.matches(".*[0-9].*")) return null;
        String limpio;
        if (s.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
            limpio = s.replace(",", "");                       // 1,220 / 12,345.50
        } else if (s.matches("\\d+,\\d{1,2}")) {
            limpio = s.replace(',', '.');                      // 12,50
        } else if (s.matches("\\d*\\.\\d+") || s.matches("\\d+\\.?")) {
            limpio = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        } else {
            return null;
        }
        if (limpio.isEmpty()) return null;
        try {
            double v = Double.parseDouble(limpio);
            return negativo ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parecido Jaro-Winkler entre 0 y 1 */
    public static double jaroWinkler(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 1;
        int la = a.length(), lb = b.length();
        if (la == 0 || lb == 0) return 0;
        int rango = Math.max(0, Math.max(la, lb) / 2 - 1);
        boolean[] ma = new boolean[la], mb = new boolean[lb];
        int coinciden = 0;
        for (int i = 0; i < la; i++) {
            int desde = Math.max(0, i - rango), hasta = Math.min(lb - 1, i + rango);
            for (int j = desde; j <= hasta; j++) {
                if (!mb[j] && a.charAt(i) == b.charAt(j)) { ma[i] = true; mb[j] = true; coinciden++; break; }
            }
        }
        if (coinciden == 0) return 0;
        int transp = 0, k = 0;
        for (int i = 0; i < la; i++) {
            if (!ma[i]) continue;
            while (!mb[k]) k++;
            if (a.charAt(i) != b.charAt(k)) transp++;
            k++;
        }
        double m = coinciden;
        double jaro = (m / la + m / lb + (m - transp / 2.0) / m) / 3.0;
        int prefijo = 0;
        for (int i = 0; i < Math.min(4, Math.min(la, lb)); i++) {
            if (a.charAt(i) == b.charAt(i)) prefijo++; else break;
        }
        return jaro + prefijo * 0.1 * (1 - jaro);
    }

    /**
     * ¿Son la misma palabra con tolerancia? Igual, muy parecida (Jaro-Winkler ≥ 0.9) o una es la
     * otra recortada por la orilla de la foto ("LAPA" de "JALAPA", "ERVEZAS" de "CERVEZAS").
     */
    public static boolean mismaPalabra(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() >= 4 && b.length() >= 4 && (a.endsWith(b) || b.endsWith(a))) return true;
        if (a.length() >= 4 && b.length() >= 4 && jaroWinkler(a, b) >= 0.9) return true;
        return false;
    }

    /** Redondea a centavos */
    public static double centavos(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
