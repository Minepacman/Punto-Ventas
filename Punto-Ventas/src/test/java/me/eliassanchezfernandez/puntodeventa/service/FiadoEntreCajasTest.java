package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ClienteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Dos cajas le fían al MISMO cliente al mismo tiempo, SIN transacción envolvente (como en la
 * tienda): el límite no se rebasa dos veces y el saldo es la suma del libro. Dos cajas reciben a la
 * vez el abono que liquida la deuda: solo una lo cobra. Con PostgreSQL real lo prueba
 * FiadoPostgresTest.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FiadoEntreCajasTest {

    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private ProductoService productoService;
    @Autowired private VentaService ventaService;
    @Autowired private CuentaClienteService cuentaService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ClienteRepository clienteRepo;
    @Autowired private ProductoRepository productoRepo;

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

    private Cajero cajeroQueFia() {
        Cajero c = cajeroService.crear("fia" + UUID.randomUUID().toString().substring(0, 6), "Cajero fiado", "clave123",
                Cajero.Rol.CAJERO, EnumSet.of(Permiso.VENDER_A_CREDITO));
        c.setDebeCambiarContrasena(false);
        return cajeroRepo.save(c);
    }

    private Producto producto(double precio, double stock) {
        Producto p = new Producto();
        p.setCodigoBarras("FI-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion("Fiado entre cajas");
        p.setPrecioCosto(precio / 2);
        p.setPrecioVenta(precio);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    @Test
    void dosCajasFianAlMismoClienteYElLimiteNoSeRebasa() throws Exception {
        entrarComoAdmin();
        Cajero cajero = cajeroQueFia();
        Cliente c = cuentaService.guardar(null, "Cliente compartido " + UUID.randomUUID().toString().substring(0, 5),
                null, null, 100, true, null);
        Producto p = producto(60, 10);

        Runnable vender = () -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(PagoVenta.aCuenta(c.getId(), 60)), null);
        List<Throwable> errores = DobleEfectoEntreCajasTest.enParalelo(List.of(
                () -> sesionService.ejecutarComo(cajero, "10.0.0.1", vender),
                () -> sesionService.ejecutarComo(cajero, "10.0.0.2", vender)));

        assertThat(errores.stream().filter(e -> e == null).count()).as("solo una venta cabe en el límite").isEqualTo(1);
        Throwable fallo = errores.stream().filter(e -> e != null).findFirst().orElseThrow();
        assertThat(fallo).isInstanceOf(PermisoRequeridoException.class).hasMessageContaining("límite");
        assertThat(clienteRepo.saldoDe(c.getId())).isCloseTo(60, within(0.001));
        assertThat(cuentaService.saldoSegunLibro(c.getId())).isCloseTo(60, within(0.001));
        assertThat(productoRepo.findById(p.getId()).orElseThrow().getCantidadActual()).as("la venta rechazada no descontó").isEqualTo(9);
    }

    @Test
    void variasCajasFianSinLimiteYNingunCargoSePierde() throws Exception {
        entrarComoAdmin();
        Cajero cajero = cajeroQueFia();
        Cliente c = cuentaService.guardar(null, "Sin límite " + UUID.randomUUID().toString().substring(0, 5),
                null, null, 0, true, null);
        Producto p = producto(25, 50);
        Runnable vender = () -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(PagoVenta.aCuenta(c.getId(), 25)), null);
        List<Throwable> errores = DobleEfectoEntreCajasTest.enParalelo(List.of(
                () -> sesionService.ejecutarComo(cajero, "10.0.0.1", vender),
                () -> sesionService.ejecutarComo(cajero, "10.0.0.2", vender),
                () -> sesionService.ejecutarComo(cajero, "10.0.0.3", vender),
                () -> sesionService.ejecutarComo(cajero, "10.0.0.4", vender)));
        assertThat(errores).containsOnlyNulls();
        assertThat(clienteRepo.saldoDe(c.getId())).isCloseTo(100, within(0.001));
        assertThat(cuentaService.saldoSegunLibro(c.getId())).isCloseTo(100, within(0.001));
    }

    @Test
    void dosCajasCobranElMismoAbonoYSoloUnaLoRegistra() throws Exception {
        entrarComoAdmin();
        Cajero cajero = cajeroQueFia();
        Cliente c = cuentaService.guardar(null, "Liquida " + UUID.randomUUID().toString().substring(0, 5),
                null, null, 0, true, null);
        Producto p = producto(80, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(PagoVenta.aCuenta(c.getId(), 80)), null);
        assertThat(v.getSaldoCliente()).isCloseTo(80, within(0.001));

        Runnable abonar = () -> cuentaService.registrarAbono(c.getId(), 80, Venta.FormaPago.EFECTIVO, null);
        List<Throwable> errores = DobleEfectoEntreCajasTest.enParalelo(List.of(
                () -> sesionService.ejecutarComo(cajero, "10.0.0.1", abonar),
                () -> sesionService.ejecutarComo(cajero, "10.0.0.2", abonar)));
        assertThat(errores.stream().filter(e -> e == null).count()).isEqualTo(1);
        assertThat(clienteRepo.saldoDe(c.getId())).isCloseTo(0, within(0.001));
        assertThat(cuentaService.saldoSegunLibro(c.getId())).isCloseTo(0, within(0.001));
    }
}
