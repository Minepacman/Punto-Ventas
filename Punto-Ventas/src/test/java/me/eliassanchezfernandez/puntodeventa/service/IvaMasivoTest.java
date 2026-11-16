package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cambio de IVA de varios productos a la vez (Productos → "Cambiar IVA a los seleccionados…")
 * y "Aplicar el IVA del departamento a sus productos": permiso, una transacción y bitácora.
 */
class IvaMasivoTest extends BaseServicioTest {

    @Autowired private AuditoriaRepository auditoriaRepo;

    private List<Auditoria> bitacoraIva() {
        return auditoriaRepo.findByAccionOrderByFechaDesc(ProductoService.ACCION_IVA_CAMBIADO);
    }

    private TipoIva ivaDe(Producto p) {
        return productoRepo.findById(p.getId()).orElseThrow().getIvaOTienda();
    }

    @Test
    void cambiaElIvaDeLosSeleccionadosYDejaUnSoloRenglonEnLaBitacora() throws Exception {
        iniciarComoAdmin();
        Producto leche = producto("Leche", 28, 10);
        Producto huevo = producto("Huevo", 45, 10);
        Producto refresco = producto("Refresco", 20, 10);
        int antes = bitacoraIva().size();

        int cambiados = productoService.cambiarIva(List.of(leche.getId(), huevo.getId()), TipoIva.TASA_0);

        assertThat(cambiados).isEqualTo(2);
        assertThat(ivaDe(leche)).isEqualTo(TipoIva.TASA_0);
        assertThat(ivaDe(huevo)).isEqualTo(TipoIva.TASA_0);
        assertThat(ivaDe(refresco)).isEqualTo(TipoIva.TIENDA);   // no seleccionado: sin cambio
        // La existencia no se toca
        assertThat(productoRepo.findById(leche.getId()).orElseThrow().getCantidadActual()).isEqualTo(10);

        List<Auditoria> bitacora = bitacoraIva();
        assertThat(bitacora).hasSize(antes + 1);
        assertThat(bitacora.get(0).getDetalle())
                .contains("2 productos").contains("IVA 0 %").contains("Leche").contains("Huevo");
    }

    @Test
    void losQueYaTenianEseIvaNoCuentanNiSeAnotan() throws Exception {
        iniciarComoAdmin();
        Producto a = producto("A", 10, 1);
        Producto b = producto("B", 10, 1);
        productoService.cambiarIva(List.of(a.getId()), TipoIva.TASA_16);
        int antes = bitacoraIva().size();

        assertThat(productoService.cambiarIva(List.of(a.getId(), b.getId()), TipoIva.TASA_16)).isEqualTo(1);
        assertThat(productoService.cambiarIva(List.of(a.getId(), b.getId()), TipoIva.TASA_16)).isZero();
        assertThat(bitacoraIva()).hasSize(antes + 1);   // la segunda vez no cambió nada: sin renglón
    }

    @Test
    void sinPermisoDeEditarProductosNoCambiaNada() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Pan", 5, 1);
        iniciarComoCajero("caj" + UUID.randomUUID().toString().substring(0, 6), Set.of(Permiso.VER_HISTORIAL));

        assertThatThrownBy(() -> productoService.cambiarIva(List.of(p.getId()), TipoIva.EXENTO))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThat(ivaDe(p)).isEqualTo(TipoIva.TIENDA);
    }

    @Test
    void muchosProductosSeCambianEnLotesDentroDeLaMismaOperacion() throws Exception {
        iniciarComoAdmin();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 520; i++) {   // más que un lote de UPDATE
            Producto p = new Producto();
            p.setCodigoBarras("M-" + UUID.randomUUID().toString().substring(0, 10));
            p.setDescripcion("Masivo " + i);
            p.setPrecioVenta(10);
            p.setTipoVenta(Producto.TipoVenta.UNIDAD);
            p.setUsaInventario(false);
            ids.add(productoRepo.save(p).getId());
        }
        assertThat(productoService.cambiarIva(ids, TipoIva.EXENTO)).isEqualTo(520);
        assertThat(productoRepo.findAllById(ids)).allMatch(p -> p.getIva() == TipoIva.EXENTO);
        assertThat(bitacoraIva().get(0).getDetalle()).startsWith("520 productos");
    }

    @Test
    void aplicaElIvaDelDepartamentoATodosSusProductos() throws Exception {
        iniciarComoAdmin();
        Departamento frutas = departamento("Frutas");
        Producto manzana = producto("Manzana", 40, 1, Producto.TipoVenta.UNIDAD, frutas);
        Producto pera = producto("Pera", 40, 1, Producto.TipoVenta.UNIDAD, frutas);
        Producto otro = producto("Jabón", 20, 1);
        productoService.cambiarIva(List.of(pera.getId()), TipoIva.TASA_16);

        frutas.setIvaPorDefecto(TipoIva.TASA_0);
        frutas = productoService.guardarDepartamento(frutas);
        assertThat(productoService.aplicarIvaDelDepartamento(frutas.getId())).isEqualTo(2);
        assertThat(ivaDe(manzana)).isEqualTo(TipoIva.TASA_0);
        assertThat(ivaDe(pera)).isEqualTo(TipoIva.TASA_0);
        assertThat(ivaDe(otro)).isEqualTo(TipoIva.TIENDA);
        assertThat(bitacoraIva().get(0).getDetalle()).contains("departamento").contains(frutas.getNombre());
    }

    @Test
    void cambiarElIvaDesdeElFormularioTambienQuedaEnLaBitacora() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Tortilla", 22, 1);
        int antes = bitacoraIva().size();
        // Objeto nuevo con el mismo id, como lo arma la pantalla
        Producto edit = new Producto();
        edit.setId(p.getId());
        edit.setCodigoBarras(p.getCodigoBarras());
        edit.setDescripcion(p.getDescripcion());
        edit.setPrecioCosto(p.getPrecioCosto());
        edit.setPrecioVenta(p.getPrecioVenta());
        edit.setTipoVenta(p.getTipoVenta());
        edit.setUsaInventario(true);
        edit.setCantidadMinima(1);
        edit.setIva(TipoIva.TASA_0);
        productoService.guardar(edit);
        assertThat(bitacoraIva()).hasSize(antes + 1);
        assertThat(bitacoraIva().get(0).getDetalle()).contains("Tortilla").contains("IVA 0 %");
    }
}
