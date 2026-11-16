package me.eliassanchezfernandez.puntodeventa.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OCR con el programa {@code tesseract} (5.x) como proceso aparte: la imagen preparada por
 * {@link PreprocesadorTicket} se le pasa por stdin en PGM y se lee la salida TSV por stdout (sin
 * archivos temporales ni shell). Una sola hebra de Tesseract ({@code OMP_THREAD_LIMIT=1}): en una PC
 * de 2 núcleos deja libre el otro para la caja.
 * <p>
 * Orientación: el preproceso ya dejó los renglones horizontales; si la confianza sale baja se
 * prueba la imagen girada 180° (foto de cabeza) y se queda la lectura con mejor confianza.
 * <p>
 * Límite de tiempo y cancelación: si se pasa del límite o se interrumpe la hebra que llama, el
 * proceso se mata en el acto.
 */
public final class MotorTesseract implements MotorOcr {

    /**
     * @param preproceso      variante del preproceso
     * @param psm             modo de segmentación de página (4 = una columna de renglones de tamaño variable;
     *                        6 = un bloque uniforme). Ver la tabla en docs/investigacion-ocr-tickets.md
     * @param idiomas         null = los de la instalación ("spa+eng" si están los dos)
     * @param limite          tiempo máximo de toda la lectura
     * @param umbralReintento con confianza media menor se prueba también la imagen girada 180°
     */
    public record Ajustes(PreprocesadorTicket.Opciones preproceso, int psm, String idiomas, Duration limite,
                          double umbralReintento) {
        public Ajustes conPreproceso(PreprocesadorTicket.Opciones p) { return new Ajustes(p, psm, idiomas, limite, umbralReintento); }
        public Ajustes conPsm(int p) { return new Ajustes(preproceso, p, idiomas, limite, umbralReintento); }
        public Ajustes conIdiomas(String i) { return new Ajustes(preproceso, psm, i, limite, umbralReintento); }
        public Ajustes conLimite(Duration l) { return new Ajustes(preproceso, psm, idiomas, l, umbralReintento); }
    }

    public static final Ajustes PREDETERMINADOS =
            new Ajustes(PreprocesadorTicket.PREDETERMINADAS, 4, null, Duration.ofSeconds(60), 0.72);

    /** Detalle de una lectura (para el arnés de evaluación y la bitácora) */
    public record Lectura(ResultadoOcr resultado, PreprocesadorTicket.Preparada preparada, int giroExtra,
                          double confianza, int corridas, long msPreproceso, long msOcr) {}

    /** Hebras que leen la salida de los procesos (daemon: no detienen el cierre del programa) */
    private static final ExecutorService LECTORES = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tesseract-salida");
        t.setDaemon(true);
        return t;
    });

    private final LocalizadorTesseract.Ubicacion ubicacion;
    private final Ajustes ajustes;

    public MotorTesseract(LocalizadorTesseract.Ubicacion ubicacion, Ajustes ajustes) {
        this.ubicacion = ubicacion;
        this.ajustes = ajustes;
    }

    public MotorTesseract(LocalizadorTesseract.Ubicacion ubicacion) {
        this(ubicacion, PREDETERMINADOS);
    }

    @Override
    public String nombre() {
        return "Tesseract " + ubicacion.version();
    }

    public LocalizadorTesseract.Ubicacion ubicacion() {
        return ubicacion;
    }

    @Override
    public ResultadoOcr leer(byte[] imagen) throws IOException {
        return leerConDetalle(imagen).resultado();
    }

    public Lectura leerConDetalle(byte[] imagen) throws IOException {
        long limite = System.nanoTime() + ajustes.limite().toNanos();
        long t0 = System.nanoTime();
        PreprocesadorTicket.Preparada p = PreprocesadorTicket.preparar(imagen, ajustes.preproceso());
        long msPre = (System.nanoTime() - t0) / 1_000_000;
        revisarInterrupcion();

        long t1 = System.nanoTime();
        List<Renglon> mejor = reconocer(p.imagen(), p.escala(), limite);
        double confMejor = TsvTesseract.confianzaMedia(mejor);
        int giroMejor = 0, corridas = 1;
        if (confMejor < ajustes.umbralReintento()) {
            for (int giro : p.alternativas()) {
                List<Renglon> otro = reconocer(p.imagen().girar90(giro), p.escala(), limite);
                corridas++;
                double conf = TsvTesseract.confianzaMedia(otro);
                if (conf > confMejor + 0.03) { mejor = otro; confMejor = conf; giroMejor = giro; }
            }
        }
        long msOcr = (System.nanoTime() - t1) / 1_000_000;
        return new Lectura(TsvTesseract.resultado(mejor), p, giroMejor, confMejor, corridas, msPre, msOcr);
    }

    /** Corre Tesseract sobre una imagen ya preparada */
    public List<Renglon> reconocer(ImagenGris img, double escala, long limiteNanos) throws IOException {
        return TsvTesseract.renglones(correr(img.aPgm(), limiteNanos), escala);
    }

    List<String> comando() {
        List<String> cmd = new ArrayList<>();
        cmd.add(ubicacion.ejecutable().toString());
        cmd.add("stdin");
        cmd.add("stdout");
        if (ubicacion.tessdata() != null) {
            cmd.add("--tessdata-dir");
            cmd.add(ubicacion.tessdata().toString());
        }
        cmd.add("-l");
        cmd.add(ajustes.idiomas() != null ? ajustes.idiomas() : ubicacion.idiomas());
        cmd.add("--psm");
        cmd.add(String.valueOf(ajustes.psm()));
        cmd.add("--dpi");
        cmd.add("300");
        cmd.add("tsv");
        return cmd;
    }

    private String correr(byte[] pgm, long limiteNanos) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(comando()).redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("OMP_THREAD_LIMIT", "1");
        Process proceso = pb.start();
        try {
            CompletableFuture<byte[]> salida = CompletableFuture.supplyAsync(() -> {
                try (InputStream in = proceso.getInputStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, LECTORES);
            try (OutputStream out = proceso.getOutputStream()) {
                out.write(pgm);
            } catch (IOException e) {
                // Tesseract terminó antes de leer todo (error de argumentos o de idioma): lo dice el código de salida
            }
            long resta = limiteNanos - System.nanoTime();
            if (resta <= 0) throw new TimeoutException();
            byte[] datos = salida.get(resta, TimeUnit.NANOSECONDS);
            if (!proceso.waitFor(Math.max(1, (limiteNanos - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException();
            }
            if (proceso.exitValue() != 0) {
                throw new IOException("Tesseract terminó con error (código " + proceso.exitValue() + ")");
            }
            return new String(datos, StandardCharsets.UTF_8);
        } catch (TimeoutException e) {
            throw new IOException("La lectura del ticket tardó más de " + ajustes.limite().toSeconds() + " s y se canceló");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Lectura del ticket cancelada");
        } catch (ExecutionException e) {
            throw new IOException("No se pudo leer la salida de Tesseract", e.getCause());
        } finally {
            if (proceso.isAlive()) proceso.destroyForcibly();
        }
    }

    private static void revisarInterrupcion() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Lectura del ticket cancelada");
    }
}
