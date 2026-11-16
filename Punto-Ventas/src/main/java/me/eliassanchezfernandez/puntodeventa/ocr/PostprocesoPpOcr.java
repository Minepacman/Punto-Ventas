package me.eliassanchezfernandez.puntodeventa.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Las partes de PP-OCR que no dependen de ONNX Runtime (Java puro, se prueban solas):
 * <ul>
 *   <li><b>Detección (DB)</b>: del mapa de probabilidad de "aquí hay texto" a cajas. Como la imagen ya
 *       viene enderezada por {@link PreprocesadorTicket}, basta la caja alineada a los ejes de cada
 *       componente (sin minAreaRect ni pyclipper); se agranda como el "unclip" de PaddleOCR
 *       (d = área · 1.6 / perímetro).</li>
 *   <li><b>Reconocimiento (CTC)</b>: decodificación voraz (argmax por columna, sin repetidos ni el
 *       símbolo vacío) y confianza = promedio de la probabilidad de cada carácter.</li>
 *   <li><b>Renglones</b>: agrupa los fragmentos reconocidos por altura y los ordena de izquierda a derecha.</li>
 * </ul>
 * Parámetros iguales a los de RapidOCR 3 (thresh 0.3, box_thresh 0.5, unclip 1.6, dilatación 2×2).
 */
public final class PostprocesoPpOcr {

    /** Caja en píxeles del mapa de detección, con su puntaje (probabilidad media) */
    public record Caja(int x0, int y0, int x1, int y1, double puntaje) {
        public int ancho() { return x1 - x0; }
        public int alto() { return y1 - y0; }
    }

    /** Un fragmento reconocido, en píxeles de la imagen de entrada */
    public record Fragmento(String texto, double confianza, int x0, int y0, int x1, int y1) {}

    /** Texto y confianza de una caja */
    public record Texto(String texto, double confianza) {}

    public static final double UMBRAL = 0.3;
    public static final double UMBRAL_CAJA = 0.5;
    public static final double UNCLIP = 1.6;

    private PostprocesoPpOcr() { }

    public static List<Caja> cajas(float[] prob, int w, int h) {
        return cajas(prob, w, h, UMBRAL, UMBRAL_CAJA, UNCLIP);
    }

    public static List<Caja> cajas(float[] prob, int w, int h, double umbral, double umbralCaja, double unclip) {
        boolean[] bin = new boolean[w * h];
        for (int i = 0; i < bin.length; i++) bin[i] = prob[i] > umbral;
        // dilatación 2×2 (como use_dilation de PaddleOCR): une letras partidas
        boolean[] dil = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                dil[i] = bin[i] || (x > 0 && bin[i - 1]) || (y > 0 && bin[i - w]) || (x > 0 && y > 0 && bin[i - w - 1]);
            }
        }
        int[] etiqueta = new int[w * h];
        int[] cola = new int[w * h];
        List<Caja> cajas = new ArrayList<>();
        int id = 0;
        for (int i = 0; i < dil.length; i++) {
            if (!dil[i] || etiqueta[i] != 0) continue;
            id++;
            int ini = 0, fin = 0;
            cola[fin++] = i;
            etiqueta[i] = id;
            int x0 = w, y0 = h, x1 = -1, y1 = -1;
            while (ini < fin) {
                int p = cola[ini++];
                int px = p % w, py = p / w;
                if (px < x0) x0 = px;
                if (px > x1) x1 = px;
                if (py < y0) y0 = py;
                if (py > y1) y1 = py;
                if (px > 0 && dil[p - 1] && etiqueta[p - 1] == 0) { etiqueta[p - 1] = id; cola[fin++] = p - 1; }
                if (px < w - 1 && dil[p + 1] && etiqueta[p + 1] == 0) { etiqueta[p + 1] = id; cola[fin++] = p + 1; }
                if (py > 0 && dil[p - w] && etiqueta[p - w] == 0) { etiqueta[p - w] = id; cola[fin++] = p - w; }
                if (py < h - 1 && dil[p + w] && etiqueta[p + w] == 0) { etiqueta[p + w] = id; cola[fin++] = p + w; }
            }
            int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            if (Math.min(bw, bh) < 3) continue;
            // puntaje "fast": probabilidad media dentro de la caja
            double suma = 0;
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) suma += prob[y * w + x];
            double puntaje = suma / (bw * (double) bh);
            if (puntaje < umbralCaja) continue;
            double d = bw * (double) bh * unclip / (2.0 * (bw + bh));
            int ex = (int) Math.round(d), ey = (int) Math.round(d);
            int nx0 = Math.max(0, x0 - ex), ny0 = Math.max(0, y0 - ey);
            int nx1 = Math.min(w, x1 + 1 + ex), ny1 = Math.min(h, y1 + 1 + ey);
            if (Math.min(nx1 - nx0, ny1 - ny0) < 5) continue;
            cajas.add(new Caja(nx0, ny0, nx1, ny1, puntaje));
        }
        return cajas;
    }

    /**
     * CTC voraz. {@code salida} = [pasos × clases] (probabilidades). Clase 0 = vacío; la clase i
     * corresponde a {@code diccionario.get(i - 1)}; la última, al espacio.
     */
    public static Texto ctc(float[] salida, int desde, int pasos, int clases, List<String> diccionario) {
        return ctc(salida, desde, pasos, clases, diccionario, null);
    }

    /**
     * Como {@link #ctc(float[], int, int, int, List)} pero solo entre las clases con {@code permitidas[c]}
     * (null = todas). El modelo es multilingüe (18 710 clases, casi todas chinas/japonesas/coreanas): en
     * tickets mexicanos un trazo dudoso salía como "长" o "丨"; restringido al alfabeto latino sale la
     * letra latina más probable.
     */
    public static Texto ctc(float[] salida, int desde, int pasos, int clases, List<String> diccionario, boolean[] permitidas) {
        StringBuilder sb = new StringBuilder();
        double suma = 0;
        int n = 0, anterior = -1;
        for (int t = 0; t < pasos; t++) {
            int base = desde + t * clases;
            int mejor = 0;
            float mejorP = salida[base];
            for (int c = 1; c < clases; c++) {
                if (salida[base + c] > mejorP && (permitidas == null || permitidas[c])) { mejorP = salida[base + c]; mejor = c; }
            }
            if (mejor != 0 && mejor != anterior) {
                int idx = mejor - 1;
                sb.append(idx < diccionario.size() ? diccionario.get(idx) : " ");
                suma += mejorP;
                n++;
            }
            anterior = mejor;
        }
        return new Texto(sb.toString().strip(), n == 0 ? 0 : suma / n);
    }

    /**
     * Clases permitidas para tickets en español: vacío, el espacio y las entradas del diccionario hechas
     * de caracteres latinos (hasta Latín extendido B), dígitos, puntuación y algunos símbolos comunes.
     */
    public static boolean[] clasesLatinas(List<String> diccionario) {
        boolean[] p = new boolean[diccionario.size() + 1];
        p[0] = true;
        for (int i = 0; i < diccionario.size(); i++) {
            String e = diccionario.get(i);
            boolean ok = !e.isEmpty();
            for (int k = 0; k < e.length() && ok; k++) {
                char ch = e.charAt(k);
                ok = ch < 0x0250 || "°×÷–—“”‘’…€".indexOf(ch) >= 0;
            }
            p[i + 1] = ok;
        }
        return p;
    }

    /** Fragmentos → renglones (por altura; dentro del renglón, de izquierda a derecha) */
    public static List<Renglon> renglones(List<Fragmento> fragmentos, double escala) {
        List<Fragmento> orden = new ArrayList<>(fragmentos);
        orden.removeIf(f -> f.texto().isBlank());
        orden.sort(Comparator.comparingDouble(f -> (f.y0() + f.y1()) / 2.0));
        List<List<Fragmento>> filas = new ArrayList<>();
        List<double[]> datos = new ArrayList<>();   // {centro y, alto}
        for (Fragmento f : orden) {
            double cy = (f.y0() + f.y1()) / 2.0, alto = f.y1() - f.y0();
            int ultima = filas.size() - 1;
            if (ultima >= 0 && Math.abs(datos.get(ultima)[0] - cy) < 0.6 * Math.max(alto, datos.get(ultima)[1])) {
                filas.get(ultima).add(f);
            } else {
                filas.add(new ArrayList<>(List.of(f)));
                datos.add(new double[] {cy, alto});
            }
        }
        List<Renglon> salida = new ArrayList<>();
        for (List<Fragmento> fila : filas) {
            fila.sort(Comparator.comparingInt(Fragmento::x0));
            StringBuilder sb = new StringBuilder();
            double suma = 0;
            int c = 0, arriba = Integer.MAX_VALUE;
            for (Fragmento f : fila) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(f.texto());
                suma += f.confianza() * f.texto().length();
                c += f.texto().length();
                arriba = Math.min(arriba, f.y0());
            }
            salida.add(new Renglon(sb.toString(), c == 0 ? 0 : suma / c, (int) Math.round(arriba / escala)));
        }
        return salida;
    }
}
