package me.eliassanchezfernandez.puntodeventa.ocr;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dónde se busca Tesseract: rutas al estilo Windows (PATH con ";" y comillas, carpeta del instalador con
 * tesseract.exe) y carpetas de idiomas que faltan. Las que corren un "tesseract" falso (un script de shell)
 * se omiten en Windows.
 */
class LocalizadorTesseractTest {

    @Test
    void pathDeWindowsSeParteConPuntoYComaYSinComillas() {
        Map<String, String> entorno = Map.of("Path",
                "C:\\Windows\\system32; \"C:\\Program Files\\Tesseract-OCR\" ;;D:\\Herramientas\\bin;");
        assertThat(LocalizadorTesseract.partesDelPath(entorno, true))
                .containsExactly("C:\\Windows\\system32", "C:\\Program Files\\Tesseract-OCR", "D:\\Herramientas\\bin");
        // la letra de unidad no se confunde con un separador
        assertThat(LocalizadorTesseract.partesDelPath(Map.of("PATH", "C:\\a;C:\\b"), true)).containsExactly("C:\\a", "C:\\b");
        // en macOS/Linux se separa con ":"
        assertThat(LocalizadorTesseract.partesDelPath(Map.of("PATH", "/usr/bin::/opt/x/bin"), false))
                .containsExactly("/usr/bin", "/opt/x/bin");
        assertThat(LocalizadorTesseract.partesDelPath(Map.of(), true)).isEmpty();
    }

    @Test
    void candidatosDeWindowsEnOrden() {
        Path app = Path.of("C:\\Program Files\\PuntoDeVenta\\app");
        Map<String, String> entorno = Map.of(
                "ProgramFiles", "C:\\Program Files",
                "ProgramFiles(x86)", "C:\\Program Files (x86)",
                "LOCALAPPDATA", "C:\\Users\\caja\\AppData\\Local",
                "Path", "C:\\Windows;\"D:\\ocr\\bin\"");
        List<LocalizadorTesseract.Candidato> c = LocalizadorTesseract.candidatos(null, entorno, app, true, "tesseract.exe");
        // 1º el del instalador: <app>/ocr/tesseract/bin/tesseract.exe con su tessdata
        assertThat(c.get(0).origen()).isEqualTo("incluido en el programa");
        assertThat(c.get(0).ejecutable()).isEqualTo(app.resolve("ocr").resolve("tesseract").resolve("bin").resolve("tesseract.exe"));
        assertThat(c.get(0).tessdata()).isEqualTo(app.resolve("ocr").resolve("tesseract").resolve("tessdata"));
        // luego el instalador de UB Mannheim (Program Files, x86 y por usuario) y el PATH, sin comillas
        List<Path> carpetas = c.stream().skip(1).map(x -> x.ejecutable().getParent()).toList();
        assertThat(carpetas).containsSubsequence(
                Path.of("C:\\Program Files", "Tesseract-OCR"),
                Path.of("C:\\Program Files (x86)", "Tesseract-OCR"),
                Path.of("C:\\Users\\caja\\AppData\\Local", "Programs", "Tesseract-OCR"),
                Path.of("C:\\Windows"),
                Path.of("D:\\ocr\\bin"));
        assertThat(c).allSatisfy(x -> assertThat(x.ejecutable().getFileName().toString()).isEqualTo("tesseract.exe"));
        // en Windows no se buscan rutas de Unix
        assertThat(c).noneMatch(x -> x.ejecutable().toString().startsWith("/usr"));
    }

    @Test
    void indicadoPorPropiedadGanaALaVariable() {
        List<LocalizadorTesseract.Candidato> c = LocalizadorTesseract.candidatos("E:\\t\\tesseract.exe",
                Map.of(LocalizadorTesseract.VARIABLE, "D:\\otro\\tesseract.exe"), null, true, "tesseract.exe");
        assertThat(c.get(0).ejecutable()).isEqualTo(Path.of("E:\\t\\tesseract.exe"));
        assertThat(c.get(0).origen()).contains(LocalizadorTesseract.PROPIEDAD);
        assertThat(c).noneMatch(x -> x.ejecutable().toString().contains("otro"));
    }

    @Test
    void incluidoSinTessdataSeSaltaYSeUsaElDelSistema(@TempDir Path dir) throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "usa un script de shell");
        // instalador con el ejecutable pero SIN carpeta tessdata (y sin idiomas compilados): no sirve
        Path app = dir.resolve("app");
        Path incluido = app.resolve("ocr/tesseract/bin/tesseract");
        tesseractFalso(incluido, null);
        // un Tesseract "del sistema" en el PATH con sus idiomas propios
        Path sistema = dir.resolve("sistema/bin/tesseract");
        tesseractFalso(sistema, "spa\neng");
        Optional<LocalizadorTesseract.Ubicacion> u = LocalizadorTesseract.buscar(null,
                Map.of("PATH", sistema.getParent().toString()), app, "Linux");
        assertThat(u).isPresent();
        assertThat(u.get().ejecutable()).isNotEqualTo(incluido);
        // (si esta computadora tiene Tesseract en /opt/homebrew o /usr/bin, ese va antes que el PATH)
        if (u.get().ejecutable().startsWith(dir)) {
            assertThat(u.get().ejecutable()).isEqualTo(sistema);
            assertThat(u.get().idiomas()).isEqualTo("spa+eng");
            assertThat(u.get().version()).isEqualTo("5.5.4");
            assertThat(u.get().tessdata()).isNull();
        }
    }

    @Test
    void incluidoConTessdataIncompletaOSoloIngles(@TempDir Path dir) throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "usa un script de shell");
        Path app = dir.resolve("app");
        Path base = app.resolve("ocr/tesseract");
        tesseractFalso(base.resolve("bin/tesseract"), null);
        Files.createDirectories(base.resolve("tessdata/configs"));
        Files.writeString(base.resolve("tessdata/configs/tsv"), "tessedit_create_tsv 1\n");
        // tessdata vacía: sin español ni inglés no sirve
        assertThat(LocalizadorTesseract.buscar(null, Map.of("PATH", ""), app, "Linux")
                .filter(x -> x.ejecutable().startsWith(dir))).isEmpty();
        // solo inglés: se usa, avisando que es "eng"
        Files.writeString(base.resolve("tessdata/eng.traineddata"), "x");
        Optional<LocalizadorTesseract.Ubicacion> u = LocalizadorTesseract.buscar(null, Map.of("PATH", ""), app, "Linux");
        assertThat(u).isPresent();
        assertThat(u.get().origen()).isEqualTo("incluido en el programa");
        assertThat(u.get().tessdata()).isEqualTo(base.resolve("tessdata"));
        assertThat(u.get().idiomas()).isEqualTo("eng");
        // con los dos
        Files.writeString(base.resolve("tessdata/spa.traineddata"), "x");
        assertThat(LocalizadorTesseract.buscar(null, Map.of("PATH", ""), app, "Linux").map(LocalizadorTesseract.Ubicacion::idiomas))
                .contains("spa+eng");
    }

    @Test
    void tessdataSinConfigTsvNoSeUsa(@TempDir Path dir) throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "usa un script de shell");
        // con los idiomas pero sin configs/tsv, Tesseract contestaría texto plano en vez de TSV
        Path app = dir.resolve("app");
        Path base = app.resolve("ocr/tesseract");
        tesseractFalso(base.resolve("bin/tesseract"), null);
        Files.createDirectories(base.resolve("tessdata"));
        Files.writeString(base.resolve("tessdata/spa.traineddata"), "x");
        Files.writeString(base.resolve("tessdata/eng.traineddata"), "x");
        assertThat(LocalizadorTesseract.buscar(null, Map.of("PATH", ""), app, "Linux")
                .filter(x -> x.ejecutable().startsWith(dir))).isEmpty();
        // con él, sí
        Files.createDirectories(base.resolve("tessdata/configs"));
        Files.writeString(base.resolve("tessdata/configs/tsv"), "tessedit_create_tsv 1\n");
        assertThat(LocalizadorTesseract.buscar(null, Map.of("PATH", ""), app, "Linux").map(LocalizadorTesseract.Ubicacion::tessdata))
                .contains(base.resolve("tessdata"));
    }

    @Test
    void ejecutableQueNoArrancaSeSalta(@TempDir Path dir) throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "usa un script de shell");
        // p. ej. un tesseract.exe de otra arquitectura o con bibliotecas faltantes: --version sale con error
        Path app = dir.resolve("app");
        Path roto = app.resolve("ocr/tesseract/bin/tesseract");
        Files.createDirectories(roto.getParent());
        Files.writeString(roto, "#!/bin/sh\necho 'dyld: Library not loaded' >&2\nexit 134\n");
        Files.setPosixFilePermissions(roto, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(app.resolve("ocr/tesseract/tessdata"));
        assertThat(LocalizadorTesseract.buscar(null, Map.of("PATH", ""), app, "Linux")
                .filter(x -> x.ejecutable().startsWith(dir))).isEmpty();
    }

    /**
     * Script que imita a tesseract: "--version" y "--list-langs" (con --tessdata-dir lista los
     * *.traineddata de esa carpeta; sin él, los idiomas "compilados" que se le den, o ninguno).
     */
    private static void tesseractFalso(Path ruta, String idiomasPropios) throws IOException {
        Files.createDirectories(ruta.getParent());
        String propios = idiomasPropios == null ? "" : idiomasPropios.replace("\n", " ");
        String script = """
                #!/bin/sh
                if [ "$1" = "--version" ]; then echo "tesseract 5.5.4"; echo " leptonica-1.87.0"; exit 0; fi
                if [ "$1" = "--tessdata-dir" ]; then
                  echo "List of available languages in \\"$2/\\" (n):"
                  for f in "$2"/*.traineddata; do [ -e "$f" ] && basename "$f" .traineddata; done
                  exit 0
                fi
                if [ "$1" = "--list-langs" ]; then
                  if [ -z "%s" ]; then echo "Error opening data file /ci/build/share/tessdata/eng.traineddata" >&2; exit 1; fi
                  echo "List of available languages in \\"/usr/share/tessdata/\\" (2):"
                  for l in %s; do echo "$l"; done
                  exit 0
                fi
                exit 1
                """.formatted(propios, propios);
        Files.writeString(ruta, script);
        Files.setPosixFilePermissions(ruta, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
