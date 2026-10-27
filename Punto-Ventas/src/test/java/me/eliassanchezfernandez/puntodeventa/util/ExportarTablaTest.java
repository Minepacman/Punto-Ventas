package me.eliassanchezfernandez.puntodeventa.util;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import me.eliassanchezfernandez.puntodeventa.util.ExportarTabla.Formato;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** Exportar tablas a Excel y PDF: el archivo se crea, trae los datos y las fórmulas quedan neutralizadas. */
class ExportarTablaTest {

    private static final List<String> ENCABEZADOS = List.of("Código", "Producto", "Importe");

    private static List<List<String>> filas() {
        return List.of(
                List.of("0012345", "Pan de caja", "$1,234.50"),
                List.of("0000001", "=HYPERLINK(\"http://malo\")", "-5.25"),
                List.of("0000002", "+cmd|' /C calc'!A0", "10"),
                List.of("0000003", "@SUM(1+1)", "3.5"),
                List.of("0000004", "Ñandú con acentos áéíóú", "$0.00"));
    }

    private static String leer(Path xlsx, String entrada) throws IOException {
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            ZipEntry e = zip.getEntry(entrada);
            assertThat(e).as("entrada " + entrada).isNotNull();
            return new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Textos de xl/sharedStrings.xml ya decodificados (el XML escapa acentos y comillas) */
    private static List<String> textosCompartidos(Path xlsx) throws Exception {
        String xml = leer(xlsx, "xl/sharedStrings.xml");
        var doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var nodos = doc.getElementsByTagName("t");
        List<String> r = new ArrayList<>();
        for (int i = 0; i < nodos.getLength(); i++) r.add(nodos.item(i).getTextContent());
        return r;
    }

    // ── Excel ─────────────────────────────────────────────────────────────

    @Test
    void elXlsxSeCreaComoUnLibroValidoConLosDatos(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("ventas.xlsx");

        ExportarXlsx.escribir(archivo, "Ventas por cajero", ENCABEZADOS, filas());

        assertThat(archivo).exists();
        assertThat(Files.size(archivo)).isPositive();
        try (ZipFile zip = new ZipFile(archivo.toFile())) {
            assertThat(zip.getEntry("[Content_Types].xml")).isNotNull();
            assertThat(zip.getEntry("xl/workbook.xml")).isNotNull();
            assertThat(zip.getEntry("xl/worksheets/sheet1.xml")).isNotNull();
        }
        String libro = leer(archivo, "xl/workbook.xml");
        assertThat(libro).contains("Ventas por cajero");
        assertThat(textosCompartidos(archivo)).contains("Código", "Producto", "Importe", "Pan de caja",
                "Ñandú con acentos áéíóú");
    }

    @Test
    void elXlsxNeutralizaLasFormulasComoElCsv(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("formulas.xlsx");
        ExportarXlsx.escribir(archivo, "Datos", ENCABEZADOS, filas());

        List<String> textos = textosCompartidos(archivo);
        String hoja = leer(archivo, "xl/worksheets/sheet1.xml");

        // Se guardan como texto con comilla delante...
        assertThat(textos).contains("'=HYPERLINK(\"http://malo\")", "'+cmd|' /C calc'!A0", "'@SUM(1+1)");
        // ...y jamás como texto que empiece con el signo ni como fórmula de la hoja
        assertThat(textos).noneMatch(t -> t.startsWith("=") || t.startsWith("+") || t.startsWith("@"));
        assertThat(hoja).doesNotContain("<f>").doesNotContain("<f ");
    }

    @Test
    void losImportesSonNumerosYLosCodigosDeBarrasSiguenSiendoTexto(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("numeros.xlsx");
        ExportarXlsx.escribir(archivo, "Datos", ENCABEZADOS, filas());

        String hoja = leer(archivo, "xl/worksheets/sheet1.xml");
        assertThat(hoja).contains("1234.5").contains("-5.25").contains("3.5");     // numéricos para sumar
        assertThat(textosCompartidos(archivo)).contains("0012345", "0000001");     // ceros a la izquierda conservados
        assertThat(ExportarXlsx.esNumero("$1,234.50")).isTrue();
        assertThat(ExportarXlsx.esNumero("7501234567890")).isFalse();
        assertThat(ExportarXlsx.esNumero("12abc")).isFalse();
    }

    @Test
    void sinFilasONombreDeHojaInvalidoTampocoFalla(@TempDir Path dir) throws IOException {
        Path vacio = dir.resolve("vacio.xlsx");
        ExportarXlsx.escribir(vacio, "Ventas: 2026/10 [prueba]?", ENCABEZADOS, List.of());
        assertThat(vacio).exists();
        assertThat(ExportarXlsx.nombreHojaValido("a".repeat(50))).hasSize(31);
        assertThat(ExportarXlsx.nombreHojaValido("x:y/z")).doesNotContain(":", "/");
        assertThat(ExportarXlsx.nombreHojaValido(null)).isEqualTo("Datos");
    }

    @Test
    void unaTablaGrandeSeEscribeSinProblema(@TempDir Path dir) throws IOException {
        List<List<String>> muchas = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) muchas.add(List.of("C" + i, "Producto " + i, String.format("%d.%02d", i, i % 100)));
        Path archivo = dir.resolve("grande.xlsx");
        ExportarXlsx.escribir(archivo, "Grande", ENCABEZADOS, muchas);
        assertThat(Files.size(archivo)).isGreaterThan(10_000);
    }

    // ── PDF ───────────────────────────────────────────────────────────────

    @Test
    void elPdfSeCreaYTraeEncabezadosYDatos(@TempDir Path dir) throws IOException {
        Path archivo = dir.resolve("ventas.pdf");

        ExportarPdf.escribir(archivo, "Ventas por cajero — Hoy", ENCABEZADOS, filas());

        assertThat(archivo).exists();
        assertThat(Files.readAllBytes(archivo)).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        String texto = textoDe(archivo);
        assertThat(texto).contains("Ventas por cajero").contains("Código").contains("Producto").contains("Importe")
                .contains("Pan de caja").contains("$1,234.50");
    }

    @Test
    void elPdfNeutralizaLasFormulas(@TempDir Path dir) throws IOException {
        Path archivo = dir.resolve("formulas.pdf");
        ExportarPdf.escribir(archivo, "Prueba", ENCABEZADOS, filas());

        String texto = textoDe(archivo);
        assertThat(texto).contains("'=HYPERLINK").contains("'+cmd").contains("'@SUM");
        assertThat(texto).doesNotContain(" =HYPERLINK").doesNotContain("\n=HYPERLINK");
    }

    @Test
    void elPdfAceptaMuchasColumnasYCeroFilas(@TempDir Path dir) throws IOException {
        List<String> encabezados = List.of("A", "B", "C", "D", "E", "F", "G", "H");
        Path ancho = dir.resolve("ancho.pdf");
        ExportarPdf.escribir(ancho, "Ancha", encabezados, List.of(List.of("1", "2", "3", "4", "5", "6", "7", "8")));
        assertThat(ancho).exists();
        Path vacio = dir.resolve("vacio.pdf");
        ExportarPdf.escribir(vacio, "Vacía", ENCABEZADOS, List.of());
        assertThat(Files.size(vacio)).isPositive();
    }

    private static String textoDe(Path pdf) throws IOException {
        PdfReader lector = new PdfReader(Files.readAllBytes(pdf));
        try {
            StringBuilder sb = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(lector);
            for (int i = 1; i <= lector.getNumberOfPages(); i++) sb.append(extractor.getTextFromPage(i)).append('\n');
            return sb.toString();
        } finally {
            lector.close();
        }
    }

    // ── Despachador y CSV ────────────────────────────────────────────────

    @Test
    void elDespachadorEligeElFormatoYElCsvSigueNeutralizando(@TempDir Path dir) throws IOException {
        for (Formato f : Formato.values()) {
            Path archivo = dir.resolve("t." + f.extension());
            ExportarTabla.escribir(f, archivo, "Prueba", ENCABEZADOS, filas());
            assertThat(archivo).exists();
            assertThat(Formato.dePath(archivo)).isEqualTo(f);
        }
        String csv = Files.readString(dir.resolve("t.csv"), StandardCharsets.UTF_8);
        assertThat(csv).contains("'=HYPERLINK").contains("'+cmd").contains("'@SUM").contains("-5.25");
        assertThat(Formato.dePath(Path.of("sin-extension"))).isEqualTo(Formato.CSV);
    }
}
