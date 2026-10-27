package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.NivelDatos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Sin Spring: nivelDatos(long) es una función pura. */
class EstadisticasServiceTest {

    private final EstadisticasService servicio = new EstadisticasService();

    @Test
    void nivelDeDatosRespetaLosLimites() {
        assertThat(servicio.nivelDatos(0)).isEqualTo(NivelDatos.ENTRENAMIENTO);
        assertThat(servicio.nivelDatos(6)).isEqualTo(NivelDatos.ENTRENAMIENTO);
        assertThat(servicio.nivelDatos(7)).isEqualTo(NivelDatos.PARCIAL);
        assertThat(servicio.nivelDatos(27)).isEqualTo(NivelDatos.PARCIAL);
        assertThat(servicio.nivelDatos(28)).isEqualTo(NivelDatos.SUFICIENTE);
    }
}
