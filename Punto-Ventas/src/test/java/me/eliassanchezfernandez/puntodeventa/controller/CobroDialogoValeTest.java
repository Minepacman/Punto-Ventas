package me.eliassanchezfernandez.puntodeventa.controller;

import me.eliassanchezfernandez.puntodeventa.service.ValeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Textos y validación del renglón «Vale de la tienda» de la ventana de cobro (sin interfaz) */
class CobroDialogoValeTest {

    private static final ValeService.Consulta VALE = new ValeService.Consulta(1L, "K7M29QXA", 150, 150, null, null);

    @Test
    void sinBuscarElValeNoSeCobraConMonto() {
        assertThat(CobroDialogo.problemaVale(null, 0)).isNull();
        assertThat(CobroDialogo.problemaVale(null, 50)).contains("pulsa Enter");
    }

    @Test
    void noSeUsaMasDeLoQueTiene() {
        assertThat(CobroDialogo.problemaVale(VALE, 150)).isNull();
        assertThat(CobroDialogo.problemaVale(VALE, 200)).contains("solo le quedan $150.00");
    }

    @Test
    void diceCuantoSeUsaYCuantoLeQueda() {
        assertThat(CobroDialogo.textoVale(VALE, 120)).contains("K7M2-9QXA").contains("se usan $120.00").contains("le quedarán $30.00");
        assertThat(CobroDialogo.textoVale(VALE, 150)).contains("se usa completo");
        assertThat(VALE.aUsar(80)).isEqualTo(80);
        assertThat(VALE.aUsar(500)).isEqualTo(150);
    }
}
