package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Fechas de la vista Hoy: funciones puras, sin reloj ni base de datos. */
class FechasComparacionTest {

    @Test
    void ayerEsElDiaAnteriorAunqueCambieElMesOElAnio() {
        assertThat(FechasComparacion.ayer(LocalDate.of(2026, 11, 14))).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(FechasComparacion.ayer(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(FechasComparacion.ayer(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void elMismoDiaDeLaSemanaPasadaEsSieteDiasAntesYCaeEnElMismoDiaDeLaSemana() {
        LocalDate sabado = LocalDate.of(2026, 11, 14);
        assertThat(sabado.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        LocalDate hace7 = FechasComparacion.mismoDiaSemanaPasada(sabado);
        assertThat(hace7).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(hace7.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        // Cruzando el cambio de mes
        assertThat(FechasComparacion.mismoDiaSemanaPasada(LocalDate.of(2026, 3, 3))).isEqualTo(LocalDate.of(2026, 2, 24));
        assertThat(FechasComparacion.etiquetaSemanaPasada(sabado)).isEqualTo("vs. el sábado pasado");
        assertThat(FechasComparacion.etiquetaSemanaPasada(LocalDate.of(2026, 11, 11))).isEqualTo("vs. el miércoles pasado");
    }

    @Test
    void hoyHaceUnAnioEsLaMismaFechaDelAnioAnterior() {
        assertThat(FechasComparacion.hoyHaceUnAnio(LocalDate.of(2026, 11, 14))).isEqualTo(LocalDate.of(2025, 11, 14));
        assertThat(FechasComparacion.hoyHaceUnAnio(LocalDate.of(2025, 3, 1))).isEqualTo(LocalDate.of(2024, 3, 1));
    }

    @Test
    void el29DeFebreroSeComparaConEl28DeFebreroDelAnioAnterior() {
        assertThat(FechasComparacion.hoyHaceUnAnio(LocalDate.of(2028, 2, 29))).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(FechasComparacion.hoyHaceUnAnio(LocalDate.of(2024, 2, 29))).isEqualTo(LocalDate.of(2023, 2, 28));
        // Y un 28 de febrero de año no bisiesto se compara con el 28 del bisiesto anterior (no con el 29)
        assertThat(FechasComparacion.hoyHaceUnAnio(LocalDate.of(2025, 2, 28))).isEqualTo(LocalDate.of(2024, 2, 28));
    }

    @Test
    void laTiendaYaVendiaSoloDesdeSuPrimeraVenta() {
        LocalDate primera = LocalDate.of(2026, 6, 10);
        assertThat(FechasComparacion.laTiendaYaVendia(primera, LocalDate.of(2026, 6, 10))).isTrue();
        assertThat(FechasComparacion.laTiendaYaVendia(primera, LocalDate.of(2026, 11, 13))).isTrue();
        assertThat(FechasComparacion.laTiendaYaVendia(primera, LocalDate.of(2026, 6, 9))).isFalse();
        // Tienda con menos de un año: "hoy hace un año" cae antes de la primera venta
        LocalDate hoy = LocalDate.of(2026, 11, 14);
        assertThat(FechasComparacion.laTiendaYaVendia(primera, FechasComparacion.hoyHaceUnAnio(hoy))).isFalse();
        assertThat(FechasComparacion.laTiendaYaVendia(primera, FechasComparacion.mismoDiaSemanaPasada(hoy))).isTrue();
        // Sin ninguna venta no hay con qué comparar
        assertThat(FechasComparacion.laTiendaYaVendia(null, hoy)).isFalse();
    }

    @Test
    void lasEtiquetasSonFrasesLlanas() {
        assertThat(FechasComparacion.etiquetaAyer()).isEqualTo("vs. ayer");
        assertThat(FechasComparacion.etiquetaHaceUnAnio()).isEqualTo("vs. hoy hace un año");
        assertThat(FechasComparacion.nombreDia(DayOfWeek.MONDAY)).isEqualTo("lunes");
    }
}
