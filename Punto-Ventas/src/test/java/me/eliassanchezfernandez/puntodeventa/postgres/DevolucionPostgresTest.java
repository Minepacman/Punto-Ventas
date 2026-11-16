package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.DevolucionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.service.ConsultasMovilService;
import me.eliassanchezfernandez.puntodeventa.service.DevolucionService;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Devoluciones simultáneas contra PostgreSQL real (las transacciones sí corren a la vez): varias
 * cajas devuelven del mismo renglón y entre todas nunca pasan de lo vendido; el inventario y lo
 * reembolsado coinciden con las devoluciones que sí quedaron.
 */
class DevolucionPostgresTest extends BasePostgresTest {

    @Autowired private DevolucionService devolucionService;
    @Autowired private DevolucionRepository devolucionRepo;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private EstadisticasService estadisticasService;
    @Autowired private ConsultasMovilService consultasMovilService;

    private List<Throwable> enParalelo(List<Runnable> acciones) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(acciones.size());
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Throwable>> futuros = new ArrayList<>();
        for (Runnable r : acciones) {
            futuros.add(pool.submit(() -> {
                salida.await();
                try {
                    r.run();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        salida.countDown();
        List<Throwable> errores = new ArrayList<>();
        for (Future<Throwable> f : futuros) errores.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return errores;
    }

    @Test
    void variasCajasDevuelvenDelMismoRenglonSinPasarDeLoVendido() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Producto a = producto("PG devuelto A", 10, 30);
        Producto b = producto("PG devuelto B", 10, 30);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(b, 2), ventaService.crearDetalle(a, 5)),
                Venta.FormaPago.EFECTIVO, 1000);
        DetalleVenta da = v.getDetalles().stream().filter(d -> d.getProducto().getId().equals(a.getId())).findFirst().orElseThrow();
        DetalleVenta db = v.getDetalles().stream().filter(d -> d.getProducto().getId().equals(b.getId())).findFirst().orElseThrow();

        // 6 cajas piden 2 piezas de A (y 1 de B) cada una: de A solo caben 2 devoluciones (4 de 5 piezas), de B 2
        List<Runnable> acciones = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String ip = "10.0.7." + i;
            acciones.add(() -> sesionService.ejecutarComo(admin, ip, () -> devolucionService.devolver(
                    new DevolucionService.Solicitud(v.getId(), List.of(
                            new DevolucionService.Linea(da.getId(), 2, true),
                            new DevolucionService.Linea(db.getId(), 1, true)), "Caja " + ip), null)));
        }
        List<Throwable> errores = enParalelo(acciones);

        long hechas = errores.stream().filter(e -> e == null).count();
        assertThat(hechas).as("solo caben dos devoluciones").isEqualTo(2);
        assertThat(errores.stream().filter(e -> e != null)).allMatch(e -> e instanceof NegocioException);
        assertThat(stockActual(a)).isEqualTo(25 + 4);
        assertThat(stockActual(b)).isEqualTo(28 + 2);
        assertThat(productoService.movimientosDe(a.getId()).stream()
                .filter(m -> m.getTipo() == MovimientoInventario.Tipo.DEVOLUCION).count()).isEqualTo(2);
        Venta fin = ventaRepo.findConDetallesById(v.getId()).orElseThrow();
        assertThat(fin.getTotalDevueltoOCero()).isEqualTo(60.0);
        assertThat(devolucionRepo.findConDetallesByVentaId(v.getId())).hasSize(2);
        assertThat(fin.getDetalles()).allMatch(d -> d.getCantidadDevueltaOCero() <= d.getCantidad());
    }

    @Test
    void unaCajaCancelaMientrasOtrasDevuelvenYNoHayDobleEfecto() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Producto p = producto("PG devolver o cancelar", 10, 30);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 4)), Venta.FormaPago.EFECTIVO, 1000);
        DetalleVenta d = v.getDetalles().get(0);

        List<Runnable> acciones = new ArrayList<>();
        acciones.add(() -> sesionService.ejecutarComo(admin, "10.0.8.9", () -> ventaService.cancelar(v.getId(), "Cancela", null)));
        for (int i = 0; i < 3; i++) {
            String ip = "10.0.8." + i;
            acciones.add(() -> sesionService.ejecutarComo(admin, ip, () -> devolucionService.devolver(
                    new DevolucionService.Solicitud(v.getId(), List.of(new DevolucionService.Linea(d.getId(), 1, true)),
                            "Caja " + ip), null)));
        }
        enParalelo(acciones);

        Venta fin = ventaRepo.findById(v.getId()).orElseThrow();
        int devoluciones = devolucionRepo.findConDetallesByVentaId(v.getId()).size();
        if (fin.isCancelada()) {
            assertThat(devoluciones).isZero();
            assertThat(stockActual(p)).isEqualTo(30);
        } else {
            assertThat(devoluciones).isEqualTo(3);
            assertThat(stockActual(p)).isEqualTo(26 + 3);
        }
    }

    /** Las consultas netas de devoluciones (corte por tasa/departamento, Estadísticas, celular) corren en PostgreSQL */
    @Test
    void losReportesNetosDeDevolucionesFuncionanEnPostgres() throws Exception {
        iniciarComoAdmin();
        Departamento dep = new Departamento();
        dep.setNombre("PG netos " + sufijo());
        dep = productoService.guardarDepartamento(dep);
        Producto p = producto("PG neto " + sufijo(), 10, 30);       // costo 5
        p.setDepartamento(dep);
        p = productoService.guardar(p);
        LocalDate hoy = LocalDate.now();
        EstadisticasService.Periodo dia = EstadisticasService.Periodo.dia(hoy);
        double cajasAntes = estadisticasService.ventasPorCaja(dia).stream().mapToDouble(EstadisticasService.VentasPorCaja::total).sum();
        double cajeroAntes = estadisticasService.ventasPorCajero(dia).stream().mapToDouble(EstadisticasService.VentasCajero::total).sum();
        double horasAntes = estadisticasService.ventasPorHora(dia).stream().mapToDouble(EstadisticasService.VentasHora::total).sum();
        double hoyAntes = estadisticasService.resumenHoy(LocalDateTime.now()).cifras().total();
        double gananciaAntes = consultasMovilService.estadisticas(dia, "Hoy", true).ganancia();
        double movilAntes = consultasMovilService.ventas(hoy, hoy, 0, 1).importe();

        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 4)), Venta.FormaPago.EFECTIVO, 100);
        devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(v.getDetalles().get(0).getId(), 1, true)), "PG"), null);

        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        assertThat(r.porDepartamento.get(dep.getNombre())).isCloseTo(30.0, within(0.001));
        assertThat(r.devueltoDeDepartamento(dep.getNombre())).isCloseTo(10.0, within(0.001));
        assertThat(r.devolucionesPorTasaIva).isNotEmpty();
        assertThat(estadisticasService.ventasPorCaja(dia).stream().mapToDouble(EstadisticasService.VentasPorCaja::total).sum()
                - cajasAntes).isCloseTo(30.0, within(0.001));
        assertThat(estadisticasService.ventasPorCajero(dia).stream().mapToDouble(EstadisticasService.VentasCajero::total).sum()
                - cajeroAntes).isCloseTo(30.0, within(0.001));
        assertThat(estadisticasService.ventasPorHora(dia).stream().mapToDouble(EstadisticasService.VentasHora::total).sum()
                - horasAntes).isCloseTo(30.0, within(0.001));
        assertThat(estadisticasService.resumenHoy(LocalDateTime.now()).cifras().total() - hoyAntes).isCloseTo(30.0, within(0.001));
        assertThat(consultasMovilService.estadisticas(dia, "Hoy", true).ganancia() - gananciaAntes).isCloseTo(15.0, within(0.001));
        assertThat(consultasMovilService.ventas(hoy, hoy, 0, 1).importe() - movilAntes).isCloseTo(30.0, within(0.001));
    }
}
