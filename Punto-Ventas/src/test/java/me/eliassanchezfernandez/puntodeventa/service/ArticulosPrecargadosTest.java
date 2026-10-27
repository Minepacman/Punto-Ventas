package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catálogo de ejemplo: solo con la BD sin productos. Sin transacción envolvente
 * y con contexto propio para partir de un catálogo realmente vacío.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArticulosPrecargadosTest {

    @Autowired private ArticulosPrecargadosService articulosService;
    @Autowired private ConfiguracionService        configuracionService;
    @Autowired private ProductoService             productoService;
    @Autowired private ProductoRepository          productoRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private SesionService               sesionService;
    @Autowired private CajeroService               cajeroService;
    @Autowired private CajeroRepository            cajeroRepo;

    @AfterEach
    void cerrar() {
        sesionService.cerrarSesion();
    }

    private void entrarComo(String usuario, String clave) throws Exception {
        var c = cajeroRepo.findByUsuarioIgnoreCase(usuario).orElseThrow();
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        sesionService.cerrarSesion();
        sesionService.autenticar(usuario, clave);
    }

    @Test
    void soloSeCargaConElCatalogoVacio() throws Exception {
        entrarComo("admin", "admin123");

        // un cajero no puede, aunque el catálogo esté vacío
        cajeroService.crear("cajeroArt", "Cajero Art", "clave123", me.eliassanchezfernandez.puntodeventa.model.Cajero.Rol.CAJERO, null);
        entrarComo("cajeroArt", "clave123");
        assertThatThrownBy(() -> articulosService.cargar()).isInstanceOf(NegocioException.class);
        assertThat(productoRepo.count()).isZero();

        entrarComo("admin", "admin123");
        assertThat(articulosService.puedeCargar()).isTrue();
        int creados = articulosService.cargar();

        assertThat(creados).isEqualTo(articulosService.cantidadDeMuestra()).isBetween(18, 22);
        assertThat(productoRepo.count()).isEqualTo(creados);
        assertThat(productoService.listarDepartamentos().size()).isBetween(3, 6);
        // pasan por ProductoService: existencia inicial con su movimiento
        assertThat(movimientoRepo.count()).isEqualTo(creados);
        // los de kilogramo quedan a granel y el resto por pieza
        var porTipo = productoRepo.findAll().stream().collect(Collectors.groupingBy(Producto::getTipoVenta, Collectors.counting()));
        assertThat(porTipo.get(Producto.TipoVenta.GRANEL)).isGreaterThanOrEqualTo(1);
        assertThat(productoRepo.findAll()).allSatisfy(p -> {
            assertThat(p.getUnidad()).isNotNull();
            assertThat(p.getPrecioVenta()).isPositive();
            assertThat(p.getDepartamento()).isNotNull();
        });

        // ya hay productos: no se ofrece ni se vuelve a cargar
        assertThat(articulosService.puedeCargar()).isFalse();
        assertThatThrownBy(() -> articulosService.cargar())
                .isInstanceOf(NegocioException.class).hasMessageContaining("catálogo vacío");
        assertThat(productoRepo.count()).isEqualTo(creados);
        // configuracionService sigue disponible para el resto de pruebas de la clase
        assertThat(configuracionService.isPermitirGranel()).isTrue();
    }
}
