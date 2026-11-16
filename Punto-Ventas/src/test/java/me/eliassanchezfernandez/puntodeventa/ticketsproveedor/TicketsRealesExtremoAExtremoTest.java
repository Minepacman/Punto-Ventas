package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import me.eliassanchezfernandez.puntodeventa.ocr.LocalizadorTesseract;
import me.eliassanchezfernandez.puntodeventa.ocr.MotorOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.MotorTesseract;
import me.eliassanchezfernandez.puntodeventa.ocr.Renglon;
import me.eliassanchezfernandez.puntodeventa.ocr.ResultadoOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.ppocr.MotorPpOcr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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
 * Arnés de EXTREMO A EXTREMO con fotos reales (opt-in; nunca corre en la suite normal): foto → motor OCR →
 * {@link InterpreteTicket} → comparación campo por campo contra {@code tNN.esperado.json}. Las fotos tienen datos
 * personales: se leen de una carpeta del dueño y nada de lo que sale se copia al repositorio.
 * <pre>
 * APP_OCR_MUESTRAS=true \
 * APP_TICKETS_FOTOS=/carpeta/con/t01.jpeg…t16.jpeg   (orden alfabético = número de muestra tNN)
 * APP_TICKETS_SALIDA=/carpeta/de/resultados           (texto leído, diferencias y resultados-e2e.md)
 * [APP_OCR_PPOCR_MODELOS=/carpeta/con/PP-OCRv6_*_small.onnx]  (si falta, solo Tesseract)
 * [APP_TICKETS_TEXTOS=/carpeta/con/NN.txt]            (texto ya leído por un OCR: solo mide el intérprete)
 * [APP_TICKETS_SOLO=t02,t14]                          (solo esas muestras)
 * [APP_TICKETS_MOTORES=tesseract,ppocr,texto]
 *   ./gradlew test --tests '*TicketsRealesExtremoAExtremoTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "APP_OCR_MUESTRAS", matches = "true")
class TicketsRealesExtremoAExtremoTest {

    /** Lo que se mide de un ticket (una muestra puede traer dos tickets: se suman) */
    record Fila(String muestra, int renglonesEsperados, int renglonesLeidos, int campos, int aciertos, int cuadran,
                int tickets, String totales, long ms, List<String> diferencias) {
        double precision() { return campos == 0 ? 1 : (double) aciertos / campos; }
    }

    interface Lector {
        ResultadoOcr leer(String muestra, Path foto) throws IOException;
    }

    @Test
    void medirDeLaFotoAlTicket() throws Exception {
        Path salida = Path.of(requerida("APP_TICKETS_SALIDA"));
        Files.createDirectories(salida);
        String fotosDir = System.getenv("APP_TICKETS_FOTOS");
        String textosDir = System.getenv("APP_TICKETS_TEXTOS");
        String motoresEnv = System.getenv("APP_TICKETS_MOTORES");
        List<String> motoresPedidos = motoresEnv == null || motoresEnv.isBlank() ? null : List.of(motoresEnv.split(","));
        String soloEnv = System.getenv("APP_TICKETS_SOLO");
        List<String> solo = soloEnv == null || soloEnv.isBlank() ? null : List.of(soloEnv.split(","));

        Map<String, Path> fotos = new LinkedHashMap<>();
        if (fotosDir != null && !fotosDir.isBlank()) {
            List<Path> archivos;
            try (Stream<Path> s = Files.list(Path.of(fotosDir))) {
                archivos = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png)$")).sorted().toList();
            }
            for (Path p : archivos) {
                String n = p.getFileName().toString().replaceAll("\\.[^.]+$", "");
                if (!n.matches("t\\d{2}")) n = String.format("t%02d", fotos.size() + 1);
                fotos.put(n, p);
            }
        }
        Map<String, Lector> motores = new LinkedHashMap<>();
        List<AutoCloseable> cerrar = new ArrayList<>();
        if (!fotos.isEmpty()) {
            Optional<LocalizadorTesseract.Ubicacion> u = LocalizadorTesseract.buscar();
            if (u.isPresent() && quiere(motoresPedidos, "tesseract")) {
                MotorTesseract t = new MotorTesseract(u.get(), MotorTesseract.PREDETERMINADOS.conLimite(Duration.ofSeconds(180)));
                motores.put("tesseract", (m, f) -> t.leer(Files.readAllBytes(f)));
            }
            String modelos = System.getenv("APP_OCR_PPOCR_MODELOS");
            if (modelos != null && !modelos.isBlank() && quiere(motoresPedidos, "ppocr")) {
                MotorPpOcr pp = new MotorPpOcr(Path.of(modelos).resolve("PP-OCRv6_det_small.onnx"),
                        Path.of(modelos).resolve("PP-OCRv6_rec_small.onnx"), 1);
                cerrar.add(pp);
                motores.put("ppocr", (m, f) -> pp.leer(Files.readAllBytes(f)));
            }
        }
        if (textosDir != null && !textosDir.isBlank() && quiere(motoresPedidos, "texto")) {
            Path dir = Path.of(textosDir);
            motores.put("texto", (m, f) -> {
                Path a = dir.resolve(m + ".txt");
                if (!Files.exists(a)) a = dir.resolve(m.substring(1) + ".txt");
                String texto = Files.readString(a, StandardCharsets.UTF_8);
                List<Renglon> rs = new ArrayList<>();
                String[] ls = texto.split("\n");
                for (int i = 0; i < ls.length; i++) rs.add(new Renglon(ls[i], 0.9, 40 + i * 22));
                return new ResultadoOcr(rs, texto);
            });
        }
        if (motores.isEmpty()) throw new IllegalStateException("Sin motores: define APP_TICKETS_FOTOS (y Tesseract/APP_OCR_PPOCR_MODELOS) o APP_TICKETS_TEXTOS");

        StringBuilder informe = new StringBuilder("# De la foto al ticket\n\n");
        for (var motor : motores.entrySet()) {
            Path dir = salida.resolve(motor.getKey());
            Files.createDirectories(dir);
            List<Fila> filas = new ArrayList<>();
            for (MuestrasTickets.Muestra mu : MuestrasTickets.todas()) {
                if (solo != null && !solo.contains(mu.nombre())) continue;
                if ("t04".equals(mu.nombre())) continue; // nota a mano: ningún OCR la lee
                Path foto = fotos.get(mu.nombre());
                if (foto == null && !"texto".equals(motor.getKey())) continue;
                long t0 = System.nanoTime();
                ResultadoOcr r;
                try {
                    r = motor.getValue().leer(mu.nombre(), foto);
                } catch (IOException e) {
                    filas.add(new Fila(mu.nombre(), 0, 0, 0, 0, 0, 0, "ERROR " + e.getMessage(), 0, List.of()));
                    continue;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Files.writeString(dir.resolve(mu.nombre() + ".txt"), r.textoCompleto(), StandardCharsets.UTF_8);
                List<TicketLeido> leidos = InterpreteTicket.interpretar(r.renglones());
                MuestrasTickets.Marcador mk = MuestrasTickets.comparar(leidos, mu.esperado(), true);
                int esperados = 0;
                StringBuilder totales = new StringBuilder();
                for (var t : mu.esperado().get("tickets")) {
                    esperados += t.get("lineas").size();
                    totales.append(totales.length() > 0 ? " / " : "").append("esp ").append(t.get("total").asText());
                }
                int leidosN = 0, cuadran = 0;
                for (TicketLeido t : leidos) {
                    leidosN += t.getLineas().size();
                    if (t.getCuadre() != null && t.getCuadre().cuadra()) cuadran++;
                    totales.append(" → leído ").append(t.getTotal()).append(t.getCuadre() != null && t.getCuadre().cuadra() ? " cuadra" : " NO cuadra");
                }
                filas.add(new Fila(mu.nombre(), esperados, leidosN, mk.campos, mk.aciertos, cuadran, leidos.size(), totales.toString(), ms, mk.diferencias));
                Files.writeString(dir.resolve(mu.nombre() + ".diferencias.txt"), String.join("\n", mk.diferencias) + "\n", StandardCharsets.UTF_8);
            }
            informe.append("## ").append(motor.getKey()).append("\n\n| Muestra | Renglones (leídos/esperados) | Campos | Cuadra | Totales | ms |\n|---|---|---|---|---|---|\n");
            int campos = 0, aciertos = 0, re = 0, rl = 0, cu = 0, tk = 0;
            for (Fila f : filas) {
                campos += f.campos(); aciertos += f.aciertos(); re += f.renglonesEsperados(); rl += f.renglonesLeidos(); cu += f.cuadran(); tk += f.tickets();
                informe.append(String.format(Locale.ROOT, "| %s | %d/%d | %d/%d (%.0f %%) | %d/%d | %s | %d |%n", f.muestra(), f.renglonesLeidos(),
                        f.renglonesEsperados(), f.aciertos(), f.campos(), 100 * f.precision(), f.cuadran(), f.tickets(), f.totales(), f.ms()));
            }
            informe.append(String.format(Locale.ROOT, "| **Total** | %d/%d | %d/%d (**%.1f %%**) | %d/%d | | |%n%n", rl, re, aciertos, campos,
                    campos == 0 ? 0 : 100.0 * aciertos / campos, cu, tk));
        }
        for (AutoCloseable c : cerrar) c.close();
        Files.writeString(salida.resolve("resultados-e2e.md"), informe.toString(), StandardCharsets.UTF_8);
        System.out.println(informe);
    }

    private static boolean quiere(List<String> pedidos, String motor) {
        return pedidos == null || pedidos.contains(motor);
    }

    private static String requerida(String variable) {
        String v = System.getenv(variable);
        if (v == null || v.isBlank()) throw new IllegalStateException("Falta la variable " + variable);
        return v;
    }
}
