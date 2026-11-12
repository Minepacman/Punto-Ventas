package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El filtro en memoria del buscador F6 coincide con el criterio de buscar(): código o descripción, sin mayúsculas */
class ProductoServiceFiltrarTest {

    private static Producto p(String codigo, String descripcion) {
        Producto x = new Producto();
        x.setCodigoBarras(codigo);
        x.setDescripcion(descripcion);
        return x;
    }

    private final List<Producto> catalogo = List.of(
            p("7501000001", "Coca-Cola 600 ml"), p("7501000002", "Leche Lala 1 L"), p("ABC-1", "Pan Bimbo"), p(null, null));

    @Test
    void textoVacioONuloDevuelveTodoEnUnaListaNueva() {
        assertThat(ProductoService.filtrar(catalogo, null)).containsExactlyElementsOf(catalogo).isNotSameAs(catalogo);
        assertThat(ProductoService.filtrar(catalogo, "   ")).hasSize(4);
    }

    @Test
    void coincidePorDescripcionOCodigoSinDistinguirMayusculasYRecortando() {
        assertThat(ProductoService.filtrar(catalogo, "COCA")).extracting(Producto::getDescripcion).containsExactly("Coca-Cola 600 ml");
        assertThat(ProductoService.filtrar(catalogo, " 100000 ")).hasSize(2);
        assertThat(ProductoService.filtrar(catalogo, "abc")).extracting(Producto::getCodigoBarras).containsExactly("ABC-1");
        assertThat(ProductoService.filtrar(catalogo, "zzz")).isEmpty();
    }
}
