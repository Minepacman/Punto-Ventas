package me.eliassanchezfernandez.puntodeventa.ocr;

import jakarta.annotation.PreDestroy;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Punto de entrada del OCR de tickets de proveedor para el resto del programa.
 * <ul>
 *   <li>Las lecturas corren en UNA hebra propia de baja prioridad (una a la vez: en una PC de 2
 *       núcleos dos lecturas a la par dejarían la caja lenta). Nunca en el hilo de JavaFX.</li>
 *   <li>{@link #leerEnSegundoPlano} devuelve un {@link Future}: {@code cancel(true)} interrumpe la hebra;
 *       Tesseract se mata en el acto y PaddleOCR se detiene entre lote y lote. Cada lectura tiene además
 *       un límite de tiempo.</li>
 *   <li>El motor se elige con {@link SeleccionMotorOcr} según la preferencia guardada en esta PC.
 *       La descarga de PaddleOCR (~82 MB) y su medición con el ticket de muestra se hacen en otra hebra,
 *       solo cuando se usa el lector o se cambia la preferencia (nunca al arrancar la caja).</li>
 * </ul>
 */
@Service
public class LectorTicketsService {

    /** Límite de una lectura completa (preproceso + OCR + reintento girado) */
    public static final Duration LIMITE = Duration.ofSeconds(60);
    /** Ticket sintético (sin datos de nadie) para medir PaddleOCR en esta computadora */
    static final String TICKET_MUESTRA = "/ocr/ticket-muestra.jpg";

    private final ExecutorService hebra = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lector-tickets");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final ExecutorService preparacion = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lector-tickets-preparacion");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean preparando = new AtomicBoolean();

    private final Path carpeta;
    private final RecursosPpOcr recursos;
    private volatile Optional<LocalizadorTesseract.Ubicacion> tesseract;
    private volatile MotorOcr ppocr;
    private volatile String estadoPreparacion = "";

    public LectorTicketsService() {
        this(RutasApp.carpetaAplicacion());
    }

    LectorTicketsService(Path carpeta) {
        this.carpeta = carpeta;
        this.recursos = new RecursosPpOcr(carpeta);
    }

    /** Tesseract de esta PC (se busca una vez; {@link #volverABuscar()} lo repite) */
    public Optional<LocalizadorTesseract.Ubicacion> tesseract() {
        Optional<LocalizadorTesseract.Ubicacion> t = tesseract;
        if (t == null) {
            t = LocalizadorTesseract.buscar();
            tesseract = t;
        }
        return t;
    }

    public void volverABuscar() {
        tesseract = null;
    }

    public SeleccionMotorOcr.Preferencia preferencia() {
        return PreferenciasOcr.leer(carpeta).preferencia();
    }

    /** Guarda la preferencia y, si hace falta, empieza a bajar/medir PaddleOCR en segundo plano */
    public void guardarPreferencia(SeleccionMotorOcr.Preferencia p) {
        PreferenciasOcr.Datos d = PreferenciasOcr.leer(carpeta);
        PreferenciasOcr.guardar(carpeta, new PreferenciasOcr.Datos(p, d.segundosPpOcr(), d.huellaPpOcr(), d.cargaOnnx()));
        prepararEnSegundoPlano();
    }

    /** Qué motor se usaría con la preferencia dada y por qué (para la pantalla de Configuración) */
    public SeleccionMotorOcr.Decision decision(SeleccionMotorOcr.Preferencia p) {
        PreferenciasOcr.Datos d = PreferenciasOcr.leer(carpeta);
        SeleccionMotorOcr.EstadoPpOcr pp = new SeleccionMotorOcr.EstadoPpOcr(true, d.cargaOnnx(), recursos.listos(),
                d.segundosPpOcr(), d.huellaPpOcr());
        return SeleccionMotorOcr.decidir(p, SeleccionMotorOcr.Equipo.actual(), tesseract().isPresent(), pp);
    }

    public SeleccionMotorOcr.Decision decision() {
        return decision(preferencia());
    }

    /** Qué está pasando con la descarga/medición de PaddleOCR (vacío si nada) */
    public String estadoPreparacion() {
        return estadoPreparacion;
    }

    /** El motor que toca usar ahora, o vacío si no hay ninguno */
    public Optional<MotorOcr> motor() {
        SeleccionMotorOcr.Decision d = decision();
        if (d.motor() == SeleccionMotorOcr.Motor.PPOCR) {
            Optional<MotorOcr> pp = cargarPpOcr();
            if (pp.isPresent()) return pp;
        }
        if (d.motor() == SeleccionMotorOcr.Motor.NINGUNO) return Optional.empty();
        return tesseract().map(u -> new MotorTesseract(u, MotorTesseract.PREDETERMINADOS.conLimite(LIMITE)));
    }

    /** Carga PaddleOCR una vez; si ONNX Runtime no arranca lo anota (la selección cae a Tesseract) */
    synchronized Optional<MotorOcr> cargarPpOcr() {
        if (ppocr != null) return Optional.of(ppocr);
        if (!recursos.listos()) return Optional.empty();
        try {
            ppocr = CargadorPpOcr.cargar(recursos.ruta(RecursosPpOcr.ONNX_RUNTIME), recursos.ruta(RecursosPpOcr.DETECCION),
                    recursos.ruta(RecursosPpOcr.RECONOCIMIENTO), CargadorPpOcr.hilosSugeridos());
            anotarCarga(true, null, null);
            return Optional.of(ppocr);
        } catch (IOException | RuntimeException | LinkageError e) {
            System.err.println("PaddleOCR no arrancó en esta computadora (se usa Tesseract): " + e);
            anotarCarga(false, null, null);
            return Optional.empty();
        }
    }

    private void anotarCarga(boolean carga, Double segundos, String huella) {
        PreferenciasOcr.Datos d = PreferenciasOcr.leer(carpeta);
        PreferenciasOcr.guardar(carpeta, new PreferenciasOcr.Datos(d.preferencia(),
                segundos != null ? segundos : d.segundosPpOcr(), huella != null ? huella : d.huellaPpOcr(), carga));
    }

    /**
     * Si la decisión lo pide, baja PaddleOCR y/o lo mide con el ticket de muestra, en otra hebra. Una sola
     * preparación a la vez; nunca lanza.
     */
    public void prepararEnSegundoPlano() {
        SeleccionMotorOcr.Decision d = decision();
        if (!d.descargarModelos() && !d.medirPpOcr()) return;
        if (!preparando.compareAndSet(false, true)) return;
        preparacion.submit(() -> {
            try {
                if (!recursos.listos()) recursos.descargar(m -> estadoPreparacion = m);
                if (d.medirPpOcr()) medirPpOcr();
                estadoPreparacion = "";
            } catch (IOException | RuntimeException e) {
                estadoPreparacion = "No se pudo preparar PaddleOCR: " + e.getMessage();
                System.err.println(estadoPreparacion);
            } finally {
                preparando.set(false);
            }
        });
    }

    /** Lee el ticket de muestra con PaddleOCR y guarda cuánto tardó (con la huella del equipo) */
    void medirPpOcr() throws IOException {
        Optional<MotorOcr> pp = cargarPpOcr();
        if (pp.isEmpty()) return;
        byte[] muestra;
        try (InputStream in = LectorTicketsService.class.getResourceAsStream(TICKET_MUESTRA)) {
            if (in == null) throw new IOException("Falta el ticket de muestra");
            muestra = in.readAllBytes();
        }
        estadoPreparacion = "Midiendo PaddleOCR en esta computadora…";
        long t0 = System.nanoTime();
        pp.get().leer(muestra);
        double s = (System.nanoTime() - t0) / 1e9;
        anotarCarga(true, Math.round(s * 10) / 10.0, SeleccionMotorOcr.Equipo.actual().huella());
    }

    /** Encola la lectura de una foto. {@code cancel(true)} la detiene. */
    public Future<ResultadoOcr> leerEnSegundoPlano(byte[] foto) {
        prepararEnSegundoPlano();
        return hebra.submit(() -> {
            MotorOcr m = motor().orElseThrow(() -> new IOException(decision().motivo()));
            return m.leer(foto);
        });
    }

    /**
     * Lee y espera el resultado (para hebras que no son de JavaFX, como el servidor móvil). Si se
     * pasa del límite (incluida la espera en la cola) cancela la lectura.
     */
    public ResultadoOcr leer(byte[] foto, Duration espera) throws IOException {
        Future<ResultadoOcr> f = leerEnSegundoPlano(foto);
        try {
            return f.get(espera.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IOException("La lectura del ticket tardó demasiado y se canceló");
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Lectura cancelada");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) throw io;
            throw new IOException("No se pudo leer el ticket: " + c.getMessage(), c);
        }
    }

    @PreDestroy
    void cerrar() {
        hebra.shutdownNow();
        preparacion.shutdownNow();
        if (ppocr instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                // al cerrar no importa
            }
        }
    }
}
