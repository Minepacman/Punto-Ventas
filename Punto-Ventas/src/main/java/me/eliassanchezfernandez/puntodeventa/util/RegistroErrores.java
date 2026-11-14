package me.eliassanchezfernandez.puntodeventa.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Registro de errores en archivo y manejador global de excepciones.
 *
 * {@link #instalar()} se llama al principio de {@code Launcher.main} (y, por si
 * se arranca {@code PuntoDeVentaApp} directo desde el IDE, también desde su
 * {@code main}); es idempotente y barato: no inicializa el registro (eso lo hace
 * Logback la primera vez que alguien escribe en él, normalmente Spring en su
 * hilo), solo:
 *
 *  - fija la propiedad {@value #PROPIEDAD_CARPETA} que lee {@code logback.xml}
 *    (carpeta {@code logs} dentro de la carpeta de datos de la aplicación: ahí
 *    rota {@value #ARCHIVO} en 5 archivos de 2 MB);
 *  - instala {@link Thread#setDefaultUncaughtExceptionHandler} (cubre también al
 *    hilo de JavaFX, que usa el manejador por defecto si nadie le pone uno);
 *  - desvía {@code System.err} al registro (lo que ya imprimían los
 *    {@code System.err.println} y {@code printStackTrace} del programa, y los
 *    avisos de JavaFX/java.util.logging que salen por ahí) sin dejar de
 *    mostrarlo en la consola.
 *
 * Un error no controlado queda en el registro con su pila y se avisa al usuario
 * con una ventana amable (sin pila), que dice dónde está el registro. El mismo
 * error repetido en menos de un minuto no vuelve a abrir la ventana, y nunca
 * hay dos ventanas de aviso a la vez.
 */
public final class RegistroErrores {

    /** Propiedad del sistema con la carpeta de registros (la lee logback.xml) */
    public static final String PROPIEDAD_CARPETA = "app.logs.dir";
    /** Nombre del archivo actual; los anteriores son caja-libre.1.log … caja-libre.4.log */
    public static final String ARCHIVO = "caja-libre.log";
    /** El mismo error repetido dentro de esta ventana de tiempo no vuelve a abrir el aviso */
    static final long VENTANA_REPETIDOS_MS = 60_000L;

    private static final AtomicBoolean INSTALADO = new AtomicBoolean();
    private static final Acelerador ACELERADOR = new Acelerador(VENTANA_REPETIDOS_MS);
    /** Evita que una línea de System.err que el propio registro provoque vuelva a entrar al registro */
    private static final ThreadLocal<Boolean> REGISTRANDO = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile PrintStream errOriginal = System.err;
    private static volatile boolean avisoAbierto;
    /** Quién muestra el aviso al usuario; las pruebas lo sustituyen */
    static volatile Consumer<Throwable> mostrador = RegistroErrores::mostrarAviso;

    private RegistroErrores() {}

    /** Carpeta donde se escriben los registros (logs dentro de la carpeta de datos, salvo propiedad explícita) */
    public static Path carpetaLogs() {
        String fijada = System.getProperty(PROPIEDAD_CARPETA);
        if (fijada != null && !fijada.isBlank()) return Path.of(fijada);
        return RutasApp.carpetaAplicacion().resolve("logs");
    }

    /** Archivo de registro actual */
    public static Path archivoActual() {
        return carpetaLogs().resolve(ARCHIVO);
    }

    /** Instala manejador, carpeta y desvío de System.err. Idempotente; nunca lanza. */
    public static void instalar() {
        if (!INSTALADO.compareAndSet(false, true)) return;
        try {
            Path carpeta = carpetaLogs();
            if (System.getProperty(PROPIEDAD_CARPETA) == null) {
                System.setProperty(PROPIEDAD_CARPETA, carpeta.toString());
            }
            try {
                Files.createDirectories(carpeta);
            } catch (IOException | RuntimeException e) {
                // Logback avisará en consola que no pudo abrir el archivo; el programa sigue
                errOriginal.println("No se pudo crear la carpeta de registros (" + carpeta + "): " + e.getMessage());
            }
            Thread.setDefaultUncaughtExceptionHandler(RegistroErrores::manejar);
            errOriginal = System.err;
            System.setErr(new PrintStream(new SalidaRegistrada(errOriginal, Charset.defaultCharset()), true,
                    Charset.defaultCharset()));
        } catch (RuntimeException e) {
            errOriginal.println("No se pudo instalar el registro de errores: " + e.getMessage());
        }
    }

    /** Una línea al arrancar con lo que el soporte necesita para ubicar el archivo y la versión */
    public static void anotarArranque() {
        try {
            registro().info("Caja Libre {} · {} {} ({}) · Java {} · registro en {}", VersionApp.etiqueta(),
                    System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                    System.getProperty("java.version"), carpetaLogs());
        } catch (RuntimeException ignorada) {
            // el registro no debe romper el arranque
        }
    }

    /**
     * Manejador de excepciones no controladas (cualquier hilo, incluido el de
     * JavaFX). Registra con pila y, si no es una repetición reciente, avisa.
     */
    public static void manejar(Thread hilo, Throwable error) {
        if (error == null || error instanceof ThreadDeath) return;
        String nombreHilo = hilo != null ? hilo.getName() : "?";
        try {
            registro().error("Error no controlado en el hilo {}", nombreHilo, error);
        } catch (Throwable fallaRegistro) {
            errOriginal.println("Error no controlado en el hilo " + nombreHilo + " (y el registro falló: " + fallaRegistro + ")");
            error.printStackTrace(errOriginal);
        }
        if (!ACELERADOR.debeAvisar(claveDe(error), System.currentTimeMillis())) return;
        try {
            mostrador.accept(error);
        } catch (RuntimeException sinInterfaz) {
            // sin JavaFX (herramientas, pruebas): el registro ya tiene todo
        }
    }

    /** Clave para reconocer "el mismo error": causa raíz, su mensaje y la primera línea de su pila */
    static String claveDe(Throwable error) {
        Throwable raiz = error;
        while (raiz.getCause() != null && raiz.getCause() != raiz) raiz = raiz.getCause();
        StackTraceElement[] pila = raiz.getStackTrace();
        return raiz.getClass().getName() + "|" + raiz.getMessage() + "|" + (pila != null && pila.length > 0 ? pila[0] : "");
    }

    /** Texto del aviso, sin pila: qué pasó, qué revisar y dónde quedó el registro */
    static String mensajeAviso(Throwable error) {
        return "Lo que estabas haciendo puede no haberse completado; revísalo antes de seguir.\n\n"
                + "Detalle técnico: " + PantallaConexion.causaRaiz(error) + "\n\n"
                + "El error quedó registrado en:\n" + carpetaLogs() + "\n\n"
                + "Si se repite, usa «Pedir soporte» en «Acerca de» y menciona la hora en que pasó.";
    }

    private static void mostrarAviso(Throwable error) {
        Runnable abrir = () -> {
            if (avisoAbierto) return;
            avisoAbierto = true;
            try {
                Alert a = new Alert(Alert.AlertType.ERROR);
                a.setTitle("Error inesperado");
                a.setHeaderText("Caja Libre encontró un error que no esperaba");
                a.setContentText(mensajeAviso(error));
                a.getDialogPane().setPrefWidth(480);
                Ventanas.estilizar(a);
                a.setOnHidden(ev -> avisoAbierto = false);
                a.show();
            } catch (RuntimeException e) {
                avisoAbierto = false;
            }
        };
        if (Platform.isFxApplicationThread()) abrir.run();
        else Platform.runLater(abrir);   // IllegalStateException si JavaFX no arrancó: lo atrapa manejar()
    }

    private static Logger registro() {
        return LoggerFactory.getLogger("me.eliassanchezfernandez.puntodeventa.errores");
    }

    // ── Repeticiones ──────────────────────────────────────────────────────

    /** Decide si un error merece ventana: no, si el mismo ya avisó hace menos de {@code ventanaMs} */
    static final class Acelerador {
        private final long ventanaMs;
        private final Map<String, Long> ultimoAviso = new HashMap<>();

        Acelerador(long ventanaMs) {
            this.ventanaMs = ventanaMs;
        }

        synchronized boolean debeAvisar(String clave, long ahoraMs) {
            Long anterior = ultimoAviso.get(clave);
            if (anterior != null && ahoraMs - anterior < ventanaMs) return false;
            if (ultimoAviso.size() > 200) ultimoAviso.clear();   // no crecer sin límite en una sesión larga
            ultimoAviso.put(clave, ahoraMs);
            return true;
        }
    }

    // ── System.err → registro ─────────────────────────────────────────────

    /**
     * Copia cada byte a la salida original y, línea por línea, lo manda al
     * registro como WARN del logger "stderr". Lo que el registro provoque
     * mientras escribe (p. ej. Logback avisando de un problema propio) no
     * vuelve a entrar, para no formar un bucle.
     */
    static final class SalidaRegistrada extends OutputStream {
        private static final int LINEA_MAXIMA = 8 * 1024;
        private final OutputStream original;
        private final Charset charset;
        private final ByteArrayOutputStream linea = new ByteArrayOutputStream();
        /** Perezoso: pedirlo en el constructor inicializaría Logback (leer logback.xml) en pleno arranque */
        private Logger stderr;

        SalidaRegistrada(OutputStream original, Charset charset) {
            this.original = original;
            this.charset = charset;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            original.write(b);
            acumular((byte) b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            original.write(b, off, len);
            for (int i = off; i < off + len; i++) acumular(b[i]);
        }

        @Override
        public void flush() throws IOException {
            original.flush();
        }

        private void acumular(byte b) {
            if (b == '\n') {
                volcar();
            } else if (b != '\r' && linea.size() < LINEA_MAXIMA) {
                linea.write(b);
            }
        }

        private void volcar() {
            String texto = linea.toString(charset);
            linea.reset();
            if (texto.isBlank() || REGISTRANDO.get()) return;
            REGISTRANDO.set(Boolean.TRUE);
            try {
                if (stderr == null) stderr = LoggerFactory.getLogger("stderr");
                stderr.warn(texto);
            } catch (RuntimeException ignorada) {
                // la consola ya lo tiene
            } finally {
                REGISTRANDO.set(Boolean.FALSE);
            }
        }
    }
}
