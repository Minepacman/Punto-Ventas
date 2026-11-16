package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La pastilla «Beta»: cada marca (FXML, Java, página móvil) apunta a una constante de
 * {@link FuncionBeta} que existe, cada función en beta se marca en algún lado y
 * docs/funciones-beta.md lista exactamente las que están en beta.
 */
class FuncionesBetaTest {

    private static final Path MAIN = Path.of("src/main");
    private static final Path DOC = Path.of("docs/funciones-beta.md");
    private static final Pattern FXML_MARCA = Pattern.compile("\\b(?:beta|funcion)=\"([A-Z_]+)\"");
    private static final Pattern JAVA_MARCA = Pattern.compile("FuncionBeta\\.([A-Z][A-Z_]+)\\b");
    private static final Pattern HTML_MARCA = Pattern.compile("etiquetaBeta\\('([A-Z_]+)'\\)");
    private static final Pattern DOC_FILA = Pattern.compile("(?m)^\\|\\s*`([A-Z_]+)`\\s*\\|");

    @Test
    void cadaFuncionTieneNombreYMotivo() {
        for (FuncionBeta f : FuncionBeta.values()) {
            assertThat(f.nombre()).as(f.name()).isNotBlank();
            assertThat(f.motivo()).as(f.name()).isNotBlank();
            assertThat(FuncionBeta.buscar(f.name())).isSameAs(f);
        }
        assertThat(FuncionBeta.buscar("NO_EXISTE")).isNull();
        assertThat(FuncionBeta.nombresEnBeta()).isNotEmpty()
                .allSatisfy(n -> assertThat(FuncionBeta.buscar(n).enBeta()).isTrue());
    }

    @Test
    void elTituloDeVentanaSoloCambiaEnBeta() {
        FuncionBeta enBeta = FuncionBeta.valueOf(FuncionBeta.nombresEnBeta().get(0));
        assertThat(FuncionBeta.tituloVentana("Báscula", enBeta)).isEqualTo("Báscula (Beta)");
        assertThat(FuncionBeta.tituloVentana("Báscula", null)).isEqualTo("Báscula");
        assertThat(FuncionBeta.AYUDA).isEqualTo("En etapa beta: funciona, pero aún se está probando. Si algo falla, avísanos.");
    }

    @Test
    void cadaMarcaApuntaAUnaFuncionQueExiste() throws IOException {
        List<String> malas = new ArrayList<>();
        for (Marca m : marcas()) if (FuncionBeta.buscar(m.funcion()) == null) malas.add(m.toString());
        assertThat(malas).as("marcas «Beta» que no existen en FuncionBeta").isEmpty();
    }

    @Test
    void cadaFuncionEnBetaSeMarcaEnAlgunaPantalla() throws IOException {
        Set<String> usadas = marcas().stream()
                .filter(m -> !m.archivo().endsWith("FuncionBeta.java"))
                .map(Marca::funcion).collect(Collectors.toSet());
        assertThat(usadas).containsAll(FuncionBeta.nombresEnBeta());
    }

    @Test
    void laDocumentacionListaExactamenteLasFuncionesEnBeta() throws IOException {
        Matcher m = DOC_FILA.matcher(Files.readString(DOC));
        Set<String> enDoc = new HashSet<>();
        while (m.find()) enDoc.add(m.group(1));
        assertThat(enDoc).as("docs/funciones-beta.md").containsExactlyInAnyOrderElementsOf(FuncionBeta.nombresEnBeta());
    }

    @Test
    void laPaginaMovilTieneLaPastilla() throws IOException {
        String html = Files.readString(MAIN.resolve("resources/movil/index.html"));
        assertThat(html).contains(".etiqueta-beta {").contains("function etiquetaBeta(").contains(FuncionBeta.AYUDA);
        String java = Files.readString(MAIN.resolve("java/me/eliassanchezfernandez/puntodeventa/servidor/ServidorMovilService.java"));
        assertThat(java).contains("r.put(\"beta\", me.eliassanchezfernandez.puntodeventa.util.FuncionBeta.nombresEnBeta())");
    }

    private record Marca(String archivo, String funcion) {
        @Override public String toString() { return archivo + ": " + funcion; }
    }

    private static List<Marca> marcas() throws IOException {
        List<Marca> r = new ArrayList<>();
        try (Stream<Path> todos = Files.walk(MAIN)) {
            for (Path p : todos.filter(Files::isRegularFile).toList()) {
                String nombre = p.toString();
                Pattern patron = nombre.endsWith(".fxml") ? FXML_MARCA
                        : nombre.endsWith(".java") ? JAVA_MARCA
                        : nombre.endsWith(".html") ? HTML_MARCA : null;
                if (patron == null) continue;
                Matcher m = patron.matcher(Files.readString(p));
                while (m.find()) {
                    String f = m.group(1);
                    // FuncionBeta.TEXTO / AYUDA son textos, no funciones
                    if (patron == JAVA_MARCA && Arrays.stream(FuncionBeta.class.getFields())
                            .anyMatch(x -> x.getName().equals(f) && !x.isEnumConstant())) continue;
                    r.add(new Marca(nombre, f));
                }
            }
        }
        return r;
    }
}
