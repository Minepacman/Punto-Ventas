package me.eliassanchezfernandez.puntodeventa.ocr;

import me.eliassanchezfernandez.puntodeventa.util.CalentadorCds;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Encuentra el ejecutable de Tesseract y sus datos de idioma. Se usa el programa {@code tesseract}
 * como proceso aparte (sin JNA): si falla o se cuelga no tumba la caja, se puede cancelar matándolo
 * y la memoria vuelve al sistema al terminar cada lectura. Orden de búsqueda:
 * <ol>
 *   <li>{@code -Dapp.ocr.tesseract=/ruta/tesseract} o variable {@code CAJA_LIBRE_TESSERACT};</li>
 *   <li>el que trae el instalador: {@code <carpeta app>/ocr/tesseract/bin/tesseract[.exe]} con
 *       {@code <carpeta app>/ocr/tesseract/tessdata} (ver {@code docs/investigacion-ocr-tickets.md});</li>
 *   <li>instalaciones conocidas (Homebrew, apt/dnf, el instalador de UB Mannheim en Windows) y el PATH.</li>
 * </ol>
 */
public final class LocalizadorTesseract {

    public static final String PROPIEDAD = "app.ocr.tesseract";
    public static final String VARIABLE = "CAJA_LIBRE_TESSERACT";
    /** Subcarpeta dentro de la carpeta "app" del instalador */
    public static final String CARPETA_INCLUIDA = "ocr/tesseract";

    /**
     * @param ejecutable ruta al programa
     * @param tessdata   carpeta de idiomas a pasar con --tessdata-dir (null = la del propio Tesseract)
     * @param origen     de dónde salió (para la pantalla de Configuración)
     * @param version    p. ej. "5.5.4" (vacío si no se pudo leer)
     * @param idiomas    "spa+eng", "spa" o "eng" según lo instalado
     */
    public record Ubicacion(Path ejecutable, Path tessdata, String origen, String version, String idiomas) {}

    private LocalizadorTesseract() { }

    /** Busca con el sistema real (propiedades, entorno, carpeta de la app). */
    public static Optional<Ubicacion> buscar() {
        return buscar(System.getProperty(PROPIEDAD), System.getenv(), CalentadorCds.carpetaApp().orElse(null),
                System.getProperty("os.name", ""));
    }

    static Optional<Ubicacion> buscar(String propiedad, Map<String, String> entorno, Path carpetaApp, String sistema) {
        boolean windows = sistema.toLowerCase().contains("win");
        String exe = windows ? "tesseract.exe" : "tesseract";
        List<Candidato> candidatos = candidatos(propiedad, entorno, carpetaApp, windows, exe);
        for (Candidato c : candidatos) {
            if (!Files.isRegularFile(c.ejecutable()) || !Files.isExecutable(c.ejecutable())) continue;
            Path tessdata = c.tessdata() != null && Files.isDirectory(c.tessdata()) ? c.tessdata() : null;
            String version = version(c.ejecutable());
            if (version == null) continue;            // no arranca (bibliotecas faltantes, arquitectura equivocada)
            String idiomas = idiomas(c.ejecutable(), tessdata);
            if (idiomas.isEmpty()) continue;          // sin español ni inglés no sirve
            return Optional.of(new Ubicacion(c.ejecutable(), tessdata, c.origen(), version, idiomas));
        }
        return Optional.empty();
    }

    record Candidato(Path ejecutable, Path tessdata, String origen) {}

    static List<Candidato> candidatos(String propiedad, Map<String, String> entorno, Path carpetaApp,
                                      boolean windows, String exe) {
        List<Candidato> lista = new ArrayList<>();
        String explicito = propiedad != null && !propiedad.isBlank() ? propiedad : entorno.get(VARIABLE);
        if (explicito != null && !explicito.isBlank()) {
            Path p = Path.of(explicito.trim());
            lista.add(new Candidato(p, hermanoTessdata(p), "indicado en " + (propiedad != null && !propiedad.isBlank() ? PROPIEDAD : VARIABLE)));
        }
        if (carpetaApp != null) {
            Path base = carpetaApp.resolve(CARPETA_INCLUIDA);
            lista.add(new Candidato(base.resolve("bin").resolve(exe), base.resolve("tessdata"), "incluido en el programa"));
        }
        Set<Path> carpetas = new LinkedHashSet<>();
        if (windows) {
            for (String var : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String v = entorno.get(var);
                if (v == null || v.isBlank()) continue;
                carpetas.add(Path.of(v, "Tesseract-OCR"));
                carpetas.add(Path.of(v, "Programs", "Tesseract-OCR"));
            }
        } else {
            carpetas.add(Path.of("/opt/homebrew/bin"));
            carpetas.add(Path.of("/usr/local/bin"));
            carpetas.add(Path.of("/usr/bin"));
        }
        String path = entorno.getOrDefault("PATH", entorno.getOrDefault("Path", ""));
        for (String parte : path.split(File.pathSeparator)) {
            if (!parte.isBlank()) {
                try {
                    carpetas.add(Path.of(parte.trim()));
                } catch (RuntimeException e) {
                    // entrada inválida del PATH
                }
            }
        }
        for (Path c : carpetas) lista.add(new Candidato(c.resolve(exe), null, "instalado en " + c));
        return lista;
    }

    private static Path hermanoTessdata(Path ejecutable) {
        Path padre = ejecutable.getParent();
        if (padre == null) return null;
        Path junto = padre.resolve("tessdata");
        if (Files.isDirectory(junto)) return junto;
        Path arriba = padre.getParent() == null ? null : padre.getParent().resolve("tessdata");
        return arriba != null && Files.isDirectory(arriba) ? arriba : null;
    }

    /** "5.5.4" o null si el programa no arranca */
    static String version(Path ejecutable) {
        String salida = ejecutar(List.of(ejecutable.toString(), "--version"));
        if (salida == null) return null;
        for (String linea : salida.split("\r?\n")) {
            String l = linea.trim();
            if (l.toLowerCase().startsWith("tesseract")) {
                String[] partes = l.split("\\s+");
                return partes.length > 1 ? partes[1].replaceFirst("^v", "") : "";
            }
        }
        return null;
    }

    /** "spa+eng", "spa", "eng" o "" según los datos de idioma disponibles */
    static String idiomas(Path ejecutable, Path tessdata) {
        List<String> cmd = new ArrayList<>(List.of(ejecutable.toString()));
        if (tessdata != null) cmd.addAll(List.of("--tessdata-dir", tessdata.toString()));
        cmd.add("--list-langs");
        String salida = ejecutar(cmd);
        if (salida == null) return "";
        boolean spa = false, eng = false;
        for (String linea : salida.split("\r?\n")) {
            String l = linea.trim();
            if (l.equals("spa")) spa = true;
            if (l.equals("eng")) eng = true;
        }
        return spa && eng ? "spa+eng" : spa ? "spa" : eng ? "eng" : "";
    }

    /** Corre un comando corto (≤ 10 s) y devuelve stdout+stderr, o null si no arrancó, falló o se colgó */
    private static String ejecutar(List<String> comando) {
        Path salida = null;
        try {
            salida = Files.createTempFile("tesseract-", ".txt");
            Process p = new ProcessBuilder(comando).redirectErrorStream(true)
                    .redirectOutput(salida.toFile()).start();
            p.getOutputStream().close();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            byte[] datos = Files.readAllBytes(salida);
            return new String(datos, 0, Math.min(datos.length, 64 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (salida != null) {
                try {
                    Files.deleteIfExists(salida);
                } catch (IOException e) {
                    // temporal: no importa
                }
            }
        }
    }
}
