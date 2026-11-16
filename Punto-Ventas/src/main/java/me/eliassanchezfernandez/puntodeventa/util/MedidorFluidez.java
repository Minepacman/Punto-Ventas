package me.eliassanchezfernandez.puntodeventa.util;

import javafx.application.Platform;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Mide, casi sin costo, si esta computadora es lenta para el programa: cuánto tardó en abrir y cuánto
 * tarda JavaFX en recalcular estilos y acomodar la pantalla en cada cuadro (dos {@code System.nanoTime()}
 * por cuadro con cambios, solo durante los primeros {@value #MUESTRAS}). Deja una línea en el registro
 * (para soporte) y, si el equipo resulta lento, avisa a {@link ModoLigero} para que la barra de estado
 * ofrezca el modo ligero una sola vez.
 */
public final class MedidorFluidez {

    private MedidorFluidez() { }

    private static final Logger LOG = LoggerFactory.getLogger("me.eliassanchezfernandez.puntodeventa.rendimiento");

    /** Arranque (de la JVM al login) a partir del cual se ofrece el modo ligero */
    static final long ARRANQUE_LENTO_MS = 30_000;
    /** Cuadros con trabajo que se miden */
    static final int MUESTRAS = 400;
    /** Mediana / percentil 90 del trabajo por cuadro (estilos + acomodo) a partir de los cuales es "lento" */
    static final double MEDIANA_LENTA_MS = 25;
    static final double P90_LENTO_MS = 120;

    private static volatile Consumer<String> alDetectarLentitud;
    private static boolean midiendo;

    /** Quién ofrece el modo ligero (la ventana principal); se llama en el hilo de JavaFX */
    public static void alDetectarLentitud(Consumer<String> oyente) {
        alDetectarLentitud = oyente;
        String ya = ModoLigero.motivoLentitud();
        if (ya != null && oyente != null) oyente.accept(ya);
    }

    /** Con el login ya en pantalla: anota cuánto tardó en abrir */
    public static void anotarArranque() {
        long ms = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
        try {
            LOG.info("Rendimiento: abrió en {} s · dibujo {} · modo ligero {} · {} núcleos · RAM {} MB · memoria máx. {} MB · {} bits",
                    String.format(Locale.ROOT, "%.1f", ms / 1000.0), ModoLigero.nombreTuberia(),
                    ModoLigero.activo() ? "encendido" : "apagado", Runtime.getRuntime().availableProcessors(),
                    ramMb(), Runtime.getRuntime().maxMemory() >> 20, System.getProperty("sun.arch.data.model", "?"));
        } catch (RuntimeException ignorada) {
            // el registro no debe romper el arranque
        }
        if (ms > ARRANQUE_LENTO_MS) lento(String.format(Locale.ROOT, "tardó %.0f s en abrir", ms / 1000.0));
    }

    /** Mide los primeros cuadros de la escena principal (una sola vez por ejecución) */
    public static void medir(Scene escena) {
        if (midiendo || escena == null) return;
        midiendo = true;
        double[] muestras = new double[MUESTRAS];
        int[] n = {0};
        long[] inicio = {0};
        Runnable[] post = new Runnable[1];
        Runnable pre = () -> inicio[0] = System.nanoTime();
        post[0] = () -> {
            double ms = (System.nanoTime() - inicio[0]) / 1e6;
            if (ms < 0.5 || n[0] >= MUESTRAS) return;   // cuadro sin cambios
            muestras[n[0]++] = ms;
            if (n[0] == MUESTRAS) {
                // quitar los oyentes fuera del propio aviso
                Platform.runLater(() -> {
                    escena.removePreLayoutPulseListener(pre);
                    escena.removePostLayoutPulseListener(post[0]);
                });
                evaluar(muestras);
            }
        };
        escena.addPreLayoutPulseListener(pre);
        escena.addPostLayoutPulseListener(post[0]);
    }

    static void evaluar(double[] muestras) {
        double[] o = muestras.clone();
        Arrays.sort(o);
        double mediana = o[o.length / 2];
        double p90 = o[(int) (o.length * 0.9)];
        try {
            LOG.info("Rendimiento de pantalla: {} cuadros con cambios, mediana {} ms, p90 {} ms, máx. {} ms (estilos y acomodo)",
                    o.length, String.format(Locale.ROOT, "%.1f", mediana), String.format(Locale.ROOT, "%.1f", p90),
                    String.format(Locale.ROOT, "%.0f", o[o.length - 1]));
        } catch (RuntimeException ignorada) {
            // solo diagnóstico
        }
        if (esLenta(mediana, p90)) {
            lento(String.format(Locale.ROOT, "la pantalla tarda %.0f ms por cuadro", mediana));
        }
    }

    static boolean esLenta(double mediana, double p90) {
        return mediana >= MEDIANA_LENTA_MS || p90 >= P90_LENTO_MS;
    }

    private static void lento(String motivo) {
        ModoLigero.anotarLentitud(motivo);
        Consumer<String> oyente = alDetectarLentitud;
        if (oyente == null) return;
        if (Platform.isFxApplicationThread()) oyente.accept(motivo);
        else Platform.runLater(() -> oyente.accept(motivo));
    }

    private static long ramMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize() >> 20;
            }
        } catch (RuntimeException | LinkageError ignorada) {
            // sin dato
        }
        return -1;
    }
}
