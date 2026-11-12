package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.PromocionRepository;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaOrden;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA v1.0.0 — defectos (ya corregidos) en catálogos, inventario, promociones y
 * órdenes de compra. Cada prueba describe el defecto que reproducía.
 */
class QaCatalogosYComprasTest extends BaseServicioTest {

    @Autowired private OrdenCompraService  ordenCompraService;
    @Autowired private ProveedorService    proveedorService;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private PromocionRepository promocionRepo;
    @Autowired private AuditoriaRepository auditoriaRepo;

    private Proveedor bimbo;

    @BeforeEach
    void iniciar() throws Exception {
        iniciarComoAdmin();
        bimbo = proveedorService.guardar(null, "Bimbo QA", null, "55 1111", "Don Luis", Set.of(), null);
    }

    private Producto conMinimo(String nombre, double stock, double minimo) {
        Producto p = producto(nombre, 20, stock);
        p.setCantidadMinima(minimo);
        return productoRepo.save(p);
    }

    private Autorizacion autorizacionAdmin() {
        return autorizacionService.autorizar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA, Permiso.AJUSTAR_INVENTARIO);
    }

    /**
     * A-1 (alto). Recibir una orden de DOS o más líneas con autorización de
     * administrador fallaba: la primera línea consumía la autorización y la
     * segunda ya no la encontraba vigente. Ahora una sola autorización cubre la
     * recepción completa, se consume UNA vez al terminar y la bitácora de la
     * orden lleva al autorizador.
     */
    @Test
    void recibirUnaOrdenDeVariasLineasConAutorizacionDebeAplicarTodasLasLineas() throws Exception {
        Producto pan = conMinimo("Pan", 2, 5);
        Producto leche = conMinimo("Leche", 3, 5);
        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(
                new LineaOrden(pan.getId(), 10, 12), new LineaOrden(leche.getId(), 6, 20)), null);

        iniciarComoCajero("recibidorQA", EnumSet.of(Permiso.GESTIONAR_PROVEEDORES));
        Autorizacion aut = autorizacionAdmin();

        assertThatCode(() -> ordenCompraService.recibir(o.getId(), List.of(aut)))
                .as("una sola autorización debe cubrir la recepción completa de la orden")
                .doesNotThrowAnyException();
        assertThat(stockActual(pan)).isEqualTo(12);
        assertThat(stockActual(leche)).isEqualTo(9);
        assertThat(aut.isConsumida()).as("de un solo uso: se consume al terminar la operación completa").isTrue();
        assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("ORDEN_COMPRA_RECIBIDA"))
                .extracting(Auditoria::getAutorizadoPor).first().isNotNull();

        // Ya consumida, no sirve para otra orden
        OrdenCompra otra;
        iniciarComoAdmin();
        otra = ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 1, 12)), null);
        iniciarComo("recibidorQA", CLAVE_PRUEBA);
        assertThatThrownBy(() -> ordenCompraService.recibir(otra.getId(), List.of(aut)))
                .isInstanceOf(PermisoRequeridoException.class);
    }

    /**
     * M-4 (medio, varias cajas). Editar una promoción guardaba el objeto completo
     * (merge) con el contador "piezas usadas" que tenía la pantalla al abrirse:
     * las ventas que otra caja registró entre tanto se perdían. Ahora el
     * contador se conserva de la base.
     */
    @Test
    void editarUnaPromocionNoDebeReiniciarLasPiezasUsadas() {
        Producto p = producto("Refresco", 20, 50);
        Promocion promo = promocion(p, Promocion.Tipo.PORCENTAJE, 10);
        promo.setLimitePiezas(20);
        promo = promocionService.guardar(promo);
        promocionService.registrarUso(promo.getId(), 5);   // una venta en otra caja

        Promocion copiaVieja = new Promocion();            // lo que arma PromocionesController al guardar
        copiaVieja.setId(promo.getId());
        copiaVieja.setProducto(p);
        copiaVieja.setTipo(Promocion.Tipo.PORCENTAJE);
        copiaVieja.setValor(10);
        copiaVieja.setFechaInicio(LocalDate.now().minusDays(1));
        copiaVieja.setFechaFin(LocalDate.now().plusDays(1));
        copiaVieja.setLimitePiezas(20);
        copiaVieja.setPiezasUsadas(0.0);
        copiaVieja.setDescripcion("editada");
        promocionService.guardar(copiaVieja);

        assertThat(promocionRepo.findById(promo.getId()).orElseThrow().getPiezasUsadasOCero())
                .as("el contador de piezas usadas debe conservarse al editar la promoción")
                .isEqualTo(5);
    }

    /**
     * M-5 (medio). Desactivar un proveedor con una orden ENVIADA la dejaba
     * irrecibible ("El proveedor está desactivado"). Decisión: lo ya pedido se
     * puede recibir; las órdenes NUEVAS a ese proveedor siguen rechazándose.
     */
    @Test
    void desactivarUnProveedorConOrdenesPendientesDebePermitirRecibirlas() {
        Producto pan = conMinimo("Pan", 2, 5);
        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 5, 10)), null);
        ordenCompraService.marcarEnviada(o.getId());

        proveedorService.cambiarActivo(bimbo.getId(), false);

        assertThatCode(() -> ordenCompraService.recibir(o.getId(), null))
                .as("una orden enviada antes de desactivar al proveedor debe poder recibirse")
                .doesNotThrowAnyException();
        assertThat(stockActual(pan)).isEqualTo(7);
        assertThatThrownBy(() -> ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 1, 10)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("desactivado");
    }

    /**
     * M-6 (medio). Dar de alta un producto con existencia inicial solo pedía
     * EDITAR_PRODUCTOS, pero activar inventario en uno existente exige
     * AJUSTAR_INVENTARIO: quien solo edita productos podía "crear" existencia.
     */
    @Test
    void altaDeProductoConExistenciaDebeExigirAjustarInventario() throws Exception {
        iniciarComoCajero("capturistaQA", EnumSet.of(Permiso.EDITAR_PRODUCTOS));
        assertThatCode(() -> producto("Sin stock", 10, 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> producto("Con stock", 10, 5))
                .as("existencia inicial sin AJUSTAR_INVENTARIO")
                .isInstanceOf(PermisoRequeridoException.class);

        iniciarComoCajero("almacenQA", EnumSet.of(Permiso.EDITAR_PRODUCTOS, Permiso.AJUSTAR_INVENTARIO));
        assertThatCode(() -> producto("Con stock y permiso", 10, 5)).doesNotThrowAnyException();
    }

    /**
     * M-7 (decisión del dueño: NO bloquear). Un precio de mayoreo mayor al de
     * venta (el caso "Coca $200 mayoreo vs $114") se guarda, pero la pantalla
     * muestra una advertencia que el usuario puede confirmar.
     */
    @Test
    void elPrecioDeMayoreoMayorQueElDeVentaSeGuardaConAdvertencia() {
        Producto p = new Producto();
        p.setCodigoBarras("QA-MAY-1");
        p.setDescripcion("Coca");
        p.setPrecioVenta(114);
        p.setPrecioMayoreo(200);
        p.setUsaInventario(false);

        assertThat(ProductoService.advertenciaMayoreo(p)).isPresent();
        assertThat(ProductoService.advertenciaMayoreo(p).get()).contains("mayoreo").contains("MAYOR");
        assertThatCode(() -> productoService.guardar(p)).doesNotThrowAnyException();

        p.setPrecioMayoreo(100);
        assertThat(ProductoService.advertenciaMayoreo(p)).isEmpty();
        p.setPrecioMayoreo(0);
        assertThat(ProductoService.advertenciaMayoreo(p)).isEmpty();
    }

    /**
     * B-7 (bajo). Un producto descontinuado seguía aceptando entradas de
     * inventario. Ahora la entrada se rechaza con un mensaje claro; el ajuste
     * (p. ej. dejarlo en cero por merma) sigue permitido.
     */
    @Test
    void unProductoDesactivadoNoDebeRecibirEntradas() {
        Producto p = producto("Viejo", 10, 3);
        productoService.cambiarActivo(p.getId(), false);
        assertThatThrownBy(() -> productoService.registrarEntrada(p.getId(), 5, "Bimbo QA", 4, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("desactivado");
        assertThatCode(() -> productoService.ajustarStock(p.getId(), 0, "Merma: caducado", null))
                .doesNotThrowAnyException();
    }
}
