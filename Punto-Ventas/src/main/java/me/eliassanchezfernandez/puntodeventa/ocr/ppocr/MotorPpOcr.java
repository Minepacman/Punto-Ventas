package me.eliassanchezfernandez.puntodeventa.ocr.ppocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import me.eliassanchezfernandez.puntodeventa.ocr.ImagenGris;
import me.eliassanchezfernandez.puntodeventa.ocr.MotorOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.PostprocesoPpOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.PreprocesadorTicket;
import me.eliassanchezfernandez.puntodeventa.ocr.Renglon;
import me.eliassanchezfernandez.puntodeventa.ocr.ResultadoOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.TsvTesseract;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * PP-OCRv6 "small" (detección DB + reconocimiento CTC multilingüe) sobre ONNX Runtime, en Java.
 * <p>
 * ESTA CLASE NO SE CARGA CON EL CARGADOR NORMAL: ONNX Runtime (~50 MB con los nativos de todas las
 * plataformas) no viene en el instalador, se baja bajo demanda. {@code CargadorPpOcr} la define en un
 * cargador propio que ve el jar descargado. No referenciarla directamente desde el resto del programa:
 * usar {@link MotorOcr}.
 * <p>
 * Preproceso: el de {@link PreprocesadorTicket} solo con orientación e inclinación (PP-OCR trae su propia
 * normalización y lee mejor la foto sin aplanar ni ampliar; ver docs/investigacion-ocr-tickets.md).
 * Constructor público (Path, Path, int): lo llama {@code CargadorPpOcr} por reflexión.
 */
public final class MotorPpOcr implements MotorOcr, AutoCloseable {

    static final int ALTO_REC = 48;
    static final int LOTE_REC = 6;
    static final int LADO_MIN_DET = 736;
    static final int LADO_MAX_DET = 2400;
    static final double UMBRAL_REINTENTO = 0.80;

    public static final PreprocesadorTicket.Opciones PREPROCESO =
            new PreprocesadorTicket.Opciones(false, true, true, true, 0, false);

    private final OrtEnvironment env;
    private final OrtSession det;
    private final OrtSession rec;
    private final List<String> diccionario;
    private final boolean[] permitidas;

    public MotorPpOcr(Path modeloDet, Path modeloRec, int hilos) throws IOException {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions so = new OrtSession.SessionOptions();
            so.setIntraOpNumThreads(Math.max(1, hilos));
            so.setInterOpNumThreads(1);
            so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            // Sin "arena" de memoria: con ella ONNX Runtime se quedaba con ~1.2 GB nativos tras unas lecturas y no
            // los devolvía (medido en PC de 2 núcleos simulada). Sin arena cada lectura libera lo suyo; cuesta
            // unos milisegundos por lectura, que en un ticket de 1–3 s no se notan.
            so.setCPUArenaAllocator(false);
            so.setMemoryPatternOptimization(false);
            det = env.createSession(modeloDet.toString(), so);
            rec = env.createSession(modeloRec.toString(), so);
            String caracteres = rec.getMetadata().getCustomMetadata().get("character");
            if (caracteres == null) throw new IOException("El modelo de reconocimiento no trae su diccionario");
            List<String> d = new ArrayList<>(Arrays.asList(caracteres.split("\n", -1)));
            if (!d.isEmpty() && d.get(d.size() - 1).isEmpty()) d.remove(d.size() - 1);
            // ONNX Runtime para Java corta el texto de los metadatos en el primer carácter de 4 bytes (los
            // emoji del final del diccionario): se rellenan esas entradas vacías (no permitidas) para que el
            // espacio quede en la última clase, como en PaddleOCR.
            long[] forma = ((ai.onnxruntime.TensorInfo) rec.getOutputInfo().values().iterator().next().getInfo()).getShape();
            int clases = (int) forma[forma.length - 1];
            if (clases > 2 && d.size() > clases - 2) throw new IOException("El diccionario no corresponde al modelo");
            while (clases > 2 && d.size() < clases - 2) d.add("");
            d.add(" ");
            diccionario = List.copyOf(d);
            permitidas = PostprocesoPpOcr.clasesLatinas(diccionario);
        } catch (OrtException e) {
            throw new IOException("No se pudo cargar PaddleOCR: " + e.getMessage(), e);
        }
    }

    @Override
    public String nombre() {
        return "PaddleOCR (PP-OCRv6)";
    }

    @Override
    public ResultadoOcr leer(byte[] imagen) throws IOException {
        return leer(imagen, PREPROCESO);
    }

    /** Con otra variante del preproceso (arnés de evaluación) */
    public ResultadoOcr leer(byte[] imagen, PreprocesadorTicket.Opciones preproceso) throws IOException {
        PreprocesadorTicket.Preparada p = PreprocesadorTicket.preparar(imagen, preproceso);
        List<Renglon> mejor = leerImagen(p.imagen(), p.escala());
        double conf = TsvTesseract.confianzaMedia(mejor);
        if (conf < UMBRAL_REINTENTO) {
            for (int giro : p.alternativas()) {
                List<Renglon> otro = leerImagen(p.imagen().girar90(giro), p.escala());
                double c = TsvTesseract.confianzaMedia(otro);
                if (c > conf + 0.03) { mejor = otro; conf = c; }
            }
        }
        return TsvTesseract.resultado(mejor);
    }

    /** Detección + reconocimiento sobre una imagen ya orientada */
    public List<Renglon> leerImagen(ImagenGris img, double escala) throws IOException {
        try {
            // ── detección ──
            double f = 1;
            int menor = Math.min(img.ancho(), img.alto()), mayor = Math.max(img.ancho(), img.alto());
            if (menor < LADO_MIN_DET) f = LADO_MIN_DET / (double) menor;
            if (mayor * f > LADO_MAX_DET) f = LADO_MAX_DET / (double) mayor;
            int dw = Math.max(32, (int) Math.round(img.ancho() * f / 32.0) * 32);
            int dh = Math.max(32, (int) Math.round(img.alto() * f / 32.0) * 32);
            ImagenGris chica = img.redimensionar(dw, dh);
            float[] entrada = new float[3 * dw * dh];
            int plano = dw * dh;
            for (int i = 0; i < plano; i++) {
                float v = ((chica.px()[i] & 0xFF) / 255f - 0.5f) / 0.5f;
                entrada[i] = v;
                entrada[plano + i] = v;
                entrada[2 * plano + i] = v;
            }
            float[] prob;
            try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(entrada), new long[] {1, 3, dh, dw});
                 OrtSession.Result r = det.run(Map.of(det.getInputNames().iterator().next(), t))) {
                FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
                prob = new float[fb.remaining()];
                fb.get(prob);
            }
            revisarInterrupcion();
            List<PostprocesoPpOcr.Caja> cajas = PostprocesoPpOcr.cajas(prob, dw, dh);
            double sx = img.ancho() / (double) dw, sy = img.alto() / (double) dh;

            // ── reconocimiento, en lotes ordenados por proporción ──
            List<int[]> recortes = new ArrayList<>();
            for (PostprocesoPpOcr.Caja c : cajas) {
                int x0 = (int) Math.floor(c.x0() * sx), y0 = (int) Math.floor(c.y0() * sy);
                int x1 = Math.min(img.ancho(), (int) Math.ceil(c.x1() * sx)), y1 = Math.min(img.alto(), (int) Math.ceil(c.y1() * sy));
                if (x1 - x0 >= 4 && y1 - y0 >= 4) recortes.add(new int[] {x0, y0, x1, y1});
            }
            recortes.sort(Comparator.comparingDouble(r -> (r[2] - r[0]) / (double) (r[3] - r[1])));
            List<PostprocesoPpOcr.Fragmento> fragmentos = new ArrayList<>();
            for (int i = 0; i < recortes.size(); i += LOTE_REC) {
                List<int[]> lote = recortes.subList(i, Math.min(recortes.size(), i + LOTE_REC));
                double maxRel = 0;
                for (int[] r : lote) maxRel = Math.max(maxRel, (r[2] - r[0]) / (double) (r[3] - r[1]));
                int ancho = Math.max(ALTO_REC, (int) (ALTO_REC * maxRel));
                float[] e = new float[lote.size() * 3 * ALTO_REC * ancho];
                int planoRec = ALTO_REC * ancho;
                for (int k = 0; k < lote.size(); k++) {
                    int[] r = lote.get(k);
                    ImagenGris recorte = img.recortar(r[0], r[1], r[2] - r[0], r[3] - r[1]);
                    int rw = Math.min(ancho, (int) Math.ceil(ALTO_REC * recorte.ancho() / (double) recorte.alto()));
                    ImagenGris norm = recorte.redimensionar(Math.max(1, rw), ALTO_REC);
                    int base = k * 3 * planoRec;
                    for (int y = 0; y < ALTO_REC; y++) {
                        for (int x = 0; x < norm.ancho(); x++) {
                            float v = (norm.get(x, y) / 255f - 0.5f) / 0.5f;
                            int idx = y * ancho + x;
                            e[base + idx] = v;
                            e[base + planoRec + idx] = v;
                            e[base + 2 * planoRec + idx] = v;
                        }
                    }
                }
                try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(e), new long[] {lote.size(), 3, ALTO_REC, ancho});
                     OrtSession.Result res = rec.run(Map.of(rec.getInputNames().iterator().next(), t))) {
                    OnnxTensor salida = (OnnxTensor) res.get(0);
                    long[] forma = salida.getInfo().getShape();
                    int pasos = (int) forma[1], clases = (int) forma[2];
                    FloatBuffer fb = salida.getFloatBuffer();
                    float[] todo = new float[fb.remaining()];
                    fb.get(todo);
                    for (int k = 0; k < lote.size(); k++) {
                        PostprocesoPpOcr.Texto tx = PostprocesoPpOcr.ctc(todo, k * pasos * clases, pasos, clases, diccionario, permitidas);
                        int[] r = lote.get(k);
                        if (!tx.texto().isEmpty()) {
                            fragmentos.add(new PostprocesoPpOcr.Fragmento(tx.texto(), tx.confianza(), r[0], r[1], r[2], r[3]));
                        }
                    }
                }
                revisarInterrupcion();
            }
            return PostprocesoPpOcr.renglones(fragmentos, escala);
        } catch (OrtException e) {
            throw new IOException("PaddleOCR falló: " + e.getMessage(), e);
        }
    }

    private static void revisarInterrupcion() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Lectura del ticket cancelada");
    }

    @Override
    public void close() {
        try {
            det.close();
            rec.close();
        } catch (OrtException e) {
            // al cerrar no importa
        }
    }
}
