package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validaciones del catálogo, inventario y departamentos. */
@SpringBootTest
@Transactional
class ProductoServiceTest {

    @Autowired private ProductoService    productoService;
    @Autowired private ProductoRepository productoRepo;

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

    // ── Guardar / validar ─────────────────────────────────────────────────

    @Test
    void guardaYBuscaPorCodigo() {
        Producto p = productoService.guardar(nuevoProducto("Coca Cola 600ml", 18, 10, 2));

        assertThat(p.getId()).isNotNull();
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
    }

    // ── Inventario ────────────────────────────────────────────────────────

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
    void agregarYAjustarStock() {
        Producto p = productoService.guardar(nuevoProducto("Arroz", 30, 10, 2));

        productoService.agregarStock(p.getId(), 5);
        assertThat(productoRepo.findById(p.getId()).orElseThrow().getCantidadActual()).isEqualTo(15);

        productoService.ajustarStock(p.getId(), 3);
        assertThat(productoRepo.findById(p.getId()).orElseThrow().getCantidadActual()).isEqualTo(3);

        assertThatThrownBy(() -> productoService.agregarStock(p.getId(), 0))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> productoService.ajustarStock(p.getId(), -1))
                .isInstanceOf(NegocioException.class);
    }

    // ── Departamentos ─────────────────────────────────────────────────────

    @Test
    void departamentoDuplicadoYEnUsoSonRechazados() {
        Departamento d = new Departamento();
        d.setNombre("Abarrotes " + UUID.randomUUID().toString().substring(0, 6));
        d = productoService.guardarDepartamento(d);

        Departamento repetido = new Departamento();
        repetido.setNombre(d.getNombre().toUpperCase());
        assertThatThrownBy(() -> productoService.guardarDepartamento(repetido))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Ya existe");

        Producto p = nuevoProducto("Frijol", 25, 5, 1);
        p.setDepartamento(d);
        productoService.guardar(p);

        final Departamento enUso = d;
        assertThatThrownBy(() -> productoService.eliminarDepartamento(enUso))
                .isInstanceOf(NegocioException.class).hasMessageContaining("pertenecen");
    }
}
