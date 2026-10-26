package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.HistorialPrecio;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bitácora de inventario (INV-04..INV-07): alta inicial, entradas, ajustes, ventas y cancelaciones. */
class InventarioMovimientosTest extends BaseServicioTest {

    @BeforeEach
    void iniciarComoAdministrador() throws Exception {
        iniciarComoAdmin();
    }

    @Test
    void elAltaDeUnProductoConExistenciaRegistraUnMovimientoInicial() {
        Producto p = producto("Nuevo con stock", 20, 12);

        List<MovimientoInventario> movs = productoService.movimientosDe(p.getId());
        assertThat(movs).hasSize(1);
        assertThat(movs.get(0).getTipo()).isEqualTo(MovimientoInventario.Tipo.ALTA_INICIAL);
        assertThat(movs.get(0).getCantidad()).isEqualTo(12);
        assertThat(movs.get(0).getExistenciaAntes()).isZero();
        assertThat(movs.get(0).getExistenciaDespues()).isEqualTo(12);
        assertThat(movs.get(0).getUsuarioId()).isEqualTo(sesionService.getCajeroActual().getId());
    }

    @Test
    void laEntradaRegistraProveedorYCostoYActualizaElCosto() {
        Producto p = producto("Aceite", 40, 5);   // costo inicial 20

        Producto actualizado = productoService.registrarEntrada(p.getId(), 10, "Distribuidora Norte", 22.5, null);

        assertThat(actualizado.getCantidadActual()).isEqualTo(15);
        assertThat(actualizado.getPrecioCosto()).isEqualTo(22.5);
        MovimientoInventario entrada = productoService.movimientosDe(p.getId()).get(0);
        assertThat(entrada.getTipo()).isEqualTo(MovimientoInventario.Tipo.ENTRADA);
        assertThat(entrada.getProveedor()).isEqualTo("Distribuidora Norte");
        assertThat(entrada.getCostoUnitario()).isEqualTo(22.5);
        assertThat(entrada.getExistenciaAntes()).isEqualTo(5);
        assertThat(entrada.getExistenciaDespues()).isEqualTo(15);
        assertThat(productoService.historialPrecios(p.getId()))
                .extracting(HistorialPrecio::getCampo, HistorialPrecio::getPrecioNuevo)
                .contains(org.assertj.core.groups.Tuple.tuple("COSTO", 22.5));

        assertThatThrownBy(() -> productoService.registrarEntrada(p.getId(), 1, "  ", 10, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("proveedor");
        assertThatThrownBy(() -> productoService.registrarEntrada(p.getId(), 0, "X", 10, null))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void elAjusteExigeMotivoYQuedaRegistrado() {
        Producto p = producto("Azúcar", 25, 10);

        assertThatThrownBy(() -> productoService.ajustarStock(p.getId(), 7, "", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("motivo");

        productoService.ajustarStock(p.getId(), 7, "Merma", null);

        assertThat(stockActual(p)).isEqualTo(7);
        MovimientoInventario ajuste = productoService.movimientosDe(p.getId()).get(0);
        assertThat(ajuste.getTipo()).isEqualTo(MovimientoInventario.Tipo.AJUSTE);
        assertThat(ajuste.getCantidad()).isEqualTo(-3);
        assertThat(ajuste.getMotivo()).isEqualTo("Merma");
    }

    @Test
    void inventarioExigePermisoOAutorizacion() throws Exception {
        Producto p = producto("Sal", 10, 10);
        iniciarComoCajero("almacen", EnumSet.noneOf(Permiso.class));

        assertThatThrownBy(() -> productoService.registrarEntrada(p.getId(), 5, "Prov", 4, null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> productoService.ajustarStock(p.getId(), 5, "Conteo", null))
                .isInstanceOf(PermisoRequeridoException.class);

        Autorizacion aut = new Autorizacion(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow(),
                Permiso.AJUSTAR_INVENTARIO, java.time.LocalDateTime.now());
        productoService.ajustarStock(p.getId(), 5, "Conteo", List.of(aut));

        assertThat(stockActual(p)).isEqualTo(5);
        assertThat(aut.isConsumida()).isTrue();
        MovimientoInventario m = productoService.movimientosDe(p.getId()).get(0);
        assertThat(m.getAutorizadoPor()).isEqualTo("Administrador");
        assertThat(m.getAutorizadoPorId()).isNotNull();
    }

    @Test
    void ventasYCancelacionesDejanMovimiento() {
        Producto p = producto("Leche", 30, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 4)), Venta.FormaPago.EFECTIVO, 200);
        ventaService.cancelar(v.getId(), "Error de captura", null);

        List<MovimientoInventario> movs = productoService.movimientosDe(p.getId());
        assertThat(movs).extracting(MovimientoInventario::getTipo).containsExactly(
                MovimientoInventario.Tipo.CANCELACION, MovimientoInventario.Tipo.VENTA, MovimientoInventario.Tipo.ALTA_INICIAL);
        assertThat(movs.get(1).getCantidad()).isEqualTo(-4);
        assertThat(movs.get(1).getVentaId()).isEqualTo(v.getId());
        assertThat(movs.get(0).getCantidad()).isEqualTo(4);
        assertThat(movs.get(0).getExistenciaDespues()).isEqualTo(10);
        assertThat(productoService.movimientosEntre(LocalDate.now(), LocalDate.now()))
                .extracting(MovimientoInventario::getId).contains(movs.get(0).getId(), movs.get(1).getId());
    }

    @Test
    void activarODesactivarInventarioDejaRastro() {
        Producto p = producto("Servicio", 100, 0);
        // Deja de usar inventario (sin existencia: no exige nada más)
        Producto sinInv = copia(p);
        sinInv.setUsaInventario(false);
        productoService.guardar(sinInv);

        // Vuelve a usar inventario con 8 piezas → ALTA_INICIAL (admin tiene AJUSTAR_INVENTARIO)
        Producto conInv = copia(p);
        conInv.setUsaInventario(true);
        conInv.setCantidadActual(8);
        productoService.guardar(conInv);

        assertThat(stockActual(p)).isEqualTo(8);
        assertThat(productoService.movimientosDe(p.getId()).get(0).getTipo())
                .isEqualTo(MovimientoInventario.Tipo.ALTA_INICIAL);

        // Dejar de usar inventario con existencia → movimiento de ajuste a 0
        Producto otraVez = copia(p);
        otraVez.setUsaInventario(false);
        productoService.guardar(otraVez);
        assertThat(stockActual(p)).isZero();
        assertThat(productoService.movimientosDe(p.getId()).get(0).getMotivo()).contains("Dejó de usar");
    }

    private static Producto copia(Producto p) {
        Producto c = new Producto();
        c.setId(p.getId());
        c.setCodigoBarras(p.getCodigoBarras());
        c.setDescripcion(p.getDescripcion());
        c.setTipoVenta(p.getTipoVenta());
        c.setPrecioCosto(p.getPrecioCosto());
        c.setPrecioVenta(p.getPrecioVenta());
        c.setPrecioMayoreo(p.getPrecioMayoreo());
        c.setUsaInventario(p.isUsaInventario());
        c.setCantidadActual(p.getCantidadActual());
        c.setCantidadMinima(p.getCantidadMinima());
        return c;
    }
}
