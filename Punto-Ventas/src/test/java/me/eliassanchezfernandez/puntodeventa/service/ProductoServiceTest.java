package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.HistorialPrecio;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validaciones del catálogo, historial de precios, activar/desactivar y departamentos. */
class ProductoServiceTest extends BaseServicioTest {

    @BeforeEach
    void iniciarComoAdministrador() throws Exception {
        iniciarComoAdmin();
    }

    private Producto nuevoProducto(String descripcion, double precio, double stock, double minimo) {
        Producto p = new Producto();
        p.setCodigoBarras("T-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(descripcion);
        p.setPrecioCosto(precio / 2);
        p.setPrecioVenta(precio);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(minimo);
        return p;
    }

    @Test
    void guardaYBuscaPorCodigo() {
        Producto p = productoService.guardar(nuevoProducto("Coca Cola 600ml", 18, 10, 2));

        assertThat(p.getId()).isNotNull();
        assertThat(p.isActivo()).isTrue();
        assertThat(productoService.buscarPorCodigo(p.getCodigoBarras())).isPresent();
        assertThat(productoService.buscar("coca")).extracting(Producto::getId).contains(p.getId());
    }

    @Test
    void rechazaCodigoDuplicado() {
        Producto p = productoService.guardar(nuevoProducto("Original", 10, 1, 0));
        Producto dup = nuevoProducto("Copia", 10, 1, 0);
        dup.setCodigoBarras(p.getCodigoBarras());

        assertThatThrownBy(() -> productoService.guardar(dup))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("Ya existe");
    }

    @Test
    void rechazaPreciosYCantidadesInvalidas() {
        Producto sinPrecio = nuevoProducto("Sin precio", 0, 1, 0);
        assertThatThrownBy(() -> productoService.guardar(sinPrecio))
                .isInstanceOf(NegocioException.class).hasMessageContaining("precio de venta");

        Producto negativo = nuevoProducto("Negativo", 10, 1, 0);
        negativo.setPrecioCosto(-1);
        assertThatThrownBy(() -> productoService.guardar(negativo))
                .isInstanceOf(NegocioException.class).hasMessageContaining("negativos");

        Producto stockNegativo = nuevoProducto("Stock negativo", 10, -3, 0);
        assertThatThrownBy(() -> productoService.guardar(stockNegativo))
                .isInstanceOf(NegocioException.class);

        Producto sinCodigo = nuevoProducto("Sin código", 10, 1, 0);
        sinCodigo.setCodigoBarras("  ");
        assertThatThrownBy(() -> productoService.guardar(sinCodigo))
                .isInstanceOf(NegocioException.class);

        Producto porPiezaConDecimales = nuevoProducto("Por pieza", 10, 2.5, 0);
        assertThatThrownBy(() -> productoService.guardar(porPiezaConDecimales))
                .isInstanceOf(NegocioException.class).hasMessageContaining("entero");
    }

    @Test
    void detectaStockBajo() {
        Producto bajo    = productoService.guardar(nuevoProducto("Bajo", 10, 2, 5));
        Producto enLimite = productoService.guardar(nuevoProducto("En límite", 10, 5, 5));
        Producto ok      = productoService.guardar(nuevoProducto("OK", 10, 50, 5));
        Producto sinInv  = nuevoProducto("Sin inventario", 10, 0, 5);
        sinInv.setUsaInventario(false);
        sinInv = productoService.guardar(sinInv);

        assertThat(productoService.productosConStockBajo()).extracting(Producto::getId)
                .contains(bajo.getId(), enLimite.getId())
                .doesNotContain(ok.getId(), sinInv.getId());
        assertThat(ProductoService.tieneStockBajo(bajo)).isTrue();
        assertThat(ProductoService.tieneStockBajo(ok)).isFalse();
    }

    @Test
    void cambiarPreciosExigePermisoYDejaHistorial() throws Exception {
        Producto p = productoService.guardar(nuevoProducto("Arroz", 30, 10, 2));

        Producto cambio = nuevoProducto("Arroz", 35, 10, 2);
        cambio.setId(p.getId());
        cambio.setCodigoBarras(p.getCodigoBarras());
        cambio.setPrecioCosto(p.getPrecioCosto());
        productoService.guardar(cambio);

        assertThat(productoService.historialPrecios(p.getId()))
                .extracting(HistorialPrecio::getCampo, HistorialPrecio::getPrecioAnterior, HistorialPrecio::getPrecioNuevo)
                .contains(org.assertj.core.groups.Tuple.tuple("VENTA", 30.0, 35.0));
        assertThat(productoService.historialPrecios(p.getId()).get(0).getUsuarioId())
                .isEqualTo(sesionService.getCajeroActual().getId());

        // Un gerente sin CAMBIAR_PRECIOS puede editar la descripción pero no el precio
        crearUsuario("ger2", me.eliassanchezfernandez.puntodeventa.model.Cajero.Rol.GERENTE,
                java.util.EnumSet.of(me.eliassanchezfernandez.puntodeventa.model.Permiso.EDITAR_PRODUCTOS));
        iniciarComo("ger2", CLAVE_PRUEBA);
        Producto soloNombre = nuevoProducto("Arroz premium", 35, 10, 2);
        soloNombre.setId(p.getId());
        soloNombre.setCodigoBarras(p.getCodigoBarras());
        soloNombre.setPrecioCosto(p.getPrecioCosto());
        productoService.guardar(soloNombre);

        Producto otroPrecio = nuevoProducto("Arroz premium", 40, 10, 2);
        otroPrecio.setId(p.getId());
        otroPrecio.setCodigoBarras(p.getCodigoBarras());
        otroPrecio.setPrecioCosto(p.getPrecioCosto());
        assertThatThrownBy(() -> productoService.guardar(otroPrecio))
                .isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void laExistenciaNoSeCambiaDesdeProductosEnEdicion() {
        Producto p = productoService.guardar(nuevoProducto("Frijol", 25, 10, 1));

        Producto editado = nuevoProducto("Frijol", 25, 500, 1);
        editado.setId(p.getId());
        editado.setCodigoBarras(p.getCodigoBarras());
        editado.setPrecioCosto(p.getPrecioCosto());
        productoService.guardar(editado);

        assertThat(stockActual(p)).isEqualTo(10);
    }

    @Test
    void desactivarOcultaElProductoEnVentas() {
        Producto p = productoService.guardar(nuevoProducto("Descontinuado", 10, 5, 1));

        productoService.cambiarActivo(p.getId(), false);

        assertThat(productoService.buscarParaVenta("Descontinuado")).isEmpty();
        assertThat(productoService.buscar("Descontinuado")).extracting(Producto::getId).contains(p.getId());
        assertThat(ventaService.buscarPorCodigo(p.getCodigoBarras())).isEmpty();
        assertThatThrownBy(() -> ventaService.crearDetalle(productoRepo.findById(p.getId()).orElseThrow(), 1))
                .isInstanceOf(NegocioException.class).hasMessageContaining("desactivado");

        productoService.cambiarActivo(p.getId(), true);
        assertThat(ventaService.buscarPorCodigo(p.getCodigoBarras())).isPresent();
    }

    @Test
    void unProductoConMovimientosOHistorialNoSeElimina() {
        Producto conStock = productoService.guardar(nuevoProducto("Con alta inicial", 10, 5, 1));   // ALTA_INICIAL
        assertThatThrownBy(() -> productoService.eliminar(conStock.getId()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Desactívalo");
        assertThat(productoService.movimientosDe(conStock.getId())).isNotEmpty();

        Producto limpio = productoService.guardar(nuevoProducto("Sin movimientos", 10, 0, 0));
        productoService.eliminar(limpio.getId());
        assertThat(productoRepo.findById(limpio.getId())).isEmpty();
    }

    @Test
    void departamentoDuplicadoYEnUsoSonRechazados() {
        Departamento d = departamento("Abarrotes");

        Departamento repetido = new Departamento();
        repetido.setNombre(d.getNombre().toUpperCase());
        assertThatThrownBy(() -> productoService.guardarDepartamento(repetido))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Ya existe");

        Producto p = nuevoProducto("Frijol", 25, 5, 1);
        p.setDepartamento(d);
        productoService.guardar(p);

        assertThatThrownBy(() -> productoService.eliminarDepartamento(d))
                .isInstanceOf(NegocioException.class).hasMessageContaining("pertenecen");
    }
}
