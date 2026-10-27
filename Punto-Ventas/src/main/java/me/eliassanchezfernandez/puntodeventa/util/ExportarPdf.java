package me.eliassanchezfernandez.puntodeventa.util;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Exportación a PDF con OpenPDF. Se prefirió a imprimir a una "impresora PDF"
 * (JavaFX PrinterJob) porque no depende de que el equipo tenga una, no abre
 * diálogos y se puede probar sin interfaz gráfica.
 *
 * Tabla con título, fecha de generación, encabezados en gris, filas alternadas,
 * importes alineados a la derecha y hoja horizontal si hay muchas columnas.
 * Como en CSV y Excel, el texto que empieza con = + - @ se neutraliza con una
 * comilla simple delante.
 */
public final class ExportarPdf {

    private static final Pattern NUMERICO = Pattern.compile("^[-+]?\\$?[0-9][0-9,]*(\\.[0-9]+)?%?$");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Color GRIS_ENCABEZADO = new Color(0xE5, 0xE7, 0xEB);
    private static final Color GRIS_FILA = new Color(0xF6, 0xF7, 0xF9);

    private ExportarPdf() { }

    public static void escribir(Path destino, String titulo, List<String> encabezados, List<List<String>> filas)
            throws IOException {
        boolean horizontal = encabezados.size() > 6;
        Document documento = new Document(horizontal ? PageSize.A4.rotate() : PageSize.A4, 36, 36, 36, 36);
        try (OutputStream salida = Files.newOutputStream(destino)) {
            PdfWriter.getInstance(documento, salida);
            documento.addTitle(titulo);
            documento.addCreator("Punto de Venta");
            documento.open();

            Font fTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            Font fSub = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
            Font fEnc = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
            Font fCel = FontFactory.getFont(FontFactory.HELVETICA, 8);

            documento.add(new Paragraph(titulo, fTitulo));
            Paragraph sub = new Paragraph("Generado el " + LocalDateTime.now().format(FECHA) + "  ·  " + filas.size() + " fila(s)", fSub);
            sub.setSpacingAfter(8);
            documento.add(sub);

            PdfPTable tabla = new PdfPTable(Math.max(1, encabezados.size()));
            tabla.setWidthPercentage(100);
            tabla.setHeaderRows(1);
            for (String e : encabezados) {
                PdfPCell c = new PdfPCell(new Phrase(ExportarCsv.neutralizarFormula(texto(e)), fEnc));
                c.setBackgroundColor(GRIS_ENCABEZADO);
                c.setPadding(4);
                c.setBorderColor(Color.LIGHT_GRAY);
                tabla.addCell(c);
            }
            int n = 0;
            for (List<String> fila : filas) {
                for (int i = 0; i < encabezados.size(); i++) {
                    String valor = texto(i < fila.size() ? fila.get(i) : "");
                    String mostrar = ExportarCsv.neutralizarFormula(valor);
                    PdfPCell c = new PdfPCell(new Phrase(mostrar, fCel));
                    c.setPadding(3);
                    c.setBorder(Rectangle.BOTTOM);
                    c.setBorderColor(Color.LIGHT_GRAY);
                    if (n % 2 == 1) c.setBackgroundColor(GRIS_FILA);
                    if (NUMERICO.matcher(valor.trim()).matches()) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    tabla.addCell(c);
                }
                n++;
            }
            documento.add(tabla);
            documento.close();      // escribe el final del PDF; debe ir antes de cerrar el archivo
        } catch (DocumentException e) {
            throw new IOException("No se pudo armar el PDF: " + e.getMessage(), e);
        }
    }

    private static String texto(String s) {
        return s == null ? "" : s;
    }
}
