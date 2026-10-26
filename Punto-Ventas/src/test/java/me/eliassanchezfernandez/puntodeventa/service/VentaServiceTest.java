package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Cobro de ventas: promociones, descuento de inventario y rechazo
 * de ventas sin existencia. Corre contra la BD desechable de pruebas.
 */
@SpringBootTest
@Transactional
class VentaServiceTest {

    @Autowired private VentaService      ventaService;
    @Autowired private PromocionService  promocionService;
    @Autowired private ProductoService   productoService;
    @Autowired private SesionService     sesionService;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private VentaRepository    ventaRepo;

    @BeforeEach
    void iniciarSesion() throws Exception {
        sesionService.autenticar("admin", "admin123");
    }

    @AfterEach
    void cerrarSesion() {
        sesionService.cerrarSesion();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Producto producto(String descripcion, double precio, double stock) {
        Producto p = new Producto();
        p.setCodigoBarras("V-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(descripcion);
        p.setPrecioCosto(precio / 2);
        p.setPrecioVenta(precio);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    private Promocion promocion(Producto p, Promocion.Tipo tipo, double valor) {
        Promocion promo = new Promocion();
        promo.setProducto(p);
        promo.setTipo(tipo);
        promo.setValor(valor);
        promo.setFechaInicio(LocalDate.now().minusDays(1));
        promo.setFechaFin(LocalDate.now().plusDays(1));
        promo.setActiva(true);
        return promocionService.guardar(promo);
    }

    /** Renglón armado "a mano", como si viniera de una UI que no validó nada */
    private DetalleVenta detalleCrudo(Producto p, int cantidad, double precio, double descuento) {
        DetalleVenta d = new DetalleVenta();
        d.setProducto(p);
        d.setCantidad(cantidad);
        d.setPrecioVenta(precio);
        d.setDescuento(descuento);
        return d;
    }

    private double stockActual(Producto p) {
        return productoRepo.findById(p.getId()).orElseThrow().getCantidadActual();
    }

    // ── Promociones ───────────────────────────────────────────────────────

    @Test
    void elTotalAplicaUnaPromocionPorPorcentaje() {
        Producto p = producto("Leche 1L", 100, 10);
        promocion(p, Promocion.Tipo.PORCENTAJE, 10);

        DetalleVenta d = ventaService.crearDetalle(p, 2);
        assertThat(d.getPrecioVenta()).isEqualTo(100);
        assertThat(d.getDescuento()).isCloseTo(20, within(0.001));
        assertThat(d.getImporte()).isCloseTo(180, within(0.001));

        Venta venta = ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 200);

        assertThat(venta.getId()).isNotNull();
        assertThat(venta.getTotal()).isCloseTo(180, within(0.001));
        assertThat(venta.getCambio()).isCloseTo(20, within(0.001));
        assertThat(venta.getDetalles()).hasSize(1);
        assertThat(venta.getCajero()).isNotNull();
        assertThat(venta.getCajero().getUsuario()).isEqualTo("admin");
        assertThat(venta.getCajeroNombre()).isEqualTo("Administrador");
    }

    @Test
    void elTotalAplicaUnaPromocionDePrecioFijo() {
        Producto p = producto("Pan", 100, 10);
        promocion(p, Promocion.Tipo.PRECIO_FIJO, 80);

        Venta venta = ventaService.cobrar(
                List.of(ventaService.crearDetalle(p, 3)), Venta.FormaPago.TARJETA, 0);

        assertThat(venta.getTotal()).isCloseTo(240, within(0.001));
        assertThat(venta.getPagoCon()).isCloseTo(240, within(0.001)); // tarjeta cobra exacto
        assertThat(venta.getCambio()).isCloseTo(0, within(0.001));
    }

    @Test
    void unaPromocionFueraDeFechaOInactivaNoAplica() {
        Producto p = producto("Galletas", 50, 10);
        Promocion vencida = promocion(p, Promocion.Tipo.PORCENTAJE, 50);
        vencida.setFechaFin(LocalDate.now().minusDays(1));
        vencida.setFechaInicio(LocalDate.now().minusDays(5));
        promocionService.guardar(vencida);

        assertThat(ventaService.crearDetalle(p, 1).getDescuento()).isZero();

        Promocion inactiva = promocion(p, Promocion.Tipo.PORCENTAJE, 50);
        inactiva.setActiva(false);
        promocionService.guardar(inactiva);

        assertThat(ventaService.crearDetalle(p, 1).getDescuento()).isZero();
        assertThat(promocionService.precioVigente(p)).isEqualTo(50);
    }

    @Test
    void seEligeLaPromocionQueMasBeneficiaAlCliente() {
        Producto p = producto("Refresco", 20, 10);
        promocion(p, Promocion.Tipo.PORCENTAJE, 10);  // → 18
        promocion(p, Promocion.Tipo.PRECIO_FIJO, 15); // → 15

        assertThat(promocionService.precioVigente(p)).isCloseTo(15, within(0.001));
    }

    @Test
    void promocionesInvalidasSonRechazadasYNuncaDanPreciosNegativos() {
        Producto p = producto("Jabón", 30, 10);

        assertThatThrownBy(() -> promocion(p, Promocion.Tipo.PORCENTAJE, 150))
                .isInstanceOf(NegocioException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> promocion(p, Promocion.Tipo.PRECIO_FIJO, 30))
                .isInstanceOf(NegocioException.class).hasMessageContaining("menor");
        assertThatThrownBy(() -> promocion(p, Promocion.Tipo.PORCENTAJE, 0))
                .isInstanceOf(NegocioException.class);

        // 100 % es el máximo permitido y deja el precio en 0, nunca negativo
        promocion(p, Promocion.Tipo.PORCENTAJE, 100);
        DetalleVenta d = ventaService.crearDetalle(p, 2);
        assertThat(d.getImporte()).isCloseTo(0, within(0.001));
        assertThat(d.getDescuento()).isGreaterThanOrEqualTo(0);
    }

    // ── Inventario ────────────────────────────────────────────────────────

    @Test
    void cobrarDescuentaElInventario() {
        Producto p = producto("Azúcar", 25, 10);

        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 3)), Venta.FormaPago.EFECTIVO, 100);

        assertThat(stockActual(p)).isEqualTo(7);
    }

    @Test
    void unProductoSinInventarioNoSeDescuentaNiSeBloquea() {
        Producto p = producto("Servicio", 10, 0);
        p.setUsaInventario(false);
        p = productoService.guardar(p);

        Venta venta = ventaService.cobrar(
                List.of(ventaService.crearDetalle(p, 5)), Venta.FormaPago.EFECTIVO, 50);

        assertThat(venta.getTotal()).isEqualTo(50);
        assertThat(stockActual(p)).isEqualTo(0);
    }

    @Test
    void rechazaUnaVentaQueExcedeElStock() {
        Producto p = producto("Aceite", 40, 2);
        long ventasAntes = ventaRepo.count();

        // Al armar el ticket
        assertThatThrownBy(() -> ventaService.crearDetalle(p, 5))
                .isInstanceOf(StockInsuficienteException.class)
                .hasMessageContaining("Aceite");

        // Y al cobrar, aunque el renglón se haya armado por fuera
        DetalleVenta crudo = detalleCrudo(p, 5, 40, 0);
        assertThatThrownBy(() -> ventaService.cobrar(List.of(crudo), Venta.FormaPago.EFECTIVO, 1000))
                .isInstanceOf(StockInsuficienteException.class);

        assertThat(stockActual(p)).isEqualTo(2);
        assertThat(ventaRepo.count()).isEqualTo(ventasAntes);
    }

    @Test
    void renglonesRepetidosDelMismoProductoSeSumanParaValidarStock() {
        Producto p = producto("Atún", 15, 5);
        long ventasAntes = ventaRepo.count();

        // Cada renglón por separado cabe (3 ≤ 5), pero juntos no (6 > 5)
        List<DetalleVenta> ticket = List.of(
                ventaService.crearDetalle(p, 3),
                ventaService.crearDetalle(p, 3));

        assertThatThrownBy(() -> ventaService.cobrar(ticket, Venta.FormaPago.EFECTIVO, 1000))
                .isInstanceOf(StockInsuficienteException.class);

        assertThat(stockActual(p)).isEqualTo(5);
        assertThat(ventaRepo.count()).isEqualTo(ventasAntes);
    }

    @Test
    void renglonesIdenticosSeGuardanTodosYDescuentanTodos() {
        Producto p = producto("Sopa", 12, 10);

        Venta venta = ventaService.cobrar(
                List.of(ventaService.crearDetalle(p, 1), ventaService.crearDetalle(p, 1)),
                Venta.FormaPago.EFECTIVO, 100);

        assertThat(venta.getDetalles()).hasSize(2);
        assertThat(venta.getTotal()).isCloseTo(24, within(0.001));
        assertThat(stockActual(p)).isEqualTo(8);
    }

    // ── Seguridad del cobro ───────────────────────────────────────────────

    @Test
    void cobrarRecalculaPrecioYPromocionDesdeLaBaseDeDatos() {
        Producto p = producto("Café", 100, 10);
        promocion(p, Promocion.Tipo.PORCENTAJE, 10);

        // La UI manda un precio de $1 y un descuento absurdo
        DetalleVenta manipulado = detalleCrudo(p, 1, 1, 999);
        Venta venta = ventaService.cobrar(List.of(manipulado), Venta.FormaPago.EFECTIVO, 100);

        assertThat(venta.getTotal()).isCloseTo(90, within(0.001));
        assertThat(manipulado.getPrecioVenta()).isEqualTo(100);
        assertThat(manipulado.getDescuento()).isCloseTo(10, within(0.001));
    }

    @Test
    void cobrarRespetaElPrecioDeMayoreoSiElProductoLoTiene() {
        Producto p = producto("Papel", 50, 10);
        p.setPrecioMayoreo(40);
        p = productoService.guardar(p);

        DetalleVenta mayoreo = detalleCrudo(p, 2, 40, 0);
        DetalleVenta inventado = detalleCrudo(p, 1, 5, 0);
        Venta venta = ventaService.cobrar(List.of(mayoreo, inventado), Venta.FormaPago.EFECTIVO, 500);

        assertThat(mayoreo.getPrecioVenta()).isEqualTo(40);     // mayoreo válido se respeta
        assertThat(inventado.getPrecioVenta()).isEqualTo(50);   // precio inventado → lista
        assertThat(venta.getTotal()).isCloseTo(130, within(0.001));
    }

    @Test
    void rechazaPagoInsuficienteTicketVacioYSinSesion() {
        Producto p = producto("Huevo", 60, 10);
        long ventasAntes = ventaRepo.count();

        assertThatThrownBy(() -> ventaService.cobrar(
                List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 10))
                .isInstanceOf(NegocioException.class).hasMessageContaining("menor");

        assertThatThrownBy(() -> ventaService.cobrar(List.of(), Venta.FormaPago.EFECTIVO, 10))
                .isInstanceOf(NegocioException.class);

        // NaN / Infinity / negativos no son montos válidos
        for (double invalido : new double[] { Double.NaN, Double.POSITIVE_INFINITY, -5 }) {
            assertThatThrownBy(() -> ventaService.cobrar(
                    List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, invalido))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("válido");
        }

        sesionService.cerrarSesion();
        assertThatThrownBy(() -> ventaService.cobrar(
                List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100))
                .isInstanceOf(NegocioException.class).hasMessageContaining("sesión");

        assertThat(stockActual(p)).isEqualTo(10);
        assertThat(ventaRepo.count()).isEqualTo(ventasAntes);
    }

    // ── Historial ─────────────────────────────────────────────────────────

    @Test
    void elHistorialDelDiaIncluyeLaVentaConSusRenglones() {
        Producto p = producto("Tortillas", 20, 10);
        Venta venta = ventaService.cobrar(
                List.of(ventaService.crearDetalle(p, 2)), Venta.FormaPago.EFECTIVO, 40);

        List<Venta> hoy = ventaService.ventasEntre(LocalDate.now(), LocalDate.now());

        assertThat(hoy).extracting(Venta::getId).contains(venta.getId());
        Venta encontrada = hoy.stream().filter(v -> v.getId().equals(venta.getId())).findFirst().orElseThrow();
        assertThat(encontrada.getDetalles()).hasSize(1);
        assertThat(encontrada.getDetalles().get(0).getProducto().getDescripcion()).isEqualTo("Tortillas");
        assertThat(ventaService.totalVentasHoy()).isGreaterThanOrEqualTo(40);

        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        assertThat(r.ventasTotales).isGreaterThanOrEqualTo(40);
        assertThat(r.efectivo).isGreaterThanOrEqualTo(40);
        assertThat(r.porDepartamento).containsKey("Sin departamento");

        assertThatThrownBy(() -> ventaService.ventasEntre(LocalDate.now(), LocalDate.now().minusDays(1)))
                .isInstanceOf(NegocioException.class);
    }
}
