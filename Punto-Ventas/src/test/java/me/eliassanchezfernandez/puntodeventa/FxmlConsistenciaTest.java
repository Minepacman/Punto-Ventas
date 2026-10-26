package me.eliassanchezfernandez.puntodeventa;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consistencia FXML ↔ controlador, sin levantar JavaFX:
 *  - cada fx:controller existe;
 *  - cada onAction/onKeyReleased/... "#metodo" existe en el controlador;
 *  - cada campo @FXML del controlador tiene su fx:id en el FXML
 *    (o es el controlador anidado de un fx:include);
 *  - ningún valor de atributo empieza con "%" sin escapar: FXMLLoader lo
 *    toma como clave de ResourceBundle y falla con "No resources specified".
 * Un desajuste aquí solo se descubriría al abrir la pantalla en producción.
 */
class FxmlConsistenciaTest {

    private static final Path FXML  = Path.of("src/main/resources/fxml");
    private static final Path CTRL  = Path.of("src/main/java/me/eliassanchezfernandez/puntodeventa/controller");

    private static final Pattern CONTROLADOR = Pattern.compile("fx:controller=\"([\\w.]+)\"");
    private static final Pattern HANDLER     = Pattern.compile("on\\w+=\"#(\\w+)\"");
    private static final Pattern FX_ID       = Pattern.compile("fx:id=\"(\\w+)\"");
    private static final Pattern INCLUDE     = Pattern.compile("<fx:include[^>]*fx:id=\"(\\w+)\"");
    private static final Pattern METODO      = Pattern.compile("void\\s+(\\w+)\\s*\\(");
    private static final Pattern CAMPO_FXML  = Pattern.compile("@FXML\\s+(?:private|protected|public)?\\s*[\\w<>?,.\\s\\[\\]]+?\\s+(\\w+)\\s*;");
    private static final Pattern CLAVE_RECURSO = Pattern.compile("\\w+=\"%[^\\\\]");

    @Test
    void todosLosFxmlCoincidenConSuControlador() throws IOException {
        List<String> problemas = new ArrayList<>();
        int revisados = 0;

        try (Stream<Path> archivos = Files.list(FXML)) {
            for (Path fxml : archivos.filter(p -> p.toString().endsWith(".fxml")).sorted().toList()) {
                String xml = Files.readString(fxml);
                String nombre = fxml.getFileName().toString();

                Matcher m = CLAVE_RECURSO.matcher(xml);
                while (m.find()) {
                    problemas.add(nombre + ": valor que empieza con '%' (clave de ResourceBundle): " + m.group());
                }

                Matcher mc = CONTROLADOR.matcher(xml);
                if (!mc.find()) continue;   // borrador sin controlador: no se carga
                revisados++;
                String clase = mc.group(1).substring(mc.group(1).lastIndexOf('.') + 1);
                Path java = CTRL.resolve(clase + ".java");
                if (!Files.exists(java)) {
                    problemas.add(nombre + ": controlador inexistente " + clase);
                    continue;
                }
                String codigo = Files.readString(java);

                Set<String> metodos = extraer(METODO, codigo);
                Set<String> ids = extraer(FX_ID, xml);
                extraer(INCLUDE, xml).forEach(i -> ids.add(i + "Controller"));

                for (String h : extraer(HANDLER, xml)) {
                    if (!metodos.contains(h)) problemas.add(nombre + ": falta el método " + h + "() en " + clase);
                }
                for (String campo : extraer(CAMPO_FXML, codigo)) {
                    if (!ids.contains(campo)) problemas.add(nombre + ": el campo @FXML " + campo + " de " + clase + " no tiene fx:id");
                }
            }
        }

        assertThat(revisados).isGreaterThanOrEqualTo(12);
        assertThat(problemas).isEmpty();
    }

    private static Set<String> extraer(Pattern patron, String texto) {
        Set<String> r = new HashSet<>();
        Matcher m = patron.matcher(texto);
        while (m.find()) r.add(m.group(1));
        return r;
    }
}
