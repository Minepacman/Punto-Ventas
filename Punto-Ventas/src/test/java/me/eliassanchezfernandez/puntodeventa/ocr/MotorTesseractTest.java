package me.eliassanchezfernandez.puntodeventa.ocr;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lectura de punta a punta con el Tesseract instalado. Si no hay Tesseract en esta computadora
 * (p. ej. en el CI) las pruebas que lo necesitan se omiten limpiamente.
 */
class MotorTesseractTest {

    private static Optional<LocalizadorTesseract.Ubicacion> tesseract() {
        Optional<LocalizadorTesseract.Ubicacion> u = LocalizadorTesseract.buscar();
        Assumptions.assumeTrue(u.isPresent(), "Tesseract no está instalado: se omite");
        return u;
    }

    @Test
    void pideLaSalidaTsvComoVariableYNoComoConfig() {
        // Sin depender de tessdata/configs/tsv (un instalador sin ese archivo daba texto plano)
        LocalizadorTesseract.Ubicacion u = new LocalizadorTesseract.Ubicacion(Path.of("/usr/bin/tesseract"), null, "prueba", "5.5.4", "spa+eng");
        List<String> cmd = new MotorTesseract(u).comando();
        assertThat(cmd).containsSequence("-c", "tessedit_create_tsv=1").containsSequence("stdin", "stdout");
        assertThat(cmd).doesNotContain("tsv");
    }

    @Test
    void leeUnTicketSinteticoGiradoYTorcido() throws IOException {
        MotorTesseract motor = new MotorTesseract(tesseract().get());
        byte[] foto = PreprocesadorTicketTest.jpeg(PreprocesadorTicketTest.girar(PreprocesadorTicketTest.ticket(20), 90 + 2));
        MotorTesseract.Lectura l = motor.leerConDetalle(foto);
        String texto = l.resultado().textoCompleto();
        assertThat(texto).contains("126.00").contains("637.50").contains("MAZAPAN");
        assertThat(l.resultado().renglones()).hasSizeGreaterThanOrEqualTo(8);
        // en orden vertical y con confianza 0..1
        List<Renglon> r = l.resultado().renglones();
        for (int i = 1; i < r.size(); i++) assertThat(r.get(i).y()).isGreaterThanOrEqualTo(r.get(i - 1).y());
        assertThat(r).allSatisfy(x -> assertThat(x.confianza()).isBetween(0.0, 1.0));
        assertThat(motor.nombre()).startsWith("Tesseract ");
    }

    @Test
    void elLimiteDeTiempoMataElProceso() {
        MotorTesseract motor = new MotorTesseract(tesseract().get(), MotorTesseract.PREDETERMINADOS.conLimite(Duration.ofMillis(1)));
        assertThatThrownBy(() -> motor.leer(PreprocesadorTicketTest.jpeg(PreprocesadorTicketTest.ticket(20))))
                .isInstanceOf(IOException.class).hasMessageContaining("se canceló");
    }

    @Test
    void interrumpirLaHebraCancelaLaLectura() throws Exception {
        MotorTesseract motor = new MotorTesseract(tesseract().get());
        byte[] foto = PreprocesadorTicketTest.jpeg(PreprocesadorTicketTest.ticket(20));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                motor.leer(foto);
            } catch (Throwable e) {
                error.set(e);
            }
        });
        t.start();
        t.join(30_000);
        assertThat(error.get()).isInstanceOf(InterruptedIOException.class);
    }

    @Test
    void candidatosEnOrden(@TempDir Path dir) throws IOException {
        Path app = dir.resolve("app");
        Path propio = dir.resolve("mio").resolve("tesseract");
        Files.createDirectories(propio.getParent().resolve("tessdata"));
        List<LocalizadorTesseract.Candidato> c = LocalizadorTesseract.candidatos(propio.toString(),
                Map.of("PATH", "/no/existe"), app, false, "tesseract");
        assertThat(c.get(0).ejecutable()).isEqualTo(propio);
        assertThat(c.get(0).tessdata()).isEqualTo(propio.getParent().resolve("tessdata"));
        assertThat(c.get(1).ejecutable()).isEqualTo(app.resolve("ocr/tesseract/bin/tesseract"));
        assertThat(c.get(1).tessdata()).isEqualTo(app.resolve("ocr/tesseract/tessdata"));
        assertThat(c).extracting(x -> x.ejecutable().toString()).contains("/opt/homebrew/bin/tesseract", "/no/existe/tesseract");

        List<LocalizadorTesseract.Candidato> w = LocalizadorTesseract.candidatos(null,
                Map.of("ProgramFiles", "C:\\Program Files", "CAJA_LIBRE_TESSERACT", "D:\\t\\tesseract.exe"), null, true, "tesseract.exe");
        assertThat(w.get(0).origen()).contains(LocalizadorTesseract.VARIABLE);
        assertThat(w).extracting(x -> x.ejecutable().toString()).anyMatch(s -> s.contains("Tesseract-OCR"));
    }

    @Test
    void ejecutableInexistenteNoSeUsa(@TempDir Path dir) {
        assertThat(LocalizadorTesseract.buscar(dir.resolve("nada").toString(), Map.of("PATH", ""), dir, "Linux")
                .filter(u -> u.ejecutable().startsWith(dir))).isEmpty();
    }

    @Test
    void preferenciasLocalesIdaYVuelta(@TempDir Path dir) {
        assertThat(PreferenciasOcr.leer(dir).preferencia()).isEqualTo(SeleccionMotorOcr.Preferencia.AUTOMATICO);
        assertThat(PreferenciasOcr.guardar(dir, new PreferenciasOcr.Datos(SeleccionMotorOcr.Preferencia.RAPIDO, 3.5, "h", false))).isTrue();
        PreferenciasOcr.Datos d = PreferenciasOcr.leer(dir);
        assertThat(d.preferencia()).isEqualTo(SeleccionMotorOcr.Preferencia.RAPIDO);
        assertThat(d.segundosPpOcr()).isEqualTo(3.5);
        assertThat(d.huellaPpOcr()).isEqualTo("h");
        assertThat(d.cargaOnnx()).isFalse();
    }

    @Test
    void elServicioLeeEnSegundoPlanoYGuardaLaPreferencia(@TempDir Path dir) throws Exception {
        LectorTicketsService s = new LectorTicketsService(dir);
        s.guardarPreferencia(SeleccionMotorOcr.Preferencia.RAPIDO);
        assertThat(s.preferencia()).isEqualTo(SeleccionMotorOcr.Preferencia.RAPIDO);
        assertThat(s.decision().motivo()).isNotBlank();
        Assumptions.assumeTrue(s.tesseract().isPresent(), "Tesseract no está instalado: se omite la lectura");
        ResultadoOcr r = s.leer(PreprocesadorTicketTest.jpeg(PreprocesadorTicketTest.ticket(20)), Duration.ofSeconds(60));
        assertThat(r.textoCompleto()).contains("TOTAL");
        s.cerrar();
    }
}
