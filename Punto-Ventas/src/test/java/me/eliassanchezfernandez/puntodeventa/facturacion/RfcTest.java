package me.eliassanchezfernandez.puntodeventa.facturacion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Forma del RFC: personas físicas, morales, genéricos y rechazos. */
class RfcTest {

    @Test
    void aceptaPersonasFisicasYMorales() {
        assertThat(Rfc.esValido("GODE561231GR8")).isTrue();      // física (13)
        assertThat(Rfc.esValido("gode561231gr8")).isTrue();      // se normaliza a mayúsculas
        assertThat(Rfc.esValido(" GODE 561231 GR8 ")).isTrue();  // espacios
        assertThat(Rfc.esValido("ABC010101AB1")).isTrue();       // moral (12)
        assertThat(Rfc.esValido("AÑO010101AB1")).isTrue();       // Ñ permitida
        assertThat(Rfc.esValido("A&B010101AB1")).isTrue();       // & permitido en morales

        assertThat(Rfc.esPersonaFisica("GODE561231GR8")).isTrue();
        assertThat(Rfc.esPersonaMoral("GODE561231GR8")).isFalse();
        assertThat(Rfc.esPersonaMoral("ABC010101AB1")).isTrue();
    }

    @Test
    void aceptaLosGenericos() {
        assertThat(Rfc.esValido(Rfc.PUBLICO_EN_GENERAL)).isTrue();
        assertThat(Rfc.esValido(Rfc.EXTRANJERO)).isTrue();
        assertThat(Rfc.esGenerico("xaxx010101000")).isTrue();
        assertThat(Rfc.esPersonaFisica(Rfc.PUBLICO_EN_GENERAL)).isFalse();
    }

    @Test
    void rechazaFormasInvalidas() {
        assertThat(Rfc.esValido(null)).isFalse();
        assertThat(Rfc.esValido("")).isFalse();
        assertThat(Rfc.esValido("GODE56123GR8")).isFalse();      // 12 pero 4 letras
        assertThat(Rfc.esValido("GODE561331GR8")).isFalse();     // mes 13
        assertThat(Rfc.esValido("GODE560230GR8")).isFalse();     // 30 de febrero
        assertThat(Rfc.esValido("GODE561231GR")).isFalse();      // homoclave corta
        assertThat(Rfc.esValido("1ODE561231GR8")).isFalse();     // dígito en las letras
        assertThat(Rfc.esValido("GODE561231GR8X")).isFalse();    // 14

        assertThatThrownBy(() -> Rfc.validar("MAL"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("formato");
        assertThatThrownBy(() -> Rfc.validar("  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("obligatorio");
        assertThat(Rfc.validar("gode561231gr8")).isEqualTo("GODE561231GR8");
    }
}
