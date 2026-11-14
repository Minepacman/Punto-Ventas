package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.PromocionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Cobro que falla a medias (la base rechaza el INSERT, la promoción se agotó): la
 * transacción revierte TODO y el ticket en pantalla queda tal cual para reintentar.
 *
 * Sin la transacción de prueba (NOT_SUPPORTED): aquí importa que la del servicio se
 * revierta de verdad. El fallo de la base se simula con un trigger de SQLite que aborta
 * cualquier INSERT en ventas; lo que sí se guarda se borra al final.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CobroFallidoTest extends BaseServicioTest {

    private static final String TRIGGER = "prueba_falla_insert_ventas";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private PromocionRepository promocionRepo;

    private final List<Long> productosCreados = new ArrayList<>();
    private final List<Long> promocionesCreadas = new ArrayList<>();

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    @AfterEach
    void limpiarLoGuardado() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + TRIGGER);
        for (Long id : productosCreados) {
            jdbc.update("DELETE FROM pagos_venta WHERE venta_id IN (SELECT venta_id FROM detalle_ventas WHERE producto_id = ?)", id);
            jdbc.update("DELETE FROM ventas WHERE id IN (SELECT venta_id FROM detalle_ventas WHERE producto_id = ?)", id);
            jdbc.update("DELETE FROM detalle_ventas WHERE producto_id = ?", id);
            jdbc.update("DELETE FROM movimientos_inventario WHERE producto_id = ?", id);
            jdbc.update("DELETE FROM historial_precios WHERE producto_id = ?", id);
        }
        for (Long id : promocionesCreadas) jdbc.update("DELETE FROM promociones WHERE id = ?", id);
        for (Long id : productosCreados) jdbc.update("DELETE FROM productos WHERE id = ?", id);
        sesionService.cerrarSesion();
        jdbc.update("DELETE FROM auditoria WHERE usuario_login LIKE 'cajero_falla_%'");
        jdbc.update("DELETE FROM cajeros WHERE usuario LIKE 'cajero_falla_%'");
    }

    private Producto productoRastreado(String descripcion, double precio, double stock) {
        Producto p = producto(descripcion, precio, stock);
        productosCreados.add(p.getId());
        return p;
    }

    private Promocion promocionHastaAgotar(Producto p, int limitePiezas) {
        Promocion promo = promocion(p, Promocion.Tipo.PORCENTAJE, 10);
        promo.setLimitePiezas(limitePiezas);
        promo = promocionService.guardar(promo);
        promocionesCreadas.add(promo.getId());
        return promo;
    }

    private double piezasUsadas(Promocion promo) {
        return promocionRepo.findById(promo.getId()).orElseThrow().getPiezasUsadasOCero();
    }

    private void hacerFallarLosInsertsDeVentas() {
        jdbc.execute("CREATE TRIGGER " + TRIGGER + " BEFORE INSERT ON ventas BEGIN "
                + "SELECT RAISE(ABORT, 'disco lleno (simulado)'); END");
    }

    @Test
    void siLaBaseFallaAlGuardarNoQuedaNadaYElMismoTicketSeCobraAlReintentar() {
        Producto p = productoRastreado("Aceite 1L", 40, 10);
        Promocion promo = promocionHastaAgotar(p, 50);
        DetalleVenta renglon = ventaService.crearDetalle(p, 3);
        Producto productoEnPantalla = renglon.getProducto();
        double descuentoEnPantalla = renglon.getDescuento();
        long ventasAntes = ventaRepo.count();

        hacerFallarLosInsertsDeVentas();
        assertThatThrownBy(() -> ventaService.cobrar(List.of(renglon), Venta.FormaPago.EFECTIVO, 200))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NegocioException.class);

        // Nada quedó en la base: ni venta, ni descuento de inventario, ni cupo de promoción
        assertThat(ventaRepo.count()).isEqualTo(ventasAntes);
        assertThat(stockActual(p)).isEqualTo(10);
        assertThat(piezasUsadas(promo)).isZero();

        // El renglón del ticket está exactamente como estaba: sin id ni venta, mismo producto y cantidad
        assertThat(renglon.getId()).isNull();
        assertThat(renglon.getVenta()).isNull();
        assertThat(renglon.getProducto()).isSameAs(productoEnPantalla);
        assertThat(renglon.getCantidad()).isEqualTo(3);
        assertThat(renglon.getDescuento()).isEqualTo(descuentoEnPantalla);

        // Se quita la falla y el MISMO objeto se cobra sin tocarlo
        jdbc.execute("DROP TRIGGER IF EXISTS " + TRIGGER);
        Venta venta = ventaService.cobrar(List.of(renglon), Venta.FormaPago.EFECTIVO, 200);

        assertThat(venta.getId()).isNotNull();
        assertThat(venta.getTotal()).isCloseTo(108, within(0.001));      // 3 × 40 − 10 %
        assertThat(venta.getDetalles()).hasSize(1);
        assertThat(venta.getDetalles().get(0)).as("la venta guarda una copia, no el renglón de pantalla").isNotSameAs(renglon);
        assertThat(venta.getDetalles().get(0).getProducto().getId()).isEqualTo(p.getId());
        assertThat(venta.getDetalles().get(0).getPromocionId()).isEqualTo(promo.getId());
        assertThat(renglon.getVenta()).as("el renglón de pantalla sigue sin venta").isNull();
        assertThat(stockActual(p)).isEqualTo(7);
        assertThat(piezasUsadas(promo)).isEqualTo(3);
        assertThat(ventaRepo.count()).isEqualTo(ventasAntes + 1);
    }

    @Test
    void siLaBaseFallaLasAutorizacionesDelTicketSiguenVigentesParaElReintento() throws Exception {
        Producto p = productoRastreado("Harina", 30, 1);
        me.eliassanchezfernandez.puntodeventa.model.Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        iniciarComoCajero("cajero_falla_" + System.nanoTime() % 100000, java.util.Set.of());
        Autorizacion aut = new Autorizacion(admin, Permiso.VENDER_SIN_STOCK, java.time.LocalDateTime.now());
        List<Autorizacion> autorizaciones = new ArrayList<>(List.of(aut));
        DetalleVenta renglon = ventaService.crearDetalle(p, 2, true);

        hacerFallarLosInsertsDeVentas();
        assertThatThrownBy(() -> ventaService.cobrar(List.of(renglon),
                List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, 100)), autorizaciones))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NegocioException.class);

        assertThat(aut.isConsumida()).as("la autorización no se gasta en un cobro que no se guardó").isFalse();
        assertThat(stockActual(p)).isEqualTo(1);
        assertThat(renglon.getAutorizadoPorUsuario()).isNull();

        jdbc.execute("DROP TRIGGER IF EXISTS " + TRIGGER);
        Venta venta = ventaService.cobrar(List.of(renglon),
                List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, 100)), autorizaciones);
        assertThat(venta.getId()).isNotNull();
        assertThat(venta.getDetalles().get(0).getAutorizadoPorUsuario()).isEqualTo("admin");
        assertThat(aut.isConsumida()).isTrue();
        assertThat(stockActual(p)).isEqualTo(-1);
    }

    @Test
    void siFallaElConsumoDelCupoDePromocionSeRevierteElInventarioYaDescontado() {
        // El inventario se descuenta con un UPDATE antes de consumir el cupo de la promoción: si ese
        // paso falla (aquí la base rechaza el UPDATE a promociones), el descuento de stock debe revertirse
        Producto p = productoRastreado("Refresco", 20, 10);
        Promocion promo = promocionHastaAgotar(p, 5);
        DetalleVenta renglon = ventaService.crearDetalle(p, 2);
        assertThat(renglon.getPromocionId()).isEqualTo(promo.getId());
        long ventasAntes = ventaRepo.count();

        jdbc.execute("CREATE TRIGGER " + TRIGGER + " BEFORE UPDATE ON promociones BEGIN "
                + "SELECT RAISE(ABORT, 'base bloqueada (simulado)'); END");
        assertThatThrownBy(() -> ventaService.cobrar(List.of(renglon), Venta.FormaPago.EFECTIVO, 100))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NegocioException.class);

        assertThat(ventaRepo.count()).isEqualTo(ventasAntes);
        assertThat(stockActual(p)).as("el inventario descontado antes del fallo se revirtió").isEqualTo(10);
        assertThat(piezasUsadas(promo)).isZero();
        assertThat(renglon.getId()).isNull();
        assertThat(renglon.getVenta()).isNull();
        assertThat(renglon.getCantidad()).isEqualTo(2);

        jdbc.execute("DROP TRIGGER IF EXISTS " + TRIGGER);
        Venta venta = ventaService.cobrar(List.of(renglon), Venta.FormaPago.EFECTIVO, 100);
        assertThat(venta.getTotal()).isCloseTo(36, within(0.001));      // 2 × 20 − 10 %
        assertThat(stockActual(p)).isEqualTo(8);
        assertThat(piezasUsadas(promo)).isEqualTo(2);
    }

    @Test
    void siOtraCajaAgotoLaPromocionElCobroRecalculaSinPromocionYElTicketSigueIntacto() {
        Producto p = productoRastreado("Jugo", 20, 10);
        Promocion promo = promocionHastaAgotar(p, 2);
        DetalleVenta renglon = ventaService.crearDetalle(p, 2);
        assertThat(renglon.getPromocionId()).isEqualTo(promo.getId());

        // Otra caja vendió las últimas piezas con promoción entre armar el ticket y cobrar
        assertThat(jdbc.update("UPDATE promociones SET piezas_usadas = 2 WHERE id = ?", promo.getId())).isEqualTo(1);

        Venta venta = ventaService.cobrar(List.of(renglon), Venta.FormaPago.EFECTIVO, 100);
        assertThat(venta.getTotal()).as("se cobra a precio de lista: la promoción ya no alcanza").isCloseTo(40, within(0.001));
        assertThat(venta.getDetalles().get(0).getPromocionId()).isNull();
        assertThat(piezasUsadas(promo)).as("no se pasa del límite").isEqualTo(2);
        assertThat(stockActual(p)).isEqualTo(8);
        // El renglón en pantalla conserva lo que mostraba (el controlador lo limpia al cobrar con éxito)
        assertThat(renglon.getVenta()).isNull();
        assertThat(renglon.getPromocionId()).isEqualTo(promo.getId());
    }
}
