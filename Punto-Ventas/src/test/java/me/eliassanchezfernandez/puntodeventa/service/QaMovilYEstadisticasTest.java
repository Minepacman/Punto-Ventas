package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.controller.estadisticas.Tarjetas;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.GrupoSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.PlanSurtido;
import me.eliassanchezfernandez.puntodeventa.servidor.RelojMutable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** QA v1.0.0 — defectos (ya corregidos) en el acceso móvil y en Estadísticas / Reabastecimiento. */
class QaMovilYEstadisticasTest extends BaseServicioTest {

    @Autowired private SesionMovilService      sesionMovilService;
    @Autowired private ReabastecimientoService reabastecimientoService;
    @Autowired private EstadisticasService     estadisticasService;
    @Autowired private AgregadosService        agregadosService;
    @Autowired private ProveedorService        proveedorService;
    @Autowired private VentaRepository         ventaRepo;

    private Clock relojOriginal;

    @AfterEach
    void restaurarReloj() {
        if (relojOriginal != null) ReflectionTestUtils.setField(sesionMovilService, "reloj", relojOriginal);
    }

    private Venta venta(LocalDateTime fecha, Producto p, double cantidad) {
        Venta v = new Venta();
        v.setFecha(fecha);
        DetalleVenta d = new DetalleVenta();
        d.setVenta(v);
        d.setProducto(p);
        d.setCantidad(cantidad);
        d.setPrecioVenta(p.getPrecioVenta());
        v.getDetalles().add(d);
        v.setTotal(p.getPrecioVenta() * cantidad);
        v.setPagoCon(v.getTotal());
        return ventaRepo.save(v);
    }

    /**
     * M-11 (medio, móvil). El usuario eligió "mantener la sesión siempre
     * abierta", lleva 3 días usando el teléfono y apaga el interruptor. El
     * servidor fijaba expira = creada + 48 h (ya en el pasado) y la sesión se
     * cerraba al instante. Ahora las 48 h cuentan desde ese momento.
     */
    @Test
    void apagarSesionPermanenteDespuesDe48hNoDebeCerrarlaAlInstante() throws Exception {
        Cajero c = iniciarComoAdmin();
        relojOriginal = (Clock) ReflectionTestUtils.getField(sesionMovilService, "reloj");
        RelojMutable reloj = new RelojMutable(Instant.now());
        ReflectionTestUtils.setField(sesionMovilService, "reloj", reloj);

        SesionMovilService.Emitida e = sesionMovilService.emitir(c, true, "127.0.0.1", "ua");
        reloj.avanzar(Duration.ofDays(3));
        assertThat(sesionMovilService.validar(e.token())).isPresent();

        sesionMovilService.fijarPermanente(e.sesion().getId(), false);

        assertThat(sesionMovilService.validar(e.token()))
                .as("al quitar 'permanente' la sesión debe durar 48 h desde ahora, no cerrarse ya")
                .isPresent();
        reloj.avanzar(Duration.ofHours(47));
        assertThat(sesionMovilService.validar(e.token())).isPresent();
        reloj.avanzar(Duration.ofHours(2));
        assertThat(sesionMovilService.validar(e.token())).isEmpty();
    }

    /**
     * M-12 (medio, móvil). Una entrada hecha desde el teléfono se registra con
     * proveedor "No indicado (móvil)"; Reabastecimiento tomaba la última entrada
     * como proveedor del producto y, al no existir en el catálogo, el producto
     * caía en "Sin proveedor". Ahora se usa la entrada más reciente cuyo
     * proveedor exista en el catálogo.
     */
    @Test
    void unaEntradaDesdeElMovilNoDebeQuitarleElProveedorAlProducto() throws Exception {
        iniciarComoAdmin();
        Proveedor bimbo = proveedorService.guardar(null, "Bimbo QA2", null, null, "Don Luis",
                EnumSet.of(DayOfWeek.MONDAY), null);
        Producto pan = producto("Pan QA", 40, 0);
        pan.setCantidadMinima(5);
        productoRepo.save(pan);
        productoService.registrarEntradaDeProveedor(pan.getId(), 6, bimbo.getId(), "Surtido", 20, List.of());
        for (int i = 9; i >= 0; i--) venta(LocalDate.now().minusDays(i).atTime(10, 0), pan, 2);
        agregadosService.recalcularTodo();
        assertThat(grupo(reabastecimientoService.planSurtido(), "Bimbo QA2")).isNotNull();

        productoService.registrarEntrada(pan.getId(), 1, "No indicado (móvil)", 0, null);   // lo que manda el servidor móvil

        PlanSurtido plan = reabastecimientoService.planSurtido();
        assertThat(grupo(plan, "Bimbo QA2"))
                .as("el producto debe seguir en el grupo de su proveedor real")
                .isNotNull();
        assertThat(grupo(plan, "Bimbo QA2").lineas()).anyMatch(l -> l.producto().getId().equals(pan.getId()));
    }

    private static GrupoSurtido grupo(PlanSurtido plan, String nombre) {
        return plan.grupos().stream().filter(g -> g.nombre().equals(nombre)).findFirst().orElse(null);
    }

    /**
     * M-13 (medio, Estadísticas). Un producto dado de alta HOY que nunca se ha
     * vendido aparecía en "Productos rezagados" con "Sin ventas registradas en N
     * días" (N = días de vida de la tienda). Ahora los días sin venta de un
     * producto nunca vendido no pueden ser más que los días desde su alta
     * (primer movimiento de inventario).
     */
    @Test
    void unProductoRecienDadoDeAltaNoDebeSalirComoRezagado() throws Exception {
        iniciarComoAdmin();
        Producto pan = producto("Pan QA3", 40, 50);
        for (int i = 30; i >= 0; i--) venta(LocalDate.now().minusDays(i).atTime(10, 0), pan, 1);
        agregadosService.recalcularTodo();
        Producto nuevo = producto("Galletas nuevas", 15, 100);   // alta de hoy, sin ventas

        ReabastecimientoService.Rezagados r = reabastecimientoService.productosRezagados(7);
        assertThat(r.productos())
                .as("un producto con horas de creado no lleva 'N días sin venderse'")
                .noneMatch(x -> x.producto().getId().equals(nuevo.getId()));
    }

    /**
     * B-13 (bajo, Estadísticas). Residuos de coma flotante en existencias a
     * granel se formateaban como "3." (la coma quedaba colgando).
     */
    @Test
    void lasCantidadesConResiduoDeComaFlotanteNoDebenMostrarPuntoColgante() {
        assertThat(Tarjetas.cantidad(3.0000000000000004)).isEqualTo("3");
        assertThat(Tarjetas.cantidad(2.9999999999999996)).isEqualTo("3");
        assertThat(Tarjetas.cantidad(2.5)).isEqualTo("2.5");
        assertThat(Tarjetas.cantidad(0.125)).isEqualTo("0.125");
        assertThat(Tarjetas.cantidad(12)).isEqualTo("12");
    }

    /**
     * B-14 (bajo, Estadísticas). La madurez de datos contaba desde la primera
     * venta aunque estuviera cancelada: una tienda cuya única venta se canceló ya
     * aparecía "con ventas" y Rezagados empezaba a marcar productos.
     */
    @Test
    void unaVentaCanceladaNoDebeContarComoPrimeraVentaDeLaTienda() throws Exception {
        iniciarComoAdmin();
        Assumptions.assumeFalse(estadisticasService.madurezActual().hayVentas(),
                "la BD de pruebas ya tiene ventas activas de otras pruebas");
        Producto p = producto("Pan QA4", 40, 50);
        Venta v = venta(LocalDate.now().minusDays(5).atTime(10, 0), p, 1);
        v.setCancelada(true);
        v.setFechaCancelacion(LocalDateTime.now());
        ventaRepo.save(v);

        assertThat(estadisticasService.madurezActual().hayVentas())
                .as("una venta cancelada no es una venta")
                .isFalse();

        venta(LocalDate.now().minusDays(2).atTime(10, 0), p, 1);
        assertThat(estadisticasService.madurezActual().hayVentas()).isTrue();
        assertThat(estadisticasService.madurezActual().diasDesdePrimeraVenta()).isEqualTo(2);
    }
}
