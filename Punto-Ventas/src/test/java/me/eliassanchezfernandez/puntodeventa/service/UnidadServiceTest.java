package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Catálogo de unidades de medida y su relación con el tipo de venta A granel. */
class UnidadServiceTest extends BaseServicioTest {

    @Autowired private UnidadService unidadService;

    private Producto productoConUnidad(String nombre, Unidad unidad, Producto.TipoVenta tipo) {
        Producto p = new Producto();
        p.setCodigoBarras("U-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioVenta(20);
        p.setPrecioCosto(10);
        p.setTipoVenta(tipo);
        p.setUnidad(unidad);
        p.setUsaInventario(true);
        p.setCantidadActual(10);
        return productoService.guardar(p);
    }

    @Test
    void elCatalogoTraeLasUnidadesPredeterminadas() {
        assertThat(unidadService.listar()).extracting(Unidad::getNombre)
                .contains("Pieza", "Kilogramo", "Gramo", "Litro", "Metro", "Caja");
        assertThat(unidadService.buscarPorNombre("kilogramo").orElseThrow().isPermiteDecimales()).isTrue();
        assertThat(unidadService.buscarPorNombre("Pieza").orElseThrow().isPermiteDecimales()).isFalse();
        assertThat(unidadService.buscarPorNombre("Caja").orElseThrow().isPermiteDecimales()).isFalse();
    }

    @Test
    void altaCambioYBajaSoloElAdmin() throws Exception {
        iniciarComoAdmin();
        Unidad docena = unidadService.guardar(new Unidad("Docena", "doc", false));
        assertThat(docena.getId()).isNotNull();

        docena.setNombre("Docena grande");
        assertThat(unidadService.guardar(docena).getNombre()).isEqualTo("Docena grande");
        assertThatThrownBy(() -> unidadService.guardar(new Unidad("PIEZA", null, false)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Ya existe");
        assertThatThrownBy(() -> unidadService.guardar(new Unidad("  ", null, false)))
                .isInstanceOf(NegocioException.class);

        unidadService.eliminar(docena.getId());
        assertThat(unidadService.buscarPorNombre("Docena grande")).isEmpty();

        iniciarComoCajero("cajeroUni", null);
        assertThatThrownBy(() -> unidadService.guardar(new Unidad("Pares", null, false)))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> unidadService.eliminar(1L)).isInstanceOf(NegocioException.class);
    }

    @Test
    void laUnidadDecideLosDecimalesDelProducto() throws Exception {
        iniciarComoAdmin();
        Unidad kg = unidadService.buscarPorNombre("Kilogramo").orElseThrow();
        Unidad pieza = unidadService.buscarPorNombre("Pieza").orElseThrow();

        Producto granel = productoConUnidad("Azúcar suelta", kg, Producto.TipoVenta.UNIDAD);
        assertThat(granel.getTipoVenta()).isEqualTo(Producto.TipoVenta.GRANEL);   // se ajusta a la unidad
        assertThat(granel.permiteDecimales()).isTrue();
        assertThat(ventaService.crearDetalle(granel, 1.25).getCantidad()).isEqualTo(1.25);

        Producto porPieza = productoConUnidad("Chicles", pieza, Producto.TipoVenta.GRANEL);
        assertThat(porPieza.getTipoVenta()).isEqualTo(Producto.TipoVenta.UNIDAD);
        assertThat(porPieza.permiteDecimales()).isFalse();
        assertThatThrownBy(() -> ventaService.crearDetalle(porPieza, 1.5)).isInstanceOf(NegocioException.class);

        // Sin unidad (productos anteriores) manda el tipo de venta, como siempre
        Producto sinUnidad = producto("Antiguo granel", 10, 10, Producto.TipoVenta.GRANEL, null);
        assertThat(sinUnidad.getUnidad()).isNull();
        assertThat(sinUnidad.permiteDecimales()).isTrue();
    }

    @Test
    void unaUnidadEnUsoNoSeBorraNiCambiaSusDecimales() throws Exception {
        iniciarComoAdmin();
        Unidad litro = unidadService.buscarPorNombre("Litro").orElseThrow();
        productoConUnidad("Leche suelta", litro, Producto.TipoVenta.GRANEL);

        assertThatThrownBy(() -> unidadService.eliminar(litro.getId()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("la usan");
        Unidad cambio = new Unidad("Litro", "L", false);
        cambio.setId(litro.getId());
        assertThatThrownBy(() -> unidadService.guardar(cambio))
                .isInstanceOf(NegocioException.class).hasMessageContaining("decimales");
        // renombrar sí se puede
        Unidad renombre = new Unidad("Litros", "L", true);
        renombre.setId(litro.getId());
        assertThat(unidadService.guardar(renombre).getNombre()).isEqualTo("Litros");
        assertThat(List.copyOf(unidadService.listar())).extracting(Unidad::getNombre).contains("Litros");
    }
}
