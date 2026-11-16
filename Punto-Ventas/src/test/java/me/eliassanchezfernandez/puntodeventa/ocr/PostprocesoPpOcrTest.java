package me.eliassanchezfernandez.puntodeventa.ocr;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PostprocesoPpOcrTest {

    @Test
    void cajasDeLaDeteccionConUnclipYPuntaje() {
        int w = 40, h = 20;
        float[] p = new float[w * h];
        // una "palabra" de 20×4 px con probabilidad 0.9, y ruido chico (2×2) que se descarta
        for (int y = 8; y < 12; y++) for (int x = 10; x < 30; x++) p[y * w + x] = 0.9f;
        p[2 * w + 2] = 0.9f; p[2 * w + 3] = 0.9f;
        List<PostprocesoPpOcr.Caja> c = PostprocesoPpOcr.cajas(p, w, h);
        assertThat(c).hasSize(1);
        PostprocesoPpOcr.Caja k = c.get(0);
        assertThat(k.x0()).isLessThan(10);
        assertThat(k.x1()).isGreaterThan(30);
        assertThat(k.y0()).isLessThan(8);
        assertThat(k.puntaje()).isGreaterThan(0.5);
        // con probabilidad baja (puntaje < 0.5) no hay caja
        for (int i = 0; i < p.length; i++) if (p[i] > 0) p[i] = 0.4f;
        assertThat(PostprocesoPpOcr.cajas(p, w, h)).isEmpty();
    }

    @Test
    void ctcQuitaVaciosYRepetidosYRespetaLasClasesPermitidas() {
        List<String> dic = List.of("1", "2", "长", " ");   // clases: 0 vacío, 1 "1", 2 "2", 3 "长", 4 " "
        int clases = 5;
        int[] argmax = {1, 1, 0, 1, 2, 3, 0};
        float[] s = new float[argmax.length * clases];
        for (int t = 0; t < argmax.length; t++) {
            s[t * clases + argmax[t]] = 0.9f;
            if (argmax[t] == 3) s[t * clases + 2] = 0.5f;           // segunda opción latina
        }
        assertThat(PostprocesoPpOcr.ctc(s, 0, argmax.length, clases, dic).texto()).isEqualTo("112长");
        boolean[] permitidas = PostprocesoPpOcr.clasesLatinas(dic);
        assertThat(permitidas).containsExactly(true, true, true, false, true);
        PostprocesoPpOcr.Texto t = PostprocesoPpOcr.ctc(s, 0, argmax.length, clases, dic, permitidas);
        assertThat(t.texto()).isEqualTo("112");                      // "2" seguido de "2" se junta (CTC)
        assertThat(t.confianza()).isCloseTo(0.9, within(1e-6));
    }

    @Test
    void renglonesPorAlturaDeIzquierdaADerecha() {
        List<Renglon> r = PostprocesoPpOcr.renglones(List.of(
                new PostprocesoPpOcr.Fragmento("89.00", 0.9, 300, 102, 360, 122),
                new PostprocesoPpOcr.Fragmento("1 ORBIT", 0.8, 10, 100, 120, 120),
                new PostprocesoPpOcr.Fragmento("TOTAL", 1.0, 10, 160, 80, 180),
                new PostprocesoPpOcr.Fragmento(" ", 1.0, 10, 200, 20, 220)), 2.0);
        assertThat(r).extracting(Renglon::texto).containsExactly("1 ORBIT 89.00", "TOTAL");
        assertThat(r.get(0).y()).isEqualTo(50);
    }

    @Test
    void conPapelOnduladoElImporteSeUneAlRenglonDeSuVecinoYNoAlDeAbajo() {
        // El importe del renglón 1 (derecha) baja 14 px por la onda del papel y queda a la altura del
        // renglón 2 (izquierda): por vecindad horizontal sigue siendo del renglón 1.
        List<Renglon> r = PostprocesoPpOcr.renglones(List.of(
                new PostprocesoPpOcr.Fragmento("1 300062064 2", 0.9, 10, 100, 200, 124),
                new PostprocesoPpOcr.Fragmento("$17.26", 0.9, 300, 108, 380, 132),
                new PostprocesoPpOcr.Fragmento("$ 34.52", 0.9, 420, 114, 520, 138),
                new PostprocesoPpOcr.Fragmento("* GIRO DTS NI RF 114GRX44X1", 0.9, 10, 126, 420, 150),
                new PostprocesoPpOcr.Fragmento("2 300062346 1", 0.9, 10, 152, 200, 176),
                new PostprocesoPpOcr.Fragmento("$17.26", 0.9, 300, 160, 380, 184),
                new PostprocesoPpOcr.Fragmento("$ 17.26", 0.9, 420, 166, 520, 190)), 1.0);
        assertThat(r).extracting(Renglon::texto).containsExactly(
                "1 300062064 2 $17.26 $ 34.52", "* GIRO DTS NI RF 114GRX44X1", "2 300062346 1 $17.26 $ 17.26");
    }

    @Test
    void fragmentosEncimadosALoAnchoNuncaSonElMismoRenglon() {
        List<Renglon> r = PostprocesoPpOcr.renglones(List.of(
                new PostprocesoPpOcr.Fragmento("SubTot", 0.9, 10, 100, 80, 120),
                new PostprocesoPpOcr.Fragmento("215.00", 0.9, 20, 112, 90, 132)), 1.0);
        assertThat(r).extracting(Renglon::texto).containsExactly("SubTot", "215.00");
    }

    @Test
    void recursosNoListosSinArchivosYVerificacionDeSha(@TempDir Path dir) throws IOException {
        RecursosPpOcr r = new RecursosPpOcr(dir);
        assertThat(r.listos()).isFalse();
        Path p = dir.resolve("x");
        Files.writeString(p, "hola");
        assertThat(RecursosPpOcr.coincide(p, RecursosPpOcr.DETECCION)).isFalse();
        assertThat(RecursosPpOcr.TODOS).allSatisfy(a -> {
            assertThat(a.url()).startsWith("https://");
            assertThat(a.sha256()).hasSize(64);
        });
    }

    /** De punta a punta con ONNX Runtime (en el classpath de pruebas) si se indican los modelos */
    @Test
    void paddleLeeElTicketDeMuestra() throws IOException {
        String dir = System.getenv("APP_OCR_PPOCR_MODELOS");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "Sin modelos de PaddleOCR: se omite");
        MotorOcr m = CargadorPpOcr.cargar(null, Path.of(dir, "PP-OCRv6_det_small.onnx"), Path.of(dir, "PP-OCRv6_rec_small.onnx"), 2);
        byte[] muestra;
        try (var in = PostprocesoPpOcrTest.class.getResourceAsStream(LectorTicketsService.TICKET_MUESTRA)) {
            muestra = in.readAllBytes();
        }
        String texto = m.leer(muestra).textoCompleto();
        assertThat(texto).contains("228.48").contains("TOTAL");
        if (m instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
}
