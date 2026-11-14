package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Entrega de los códigos leídos durante un modal al destino registrado (la parte sin JavaFX). */
class LectorEnModalesTest {

    @AfterEach
    void sinDestino() {
        LectorEnModales.registrarDestino(null);
    }

    @Test
    void losCodigosLlegamAlDestinoEnOrden() {
        List<String> recibidos = new ArrayList<>();
        LectorEnModales.registrarDestino(c -> recibidos.add(c));

        LectorEnModales.entregar(List.of("7501055300006", "4321"));

        assertThat(recibidos).containsExactly("7501055300006", "4321");
    }

    @Test
    void siElDestinoNoPuedeTomarlosSeDescartanSinError() {
        List<String> recibidos = new ArrayList<>();
        LectorEnModales.registrarDestino(c -> { recibidos.add(c); return false; });   // Ventas no está a la vista

        LectorEnModales.entregar(List.of("111", "222"));

        assertThat(recibidos).as("se intenta una vez y no se insiste con los demás").containsExactly("111");
    }

    @Test
    void sinDestinoRegistradoNoPasaNada() {
        LectorEnModales.registrarDestino(null);
        assertThat(LectorEnModales.destinoActual()).isNull();
        LectorEnModales.entregar(List.of("111"));   // no lanza
    }

    @Test
    void elUltimoDestinoRegistradoGana() {
        List<String> viejo = new ArrayList<>(), nuevo = new ArrayList<>();
        LectorEnModales.registrarDestino(viejo::add);
        LectorEnModales.registrarDestino(nuevo::add);

        LectorEnModales.entregar(List.of("9"));

        assertThat(viejo).isEmpty();
        assertThat(nuevo).containsExactly("9");
    }
}
