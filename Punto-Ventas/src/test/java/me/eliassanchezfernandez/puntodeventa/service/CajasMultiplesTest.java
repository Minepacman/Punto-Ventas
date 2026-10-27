package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CorteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.FondoCajaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Varias cajas: cada venta, fondo, corte, ticket pendiente y movimiento lleva
 * su caja; el fondo y el corte son por caja y el corte general suma todas.
 * Se simula "ser otra caja" cambiando la identidad del proceso.
 */
class CajasMultiplesTest extends BaseServicioTest {

    @Autowired private CajaService cajaService;
    @Autowired private IdentidadCaja identidadCaja;
    @Autowired private TicketPendienteService ticketPendienteService;
    @Autowired private EstadisticasService estadisticasService;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private CorteRepository corteRepo;
    @Autowired private FondoCajaRepository fondoRepo;

    @AfterEach
    void volverASerLaCajaUno() {
        identidadCaja.fijarParaPruebas(1, "", false);
    }

    private void serCaja(int n) {
        identidadCaja.fijarParaPruebas(n, "Caja " + n, true);
    }

    private Venta vender(Producto p, double cantidad, Venta.FormaPago forma, double pago) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, cantidad)), forma, pago);
    }

    @Test
    void laIdentidadPorDefectoEsLaCajaUno() {
        assertThat(identidadCaja.numero()).isEqualTo(1);
        assertThat(identidadCaja.nombre()).isEqualTo("Caja 1");
        assertThat(identidadCaja.esMultiCaja()).isFalse();
        assertThat(IdentidadCaja.etiqueta(null)).isEqualTo("Caja 1");
        assertThat(IdentidadCaja.numeroONumeroPorDefecto(3)).isEqualTo(3);
    }

    @Test
    void cadaVentaYMovimientoLlevanSuCaja() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Agua", 10, 50);
        Venta v1 = vender(p, 1, Venta.FormaPago.EFECTIVO, 10);
        serCaja(2);
        Venta v2 = vender(p, 2, Venta.FormaPago.EFECTIVO, 20);

        assertThat(v1.getCaja()).isEqualTo(1);
        assertThat(v2.getCaja()).isEqualTo(2);
        assertThat(ventaRepo.findById(v2.getId()).orElseThrow().getCajaONumero()).isEqualTo(2);
        List<MovimientoInventario> movs = productoService.movimientosDe(p.getId());
        assertThat(movs).extracting(MovimientoInventario::getCajaONumero).contains(1, 2);
        // Filas anteriores a "varias cajas" (sin caja) cuentan como caja 1
        Venta vieja = new Venta();
        assertThat(vieja.getCajaONumero()).isEqualTo(1);
    }

    @Test
    void elFondoEsPorCajaYPorDia() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 500);
        assertThat(cajaService.requiereFondo(admin)).isFalse();

        serCaja(2);
        assertThat(cajaService.requiereFondo(admin)).isTrue();          // la caja 2 todavía no tiene fondo
        assertThat(cajaService.fondoVigenteDelDia(LocalDate.now())).isEqualTo(0);
        cajaService.registrarFondo(admin, 300);
        assertThatThrownBy(() -> cajaService.registrarFondo(admin, 1)).hasMessageContaining("Caja 2");
        assertThat(cajaService.fondoVigenteDelDia(LocalDate.now())).isEqualTo(300);
        assertThat(cajaService.fondoVigenteDelDia(LocalDate.now(), 1)).isEqualTo(500);
        cajaService.corregirFondo(350, "Se contó mal en la 2");
        assertThat(cajaService.fondoVigenteDelDia(LocalDate.now(), 2)).isEqualTo(350);
        assertThat(cajaService.fondoVigenteDelDia(LocalDate.now(), 1)).isEqualTo(500);   // la 1 no cambia
        assertThat(cajaService.fondosPorCaja(LocalDate.now())).containsEntry(1, 500.0).containsEntry(2, 350.0);
        assertThat(cajaService.fondoTotalDelDia(LocalDate.now())).isEqualTo(850);
        assertThat(fondoRepo.findDeCaja(LocalDate.now(), 2)).hasSize(2);
    }

    @Test
    void elCorteEsPorCajaYElGeneralSumaTodas() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Producto p = producto("Pan", 100, 50);
        cajaService.registrarFondo(admin, 200);
        vender(p, 1, Venta.FormaPago.EFECTIVO, 100);           // caja 1: 100 efectivo
        serCaja(2);
        cajaService.registrarFondo(admin, 50);
        vender(p, 2, Venta.FormaPago.EFECTIVO, 200);           // caja 2: 200 efectivo
        vender(p, 1, Venta.FormaPago.TARJETA, 100);            // caja 2: 100 tarjeta

        VentaService.ResumenCorte r2 = ventaService.resumenDelDia();
        assertThat(r2.caja).isEqualTo(2);
        assertThat(r2.numVentas).isEqualTo(2);
        assertThat(r2.efectivo).isCloseTo(200, within(0.001));
        assertThat(r2.tarjeta).isCloseTo(100, within(0.001));
        assertThat(ventaService.totalVentasHoy()).isCloseTo(300, within(0.001));

        Corte c2 = cajaService.hacerCorte(250, null);
        assertThat(c2.getCajaONumero()).isEqualTo(2);
        assertThat(c2.isGeneral()).isFalse();
        assertThat(c2.getFondoInicial()).isEqualTo(50);
        assertThat(c2.getEfectivoEsperado()).isCloseTo(250, within(0.001));
        assertThat(c2.getDiferencia()).isCloseTo(0, within(0.001));
        assertThat(c2.getEtiquetaCaja()).isEqualTo("Caja 2");
        assertThat(cajaService.cortesDeHoyDeEstaCaja()).extracting(Corte::getId).containsExactly(c2.getId());

        serCaja(1);
        VentaService.ResumenCorte r1 = ventaService.resumenDelDia();
        assertThat(r1.numVentas).isEqualTo(1);
        assertThat(r1.efectivo).isCloseTo(100, within(0.001));
        assertThat(cajaService.cortesDeHoyDeEstaCaja()).isEmpty();           // el corte de la 2 no es de la 1

        // Corte general: fondos 200 + 50, efectivo 100 + 200, tarjeta 100, 3 ventas
        VentaService.ResumenCorte general = ventaService.resumenGeneralDe(LocalDate.now());
        assertThat(general.esGeneral()).isTrue();
        assertThat(general.numVentas).isEqualTo(3);
        assertThat(general.porCaja).extracting(VentaService.VentasCaja::caja).containsExactly(1, 2);
        assertThat(general.porCaja.get(1).total()).isCloseTo(300, within(0.001));
        Corte cg = cajaService.hacerCorteGeneral(560, "Cuadre de todas");
        assertThat(cg.isGeneral()).isTrue();
        assertThat(cg.getEtiquetaCaja()).isEqualTo("Todas las cajas");
        assertThat(cg.getFondoInicial()).isCloseTo(250, within(0.001));
        assertThat(cg.getEfectivoEsperado()).isCloseTo(550, within(0.001));
        assertThat(cg.getDiferencia()).isCloseTo(10, within(0.001));
        assertThat(cg.getVentasTarjeta()).isCloseTo(100, within(0.001));
        assertThat(cajaService.cortesDeHoy()).extracting(Corte::getId).contains(c2.getId(), cg.getId());
        assertThat(corteRepo.findDeCaja(LocalDate.now(), 1)).isEmpty();      // el general no es de la caja 1

        // El corte general y el resumen general son solo del administrador
        iniciarComoCajero("cajeroCorte", java.util.EnumSet.of(me.eliassanchezfernandez.puntodeventa.model.Permiso.HACER_CORTE));
        assertThatThrownBy(() -> cajaService.hacerCorteGeneral(0, null)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> ventaService.resumenGeneralDe(LocalDate.now())).isInstanceOf(NegocioException.class);
    }

    @Test
    void elTicketPendienteEsDelCajeroEnEsaCaja() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Pendiente", 10, 20);
        ticketPendienteService.guardar(List.of(ventaService.crearDetalle(p, 3)));
        assertThat(ticketPendienteService.hayPendiente()).isTrue();

        serCaja(2);
        assertThat(ticketPendienteService.hayPendiente()).isFalse();          // en la caja 2 no está
        ticketPendienteService.guardar(List.of(ventaService.crearDetalle(p, 1)));
        vender(p, 1, Venta.FormaPago.EFECTIVO, 10);                            // cobrar en la 2 solo borra el de la 2
        assertThat(ticketPendienteService.hayPendiente()).isFalse();

        serCaja(1);
        assertThat(ticketPendienteService.recuperar()).hasSize(1);
        assertThat(ticketPendienteService.recuperar().get(0).getCantidad()).isEqualTo(3);
        ticketPendienteService.descartarDelCajeroActual();
        assertThat(ticketPendienteService.hayPendiente()).isFalse();
    }

    @Test
    void lasEstadisticasSeFiltranPorCaja() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Jugo", 20, 100);
        vender(p, 1, Venta.FormaPago.EFECTIVO, 20);
        serCaja(3);
        vender(p, 2, Venta.FormaPago.EFECTIVO, 40);
        vender(p, 1, Venta.FormaPago.EFECTIVO, 20);
        Periodo hoy = Periodo.dia(LocalDate.now());

        List<EstadisticasService.VentasPorCaja> porCaja = estadisticasService.ventasPorCaja(hoy);
        assertThat(porCaja).extracting(EstadisticasService.VentasPorCaja::caja).contains(1, 3);
        EstadisticasService.VentasPorCaja caja3 = porCaja.stream().filter(c -> c.caja() == 3).findFirst().orElseThrow();
        assertThat(caja3.ventas()).isEqualTo(2);
        assertThat(caja3.total()).isCloseTo(60, within(0.001));
        assertThat(caja3.etiqueta()).isEqualTo("Caja 3");
        assertThat(porCaja.stream().mapToDouble(EstadisticasService.VentasPorCaja::porcentaje).sum()).isCloseTo(100, within(0.01));
        assertThat(estadisticasService.cajasConVentas()).contains(1, 3);

        double totalCaja3 = estadisticasService.ventasPorCajero(hoy, 3).stream().mapToDouble(EstadisticasService.VentasCajero::total).sum();
        assertThat(totalCaja3).isCloseTo(60, within(0.001));
        double todas = estadisticasService.ventasPorCajero(hoy, null).stream().mapToDouble(EstadisticasService.VentasCajero::total).sum();
        assertThat(todas).isGreaterThanOrEqualTo(80);
        int ventasHoraCaja3 = estadisticasService.ventasPorHora(hoy, 3).stream().mapToInt(EstadisticasService.VentasHora::ventas).sum();
        assertThat(ventasHoraCaja3).isEqualTo(2);
    }

    @Test
    void losFoliosSonUnicosEntreCajasPorqueSalenDeLaMismaSecuencia() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Folio", 5, 100);
        Venta a = vender(p, 1, Venta.FormaPago.EFECTIVO, 5);
        serCaja(2);
        Venta b = vender(p, 1, Venta.FormaPago.EFECTIVO, 5);
        serCaja(1);
        Venta c = vender(p, 1, Venta.FormaPago.EFECTIVO, 5);
        assertThat(List.of(a.getFolio(), b.getFolio(), c.getFolio())).doesNotHaveDuplicates();
        assertThat(b.getId()).isGreaterThan(a.getId());
        assertThat(c.getId()).isGreaterThan(b.getId());
    }
}
