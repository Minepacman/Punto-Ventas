package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Ticket sin cobrar conservado por cajero al cerrar sesión (USR-15). */
class TicketPendienteTest extends BaseServicioTest {

    @Autowired private TicketPendienteService ticketPendienteService;

    @Test
    void elTicketSeGuardaPorCajeroYSeRestauraSoloParaEl() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Pendiente", 50, 10);
        p.setPrecioMayoreo(40);
        p = productoService.guardar(p);
        DetalleVenta d = ventaService.crearDetalle(p, 2);
        ventaService.aplicarMayoreo(d, true, null);

        ticketPendienteService.guardar(List.of(d));
        assertThat(ticketPendienteService.hayPendiente()).isTrue();

        // Otro cajero no lo ve
        iniciarComoCajero("otroCajero", null);
        assertThat(ticketPendienteService.recuperar()).isEmpty();

        // El mismo cajero lo recupera con sus marcas y precios recalculados
        iniciarComoAdmin();
        List<DetalleVenta> restaurado = ticketPendienteService.recuperar();
        assertThat(restaurado).hasSize(1);
        DetalleVenta r = restaurado.get(0);
        assertThat(r.getProducto().getId()).isEqualTo(p.getId());
        assertThat(r.getCantidad()).isEqualTo(2);
        assertThat(r.isMayoreo()).isTrue();
        ventaService.recalcularPrecio(r);
        assertThat(r.getImporte()).isCloseTo(80, within(0.001));

        // Al cobrar, el pendiente desaparece
        ventaService.cobrar(List.of(r), Venta.FormaPago.EFECTIVO, 100);
        assertThat(ticketPendienteService.hayPendiente()).isFalse();
    }

    @Test
    void cancelarElTicketLoDescarta() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Descartable", 10, 10);
        ticketPendienteService.guardar(List.of(ventaService.crearDetalle(p, 1)));
        ticketPendienteService.descartarDelCajeroActual();
        assertThat(ticketPendienteService.hayPendiente()).isFalse();
    }
}
