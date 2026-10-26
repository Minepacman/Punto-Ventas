package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Pago dividido entre efectivo y tarjeta (VEN-07, VEN-08) y "Crédito" oculto (VEN-10). */
class PagosDivididosTest extends BaseServicioTest {

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    @Test
    void efectivoMasTarjetaCubrenElTotalYElCambioSaleDelEfectivo() {
        Producto p = producto("Combo", 150, 10);
        double efectivoAntes = ventaService.resumenDelDia().efectivo;
        double tarjetaAntes  = ventaService.resumenDelDia().tarjeta;

        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(
                new PagoVenta(Venta.FormaPago.EFECTIVO, 100),
                new PagoVenta(Venta.FormaPago.TARJETA, 80)), null);

        assertThat(v.getTotal()).isCloseTo(150, within(0.001));
        assertThat(v.getPagoCon()).isCloseTo(180, within(0.001));
        assertThat(v.getCambio()).isCloseTo(30, within(0.001));
        assertThat(v.getPagos()).hasSize(2);
        assertThat(v.montoPagadoCon(Venta.FormaPago.EFECTIVO)).isEqualTo(100);
        assertThat(v.montoPagadoCon(Venta.FormaPago.TARJETA)).isEqualTo(80);
        assertThat(v.getFormaPago()).isEqualTo(Venta.FormaPago.EFECTIVO);   // la de mayor monto

        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        assertThat(r.efectivo - efectivoAntes).isCloseTo(70, within(0.001));   // 100 − 30 de cambio
        assertThat(r.tarjeta - tarjetaAntes).isCloseTo(80, within(0.001));
    }

    @Test
    void laTarjetaNoPuedeExcederElTotalNiLaSumaSerMenor() {
        Producto p = producto("Combo2", 100, 10);

        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(
                new PagoVenta(Venta.FormaPago.EFECTIVO, 50),
                new PagoVenta(Venta.FormaPago.TARJETA, 120)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("tarjeta");

        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(
                new PagoVenta(Venta.FormaPago.EFECTIVO, 30),
                new PagoVenta(Venta.FormaPago.TARJETA, 30)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("menor");

        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(), null))
                .isInstanceOf(NegocioException.class);

        assertThat(stockActual(p)).isEqualTo(10);
    }

    @Test
    void elCreditoNoEstaDisponibleHastaTenerClientes() {
        Producto p = producto("Combo3", 100, 10);
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                Venta.FormaPago.CREDITO, 100))
                .isInstanceOf(NegocioException.class).hasMessageContaining("crédito");
    }

    @Test
    void pagoSoloConTarjetaExacto() {
        Producto p = producto("Combo4", 99.99, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(
                new PagoVenta(Venta.FormaPago.TARJETA, 99.99)), null);
        assertThat(v.getCambio()).isCloseTo(0, within(0.001));
        assertThat(v.getFormaPago()).isEqualTo(Venta.FormaPago.TARJETA);
    }
}
