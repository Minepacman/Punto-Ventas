package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Normalización de códigos de barras como la hace el catálogo codigos-barras-mx */
class CodigoBarrasTest {

    @Test
    void ean13ValidoSeQuedaIgual() {
        assertThat(CodigoBarras.normalizar("7501055300075")).contains("7501055300075");
        assertThat(CodigoBarras.normalizar("  7501055305339 ")).contains("7501055305339");
    }

    @Test
    void upcAde12DigitosSeEscribeComoEan13ConCeroAlFrente() {
        assertThat(CodigoBarras.normalizar("744886534234")).contains("0744886534234");
    }

    @Test
    void ean8SeQuedaEn8() {
        assertThat(CodigoBarras.normalizar("96385074")).contains("96385074");
        assertThat(CodigoBarras.normalizar("00000154")).contains("00000154");
    }

    @Test
    void gtin14ConCeroDeRellenoSeReduceA13() {
        assertThat(CodigoBarras.normalizar("07501055300075")).contains("7501055300075");
        // Un GTIN-14 "de verdad" (indicador distinto de 0) no es un EAN-13
        assertThat(CodigoBarras.normalizar("17501055300075")).isEmpty();
    }

    @Test
    void seAdmitenEspaciosYGuionesComoSeparadores() {
        assertThat(CodigoBarras.normalizar("750 105 530 0075")).contains("7501055300075");
        assertThat(CodigoBarras.normalizar("7-501055-300075")).contains("7501055300075");
    }

    @Test
    void codigosInternosYBasuraNoSeNormalizan() {
        assertThat(CodigoBarras.normalizar("PRE-0001")).isEmpty();
        assertThat(CodigoBarras.normalizar("T-a1b2c3d4")).isEmpty();
        assertThat(CodigoBarras.normalizar("123")).isEmpty();
        assertThat(CodigoBarras.normalizar("")).isEmpty();
        assertThat(CodigoBarras.normalizar("   ")).isEmpty();
        assertThat(CodigoBarras.normalizar(null)).isEmpty();
        assertThat(CodigoBarras.normalizar("75010553000")).isEmpty();        // 11 dígitos
        assertThat(CodigoBarras.normalizar("750105530007512")).isEmpty();    // 15 dígitos
    }

    @Test
    void elDigitoDeControlSeComprueba() {
        assertThat(CodigoBarras.digitoDeControlValido("7501055300075")).isTrue();
        assertThat(CodigoBarras.digitoDeControlValido("7501055300076")).isFalse();
        assertThat(CodigoBarras.digitoDeControlValido("4006381333931")).isTrue();
        assertThat(CodigoBarras.digitoDeControlValido("12345670")).isTrue();
        assertThat(CodigoBarras.digitoDeControlValido("12345671")).isFalse();
        assertThat(CodigoBarras.normalizar("7501055300076")).isEmpty();
        assertThat(CodigoBarras.digitoDeControlValido("75010553000a5")).isFalse();
        assertThat(CodigoBarras.digitoDeControlValido(null)).isFalse();
    }

    @Test
    void elFragmentoSonLosUltimosTresDigitos() {
        assertThat(CodigoBarras.fragmento("7501055300075")).isEqualTo("075");
        assertThat(CodigoBarras.fragmento("96385074")).isEqualTo("074");
    }
}
