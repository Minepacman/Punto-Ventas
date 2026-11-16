package me.eliassanchezfernandez.puntodeventa.ocr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TsvTesseractTest {

    private static final String CABECERA = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";

    private static String palabra(int bloque, int linea, int x, int y, int w, double conf, String texto) {
        return "5\t1\t" + bloque + "\t1\t" + linea + "\t1\t" + x + "\t" + y + "\t" + w + "\t20\t" + conf + "\t" + texto + "\n";
    }

    @Test
    void uneLaDescripcionYLosImportesQueTesseractPartioEnBloquesDistintos() {
        String tsv = CABECERA
                + "4\t1\t1\t1\t1\t0\t10\t100\t300\t20\t-1\t\n"
                + palabra(1, 1, 10, 100, 40, 95, "1")
                + palabra(1, 1, 60, 101, 120, 90, "ORBIT")
                + palabra(2, 1, 400, 98, 80, 80, "89.00")
                + palabra(1, 2, 10, 140, 40, 95, "1")
                + palabra(1, 2, 60, 141, 120, 70, "MAZAPAN")
                + palabra(2, 2, 400, 139, 80, 60, "109.00");
        List<Renglon> r = TsvTesseract.renglones(tsv, 2.0);
        assertThat(r).extracting(Renglon::texto).containsExactly("1 ORBIT 89.00", "1 MAZAPAN 109.00");
        assertThat(r.get(0).y()).isEqualTo(49);          // 98 / escala 2
        assertThat(r.get(0).confianza()).isBetween(0.8, 0.95);
    }

    @Test
    void ignoraPalabrasVaciasYConfianzaNegativaYFilasDanadas() {
        String tsv = CABECERA
                + palabra(1, 1, 10, 10, 40, -1, "x")
                + palabra(1, 1, 60, 10, 40, 90, " ")
                + "5\t1\t1\t1\t1\t1\tno\tes\tnumero\t1\t90\tmal\n"
                + palabra(1, 1, 100, 10, 40, 50, "TOTAL");
        List<Renglon> r = TsvTesseract.renglones(tsv, 1);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).texto()).isEqualTo("TOTAL");
        assertThat(r.get(0).confianza()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void lineasQueSeEncimanALoAnchoNoSeUnen() {
        String tsv = CABECERA
                + palabra(1, 1, 10, 100, 200, 90, "ARRIBA")
                + palabra(1, 2, 20, 108, 200, 90, "ABAJO");
        assertThat(TsvTesseract.renglones(tsv, 1)).hasSize(2);
    }

    @Test
    void resultadoYConfianzaMedia() {
        List<Renglon> r = List.of(new Renglon("AB", 1.0, 0), new Renglon("CDEF", 0.4, 10));
        assertThat(TsvTesseract.confianzaMedia(r)).isCloseTo((2 * 1.0 + 4 * 0.4) / 6, within(1e-9));
        assertThat(TsvTesseract.resultado(r).textoCompleto()).isEqualTo("AB\nCDEF");
        assertThat(TsvTesseract.confianzaMedia(List.of())).isZero();
        assertThat(TsvTesseract.renglones(null, 1)).isEmpty();
    }
}
