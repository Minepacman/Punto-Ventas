package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ResumenDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.AlcanceComparacion;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.CifrasDia;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.ComparacionDia;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.ResumenHoy;
import me.eliassanchezfernandez.puntodeventa.util.FechasComparacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Vista "Hoy" de Estadísticas: venta y tickets de hoy contra ayer, el mismo día
 * de la semana pasada y hoy hace un año, cortando los días de referencia a la
 * misma hora de hoy. Las ventas canceladas no cuentan (igual que en el corte).
 */
class EstadisticasHoyTest extends BaseServicioTest {

    @Autowired private EstadisticasService estadisticasService;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private ResumenDiarioRepository resumenRepo;

    private Producto coca;
    private final LocalDate hoy = LocalDate.now();
    /** "Ahora" fijo: mediodía de hoy */
    private final LocalDateTime ahora = hoy.atTime(12, 0);

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        coca = producto("Coca Cola 600 ml", 20, 500);
    }

    private Venta venta(LocalDateTime fecha, double cantidad) {
        Venta v = new Venta();
        v.setFecha(fecha);
        DetalleVenta d = new DetalleVenta();
        d.setVenta(v);
        d.setProducto(coca);
        d.setCantidad(cantidad);
        d.setPrecioVenta(coca.getPrecioVenta());
        v.getDetalles().add(d);
        v.setTotal(coca.getPrecioVenta() * cantidad);
        v.setPagoCon(v.getTotal());
        return ventaRepo.save(v);
    }

    private Venta cancelada(LocalDateTime fecha, double cantidad) {
        Venta v = venta(fecha, cantidad);
        v.setCancelada(true);
        v.setFechaCancelacion(fecha.plusMinutes(5));
        return ventaRepo.save(v);
    }

    private static ComparacionDia la(ResumenHoy r, int indice) { return r.comparaciones().get(indice); }

    @Test
    void comparaHoyConAyerYLaSemanaPasadaHastaLaMismaHoraYExcluyeLasCanceladas() {
        LocalDate ayer = hoy.minusDays(1);
        LocalDate hace7 = hoy.minusDays(7);
        LocalDate haceUnAnio = hoy.minusYears(1);

        venta(haceUnAnio.atTime(8, 0), 1.5);           // 30: la tienda ya existía hace un año
        venta(hoy.atTime(9, 0), 1);                    // 20
        venta(hoy.atTime(10, 30), 2);                  // 40  → hoy: 60, 2 tickets
        cancelada(hoy.atTime(11, 0), 50);              // no cuenta
        venta(ayer.atTime(9, 0), 1);                   // 20
        venta(ayer.atTime(11, 59), 1);                 // 20  → ayer hasta las 12:00: 40, 2 tickets
        venta(ayer.atTime(12, 0), 5);                  // 100 a las 12:00 en punto: ya NO entra (rango semiabierto)
        venta(ayer.atTime(16, 0), 5);                  // 100 por la tarde: fuera del corte
        cancelada(ayer.atTime(10, 0), 50);             // no cuenta aunque sea antes del corte
        venta(hace7.atTime(13, 0), 1);                 // la semana pasada solo vendió por la tarde

        ResumenHoy r = estadisticasService.resumenHoy(ahora);
        assertThat(r.hoy()).isEqualTo(hoy);
        assertThat(r.hasta()).isEqualTo(LocalTime.of(12, 0));
        assertThat(r.cifras()).isEqualTo(new CifrasDia(60, 2));
        assertThat(r.comparaciones()).hasSize(3);

        ComparacionDia vsAyer = la(r, 0);
        assertThat(vsAyer.etiqueta()).isEqualTo("vs. ayer");
        assertThat(vsAyer.dia()).isEqualTo(ayer);
        assertThat(vsAyer.alcance()).isEqualTo(AlcanceComparacion.HASTA_LA_HORA);
        assertThat(vsAyer.referencia()).isEqualTo(new CifrasDia(40, 2));
        assertThat(vsAyer.variacionTotalPct(r.cifras())).isCloseTo(50.0, within(0.001));
        assertThat(vsAyer.diferenciaTotal(r.cifras())).isCloseTo(20.0, within(0.001));
        assertThat(vsAyer.variacionTicketsPct(r.cifras())).isCloseTo(0.0, within(0.001));
        assertThat(vsAyer.diferenciaTickets(r.cifras())).isZero();

        ComparacionDia vsSemana = la(r, 1);
        assertThat(vsSemana.etiqueta()).isEqualTo(FechasComparacion.etiquetaSemanaPasada(hoy));
        assertThat(vsSemana.dia()).isEqualTo(hace7);
        assertThat(vsSemana.alcance()).as("hubo tickets ese día, aunque después del corte").isEqualTo(AlcanceComparacion.HASTA_LA_HORA);
        assertThat(vsSemana.referencia()).isEqualTo(CifrasDia.CERO);
        assertThat(vsSemana.variacionTotalPct(r.cifras())).as("sin dividir entre cero").isNull();
        assertThat(vsSemana.diferenciaTotal(r.cifras())).isCloseTo(60.0, within(0.001));
        assertThat(vsSemana.diferenciaTickets(r.cifras())).isEqualTo(2);

        ComparacionDia vsAnio = la(r, 2);
        assertThat(vsAnio.etiqueta()).isEqualTo("vs. hoy hace un año");
        assertThat(vsAnio.dia()).isEqualTo(haceUnAnio);
        assertThat(vsAnio.alcance()).isEqualTo(AlcanceComparacion.HASTA_LA_HORA);
        assertThat(vsAnio.referencia()).isEqualTo(new CifrasDia(30, 1));
        assertThat(vsAnio.variacionTotalPct(r.cifras())).isCloseTo(100.0, within(0.001));
        assertThat(vsAnio.variacionTicketsPct(r.cifras())).isCloseTo(100.0, within(0.001));
    }

    @Test
    void sinDatosCuandoLaTiendaAunNoVendiaYSinVentasCuandoYaVendiaPeroEseDiaNoHuboNada() {
        venta(hoy.minusDays(3).atTime(10, 0), 1);      // primera venta: hace 3 días
        venta(hoy.atTime(8, 0), 1);

        ResumenHoy r = estadisticasService.resumenHoy(ahora);
        assertThat(la(r, 0).alcance()).as("ayer la tienda ya vendía pero no hubo ventas").isEqualTo(AlcanceComparacion.SIN_VENTAS);
        assertThat(la(r, 0).hayReferencia()).isFalse();
        assertThat(la(r, 0).variacionTotalPct(r.cifras())).isNull();
        assertThat(la(r, 1).alcance()).as("hace 7 días la tienda no existía").isEqualTo(AlcanceComparacion.LA_TIENDA_NO_VENDIA);
        assertThat(la(r, 2).alcance()).as("tienda con menos de un año").isEqualTo(AlcanceComparacion.LA_TIENDA_NO_VENDIA);
    }

    @Test
    void sinNingunaVentaTodoQuedaEnCeroYSinReferencia() {
        ResumenHoy r = estadisticasService.resumenHoy(ahora);
        assertThat(r.cifras()).isEqualTo(CifrasDia.CERO);
        assertThat(r.comparaciones()).allSatisfy(c -> {
            assertThat(c.alcance()).isEqualTo(AlcanceComparacion.LA_TIENDA_NO_VENDIA);
            assertThat(c.hayReferencia()).isFalse();
        });
    }

    @Test
    void unaVentaCanceladaNoCuentaComoPrimeraVentaDeLaTienda() {
        cancelada(hoy.minusDays(10).atTime(10, 0), 1);
        venta(hoy.minusDays(2).atTime(10, 0), 1);
        ResumenHoy r = estadisticasService.resumenHoy(ahora);
        assertThat(la(r, 1).alcance()).as("hace 7 días solo había una venta cancelada: la tienda aún no vendía")
                .isEqualTo(AlcanceComparacion.LA_TIENDA_NO_VENDIA);
    }

    @Test
    void siSoloQuedaElResumenDiarioDeUnDiaViejoSeComparaElDiaCompleto() {
        // Historial depurado: el ticket de hace un año ya no está, pero sí su resumen del día
        LocalDate haceUnAnio = hoy.minusYears(1);
        venta(haceUnAnio.minusDays(30).atTime(9, 0), 1);     // la tienda ya existía
        ResumenDiario resumen = new ResumenDiario(haceUnAnio);
        resumen.sumarVenta(Franja.MANANA, 80, 4);
        resumen.sumarVenta(Franja.TARDE, 120, 6);
        resumenRepo.save(resumen);
        venta(hoy.atTime(9, 0), 5);                         // hoy: 100, 1 ticket

        ResumenHoy r = estadisticasService.resumenHoy(ahora);
        ComparacionDia vsAnio = la(r, 2);
        assertThat(vsAnio.alcance()).isEqualTo(AlcanceComparacion.DIA_COMPLETO);
        assertThat(vsAnio.referencia()).isEqualTo(new CifrasDia(200, 2));
        assertThat(vsAnio.variacionTotalPct(r.cifras())).isCloseTo(-50.0, within(0.001));
        assertThat(vsAnio.diferenciaTickets(r.cifras())).isEqualTo(-1);
    }

    @Test
    void coincideConElCorteDelDiaYCuentaElProductoComunComoUnaVentaNormal() {
        venta(hoy.atTime(9, 0), 2);                                                          // 40
        ventaService.cobrar(List.of(ventaService.crearDetalleProductoComun("Bolsa de hielo", 25, 2)),
                Venta.FormaPago.EFECTIVO, 50);                                               // 50, producto común
        cancelada(hoy.atTime(10, 0), 3);

        ResumenHoy r = estadisticasService.resumenHoy(LocalDateTime.now());
        assertThat(r.cifras().tickets()).isEqualTo(2);
        assertThat(r.cifras().total()).isCloseTo(90, within(0.001));
        Double corte = ventaRepo.sumTotalEntre(hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay(), null);
        assertThat(r.cifras().total()).isCloseTo(corte, within(0.001));
    }

    @Test
    void exigeElPermisoDeVerHistorial() throws Exception {
        iniciarComoCajero("cajero-hoy", Set.of(Permiso.APLICAR_MAYOREO));
        assertThatThrownBy(() -> estadisticasService.resumenHoy(ahora)).isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void elDiaDeReferenciaDel29DeFebreroEsEl28DelAnioAnterior() {
        ResumenHoy r = estadisticasService.resumenHoy(LocalDateTime.of(2028, 2, 29, 10, 0));
        assertThat(la(r, 2).dia()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(la(r, 0).dia()).isEqualTo(LocalDate.of(2028, 2, 28));
        assertThat(la(r, 1).dia()).isEqualTo(LocalDate.of(2028, 2, 22));
    }
}
