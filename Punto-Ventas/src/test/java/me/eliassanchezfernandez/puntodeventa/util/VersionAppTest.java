package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** La versión sale de build.gradle (version.properties expandido por Gradle) y nunca se inventa. */
class VersionAppTest {

    @Test
    void laVersionCompiladaEsSemanticaYNoQuedoSinExpandir() {
        String v = VersionApp.version();
        assertThat(v).doesNotContain("${").isNotEqualTo(VersionApp.DESCONOCIDA);
        assertThat(VersionSemantica.parsear(v)).as("version.properties debe traer la versión de build.gradle").isPresent();
        assertThat(VersionApp.etiqueta()).isEqualTo("v" + v);
        assertThat(VersionApp.agenteUsuario()).isEqualTo("PuntoDeVenta/" + v);
    }

    @Test
    void siElRecursoNoSeExpandioUsaElManifiestoYSiNoDiceDesarrollo() {
        assertThat(VersionApp.desde("1.2.3", "9.9.9")).isEqualTo("1.2.3");
        assertThat(VersionApp.desde("${version}", "1.2.3")).isEqualTo("1.2.3");
        assertThat(VersionApp.desde(null, " 1.2.3 ")).isEqualTo("1.2.3");
        assertThat(VersionApp.desde("unspecified", null)).isEqualTo(VersionApp.DESCONOCIDA);
        assertThat(VersionApp.desde("", "")).isEqualTo(VersionApp.DESCONOCIDA);
    }
}
