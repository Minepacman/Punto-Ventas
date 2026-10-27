package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.ParProductos;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoClima;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ResumenDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Agregados de Estadísticas: coinciden con las ventas crudas, excluyen las
 * canceladas, respetan las franjas, la canasta de mercado sale bien en un
 * conjunto conocido y la actualización incremental solo rehace lo que cambió.
 */
class AgregadosServiceTest extends BaseServicioTest {

    @Autowired private AgregadosService         agregadosService;
    @Autowired private VentaRepository          ventaRepo;
    @Autowired private ResumenDiarioRepository  resumenRepo;
    @Autowired private ProductoDiarioRepository productoDiarioRepo;
    @Autowired private AuditoriaService         auditoriaService;

    private Producto coca, sabritas, cafe;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        coca     = producto("Coca Cola 600 ml", 20, 100);
        sabritas = producto("Sabritas original", 18, 100);
        cafe     = producto("Café soluble", 60, 100);
    }

    /** Venta directa en la BD con fecha controlada (cobrar() siempre usa "ahora") */
    Venta venta(LocalDateTime fecha, Object... productoCantidad) {
        Venta v = new Venta();
        v.setFecha(fecha);
        double total = 0;
        for (int i = 0; i < productoCantidad.length; i += 2) {
            Producto p = (Producto) productoCantidad[i];
            double cant = ((Number) productoCantidad[i + 1]).doubleValue();
            DetalleVenta d = new DetalleVenta();
            d.setVenta(v);
            d.setProducto(p);
            d.setCantidad(cant);
            d.setPrecioVenta(p.getPrecioVenta());
            v.getDetalles().add(d);
            total += p.getPrecioVenta() * cant;
        }
        v.setTotal(total);
        v.setPagoCon(total);
        return ventaRepo.save(v);
    }

    @Test
    void elResumenDiarioCoincideConLasVentasYExcluyeCanceladas() {
        LocalDate dia = LocalDate.now().minusDays(3);
        venta(dia.atTime(8, 30), coca, 2, sabritas, 1);        // mañana: 58
        venta(dia.atTime(12, 0), cafe, 1);                      // mediodía (12:00 ya es mediodía): 60
        venta(dia.atTime(18, 59), coca, 1);                     // tarde: 20
        Venta cancelada = venta(dia.atTime(20, 0), cafe, 5);    // noche, cancelada: no cuenta
        cancelada.setCancelada(true);
        cancelada.setFechaCancelacion(dia.atTime(20, 5));
        ventaRepo.save(cancelada);

        AgregadosService.Resultado r = agregadosService.recalcularTodo();
        assertThat(r.dias()).isGreaterThanOrEqualTo(1);

        ResumenDiario res = resumenRepo.findByFecha(dia).orElseThrow();
        assertThat(res.getTotal()).isCloseTo(138, within(0.001));
        assertThat(res.getTransacciones()).isEqualTo(3);
        assertThat(res.getPiezas()).isCloseTo(5, within(0.001));
        assertThat(res.totalDe(Franja.MANANA)).isCloseTo(58, within(0.001));
        assertThat(res.totalDe(Franja.MEDIODIA)).isCloseTo(60, within(0.001));
        assertThat(res.totalDe(Franja.TARDE)).isCloseTo(20, within(0.001));
        assertThat(res.totalDe(Franja.NOCHE)).isZero();
        assertThat(res.transaccionesDe(Franja.MANANA)).isEqualTo(1);
        assertThat(res.getTicketPromedio()).isCloseTo(46, within(0.001));

        List<ProductoDiario> productos = productoDiarioRepo.findEntre(dia, dia);
        ProductoDiario pdCoca = productos.stream().filter(p -> p.getProducto().getId().equals(coca.getId())).findFirst().orElseThrow();
        assertThat(pdCoca.getPiezas()).isCloseTo(3, within(0.001));
        assertThat(pdCoca.getImporte()).isCloseTo(60, within(0.001));
        assertThat(pdCoca.piezasDe(Franja.MANANA)).isCloseTo(2, within(0.001));
        assertThat(pdCoca.piezasDe(Franja.TARDE)).isCloseTo(1, within(0.001));
        ProductoDiario pdCafe = productos.stream().filter(p -> p.getProducto().getId().equals(cafe.getId())).findFirst().orElseThrow();
        assertThat(pdCafe.getPiezas()).isCloseTo(1, within(0.001));   // las 5 canceladas no cuentan

        // "Hoy" en vivo da el mismo resultado que el agregado guardado
        AgregadosService.DiaCalculado vivo = agregadosService.calcularDia(dia);
        assertThat(vivo.resumen().getTotal()).isCloseTo(res.getTotal(), within(0.001));
        assertThat(vivo.productos()).hasSize(3);
    }

    @Test
    void lasFranjasRespetanLosLimites() {
        assertThat(Franja.de(0)).isEqualTo(Franja.MADRUGADA);
        assertThat(Franja.de(5)).isEqualTo(Franja.MADRUGADA);
        assertThat(Franja.de(6)).isEqualTo(Franja.MANANA);
        assertThat(Franja.de(11)).isEqualTo(Franja.MANANA);
        assertThat(Franja.de(12)).isEqualTo(Franja.MEDIODIA);
        assertThat(Franja.de(14)).isEqualTo(Franja.MEDIODIA);
        assertThat(Franja.de(15)).isEqualTo(Franja.TARDE);
        assertThat(Franja.de(18)).isEqualTo(Franja.TARDE);
        assertThat(Franja.de(19)).isEqualTo(Franja.NOCHE);
        assertThat(Franja.de(23)).isEqualTo(Franja.NOCHE);
        assertThat(Franja.de(LocalDateTime.of(2026, 10, 26, 23, 59))).isEqualTo(Franja.NOCHE);
        assertThatThrownBy(() -> Franja.de(24)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laCanastaDeMercadoCalculaSoporteConfianzaYLift() {
        // 10 tickets: 4 × (coca + sabritas), 2 × coca, 3 × (café + sabritas), 1 × café
        List<Venta> tickets = new ArrayList<>();
        LocalDateTime t = LocalDate.now().minusDays(2).atTime(10, 0);
        for (int i = 0; i < 4; i++) tickets.add(venta(t, coca, 1, sabritas, 2));
        for (int i = 0; i < 2; i++) tickets.add(venta(t, coca, 1));
        for (int i = 0; i < 3; i++) tickets.add(venta(t, cafe, 1, sabritas, 1));
        tickets.add(venta(t, cafe, 1));

        List<ParProductos> pares = AgregadosService.calcularPares(tickets);

        ParProductos cocaASabritas = par(pares, coca, sabritas);
        assertThat(cocaASabritas.getCoOcurrencias()).isEqualTo(4);
        assertThat(cocaASabritas.getSoporte()).isCloseTo(4.0 / 10, within(0.0001));
        assertThat(cocaASabritas.getConfianza()).isCloseTo(4.0 / 6, within(0.0001));     // P(sabritas | coca)
        assertThat(cocaASabritas.getLift()).isCloseTo((4.0 / 6) / (7.0 / 10), within(0.0001));
        ParProductos sabritasACoca = par(pares, sabritas, coca);
        assertThat(sabritasACoca.getConfianza()).isCloseTo(4.0 / 7, within(0.0001));
        ParProductos cafeASabritas = par(pares, cafe, sabritas);
        assertThat(cafeASabritas.getConfianza()).isCloseTo(3.0 / 4, within(0.0001));
        assertThat(cafeASabritas.getLift()).isCloseTo((3.0 / 4) / (7.0 / 10), within(0.0001));
        // Coca y café nunca van juntos: no hay par
        assertThat(pares).noneMatch(p -> p.getProductoA().getId().equals(coca.getId()) && p.getProductoB().getId().equals(cafe.getId()));

        // Un ticket cancelado no cuenta
        tickets.get(0).setCancelada(true);
        assertThat(par(AgregadosService.calcularPares(tickets), coca, sabritas).getCoOcurrencias()).isEqualTo(3);
        // Sin tickets no hay pares
        assertThat(AgregadosService.calcularPares(List.of())).isEmpty();
    }

    private static ParProductos par(List<ParProductos> pares, Producto a, Producto b) {
        return pares.stream()
                .filter(p -> p.getProductoA().getId().equals(a.getId()) && p.getProductoB().getId().equals(b.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("Falta el par " + a.getDescripcion() + " → " + b.getDescripcion()));
    }

    @Test
    void laCorrelacionConElClimaUsaElRegistroDeLaHora() {
        LocalDateTime lluvia = LocalDate.now().minusDays(2).atTime(16, 0);
        LocalDateTime sol    = LocalDate.now().minusDays(2).atTime(11, 0);
        List<ClimaRegistro> clima = List.of(
                new ClimaRegistro(lluvia, 12, 61, ClimaService.LLUVIA, ClimaService.FRIO),
                new ClimaRegistro(sol, 30, 0, ClimaService.DESPEJADO, ClimaService.CALOR));
        List<Venta> ventas = List.of(
                venta(lluvia.plusMinutes(10), cafe, 4, coca, 1),
                venta(lluvia.plusMinutes(40), cafe, 2),
                venta(sol.plusMinutes(5), coca, 5, cafe, 1),
                venta(sol.plusMinutes(30), coca, 3),
                venta(LocalDate.now().minusDays(1).atTime(9, 0), coca, 50));   // sin clima registrado: no cuenta

        List<ProductoClima> filas = AgregadosService.calcularProductoClima(ventas, clima);

        // Con clima: café 7 piezas, coca 9 → total 16. Con lluvia: café 6, coca 1 (total 7)
        ProductoClima cafeLluvia = filas.stream().filter(f -> f.getTipo() == ProductoClima.Tipo.CONDICION
                && f.getClave().equals(ClimaService.LLUVIA) && f.getProducto().getId().equals(cafe.getId())).findFirst().orElseThrow();
        assertThat(cafeLluvia.getPiezas()).isCloseTo(6, within(0.001));
        assertThat(cafeLluvia.getIndice()).isCloseTo((6.0 / 7) / (7.0 / 16), within(0.001));
        ProductoClima cocaCalor = filas.stream().filter(f -> f.getTipo() == ProductoClima.Tipo.BANDA
                && f.getClave().equals(ClimaService.CALOR) && f.getProducto().getId().equals(coca.getId())).findFirst().orElseThrow();
        assertThat(cocaCalor.getIndice()).isGreaterThan(1.0);
        // Coca con lluvia solo llevó 1 pieza: por debajo del mínimo, no se genera fila
        assertThat(filas).noneMatch(f -> f.getClave().equals(ClimaService.LLUVIA) && f.getProducto().getId().equals(coca.getId()));
        assertThat(AgregadosService.calcularProductoClima(ventas, List.of())).isEmpty();
    }

    @Test
    void laActualizacionIncrementalSoloRehaceLosDiasQueCambiaron() {
        LocalDate ayer = LocalDate.now().minusDays(1);
        LocalDate hace10 = LocalDate.now().minusDays(10);
        Venta vieja = venta(hace10.atTime(10, 0), coca, 2);
        venta(ayer.atTime(10, 0), sabritas, 1);
        agregadosService.recalcularTodo();
        assertThat(resumenRepo.findByFecha(hace10).orElseThrow().getTotal()).isCloseTo(40, within(0.001));
        assertThat(resumenRepo.findByFecha(ayer).orElseThrow().getTotal()).isCloseTo(18, within(0.001));
        assertThat(agregadosService.estaDesactualizado()).isFalse();

        // Una venta nueva (ahora) y la cancelación de la venta vieja
        venta(LocalDateTime.now(), cafe, 1);
        vieja.setCancelada(true);
        vieja.setFechaCancelacion(LocalDateTime.now());
        ventaRepo.save(vieja);

        AgregadosService.Resultado r = agregadosService.actualizar();

        assertThat(r.dias()).isBetween(1, 2);                          // hoy y, si hubo, el día de la vieja
        assertThat(resumenRepo.findByFecha(hace10)).isEmpty();          // el día quedó sin ventas activas
        assertThat(resumenRepo.findByFecha(ayer).orElseThrow().getTotal()).isCloseTo(18, within(0.001));
        Optional<ResumenDiario> hoy = resumenRepo.findByFecha(LocalDate.now());
        assertThat(hoy).isPresent();
        assertThat(hoy.get().getTotal()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void recalcularManualExigePermisoYQuedaEnLaBitacora() throws Exception {
        venta(LocalDateTime.now().minusHours(1), coca, 1);
        agregadosService.recalcularManual();
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "ESTADISTICAS_RECALCULADAS".equals(a.getAccion()));

        iniciarComoCajero("cajeroEst", null);
        assertThatThrownBy(() -> agregadosService.recalcularManual()).isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void sinVentasNoFallaYQuedaMarcadoComoActualizado() {
        AgregadosService.Resultado r = agregadosService.recalcularTodo();
        assertThat(r.pares()).isZero();
        assertThat(agregadosService.ultimaActualizacion()).isPresent();
        assertThat(agregadosService.actualizar().dias()).isZero();
        List<Auditoria> bitacora = auditoriaService.ultimas();
        assertThat(bitacora).isNotNull();
    }
}
