package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Punto único para exportar una tabla (encabezados + filas de texto) a CSV,
 * Excel (.xlsx) o PDF. Los tres formatos neutralizan las fórmulas
 * (= + - @) igual que {@link ExportarCsv}.
 */
public final class ExportarTabla {

    public enum Formato {
        CSV("csv", "CSV (*.csv)"), XLSX("xlsx", "Excel (*.xlsx)"), PDF("pdf", "PDF (*.pdf)");

        private final String extension;
        private final String etiqueta;

        Formato(String extension, String etiqueta) { this.extension = extension; this.etiqueta = etiqueta; }

        public String extension() { return extension; }
        public String etiqueta() { return etiqueta; }

        /** Formato según la extensión del nombre de archivo (CSV si no se reconoce) */
        public static Formato dePath(Path p) {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".xlsx")) return XLSX;
            if (n.endsWith(".pdf")) return PDF;
            return CSV;
        }
    }

    private ExportarTabla() { }

    public static void escribir(Formato formato, Path destino, String titulo, List<String> encabezados,
                                List<List<String>> filas) throws IOException {
        switch (formato) {
            case XLSX -> ExportarXlsx.escribir(destino, titulo, encabezados, filas);
            case PDF  -> ExportarPdf.escribir(destino, titulo, encabezados, filas);
            default   -> ExportarCsv.escribir(destino, encabezados, filas);
        }
    }
}
