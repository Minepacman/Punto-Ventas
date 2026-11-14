package me.eliassanchezfernandez.puntodeventa.importacion;

import me.eliassanchezfernandez.puntodeventa.util.ExportarXlsx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lectores de CSV (codificación, separador, comillas), XLSX (propio, StAX) y XLS (BIFF8 propio) */
class LectoresTest {

    static final Path RECURSOS = Path.of("src/test/resources/importacion");

    @TempDir Path tmp;

    // ── CSV ──────────────────────────────────────────────────────────────

    @Test
    void csvDeExcelEnEspanolConPuntoYComaYWindows1252() throws IOException {
        TablaImportada t = LectorArchivo.leer(RECURSOS.resolve("eleventa-export.csv"));
        assertThat(t.detalle()).contains("punto y coma").contains("Windows-1252");
        assertThat(t.columnas()).isEqualTo(15);
        List<String> enc = t.encabezados(true);
        assertThat(enc.get(0)).isEqualTo("Código");
        assertThat(enc.get(7)).isEqualTo("Inv. Mínimo");
        assertThat(t.datos(true)).hasSize(4);
        assertThat(t.datos(true).get(3).get(1)).isEqualTo("Azúcar estándar 1 kg");
        assertThat(t.datos(true).get(0).get(2)).isEqualTo("$12.50");
    }

    @Test
    void csvConBomUtf8ComasYCamposEntrecomillados() throws IOException {
        TablaImportada t = LectorArchivo.leer(RECURSOS.resolve("abarrotes-pdv.csv"));
        assertThat(t.detalle()).contains("coma").contains("UTF-8 con BOM");
        assertThat(t.encabezados(true).get(0)).as("el BOM no debe quedar pegado al primer encabezado").isEqualTo("Codigo");
        List<String> ultima = t.datos(true).get(4);
        assertThat(ultima.get(0)).isEqualTo("7501055363506");
        assertThat(ultima.get(1)).isEqualTo("Jugo Del Valle, Naranja 1 L");
        assertThat(t.datos(true).get(2).get(7)).isEqualTo("Lácteos");
    }

    @Test
    void csvConTabuladorYSinBom() throws IOException {
        TablaImportada t = LectorArchivo.leer(RECURSOS.resolve("sicar.csv"));
        assertThat(t.detalle()).contains("tabulador").contains("UTF-8");
        assertThat(t.columnas()).isEqualTo(21);
        assertThat(t.encabezados(true).get(2)).isEqualTo("Descripción");
        assertThat(t.datos(true).get(1).get(14)).isEqualTo("15.250");
    }

    @Test
    void csvConSaltoDeLineaDentroDeComillas() throws IOException {
        TablaImportada t = LectorArchivo.leer(RECURSOS.resolve("loyverse.csv"));
        assertThat(t.datos(true)).hasSize(3);
        assertThat(t.datos(true).get(1).get(4)).isEqualTo("Jitomate por kilo\nde la central");
        assertThat(t.datos(true).get(0).get(4)).isEqualTo("Refresco de cola, botella PET");
    }

    @Test
    void csvUtf16ConBomYComillasEscapadas() {
        String texto = "Codigo,Nombre\r\n1,\"Dice \"\"hola\"\"\"\r\n2,Ñandú\r\n";
        byte[] cuerpo = texto.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[cuerpo.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(cuerpo, 0, bytes, 2, cuerpo.length);
        LectorCsv.Decodificado d = LectorCsv.decodificar(bytes);
        assertThat(d.codificacion()).isEqualTo("UTF-16");
        TablaImportada t = LectorCsv.leerTexto("x.csv", d.texto(), d.codificacion());
        assertThat(t.datos(true).get(0).get(1)).isEqualTo("Dice \"hola\"");
        assertThat(t.datos(true).get(1).get(1)).isEqualTo("Ñandú");
    }

    @Test
    void detectaElSeparadorAunqueHayaComasDentroDeComillas() {
        assertThat(LectorCsv.detectarSeparador("a;b;c\n\"1,5\";\"2,5\";x\n")).isEqualTo(';');
        assertThat(LectorCsv.detectarSeparador("a,b,c\n1,2,3\n")).isEqualTo(',');
        assertThat(LectorCsv.detectarSeparador("a|b|c\n1|2|3\n")).isEqualTo('|');
        assertThat(LectorCsv.detectarSeparador("a\tb\n1\t2\n")).isEqualTo('\t');
    }

    @Test
    void bytesInvalidosEnUtf8CaenAWindows1252() {
        byte[] latin = "Jamón serrano".getBytes(Charset.forName("windows-1252"));
        LectorCsv.Decodificado d = LectorCsv.decodificar(latin);
        assertThat(d.texto()).isEqualTo("Jamón serrano");
        assertThat(d.codificacion()).contains("Windows-1252");
        LectorCsv.Decodificado u = LectorCsv.decodificar("Jamón".getBytes(StandardCharsets.UTF_8));
        assertThat(u.texto()).isEqualTo("Jamón");
        assertThat(u.codificacion()).isEqualTo("UTF-8");
    }

    @Test
    void filasVaciasSeDescartanYLasCortasSeRellenan() {
        TablaImportada t = LectorCsv.leerTexto("x.csv", "a,b,c\n1,2\n,,\n\n4,5,6,7\n", "UTF-8");
        assertThat(t.columnas()).isEqualTo(4);
        assertThat(t.filas()).hasSize(3);
        assertThat(t.filas().get(1)).containsExactly("1", "2", "", "");
        assertThat(t.encabezados(true).get(3)).isEqualTo("Columna D");
        assertThat(TablaImportada.letraColumna(26)).isEqualTo("AA");
    }

    // ── XLSX ─────────────────────────────────────────────────────────────

    @Test
    void xlsxEscritoPorElPropioExportadorSeLeeDeVuelta() throws IOException {
        Path f = tmp.resolve("catalogo.xlsx");
        ExportarXlsx.escribir(f, "Productos", List.of("Código", "Descripción", "Precio"),
                List.of(List.of("7501055300136", "Coca-Cola 600 ml", "18.00"), List.of("2001", "Jitomate", "32.50")));
        TablaImportada t = LectorArchivo.leer(f);
        assertThat(t.detalle()).contains("Excel").contains("Productos");
        assertThat(t.encabezados(true)).containsExactly("Código", "Descripción", "Precio");
        assertThat(t.datos(true).get(0)).containsExactly("7501055300136", "Coca-Cola 600 ml", "18");
        assertThat(t.datos(true).get(1).get(2)).isEqualTo("32.5");
    }

    @Test
    void xlsxArmadoAManoConCadenasCompartidasNumerosEnNotacionCientificaYBooleanos() throws IOException {
        Path f = tmp.resolve("mano.xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(f))) {
            entrada(zip, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types/>");
            entrada(zip, "xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                    + "<sheet name=\"Vacía\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Catálogo\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>");
            entrada(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"x\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"x\" Target=\"/xl/worksheets/hoja2.xml\"/></Relationships>");
            entrada(zip, "xl/sharedStrings.xml", "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"3\" uniqueCount=\"3\">"
                    + "<si><t>Código</t></si><si><r><rPr><b/></rPr><t>Descrip</t></r><r><t>ción</t></r></si><si><t xml:space=\"preserve\">Jamón  </t></si></sst>");
            entrada(zip, "xl/worksheets/sheet1.xml", "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>");
            entrada(zip, "xl/worksheets/hoja2.xml", "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"inlineStr\"><is><t>Activo</t></is></c></row>"
                    + "<row r=\"3\"><c r=\"A3\"><v>7.501055300136E12</v></c><c r=\"B3\" t=\"s\"><v>2</v></c><c r=\"C3\" t=\"b\"><v>1</v></c><c r=\"E3\"><v>12.50</v></c></row>"
                    + "<row r=\"4\"><c r=\"A4\" t=\"str\"><v>ABC</v></c><c r=\"B4\" t=\"e\"><v>#N/A</v></c><c r=\"C4\"/></row>"
                    + "</sheetData></worksheet>");
        }
        TablaImportada t = LectorArchivo.leer(f);
        assertThat(t.detalle()).as("la primera hoja está vacía: se toma la siguiente con datos").contains("Catálogo");
        assertThat(t.columnas()).isEqualTo(5);
        assertThat(t.filas().get(0)).containsExactly("Código", "Descripción", "Activo", "", "");
        assertThat(t.filas().get(1)).containsExactly("7501055300136", "Jamón  ", "Sí", "", "12.5");
        assertThat(t.filas().get(2)).containsExactly("ABC", "", "", "", "");
    }

    @Test
    void xlsxSeReconocePorContenidoAunqueTengaOtraExtension() throws IOException {
        Path f = tmp.resolve("renombrado.csv");
        ExportarXlsx.escribir(f, "Hoja", List.of("A"), List.of(List.of("1")));
        assertThat(LectorArchivo.leer(f).detalle()).contains("Excel");
    }

    @Test
    void numerosDeExcelSeEscribenSinNotacionCientifica() {
        assertThat(LectorXlsx.numeroATexto("7.501E12")).isEqualTo("7501000000000");
        assertThat(LectorXlsx.numeroATexto("12.50")).isEqualTo("12.5");
        assertThat(LectorXlsx.numeroATexto("3.0")).isEqualTo("3");
        assertThat(LectorXlsx.numeroATexto("0.1")).isEqualTo("0.1");
        assertThat(LectorXlsx.numeroATexto("texto")).isEqualTo("texto");
        assertThat(LectorXlsx.indiceColumna("A1")).isZero();
        assertThat(LectorXlsx.indiceColumna("AB12")).isEqualTo(27);
    }

    // ── XLS (BIFF8) ──────────────────────────────────────────────────────

    @Test
    void xlsRealDeLaPlantillaOficialDeEleventa() throws IOException {
        TablaImportada t = LectorArchivo.leer(RECURSOS.resolve("eleventa-plantilla.xls"));
        assertThat(t.detalle()).contains("Excel 97-2003").contains("prueba_excel");
        assertThat(t.columnas()).isEqualTo(14);
        assertThat(t.encabezados(true)).startsWith("Codigo", "Descripcion", "Precio Costo", "Precio Venta", "Precio Mayoreo",
                "Departamento", "Existencia", "Inv. Minimo", "Inv. Máximo", "Tipo de Venta");
        assertThat(t.datos(true)).hasSize(2);
        assertThat(t.datos(true).get(0)).containsExactly("PRUEBA01", "Producto de Prueba", "10", "15", "12.5", "Prueba", "10", "3", "10",
                "UNIDAD", "16", "0", "01010101", "EA");
        assertThat(t.datos(true).get(1).get(9)).isEqualTo("GRANEL");
        assertThat(Origen.detectar(t.encabezados(true))).isEqualTo(Origen.ELEVENTA);
    }

    @Test
    void xlsSinteticoConSstPartidaEnContinueCadenasAnchasRkYMulrk() throws IOException {
        String largo = "Descripción muy larga con acentos áéíóú y eñes ñÑ para forzar que la tabla de cadenas se parta ".repeat(3);
        Object[][] filas = {
                {"Código", "Descripción", "Costo", "Precio", "Existencia", "Activo"},
                {"7501055300136", largo, 12.5, 18.0, 24.0, Boolean.TRUE},
                {"2001", "Jitomate 日本", 18.0, 32.0, 7.5, Boolean.FALSE},
                {"3", "Tres", 1.0, 2.0, 3.0, null},
                {"4", "Cuatro", 1234567.891, 0.07, 100000.0, null},
        };
        for (boolean continuar : new boolean[] {false, true}) {
            Path f = tmp.resolve("sintetico-" + continuar + ".xls");
            Files.write(f, BiffPrueba.libro("Artículos", filas, continuar));
            TablaImportada t = LectorArchivo.leer(f);
            assertThat(t.detalle()).contains("Excel 97-2003").contains("Artículos");
            assertThat(t.filas()).hasSize(5);
            assertThat(t.filas().get(0)).containsExactly("Código", "Descripción", "Costo", "Precio", "Existencia", "Activo");
            assertThat(t.filas().get(1)).containsExactly("7501055300136", largo, "12.5", "18", "24", "Sí");
            assertThat(t.filas().get(2)).containsExactly("2001", "Jitomate 日本", "18", "32", "7.5", "No");
            assertThat(t.filas().get(3)).containsExactly("3", "Tres", "1", "2", "3", "");
            assertThat(t.filas().get(4)).containsExactly("4", "Cuatro", "1234567.891", "0.07", "100000", "");
        }
    }

    @Test
    void xlsChicoEnMiniStreamYGrandeEnFlujoNormal() throws IOException {
        Object[][] chico = {{"A", "B"}, {"uno", 1.0}};
        Path f1 = tmp.resolve("chico.xls");
        Files.write(f1, BiffPrueba.libro("Hoja1", chico, false));
        assertThat(Files.size(f1)).isLessThan(4096 + 2048);
        assertThat(LectorArchivo.leer(f1).filas().get(1)).containsExactly("uno", "1");

        Object[][] grande = new Object[400][];
        for (int i = 0; i < grande.length; i++) grande[i] = new Object[] {"código " + i, "producto número " + i, (double) i};
        Path f2 = tmp.resolve("grande.xls");
        Files.write(f2, BiffPrueba.libro("Hoja1", grande, false));
        TablaImportada t = LectorArchivo.leer(f2);
        assertThat(t.filas()).hasSize(400);
        assertThat(t.filas().get(399)).containsExactly("código 399", "producto número 399", "399");
    }

    @Test
    void xlsMalFormadoYArchivosQueNoSonExcelDanMensajeClaro() throws IOException {
        Path falso = tmp.resolve("falso.xls");
        Files.write(falso, "<html><table><tr><td>1</td></tr></table></html>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> LectorArchivo.leer(falso)).isInstanceOf(IOException.class).hasMessageContaining("HTML");

        Path roto = tmp.resolve("roto.xls");
        byte[] bytes = new byte[600];
        bytes[0] = (byte) 0xD0; bytes[1] = (byte) 0xCF; bytes[2] = 0x11; bytes[3] = (byte) 0xE0;
        Files.write(roto, bytes);
        assertThatThrownBy(() -> LectorArchivo.leer(roto)).isInstanceOf(IOException.class);

        assertThatThrownBy(() -> LectorArchivo.leer(tmp.resolve("no-existe.csv"))).isInstanceOf(IOException.class)
                .hasMessageContaining("No se encontró");
    }

    @Test
    void decodificaNumerosRk() {
        assertThat(LectorXls.rk((1234 << 2) | 0x02)).isEqualTo(1234);
        assertThat(LectorXls.rk((1250 << 2) | 0x03)).isEqualTo(12.5);
        assertThat(LectorXls.rk((-7 << 2) | 0x02)).isEqualTo(-7);
        int bitsAltos = (int) (Double.doubleToLongBits(0.5) >>> 32);
        assertThat(LectorXls.rk(bitsAltos & 0xFFFFFFFC)).isEqualTo(0.5);
    }

    private static void entrada(ZipOutputStream zip, String nombre, String contenido) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(contenido.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
