package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas SIN transacción envolvente: cada operación se confirma de verdad en
 * la BD desechable, como en la aplicación. Cubren los borrados que dejarían
 * referencias rotas (SQLite no valida llaves foráneas) y que las pantallas
 * que leen esos datos siguen funcionando. Al terminar la clase se descarta el
 * contexto (y con él la BD create-drop) para no contaminar a las demás.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IntegridadSinTransaccionTest {

    @Autowired private SesionService      sesionService;
    @Autowired private CajeroService      cajeroService;
    @Autowired private ProductoService    productoService;
    @Autowired private PromocionService   promocionService;
    @Autowired private VentaService       ventaService;
    @Autowired private CajaService        cajaService;
    @Autowired private CajeroRepository   cajeroRepo;
    @Autowired private ProductoRepository productoRepo;

    @AfterEach
    void cerrar() {
        sesionService.cerrarSesion();
    }

    private Cajero entrarComo(String usuario, String clave) throws Exception {
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(usuario).orElseThrow();
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        sesionService.cerrarSesion();
        return sesionService.autenticar(usuario, clave);
    }

    private Producto producto(String descripcion, double precio, double stock, Departamento depto) {
        Producto p = new Producto();
        p.setCodigoBarras("NT-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(descripcion);
        p.setPrecioCosto(precio / 2);
        p.setPrecioVenta(precio);
        p.setDepartamento(depto);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    @Test
    void unUsuarioConHistorialNoSeEliminaYElHistorialSigueCargando() throws Exception {
        entrarComo("admin", "admin123");
        String login = "hist" + UUID.randomUUID().toString().substring(0, 6);
        Cajero cajero = cajeroService.crear(login, "Cajero Con Historial", "clave123", Cajero.Rol.CAJERO,
                EnumSet.of(Permiso.HACER_CORTE));
        Producto p = producto("Vendido por el cajero", 25, 10, null);

        // El cajero vende, registra el fondo del día y hace un corte: queda referenciado en 4 tablas
        entrarComo(login, "clave123");
        if (cajaService.requiereFondo(cajero)) cajaService.registrarFondo(sesionService.getCajeroActual(), 100);
        Venta venta = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), Venta.FormaPago.EFECTIVO, 50);
        cajaService.hacerCorte(150, null);

        entrarComo("admin", "admin123");
        assertThatThrownBy(() -> cajeroService.eliminar(cajero.getId()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Desactívalo");
        assertThat(cajeroRepo.findById(cajero.getId())).isPresent();

        // Lo que la pantalla principal y el historial cargan sigue funcionando
        assertThat(ventaService.resumenDelDia().numVentas).isGreaterThanOrEqualTo(1);
        assertThat(ventaService.ventasEntre(LocalDate.now(), LocalDate.now()))
                .filteredOn(v -> v.getId().equals(venta.getId())).first()
                .extracting(v -> v.getCajero().getUsuario()).isEqualTo(login);
        assertThat(cajaService.cortesDeHoy()).isNotEmpty();

        // La alternativa correcta: desactivarlo
        cajeroService.cambiarEstado(cajero.getId(), false);
        assertThat(cajeroRepo.findById(cajero.getId()).orElseThrow().isActivo()).isFalse();

        // Un usuario sin historial sí se elimina
        Cajero sinHistorial = cajeroService.crear("tmp" + UUID.randomUUID().toString().substring(0, 6),
                "Temporal", "clave123", Cajero.Rol.CAJERO);
        cajeroService.eliminar(sinHistorial.getId());
        assertThat(cajeroRepo.findById(sinHistorial.getId())).isEmpty();
    }

    @Test
    void unDepartamentoConPromocionNoSeEliminaYLasPromocionesSiguenCargando() throws Exception {
        entrarComo("admin", "admin123");
        Departamento d = new Departamento();
        d.setNombre("Promo " + UUID.randomUUID().toString().substring(0, 6));
        d = productoService.guardarDepartamento(d);

        Promocion promo = new Promocion();
        promo.setDepartamento(d);
        promo.setTipo(Promocion.Tipo.PORCENTAJE);
        promo.setValor(10);
        promo = promocionService.guardar(promo);

        final Departamento depto = d;
        assertThatThrownBy(() -> productoService.eliminarDepartamento(depto))
                .isInstanceOf(NegocioException.class).hasMessageContaining("promociones");

        final Long promoId = promo.getId();
        assertThat(promocionService.listarTodas()).filteredOn(x -> x.getId().equals(promoId)).first()
                .extracting(x -> x.getDepartamento().getNombre()).isEqualTo(d.getNombre());

        // Sin promociones ni productos sí se elimina
        promocionService.eliminar(promo.getId());
        productoService.eliminarDepartamento(d);
        assertThat(productoService.listarDepartamentos()).extracting(Departamento::getId).doesNotContain(d.getId());
    }

    @Test
    void unProductoConMovimientosNoSeEliminaYElReporteSigueCargando() throws Exception {
        entrarComo("admin", "admin123");
        Producto p = producto("Con movimientos", 30, 4, null);   // ALTA_INICIAL
        productoService.registrarEntrada(p.getId(), 6, "Proveedor NT", 12, null);

        assertThatThrownBy(() -> productoService.eliminar(p.getId()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Desactívalo");
        assertThat(productoRepo.findById(p.getId())).isPresent();
        assertThat(productoService.movimientosEntre(LocalDate.now(), LocalDate.now()))
                .filteredOn(m -> m.getProducto().getId().equals(p.getId())).hasSize(2);

        productoService.cambiarActivo(p.getId(), false);
        assertThat(productoRepo.findById(p.getId()).orElseThrow().isActivo()).isFalse();
    }
}
