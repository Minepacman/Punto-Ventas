package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import java.util.Locale;

/**
 * Color sRGB opaco (0..255 por canal) con las cuentas que necesita la apariencia: mezclar,
 * aclarar/oscurecer, luminancia relativa y contraste WCAG 2.x. Sin JavaFX, así se prueba solo
 * y lo usa también el servidor de la página móvil.
 */
public record Rgb(int r, int g, int b) {

    public static final Rgb BLANCO = new Rgb(255, 255, 255);
    public static final Rgb NEGRO = new Rgb(0, 0, 0);

    public Rgb {
        r = limitar(r);
        g = limitar(g);
        b = limitar(b);
    }

    private static int limitar(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** "#RGB", "#RRGGBB" o "RRGGBB" (sin distinguir mayúsculas); null si no es válido */
    public static Rgb deHex(String texto) {
        if (texto == null) return null;
        String t = texto.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6 || !t.matches("[0-9a-fA-F]{6}")) return null;
        int v = Integer.parseInt(t, 16);
        return new Rgb((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
    }

    public String hex() {
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    /** rgba(r, g, b, a) para CSS (JavaFX y web) */
    public String rgba(double alfa) {
        return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.2f)", r, g, b, Math.max(0, Math.min(1, alfa)));
    }

    /** Mezcla: t = 0 → este color, t = 1 → otro */
    public Rgb mezclar(Rgb otro, double t) {
        return new Rgb(
                (int) Math.round(r + (otro.r - r) * t),
                (int) Math.round(g + (otro.g - g) * t),
                (int) Math.round(b + (otro.b - b) * t));
    }

    public Rgb aclarar(double t) { return mezclar(BLANCO, t); }

    public Rgb oscurecer(double t) { return mezclar(NEGRO, t); }

    /** Luminancia relativa WCAG (0 = negro, 1 = blanco) */
    public double luminancia() {
        return 0.2126 * lineal(r) + 0.7152 * lineal(g) + 0.0722 * lineal(b);
    }

    private static double lineal(int canal) {
        double c = canal / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Razón de contraste WCAG entre dos colores (1..21) */
    public double contraste(Rgb otro) {
        double a = luminancia(), b2 = otro.luminancia();
        double claro = Math.max(a, b2), oscuro = Math.min(a, b2);
        return (claro + 0.05) / (oscuro + 0.05);
    }

    /** Blanco o negro, el que mejor se lee sobre este color (prefiere blanco si llega a AA 4.5:1) */
    public Rgb textoEncima() {
        double conBlanco = contraste(BLANCO);
        if (conBlanco >= 4.5) return BLANCO;
        return conBlanco >= contraste(NEGRO) ? BLANCO : NEGRO;
    }

    /**
     * El color más parecido a este que tenga al menos {@code minimo} de contraste contra {@code fondo}:
     * se oscurece (fondo claro) o aclara (fondo oscuro) poco a poco. Si ni así alcanza devuelve blanco o negro.
     */
    public Rgb conContrasteMinimo(Rgb fondo, double minimo) {
        if (contraste(fondo) >= minimo) return this;
        boolean fondoClaro = fondo.luminancia() > 0.18;
        for (int i = 1; i <= 40; i++) {
            Rgb c = fondoClaro ? oscurecer(i / 40.0) : aclarar(i / 40.0);
            if (c.contraste(fondo) >= minimo) return c;
        }
        return fondoClaro ? NEGRO : BLANCO;
    }

    /** Tono, saturación y luminosidad (HSL), cada uno 0..1 */
    public double[] hsl() {
        double rr = r / 255.0, gg = g / 255.0, bb = b / 255.0;
        double max = Math.max(rr, Math.max(gg, bb)), min = Math.min(rr, Math.min(gg, bb));
        double l = (max + min) / 2, h = 0, s = 0;
        if (max != min) {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == rr) h = (gg - bb) / d + (gg < bb ? 6 : 0);
            else if (max == gg) h = (bb - rr) / d + 2;
            else h = (rr - gg) / d + 4;
            h /= 6;
        }
        return new double[]{h, s, l};
    }

    public static Rgb deHsl(double h, double s, double l) {
        h = ((h % 1) + 1) % 1;
        s = Math.max(0, Math.min(1, s));
        l = Math.max(0, Math.min(1, l));
        if (s == 0) {
            int v = (int) Math.round(l * 255);
            return new Rgb(v, v, v);
        }
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        return new Rgb(
                (int) Math.round(tono(p, q, h + 1.0 / 3) * 255),
                (int) Math.round(tono(p, q, h) * 255),
                (int) Math.round(tono(p, q, h - 1.0 / 3) * 255));
    }

    private static double tono(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 1.0 / 2) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }
}
