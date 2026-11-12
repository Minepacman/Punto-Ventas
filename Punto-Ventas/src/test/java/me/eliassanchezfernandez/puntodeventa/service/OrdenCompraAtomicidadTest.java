package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.OrdenCompraRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaOrden;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recibir una orden es todo o nada. SIN transacción envolvente: cada operación se
 * confirma de verdad en la BD desechable, así se comprueba el rollback real del servicio.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrdenCompraAtomicidadTest {

    @Autowired private SesionService sesionService;
    @Autowired private ProductoService productoService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private OrdenCompraRepository ordenRepo;

    @AfterEach
    void cerrar() {
        sesionService.cerrarSesion();
    }

    private Producto producto(String nombre, double stock) {
        Producto p = new Producto();
        p.setCodigoBarras("AT-" + UUID.randomUUID().toString().substring(0, 8));
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

    private long entradasDe(Producto p) {
        return productoService.movimientosDe(p.getId()).stream()
                .filter(m -> m.getTipo() == MovimientoInventario.Tipo.ENTRADA).count();
    }

    @Test
    void siUnaLineaFallaNoSeAplicaNingunaYLaOrdenSigueIgual() throws Exception {
        var admin = cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);
        sesionService.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);

        Proveedor prov = proveedorService.guardar(null, "Atómico " + UUID.randomUUID().toString().substring(0, 5),
                null, null, null, null, null);
        Producto uno = producto("Uno", 3);
        Producto dos = producto("Dos", 3);
        Producto tres = producto("Tres", 3);
        OrdenCompra orden = ordenCompraService.crear(prov.getId(), List.of(
                new LineaOrden(uno.getId(), 5, 7), new LineaOrden(dos.getId(), 5, 7), new LineaOrden(tres.getId(), 5, 7)), null);
        ordenCompraService.marcarEnviada(orden.getId());

        // Entre la captura y la recepción, "Dos" deja de manejar inventario: su línea ya no puede entrar
        Producto d = productoRepo.findById(dos.getId()).orElseThrow();
        d.setUsaInventario(false);
        productoRepo.save(d);

        assertThatThrownBy(() -> ordenCompraService.recibir(orden.getId(), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no se aplicó ninguna línea").hasMessageContaining("Dos");

        // La primera línea (que sí podía entrar) se revirtió junto con todo lo demás
        assertThat(stock(uno)).isEqualTo(3);
        assertThat(stock(tres)).isEqualTo(3);
        assertThat(entradasDe(uno)).isZero();
        assertThat(entradasDe(tres)).isZero();
        assertThat(productoRepo.findById(uno.getId()).orElseThrow().getPrecioCosto()).isEqualTo(5);   // el costo tampoco cambió
        OrdenCompra sigue = ordenRepo.findById(orden.getId()).orElseThrow();
        assertThat(sigue.getEstado()).isEqualTo(OrdenCompra.Estado.ENVIADA);
        assertThat(sigue.getFechaRecepcion()).isNull();

        // Corregido el problema, la misma orden se recibe completa
        d.setUsaInventario(true);
        productoRepo.save(d);
        assertThat(ordenCompraService.recibir(orden.getId(), null).getEstado()).isEqualTo(OrdenCompra.Estado.RECIBIDA);
        assertThat(stock(uno)).isEqualTo(8);
        assertThat(stock(dos)).isEqualTo(8);
        assertThat(stock(tres)).isEqualTo(8);
        assertThat(entradasDe(uno)).isEqualTo(1);
    }
}
