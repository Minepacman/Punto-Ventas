package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.OrdenCompraRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaOrden;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos cajas cancelan la misma venta (o reciben la misma orden) al mismo tiempo: solo una lo
 * logra y la mercancía regresa (o entra) UNA vez. SIN transacción envolvente, contra la BD
 * SQLite desechable, como en la tienda: cada hilo es una caja con su propio cajero
 * ({@code ejecutarComo}). En SQLite las escrituras se serializan con BEGIN IMMEDIATE (la segunda
 * caja ve el estado ya cambiado al leer); el caso en que las dos leen "activa" y la segunda choca
 * con el UPDATE condicionado se prueba de forma determinista en {@link DobleEfectoCondicionadoTest}
 * y con hilos reales en el perfil PostgreSQL (DobleEfectoPostgresTest).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DobleEfectoEntreCajasTest {

    @Autowired private SesionService sesionService;
    @Autowired private ProductoService productoService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private VentaService ventaService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private OrdenCompraRepository ordenRepo;

    @AfterEach
    void cerrar() {
        sesionService.cerrarSesion();
    }

    private Cajero entrarComoAdmin() throws Exception {
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);
        sesionService.cerrarSesion();
        return sesionService.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);
    }

    private Producto producto(String nombre, double stock) {
        Producto p = new Producto();
        p.setCodigoBarras("DE-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioCosto(5);
        p.setPrecioVenta(10);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    private double stock(Producto p) {
        return productoRepo.findById(p.getId()).orElseThrow().getCantidadActual();
    }

    private long movimientos(Producto p, MovimientoInventario.Tipo tipo) {
        return productoService.movimientosDe(p.getId()).stream().filter(m -> m.getTipo() == tipo).count();
    }

    /** Lanza las acciones a la vez; devuelve el error de cada una (null = se hizo) */
    static List<Throwable> enParalelo(List<Runnable> acciones) throws Exception {
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
    void dosCajasCancelanLaMismaVentaYLaMercanciaRegresaUnaSolaVez() throws Exception {
        Cajero admin = entrarComoAdmin();
        Producto a = producto("Cancelada A", 20);
        Producto b = producto("Cancelada B", 20);
        // Renglones en orden "desordenado" (b antes que a) y un producto repetido
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(b, 2), ventaService.crearDetalle(a, 3),
                ventaService.crearDetalle(b, 1)), Venta.FormaPago.EFECTIVO, 500);
        assertThat(stock(a)).isEqualTo(17);
        assertThat(stock(b)).isEqualTo(17);

        List<Throwable> errores = enParalelo(List.of(
                () -> sesionService.ejecutarComo(admin, "10.0.0.1", () -> ventaService.cancelar(v.getId(), "Caja 1", null)),
                () -> sesionService.ejecutarComo(admin, "10.0.0.2", () -> ventaService.cancelar(v.getId(), "Caja 2", null))));

        assertThat(errores.stream().filter(e -> e == null).count()).as("solo una caja cancela").isEqualTo(1);
        Throwable fallo = errores.stream().filter(e -> e != null).findFirst().orElseThrow();
        assertThat(fallo).isInstanceOf(NegocioException.class).hasMessageContaining("cancelada");
        assertThat(stock(a)).isEqualTo(20);   // regresó una vez, no 23
        assertThat(stock(b)).isEqualTo(20);
        assertThat(movimientos(a, MovimientoInventario.Tipo.CANCELACION)).isEqualTo(1);
        assertThat(movimientos(b, MovimientoInventario.Tipo.CANCELACION)).isEqualTo(1);
        assertThat(ventaRepo.findById(v.getId()).orElseThrow().isCancelada()).isTrue();
    }

    @Test
    void dosCajasRecibenLaMismaOrdenYLaMercanciaEntraUnaSolaVez() throws Exception {
        Cajero admin = entrarComoAdmin();
        Proveedor prov = proveedorService.guardar(null, "Doble " + UUID.randomUUID().toString().substring(0, 5),
                null, null, null, null, null);
        Producto uno = producto("Orden uno", 3);
        Producto dos = producto("Orden dos", 3);
        OrdenCompra orden = ordenCompraService.crear(prov.getId(), List.of(
                new LineaOrden(dos.getId(), 5, 7), new LineaOrden(uno.getId(), 4, 7)), null);
        ordenCompraService.marcarEnviada(orden.getId());

        List<Throwable> errores = enParalelo(List.of(
                () -> sesionService.ejecutarComo(admin, "10.0.0.1", () -> ordenCompraService.recibir(orden.getId(), null)),
                () -> sesionService.ejecutarComo(admin, "10.0.0.2", () -> ordenCompraService.recibir(orden.getId(), null))));

        assertThat(errores.stream().filter(e -> e == null).count()).as("solo una caja recibe").isEqualTo(1);
        Throwable fallo = errores.stream().filter(e -> e != null).findFirst().orElseThrow();
        assertThat(fallo).isInstanceOf(NegocioException.class).hasMessageContaining("recibida");
        assertThat(stock(uno)).isEqualTo(7);    // 3 + 4, una vez
        assertThat(stock(dos)).isEqualTo(8);    // 3 + 5, una vez
        assertThat(movimientos(uno, MovimientoInventario.Tipo.ENTRADA)).isEqualTo(1);
        assertThat(movimientos(dos, MovimientoInventario.Tipo.ENTRADA)).isEqualTo(1);
        assertThat(ordenRepo.findById(orden.getId()).orElseThrow().getEstado()).isEqualTo(OrdenCompra.Estado.RECIBIDA);
    }

    @Test
    void unaCajaCancelaLaOrdenMientrasOtraLaRecibe() throws Exception {
        Cajero admin = entrarComoAdmin();
        Proveedor prov = proveedorService.guardar(null, "Carrera " + UUID.randomUUID().toString().substring(0, 5),
                null, null, null, null, null);
        Producto p = producto("En disputa", 1);
        OrdenCompra orden = ordenCompraService.crear(prov.getId(), List.of(new LineaOrden(p.getId(), 6, 7)), null);
        ordenCompraService.marcarEnviada(orden.getId());

        List<Throwable> errores = enParalelo(List.of(
                () -> sesionService.ejecutarComo(admin, "10.0.0.1", () -> ordenCompraService.recibir(orden.getId(), null)),
                () -> sesionService.ejecutarComo(admin, "10.0.0.2", () -> ordenCompraService.cancelar(orden.getId()))));

        assertThat(errores.stream().filter(e -> e == null).count()).isEqualTo(1);
        OrdenCompra.Estado fin = ordenRepo.findById(orden.getId()).orElseThrow().getEstado();
        // Gane quien gane, el inventario coincide con el estado final
        if (fin == OrdenCompra.Estado.RECIBIDA) {
            assertThat(stock(p)).isEqualTo(7);
            assertThat(movimientos(p, MovimientoInventario.Tipo.ENTRADA)).isEqualTo(1);
        } else {
            assertThat(fin).isEqualTo(OrdenCompra.Estado.CANCELADA);
            assertThat(stock(p)).isEqualTo(1);
            assertThat(movimientos(p, MovimientoInventario.Tipo.ENTRADA)).isZero();
        }
    }
}
