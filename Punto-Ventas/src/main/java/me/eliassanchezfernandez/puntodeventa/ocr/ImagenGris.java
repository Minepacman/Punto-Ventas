package me.eliassanchezfernandez.puntodeventa.ocr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Imagen en escala de grises 0..255 (fila por fila) con las operaciones geométricas que necesita el
 * preproceso. Todo con arreglos: sin Graphics2D ni el entorno gráfico de AWT (dentro de una app
 * JavaFX en macOS es fuente de bloqueos, igual que en {@code ProductoImagenService}).
 */
public record ImagenGris(byte[] px, int ancho, int alto) {

    public ImagenGris {
        if (ancho <= 0 || alto <= 0 || px.length != ancho * alto) {
            throw new IllegalArgumentException("Dimensiones inválidas: " + ancho + "×" + alto);
        }
    }

    public static ImagenGris blanca(int ancho, int alto) {
        byte[] p = new byte[ancho * alto];
        java.util.Arrays.fill(p, (byte) 255);
        return new ImagenGris(p, ancho, alto);
    }

    public int get(int x, int y) {
        return px[y * ancho + x] & 0xFF;
    }

    /** Giro en múltiplos de 90° en sentido horario (0, 90, 180, 270) */
    public ImagenGris girar90(int grados) {
        int g = ((grados % 360) + 360) % 360;
        if (g == 0) return this;
        int w = ancho, h = alto;
        boolean intercambia = g == 90 || g == 270;
        int dw = intercambia ? h : w, dh = intercambia ? w : h;
        byte[] d = new byte[dw * dh];
        for (int y = 0; y < dh; y++) {
            int fila = y * dw;
            for (int x = 0; x < dw; x++) {
                int sx, sy;
                if (g == 90) { sx = y; sy = h - 1 - x; }
                else if (g == 180) { sx = w - 1 - x; sy = h - 1 - y; }
                else { sx = w - 1 - y; sy = x; }
                d[fila + x] = px[sy * w + sx];
            }
        }
        return new ImagenGris(d, dw, dh);
    }

    /**
     * Giro por un ángulo pequeño (grados, positivo = horario en la pantalla) alrededor del centro,
     * con interpolación bilineal; lo que queda fuera se rellena de blanco. Mismo tamaño de lienzo.
     */
    public ImagenGris girar(double grados) {
        if (Math.abs(grados) < 0.05) return this;
        double r = Math.toRadians(grados);
        double cos = Math.cos(r), sin = Math.sin(r);
        double cx = (ancho - 1) / 2.0, cy = (alto - 1) / 2.0;
        byte[] d = new byte[ancho * alto];
        for (int y = 0; y < alto; y++) {
            double dy = y - cy;
            for (int x = 0; x < ancho; x++) {
                double dx = x - cx;
                // origen = giro inverso del destino
                double sx = cos * dx + sin * dy + cx;
                double sy = -sin * dx + cos * dy + cy;
                d[y * ancho + x] = (byte) muestrear(sx, sy);
            }
        }
        return new ImagenGris(d, ancho, alto);
    }

    /** Valor bilineal en (x, y); fuera de la imagen = blanco */
    int muestrear(double x, double y) {
        if (x < 0 || y < 0 || x > ancho - 1 || y > alto - 1) return 255;
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, ancho - 1), y1 = Math.min(y0 + 1, alto - 1);
        double fx = x - x0, fy = y - y0;
        double a = get(x0, y0) * (1 - fx) + get(x1, y0) * fx;
        double b = get(x0, y1) * (1 - fx) + get(x1, y1) * fx;
        return (int) Math.round(a * (1 - fy) + b * fy);
    }

    public ImagenGris recortar(int x0, int y0, int w, int h) {
        x0 = Math.max(0, Math.min(x0, ancho - 1));
        y0 = Math.max(0, Math.min(y0, alto - 1));
        w = Math.max(1, Math.min(w, ancho - x0));
        h = Math.max(1, Math.min(h, alto - y0));
        if (x0 == 0 && y0 == 0 && w == ancho && h == alto) return this;
        byte[] d = new byte[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(px, (y0 + y) * ancho + x0, d, y * w, w);
        return new ImagenGris(d, w, h);
    }

    /** Cambio de tamaño: bilineal al ampliar, promedio por áreas al reducir */
    public ImagenGris escalar(double factor) {
        if (Math.abs(factor - 1) < 0.01) return this;
        int dw = Math.max(1, (int) Math.round(ancho * factor));
        int dh = Math.max(1, (int) Math.round(alto * factor));
        byte[] d = new byte[dw * dh];
        if (factor > 1) {
            for (int y = 0; y < dh; y++) {
                double sy = Math.min(alto - 1, Math.max(0, (y + 0.5) / factor - 0.5));
                for (int x = 0; x < dw; x++) {
                    double sx = Math.min(ancho - 1, Math.max(0, (x + 0.5) / factor - 0.5));
                    d[y * dw + x] = (byte) muestrear(sx, sy);
                }
            }
        } else {
            for (int y = 0; y < dh; y++) {
                int ya = (int) (y / factor), yb = Math.max(ya + 1, Math.min(alto, (int) ((y + 1) / factor)));
                for (int x = 0; x < dw; x++) {
                    int xa = (int) (x / factor), xb = Math.max(xa + 1, Math.min(ancho, (int) ((x + 1) / factor)));
                    long suma = 0;
                    int n = 0;
                    for (int yy = ya; yy < yb && yy < alto; yy++) {
                        for (int xx = xa; xx < xb && xx < ancho; xx++) { suma += px[yy * ancho + xx] & 0xFF; n++; }
                    }
                    d[y * dw + x] = (byte) (n == 0 ? 255 : suma / n);
                }
            }
        }
        return new ImagenGris(d, dw, dh);
    }

    /** Cambio de tamaño a un ancho y alto exactos (bilineal; para la entrada del reconocedor de PP-OCR) */
    public ImagenGris redimensionar(int dw, int dh) {
        if (dw == ancho && dh == alto) return this;
        byte[] d = new byte[dw * dh];
        double fx = ancho / (double) dw, fy = alto / (double) dh;
        for (int y = 0; y < dh; y++) {
            double sy = Math.min(alto - 1, Math.max(0, (y + 0.5) * fy - 0.5));
            for (int x = 0; x < dw; x++) {
                double sx = Math.min(ancho - 1, Math.max(0, (x + 0.5) * fx - 0.5));
                d[y * dw + x] = (byte) muestrear(sx, sy);
            }
        }
        return new ImagenGris(d, dw, dh);
    }

    /** PGM binario (P5): lo lee Leptonica/Tesseract sin compresión, mucho más rápido de escribir que PNG */
    public byte[] aPgm() {
        byte[] cabecera = ("P5\n" + ancho + " " + alto + "\n255\n").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream(cabecera.length + px.length);
        out.writeBytes(cabecera);
        out.writeBytes(px);
        return out.toByteArray();
    }

    /** Histograma de 256 cubetas */
    public int[] histograma() {
        int[] h = new int[256];
        for (byte b : px) h[b & 0xFF]++;
        return h;
    }
}
