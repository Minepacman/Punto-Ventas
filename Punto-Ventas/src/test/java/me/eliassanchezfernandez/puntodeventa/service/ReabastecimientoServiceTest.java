package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.CriterioTop;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.MadurezDatos;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.RankingProductos;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.GrupoSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.LineaSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.PlanSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.ProductoRezagado;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.Rezagados;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.Urgencia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Reabastecimiento sobre la base de pruebas: madurez de datos, plan de surtido
 * agrupado por proveedor (última entrada) o por departamento, orden borrador
 * desde el plan, rezagados, ranking por piezas y permisos.
 */
class ReabastecimientoServiceTest extends BaseServicioTest {

    @Autowired private ReabastecimientoService reabastecimientoService;
    @Autowired private EstadisticasService estadisticasService;
    @Autowired private AgregadosService agregadosService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository movimientoRepo;

    private final LocalDate hoy = LocalDate.now();
    private Proveedor bimbo;
    private Departamento lacteos;
    private Producto pan, leche, chicles, sal;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        bimbo = proveedorService.guardar(null, "Bimbo", null, null, "Don Luis", EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), null);
        lacteos = departamento("Lácteos");

        pan = producto("Pan blanco", 40, 0);            // costo 20; lo surte Bimbo (entrada): queda en 6, mínimo 5
        pan.setCantidadMinima(5);
        productoRepo.save(pan);
        productoService.registrarEntradaDeProveedor(pan.getId(), 6, bimbo.getId(), "Surtido", 20, List.of());

        leche = producto("Leche 1 L", 25, 50, Producto.TipoVenta.UNIDAD, lacteos);   // sin proveedor; mucha existencia
        leche.setCantidadMinima(5);
        productoRepo.save(leche);

        chicles = producto("Chicles", 5, 200);          // nunca se vende; está en inventario desde el primer día
        chicles.setCantidadMinima(2);
        productoRepo.save(chicles);
        for (var m : movimientoRepo.findByProductoIdOrderByFechaDesc(chicles.getId())) {
            m.setFecha(hoy.minusDays(9).atTime(9, 0));   // alta inicial el mismo día que la primera venta
            movimientoRepo.save(m);
        }

        sal = producto("Sal 1 kg", 12, 1000);           // se vendió 1 vez: rotación bajísima
        sal.setCantidadMinima(1);
        productoRepo.save(sal);

        // 10 días de ventas (hoy−9 … hoy): pan 2 al día, leche 1 al día; sal 1 pieza el primer día
        for (int i = 9; i >= 0; i--) {
            LocalDate dia = hoy.minusDays(i);
            venta(dia.atTime(10, 0), pan, 2);
            venta(dia.atTime(11, 0), leche, 1);
            if (i == 9) venta(dia.atTime(12, 0), sal, 1);
        }
        agregadosService.recalcularTodo();
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

    private static GrupoSurtido grupo(PlanSurtido plan, String nombre) {
        return plan.grupos().stream().filter(g -> g.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private static LineaSurtido linea(GrupoSurtido g, Producto p) {
        return g.lineas().stream().filter(l -> l.producto().getId().equals(p.getId())).findFirst().orElseThrow();
    }

    // ── Madurez ───────────────────────────────────────────────────────────

    @Test
    void laMadurezCuentaLosDiasDesdeLaPrimeraVentaYLosDiasConVentas() {
        MadurezDatos m = estadisticasService.madurezActual();
        assertThat(m.diasDesdePrimeraVenta()).isEqualTo(9);
        assertThat(m.diasConVentas()).isEqualTo(10);           // 9 agregados + hoy en vivo
        assertThat(m.confiable()).isFalse();
        assertThat(m.diasFaltantes()).isEqualTo(21);
        assertThat(m.porcentajeConfianza()).isEqualTo(30);
        assertThat(m.mensaje()).contains("Datos de 9 días").contains("10 con ventas");
    }

    // ── Plan de surtido ───────────────────────────────────────────────────

    @Test
    void elPlanAgrupaPorProveedorDeLaUltimaEntradaYPorDepartamentoSinProveedor() {
        PlanSurtido plan = reabastecimientoService.planSurtido();
        assertThat(plan.diasVentana()).isEqualTo(10);          // 9 días desde la primera venta + hoy
        assertThat(plan.conCostos()).isTrue();                 // admin ve costos
        assertThat(plan.grupos()).extracting(GrupoSurtido::nombre)
                .contains("Bimbo", "Sin proveedor · " + lacteos.getNombre(), "Sin proveedor · Sin departamento");
        // Proveedores primero, los "sin proveedor" al final
        assertThat(plan.grupos().get(0).nombre()).isEqualTo("Bimbo");

        GrupoSurtido deBimbo = grupo(plan, "Bimbo");
        assertThat(deBimbo.tieneProveedor()).isTrue();
        assertThat(deBimbo.horizonteDias()).isEqualTo(4);      // visita lunes y jueves
        assertThat(deBimbo.diasVisita()).contains("Lun").contains("Jue");
        assertThat(deBimbo.lineas()).hasSize(1);

        // Pan: 20 piezas en 10 días = 2 al día; existencia 6 (3 días), mínimo 5, próximo surtido en 4 días
        LineaSurtido p = linea(deBimbo, pan);
        assertThat(p.ventaDiaria()).isCloseTo(2.0, within(0.001));
        assertThat(p.diasCobertura()).isCloseTo(3.0, within(0.001));
        assertThat(p.urgencia()).isEqualTo(Urgencia.PRONTO);
        assertThat(p.pedido()).isEqualTo(7);                   // objetivo 13 − 6
        assertThat(p.frecuenciaDias()).isEqualTo(4);
        assertThat(p.costoPedido()).isCloseTo(140, within(0.001));
        assertThat(deBimbo.productosAPedir()).isEqualTo(1);
        assertThat(deBimbo.costoPedido()).isCloseTo(140, within(0.001));

        // Leche: 1 al día, 50 piezas → alcanza 50 días; surtir cada 7; nada que pedir
        LineaSurtido l = linea(grupo(plan, "Sin proveedor · " + lacteos.getNombre()), leche);
        assertThat(l.urgencia()).isEqualTo(Urgencia.OK);
        assertThat(l.pedido()).isZero();
        assertThat(l.diasCobertura()).isCloseTo(50, within(0.001));
        assertThat(l.frecuenciaDias()).isEqualTo(7);

        // Chicles: sin ventas → sin cobertura ni frecuencia, nada que pedir
        LineaSurtido c = linea(grupo(plan, "Sin proveedor · Sin departamento"), chicles);
        assertThat(c.ventaDiaria()).isZero();
        assertThat(c.diasCobertura()).isNull();
        assertThat(c.frecuenciaDias()).isNull();
        assertThat(c.urgencia()).isEqualTo(Urgencia.OK);

        assertThat(plan.productosAPedir()).isEqualTo(1);
        assertThat(plan.costoPedido()).isCloseTo(140, within(0.001));
    }

    @Test
    void unProductoAgotadoYUnoBajoElMinimoSalenPrimeroEnSuGrupo() {
        pan.setCantidadActual(0);
        productoRepo.save(pan);
        Producto tortillas = producto("Tortillinas", 30, 2);
        tortillas.setCantidadMinima(4);
        productoRepo.save(tortillas);
        productoService.registrarEntradaDeProveedor(tortillas.getId(), 1, bimbo.getId(), null, 15, List.of());  // queda en 3 < 4

        GrupoSurtido deBimbo = grupo(reabastecimientoService.planSurtido(), "Bimbo");
        assertThat(deBimbo.lineas()).extracting(LineaSurtido::urgencia)
                .containsExactly(Urgencia.AGOTADO, Urgencia.BAJO_MINIMO);
        assertThat(linea(deBimbo, pan).pedido()).isEqualTo(13);          // objetivo 13 − 0
        assertThat(linea(deBimbo, tortillas).pedido()).isEqualTo(5);     // objetivo 8 − 3
        assertThat(deBimbo.productosAPedir()).isEqualTo(2);
    }

    @Test
    void crearOrdenBorradorDesdeElPlanUsaSoloLoQueHayQuePedir() {
        GrupoSurtido deBimbo = grupo(reabastecimientoService.planSurtido(), "Bimbo");
        OrdenCompra o = reabastecimientoService.crearOrdenBorrador(bimbo.getId(), deBimbo.lineas(), "Desde Estadísticas");

        assertThat(o.getEstado()).isEqualTo(OrdenCompra.Estado.BORRADOR);
        assertThat(o.getProveedor().getId()).isEqualTo(bimbo.getId());
        assertThat(o.getLineas()).hasSize(1);
        assertThat(o.getLineas().get(0).getProducto().getId()).isEqualTo(pan.getId());
        assertThat(o.getLineas().get(0).getCantidad()).isEqualTo(7);
        assertThat(o.getLineas().get(0).getCostoUnitario()).isEqualTo(20);
        assertThat(ordenCompraService.obtener(o.getId()).getNotas()).isEqualTo("Desde Estadísticas");

        // Un grupo sin nada que pedir no genera orden
        GrupoSurtido lacteosGrupo = grupo(reabastecimientoService.planSurtido(), "Sin proveedor · " + lacteos.getNombre());
        assertThatThrownBy(() -> reabastecimientoService.crearOrdenBorrador(bimbo.getId(), lacteosGrupo.lineas(), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("No hay productos que pedir");
    }

    // ── Rezagados ─────────────────────────────────────────────────────────

    @Test
    void losRezagadosIncluyenRotacionBajaYSinVentaSegunElUmbral() {
        // Con 30 días de umbral y solo 9 de historial, nadie lleva 30 días sin venta; la sal sí es rotación baja
        Rezagados r = reabastecimientoService.productosRezagados(30);
        assertThat(r.productos()).extracting(x -> x.producto().getId()).containsExactly(sal.getId());
        ProductoRezagado s = r.productos().get(0);
        assertThat(s.motivo()).contains("Rotación baja");
        assertThat(s.ultimaVenta()).isEqualTo(hoy.minusDays(9));
        assertThat(s.diasSinVenta()).isEqualTo(9);
        assertThat(s.piezasVentana()).isCloseTo(1, within(0.001));
        assertThat(s.diasCobertura()).isCloseTo(10000, within(0.5));    // 1000 piezas ÷ 0.1 al día
        assertThat(s.valorVenta()).isCloseTo(12000, within(0.001));
        assertThat(s.valorCosto()).isCloseTo(6000, within(0.001));
        assertThat(r.conCostos()).isTrue();
        assertThat(r.totalValorCosto()).isCloseTo(6000, within(0.001));

        // Con umbral 5 entran también los chicles (nunca vendidos: cuentan desde la primera venta de la tienda,
        // o desde su alta si es posterior; ver unProductoRecienDadoDeAltaNoEsRezagado en QaMovilYEstadisticasTest)
        Rezagados r5 = reabastecimientoService.productosRezagados(5);
        assertThat(r5.productos()).extracting(x -> x.producto().getId()).containsExactlyInAnyOrder(sal.getId(), chicles.getId());
        ProductoRezagado c = r5.productos().stream().filter(x -> x.producto().getId().equals(chicles.getId())).findFirst().orElseThrow();
        assertThat(c.ultimaVenta()).isNull();
        assertThat(c.diasSinVenta()).isEqualTo(9);
        assertThat(c.motivo()).contains("Sin ventas registradas");
        // Lo que más dinero tiene detenido va primero
        assertThat(r5.productos().get(0).producto().getId()).isEqualTo(sal.getId());

        // Pan y leche se vendieron hoy: nunca son rezagados
        assertThat(r5.productos()).extracting(x -> x.producto().getId()).doesNotContain(pan.getId(), leche.getId());
    }

    // ── Ranking por piezas ────────────────────────────────────────────────

    @Test
    void elRankingPuedeOrdenarsePorPiezasConPorcentajeDelTotal() {
        Periodo p = Periodo.entre(hoy.minusDays(9), hoy);
        RankingProductos porImporte = estadisticasService.ranking(p, 10, CriterioTop.IMPORTE);
        assertThat(porImporte.productos().get(0).producto().getId()).isEqualTo(pan.getId());      // 20 × 40 = 800
        assertThat(porImporte.importeTotal()).isCloseTo(800 + 250 + 12, within(0.001));
        assertThat(porImporte.porcentaje(porImporte.productos().get(0))).isCloseTo(800.0 / 1062 * 100, within(0.01));

        RankingProductos porPiezas = estadisticasService.ranking(p, 10, CriterioTop.PIEZAS);
        assertThat(porPiezas.productos()).extracting(t -> t.producto().getId())
                .containsExactly(pan.getId(), leche.getId(), sal.getId());                          // 20, 10, 1
        assertThat(porPiezas.piezasTotal()).isCloseTo(31, within(0.001));
        assertThat(porPiezas.porcentaje(porPiezas.productos().get(1))).isCloseTo(10.0 / 31 * 100, within(0.01));
    }

    // ── Permisos ──────────────────────────────────────────────────────────

    @Test
    void sinVerHistorialNoHayPlanNiRezagadosYSinVerCostosNoSalenCostos() throws Exception {
        iniciarComoCajero("cajero", EnumSet.noneOf(Permiso.class));
        assertThatThrownBy(() -> reabastecimientoService.planSurtido()).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> reabastecimientoService.productosRezagados(30)).isInstanceOf(PermisoRequeridoException.class);

        iniciarComoAdmin();
        crearUsuario("encargada", Cajero.Rol.CAJERO, Set.of(Permiso.VER_HISTORIAL));
        iniciarComo("encargada", CLAVE_PRUEBA);
        PlanSurtido plan = reabastecimientoService.planSurtido();
        assertThat(plan.conCostos()).isFalse();
        assertThat(linea(grupo(plan, "Bimbo"), pan).pedido()).isEqualTo(7);     // el pedido sí
        assertThat(linea(grupo(plan, "Bimbo"), pan).costoPedido()).isZero();    // el costo no
        assertThat(plan.costoPedido()).isZero();
        Rezagados r = reabastecimientoService.productosRezagados(30);
        assertThat(r.conCostos()).isFalse();
        assertThat(r.productos().get(0).valorCosto()).isZero();
        assertThat(r.productos().get(0).valorVenta()).isCloseTo(12000, within(0.001));

        // Crear la orden exige GESTIONAR_PROVEEDORES aunque se vea el plan
        GrupoSurtido deBimbo = grupo(plan, "Bimbo");
        assertThatThrownBy(() -> reabastecimientoService.crearOrdenBorrador(bimbo.getId(), deBimbo.lineas(), null))
                .isInstanceOf(PermisoRequeridoException.class);
    }
}
