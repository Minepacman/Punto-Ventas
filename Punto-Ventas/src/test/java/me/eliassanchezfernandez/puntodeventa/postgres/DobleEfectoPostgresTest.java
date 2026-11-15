package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.OrdenCompraRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaOrden;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doble efecto e interbloqueos entre cajas contra PostgreSQL real (aquí las dos transacciones SÍ
 * corren a la vez, no se serializan como en SQLite):
 *  - dos cajas cancelan la misma venta / reciben la misma orden: el UPDATE condicionado deja pasar
 *    a una; la otra espera la fila, ve 0 afectadas y no repite el efecto;
 *  - muchas ventas con los mismos productos en órdenes distintos no se interbloquean (las filas se
 *    toman siempre por id de producto ascendente).
 */
class DobleEfectoPostgresTest extends BasePostgresTest {

    @Autowired private VentaRepository ventaRepo;
    @Autowired private OrdenCompraRepository ordenRepo;
    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private ProveedorService proveedorService;

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

    private long movimientos(Producto p, MovimientoInventario.Tipo tipo) {
        return productoService.movimientosDe(p.getId()).stream().filter(m -> m.getTipo() == tipo).count();
    }

    @Test
    void variasCajasCancelanLaMismaVentaYSoloUnaDevuelveLaMercancia() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Producto a = producto("PG cancelada A", 10, 30);
        Producto b = producto("PG cancelada B", 10, 30);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(b, 4), ventaService.crearDetalle(a, 6)),
                Venta.FormaPago.EFECTIVO, 1000);

        List<Runnable> acciones = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String ip = "10.0.1." + i;
            acciones.add(() -> sesionService.ejecutarComo(admin, ip, () -> ventaService.cancelar(v.getId(), "Caja " + ip, null)));
        }
        List<Throwable> errores = enParalelo(acciones);

        assertThat(errores.stream().filter(e -> e == null).count()).as("solo una caja cancela").isEqualTo(1);
        assertThat(errores.stream().filter(e -> e != null))
                .allMatch(e -> e instanceof NegocioException && e.getMessage().contains("cancelada"));
        assertThat(stockActual(a)).isEqualTo(30);
        assertThat(stockActual(b)).isEqualTo(30);
        assertThat(movimientos(a, MovimientoInventario.Tipo.CANCELACION)).isEqualTo(1);
        assertThat(movimientos(b, MovimientoInventario.Tipo.CANCELACION)).isEqualTo(1);
        assertThat(ventaRepo.findById(v.getId()).orElseThrow().isCancelada()).isTrue();
    }

    @Test
    void variasCajasRecibenLaMismaOrdenYSoloEntraUnaVez() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Proveedor prov = proveedorService.guardar(null, "PG doble " + sufijo(), null, null, null, null, null);
        Producto uno = producto("PG orden uno", 10, 1);
        Producto dos = producto("PG orden dos", 10, 1);
        OrdenCompra orden = ordenCompraService.crear(prov.getId(), List.of(
                new LineaOrden(dos.getId(), 5, 4), new LineaOrden(uno.getId(), 3, 4)), null);
        ordenCompraService.marcarEnviada(orden.getId());

        List<Runnable> acciones = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String ip = "10.0.2." + i;
            acciones.add(() -> sesionService.ejecutarComo(admin, ip, () -> ordenCompraService.recibir(orden.getId(), null)));
        }
        List<Throwable> errores = enParalelo(acciones);

        assertThat(errores.stream().filter(e -> e == null).count()).as("solo una caja recibe").isEqualTo(1);
        assertThat(errores.stream().filter(e -> e != null))
                .allMatch(e -> e instanceof NegocioException && e.getMessage().contains("recibida"));
        assertThat(stockActual(uno)).isEqualTo(4);
        assertThat(stockActual(dos)).isEqualTo(6);
        assertThat(movimientos(uno, MovimientoInventario.Tipo.ENTRADA)).isEqualTo(1);
        assertThat(movimientos(dos, MovimientoInventario.Tipo.ENTRADA)).isEqualTo(1);
        assertThat(ordenRepo.findById(orden.getId()).orElseThrow().getEstado()).isEqualTo(OrdenCompra.Estado.RECIBIDA);
    }

    @Test
    void ventasConLosMismosProductosEnOrdenInversoNoSeInterbloquean() throws Exception {
        iniciarComoAdmin();
        List<Producto> productos = new ArrayList<>();
        for (int i = 0; i < 5; i++) productos.add(producto("PG cruce " + i, 10, 1000));
        List<Cajero> cajeros = new ArrayList<>();
        for (int i = 0; i < 6; i++) cajeros.add(crearCajero("cruce" + i + "-" + sufijo(), EnumSet.noneOf(Permiso.class)));

        List<Runnable> acciones = new ArrayList<>();
        for (int i = 0; i < cajeros.size(); i++) {
            Cajero c = cajeros.get(i);
            List<Producto> orden = new ArrayList<>(productos);
            if (i % 2 == 1) java.util.Collections.reverse(orden);   // la mitad de las cajas escanea al revés
            for (int j = 0; j < 4; j++) {
                acciones.add(() -> sesionService.ejecutarComo(c, "10.0.3.1", () -> ventaService.cobrar(
                        orden.stream().map(p -> ventaService.crearDetalle(p, 1, true)).toList(),
                        Venta.FormaPago.EFECTIVO, 1000)));
            }
        }
        List<Throwable> errores = enParalelo(acciones);
        assertThat(errores).as("ningún interbloqueo ni fallo").allMatch(e -> e == null);
        for (Producto p : productos) assertThat(stockActual(p)).isEqualTo(1000 - 24);
    }
}
