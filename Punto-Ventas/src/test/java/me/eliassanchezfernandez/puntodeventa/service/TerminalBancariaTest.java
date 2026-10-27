package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.terminal.DatosTarjeta;
import me.eliassanchezfernandez.puntodeventa.terminal.ManualTerminal;
import me.eliassanchezfernandez.puntodeventa.terminal.TerminalBancaria;
import me.eliassanchezfernandez.puntodeventa.terminal.TerminalesBancarias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Terminal bancaria con captura manual: validación, guardado en el pago, ticket, historial y corte. */
class TerminalBancariaTest extends BaseServicioTest {

    @Autowired private DispositivosService dispositivosService;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private TicketService ticketService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ManualTerminal manualTerminal;
    @Autowired private TerminalesBancarias terminales;

    @BeforeEach
    void iniciar() throws Exception {
        iniciarComoAdmin();
        dispositivosService.guardarTerminales("Terminal BBVA\nTerminal Clip", false);
    }

    private Venta cobrarConTarjeta(Producto p, PagoVenta tarjeta) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(tarjeta), null);
    }

    private PagoVenta tarjeta(double monto, String terminal, String ultimos4, String autorizacion) {
        return new PagoVenta(Venta.FormaPago.TARJETA, monto, terminal, ultimos4, autorizacion);
    }

    private PagoVenta recargarPagoTarjeta(Venta v) {
        Venta recargada = ventaRepo.findConDetallesById(v.getId()).orElseThrow();
        ventaRepo.cargarPagos(List.of(recargada));
        return recargada.getPagos().stream().filter(x -> x.getFormaPago() == Venta.FormaPago.TARJETA).findFirst().orElseThrow();
    }

    // ── Validación ────────────────────────────────────────────────────────

    @Test
    void validaYNormalizaLosTresDatos() {
        DatosTarjeta d = DatosTarjeta.validar(" terminal bbva ", " 12 34 ", " ab12cd ", List.of("Terminal BBVA"));
        assertThat(d.terminal()).isEqualTo("Terminal BBVA");     // con el nombre exacto de la lista
        assertThat(d.ultimos4()).isEqualTo("1234");
        assertThat(d.autorizacion()).isEqualTo("AB12CD");
    }

    @Test
    void rechazaDatosMalFormados() {
        List<String> lista = List.of("T1");
        assertThatThrownBy(() -> DatosTarjeta.validar(null, "1234", "ABC123", lista)).hasMessageContaining("terminal");
        assertThatThrownBy(() -> DatosTarjeta.validar("Otra", "1234", "ABC123", lista)).hasMessageContaining("no está en la lista");
        assertThatThrownBy(() -> DatosTarjeta.validar("T1", "123", "ABC123", lista)).hasMessageContaining("4 dígitos");
        assertThatThrownBy(() -> DatosTarjeta.validar("T1", "12a4", "ABC123", lista)).hasMessageContaining("solo números");
        assertThatThrownBy(() -> DatosTarjeta.validar("T1", "4111111111111111", "ABC123", lista)).hasMessageContaining("4 dígitos");
        assertThatThrownBy(() -> DatosTarjeta.validar("T1", "1234", "", lista)).hasMessageContaining("autorización");
        assertThatThrownBy(() -> DatosTarjeta.validar("T1", "1234", "ab", lista)).hasMessageContaining("autorización");
        assertThatThrownBy(() -> DatosTarjeta.validar("T1", "1234", "AB-1234!", lista)).hasMessageContaining("autorización");
    }

    // ── Guardado en el pago ───────────────────────────────────────────────

    @Test
    void cobrarGuardaTerminalUltimos4YAutorizacionEnElPago() {
        Producto p = producto("Refresco", 30, 10);
        Venta v = cobrarConTarjeta(p, tarjeta(30, "terminal clip", "4242", "a1b2c3"));

        PagoVenta pago = recargarPagoTarjeta(v);
        assertThat(pago.getTerminalNombre()).isEqualTo("Terminal Clip");
        assertThat(pago.getTarjetaUltimos4()).isEqualTo("4242");
        assertThat(pago.getAutorizacion()).isEqualTo("A1B2C3");
        assertThat(pago.resumenTarjeta()).isEqualTo("Terminal Clip ****4242 aut. A1B2C3");
    }

    @Test
    void datosInvalidosCancelanElCobroCompleto() {
        Producto p = producto("Agua", 10, 10);
        assertThatThrownBy(() -> cobrarConTarjeta(p, tarjeta(10, "Terminal BBVA", "12", "ABC123")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("4 dígitos");
        assertThatThrownBy(() -> cobrarConTarjeta(p, tarjeta(10, "Terminal inexistente", "1234", "ABC123")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no está en la lista");
        assertThat(stockActual(p)).isEqualTo(10);   // nada se vendió
    }

    @Test
    void sinDatosSePermiteSalvoQueConfiguracionLosExija() {
        Producto p = producto("Pan", 12, 10);
        Venta v = cobrarConTarjeta(p, new PagoVenta(Venta.FormaPago.TARJETA, 12));   // compatibilidad
        assertThat(recargarPagoTarjeta(v).tieneDatosTarjeta()).isFalse();

        dispositivosService.guardarTerminales("Terminal BBVA", true);
        assertThatThrownBy(() -> cobrarConTarjeta(p, new PagoVenta(Venta.FormaPago.TARJETA, 12)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("terminal");
        cobrarConTarjeta(p, tarjeta(12, "Terminal BBVA", "0001", "XYZ789"));        // con datos, sí
        assertThat(stockActual(p)).isEqualTo(8);
    }

    @Test
    void losDatosDeTarjetaSeDescartanEnOtrasFormasDePago() {
        Producto p = producto("Dulce", 10, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(
                new PagoVenta(Venta.FormaPago.EFECTIVO, 10, "Terminal BBVA", "1234", "ABC123")), null);
        Venta recargada = ventaRepo.findConDetallesById(v.getId()).orElseThrow();
        ventaRepo.cargarPagos(List.of(recargada));
        assertThat(recargada.getPagos().get(0).tieneDatosTarjeta()).isFalse();
    }

    @Test
    void pagoDivididoEfectivoMasTarjetaGuardaLosDatosSoloEnLaTarjeta() {
        Producto p = producto("Combo", 100, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(
                new PagoVenta(Venta.FormaPago.EFECTIVO, 40),
                tarjeta(60, "Terminal BBVA", "9876", "AUT555")), null);
        PagoVenta pago = recargarPagoTarjeta(v);
        assertThat(pago.getMonto()).isEqualTo(60);
        assertThat(pago.getTarjetaUltimos4()).isEqualTo("9876");
    }

    // ── Ticket, historial y corte ─────────────────────────────────────────

    @Test
    void elTicketImpresoMuestraLosDatosDelVoucher() {
        Producto p = producto("Leche", 25, 10);
        Venta v = cobrarConTarjeta(p, tarjeta(25, "Terminal BBVA", "4242", "AUT123"));
        Venta recargada = ventaRepo.findConDetallesById(v.getId()).orElseThrow();
        ventaRepo.cargarPagos(List.of(recargada));

        String ticket = ticketService.documentoVenta(recargada, configuracionService.obtener(), false).aTexto(48);

        assertThat(ticket).contains("Tarjeta").contains("Terminal BBVA ****4242 aut. AUT123");
    }

    @Test
    void elCorteDesglosaLaTarjetaPorTerminalYListaLosVouchers() {
        var antes = ventaService.resumenDelDia();
        Producto p = producto("Café", 50, 20);
        cobrarConTarjeta(p, tarjeta(50, "Terminal BBVA", "1111", "AAA111"));
        cobrarConTarjeta(p, tarjeta(50, "Terminal BBVA", "2222", "BBB222"));
        cobrarConTarjeta(p, tarjeta(50, "Terminal Clip", "3333", "CCC333"));

        var r = ventaService.resumenDelDia();

        assertThat(r.tarjeta - antes.tarjeta).isCloseTo(150, within(0.001));
        var bbva = r.tarjetaPorTerminal.stream().filter(t -> t.terminal().equals("Terminal BBVA")).findFirst().orElseThrow();
        assertThat(bbva.pagos() - contar(antes, "Terminal BBVA")).isEqualTo(2);
        assertThat(r.vouchers).extracting(v -> v.autorizacion()).contains("AAA111", "BBB222", "CCC333");
        // La suma por terminal coincide con el total de tarjeta del día
        assertThat(r.tarjetaPorTerminal.stream().mapToDouble(t -> t.monto()).sum()).isCloseTo(r.tarjeta, within(0.001));
    }

    private static int contar(VentaService.ResumenCorte r, String terminal) {
        return r.tarjetaPorTerminal.stream().filter(t -> t.terminal().equals(terminal)).mapToInt(t -> t.pagos()).sum();
    }

    @Test
    void unaVentaCanceladaYaNoApareceEnElCorteDeTarjeta() {
        Producto p = producto("Té", 40, 10);
        Venta v = cobrarConTarjeta(p, tarjeta(40, "Terminal Clip", "7777", "ZZZ999"));
        assertThat(ventaService.resumenDelDia().vouchers).extracting(x -> x.autorizacion()).contains("ZZZ999");

        ventaService.cancelar(v.getId(), "error de captura", List.of());

        assertThat(ventaService.resumenDelDia().vouchers).extracting(x -> x.autorizacion()).doesNotContain("ZZZ999");
    }

    // ── Terminal enchufable y configuración ───────────────────────────────

    @Test
    void laTerminalManualValidaYDevuelveLosDatos() throws Exception {
        assertThat(terminales.activa()).isSameAs(manualTerminal);
        assertThat(manualTerminal.capturaManual()).isTrue();

        TerminalBancaria.ResultadoTerminal r = manualTerminal.cobrar(
                new TerminalBancaria.SolicitudTerminal(50, "terminal bbva", "1234", "abc123"));
        assertThat(r.aprobado()).isTrue();
        assertThat(r.datos()).isEqualTo(new DatosTarjeta("Terminal BBVA", "1234", "ABC123"));

        assertThatThrownBy(() -> manualTerminal.cobrar(new TerminalBancaria.SolicitudTerminal(50, "Terminal BBVA", "12", "ABC123")))
                .isInstanceOf(TerminalBancaria.TerminalException.class);
    }

    @Test
    void laListaDeTerminalesSeValidaYSoloLaCambiaElAdmin() throws Exception {
        assertThatThrownBy(() -> dispositivosService.guardarTerminales("  \n ", false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("al menos una");
        assertThatThrownBy(() -> dispositivosService.guardarTerminales("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11", false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Máximo");
        assertThatThrownBy(() -> dispositivosService.guardarTerminales("x".repeat(41), false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("40");

        dispositivosService.guardarTerminales("A\nb\nB\n  C  ", false);
        assertThat(dispositivosService.obtener().getListaTerminales()).containsExactly("A", "b", "C");   // sin repetidos

        iniciarComoCajero("intruso2", EnumSet.allOf(Permiso.class));
        assertThatThrownBy(() -> dispositivosService.guardarTerminales("Z", false)).isInstanceOf(NegocioException.class);
    }
}
