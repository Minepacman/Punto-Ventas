package me.eliassanchezfernandez.puntodeventa.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.util.ExportarTabla.Formato;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Exportar la tabla que se está viendo (o unas filas ya armadas) a CSV, Excel o PDF
 * con el cuadro "Guardar como". Lo que se ve es lo que se exporta: encabezados y
 * valores tal como los muestran las columnas. El archivo se escribe en un hilo
 * aparte y el resultado se avisa en el de JavaFX.
 */
public final class ExportadorTablas {

    private ExportadorTablas() { }

    /** Exporta las columnas visibles y TODAS las filas de la tabla */
    public static <T> void exportar(TableView<T> tabla, String nombreBase, String titulo) {
        if (tabla.getItems().isEmpty()) {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Exportar", "No hay filas que exportar.");
            return;
        }
        List<String> encabezados = new ArrayList<>();
        List<TableColumn<T, ?>> columnas = new ArrayList<>();
        for (TableColumn<T, ?> c : tabla.getColumns()) {
            if (!c.isVisible()) continue;
            columnas.add(c);
            encabezados.add(c.getText());
        }
        List<List<String>> filas = new ArrayList<>();
        for (T item : tabla.getItems()) {
            List<String> fila = new ArrayList<>();
            for (TableColumn<T, ?> c : columnas) {
                Object v = c.getCellObservableValue(item) != null ? c.getCellObservableValue(item).getValue() : null;
                fila.add(v == null ? "" : v.toString());
            }
            filas.add(fila);
        }
        exportar(tabla.getScene() != null ? tabla.getScene().getWindow() : null, null, nombreBase, titulo, encabezados, filas);
    }

    /**
     * @param formatoFijo si no es null solo se ofrece ese formato; si es null, los tres
     */
    public static void exportar(Window owner, Formato formatoFijo, String nombreBase, String titulo,
                                List<String> encabezados, List<List<String>> filas) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Exportar");
        fc.setInitialFileName(nombreBase + "-" + LocalDate.now() + "." + (formatoFijo != null ? formatoFijo.extension() : "xlsx"));
        FileChooser.ExtensionFilter filtroCsv = filtro(Formato.CSV);
        FileChooser.ExtensionFilter filtroXlsx = filtro(Formato.XLSX);
        FileChooser.ExtensionFilter filtroPdf = filtro(Formato.PDF);
        if (formatoFijo == null) {
            fc.getExtensionFilters().addAll(filtroXlsx, filtroPdf, filtroCsv);
        } else {
            fc.getExtensionFilters().add(filtro(formatoFijo));
        }
        File elegido = fc.showSaveDialog(owner);
        if (elegido == null) return;

        // El formato lo manda la extensión escrita; si falta, el filtro elegido
        Formato formato = formatoFijo;
        if (formato == null) {
            String nombre = elegido.getName().toLowerCase();
            if (nombre.endsWith(".xlsx")) formato = Formato.XLSX;
            else if (nombre.endsWith(".pdf")) formato = Formato.PDF;
            else if (nombre.endsWith(".csv")) formato = Formato.CSV;
            else {
                FileChooser.ExtensionFilter sel = fc.getSelectedExtensionFilter();
                formato = sel == filtroPdf ? Formato.PDF : sel == filtroCsv ? Formato.CSV : Formato.XLSX;
            }
        }
        Path destino = elegido.toPath();
        if (!destino.getFileName().toString().toLowerCase().endsWith("." + formato.extension())) {
            destino = destino.resolveSibling(destino.getFileName() + "." + formato.extension());
        }
        final Path destinoFinal = destino;
        final Formato formatoFinal = formato;
        Thread t = new Thread(() -> {
            String error = null;
            try {
                ExportarTabla.escribir(formatoFinal, destinoFinal, titulo, encabezados, filas);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String fallo = error;
            Platform.runLater(() -> {
                if (fallo == null) {
                    Ventanas.alerta(Alert.AlertType.INFORMATION, "Exportar",
                            "Se exportaron " + filas.size() + " filas a:\n" + destinoFinal);
                } else {
                    Ventanas.alerta(Alert.AlertType.ERROR, "Exportar", "No se pudo escribir el archivo: " + fallo);
                }
            });
        }, "exportar-tabla");
        t.setDaemon(true);
        t.start();
    }

    private static FileChooser.ExtensionFilter filtro(Formato f) {
        return new FileChooser.ExtensionFilter(f.etiqueta(), "*." + f.extension());
    }
}
