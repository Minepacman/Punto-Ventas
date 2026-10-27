package me.eliassanchezfernandez.puntodeventa.impresion;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Envía bytes crudos a una impresora del sistema operativo por javax.print
 * (DocFlavor.BYTE_ARRAY.AUTOSENSE). Funciona con cualquier impresora instalada,
 * USB, red o puerto serie, mientras el controlador deje pasar datos "RAW"
 * (en Windows, el controlador "Generic / Text Only" o el propio de la marca).
 */
public class EnvioJavaxPrint implements EnvioRaw {

    private static final long ESPERA_MAXIMA_SEGUNDOS = 15;

    private final String nombreImpresora;

    public EnvioJavaxPrint(String nombreImpresora) {
        this.nombreImpresora = nombreImpresora;
    }

    /** Nombres de las impresoras instaladas (puede tardar: llamar fuera del hilo de JavaFX) */
    public static List<String> listarImpresoras() {
        List<String> nombres = new ArrayList<>();
        try {
            for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) nombres.add(ps.getName());
        } catch (RuntimeException | LinkageError e) {
            // sin servicio de impresión en este equipo: lista vacía
        }
        return nombres;
    }

    static PrintService buscar(String nombre) {
        if (nombre == null || nombre.isBlank()) return null;
        try {
            for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) {
                if (ps.getName().equalsIgnoreCase(nombre.trim())) return ps;
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    @Override
    public void enviar(byte[] datos) throws ImpresionException {
        if (nombreImpresora == null || nombreImpresora.isBlank()) {
            throw new ImpresionException("No se ha elegido la impresora de tickets.");
        }
        PrintService servicio = buscar(nombreImpresora);
        if (servicio == null) {
            throw new ImpresionException("No se encontró la impresora \"" + nombreImpresora
                    + "\". Revisa que esté encendida, conectada e instalada.");
        }
        DocFlavor sabor = DocFlavor.BYTE_ARRAY.AUTOSENSE;
        if (!servicio.isDocFlavorSupported(sabor)) {
            throw new ImpresionException("La impresora \"" + nombreImpresora
                    + "\" no acepta datos directos (RAW). Instala el controlador del fabricante o el genérico \"Text Only\".");
        }
        CountDownLatch terminado = new CountDownLatch(1);
        AtomicReference<String> fallo = new AtomicReference<>();
        try {
            DocPrintJob trabajo = servicio.createPrintJob();
            trabajo.addPrintJobListener(new PrintJobAdapter() {
                @Override public void printJobCompleted(PrintJobEvent e)      { terminado.countDown(); }
                @Override public void printDataTransferCompleted(PrintJobEvent e) { terminado.countDown(); }
                @Override public void printJobNoMoreEvents(PrintJobEvent e)   { terminado.countDown(); }
                @Override public void printJobCanceled(PrintJobEvent e)       { fallo.set("El trabajo de impresión fue cancelado."); terminado.countDown(); }
                @Override public void printJobFailed(PrintJobEvent e)         {
                    fallo.set("La impresora rechazó el trabajo (¿sin papel, tapa abierta o apagada?).");
                    terminado.countDown();
                }
            });
            Doc doc = new SimpleDoc(datos, sabor, null);
            trabajo.print(doc, new HashPrintRequestAttributeSet());
            if (!terminado.await(ESPERA_MAXIMA_SEGUNDOS, TimeUnit.SECONDS)) {
                throw new ImpresionException("La impresora \"" + nombreImpresora + "\" no respondió a tiempo.");
            }
        } catch (PrintException e) {
            throw new ImpresionException("No se pudo imprimir en \"" + nombreImpresora + "\": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImpresionException("La impresión fue interrumpida.", e);
        } catch (RuntimeException | LinkageError e) {
            throw new ImpresionException("No se pudo imprimir en \"" + nombreImpresora + "\": " + e.getMessage(), e);
        }
        if (fallo.get() != null) throw new ImpresionException(fallo.get());
    }
}
