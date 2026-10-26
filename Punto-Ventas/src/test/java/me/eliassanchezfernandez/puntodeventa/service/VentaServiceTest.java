package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Cobro de ventas: promociones básicas, descuento de inventario y rechazo sin existencia. */
class VentaServiceTest extends BaseServicioTest {

    @Autowired private VentaRepository ventaRepo;

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    @Test
    void elTotalAplicaUnaPromocionPorPorcentaje() {
        Producto p = producto("Leche 1L", 100, 10);
        promocion(p, Promocion.Tipo.PORCENTAJE, 10);

        DetalleVenta d = ventaService.crearDetalle(p, 2);
        assertThat(d.getPrecioVenta()).isEqualTo(100);
        assertThat(d.getDescuento()).isCloseTo(20, within(0.001));
        assertThat(d.getImporte()).isCloseTo(180, within(0.001));
        assertThat(d.getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.PROMOCION);

        Venta venta = ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 200);

        assertThat(venta.getId()).isNotNull();
        assertThat(venta.getTotal()).isCloseTo(180, within(0.001));
        assertThat(venta.getCambio()).isCloseTo(20, within(0.001));
        assertThat(venta.getDetalles()).hasSize(1);
        assertThat(venta.getCajero().getUsuario()).isEqualTo("admin");
        assertThat(venta.getCajeroNombre()).isEqualTo("Administrador");
        assertThat(venta.getDetalles().get(0).getPromocionNombre()).isNotBlank();
    }

    @Test
    void elTotalAplicaUnaPromocionDePrecioFijo() {
        Producto p = producto("Pan", 100, 10);
        promocion(p, Promocion.Tipo.PRECIO_FIJO, 80);

        Venta venta = ventaService.cobrar(
                List.of(ventaService.crearDetalle(p, 3)), Venta.FormaPago.TARJETA, 240);

        assertThat(venta.getTotal()).isCloseTo(240, within(0.001));
        assertThat(venta.getPagoCon()).isCloseTo(240, within(0.001));
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
    void promocionesInvalidasSonRechazadasYNuncaDanPreciosNegativos() {
        Producto p = producto("Jabón", 30, 10);

        assertThatThrownBy(() -> promocion(p, Promocion.Tipo.PORCENTAJE, 150))
                .isInstanceOf(NegocioException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> promocion(p, Promocion.Tipo.PRECIO_FIJO, 30))
                .isInstanceOf(NegocioException.class).hasMessageContaining("menor");
        assertThatThrownBy(() -> promocion(p, Promocion.Tipo.PORCENTAJE, 0))
                .isInstanceOf(NegocioException.class);

        promocion(p, Promocion.Tipo.PORCENTAJE, 100);
        DetalleVenta d = ventaService.crearDetalle(p, 2);
        assertThat(d.getImporte()).isCloseTo(0, within(0.001));
        assertThat(d.getDescuento()).isGreaterThanOrEqualTo(0);
    }

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
    void rechazaUnaVentaQueExcedeElStockSinPermiso() throws Exception {
        Producto p = producto("Aceite", 40, 2);
        iniciarComoCajero("caja1", null);
        long ventasAntes = ventaRepo.count();

        assertThatThrownBy(() -> ventaService.crearDetalle(p, 5))
                .isInstanceOf(StockInsuficienteException.class)
                .hasMessageContaining("Aceite");

        // Al cobrar se reporta como permiso requerido (VENDER_SIN_STOCK) para ofrecer la autorización
        DetalleVenta crudo = detalleCrudo(p, 5, 40, 0);
        assertThatThrownBy(() -> ventaService.cobrar(List.of(crudo), Venta.FormaPago.EFECTIVO, 1000))
                .isInstanceOf(PermisoRequeridoException.class)
                .hasMessageContaining("Aceite")
                .extracting(e -> ((PermisoRequeridoException) e).getPermiso())
                .isEqualTo(me.eliassanchezfernandez.puntodeventa.model.Permiso.VENDER_SIN_STOCK);

        assertThat(stockActual(p)).isEqualTo(2);
        assertThat(ventaRepo.count()).isEqualTo(ventasAntes);
    }

    @Test
    void elAvisoDeStockBajoSoloSaleCuandoLaVentaCruzaElMinimo() {
        Producto p = producto("Al límite", 10, 3);   // mínimo 1
        p.setCantidadMinima(2);
        p = productoService.guardar(p);

        Venta v1 = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 10);
        assertThat(v1.getAlertasStockBajo()).extracting(Producto::getId).containsExactly(p.getId());   // 3 → 2

        Venta v2 = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 10);
        assertThat(v2.getAlertasStockBajo()).isEmpty();   // 2 → 1: ya estaba bajo, no se repite
    }

    @Test
    void renglonesRepetidosDelMismoProductoSeSumanParaValidarStock() throws Exception {
        Producto p = producto("Atún", 15, 5);
        iniciarComoCajero("caja2", null);
        long ventasAntes = ventaRepo.count();

        List<DetalleVenta> ticket = List.of(
                ventaService.crearDetalle(p, 3),
                ventaService.crearDetalle(p, 3));

        assertThatThrownBy(() -> ventaService.cobrar(ticket, Venta.FormaPago.EFECTIVO, 1000))
                .isInstanceOf(PermisoRequeridoException.class).hasMessageContaining("Atún");

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

    @Test
    void cobrarRecalculaPrecioYPromocionDesdeLaBaseDeDatos() {
        Producto p = producto("Café", 100, 10);
        promocion(p, Promocion.Tipo.PORCENTAJE, 10);

        DetalleVenta manipulado = detalleCrudo(p, 1, 1, 999);
        Venta venta = ventaService.cobrar(List.of(manipulado), Venta.FormaPago.EFECTIVO, 100);

        assertThat(venta.getTotal()).isCloseTo(90, within(0.001));
        assertThat(manipulado.getPrecioVenta()).isEqualTo(100);
        assertThat(manipulado.getDescuento()).isCloseTo(10, within(0.001));
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

        for (double invalido : new double[] { Double.NaN, Double.POSITIVE_INFINITY, -5 }) {
            assertThatThrownBy(() -> ventaService.cobrar(
                    List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, invalido))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("válido");
        }

        sesionService.cerrarSesion();
        assertThatThrownBy(() -> ventaService.cobrar(
                List.of(detalleCrudo(p, 1, 60, 0)), Venta.FormaPago.EFECTIVO, 100))
                .isInstanceOf(NegocioException.class).hasMessageContaining("sesión");

        assertThat(stockActual(p)).isEqualTo(10);
        assertThat(ventaRepo.count()).isEqualTo(ventasAntes);
    }

    @Test
    void elHistorialDelDiaIncluyeLaVentaConSusRenglonesYPagos() {
        Producto p = producto("Tortillas", 20, 10);
        Venta venta = ventaService.cobrar(
                List.of(ventaService.crearDetalle(p, 2)), Venta.FormaPago.EFECTIVO, 40);

        List<Venta> hoy = ventaService.ventasEntre(LocalDate.now(), LocalDate.now());

        assertThat(hoy).extracting(Venta::getId).contains(venta.getId());
        Venta encontrada = hoy.stream().filter(v -> v.getId().equals(venta.getId())).findFirst().orElseThrow();
        assertThat(encontrada.getDetalles()).hasSize(1);
        assertThat(encontrada.getPagos()).hasSize(1);
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
