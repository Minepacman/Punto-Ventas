package me.eliassanchezfernandez.puntodeventa.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompraLinea;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.OrdenCompraRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaOrden;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El caso que solo se da con varias cajas: esta caja leyó la venta (u orden) cuando seguía activa
 * y, antes de escribir, otra caja la canceló (o recibió). Se reproduce sin hilos: la entidad ya
 * cargada en el contexto de persistencia es la "lectura vieja" y el UPDATE directo es la otra
 * caja. El UPDATE condicionado afecta 0 filas y nada más ocurre. También: el orden de bloqueo de
 * productos y la caja bloqueada tras migrar al servidor.
 */
class DobleEfectoCondicionadoTest extends BaseServicioTest {

    @Autowired private VentaRepository ventaRepo;
    @Autowired private OrdenCompraRepository ordenRepo;
    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private RutasApp rutas;
    @PersistenceContext private EntityManager em;

    private long movimientos(Producto p, MovimientoInventario.Tipo tipo) {
        return productoService.movimientosDe(p.getId()).stream().filter(m -> m.getTipo() == tipo).count();
    }

    private double existencia(Producto p) {
        em.clear();
        return stockActual(p);
    }

    private OrdenCompra ordenEnviada(Producto... productos) {
        Proveedor prov = proveedorService.guardar(null, "Cond " + UUID.randomUUID().toString().substring(0, 5),
                null, null, null, null, null);
        List<LineaOrden> lineas = new ArrayList<>();
        for (Producto p : productos) lineas.add(new LineaOrden(p.getId(), 5, 7));
        OrdenCompra o = ordenCompraService.crear(prov.getId(), lineas, null);
        return ordenCompraService.marcarEnviada(o.getId());
    }

    @Test
    void siOtraCajaYaCancelóLaVentaNoSeDevuelveMercanciaOtraVez() throws Exception {
        var admin = iniciarComoAdmin();
        Producto p = producto("Leída activa", 10, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 4)), Venta.FormaPago.EFECTIVO, 100);
        assertThat(v.isCancelada()).isFalse();   // esta caja la tiene cargada "activa"

        // Otra caja la cancela y regresa la mercancía por su lado
        assertThat(ventaRepo.marcarCancelada(v.getId(), LocalDateTime.now(), "Otra caja", admin.getId(),
                admin.getNombreCompleto(), null, null)).isEqualTo(1);

        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "Esta caja", null))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("Esta venta ya fue cancelada en otra caja");
        assertThat(movimientos(p, MovimientoInventario.Tipo.CANCELACION)).isZero();
        assertThat(existencia(p)).isEqualTo(6);   // la mercancía NO regresó por esta caja
        Venta enBase = ventaRepo.findById(v.getId()).orElseThrow();
        assertThat(enBase.getMotivoCancelacion()).isEqualTo("Otra caja");   // no se pisó la cancelación ajena
    }

    @Test
    void laMarcaCondicionadaSoloAfectaUnaVez() throws Exception {
        var admin = iniciarComoAdmin();
        Producto p = producto("Marca única", 10, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
        assertThat(ventaRepo.marcarCancelada(v.getId(), LocalDateTime.now(), "1", admin.getId(), "a", null, null)).isEqualTo(1);
        assertThat(ventaRepo.marcarCancelada(v.getId(), LocalDateTime.now(), "2", admin.getId(), "a", null, null)).isZero();
    }

    @Test
    void siOtraCajaYaRecibióLaOrdenNoEntraMercanciaOtraVez() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Orden leída enviada", 10, 2);
        OrdenCompra o = ordenEnviada(p);
        assertThat(o.getEstado()).isEqualTo(OrdenCompra.Estado.ENVIADA);

        assertThat(ordenRepo.marcarRecibida(o.getId(), OrdenCompra.Estado.RECIBIDA, LocalDateTime.now(), "Otra caja",
                List.of(OrdenCompra.Estado.BORRADOR, OrdenCompra.Estado.ENVIADA))).isEqualTo(1);

        assertThatThrownBy(() -> ordenCompraService.recibir(o.getId(), null))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("ya fue recibida en otra caja");
        assertThat(movimientos(p, MovimientoInventario.Tipo.ENTRADA)).isZero();
        assertThat(existencia(p)).isEqualTo(2);
    }

    @Test
    void siOtraCajaYaCancelóLaOrdenNoSeRecibe() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Orden cancelada fuera", 10, 2);
        OrdenCompra o = ordenEnviada(p);
        assertThat(ordenRepo.cambiarEstado(o.getId(), OrdenCompra.Estado.CANCELADA,
                List.of(OrdenCompra.Estado.BORRADOR, OrdenCompra.Estado.ENVIADA))).isEqualTo(1);

        assertThatThrownBy(() -> ordenCompraService.recibir(o.getId(), null))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("ya fue cancelada en otra caja");
        assertThat(existencia(p)).isEqualTo(2);
    }

    @Test
    void cancelarUnaOrdenQueOtraCajaYaRecibióSeRehúsa() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Orden recibida fuera", 10, 2);
        OrdenCompra o = ordenEnviada(p);
        ordenRepo.marcarRecibida(o.getId(), OrdenCompra.Estado.RECIBIDA, LocalDateTime.now(), "Otra caja",
                List.of(OrdenCompra.Estado.ENVIADA));

        assertThatThrownBy(() -> ordenCompraService.cancelar(o.getId()))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("otra caja");
        em.clear();
        assertThat(ordenRepo.findById(o.getId()).orElseThrow().getEstado()).isEqualTo(OrdenCompra.Estado.RECIBIDA);
    }

    // ── Orden de bloqueo ──────────────────────────────────────────────────

    private static Producto conId(long id) {
        Producto p = new Producto();
        p.setId(id);
        return p;
    }

    @Test
    void lasCantidadesDeUnaVentaSeRecorrenPorIdDeProducto() {
        List<DetalleVenta> detalles = new ArrayList<>();
        for (long id : new long[]{42, 7, 19, 7, 3, 42}) {
            DetalleVenta d = new DetalleVenta();
            d.setProducto(conId(id));
            d.setCantidad(id == 7 ? 2 : 1);
            detalles.add(d);
        }
        var porProducto = VentaService.cantidadesPorProducto(detalles);
        assertThat(porProducto.keySet()).containsExactly(3L, 7L, 19L, 42L);
        assertThat(porProducto.get(7L)).isEqualTo(4);    // repetidos sumados
        assertThat(porProducto.get(42L)).isEqualTo(2);
    }

    @Test
    void lasLineasDeUnaOrdenSeRecibenPorIdDeProducto() {
        List<OrdenCompraLinea> lineas = new ArrayList<>();
        for (long id : new long[]{30, 4, 12, 4}) {
            OrdenCompraLinea l = new OrdenCompraLinea();
            l.setProducto(conId(id));
            lineas.add(l);
        }
        assertThat(OrdenCompraService.lineasEnOrdenDeBloqueo(lineas))
                .extracting(l -> l.getProducto().getId()).containsExactly(4L, 4L, 12L, 30L);
        assertThat(lineas).extracting(l -> l.getProducto().getId()).containsExactly(30L, 4L, 12L, 4L);   // sin tocar la original
    }

    // ── Caja migrada al servidor ──────────────────────────────────────────

    @Test
    void trasMigrarAlServidorLaCajaYaNoCobraNiMueveInventario() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Migrado", 10, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
        OrdenCompra o = ordenEnviada(p);
        rutas.marcarMigradaAlServidor("servidor:5432/caja");
        try {
            assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("servidor:5432/caja");
            assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "x", null))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("vuelve a abrir");
            assertThatThrownBy(() -> ordenCompraService.recibir(o.getId(), null))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("vuelve a abrir");
            assertThatThrownBy(() -> productoService.ajustarStock(p.getId(), 3, "conteo", null))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("vuelve a abrir");
            assertThatThrownBy(() -> productoService.registrarEntrada(p.getId(), 3, "Surtidor", 0, null))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("vuelve a abrir");
        } finally {
            rutas.marcarMigradaAlServidor(null);   // el bean es compartido por todas las pruebas
        }
        assertThat(existencia(p)).isEqualTo(9);
    }
}
