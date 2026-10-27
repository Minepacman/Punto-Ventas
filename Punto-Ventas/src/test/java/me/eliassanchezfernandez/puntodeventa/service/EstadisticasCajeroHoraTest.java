package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.VentasCajero;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.VentasHora;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Ventas por cajero y por hora: las sumas coinciden con las ventas crudas y las
 * canceladas no cuentan.
 */
class EstadisticasCajeroHoraTest extends BaseServicioTest {

    @Autowired private EstadisticasService estadisticasService;
    @Autowired private VentaRepository ventaRepo;

    private Producto p;
    private final LocalDate hoy = LocalDate.now();

    @BeforeEach
    void iniciar() throws Exception {
        iniciarComoAdmin();
        p = producto("Refresco", 20, 500);
    }

    /** Cobra una venta de {@code piezas} y la deja fechada a esa hora de ese día */
    private Venta vender(int piezas, LocalDate dia, int hora, int minuto) {
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, piezas)), Venta.FormaPago.EFECTIVO, piezas * 20 + 100);
        v.setFecha(LocalDateTime.of(dia, java.time.LocalTime.of(hora, minuto)));
        return ventaRepo.save(v);
    }

    @Test
    void ventasPorCajeroSumaIgualQueLasVentasCrudasYExcluyeLasCanceladas() throws Exception {
        crearUsuario("ana", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        vender(1, hoy, 9, 15);                       // admin: 20
        vender(2, hoy, 9, 45);                       // admin: 40
        Venta cancelada = vender(5, hoy, 10, 5);     // admin: 100, se cancela
        iniciarComo("ana", CLAVE_PRUEBA);
        vender(3, hoy, 14, 30);                      // ana: 60
        iniciarComoAdmin();
        ventaService.cancelar(cancelada.getId(), "error", List.of());

        List<VentasCajero> r = estadisticasService.ventasPorCajero(Periodo.dia(hoy));

        // Valor esperado calculado de las ventas crudas (activas) agrupadas por cajero
        Map<String, Double> esperado = ventaRepo.findEntre(hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay()).stream()
                .filter(Venta::estaActiva)
                .collect(Collectors.groupingBy(v -> v.getCajeroNombre() != null ? v.getCajeroNombre() : "Sin cajero",
                        TreeMap::new, Collectors.summingDouble(Venta::getTotal)));
        assertThat(r).extracting(VentasCajero::cajero).containsExactlyInAnyOrderElementsOf(esperado.keySet());
        for (VentasCajero c : r) assertThat(c.total()).isCloseTo(esperado.get(c.cajero()), within(0.005));

        VentasCajero ana = r.stream().filter(c -> c.cajero().endsWith(" ana")).findFirst().orElseThrow();
        VentasCajero admin = r.stream().filter(c -> c != ana).findFirst().orElse(null);
        assertThat(ana.ventas()).isEqualTo(1);
        assertThat(ana.total()).isCloseTo(60, within(0.005));
        assertThat(ana.ticketPromedio()).isCloseTo(60, within(0.005));
        // La cancelada (100) no cuenta: el admin suma 20 + 40
        double totalAdmin = r.stream().filter(c -> c != ana).mapToDouble(VentasCajero::total).sum();
        assertThat(totalAdmin).isCloseTo(60, within(0.005));
        assertThat(admin).isNotNull();
        assertThat(admin.ventas()).isEqualTo(2);
        assertThat(r.stream().mapToDouble(VentasCajero::porcentaje).sum()).isCloseTo(100, within(0.01));
        assertThat(r.get(0).total()).isGreaterThanOrEqualTo(r.get(r.size() - 1).total());   // de mayor a menor
    }

    @Test
    void ventasPorHoraSumaIgualQueLasVentasCrudasYExcluyeLasCanceladas() {
        vender(1, hoy, 9, 15);                       // 09: 20
        vender(2, hoy, 9, 59);                       // 09: 40
        vender(4, hoy, 14, 0);                       // 14: 80
        Venta cancelada = vender(10, hoy, 14, 30);   // 14: 200 (cancelada)
        vender(1, hoy, 23, 59);                      // 23: 20
        ventaService.cancelar(cancelada.getId(), "devolución", List.of());

        List<VentasHora> r = estadisticasService.ventasPorHora(Periodo.dia(hoy));

        assertThat(r).extracting(VentasHora::hora).containsExactly(9, 14, 23);          // solo horas con ventas, en orden
        assertThat(r.get(0).ventas()).isEqualTo(2);
        assertThat(r.get(0).total()).isCloseTo(60, within(0.005));
        assertThat(r.get(1).total()).isCloseTo(80, within(0.005));                       // sin los 200 cancelados
        assertThat(r.get(2).total()).isCloseTo(20, within(0.005));
        assertThat(r.get(0).rango()).isEqualTo("09:00 - 09:59");

        double crudo = ventaRepo.findEntre(hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay()).stream()
                .filter(Venta::estaActiva).mapToDouble(Venta::getTotal).sum();
        assertThat(r.stream().mapToDouble(VentasHora::total).sum()).isCloseTo(crudo, within(0.005));
        assertThat(r.stream().mapToInt(VentasHora::ventas).sum()).isEqualTo(
                (int) ventaRepo.findEntre(hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay()).stream().filter(Venta::estaActiva).count());
        assertThat(r.stream().mapToDouble(VentasHora::porcentaje).sum()).isCloseTo(100, within(0.01));
    }

    @Test
    void elPeriodoRecortaPorFechaYUnDiaSinVentasDaListaVacia() {
        vender(1, hoy, 10, 0);
        vender(3, hoy.minusDays(1), 10, 0);
        vender(5, hoy.minusDays(10), 18, 0);

        assertThat(estadisticasService.ventasPorHora(Periodo.dia(hoy)).stream().mapToDouble(VentasHora::total).sum())
                .isCloseTo(20, within(0.005));
        assertThat(estadisticasService.ventasPorHora(Periodo.entre(hoy.minusDays(1), hoy)).stream().mapToDouble(VentasHora::total).sum())
                .isCloseTo(80, within(0.005));
        assertThat(estadisticasService.ventasPorCajero(Periodo.dia(hoy.minusDays(30)))).isEmpty();
        assertThat(estadisticasService.ventasPorHora(Periodo.dia(hoy.minusDays(30)))).isEmpty();
    }

    @Test
    void exigenVerHistorial() throws Exception {
        iniciarComoCajero("sinhistorial", EnumSet.noneOf(Permiso.class));
        assertThatThrownBy(() -> estadisticasService.ventasPorCajero(Periodo.dia(hoy)))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> estadisticasService.ventasPorHora(Periodo.dia(hoy)))
                .isInstanceOf(PermisoRequeridoException.class);
    }
}
