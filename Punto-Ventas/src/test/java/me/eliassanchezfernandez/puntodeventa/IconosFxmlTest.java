package me.eliassanchezfernandez.puntodeventa;

import me.eliassanchezfernandez.puntodeventa.util.Iconos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los iconos de la interfaz son Lucide (util/Iconos), no emojis: un emoji depende de la fuente del sistema
 * (en Windows 10 viejo salen como cuadros o en color) y desentona con la página móvil.
 */
class IconosFxmlTest {

    private static final Path FXML = Path.of("src/main/resources/fxml");
    private static final Pattern ICONO = Pattern.compile("<Icono\\s[^>]*?nombre=\"([^\"]*)\"");
    /** Pictogramas: flechas de una línea (U+2190-21FF) y "→" como texto no cuentan; los símbolos sí. */
    private static final Pattern EMOJI = Pattern.compile(
            "[\\u2300-\\u23FF\\u25A0-\\u27BF\\u2B00-\\u2BFF\\uFE0F\\x{1F000}-\\x{1FAFF}]");

    @Test
    void losFxmlNoUsanEmojisYSusIconosExisten() throws IOException {
        List<String> problemas = new ArrayList<>();
        int iconos = 0;
        try (Stream<Path> archivos = Files.list(FXML)) {
            for (Path f : archivos.filter(p -> p.toString().endsWith(".fxml")).sorted().toList()) {
                String xml = Files.readString(f);
                String nombre = f.getFileName().toString();
                Matcher e = EMOJI.matcher(xml);
                while (e.find()) {
                    problemas.add(nombre + ": emoji/símbolo U+" + Integer.toHexString(xml.codePointAt(e.start())).toUpperCase());
                }
                Matcher m = ICONO.matcher(xml);
                while (m.find()) {
                    iconos++;
                    if (!Iconos.existe(m.group(1))) problemas.add(nombre + ": icono inexistente '" + m.group(1) + "'");
                }
                if (xml.contains("<Icono") && !xml.contains("<?import me.eliassanchezfernandez.puntodeventa.util.Icono?>")) {
                    problemas.add(nombre + ": usa <Icono> sin importarlo");
                }
            }
        }
        assertThat(problemas).isEmpty();
        assertThat(iconos).isGreaterThan(50);
    }

    @Test
    void todoIconoTieneTrazos() {
        for (String n : Iconos.nombres()) {
            assertThat(Iconos.trazos(n)).as(n).isNotEmpty();
        }
        assertThat(Iconos.existe("buscar")).isTrue();
        assertThat(Iconos.existe("no-existe")).isFalse();
    }
}
