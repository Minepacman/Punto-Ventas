package me.eliassanchezfernandez.puntodeventa.ocr;

import me.eliassanchezfernandez.puntodeventa.service.ProductoImagenService;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Prepara la foto de un ticket para el OCR, en Java puro (sin OpenCV):
 * <ol>
 *   <li>decodifica (submuestreando fotos enormes) y aplica la orientación EXIF;</li>
 *   <li><b>recorta al papel</b>: el fondo del mostrador es oscuro o de color (luz morada, envolturas);
 *       el papel es el componente claro y poco saturado más grande (canal mínimo RGB + Otsu);</li>
 *   <li><b>quita el tinte de la luz</b> (balance de blancos con el percentil 90 de cada canal sobre
 *       el papel) y pasa a gris;</li>
 *   <li><b>aplana el fondo</b>: divide entre una estimación suave del papel (máximo por bloques
 *       interpolado). Elimina sombras de la mano, el degradado de la luz y los dobleces;</li>
 *   <li><b>orienta y endereza</b>: prueba ángulos alrededor de 0° y de 90° y se queda con el que
 *       hace más nítido el perfil de renglones (proyección horizontal de la tinta). El 180° (o
 *       90° contra 270°) no se distingue así: lo decide el motor por la confianza del OCR con
 *       {@link Preparada#alternativas()};</li>
 *   <li><b>amplía</b> para que la letra mida ~{@link Opciones#alturaLetraObjetivo()} px (Tesseract
 *       pierde precisión con letra de menos de ~20 px, típica de una foto de WhatsApp de 900 px);</li>
 *   <li>opcional: <b>binariza</b> con Sauvola (umbral local; aguanta tinta tenue y papel manchado).</li>
 * </ol>
 * Medido con las 16 fotos reales en la Mac M5: ~0.1–0.3 s por foto (ver docs/investigacion-ocr-tickets.md).
 */
public final class PreprocesadorTicket {

    /** Lado máximo al decodificar: una foto de 12 MP se lee submuestreada a ≤ 3000 px */
    static final int LADO_MAXIMO_DECODIFICADO = 3000;
    /** Tope de píxeles de la imagen final (memoria y tiempo de Tesseract en PCs de 2–4 GB) */
    static final long PIXELES_MAXIMOS_SALIDA = 9_000_000L;

    /**
     * Variantes del preproceso (para el arnés de evaluación; la app usa {@link #PREDETERMINADAS}).
     *
     * @param recortar             recortar al papel
     * @param balanceBlancos       quitar el tinte de la luz antes de pasar a gris
     * @param aplanarFondo         dividir entre el fondo estimado (sombras y dobleces)
     * @param enderezar            orientar (0/90) y corregir la inclinación
     * @param alturaLetraObjetivo  altura de renglón deseada en px (0 = no ampliar)
     * @param sauvola              binarizar con Sauvola (si no, se entrega en gris y Tesseract binariza)
     */
    public record Opciones(boolean recortar, boolean balanceBlancos, boolean aplanarFondo, boolean enderezar,
                           int alturaLetraObjetivo, boolean sauvola) {
        public static Opciones ninguna() {
            return new Opciones(false, false, false, false, 0, false);
        }
    }

    public static final Opciones PREDETERMINADAS = new Opciones(true, true, true, true, 28, false);

    /**
     * Resultado del preproceso.
     *
     * @param imagen        imagen lista para el OCR
     * @param giro90        giro aplicado en múltiplos de 90° (horario)
     * @param inclinacion   giro fino aplicado (grados)
     * @param escala        factor de ampliación aplicado (renglón y = y en la imagen / escala)
     * @param alturaLetra   altura de renglón estimada en la foto original (px)
     * @param alternativas  giros adicionales (sobre {@code imagen}) que vale la pena probar si la
     *                      confianza del OCR sale baja: {180} siempre
     * @param milisegundos  tiempo total del preproceso
     */
    public record Preparada(ImagenGris imagen, int giro90, double inclinacion, double escala,
                            double alturaLetra, int[] recorte, int[] alternativas, long milisegundos) {}

    private PreprocesadorTicket() { }

    // ── Entrada ──────────────────────────────────────────────────────────────

    /** Píxeles RGB 0xRRGGBB fila por fila */
    public record Rgb(int[] px, int ancho, int alto) {}

    public static Preparada preparar(byte[] foto, Opciones op) throws IOException {
        return preparar(decodificar(foto), op);
    }

    /** Decodifica con ImageIO (JPEG/PNG/GIF/BMP), submuestrea fotos enormes y aplica la orientación EXIF */
    public static Rgb decodificar(byte[] foto) throws IOException {
        if (foto == null || foto.length == 0) throw new IOException("La foto está vacía");
        BufferedImage img;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(foto))) {
            Iterator<ImageReader> lectores = iis == null ? null : ImageIO.getImageReaders(iis);
            if (lectores == null || !lectores.hasNext()) throw new IOException("El archivo no es una imagen que se pueda leer (usa JPG o PNG)");
            ImageReader lector = lectores.next();
            try {
                lector.setInput(iis, true, true);
                int lado = Math.max(lector.getWidth(0), lector.getHeight(0));
                ImageReadParam param = lector.getDefaultReadParam();
                int factor = (int) Math.ceil(lado / (double) LADO_MAXIMO_DECODIFICADO);
                if (factor > 1) param.setSourceSubsampling(factor, factor, 0, 0);
                img = lector.read(0, param);
            } finally {
                lector.dispose();
            }
        }
        if (img == null) throw new IOException("El archivo no es una imagen que se pueda leer (usa JPG o PNG)");
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        return orientar(new Rgb(px, w, h), ProductoImagenService.orientacionExif(foto));
    }

    /** Aplica la orientación EXIF 1..8 (como la muestra el teléfono) */
    static Rgb orientar(Rgb img, int orientacion) {
        if (orientacion <= 1 || orientacion > 8) return img;
        int w = img.ancho(), h = img.alto();
        boolean gira = orientacion >= 5;
        int dw = gira ? h : w, dh = gira ? w : h;
        int[] src = img.px(), dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx, sy;
                switch (orientacion) {
                    case 2 -> { sx = w - 1 - x; sy = y; }
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }
                    case 4 -> { sx = x; sy = h - 1 - y; }
                    case 5 -> { sx = y; sy = x; }
                    case 6 -> { sx = y; sy = h - 1 - x; }
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }
                    default -> { sx = w - 1 - y; sy = x; }
                }
                dst[y * dw + x] = src[sy * w + sx];
            }
        }
        return new Rgb(dst, dw, dh);
    }

    // ── Tubería ──────────────────────────────────────────────────────────────

    public static Preparada preparar(Rgb rgb, Opciones op) {
        long t0 = System.nanoTime();
        int[] recorte = op.recortar() ? detectarPapel(rgb) : new int[] {0, 0, rgb.ancho(), rgb.alto()};
        Rgb papel = recortarRgb(rgb, recorte);
        ImagenGris gris = aGris(papel, op.balanceBlancos());
        if (op.aplanarFondo()) gris = aplanarFondo(gris);

        int giro90 = 0;
        double inclinacion = 0;
        double alturaLetra;
        if (op.enderezar()) {
            Orientacion o = estimarOrientacion(gris);
            giro90 = o.giro90();
            inclinacion = o.inclinacion();
            gris = gris.girar90(giro90).girar(inclinacion);
            alturaLetra = o.alturaLetra();
        } else {
            alturaLetra = estimarAlturaLetra(gris);
        }

        double escala = 1;
        if (op.alturaLetraObjetivo() > 0 && alturaLetra > 0) {
            escala = Math.max(1.0, Math.min(3.0, op.alturaLetraObjetivo() / alturaLetra));
            double maxEscala = Math.sqrt(PIXELES_MAXIMOS_SALIDA / (double) ((long) gris.ancho() * gris.alto()));
            escala = Math.max(1.0, Math.min(escala, maxEscala));
            gris = gris.escalar(escala);
        }
        if (op.sauvola()) gris = sauvola(gris, Math.max(15, (int) Math.round(alturaLetra * escala * 1.5)) | 1, 0.2);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new Preparada(gris, giro90, inclinacion, escala, alturaLetra, recorte, new int[] {180}, ms);
    }

    // ── 1. Recorte al papel ─────────────────────────────────────────────────

    /**
     * Rectángulo {x, y, ancho, alto} del papel: el componente conexo más grande de píxeles
     * "blanquecinos" (canal mínimo RGB alto: la luz morada y las envolturas tienen un canal bajo)
     * en una copia de ≤ 400 px, con un margen. Si no hay un componente claro y grande (≥ 8 % de la
     * foto) devuelve la foto entera: recortar de más pierde renglones, recortar de menos solo deja
     * algo de basura que el intérprete descarta.
     */
    static int[] detectarPapel(Rgb rgb) {
        int w = rgb.ancho(), h = rgb.alto();
        int paso = Math.max(1, (int) Math.ceil(Math.max(w, h) / 400.0));
        int sw = (w + paso - 1) / paso, sh = (h + paso - 1) / paso;
        int[] minimo = new int[sw * sh];
        int[] hist = new int[256];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int suma = 0, n = 0;
                for (int yy = y * paso; yy < Math.min(h, (y + 1) * paso); yy++) {
                    for (int xx = x * paso; xx < Math.min(w, (x + 1) * paso); xx++) {
                        int c = rgb.px()[yy * w + xx];
                        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                        suma += Math.min(r, Math.min(g, b));
                        n++;
                    }
                }
                int v = suma / Math.max(1, n);
                minimo[y * sw + x] = v;
                hist[v]++;
            }
        }
        int umbral = otsu(hist);
        boolean[] claro = new boolean[sw * sh];
        for (int i = 0; i < claro.length; i++) claro[i] = minimo[i] > umbral;
        // cierre 1 px: une los pedazos de papel que separan los renglones de texto
        claro = dilatar(claro, sw, sh, 2);
        claro = erosionar(claro, sw, sh, 2);

        int[] etiqueta = new int[sw * sh];
        int mejor = 0, mejorArea = 0;
        int[] mejorCaja = null;
        int siguiente = 1;
        ArrayDeque<Integer> cola = new ArrayDeque<>();
        for (int i = 0; i < claro.length; i++) {
            if (!claro[i] || etiqueta[i] != 0) continue;
            int id = siguiente++;
            int area = 0, x0 = sw, y0 = sh, x1 = -1, y1 = -1;
            etiqueta[i] = id;
            cola.add(i);
            while (!cola.isEmpty()) {
                int p = cola.poll();
                area++;
                int px = p % sw, py = p / sw;
                x0 = Math.min(x0, px); x1 = Math.max(x1, px);
                y0 = Math.min(y0, py); y1 = Math.max(y1, py);
                if (px > 0 && claro[p - 1] && etiqueta[p - 1] == 0) { etiqueta[p - 1] = id; cola.add(p - 1); }
                if (px < sw - 1 && claro[p + 1] && etiqueta[p + 1] == 0) { etiqueta[p + 1] = id; cola.add(p + 1); }
                if (py > 0 && claro[p - sw] && etiqueta[p - sw] == 0) { etiqueta[p - sw] = id; cola.add(p - sw); }
                if (py < sh - 1 && claro[p + sw] && etiqueta[p + sw] == 0) { etiqueta[p + sw] = id; cola.add(p + sw); }
            }
            if (area > mejorArea) { mejorArea = area; mejor = id; mejorCaja = new int[] {x0, y0, x1, y1}; }
        }
        if (mejor == 0 || mejorArea < 0.08 * sw * sh) return new int[] {0, 0, w, h};
        int margen = Math.max(2, (int) Math.round(0.02 * Math.max(sw, sh)));
        int x0 = Math.max(0, (mejorCaja[0] - margen) * paso);
        int y0 = Math.max(0, (mejorCaja[1] - margen) * paso);
        int x1 = Math.min(w, (mejorCaja[2] + 1 + margen) * paso);
        int y1 = Math.min(h, (mejorCaja[3] + 1 + margen) * paso);
        return new int[] {x0, y0, x1 - x0, y1 - y0};
    }

    static Rgb recortarRgb(Rgb rgb, int[] r) {
        if (r[0] == 0 && r[1] == 0 && r[2] == rgb.ancho() && r[3] == rgb.alto()) return rgb;
        int[] d = new int[r[2] * r[3]];
        for (int y = 0; y < r[3]; y++) System.arraycopy(rgb.px(), (r[1] + y) * rgb.ancho() + r[0], d, y * r[2], r[2]);
        return new Rgb(d, r[2], r[3]);
    }

    // ── 2. Balance de blancos y gris ────────────────────────────────────────

    /**
     * Gris (luminancia). Con balance de blancos, cada canal se escala para que el percentil 90 de
     * los píxeles claros (el papel) quede en 255: la luz morada del mostrador pinta el papel de lila
     * y, sin esto, el papel queda más oscuro de un lado que del otro.
     */
    static ImagenGris aGris(Rgb rgb, boolean balance) {
        int n = rgb.px().length;
        double fr = 1, fg = 1, fb = 1;
        if (balance) {
            int[] hr = new int[256], hg = new int[256], hb = new int[256], hm = new int[256];
            int paso = Math.max(1, n / 200_000);
            for (int i = 0; i < n; i += paso) {
                int c = rgb.px()[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                hm[Math.min(r, Math.min(g, b))]++;
            }
            int umbral = otsu(hm);
            for (int i = 0; i < n; i += paso) {
                int c = rgb.px()[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (Math.min(r, Math.min(g, b)) <= umbral) continue;
                hr[r]++; hg[g]++; hb[b]++;
            }
            fr = 255.0 / Math.max(64, percentil(hr, 0.9));
            fg = 255.0 / Math.max(64, percentil(hg, 0.9));
            fb = 255.0 / Math.max(64, percentil(hb, 0.9));
        }
        byte[] d = new byte[n];
        for (int i = 0; i < n; i++) {
            int c = rgb.px()[i];
            double r = Math.min(255, ((c >> 16) & 0xFF) * fr);
            double g = Math.min(255, ((c >> 8) & 0xFF) * fg);
            double b = Math.min(255, (c & 0xFF) * fb);
            d[i] = (byte) (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
        }
        return new ImagenGris(d, rgb.ancho(), rgb.alto());
    }

    // ── 3. Aplanado del fondo (sombras, dobleces, degradado de luz) ─────────

    /**
     * Divide cada píxel entre el brillo del papel a su alrededor. El papel se estima con el
     * percentil 95 de bloques de ~1/24 del lado menor, suavizado e interpolado. Después estira
     * el contraste (percentil 1 → 0). El texto conserva su forma; la sombra de la mano o de un
     * doblez queda del mismo blanco que el resto.
     */
    static ImagenGris aplanarFondo(ImagenGris g) {
        int w = g.ancho(), h = g.alto();
        int bloque = Math.max(12, Math.min(w, h) / 24);
        int bw = (w + bloque - 1) / bloque, bh = (h + bloque - 1) / bloque;
        double[] fondo = new double[bw * bh];
        int[] hist = new int[256];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                Arrays.fill(hist, 0);
                int n = 0;
                for (int y = by * bloque; y < Math.min(h, (by + 1) * bloque); y += 2) {
                    for (int x = bx * bloque; x < Math.min(w, (bx + 1) * bloque); x += 2) { hist[g.get(x, y)]++; n++; }
                }
                fondo[by * bw + bx] = Math.max(24, percentil(hist, 0.95));
            }
        }
        // un bloque casi todo tinta (letra grande) tiene un "fondo" oscuro falso: tomar el máximo con los vecinos
        double[] ampliado = new double[fondo.length];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                double m = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int yy = by + dy, xx = bx + dx;
                    if (yy >= 0 && yy < bh && xx >= 0 && xx < bw) m = Math.max(m, fondo[yy * bw + xx]);
                }
                ampliado[by * bw + bx] = m;
            }
        }
        // suavizado 3×3 (promedio)
        double[] suave = new double[fondo.length];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                double s = 0;
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int yy = by + dy, xx = bx + dx;
                    if (yy >= 0 && yy < bh && xx >= 0 && xx < bw) { s += ampliado[yy * bw + xx]; n++; }
                }
                suave[by * bw + bx] = s / n;
            }
        }
        byte[] d = new byte[w * h];
        int[] histSalida = new int[256];
        for (int y = 0; y < h; y++) {
            double fy = Math.max(0, Math.min(bh - 1.0, (y + 0.5) / bloque - 0.5));
            int y0 = (int) fy, y1 = Math.min(bh - 1, y0 + 1);
            double ty = fy - y0;
            for (int x = 0; x < w; x++) {
                double fx = Math.max(0, Math.min(bw - 1.0, (x + 0.5) / bloque - 0.5));
                int x0 = (int) fx, x1 = Math.min(bw - 1, x0 + 1);
                double tx = fx - x0;
                double f = (suave[y0 * bw + x0] * (1 - tx) + suave[y0 * bw + x1] * tx) * (1 - ty)
                        + (suave[y1 * bw + x0] * (1 - tx) + suave[y1 * bw + x1] * tx) * ty;
                int v = (int) Math.min(255, Math.round(255.0 * g.get(x, y) / f));
                d[y * w + x] = (byte) v;
                histSalida[v]++;
            }
        }
        // estirar: percentil 1 → 0 (tinta tenue de impresoras de matriz y térmicas gastadas)
        int bajo = percentil(histSalida, 0.01);
        if (bajo > 0 && bajo < 200) {
            double k = 255.0 / (255 - bajo);
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                d[i] = (byte) (v <= bajo ? 0 : (int) Math.min(255, Math.round((v - bajo) * k)));
            }
        }
        return new ImagenGris(d, w, h);
    }

    // ── 4. Orientación e inclinación ─────────────────────────────────────────

    /** @param alturaLetra altura de renglón en px de la imagen de entrada (ya orientada) */
    record Orientacion(int giro90, double inclinacion, double alturaLetra, double puntaje0, double puntaje90) {}

    /**
     * Elige entre texto horizontal (0°) y vertical (90°) y la inclinación fina (±8°), maximizando
     * la nitidez del perfil de proyección de la tinta (suma de cuadrados de las diferencias entre
     * filas vecinas: los renglones bien alineados dan picos y valles marcados).
     */
    static Orientacion estimarOrientacion(ImagenGris g) {
        double f = Math.min(1.0, 900.0 / Math.max(g.ancho(), g.alto()));
        ImagenGris chica = g.escalar(f);
        int[][] tinta = puntosDeTinta(chica);
        java.util.List<int[]> letras = new java.util.ArrayList<>();
        componentesTexto(chica, letras);
        int[] votos = votosVecinos(letras);
        boolean vertical = votos[1] > votos[0] * 1.3;
        double[] m0 = vertical ? new double[] {0, 0} : mejorAngulo(tinta, 0);
        double[] m90 = vertical ? mejorAngulo(tinta, 90) : new double[] {90, 0};
        double angulo = vertical ? m90[0] : m0[0];
        int giro90 = vertical ? 90 : 0;
        double fino = vertical ? angulo - 90 : angulo;
        // altura de renglón en la imagen ya girada
        ImagenGris orientada = chica.girar90(giro90).girar(-fino);
        double altura = estimarAlturaLetra(orientada) / f;
        return new Orientacion(giro90, -fino, altura, votos[0], votos[1]);
    }

    /**
     * Método "docstrum": en un renglón la letra más cercana a cada letra está a un lado (el espacio
     * entre letras es menor que el interlineado), aunque la fuente sea monoespaciada y las columnas
     * queden alineadas. Devuelve {votos horizontales, votos verticales} de la dirección al vecino más
     * cercano de altura parecida. (Proyectar la tinta no sirve para esto: en los tickets de fuente
     * monoespaciada las columnas dan un perfil tan nítido como los renglones.)
     */
    static int[] votosVecinos(java.util.List<int[]> letras) {
        int n = letras.size();
        int paso = Math.max(1, n / 3000);
        int h = 0, v = 0;
        for (int i = 0; i < n; i += paso) {
            int[] a = letras.get(i);
            double ax = a[0] + a[2] / 2.0, ay = a[1] + a[3] / 2.0;
            double mejor = Double.MAX_VALUE, mdx = 0, mdy = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                int[] b = letras.get(j);
                if (b[3] * 2 < a[3] || b[3] > a[3] * 2) continue;
                double dx = b[0] + b[2] / 2.0 - ax, dy = b[1] + b[3] / 2.0 - ay;
                double d = dx * dx + dy * dy;
                if (d < mejor && d > 0) { mejor = d; mdx = dx; mdy = dy; }
            }
            if (mejor == Double.MAX_VALUE) continue;
            double ang = Math.abs(Math.toDegrees(Math.atan2(mdy, mdx)));
            if (ang > 90) ang = 180 - ang;
            if (ang < 30) h++;
            else if (ang > 60) v++;
        }
        return new int[] {h, v};
    }

    /**
     * Píxeles oscuros que pertenecen a componentes del tamaño de una letra (alto 2..~1/20 de la
     * imagen, no mucho más anchos que altos). Descarta el fondo oscuro que quedó dentro del
     * recorte, las orillas del papel, las líneas "=====" largas y los códigos QR: sin este filtro
     * dominaban el perfil y torcían la estimación del ángulo.
     */
    static boolean[] mascaraTexto(ImagenGris g) {
        return componentesTexto(g, null);
    }

    /** Como {@link #mascaraTexto}; si {@code cajas} no es null agrega {x0, y0, ancho, alto} de cada letra */
    static boolean[] componentesTexto(ImagenGris g, java.util.List<int[]> cajas) {
        int w = g.ancho(), h = g.alto();
        boolean[] oscuro = new boolean[w * h];
        for (int i = 0; i < oscuro.length; i++) oscuro[i] = (g.px()[i] & 0xFF) < 110;
        boolean[] texto = new boolean[w * h];
        int[] etiqueta = new int[w * h];
        int[] cola = new int[w * h];
        int altoMax = Math.max(12, h / 20);
        int id = 0;
        for (int i = 0; i < oscuro.length; i++) {
            if (!oscuro[i] || etiqueta[i] != 0) continue;
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
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = py + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = px + dx;
                        if (xx < 0 || xx >= w) continue;
                        int q = yy * w + xx;
                        if (oscuro[q] && etiqueta[q] == 0) { etiqueta[q] = id; cola[fin++] = q; }
                    }
                }
            }
            int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
            boolean letra = ch >= 2 && ch <= altoMax && cw <= 3 * ch + 4 && fin >= 3;
            if (letra) {
                for (int k = 0; k < fin; k++) texto[cola[k]] = true;
                if (cajas != null) cajas.add(new int[] {x0, y0, cw, ch});
            }
        }
        return texto;
    }

    /** Coordenadas (x, y) de los píxeles de texto, a lo más ~150 000 (submuestreo regular) */
    static int[][] puntosDeTinta(ImagenGris g) {
        boolean[] m = mascaraTexto(g);
        int n = 0;
        for (boolean b : m) if (b) n++;
        int paso = Math.max(1, n / 150_000);
        int[] xs = new int[n / paso + 1], ys = new int[n / paso + 1];
        int k = 0, i = 0;
        for (int p = 0; p < m.length; p++) {
            if (m[p] && i++ % paso == 0 && k < xs.length) { xs[k] = p % g.ancho(); ys[k] = p / g.ancho(); k++; }
        }
        return new int[][] {Arrays.copyOf(xs, k), Arrays.copyOf(ys, k)};
    }

    /** {ángulo, puntaje} del mejor ángulo en centro ± 8° (paso 0.5° y luego 0.1°) */
    static double[] mejorAngulo(int[][] tinta, double centro) {
        double mejor = centro, mejorP = -1;
        for (double a = centro - 8; a <= centro + 8.001; a += 0.5) {
            double p = nitidez(tinta, a);
            if (p > mejorP) { mejorP = p; mejor = a; }
        }
        double base = mejor;
        for (double a = base - 0.4; a <= base + 0.401; a += 0.1) {
            double p = nitidez(tinta, a);
            if (p > mejorP) { mejorP = p; mejor = a; }
        }
        return new double[] {mejor, mejorP};
    }

    /**
     * Proyecta los puntos sobre la normal de la dirección {@code grados} (0° = renglones
     * horizontales) y mide la nitidez del perfil.
     */
    static double nitidez(int[][] tinta, double grados) {
        int[] xs = tinta[0], ys = tinta[1];
        if (xs.length == 0) return 0;
        double r = Math.toRadians(grados);
        double sin = Math.sin(r), cos = Math.cos(r);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int[] pos = new int[xs.length];
        for (int i = 0; i < xs.length; i++) {
            int p = (int) Math.round(-xs[i] * sin + ys[i] * cos);
            pos[i] = p;
            if (p < min) min = p;
            if (p > max) max = p;
        }
        long[] hist = new long[max - min + 1];
        for (int p : pos) hist[p - min]++;
        double s = 0;
        for (int i = 1; i < hist.length; i++) {
            double d = hist[i] - hist[i - 1];
            s += d * d;
        }
        return s / xs.length;
    }

    /**
     * Altura típica de renglón (px): la imagen se parte en 6 franjas verticales (así la curvatura
     * del papel, la inclinación que quede y el fondo no juntan renglones vecinos) y en cada franja se
     * miden las corridas de filas con tinta; se devuelve la mediana. 0 si no hay texto reconocible.
     */
    static double estimarAlturaLetra(ImagenGris g) {
        boolean[] m = mascaraTexto(g);
        int franjas = 6;
        int ancho = Math.max(1, g.ancho() / franjas);
        java.util.List<Integer> corridas = new java.util.ArrayList<>();
        for (int f = 0; f < franjas; f++) {
            int x0 = f * ancho, x1 = f == franjas - 1 ? g.ancho() : x0 + ancho;
            int umbral = Math.max(2, (int) Math.round((x1 - x0) * 0.03));
            int inicio = -1;
            for (int y = 0; y <= g.alto(); y++) {
                boolean con = false;
                if (y < g.alto()) {
                    int c = 0;
                    for (int x = x0; x < x1; x++) if (m[y * g.ancho() + x]) c++;
                    con = c >= umbral;
                }
                if (con && inicio < 0) inicio = y;
                if (!con && inicio >= 0) {
                    int largo = y - inicio;
                    if (largo >= 3 && largo < g.alto() / 6) corridas.add(largo);
                    inicio = -1;
                }
            }
        }
        if (corridas.size() < 3) return 0;
        java.util.Collections.sort(corridas);
        return corridas.get(corridas.size() / 2);
    }

    // ── 5. Binarización de Sauvola ───────────────────────────────────────────

    /**
     * Umbral local T = m·(1 + k·(s/R − 1)) en una ventana de {@code ventana}×{@code ventana}
     * (imágenes integrales: O(1) por píxel). R = 128.
     */
    static ImagenGris sauvola(ImagenGris g, int ventana, double k) {
        int w = g.ancho(), h = g.alto();
        long[] s = new long[(w + 1) * (h + 1)];
        long[] s2 = new long[(w + 1) * (h + 1)];
        for (int y = 1; y <= h; y++) {
            long fila = 0, fila2 = 0;
            for (int x = 1; x <= w; x++) {
                int v = g.get(x - 1, y - 1);
                fila += v;
                fila2 += (long) v * v;
                s[y * (w + 1) + x] = s[(y - 1) * (w + 1) + x] + fila;
                s2[y * (w + 1) + x] = s2[(y - 1) * (w + 1) + x] + fila2;
            }
        }
        int r = ventana / 2;
        byte[] d = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                long n = (long) (x1 - x0) * (y1 - y0);
                long suma = s[y1 * (w + 1) + x1] - s[y0 * (w + 1) + x1] - s[y1 * (w + 1) + x0] + s[y0 * (w + 1) + x0];
                long suma2 = s2[y1 * (w + 1) + x1] - s2[y0 * (w + 1) + x1] - s2[y1 * (w + 1) + x0] + s2[y0 * (w + 1) + x0];
                double m = suma / (double) n;
                double var = Math.max(0, suma2 / (double) n - m * m);
                double t = m * (1 + k * (Math.sqrt(var) / 128.0 - 1));
                d[y * w + x] = (byte) (g.get(x, y) <= t ? 0 : 255);
            }
        }
        return new ImagenGris(d, w, h);
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    static int otsu(int[] hist) {
        long total = 0, suma = 0;
        for (int i = 0; i < 256; i++) { total += hist[i]; suma += (long) i * hist[i]; }
        long sumaB = 0, pesoB = 0;
        double mejor = -1;
        int umbral = 127;
        for (int t = 0; t < 256; t++) {
            pesoB += hist[t];
            if (pesoB == 0) continue;
            long pesoF = total - pesoB;
            if (pesoF == 0) break;
            sumaB += (long) t * hist[t];
            double mB = sumaB / (double) pesoB, mF = (suma - sumaB) / (double) pesoF;
            double entre = (double) pesoB * pesoF * (mB - mF) * (mB - mF);
            if (entre > mejor) { mejor = entre; umbral = t; }
        }
        return umbral;
    }

    static int percentil(int[] hist, double q) {
        long total = 0;
        for (int v : hist) total += v;
        if (total == 0) return 0;
        long objetivo = (long) Math.ceil(q * total);
        long acumulado = 0;
        for (int i = 0; i < hist.length; i++) {
            acumulado += hist[i];
            if (acumulado >= objetivo) return i;
        }
        return hist.length - 1;
    }

    private static boolean[] dilatar(boolean[] m, int w, int h, int r) {
        boolean[] d = new boolean[m.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (!m[y * w + x]) continue;
            for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
                int yy = y + dy, xx = x + dx;
                if (yy >= 0 && yy < h && xx >= 0 && xx < w) d[yy * w + xx] = true;
            }
        }
        return d;
    }

    private static boolean[] erosionar(boolean[] m, int w, int h, int r) {
        boolean[] d = new boolean[m.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean todos = true;
            for (int dy = -r; dy <= r && todos; dy++) for (int dx = -r; dx <= r; dx++) {
                int yy = y + dy, xx = x + dx;
                if (yy >= 0 && yy < h && xx >= 0 && xx < w && !m[yy * w + xx]) { todos = false; break; }
            }
            d[y * w + x] = todos;
        }
        return d;
    }
}
