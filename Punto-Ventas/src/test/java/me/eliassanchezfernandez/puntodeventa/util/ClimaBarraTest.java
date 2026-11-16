package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clima de la cabecera ("24 °C · Soleado"): texto, icono según el código WMO,
 * tooltip con la ciudad y la hora, y atenuado con "(hace X h)" pasadas 3 horas.
 * Funciones puras: sin JavaFX, red ni base de datos.
 */
class ClimaBarraTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 14, 14, 20);

    @Test
    void elTextoLlevaTemperaturaRedondeadaYCondicionCorta() {
        assertThat(ClimaBarra.texto(24.4, 0)).isEqualTo("24 °C · Soleado");
        assertThat(ClimaBarra.texto(24.5, 3)).isEqualTo("25 °C · Nublado");
        assertThat(ClimaBarra.texto(-0.3, 71)).isEqualTo("0 °C · Nieve");
        assertThat(ClimaBarra.texto(-3.6, 95)).isEqualTo("-4 °C · Tormenta");
    }

    @Test
    void elCodigoWmoSeTraduceAUnaCondicionCortaEnEspanol() {
        assertThat(ClimaBarra.condicion(0)).isEqualTo("Soleado");
        assertThat(ClimaBarra.condicion(1)).isEqualTo("Despejado");
        assertThat(ClimaBarra.condicion(2)).isEqualTo("Poco nublado");
        assertThat(ClimaBarra.condicion(3)).isEqualTo("Nublado");
        assertThat(ClimaBarra.condicion(45)).isEqualTo("Niebla");
        assertThat(ClimaBarra.condicion(48)).isEqualTo("Niebla");
        assertThat(ClimaBarra.condicion(51)).isEqualTo("Llovizna");
        assertThat(ClimaBarra.condicion(57)).isEqualTo("Llovizna");
        assertThat(ClimaBarra.condicion(61)).isEqualTo("Lluvia");
        assertThat(ClimaBarra.condicion(67)).isEqualTo("Lluvia");
        assertThat(ClimaBarra.condicion(71)).isEqualTo("Nieve");
        assertThat(ClimaBarra.condicion(77)).isEqualTo("Nieve");
        assertThat(ClimaBarra.condicion(80)).isEqualTo("Chubascos");
        assertThat(ClimaBarra.condicion(82)).isEqualTo("Chubascos");
        assertThat(ClimaBarra.condicion(85)).isEqualTo("Nieve");
        assertThat(ClimaBarra.condicion(86)).isEqualTo("Nieve");
        assertThat(ClimaBarra.condicion(95)).isEqualTo("Tormenta");
        assertThat(ClimaBarra.condicion(99)).isEqualTo("Tormenta");
        assertThat(ClimaBarra.condicion(1234)).isEqualTo("Nublado");   // desconocido: neutro, como ClimaService
    }

    @Test
    void cadaCodigoTieneUnIconoDelCatalogo() {
        assertThat(ClimaBarra.icono(0)).isEqualTo("sol");
        assertThat(ClimaBarra.icono(1)).isEqualTo("sol");
        assertThat(ClimaBarra.icono(2)).isEqualTo("climaSol");
        assertThat(ClimaBarra.icono(3)).isEqualTo("nube");
        assertThat(ClimaBarra.icono(45)).isEqualTo("niebla");
        assertThat(ClimaBarra.icono(55)).isEqualTo("lluvia");
        assertThat(ClimaBarra.icono(63)).isEqualTo("lluvia");
        assertThat(ClimaBarra.icono(81)).isEqualTo("lluvia");
        assertThat(ClimaBarra.icono(73)).isEqualTo("nieve");
        assertThat(ClimaBarra.icono(96)).isEqualTo("tormenta");
        assertThat(ClimaBarra.icono(1234)).isEqualTo("nube");
        // Todos los iconos que puede pedir existen en util/Iconos (si no, la cabecera quedaría sin dibujo)
        for (int codigo : new int[] {0, 1, 2, 3, 45, 48, 51, 61, 71, 80, 85, 95, 1234}) {
            assertThat(Iconos.existe(ClimaBarra.icono(codigo))).as("código WMO " + codigo).isTrue();
        }
    }

    @Test
    void unRegistroDeMenosDeTresHorasEstaVigente() {
        assertThat(ClimaBarra.desactualizado(AHORA.minusMinutes(20), AHORA)).isFalse();
        assertThat(ClimaBarra.desactualizado(AHORA.minusHours(3), AHORA)).isFalse();        // justo 3 h: todavía vale
        assertThat(ClimaBarra.desactualizado(AHORA.minusHours(3).minusMinutes(1), AHORA)).isTrue();
        assertThat(ClimaBarra.desactualizado(AHORA.minusDays(2), AHORA)).isTrue();
    }

    @Test
    void laAntiguedadSeDiceEnHorasYDesdeDosDiasEnDias() {
        assertThat(ClimaBarra.antiguedad(AHORA.minusHours(5).minusMinutes(10), AHORA)).isEqualTo("(hace 5 h)");
        assertThat(ClimaBarra.antiguedad(AHORA.minusHours(47), AHORA)).isEqualTo("(hace 47 h)");
        assertThat(ClimaBarra.antiguedad(AHORA.minusHours(48), AHORA)).isEqualTo("(hace 2 días)");
        assertThat(ClimaBarra.antiguedad(AHORA.minusDays(5), AHORA)).isEqualTo("(hace 5 días)");
        assertThat(ClimaBarra.antiguedad(AHORA.plusMinutes(5), AHORA)).isEqualTo("(hace 0 h)");   // reloj adelantado: nunca negativo
    }

    @Test
    void elTooltipDiceLaCiudadYLaHoraYSoloSiEstaViejoLaAntiguedad() {
        LocalDateTime registro = LocalDateTime.of(2026, 11, 14, 14, 0);
        assertThat(ClimaBarra.tooltip("Xalapa", registro, AHORA))
                .isEqualTo("Clima de Xalapa, actualizado a las 14:00");
        assertThat(ClimaBarra.tooltip("  Córdoba, Ver.  ", registro, AHORA))
                .isEqualTo("Clima de Córdoba, Ver., actualizado a las 14:00");
        assertThat(ClimaBarra.tooltip(null, registro, AHORA))
                .isEqualTo("Clima del negocio, actualizado a las 14:00");
        assertThat(ClimaBarra.tooltip("  ", registro, AHORA))
                .isEqualTo("Clima del negocio, actualizado a las 14:00");
        assertThat(ClimaBarra.tooltip("Xalapa", LocalDateTime.of(2026, 11, 14, 9, 0), AHORA))
                .isEqualTo("Clima de Xalapa, actualizado a las 09:00 (hace 5 h)");
    }

    @Test
    void elIndicadorSeArmaDesdeElUltimoRegistro() {
        ClimaRegistro vigente = new ClimaRegistro(LocalDateTime.of(2026, 11, 14, 14, 0), 23.7, 0, "Despejado", "Templado");
        ClimaBarra.Indicador i = ClimaBarra.de(vigente, "Xalapa", AHORA);
        assertThat(i.texto()).isEqualTo("24 °C · Soleado");
        assertThat(i.icono()).isEqualTo("sol");
        assertThat(i.tooltip()).isEqualTo("Clima de Xalapa, actualizado a las 14:00");
        assertThat(i.desactualizado()).isFalse();

        ClimaRegistro viejo = new ClimaRegistro(LocalDateTime.of(2026, 11, 14, 6, 0), 12.2, 61, "Lluvia", "Frío");
        ClimaBarra.Indicador v = ClimaBarra.de(viejo, null, AHORA);
        assertThat(v.texto()).isEqualTo("12 °C · Lluvia");
        assertThat(v.icono()).isEqualTo("lluvia");
        assertThat(v.tooltip()).isEqualTo("Clima del negocio, actualizado a las 06:00 (hace 8 h)");
        assertThat(v.desactualizado()).isTrue();
    }
}
