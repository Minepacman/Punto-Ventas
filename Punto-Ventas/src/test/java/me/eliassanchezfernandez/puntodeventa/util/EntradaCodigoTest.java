package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Captura "cantidad*código" en el campo de código de Ventas. */
class EntradaCodigoTest {

    @Test
    void codigoSoloEsCantidadUno() {
        assertThat(EntradaCodigo.interpretar("7501055300006")).contains(new EntradaCodigo.Captura("7501055300006", 1));
        assertThat(EntradaCodigo.interpretar("  ABC-12  ")).contains(new EntradaCodigo.Captura("ABC-12", 1));
        assertThat(EntradaCodigo.traeCantidad("7501055300006")).isFalse();
    }

    @Test
    void cantidadAsteriscoCodigo() {
        assertThat(EntradaCodigo.interpretar("3*7501055300006")).contains(new EntradaCodigo.Captura("7501055300006", 3));
        assertThat(EntradaCodigo.interpretar("3 * 7501055300006")).contains(new EntradaCodigo.Captura("7501055300006", 3));
        assertThat(EntradaCodigo.interpretar("1.5*PLATANO")).contains(new EntradaCodigo.Captura("PLATANO", 1.5));
        assertThat(EntradaCodigo.interpretar("1,250*PLATANO")).contains(new EntradaCodigo.Captura("PLATANO", 1.25));
        assertThat(EntradaCodigo.interpretar("2x4321")).contains(new EntradaCodigo.Captura("4321", 2));
        assertThat(EntradaCodigo.interpretar("2 X 4321")).contains(new EntradaCodigo.Captura("4321", 2));
        assertThat(EntradaCodigo.traeCantidad("3*")).isTrue();
    }

    @Test
    void cantidadInvalidaOVaciaNoSeAcepta() {
        assertThat(EntradaCodigo.interpretar("0*123")).isEmpty();
        assertThat(EntradaCodigo.interpretar("3*")).isEmpty();       // falta el código (todavía no se escanea)
        assertThat(EntradaCodigo.interpretar("")).isEmpty();
        assertThat(EntradaCodigo.interpretar(null)).isEmpty();
        assertThat(EntradaCodigo.interpretar("1.2.3*123")).isEmpty();
    }

    @Test
    void unCodigoConXOAsteriscoAdentroNoSeConfunde() {
        // Empieza con letra: es un código tal cual, aunque tenga x o * adentro
        assertThat(EntradaCodigo.interpretar("AX*12")).contains(new EntradaCodigo.Captura("AX*12", 1));
        assertThat(EntradaCodigo.interpretar("P2x4")).contains(new EntradaCodigo.Captura("P2x4", 1));
        // Solo dígitos con una x al final no es cantidad (no hay código después)
        assertThat(EntradaCodigo.interpretar("12x")).contains(new EntradaCodigo.Captura("12x", 1));
    }
}
