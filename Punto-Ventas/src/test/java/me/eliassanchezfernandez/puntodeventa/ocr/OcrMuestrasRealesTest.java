package me.eliassanchezfernandez.puntodeventa.ocr;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Arnés de evaluación con fotos REALES de tickets (opt-in; nunca corre en la suite normal).
 * Las fotos tienen datos personales: se leen de la carpeta del dueño y NO se copian al repositorio.
 * <pre>
 * APP_OCR_MUESTRAS=true \
 * APP_OCR_FOTOS=/ruta/a/las/fotos \
 * APP_OCR_VERDAD=/ruta/a/verdad.txt \          (transcripciones; ver MedidaOcr.leerVerdad)
 * APP_OCR_SALIDA=/ruta/de/resultados \          (textos leídos y tabla resultados.md)
 * [APP_OCR_VARIANTES=pre-final,psm6]            (por omisión, todas)
 * [APP_OCR_GUARDAR_IMAGENES=true]               (guarda las imágenes preparadas, p. ej. para PP-OCR)
 *   ./gradlew test --tests '*OcrMuestrasRealesTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "APP_OCR_MUESTRAS", matches = "true")
class OcrMuestrasRealesTest {

    record Variante(String nombre, MotorTesseract.Ajustes ajustes) {}

    static List<Variante> variantes() {
        MotorTesseract.Ajustes base = MotorTesseract.PREDETERMINADOS.conLimite(Duration.ofSeconds(120));
        PreprocesadorTicket.Opciones nada = PreprocesadorTicket.Opciones.ninguna();
        PreprocesadorTicket.Opciones recorte = new PreprocesadorTicket.Opciones(true, true, false, false, 0, false);
        PreprocesadorTicket.Opciones aplanar = new PreprocesadorTicket.Opciones(true, true, true, false, 0, false);
        PreprocesadorTicket.Opciones enderezar = new PreprocesadorTicket.Opciones(true, true, true, true, 0, false);
        PreprocesadorTicket.Opciones fin = PreprocesadorTicket.PREDETERMINADAS;
        List<Variante> v = new ArrayList<>();
        v.add(new Variante("crudo", base.conPreproceso(nada)));
        v.add(new Variante("recorte+gris", base.conPreproceso(recorte)));
        v.add(new Variante("+aplanar", base.conPreproceso(aplanar)));
        v.add(new Variante("+enderezar", base.conPreproceso(enderezar)));
        v.add(new Variante("pre-final", base.conPreproceso(fin)));
        v.add(new Variante("pre-final-sauvola", base.conPreproceso(new PreprocesadorTicket.Opciones(true, true, true, true, fin.alturaLetraObjetivo(), true))));
        v.add(new Variante("ampliar36", base.conPreproceso(new PreprocesadorTicket.Opciones(true, true, true, true, 36, false))));
        v.add(new Variante("ampliar48", base.conPreproceso(new PreprocesadorTicket.Opciones(true, true, true, true, 48, false))));
        v.add(new Variante("sin-recorte", base.conPreproceso(new PreprocesadorTicket.Opciones(false, true, true, true, fin.alturaLetraObjetivo(), false))));
        v.add(new Variante("sin-balance", base.conPreproceso(new PreprocesadorTicket.Opciones(true, false, true, true, fin.alturaLetraObjetivo(), false))));
        v.add(new Variante("psm6", base.conPsm(6)));
        v.add(new Variante("psm3", base.conPsm(3)));
        v.add(new Variante("psm11", base.conPsm(11)));
        v.add(new Variante("solo-spa", base.conIdiomas("spa")));
        return v;
    }

    @Test
    void evaluarTodasLasVariantes() throws IOException {
        Path fotos = Path.of(requerida("APP_OCR_FOTOS"));
        Path verdadArchivo = Path.of(requerida("APP_OCR_VERDAD"));
        Path salida = Path.of(requerida("APP_OCR_SALIDA"));
        Optional<LocalizadorTesseract.Ubicacion> ubicacion = LocalizadorTesseract.buscar();
        Assumptions.assumeTrue(ubicacion.isPresent(), "Tesseract no está instalado");
        Map<String, List<String>> verdad = MedidaOcr.leerVerdad(Files.readString(verdadArchivo, StandardCharsets.UTF_8));
        List<Path> archivos;
        try (Stream<Path> s = Files.list(fotos)) {
            archivos = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png)$")).sorted().toList();
        }
        String filtro = System.getenv("APP_OCR_VARIANTES");
        boolean guardarImagenes = "true".equals(System.getenv("APP_OCR_GUARDAR_IMAGENES"));
        Files.createDirectories(salida);

        StringBuilder tabla = new StringBuilder();
        tabla.append("Tesseract ").append(ubicacion.get().version()).append(" (").append(ubicacion.get().idiomas()).append(")\n\n");
        tabla.append("| Variante | CER renglones | Importes exactos | Preproceso ms (prom.) | OCR ms (prom.) | Total ms (máx.) |\n|---|---|---|---|---|---|\n");
        StringBuilder detalle = new StringBuilder();
        for (Variante v : variantes()) {
            if (filtro != null && !filtro.isBlank() && !List.of(filtro.split(",")).contains(v.nombre())) continue;
            MotorTesseract motor = new MotorTesseract(ubicacion.get(), v.ajustes());
            MedidaOcr.Medida total = MedidaOcr.Medida.cero();
            long sumaPre = 0, sumaOcr = 0, maxTotal = 0;
            int n = 0;
            Path dir = salida.resolve(v.nombre());
            Files.createDirectories(dir);
            Map<String, String> filas = new LinkedHashMap<>();
            int indice = 0;
            for (Path foto : archivos) {
                indice++;
                String nombre = foto.getFileName().toString();
                List<String> esperado = esperado(verdad, nombre, indice);
                MotorTesseract.Lectura l = motor.leerConDetalle(Files.readAllBytes(foto));
                String id = String.format("%02d", indice);
                Files.writeString(dir.resolve(id + ".txt"), l.resultado().textoCompleto(), StandardCharsets.UTF_8);
                if (guardarImagenes && "pre-final".equals(v.nombre())) {
                    guardarPng(l.preparada().imagen().girar90(l.giroExtra()), salida.resolve("preparadas").resolve(id + ".png"));
                }
                sumaPre += l.msPreproceso();
                sumaOcr += l.msOcr();
                maxTotal = Math.max(maxTotal, l.msPreproceso() + l.msOcr());
                n++;
                if (esperado == null || esperado.contains("#MANUSCRITO")) {
                    filas.put(id, "sin evaluar (" + (esperado == null ? "sin transcripción" : "manuscrito") + ")");
                    continue;
                }
                MedidaOcr.Medida m = MedidaOcr.medir(esperado, l.resultado().textoCompleto());
                total = total.mas(m);
                filas.put(id, String.format(Locale.ROOT, "CER %.1f %%, importes %d/%d, giro %d+%d, incl. %.1f°, escala %.2f, letra %.0f px, conf %.2f, %d corridas, %d+%d ms",
                        100 * m.cer(), m.importesLeidos(), m.importesEsperados(), l.preparada().giro90(), l.giroExtra(),
                        l.preparada().inclinacion(), l.preparada().escala(), l.preparada().alturaLetra(), l.confianza(),
                        l.corridas(), l.msPreproceso(), l.msOcr()));
            }
            tabla.append(String.format(Locale.ROOT, "| %s | %.1f %% | %.1f %% (%d/%d) | %d | %d | %d |%n", v.nombre(), 100 * total.cer(),
                    100 * total.importes(), total.importesLeidos(), total.importesEsperados(), sumaPre / Math.max(1, n),
                    sumaOcr / Math.max(1, n), maxTotal));
            detalle.append("\n### ").append(v.nombre()).append('\n');
            filas.forEach((k, s) -> detalle.append("- ").append(k).append(": ").append(s).append('\n'));
            System.out.println("[OCR] " + v.nombre() + String.format(Locale.ROOT, " CER %.1f %% importes %.1f %%", 100 * total.cer(), 100 * total.importes()));
        }
        evaluarPpOcr(archivos, verdad, salida, filtro, tabla, detalle);
        Files.writeString(salida.resolve("resultados.md"), tabla + detalle.toString(), StandardCharsets.UTF_8);
        System.out.println(tabla);
    }

    /**
     * PaddleOCR (si APP_OCR_PPOCR_MODELOS apunta a una carpeta con PP-OCRv6_det_small.onnx y
     * PP-OCRv6_rec_small.onnx). Se carga con CargadorPpOcr, igual que en el programa.
     */
    private static void evaluarPpOcr(List<Path> archivos, Map<String, List<String>> verdad, Path salida, String filtro,
                                     StringBuilder tabla, StringBuilder detalle) throws IOException {
        String dirModelos = System.getenv("APP_OCR_PPOCR_MODELOS");
        if (dirModelos == null || dirModelos.isBlank()) return;
        Path modelos = Path.of(dirModelos);
        Map<String, PreprocesadorTicket.Opciones> variantes = new LinkedHashMap<>();
        variantes.put("ppocr", me.eliassanchezfernandez.puntodeventa.ocr.ppocr.MotorPpOcr.PREPROCESO);
        variantes.put("ppocr-crudo", new PreprocesadorTicket.Opciones(false, false, false, true, 0, false));
        variantes.put("ppocr-aplanado", new PreprocesadorTicket.Opciones(false, true, true, true, 0, false));
        variantes.put("ppocr-pre-final", PreprocesadorTicket.PREDETERMINADAS);
        String hilosEnv = System.getenv("APP_OCR_PPOCR_HILOS");
        int hilos = hilosEnv == null ? CargadorPpOcr.hilosSugeridos() : Integer.parseInt(hilosEnv);
        long tc = System.nanoTime();
        MotorOcr cargado = CargadorPpOcr.cargar(null, modelos.resolve("PP-OCRv6_det_small.onnx"),
                modelos.resolve("PP-OCRv6_rec_small.onnx"), hilos);
        long msCarga = (System.nanoTime() - tc) / 1_000_000;
        var motor = new me.eliassanchezfernandez.puntodeventa.ocr.ppocr.MotorPpOcr(modelos.resolve("PP-OCRv6_det_small.onnx"),
                modelos.resolve("PP-OCRv6_rec_small.onnx"), hilos);
        assertThat(cargado.nombre()).isEqualTo(motor.nombre());
        for (var v : variantes.entrySet()) {
            if (filtro != null && !filtro.isBlank() && !List.of(filtro.split(",")).contains(v.getKey())) continue;
            MedidaOcr.Medida total = MedidaOcr.Medida.cero();
            long suma = 0, max = 0;
            int indice = 0;
            Path dir = salida.resolve(v.getKey());
            Files.createDirectories(dir);
            detalle.append("\n### ").append(v.getKey()).append(" (").append(hilos).append(" hilos, carga ").append(msCarga).append(" ms)\n");
            for (Path foto : archivos) {
                indice++;
                String id = String.format("%02d", indice);
                long t0 = System.nanoTime();
                ResultadoOcr r = motor.leer(Files.readAllBytes(foto), v.getValue());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                suma += ms;
                max = Math.max(max, ms);
                Files.writeString(dir.resolve(id + ".txt"), r.textoCompleto(), StandardCharsets.UTF_8);
                List<String> esperado = esperado(verdad, foto.getFileName().toString(), indice);
                if (esperado == null || esperado.contains("#MANUSCRITO")) continue;
                MedidaOcr.Medida m = MedidaOcr.medir(esperado, r.textoCompleto());
                total = total.mas(m);
                detalle.append(String.format(Locale.ROOT, "- %s: CER %.1f %%, importes %d/%d, %d ms%n", id, 100 * m.cer(),
                        m.importesLeidos(), m.importesEsperados(), ms));
            }
            tabla.append(String.format(Locale.ROOT, "| %s | %.1f %% | %.1f %% (%d/%d) | (incluido) | %d | %d |%n", v.getKey(),
                    100 * total.cer(), 100 * total.importes(), total.importesLeidos(), total.importesEsperados(),
                    suma / Math.max(1, archivos.size()), max));
        }
        motor.close();
    }

    /**
     * Transcripción por nombre de archivo; si no hay (p. ej. imágenes ya preparadas "01.png"…), por
     * posición en orden alfabético de los nombres transcritos.
     */
    private static List<String> esperado(Map<String, List<String>> verdad, String nombre, int indice) {
        List<String> e = verdad.get(nombre);
        if (e != null) return e;
        List<String> nombres = verdad.keySet().stream().sorted().toList();
        return indice >= 1 && indice <= nombres.size() ? verdad.get(nombres.get(indice - 1)) : null;
    }

    private static void guardarPng(ImagenGris g, Path destino) throws IOException {
        Files.createDirectories(destino.getParent());
        BufferedImage img = new BufferedImage(g.ancho(), g.alto(), BufferedImage.TYPE_BYTE_GRAY);
        img.getRaster().setDataElements(0, 0, g.ancho(), g.alto(), g.px());
        ImageIO.write(img, "png", destino.toFile());
    }

    private static String requerida(String variable) {
        String v = System.getenv(variable);
        Assumptions.assumeTrue(v != null && !v.isBlank(), "Falta la variable " + variable);
        return v;
    }
}
