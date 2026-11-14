package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ResumenDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * M-14 del QA: los agregados de Estadísticas se quedaban viejos hasta la
 * siguiente corrida (03:00 o el próximo arranque) cuando se cancelaba una venta
 * de un día ya consolidado, o cuando el día de la última corrida seguía
 * recibiendo ventas (la corrida de arranque es a media mañana; de madrugada
 * "ayer" aún no está consolidado). Ahora esos días se calculan en vivo.
 */
class EstadisticasEnVivoTest extends BaseServicioTest {

    @Autowired private EstadisticasService estadisticasService;
    @Autowired private AgregadosService agregadosService;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private ResumenDiarioRepository resumenRepo;
    @Autowired private ConfiguracionRepository configuracionRepo;

    private Producto coca;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        coca = producto("Coca Cola 600 ml", 20, 500);
    }

    Venta venta(LocalDateTime fecha, double cantidad) {
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

    private void fijarUltimaActualizacion(LocalDateTime cuando) {
        Configuracion c = configuracionRepo.findById(Configuracion.ID_UNICO).orElseThrow();
        c.setEstadisticasActualizado(cuando);
        configuracionRepo.save(c);
    }

    @Test
    void cancelarUnaVentaDeUnDiaConsolidadoSeReflejaSinEsperarLaSiguienteCorrida() {
        LocalDate hace3 = LocalDate.now().minusDays(3);
        Venta v = venta(hace3.atTime(10, 0), 2);                 // 40
        venta(hace3.atTime(12, 0), 1);                           // 20
        agregadosService.recalcularTodo();
        assertThat(resumenRepo.findByFecha(hace3).orElseThrow().getTotal()).isCloseTo(60, within(0.001));
        Periodo p = Periodo.entre(hace3, hace3);
        assertThat(estadisticasService.totales(p).total()).isCloseTo(60, within(0.001));

        // Se cancela la venta de 40 y NO corre la actualización de agregados
        v.setCancelada(true);
        v.setFechaCancelacion(LocalDateTime.now());
        ventaRepo.save(v);

        assertThat(resumenRepo.findByFecha(hace3).orElseThrow().getTotal())
                .as("el agregado guardado sigue viejo: lo rehace la corrida nocturna").isCloseTo(60, within(0.001));
        EstadisticasService.Totales t = estadisticasService.totales(p);
        assertThat(t.total()).as("la pantalla ya no debe contar la venta cancelada").isCloseTo(20, within(0.001));
        assertThat(t.transacciones()).isEqualTo(1);
        assertThat(estadisticasService.topProductos(p, 5)).singleElement()
                .satisfies(tp -> assertThat(tp.piezas()).isCloseTo(1, within(0.001)));
        // Un día consolidado que no cambió se sigue leyendo del agregado (una sola fila, sin duplicar)
        List<ResumenDiario> semana = estadisticasService.resumenesEntre(Periodo.entre(hace3.minusDays(2), LocalDate.now()));
        assertThat(semana).extracting(ResumenDiario::getFecha).doesNotHaveDuplicates();
        assertThat(semana).filteredOn(r -> r.getFecha().equals(hace3)).singleElement()
                .satisfies(r -> assertThat(r.getTotal()).isCloseTo(20, within(0.001)));
    }

    @Test
    void elDiaDeLaUltimaCorridaYLosSiguientesSeCalculanEnVivo() {
        LocalDate ayer = LocalDate.now().minusDays(1);
        venta(ayer.atTime(9, 0), 1);                              // 20, antes de la corrida de "ayer 09:30"
        agregadosService.recalcularTodo();
        // La última corrida fue ayer a las 09:30 (arranque del programa); la PC se apagó antes de las 03:00
        fijarUltimaActualizacion(ayer.atTime(9, 30));
        venta(ayer.atTime(20, 0), 3);                             // 60: venta de ayer en la noche, sin consolidar
        venta(LocalDate.now().atTime(0, 30), 1);                  // 20: madrugada de hoy

        assertThat(resumenRepo.findByFecha(ayer).orElseThrow().getTotal())
                .as("el agregado de ayer quedó incompleto").isCloseTo(20, within(0.001));
        assertThat(estadisticasService.totales(Periodo.dia(ayer)).total())
                .as("ayer se calcula en vivo porque la corrida fue ese día").isCloseTo(80, within(0.001));
        assertThat(estadisticasService.totales(Periodo.dia(LocalDate.now())).total()).isCloseTo(20, within(0.001));
        List<ResumenDiario> dos = estadisticasService.resumenesEntre(Periodo.entre(ayer, LocalDate.now()));
        assertThat(dos).extracting(ResumenDiario::getFecha).containsExactly(ayer, LocalDate.now());
        assertThat(dos.get(0).getTotal()).isCloseTo(80, within(0.001));
        assertThat(estadisticasService.serieDiaria(Periodo.entre(ayer, LocalDate.now())).get(ayer)).isCloseTo(80, within(0.001));
    }

    @Test
    void sinNingunaCorridaTodoSeCalculaEnVivoYConCorridaRecienteLosDiasViejosVienenDelAgregado() {
        LocalDate hace5 = LocalDate.now().minusDays(5);
        venta(hace5.atTime(11, 0), 2);                            // 40
        // Nunca ha corrido la actualización: no hay agregados, pero la pantalla no muestra ceros
        assertThat(agregadosService.ultimaActualizacion()).isEmpty();
        assertThat(estadisticasService.totales(Periodo.dia(hace5)).total()).isCloseTo(40, within(0.001));

        agregadosService.recalcularTodo();
        EstadisticasService.DiasEnVivo vivo = estadisticasService.diasEnVivo(Periodo.entre(hace5, LocalDate.now()));
        assertThat(vivo.hastaConsolidado()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(vivo.desdeVivo()).isEqualTo(LocalDate.now());
        assertThat(vivo.sueltos()).isEmpty();
        assertThat(estadisticasService.totales(Periodo.entre(hace5, LocalDate.now())).total()).isCloseTo(40, within(0.001));
    }
}
