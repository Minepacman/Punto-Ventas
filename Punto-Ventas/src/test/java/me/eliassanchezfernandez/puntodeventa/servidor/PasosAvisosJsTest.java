package me.eliassanchezfernandez.puntodeventa.servidor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las funciones puras del asistente de avisos (`pasosAvisos` en movil/index.html: plataforma,
 * versión de iOS, siguiente paso, lectura del #traspaso) se prueban con Node si está instalado
 * (el proyecto no trae un motor de JavaScript). Sin Node la prueba se omite; el bloque marcado sí
 * se comprueba siempre.
 */
class PasosAvisosJsTest {

    private static String recurso(String ruta) throws Exception {
        try (InputStream in = PasosAvisosJsTest.class.getResourceAsStream(ruta)) {
            assertThat(in).as(ruta).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String bloquePuro() throws Exception {
        String html = recurso("/movil/index.html");
        int ini = html.indexOf("/* PASOS-AVISOS:INICIO");
        int fin = html.indexOf("/* PASOS-AVISOS:FIN */");
        assertThat(ini).as("bloque marcado en index.html").isPositive();
        assertThat(fin).isGreaterThan(ini);
        String bloque = html.substring(ini, fin);
        // Puras: sin DOM, red ni almacenamiento
        assertThat(bloque).doesNotContain("document.").doesNotContain("fetch(").doesNotContain("localStorage")
                .doesNotContain("navigator.").doesNotContain("api.");
        return bloque;
    }

    private static boolean hayNode() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void lasFuncionesPurasDelAsistenteDecidenElPasoCorrecto() throws Exception {
        String bloque = bloquePuro();
        Assumptions.assumeTrue(hayNode(), "Node no está instalado: se omite la prueba de JavaScript");
        Path dir = Files.createTempDirectory("pasos-avisos");
        try {
            Path pasos = dir.resolve("pasos.js");
            Path prueba = dir.resolve("prueba.js");
            Files.writeString(pasos, bloque + "\nmodule.exports = pasosAvisos;\n");
            Files.writeString(prueba, recurso("/movil/pasos-avisos.prueba.js"));
            Process p = new ProcessBuilder("node", prueba.toString(), pasos.toString()).redirectErrorStream(true).start();
            String salida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).as(salida).isZero();
            assertThat(salida).contains("todas las pruebas pasaron");
        } finally {
            try (var s = Files.list(dir)) { for (Path f : s.toList()) Files.deleteIfExists(f); }
            Files.deleteIfExists(dir);
        }
    }
}
