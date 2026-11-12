package me.eliassanchezfernandez.puntodeventa;

import javafx.css.CssParser;
import javafx.css.Stylesheet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hoja de estilos sin levantar JavaFX:
 *  - app.css se analiza con el parser de JavaFX sin errores (un error deja
 *    reglas sin aplicar y en la consola solo sale "CSS Error parsing");
 *  - solo usa propiedades "-fx-..." o variables propias "-algo" (JavaFX
 *    ignora en silencio "border-radius", "color", etc.);
 *  - cada styleClass que usan los FXML y los controladores existe en app.css o
 *    en el tema de AtlantaFX (si no, el estilo simplemente no se ve).
 */
class EstilosCssTest {

    private static final Path CSS  = Path.of("src/main/resources/css/app.css");
    private static final Path FXML = Path.of("src/main/resources/fxml");
    private static final Path JAVA = Path.of("src/main/java/me/eliassanchezfernandez/puntodeventa");

    /** Clases que solo marcan un nodo (sin regla propia a propósito) */
    private static final Set<String> SOLO_MARCA = Set.of("reloj-box");

    private static final Pattern STYLE_CLASS_FXML = Pattern.compile("styleClass=\"([^\"]+)\"");
    private static final Pattern STYLE_CLASS_JAVA = Pattern.compile("getStyleClass\\(\\)\\.(?:add|addAll|setAll)\\(([^;]*?)\\)\\s*;");
    private static final Pattern CADENA = Pattern.compile("\"([a-z][a-z0-9-]*)\"");
    private static final Pattern SELECTOR_CLASE = Pattern.compile("\\.([a-zA-Z][\\w-]*)");

    @Test
    void appCssSeAnalizaSinErrores() throws IOException {
        var errores = CssParser.errorsProperty();
        errores.clear();
        URL url = CSS.toUri().toURL();
        Stylesheet hoja = new CssParser().parse(url);
        assertThat(hoja.getRules()).isNotEmpty();
        assertThat(errores).as("errores al analizar app.css").isEmpty();
    }

    @Test
    void soloUsaPropiedadesDeJavaFxOVariables() throws IOException {
        String css = sinComentarios(Files.readString(CSS));
        Matcher m = Pattern.compile("(?m)^\\s*([a-zA-Z][\\w-]*)\\s*:[^{};]*;").matcher(css);
        List<String> invalidas = new ArrayList<>();
        while (m.find()) invalidas.add(m.group(1));
        // Pseudoclases al inicio de renglón (p. ej. "a:hover") no deberían aparecer; propiedades sin "-" sí son error
        assertThat(invalidas).as("propiedades que JavaFX ignora (deben empezar con -fx- o ser variables -nombre)").isEmpty();
    }

    @Test
    void cadaStyleClassUsadaExisteEnAppCssOEnElTema() throws IOException {
        Set<String> definidas = new HashSet<>(clasesDe(sinComentarios(Files.readString(CSS))));
        definidas.addAll(clasesDelTema());
        definidas.addAll(SOLO_MARCA);

        Set<String> faltan = new TreeSet<>();
        try (Stream<Path> fxmls = Files.list(FXML)) {
            for (Path f : fxmls.filter(p -> p.toString().endsWith(".fxml")).toList()) {
                Matcher m = STYLE_CLASS_FXML.matcher(Files.readString(f));
                while (m.find()) {
                    for (String c : m.group(1).split("[,\\s]+")) {
                        if (!c.isBlank() && !definidas.contains(c)) faltan.add(f.getFileName() + ": " + c);
                    }
                }
            }
        }
        try (Stream<Path> javas = Files.walk(JAVA)) {
            for (Path f : javas.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = STYLE_CLASS_JAVA.matcher(Files.readString(f));
                while (m.find()) {
                    Matcher c = CADENA.matcher(m.group(1));
                    while (c.find()) {
                        if (c.group(1).endsWith("-")) continue;   // prefijo que se completa en código ("heat-" + nivel)
                        if (!definidas.contains(c.group(1))) faltan.add(f.getFileName() + ": " + c.group(1));
                    }
                }
            }
        }
        assertThat(faltan).as("styleClass sin regla en app.css ni en AtlantaFX").isEmpty();
    }

    private static Set<String> clasesDelTema() throws IOException {
        try (InputStream in = EstilosCssTest.class.getResourceAsStream("/atlantafx/base/theme/nord-light.css")) {
            if (in == null) return Set.of();
            return clasesDe(sinComentarios(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    private static Set<String> clasesDe(String css) {
        Set<String> clases = new HashSet<>();
        // Solo la parte de selectores (antes de cada "{")
        for (String bloque : css.split("\\{")) {
            int fin = bloque.lastIndexOf('}');
            String selector = fin >= 0 ? bloque.substring(fin + 1) : bloque;
            Matcher m = SELECTOR_CLASE.matcher(selector);
            while (m.find()) clases.add(m.group(1));
        }
        return clases;
    }

    private static String sinComentarios(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }
}
