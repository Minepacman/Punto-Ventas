package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Comparacion;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Totales;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Consultas de la pantalla de Estadísticas: periodos, comparaciones (con
 * periodos anteriores vacíos), mapa de calor, ranking y permiso VER_HISTORIAL.
 */
class EstadisticasConsultasTest extends BaseServicioTest {

    @Autowired private EstadisticasService estadisticasService;
    @Autowired private AgregadosService    agregadosService;
    @Autowired private VentaRepository     ventaRepo;

    private Producto coca, pan;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        coca = producto("Coca Cola 600 ml", 20, 100);
        pan  = producto("Pan blanco", 40, 100);
    }

    Venta venta(LocalDateTime fecha, Producto p, double cantidad) {
        Venta v = new Venta();
        v.setFecha(fecha);
        DetalleVenta d = new DetalleVenta();
        d.setVenta(v);
        d.setProducto(p);
        d.setCantidad(cantidad);
        d.setPrecioVenta(p.getPrecioVenta());
        v.getDetalles().add(d);
        v.setTotal(p.getPrecioVenta() * cantidad);
        v.setPagoCon(v.getTotal());
        return ventaRepo.save(v);
    }

    @Test
    void lasComparacionesNoDividenEntreCeroCuandoElPeriodoAnteriorEstaVacio() {
        LocalDate hoy = LocalDate.now();
        venta(hoy.atTime(10, 0), coca, 2);                        // hoy: 40 (en vivo, sin agregados)
        venta(hoy.minusWeeks(1).atTime(10, 0), coca, 1);          // mismo día de la semana pasada: 20
        agregadosService.recalcularTodo();

        List<Comparacion> comps = estadisticasService.comparaciones();
        assertThat(comps).extracting(Comparacion::etiqueta).containsExactly("Hoy", "Semana", "Mes", "Año");

        Comparacion dia = comps.get(0);
        assertThat(dia.actual().total()).isCloseTo(40, within(0.001));
        assertThat(dia.anterior().total()).isCloseTo(20, within(0.001));
        assertThat(dia.variacionPct()).isCloseTo(100.0, within(0.001));

        // Un periodo anterior sin ventas → variación nula (no hay con qué comparar)
        Comparacion sinReferencia = new Comparacion("x", "x", Periodo.dia(hoy), Periodo.dia(hoy.minusDays(1)),
                new Totales(50, 1, 1), Totales.CERO);
        assertThat(sinReferencia.variacionPct()).isNull();
        Comparacion baja = new Comparacion("x", "x", Periodo.dia(hoy), Periodo.dia(hoy.minusDays(1)),
                new Totales(50, 1, 1), new Totales(100, 2, 2));
        assertThat(baja.variacionPct()).isCloseTo(-50.0, within(0.001));
        // Los periodos "a la fecha" terminan hoy y el anterior el mismo tramo
        Comparacion semana = comps.get(1);
        assertThat(semana.periodoActual().hasta()).isEqualTo(hoy);
        assertThat(semana.periodoActual().dias()).isEqualTo(semana.periodoAnterior().dias());
        assertThat(comps.get(2).periodoActual().desde()).isEqualTo(hoy.withDayOfMonth(1));
        assertThat(comps.get(3).periodoActual().desde()).isEqualTo(hoy.withDayOfYear(1));
    }

    @Test
    void totalesSerieYRankingMezclanAgregadosConHoyEnVivo() {
        LocalDate hoy = LocalDate.now();
        venta(hoy.minusDays(2).atTime(9, 0), pan, 1);             // 40 (agregado)
        venta(hoy.minusDays(1).atTime(17, 0), coca, 3);           // 60 (agregado)
        agregadosService.recalcularTodo();
        venta(hoy.atTime(8, 0), coca, 1);                         // 20 (hoy, en vivo: no está agregado)

        Periodo p = Periodo.entre(hoy.minusDays(2), hoy);
        Totales t = estadisticasService.totales(p);
        assertThat(t.total()).isCloseTo(120, within(0.001));
        assertThat(t.transacciones()).isEqualTo(3);
        assertThat(t.piezas()).isCloseTo(5, within(0.001));
        assertThat(t.ticketPromedio()).isCloseTo(40, within(0.001));

        Map<LocalDate, Double> serie = estadisticasService.serieDiaria(p);
        assertThat(serie).hasSize(3);
        assertThat(serie.get(hoy)).isCloseTo(20, within(0.001));
        assertThat(serie.get(hoy.minusDays(1))).isCloseTo(60, within(0.001));

        List<EstadisticasService.ProductoTop> top = estadisticasService.topProductos(p, 10);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).producto().getId()).isEqualTo(coca.getId());     // 80 > 40
        assertThat(top.get(0).piezas()).isCloseTo(4, within(0.001));
        assertThat(top.get(0).importe()).isCloseTo(80, within(0.001));

        Map<Franja, Totales> franjas = estadisticasService.porFranja(p);
        assertThat(franjas.get(Franja.MANANA).total()).isCloseTo(60, within(0.001));
        assertThat(franjas.get(Franja.TARDE).total()).isCloseTo(60, within(0.001));
        assertThat(franjas.get(Franja.NOCHE).total()).isZero();

        // Hoy solo (en vivo)
        assertThat(estadisticasService.totales(Periodo.dia(hoy)).total()).isCloseTo(20, within(0.001));
        // Un periodo futuro no tiene ventas
        assertThat(estadisticasService.totales(Periodo.entre(hoy.plusDays(1), hoy.plusDays(3)))).isEqualTo(Totales.CERO);
    }

    @Test
    void elMapaDeCalorYElDiaConMasVentasSalenDeLosResumenes() {
        LocalDate sabado = LocalDate.of(2026, 10, 24);   // sábado
        ResumenDiario s = new ResumenDiario(sabado);
        s.sumarVenta(Franja.TARDE, 300, 3);
        s.sumarVenta(Franja.TARDE, 100, 1);
        s.sumarVenta(Franja.MANANA, 50, 1);
        ResumenDiario l = new ResumenDiario(sabado.minusDays(5));   // lunes
        l.sumarVenta(Franja.MADRUGADA, 10, 1);

        double[][] mapa = EstadisticasService.mapaCalorDe(List.of(s, l));
        int sab = DayOfWeek.SATURDAY.getValue() - 1, lun = 0;
        int tarde = Franja.TARDE.ordinal(), manana = Franja.MANANA.ordinal(), madrugada = Franja.MADRUGADA.ordinal();
        assertThat(mapa[sab][tarde]).isCloseTo(400, within(0.001));
        assertThat(mapa[sab][manana]).isCloseTo(50, within(0.001));
        assertThat(mapa[lun][madrugada]).isCloseTo(10, within(0.001));
        assertThat(mapa[sab][Franja.NOCHE.ordinal()]).isZero();

        assertThat(EstadisticasService.intensidad(400, 400)).isEqualTo(3);
        assertThat(EstadisticasService.intensidad(150, 400)).isEqualTo(2);
        assertThat(EstadisticasService.intensidad(50, 400)).isEqualTo(1);
        assertThat(EstadisticasService.intensidad(0, 400)).isZero();
        assertThat(EstadisticasService.intensidad(5, 0)).isZero();

        double[] porDia = new double[7];
        porDia[sab] = 450; porDia[lun] = 10;
        assertThat(EstadisticasService.diaConMasVentas(porDia)).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(EstadisticasService.diaConMasVentas(new double[7])).isNull();
    }

    @Test
    void losPeriodosSeValidanYLasConsultasExigenVerHistorial() throws Exception {
        LocalDate hoy = LocalDate.now();
        assertThatThrownBy(() -> Periodo.entre(hoy, hoy.minusDays(1))).isInstanceOf(NegocioException.class);
        assertThat(Periodo.semana(hoy).desde().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(Periodo.semana(hoy).hasta()).isEqualTo(hoy);
        assertThat(Periodo.entre(hoy.minusDays(6), hoy).dias()).isEqualTo(7);

        iniciarComoCajero("cajeroSinHist", null);
        assertThatThrownBy(() -> estadisticasService.totales(Periodo.dia(hoy))).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> estadisticasService.topProductos(Periodo.dia(hoy), 5)).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> estadisticasService.paresTop(5)).isInstanceOf(PermisoRequeridoException.class);
    }
}
