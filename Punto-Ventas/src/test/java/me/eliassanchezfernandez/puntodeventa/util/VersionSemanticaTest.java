package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Orden de versiones semánticas: numérico (no alfabético) y prelanzamientos por debajo de la final. */
class VersionSemanticaTest {

    private static VersionSemantica v(String s) {
        return VersionSemantica.parsear(s).orElseThrow();
    }

    @Test
    void parseaConYSinPrefijoYConMetadatos() {
        assertThat(v("1.2.3")).isEqualTo(new VersionSemantica(1, 2, 3, null));
        assertThat(v("v1.2.3")).isEqualTo(new VersionSemantica(1, 2, 3, null));
        assertThat(v("1.2")).isEqualTo(new VersionSemantica(1, 2, 0, null));
        assertThat(v("1.1.0-beta.1+build.7")).isEqualTo(new VersionSemantica(1, 1, 0, "beta.1"));
        assertThat(v("1.1.0-beta.1").esPrelanzamiento()).isTrue();
        assertThat(v("1.1.0").esPrelanzamiento()).isFalse();
    }

    @Test
    void rechazaLoQueNoEsVersion() {
        assertThat(VersionSemantica.parsear("desarrollo")).isEmpty();
        assertThat(VersionSemantica.parsear("latest")).isEmpty();
        assertThat(VersionSemantica.parsear("")).isEmpty();
        assertThat(VersionSemantica.parsear(null)).isEmpty();
        assertThat(VersionSemantica.parsear("1")).isEmpty();
        assertThat(VersionSemantica.parsear("1.2.3.4")).isEmpty();
        assertThat(VersionSemantica.parsear("99999999999.0.0")).isEmpty();
    }

    @Test
    void comparaNumericamenteNoComoTexto() {
        assertThat(v("1.10.0").esMayorQue(v("1.9.0"))).isTrue();
        assertThat(v("1.9.0").esMayorQue(v("1.10.0"))).isFalse();
        assertThat(v("2.0.0").esMayorQue(v("1.99.99"))).isTrue();
        assertThat(v("1.0.1").esMayorQue(v("1.0.0"))).isTrue();
        assertThat(v("1.0.0").compareTo(v("v1.0.0"))).isZero();
    }

    @Test
    void losPrelanzamientosQuedanDebajoDeLaFinalYSeOrdenanEntreSi() {
        assertThat(v("1.0.0").esMayorQue(v("1.0.0-rc.1"))).isTrue();
        assertThat(v("1.0.0-rc.1").esMayorQue(v("1.0.0"))).isFalse();
        assertThat(v("1.0.0-rc.1").esMayorQue(v("1.0.0-beta.9"))).isTrue();      // alfabético: rc > beta
        assertThat(v("1.0.0-beta.10").esMayorQue(v("1.0.0-beta.9"))).isTrue();   // numérico dentro del identificador
        assertThat(v("1.0.0-beta.1").esMayorQue(v("1.0.0-beta"))).isTrue();      // el más largo gana
        assertThat(v("1.0.0-beta").esMayorQue(v("1.0.0-1"))).isTrue();           // numérico < alfanumérico
        assertThat(v("1.0.1-beta.1").esMayorQue(v("1.0.0"))).isTrue();           // un parche nuevo en beta supera a la final anterior
    }

    @Test
    void laFormaNumericaEsLaQueExigeJpackage() {
        assertThat(v("v1.1.0-beta.1").numerica()).isEqualTo("1.1.0");
        assertThat(v("1.1.0-beta.1").toString()).isEqualTo("1.1.0-beta.1");
    }
}
