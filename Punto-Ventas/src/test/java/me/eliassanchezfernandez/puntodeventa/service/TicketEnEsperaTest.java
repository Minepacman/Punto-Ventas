package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tickets en espera (F11 / Shift+F11): se apartan varios por caja, se recuperan
 * una sola vez, no tocan el inventario y no se confunden con el ticket
 * autoguardado al cerrar sesión (USR-15).
 */
class TicketEnEsperaTest extends BaseServicioTest {

    @Autowired private TicketPendienteService ticketPendienteService;
    @Autowired private IdentidadCaja identidadCaja;

    @org.junit.jupiter.api.AfterEach
    void volverASerLaCajaUno() {
        identidadCaja.fijarParaPruebas(1, "", false);
    }

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    @Test
    void sePuedenApartarVariosYRecuperarCadaUnoUnaSolaVez() {
        Producto refresco = producto("Refresco", 20, 10);
        Producto pan = producto("Pan", 35, 5);
        int antes = ticketPendienteService.contarEnEspera();

        var a = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(refresco, 2)), "Sra. Lupita");
        var b = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(pan, 1), ventaService.crearDetalle(refresco, 1)), null);

        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(antes + 2);
        List<TicketPendienteService.EnEspera> lista = ticketPendienteService.listarEnEspera();
        assertThat(lista).extracting(TicketPendienteService.EnEspera::id).contains(a.id(), b.id());
        TicketPendienteService.EnEspera resumenA = lista.stream().filter(t -> t.id().equals(a.id())).findFirst().orElseThrow();
        assertThat(resumenA.nota()).isEqualTo("Sra. Lupita");
        assertThat(resumenA.renglones()).isEqualTo(1);
        assertThat(resumenA.piezas()).isEqualTo(2);
        assertThat(resumenA.cajero()).isNotBlank();
        assertThat(stockActual(refresco)).isEqualTo(10);   // apartar no vende

        List<DetalleVenta> recuperado = ticketPendienteService.recuperarEnEspera(b.id());
        assertThat(recuperado).hasSize(2);
        assertThat(recuperado.get(0).getProducto().getId()).isEqualTo(pan.getId());
        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(antes + 1);

        // Ya no está: recuperarlo otra vez avisa (otro cajero pudo haberlo tomado)
        assertThatThrownBy(() -> ticketPendienteService.recuperarEnEspera(b.id()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya no existe");

        ticketPendienteService.descartarEnEspera(a.id());
        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(antes);
    }

    @Test
    void conservaMayoreoYDescuentoManualDelRenglon() {
        Producto p = producto("Caja de refresco", 200, 10);
        p.setPrecioMayoreo(180);
        p = productoService.guardar(p);
        DetalleVenta d = ventaService.crearDetalle(p, 3);
        ventaService.aplicarMayoreo(d, true, null);
        DetalleVenta d2 = ventaService.crearDetalle(producto("Pan", 35, 5), 1);
        ventaService.aplicarDescuentoManual(d2, 10.0, null, null);

        var t = ticketPendienteService.ponerEnEspera(List.of(d, d2), "mayoreo");
        List<DetalleVenta> rec = ticketPendienteService.recuperarEnEspera(t.id());
        assertThat(rec.get(0).isMayoreo()).isTrue();
        assertThat(rec.get(0).getCantidad()).isEqualTo(3);
        assertThat(rec.get(1).getDescuentoManualPct()).isEqualTo(10.0);
        ventaService.recalcularPrecio(rec.get(0));
        assertThat(rec.get(0).getPrecioVenta()).isEqualTo(180);
    }

    @Test
    void elTicketEnEsperaNoSeConfundeConElAutoguardadoNiSeBorraAlCobrar() {
        Producto p = producto("Leche", 28, 10);
        var enEspera = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 1)), "espera");

        // El autoguardado (cerrar sesión) es otro ticket y recuperar() solo devuelve ese
        ticketPendienteService.guardar(List.of(ventaService.crearDetalle(p, 4)));
        List<DetalleVenta> autoguardado = ticketPendienteService.recuperar();
        assertThat(autoguardado).hasSize(1);
        assertThat(autoguardado.get(0).getCantidad()).isEqualTo(4);
        assertThat(ticketPendienteService.hayPendiente()).isTrue();

        // Cobrar otra venta borra el autoguardado, pero el ticket en espera sigue ahí
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, 100)), null);
        assertThat(ticketPendienteService.hayPendiente()).isFalse();
        assertThat(ticketPendienteService.listarEnEspera()).extracting(TicketPendienteService.EnEspera::id).contains(enEspera.id());

        // Descartar el autoguardado del cajero tampoco toca el que está en espera
        ticketPendienteService.descartarDelCajeroActual();
        assertThat(ticketPendienteService.listarEnEspera()).extracting(TicketPendienteService.EnEspera::id).contains(enEspera.id());
    }

    @Test
    void otroCajeroDeLaMismaCajaPuedeRecuperarlo() throws Exception {
        Producto p = producto("Jabón", 15, 10);
        var t = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 2)), "turno");

        crearUsuario("cajero_espera", Cajero.Rol.CAJERO, Set.<Permiso>of());
        iniciarComo("cajero_espera", CLAVE_PRUEBA);

        assertThat(ticketPendienteService.listarEnEspera()).extracting(TicketPendienteService.EnEspera::id).contains(t.id());
        assertThat(ticketPendienteService.recuperarEnEspera(t.id())).hasSize(1);
    }

    @Test
    void validaVacioNotaLargaYTope() {
        Producto p = producto("Chicle", 2, 100);
        assertThatThrownBy(() -> ticketPendienteService.ponerEnEspera(List.of(), "x"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("vacío");
        assertThatThrownBy(() -> ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 1)), "n".repeat(61)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("60");

        List<Long> ids = new ArrayList<>();
        while (ticketPendienteService.contarEnEspera() < TicketPendienteService.MAX_EN_ESPERA) {
            ids.add(ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 1)), "  ").id());
        }
        assertThatThrownBy(() -> ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 1)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("en espera");
        // Una nota en blanco se guarda como "sin nota"
        assertThat(ticketPendienteService.listarEnEspera()).filteredOn(t -> ids.contains(t.id())).allMatch(t -> t.nota() == null);
        ids.forEach(ticketPendienteService::descartarEnEspera);
    }

    @Test
    void sinSesionNoSePuedeApartarNiRecuperar() {
        Producto p = producto("Sal", 12, 10);
        var t = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 1)), null);
        sesionService.cerrarSesion();
        assertThatThrownBy(() -> ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 1)), null))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> ticketPendienteService.recuperarEnEspera(t.id()))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void cadaCajaSoloVeRecuperaYDescartaSusTicketsEnEspera() {
        Producto p = producto("Galletas", 18, 10);
        identidadCaja.fijarParaPruebas(1, "Caja 1", true);
        var deLaUno = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 1)), "caja uno");
        int enLaUno = ticketPendienteService.contarEnEspera();

        identidadCaja.fijarParaPruebas(2, "Caja 2", true);
        int enLaDosAntes = ticketPendienteService.contarEnEspera();
        assertThat(ticketPendienteService.listarEnEspera()).extracting(TicketPendienteService.EnEspera::id).doesNotContain(deLaUno.id());
        // Aunque se conozca el id, otra caja no puede recuperarlo ni descartarlo (la mercancía está en la caja 1)
        assertThatThrownBy(() -> ticketPendienteService.recuperarEnEspera(deLaUno.id()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya no existe");
        ticketPendienteService.descartarEnEspera(deLaUno.id());
        var deLaDos = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 2)), "caja dos");
        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(enLaDosAntes + 1);

        identidadCaja.fijarParaPruebas(1, "Caja 1", true);
        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(enLaUno);
        assertThat(ticketPendienteService.recuperarEnEspera(deLaUno.id())).hasSize(1);
        identidadCaja.fijarParaPruebas(2, "Caja 2", true);
        ticketPendienteService.descartarEnEspera(deLaDos.id());
    }
}
