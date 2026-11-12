package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.Venta.FormaPago;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Cuentas del cobro en pantalla: mismas reglas que VentaService.cobrar (VEN-07, VEN-08). */
class CalculoCobroTest {

    private static Map<FormaPago, Double> montos(Object... pares) {
        Map<FormaPago, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) m.put((FormaPago) pares[i], ((Number) pares[i + 1]).doubleValue());
        return m;
    }

    @Test
    void efectivoConCambio() {
        CalculoCobro.Resultado r = CalculoCobro.calcular(137.5, montos(FormaPago.EFECTIVO, 200));
        assertThat(r.esValido()).isTrue();
        assertThat(r.pagado()).isEqualTo(200);
        assertThat(r.cambio()).isCloseTo(62.5, within(0.001));
        assertThat(r.faltante()).isEqualTo(0);
    }

    @Test
    void efectivoInsuficienteDiceCuantoFalta() {
        CalculoCobro.Resultado r = CalculoCobro.calcular(137.5, montos(FormaPago.EFECTIVO, 100));
        assertThat(r.esValido()).isFalse();
        assertThat(r.faltante()).isCloseTo(37.5, within(0.001));
        assertThat(r.cambio()).isEqualTo(0);
        assertThat(r.error()).contains("Faltan $37.50");
    }

    @Test
    void pagoDivididoElCambioSaleDelEfectivo() {
        CalculoCobro.Resultado r = CalculoCobro.calcular(150, montos(FormaPago.EFECTIVO, 100, FormaPago.TARJETA, 80));
        assertThat(r.esValido()).isTrue();
        assertThat(r.pagado()).isEqualTo(180);
        assertThat(r.noEfectivo()).isEqualTo(80);
        assertThat(r.cambio()).isCloseTo(30, within(0.001));
    }

    @Test
    void loQueNoEsEfectivoNoPuedeExcederElTotal() {
        CalculoCobro.Resultado r = CalculoCobro.calcular(100, montos(FormaPago.TARJETA, 120));
        assertThat(r.esValido()).isFalse();
        assertThat(r.error()).contains("tarjeta").contains("$120.00").contains("$100.00");

        CalculoCobro.Resultado r2 = CalculoCobro.calcular(100, montos(FormaPago.TARJETA, 60, FormaPago.VALES, 50));
        assertThat(r2.esValido()).isFalse();

        // Exacto con tarjeta sí
        assertThat(CalculoCobro.calcular(99.99, montos(FormaPago.TARJETA, 99.99)).esValido()).isTrue();
    }

    @Test
    void sinPagoOTicketVacioNoEsValido() {
        CalculoCobro.Resultado vacio = CalculoCobro.calcular(50, montos());
        assertThat(vacio.esValido()).isFalse();
        assertThat(vacio.hayPago()).isFalse();
        assertThat(vacio.error()).contains("pagó");

        assertThat(CalculoCobro.calcular(0, montos(FormaPago.EFECTIVO, 50)).error()).contains("vacío");
    }

    @Test
    void toleraCentavosDePuntoFlotante() {
        // 0.1 + 0.2 ≠ 0.3 en binario: aun así el pago exacto es válido y el cambio es 0
        CalculoCobro.Resultado r = CalculoCobro.calcular(0.3, montos(FormaPago.EFECTIVO, 0.1, FormaPago.TARJETA, 0.2));
        assertThat(r.esValido()).isTrue();
        assertThat(r.cambio()).isEqualTo(0);
    }

    @Test
    void leeLosCamposDeTextoYRechazaBasura() {
        Map<FormaPago, String> textos = new LinkedHashMap<>();
        textos.put(FormaPago.EFECTIVO, " $1,500 ");
        textos.put(FormaPago.TARJETA, "");
        textos.put(FormaPago.VALES, "0");
        assertThat(CalculoCobro.leerMontos(textos)).containsExactly(Map.entry(FormaPago.EFECTIVO, 1500.0));

        textos.put(FormaPago.TARJETA, "abc");
        assertThat(CalculoCobro.leerMontos(textos)).isNull();
        textos.put(FormaPago.TARJETA, "-5");
        assertThat(CalculoCobro.leerMontos(textos)).isNull();
    }

    @Test
    void parsearMontoYFormatoDeCampo() {
        assertThat(CalculoCobro.parsearMonto(null, 7)).isEqualTo(7);
        assertThat(CalculoCobro.parsearMonto("  ", 7)).isEqualTo(7);
        assertThat(CalculoCobro.parsearMonto("12.5", 0)).isEqualTo(12.5);
        assertThat(CalculoCobro.parsearMonto("1e400", 0)).isNaN();
        assertThat(CalculoCobro.formatoCampo(137.5)).isEqualTo("137.50");
        assertThat(CalculoCobro.formatoCampo(1234.567)).isEqualTo("1234.57");
    }

    @Test
    void billetesRapidosQueSiguenAlTotal() {
        assertThat(CalculoCobro.sugerenciasDeEfectivo(137.5)).containsExactly(137.5, 140.0, 150.0, 200.0, 500.0);
        assertThat(CalculoCobro.sugerenciasDeEfectivo(20)).containsExactly(20.0, 50.0, 100.0, 200.0, 500.0);
        assertThat(CalculoCobro.sugerenciasDeEfectivo(1250)).containsExactly(1250.0);   // mayor al billete más grande
        assertThat(CalculoCobro.sugerenciasDeEfectivo(0)).isEmpty();
        assertThat(CalculoCobro.sugerenciasDeEfectivo(500)).containsExactly(500.0, 1000.0);
    }

    @Test
    void unCodigoEscaneadoEnElCampoDePagoNoSeCobra() {
        // El cursor estaba en "Efectivo" y el lector escribió un EAN-13 + Enter
        CalculoCobro.Resultado r = CalculoCobro.calcular(150, montos(FormaPago.EFECTIVO, 7501055300006.0));
        assertThat(r.esValido()).isFalse();
        assertThat(r.error()).contains("código");
        assertThat(r.cambio()).isZero();
        // "200" seguido del código escaneado (el texto seleccionado no se reemplazó)
        assertThat(CalculoCobro.calcular(150, montos(FormaPago.EFECTIVO, 2007501055300006.0)).esValido()).isFalse();
        // Un billete grande sigue siendo válido
        assertThat(CalculoCobro.calcular(150, montos(FormaPago.EFECTIVO, 1000)).cambio()).isEqualTo(850);
    }

    @Test
    void losBilletesRapidosVanDeMenorAMayor() {
        // Antes salía 2.30, 10, 50, 20, 100: F3 = $50 y F4 = $20 confundía al cajero
        assertThat(CalculoCobro.sugerenciasDeEfectivo(2.3)).containsExactly(2.3, 10.0, 20.0, 50.0, 100.0);
        assertThat(CalculoCobro.sugerenciasDeEfectivo(12)).isSorted();
    }
}
