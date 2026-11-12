package me.eliassanchezfernandez.puntodeventa;

import me.eliassanchezfernandez.puntodeventa.util.Marca;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El nombre visible del programa es «Caja Libre» ({@link Marca#NOMBRE}). «Punto de Venta»
 * con mayúsculas (como nombre propio) ya no debe aparecer en lo que ve el usuario: FXML,
 * página móvil, CSS y cadenas de Java. «punto de venta» en minúsculas sigue siendo el
 * sustantivo genérico y se permite. Los nombres técnicos (PuntoDeVenta.app, carpeta
 * PuntoDeVenta, paquete punto-de-venta, repositorio Punto-Ventas) no cambian a propósito.
 */
class NombreProductoTest {

    private static final Path MAIN = Path.of("src/main");
    private static final Pattern NOMBRE_VIEJO = Pattern.compile("Punto de Venta|PUNTO DE VENTA");

    @Test
    void elNombreVisibleEsCajaLibre() throws IOException {
        assertThat(Marca.NOMBRE).isEqualTo("Caja Libre");
        List<String> problemas = new ArrayList<>();
        try (Stream<Path> archivos = Files.walk(MAIN)) {
            for (Path p : archivos.filter(Files::isRegularFile).toList()) {
                String n = p.toString();
                if (!(n.endsWith(".java") || n.endsWith(".fxml") || n.endsWith(".html") || n.endsWith(".css") || n.endsWith(".properties"))) continue;
                String[] lineas = Files.readString(p, StandardCharsets.UTF_8).split("\n");
                for (int i = 0; i < lineas.length; i++) {
                    if (NOMBRE_VIEJO.matcher(lineas[i]).find()) problemas.add(MAIN.relativize(p) + ":" + (i + 1) + ": " + lineas[i].trim());
                }
            }
        }
        assertThat(problemas).as("«Punto de Venta» como nombre del producto; usa Marca.NOMBRE / «Caja Libre»").isEmpty();
    }

    @Test
    void losFxmlDeMarcaDicenCajaLibre() throws IOException {
        for (String f : new String[] {"arranque.fxml", "main.fxml", "login.fxml"}) {
            assertThat(Files.readString(Path.of("src/main/resources/fxml", f))).as(f).contains("text=\"Caja Libre\"");
        }
        String movil = Files.readString(Path.of("src/main/resources/movil/index.html"));
        assertThat(movil).contains("<meta name=\"application-name\" content=\"Caja Libre\">").contains("<title>Caja Libre");
    }
}
