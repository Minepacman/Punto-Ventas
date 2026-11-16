package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.DevolucionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos cajas devuelven el mismo renglón a la vez (o una devuelve mientras otra cancela), SIN
 * transacción envolvente contra la BD SQLite desechable, cada hilo como una caja con su cajero:
 * entre las dos nunca se devuelve más de lo vendido y la mercancía y el dinero salen una vez.
 * Con hilos de verdad simultáneos se prueba también en PostgreSQL (DevolucionPostgresTest).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DevolucionEntreCajasTest {

    @Autowired private SesionService sesionService;
    @Autowired private ProductoService productoService;
    @Autowired private VentaService ventaService;
    @Autowired private DevolucionService devolucionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private DevolucionRepository devolucionRepo;

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
        p.setCodigoBarras("DV-" + UUID.randomUUID().toString().substring(0, 8));
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

    private Runnable devolver(Cajero c, String ip, Venta v, DetalleVenta d, double cantidad) {
        return () -> sesionService.ejecutarComo(c, ip, () -> devolucionService.devolver(new DevolucionService.Solicitud(
                v.getId(), List.of(new DevolucionService.Linea(d.getId(), cantidad, true)), "Caja " + ip), null));
    }

    @Test
    void dosCajasDevuelvenElMismoRenglonYNoPasanDeLoVendido() throws Exception {
        Cajero admin = entrarComoAdmin();
        Producto p = producto("Devuelto en dos cajas", 20);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 3)), Venta.FormaPago.EFECTIVO, 100);
        DetalleVenta d = v.getDetalles().get(0);
        assertThat(stock(p)).isEqualTo(17);

        List<Throwable> errores = DobleEfectoEntreCajasTest.enParalelo(List.of(
                devolver(admin, "10.0.5.1", v, d, 2), devolver(admin, "10.0.5.2", v, d, 2)));

        assertThat(errores.stream().filter(e -> e == null).count()).as("solo una caja devuelve").isEqualTo(1);
        assertThat(errores.stream().filter(e -> e != null)).allMatch(e -> e instanceof NegocioException);
        assertThat(stock(p)).isEqualTo(19);
        assertThat(productoService.movimientosDe(p.getId()).stream()
                .filter(m -> m.getTipo() == MovimientoInventario.Tipo.DEVOLUCION).count()).isEqualTo(1);
        Venta despues = ventaRepo.findConDetallesById(v.getId()).orElseThrow();
        assertThat(despues.getDetalles().get(0).getCantidadDevueltaOCero()).isEqualTo(2.0);
        assertThat(despues.getTotalDevueltoOCero()).isEqualTo(20.0);
        assertThat(devolucionRepo.findConDetallesByVentaId(v.getId())).hasSize(1);
    }

    @Test
    void unaCajaDevuelveMientrasOtraCancelaYSoloGanaUna() throws Exception {
        Cajero admin = entrarComoAdmin();
        Producto p = producto("Devolver o cancelar", 20);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 3)), Venta.FormaPago.EFECTIVO, 100);
        DetalleVenta d = v.getDetalles().get(0);

        List<Throwable> errores = DobleEfectoEntreCajasTest.enParalelo(List.of(
                devolver(admin, "10.0.6.1", v, d, 1),
                () -> sesionService.ejecutarComo(admin, "10.0.6.2", () -> ventaService.cancelar(v.getId(), "Caja 2", null))));

        assertThat(errores.stream().filter(e -> e == null).count()).isEqualTo(1);
        Venta fin = ventaRepo.findById(v.getId()).orElseThrow();
        if (fin.isCancelada()) {
            assertThat(stock(p)).isEqualTo(20);            // regresó todo una vez
            assertThat(devolucionRepo.findConDetallesByVentaId(v.getId())).isEmpty();
        } else {
            assertThat(stock(p)).isEqualTo(18);            // 17 + 1 devuelto
            assertThat(fin.getTotalDevueltoOCero()).isEqualTo(10.0);
        }
    }
}
