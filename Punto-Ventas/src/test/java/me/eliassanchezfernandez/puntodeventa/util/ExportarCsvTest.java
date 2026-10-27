package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CSV: separador coma, comillas solo cuando hace falta, UTF-8 con BOM. */
class ExportarCsvTest {

    @Test
    void generaLineasConEscapeCorrecto() {
        String csv = ExportarCsv.generar(List.of("Producto", "Piezas", "Importe"), List.of(
                List.of("Coca Cola 600 ml", "3", "$60.00"),
                List.of("Pan \"bimbo\", blanco", "1", "$40.00"),
                List.of(" con espacios ", "", "x")));
        assertThat(csv).isEqualTo("Producto,Piezas,Importe\r\n"
                + "Coca Cola 600 ml,3,$60.00\r\n"
                + "\"Pan \"\"bimbo\"\", blanco\",1,$40.00\r\n"
                + "\" con espacios \",,x\r\n");
        assertThat(ExportarCsv.escapar(null)).isEmpty();
        assertThat(ExportarCsv.escapar("salto\nde línea")).isEqualTo("\"salto\nde línea\"");
    }

    @Test
    void escribeElArchivoConBom() throws Exception {
        Path destino = Files.createTempFile("pos-csv-", ".csv");
        try {
            ExportarCsv.escribir(destino, List.of("Día", "Total"), List.of(List.of("Sábado", "1")));
            byte[] bytes = Files.readAllBytes(destino);
            assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
            assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
            assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
            assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).isEqualTo("Día,Total\r\nSábado,1\r\n");
        } finally {
            Files.deleteIfExists(destino);
        }
    }

    @Test
    void neutralizaFormulasPeroNoNumeros() {
        assertThat(ExportarCsv.escapar("=HYPERLINK(\"http://x\")")).startsWith("\"'=HYPERLINK");
        assertThat(ExportarCsv.escapar("+cmd|' /C calc'!A0")).startsWith("'+cmd");
        assertThat(ExportarCsv.escapar("-2+3")).isEqualTo("'-2+3");
        assertThat(ExportarCsv.escapar("@SUMA(A1)")).isEqualTo("'@SUMA(A1)");
        assertThat(ExportarCsv.escapar("\t=1+1")).isEqualTo("'\t=1+1");
        // números legítimos intactos
        assertThat(ExportarCsv.escapar("-5.25")).isEqualTo("-5.25");
        assertThat(ExportarCsv.escapar("+3")).isEqualTo("+3");
        assertThat(ExportarCsv.escapar("Coca-Cola")).isEqualTo("Coca-Cola");
        assertThat(ExportarCsv.generar(List.of("=a"), List.of(List.of("=b")))).isEqualTo("'=a\r\n'=b\r\n");
    }
}
