package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * QA v1.0.0 — flujos de venta, ticket en espera y corte. Las pruebas de
 * defectos ya corregidos describen el defecto; las marcadas "DOCUMENTA" dejan
 * constancia de una decisión del dueño (el comportamiento actual se conserva).
 */
class QaVentasYCorteTest extends BaseServicioTest {

    @Autowired private VentaRepository         ventaRepo;
    @Autowired private CajaService             cajaService;
    @Autowired private TicketPendienteService  ticketPendienteService;
    @Autowired private ConfiguracionService    configuracionService;

    // ── M-1: descuento por importe + reducir cantidad ─────────────────────

    /**
     * M-1 (medio). El cajero daba $15 de descuento a 2 piezas de $10; al quitar
     * una pieza el descuento se recortaba a min(bruto, importe) = $10 y el
     * renglón quedaba en $0.00 cobrable. Ahora, si el descuento fijo ya no cabe
     * en el renglón, se quita (la pantalla avisa) y el renglón vuelve a su precio.
     */
    @Test
    void descuentoPorImporteNoDebeDejarElRenglonEnCeroAlReducirLaCantidad() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Galletas", 10, 20);
        DetalleVenta d = ventaService.crearDetalle(p, 2);
        ventaService.aplicarDescuentoManual(d, null, 15.0, null);
        assertThat(d.getImporte()).isCloseTo(5, within(0.001));

        // El cajero quita una pieza (botón "−" o Cantidad): la UI hace esto mismo
        d.setCantidad(1);
        ventaService.recalcularPrecio(d);

        assertThat(d.getImporte())
                .as("el renglón no debe quedar en $0 por un descuento de importe mayor al renglón")
                .isCloseTo(10, within(0.001));
        assertThat(d.tieneDescuentoManual()).as("el descuento que ya no cabe se quita").isFalse();
        assertThat(d.getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.NINGUNO);

        // Un descuento que SÍ cabe se conserva al cambiar la cantidad
        ventaService.aplicarDescuentoManual(d, null, 4.0, null);
        d.setCantidad(3);
        ventaService.recalcularPrecio(d);
        assertThat(d.getImporte()).isCloseTo(26, within(0.001));
        assertThat(d.tieneDescuentoManual()).isTrue();
    }

    // ── M-2: cancelar una venta de ayer (DOCUMENTA) ───────────────────────

    /**
     * DOCUMENTA (decisión del dueño: se conserva). Una venta de AYER cancelada
     * HOY cuenta como cancelada en el corte de AYER (el día de la venta), no en
     * el de hoy: el resumen de hoy no cambia ni en canceladas ni en efectivo.
     * Quien devuelva efectivo del cajón de hoy debe anotarlo en las
     * observaciones del corte.
     */
    @Test
    void cancelarUnaVentaDeAyerCuentaEnElCorteDeAyerNoEnElDeHoy() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Aceite", 40, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 40);
        v.setFecha(LocalDateTime.now().minusDays(1).withHour(12));
        ventaRepo.save(v);

        LocalDate ayer = LocalDate.now().minusDays(1);
        VentaService.ResumenCorte hoyAntes = ventaService.resumenCompletoDe(LocalDate.now());
        VentaService.ResumenCorte ayerAntes = ventaService.resumenCompletoDe(ayer);
        ventaService.cancelar(v.getId(), "Cliente devolvió el producto", null);
        VentaService.ResumenCorte hoy = ventaService.resumenCompletoDe(LocalDate.now());
        VentaService.ResumenCorte ayerDespues = ventaService.resumenCompletoDe(ayer);

        assertThat(hoy.numCanceladas).isEqualTo(hoyAntes.numCanceladas);
        assertThat(hoy.efectivo).isCloseTo(hoyAntes.efectivo, within(0.001));
        assertThat(ayerDespues.numCanceladas).isEqualTo(ayerAntes.numCanceladas + 1);
        assertThat(ayerDespues.efectivo).isCloseTo(ayerAntes.efectivo - 40, within(0.001));
    }

    // ── B-1: ticket en espera con producto desactivado ────────────────────

    /**
     * B-1 (bajo). Un ticket en espera con dos productos; mientras tanto se
     * desactiva uno. El renglón desaparecía sin aviso. Ahora el resumen sigue
     * contando los renglones que dejó el cliente y dice cuántos se omitieron;
     * recuperar devuelve solo los vendibles.
     */
    @Test
    void recuperarTicketEnEsperaDebeAvisarDeRenglonesOmitidos() throws Exception {
        iniciarComoAdmin();
        Producto a = producto("Leche", 25, 10);
        Producto b = producto("Pan", 30, 10);
        TicketPendienteService.EnEspera guardado = ticketPendienteService.ponerEnEspera(
                List.of(ventaService.crearDetalle(a, 1), ventaService.crearDetalle(b, 2)), "Sra. Lupita");
        assertThat(guardado.renglones()).isEqualTo(2);
        assertThat(guardado.omitidos()).isZero();

        productoService.cambiarActivo(b.getId(), false);

        TicketPendienteService.EnEspera listado = ticketPendienteService.listarEnEspera().stream()
                .filter(t -> t.id().equals(guardado.id())).findFirst().orElseThrow();
        assertThat(listado.renglones())
                .as("el resumen del ticket en espera no debe perder renglones en silencio")
                .isEqualTo(2);
        assertThat(listado.omitidos()).isEqualTo(1);
        assertThat(listado.detalles()).hasSize(1);
        assertThat(ticketPendienteService.recuperarEnEspera(guardado.id()))
                .extracting(d -> d.getProducto().getId()).containsExactly(a.getId());
    }

    // ── M-24: corte acumulado (DOCUMENTA) ─────────────────────────────────

    /**
     * DOCUMENTA (decisión a confirmar). El corte siempre suma desde las 00:00:
     * si la cajera del turno 1 hizo su corte y se llevó su efectivo, el corte
     * del turno 2 "espera" también el efectivo del turno 1 y marca FALTANTE.
     * No existe un corte "desde el último corte" (por turno).
     */
    @Test
    void elCorteSiempreAcumulaTodoElDiaNoDesdeElUltimoCorte() throws Exception {
        Cajero admin = iniciarComoAdmin();
        if (cajaService.requiereFondo(admin)) cajaService.registrarFondo(admin, 100);
        double fondo = cajaService.fondoVigenteDelDia(LocalDate.now());
        VentaService.ResumenCorte base = ventaService.resumenCompletoDe(LocalDate.now());
        Producto p = producto("Refresco", 50, 100);

        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 50);
        Corte turno1 = cajaService.hacerCorte(fondo + base.efectivo + 50, null);
        assertThat(turno1.getDiferencia()).isCloseTo(0, within(0.001));

        // Turno 2: la cajera nueva solo tiene en el cajón el fondo y SU venta
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 50);
        Corte turno2 = cajaService.hacerCorte(fondo + 50, null);

        assertThat(turno2.getEfectivoEsperado()).isCloseTo(fondo + base.efectivo + 100, within(0.001));
        assertThat(turno2.hayFaltante()).isTrue();   // "faltante" de 50 que en realidad se llevó el turno 1
    }

    // ── B-2: el corte guardado conserva transferencia y vales ─────────────

    /**
     * B-2 (bajo). El registro Corte solo tenía ventasEfectivo y ventasTarjeta:
     * en cortes viejos efectivo + tarjeta no cuadraba con el total. Ahora guarda
     * también transferencia y vales.
     */
    @Test
    void elCorteGuardadoDebeConservarTransferenciasYVales() throws Exception {
        Cajero admin = iniciarComoAdmin();
        configuracionService.guardarFormasPago(true, true, true, true);
        if (cajaService.requiereFondo(admin)) cajaService.registrarFondo(admin, 0);
        Producto p = producto("Detergente", 80, 10);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(new PagoVenta(Venta.FormaPago.TRANSFERENCIA, 80)), null);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(new PagoVenta(Venta.FormaPago.VALES, 80)), null);

        Corte c = cajaService.hacerCorte(cajaService.fondoVigenteDelDia(LocalDate.now())
                + ventaService.resumenCompletoDe(LocalDate.now()).efectivo, null);
        VentaService.ResumenCorte r = ventaService.resumenCompletoDe(LocalDate.now());
        assertThat(r.transferencia).isGreaterThanOrEqualTo(80);
        assertThat(c.getVentasTransferenciaOCero()).isCloseTo(r.transferencia, within(0.01));
        assertThat(c.getVentasValesOCero()).isCloseTo(r.vales, within(0.01));

        double sumaGuardada = c.getVentasEfectivo() + c.getVentasTarjeta() + c.getVentasTransferenciaOCero() + c.getVentasValesOCero();
        assertThat(sumaGuardada)
                .as("el registro del corte debe conservar todas las formas de pago")
                .isCloseTo(c.getVentasTotales(), within(0.01));
    }

    // ── B-3: la autorización caduca a los 5 min (DOCUMENTA) ───────────────

    /**
     * DOCUMENTA (UX). La autorización de un administrador para mayoreo vale 5
     * minutos. Si el cliente tarda más en terminar su compra, al cobrar el
     * servicio vuelve a exigir la autorización (la pantalla la vuelve a pedir).
     */
    @Test
    void laAutorizacionDeUnTicketCaducaALosCincoMinutosYSeVuelveAPedirAlCobrar() throws Exception {
        iniciarComoAdmin();
        Cajero admin = sesionService.getCajeroActual();
        crearUsuario("cajeroQA", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        Producto p = producto("Azúcar", 30, 10);
        p.setPrecioMayoreo(25);
        productoService.guardar(p);
        iniciarComo("cajeroQA", CLAVE_PRUEBA);

        Autorizacion vieja = new Autorizacion(admin, Permiso.APLICAR_MAYOREO, LocalDateTime.now().minusMinutes(6));
        List<Autorizacion> auts = new ArrayList<>(List.of(vieja));
        DetalleVenta d = ventaService.crearDetalle(p, 1);
        d.setMayoreo(true);
        ventaService.recalcularPrecio(d);

        assertThatThrownBy(() -> ventaService.cobrar(List.of(d), List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, 25)), auts))
                .isInstanceOf(PermisoRequeridoException.class);
    }
}
