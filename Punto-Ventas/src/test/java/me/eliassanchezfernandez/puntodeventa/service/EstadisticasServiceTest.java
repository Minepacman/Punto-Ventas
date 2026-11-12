package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.NivelDatos;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.MadurezDatos;
import me.eliassanchezfernandez.puntodeventa.service.RecomendacionService.Pesos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Sin Spring: nivelDatos(long) y MadurezDatos son funciones puras. */
class EstadisticasServiceTest {

    private final EstadisticasService servicio = new EstadisticasService();

    @Test
    void nivelDeDatosRespetaLosLimites() {
        assertThat(servicio.nivelDatos(0)).isEqualTo(NivelDatos.ENTRENAMIENTO);
        assertThat(servicio.nivelDatos(6)).isEqualTo(NivelDatos.ENTRENAMIENTO);
        assertThat(servicio.nivelDatos(7)).isEqualTo(NivelDatos.PARCIAL);
        assertThat(servicio.nivelDatos(29)).isEqualTo(NivelDatos.PARCIAL);
        assertThat(servicio.nivelDatos(EstadisticasService.DIAS_CONFIABLES)).isEqualTo(NivelDatos.SUFICIENTE);
        assertThat(EstadisticasService.DIAS_CONFIABLES).isEqualTo(30);
    }

    @Test
    void laMadurezCuentaDiasYAvisaHastaLos30() {
        MadurezDatos dia0 = new MadurezDatos(0, 1, NivelDatos.ENTRENAMIENTO);
        assertThat(dia0.hayVentas()).isTrue();
        assertThat(dia0.confiable()).isFalse();
        assertThat(dia0.factorConfianza()).isZero();
        assertThat(dia0.diasFaltantes()).isEqualTo(30);
        assertThat(dia0.mensaje()).contains("Datos de 0 días").contains("a partir de 30 días").contains("faltan 30");

        MadurezDatos dia15 = new MadurezDatos(15, 12, NivelDatos.PARCIAL);
        assertThat(dia15.factorConfianza()).isCloseTo(0.5, within(0.001));
        assertThat(dia15.porcentajeConfianza()).isEqualTo(50);
        assertThat(dia15.mensaje()).contains("15 días").contains("12 con ventas").contains("faltan 15");

        MadurezDatos dia30 = new MadurezDatos(30, 28, NivelDatos.SUFICIENTE);
        assertThat(dia30.confiable()).isTrue();
        assertThat(dia30.factorConfianza()).isEqualTo(1.0);
        assertThat(dia30.diasFaltantes()).isZero();
        assertThat(dia30.mensaje()).contains("confiables").doesNotContain("faltan");

        MadurezDatos dia90 = new MadurezDatos(90, 80, NivelDatos.SUFICIENTE);
        assertThat(dia90.factorConfianza()).isEqualTo(1.0);   // nunca pasa de 1

        assertThat(MadurezDatos.SIN_DATOS.hayVentas()).isFalse();
        assertThat(MadurezDatos.SIN_DATOS.factorConfianza()).isZero();
        assertThat(MadurezDatos.SIN_DATOS.mensaje()).contains("Aún no hay ventas");
    }

    @Test
    void elTopeDeConfianzaSubeDiaADiaConLaMadurez() {
        Pesos sinDatos = Pesos.para(MadurezDatos.SIN_DATOS, true);
        assertThat(sinDatos.tope()).isCloseTo(0.35, within(0.001));
        assertThat(Pesos.para(new MadurezDatos(0, 1, NivelDatos.ENTRENAMIENTO), true).tope()).isCloseTo(0.35, within(0.001));
        assertThat(Pesos.para(new MadurezDatos(15, 15, NivelDatos.PARCIAL), true).tope()).isCloseTo(0.675, within(0.001));
        assertThat(Pesos.para(new MadurezDatos(30, 30, NivelDatos.SUFICIENTE), true).tope()).isCloseTo(1.0, within(0.001));
        assertThat(Pesos.para(new MadurezDatos(200, 180, NivelDatos.SUFICIENTE), false).tope()).isCloseTo(1.0, within(0.001));

        // Los pesos de las fuentes siguen siendo los del nivel
        Pesos parcial = Pesos.para(new MadurezDatos(15, 15, NivelDatos.PARCIAL), true);
        Pesos porNivel = Pesos.para(NivelDatos.PARCIAL, true);
        assertThat(parcial.canasta()).isEqualTo(porNivel.canasta());
        assertThat(parcial.generico()).isEqualTo(porNivel.generico());
        // El tope crece de forma monótona
        double anterior = 0;
        for (int d = 0; d <= 30; d++) {
            double tope = Pesos.para(new MadurezDatos(d, d, servicio.nivelDatos(d)), true).tope();
            assertThat(tope).isGreaterThanOrEqualTo(anterior);
            anterior = tope;
        }
    }
}
