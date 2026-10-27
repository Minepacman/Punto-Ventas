package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Formas de pago habilitadas: Efectivo y Tarjeta por defecto, Transferencia y Vales opcionales, Crédito nunca. */
class FormasPagoTest extends BaseServicioTest {

    @Autowired private ConfiguracionService configuracionService;

    @Test
    void pordefectoSoloEfectivoYTarjeta() {
        assertThat(configuracionService.formasPagoHabilitadas())
                .containsExactly(Venta.FormaPago.EFECTIVO, Venta.FormaPago.TARJETA);
    }

    @Test
    void deberQuedarAlMenosUnaYSoloElAdminLasCambia() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> configuracionService.guardarFormasPago(false, false, false, false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("al menos una");
        configuracionService.guardarFormasPago(true, false, true, true);
        assertThat(configuracionService.formasPagoHabilitadas())
                .containsExactly(Venta.FormaPago.EFECTIVO, Venta.FormaPago.TRANSFERENCIA, Venta.FormaPago.VALES);

        iniciarComoCajero("cajeroPago", null);
        assertThatThrownBy(() -> configuracionService.guardarFormasPago(true, true, true, true))
                .isInstanceOf(NegocioException.class);
        assertThat(configuracionService.formasPagoHabilitadas()).hasSize(3);   // sin cambios
    }

    @Test
    void ventaServiceRechazaLasFormasDeshabilitadas() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Para pagos", 100, 20);

        // Tarjeta deshabilitada
        configuracionService.guardarFormasPago(true, false, false, false);
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(new PagoVenta(Venta.FormaPago.TARJETA, 100)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no está habilitada");
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(new PagoVenta(Venta.FormaPago.TRANSFERENCIA, 100)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Transferencia");
        assertThat(stockActual(p)).isEqualTo(20);   // no se cobró nada

        // Efectivo sí
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);

        // Efectivo deshabilitado, solo tarjeta
        configuracionService.guardarFormasPago(false, true, false, false);
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Efectivo");
        // Crédito sigue sin existir aunque se intente
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.CREDITO, 100))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void transferenciaYValesSePuedenCombinarYSumanEnElCorte() throws Exception {
        iniciarComoAdmin();
        configuracionService.guardarFormasPago(true, true, true, true);
        Producto p = producto("Combinado", 100, 20);
        double transAntes = ventaService.resumenDelDia().transferencia;
        double valesAntes = ventaService.resumenDelDia().vales;

        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(
                new PagoVenta(Venta.FormaPago.TRANSFERENCIA, 60),
                new PagoVenta(Venta.FormaPago.VALES, 40)), null);

        assertThat(v.getTotal()).isEqualTo(100);
        assertThat(v.montoPagadoCon(Venta.FormaPago.TRANSFERENCIA)).isEqualTo(60);
        assertThat(v.montoPagadoCon(Venta.FormaPago.VALES)).isEqualTo(40);
        assertThat(v.getFormaPago()).isEqualTo(Venta.FormaPago.TRANSFERENCIA);   // la de mayor monto
        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        assertThat(r.transferencia - transAntes).isCloseTo(60, within(0.001));
        assertThat(r.vales - valesAntes).isCloseTo(40, within(0.001));

        // Como la tarjeta, no pueden exceder el total (no hay cambio en transferencias)
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(new PagoVenta(Venta.FormaPago.TRANSFERENCIA, 150)), null))
                .isInstanceOf(NegocioException.class);
    }
}
