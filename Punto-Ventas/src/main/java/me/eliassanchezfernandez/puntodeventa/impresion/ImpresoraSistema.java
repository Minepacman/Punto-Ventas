package me.eliassanchezfernandez.puntodeventa.impresion;

import javafx.print.PageLayout;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Impresora normal (láser, inyección de tinta...) por JavaFX {@link PrinterJob}:
 * dibuja el texto del documento en monoespaciado, sin diálogo de impresión,
 * partiéndolo en páginas si no cabe. PrinterJob puede usarse fuera del hilo de
 * JavaFX siempre que los nodos no estén en una escena (aquí no lo están).
 * No soporta cajón de dinero.
 */
public class ImpresoraSistema implements ImpresoraTickets {

    private static final double TAMANO_FUENTE = 9;

    private final String nombreImpresora;
    private final int columnas;

    public ImpresoraSistema(String nombreImpresora, int columnas) {
        this.nombreImpresora = nombreImpresora;
        this.columnas = columnas;
    }

    @Override
    public void imprimir(DocumentoImpresion documento, int copias, boolean abrirCajon) throws ImpresionException {
        Printer impresora = elegir();
        PrinterJob trabajo = PrinterJob.createPrinterJob(impresora);
        if (trabajo == null) {
            throw new ImpresionException("No se pudo iniciar el trabajo de impresión en \"" + impresora.getName() + "\".");
        }
        try {
            PageLayout pagina = trabajo.getJobSettings().getPageLayout();
            Font fuente = Font.font("Monospaced", TAMANO_FUENTE);
            Text medida = new Text("Mg");
            medida.setFont(fuente);
            double altoLinea = medida.getBoundsInLocal().getHeight();
            int lineasPorPagina = Math.max(10, (int) Math.floor(pagina.getPrintableHeight() / Math.max(1, altoLinea)) - 1);

            List<String> lineas = List.of(documento.aTexto(columnas).split("\n", -1));
            List<VBox> paginas = new ArrayList<>();
            for (int i = 0; i < lineas.size(); i += lineasPorPagina) {
                VBox caja = new VBox();
                for (String l : lineas.subList(i, Math.min(lineas.size(), i + lineasPorPagina))) {
                    Text t = new Text(l.isEmpty() ? " " : l);
                    t.setFont(fuente);
                    caja.getChildren().add(t);
                }
                paginas.add(caja);
            }
            trabajo.getJobSettings().setCopies(Math.max(1, copias));
            for (VBox p : paginas) {
                if (!trabajo.printPage(p)) {
                    trabajo.cancelJob();
                    throw new ImpresionException("La impresora \"" + impresora.getName() + "\" no pudo imprimir la página.");
                }
            }
            if (!trabajo.endJob()) {
                throw new ImpresionException("La impresora \"" + impresora.getName() + "\" no terminó el trabajo.");
            }
        } catch (RuntimeException | LinkageError e) {
            trabajo.cancelJob();
            throw new ImpresionException("No se pudo imprimir: " + e.getMessage(), e);
        }
    }

    private Printer elegir() throws ImpresionException {
        Printer elegida = null;
        if (nombreImpresora != null && !nombreImpresora.isBlank()) {
            for (Printer p : Printer.getAllPrinters()) {
                if (p.getName().equalsIgnoreCase(nombreImpresora.trim())) { elegida = p; break; }
            }
            if (elegida == null) {
                throw new ImpresionException("No se encontró la impresora \"" + nombreImpresora + "\".");
            }
        } else {
            elegida = Printer.getDefaultPrinter();
        }
        if (elegida == null) throw new ImpresionException("Este equipo no tiene impresoras instaladas.");
        return elegida;
    }

    @Override
    public void abrirCajon() throws ImpresionException {
        throw new ImpresionException("El cajón de dinero solo se abre con una impresora de tickets ESC/POS.");
    }

    @Override public boolean soportaCajon() { return false; }

    @Override public String descripcion() { return "Impresora del sistema · " + (nombreImpresora == null ? "predeterminada" : nombreImpresora); }
}
