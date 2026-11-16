package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.GrupoSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.PlanSurtido;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proveedor habitual del producto (Productos → Nuevo producto → Proveedor):
 * se guarda y se lee con el catálogo, la primera entrada de mercancía con proveedor
 * lo fija si estaba vacío (y nunca pisa lo elegido), Reabastecimiento lo prefiere
 * sobre el inferido de las entradas, el cambio queda en bitácora y la lista de
 * Productos no hace una consulta por producto.
 */
class ProveedorHabitualTest extends BaseServicioTest {

    @Autowired private ProveedorService proveedorService;
    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private ReabastecimientoService reabastecimientoService;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private AuditoriaRepository auditoriaRepo;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    private Proveedor bimbo, sabritas;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        bimbo = proveedorService.guardar(null, "Bimbo " + sufijo(), null, null, null, EnumSet.of(DayOfWeek.MONDAY), Set.of(), null);
        sabritas = proveedorService.guardar(null, "Sabritas " + sufijo(), null, null, null, Set.of(), Set.of(), null);
    }

    private static String sufijo() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    /** Producto nuevo tal como lo arma el formulario: solo el id del proveedor importa */
    private Producto nuevo(String descripcion, double stock, Proveedor proveedor) {
        Producto p = new Producto();
        p.setCodigoBarras("PH-" + sufijo());
        p.setDescripcion(descripcion);
        p.setPrecioCosto(5);
        p.setPrecioVenta(10);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(2);
        if (proveedor != null) {
            Proveedor soloId = new Proveedor();
            soloId.setId(proveedor.getId());
            p.setProveedor(soloId);
        }
        return p;
    }

    /** Copia "del formulario" de un producto guardado, con otro proveedor */
    private static Producto editado(Producto g, Proveedor proveedor) {
        Producto p = new Producto();
        p.setId(g.getId());
        p.setCodigoBarras(g.getCodigoBarras());
        p.setDescripcion(g.getDescripcion());
        p.setTipoVenta(g.getTipoVenta());
        p.setPrecioCosto(g.getPrecioCosto());
        p.setPrecioVenta(g.getPrecioVenta());
        p.setPrecioMayoreo(g.getPrecioMayoreo());
        p.setDepartamento(g.getDepartamento());
        p.setUnidad(g.getUnidad());
        p.setUsaInventario(g.isUsaInventario());
        p.setCantidadActual(g.getCantidadActual());
        p.setCantidadMinima(g.getCantidadMinima());
        p.setActivo(g.isActivo());
        p.setProveedor(proveedor);
        return p;
    }

    private Proveedor proveedorEnBd(Producto p) {
        em.flush();
        em.clear();
        return productoRepo.findById(p.getId()).orElseThrow().getProveedor();
    }

    private static String nombre(Proveedor p) {
        return p == null ? null : p.getNombre();
    }

    // ── Guardar ───────────────────────────────────────────────────────────

    @Test
    void seGuardaConOSinProveedorYLaListaDeProductosLoTrae() {
        Producto sin = productoService.guardar(nuevo("Sin proveedor", 0, null));
        Producto con = productoService.guardar(nuevo("Con proveedor", 0, bimbo));

        assertThat(proveedorEnBd(sin)).isNull();
        assertThat(nombre(proveedorEnBd(con))).isEqualTo(bimbo.getNombre());

        em.clear();
        List<Producto> lista = productoService.listarTodos();
        Producto enLista = lista.stream().filter(p -> p.getId().equals(con.getId())).findFirst().orElseThrow();
        assertThat(enLista.getProveedor().getNombre()).isEqualTo(bimbo.getNombre());
        assertThat(lista.stream().filter(p -> p.getId().equals(sin.getId())).findFirst().orElseThrow().getProveedor()).isNull();
    }

    @Test
    void laExistenciaInicialQuedaLigadaAlProveedorElegido() {
        Producto con = productoService.guardar(nuevo("Pan de caja", 12, bimbo));

        List<MovimientoInventario> movimientos = movimientoRepo.findByProductoIdOrderByFechaDesc(con.getId());
        assertThat(movimientos).hasSize(1);
        MovimientoInventario alta = movimientos.get(0);
        assertThat(alta.getTipo()).isEqualTo(MovimientoInventario.Tipo.ALTA_INICIAL);
        assertThat(alta.getProveedor()).isEqualTo(bimbo.getNombre());
        assertThat(alta.getProveedorId()).isEqualTo(bimbo.getId());

        // Sin proveedor elegido, el alta inicial sigue sin proveedor (como siempre)
        Producto sin = productoService.guardar(nuevo("Sal", 3, null));
        assertThat(movimientoRepo.findByProductoIdOrderByFechaDesc(sin.getId()).get(0).getProveedor()).isNull();
    }

    @Test
    void rechazaUnProveedorInexistenteODesactivadoPeroConservaElQueElProductoYaTenia() {
        Proveedor fantasma = new Proveedor();
        fantasma.setId(999_999L);
        Producto conFantasma = nuevo("Fantasma", 0, null);
        conFantasma.setProveedor(fantasma);
        assertThatThrownBy(() -> productoService.guardar(conFantasma))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya no existe");

        Producto p = productoService.guardar(nuevo("Papas", 0, sabritas));
        proveedorService.cambiarActivo(sabritas.getId(), false);

        // Editar el producto sin tocar su proveedor (desactivado) se permite...
        Producto g = productoService.guardar(editado(p, sabritas));
        assertThat(nombre(proveedorEnBd(g))).isEqualTo(sabritas.getNombre());

        // ...pero asignarlo a otro producto, no
        assertThatThrownBy(() -> productoService.guardar(nuevo("Otro", 0, sabritas)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("desactivado");

        // El nombre sigue disponible aunque esté desactivado (la tabla lo muestra)
        em.clear();
        Producto enLista = productoService.listarTodos().stream().filter(x -> x.getId().equals(p.getId())).findFirst().orElseThrow();
        assertThat(enLista.getProveedor().getNombre()).isEqualTo(sabritas.getNombre());
        assertThat(enLista.getProveedor().isActivo()).isFalse();
    }

    @Test
    void cambiarElProveedorAlEditarQuedaEnBitacoraYSoloExigeEditarProductos() throws Exception {
        Producto p = productoService.guardar(nuevo("Galletas", 0, bimbo));
        auditoriaRepo.deleteAll();

        productoService.guardar(editado(p, sabritas));
        List<Auditoria> cambios = auditoriaRepo.findByAccionOrderByFechaDesc("PRODUCTO_PROVEEDOR_CAMBIADO");
        assertThat(cambios).hasSize(1);
        assertThat(cambios.get(0).getDetalle()).contains(p.getCodigoBarras()).contains(bimbo.getNombre()).contains(sabritas.getNombre());

        // Guardar sin cambiar el proveedor no agrega nada a la bitácora
        productoService.guardar(editado(productoRepo.findById(p.getId()).orElseThrow(), sabritas));
        assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("PRODUCTO_PROVEEDOR_CAMBIADO")).hasSize(1);

        // Quitarlo también se registra
        productoService.guardar(editado(productoRepo.findById(p.getId()).orElseThrow(), null));
        assertThat(proveedorEnBd(p)).isNull();
        assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("PRODUCTO_PROVEEDOR_CAMBIADO")).hasSize(2);
        assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("PRODUCTO_PROVEEDOR_CAMBIADO").get(0).getDetalle()).contains("ninguno");

        // Un usuario con EDITAR_PRODUCTOS (sin GESTIONAR_PROVEEDORES ni VER_GANANCIAS) puede elegir el proveedor
        Producto otro = productoService.guardar(nuevo("Jugo", 0, null));
        iniciarComoCajero("editor" + sufijo(), EnumSet.of(me.eliassanchezfernandez.puntodeventa.model.Permiso.EDITAR_PRODUCTOS));
        productoService.guardar(editado(otro, bimbo));
        assertThat(nombre(proveedorEnBd(otro))).isEqualTo(bimbo.getNombre());
    }

    // ── Autollenado desde las entradas ────────────────────────────────────

    @Test
    void laPrimeraEntradaConProveedorLoFijaYLasSiguientesNoLoPisan() {
        Producto p = productoService.guardar(nuevo("Tortillas", 0, null));

        // Entrada con texto libre (móvil): no hay proveedor del catálogo, nada que fijar
        productoService.registrarEntrada(p.getId(), 2, "No indicado (móvil)", 0, null);
        assertThat(proveedorEnBd(p)).isNull();

        productoService.registrarEntradaDeProveedor(p.getId(), 5, bimbo.getId(), null, 4, null);
        assertThat(nombre(proveedorEnBd(p))).isEqualTo(bimbo.getNombre());

        // Una entrada posterior de otro proveedor NO cambia el habitual
        productoService.registrarEntradaDeProveedor(p.getId(), 5, sabritas.getId(), null, 4, null);
        assertThat(nombre(proveedorEnBd(p))).isEqualTo(bimbo.getNombre());
    }

    @Test
    void unaEntradaNuncaPisaElProveedorQueEligioElUsuario() {
        Producto p = productoService.guardar(nuevo("Refresco", 0, sabritas));
        productoService.registrarEntradaDeProveedor(p.getId(), 10, bimbo.getId(), null, 8, null);
        assertThat(nombre(proveedorEnBd(p))).isEqualTo(sabritas.getNombre());
    }

    @Test
    void recibirUnaOrdenDeCompraFijaElProveedorDeLosProductosQueNoTenian() {
        Producto sinProveedor = productoService.guardar(nuevo("Cacahuates", 0, null));
        Producto conProveedor = productoService.guardar(nuevo("Chicharrones", 0, bimbo));
        OrdenCompra orden = ordenCompraService.crear(sabritas.getId(), List.of(
                new OrdenCompraService.LineaOrden(sinProveedor.getId(), 10, 7),
                new OrdenCompraService.LineaOrden(conProveedor.getId(), 4, 9)), null);

        ordenCompraService.recibir(orden.getId(), null);

        assertThat(nombre(proveedorEnBd(sinProveedor))).isEqualTo(sabritas.getNombre());
        assertThat(nombre(proveedorEnBd(conProveedor))).isEqualTo(bimbo.getNombre());   // ya lo tenía: no se toca
    }

    // ── Reabastecimiento ──────────────────────────────────────────────────

    @Test
    void elPlanDeSurtidoPrefiereElProveedorHabitualYSiNoHayUsaElDeLasEntradas() {
        // Habitual Sabritas, aunque la última entrada venga de Bimbo
        Producto papas = productoService.guardar(nuevo("Papas adobadas", 0, sabritas));
        productoService.registrarEntradaDeProveedor(papas.getId(), 1, bimbo.getId(), null, 5, null);
        // Sin habitual elegido: la primera entrada (Bimbo) lo fija y el plan lo agrupa ahí
        Producto pan = productoService.guardar(nuevo("Pan blanco", 0, null));
        productoService.registrarEntradaDeProveedor(pan.getId(), 1, bimbo.getId(), null, 5, null);
        // Sin ninguna entrada ni proveedor: va al grupo sin proveedor
        Producto sal = productoService.guardar(nuevo("Sal de mesa", 1, null));

        PlanSurtido plan = reabastecimientoService.planSurtido();
        assertThat(grupoDe(plan, papas).nombre()).isEqualTo(sabritas.getNombre());
        assertThat(grupoDe(plan, pan).nombre()).isEqualTo(bimbo.getNombre());
        assertThat(grupoDe(plan, sal).nombre()).startsWith(ReabastecimientoService.SIN_PROVEEDOR);
        assertThat(grupoDe(plan, pan).horizonteDias()).isEqualTo(7);   // Bimbo visita 1 día a la semana
    }

    private static GrupoSurtido grupoDe(PlanSurtido plan, Producto p) {
        return plan.grupos().stream()
                .filter(g -> g.lineas().stream().anyMatch(l -> l.producto().getId().equals(p.getId())))
                .findFirst().orElseThrow();
    }

    // ── Conteo por proveedor (página móvil) ───────────────────────────────

    @Test
    void cuentaLosProductosActivosDeCadaProveedor() {
        productoService.guardar(nuevo("A", 0, bimbo));
        productoService.guardar(nuevo("B", 0, bimbo));
        Producto inactivo = productoService.guardar(nuevo("C", 0, bimbo));
        productoService.cambiarActivo(inactivo.getId(), false);
        productoService.guardar(nuevo("D", 0, null));

        Map<Long, Long> cuenta = productoService.productosActivosPorProveedor();
        assertThat(cuenta.get(bimbo.getId())).isEqualTo(2);
        assertThat(cuenta.get(sabritas.getId())).isNull();
    }

    // ── Sin N+1 ───────────────────────────────────────────────────────────

    @Test
    void listarLosProductosNoHaceUnaConsultaPorProveedor() {
        for (int i = 0; i < 6; i++) productoService.guardar(nuevo("Producto " + i, 0, i % 2 == 0 ? bimbo : sabritas));
        productoService.guardar(nuevo("Suelto", 0, null));
        em.flush();
        em.clear();

        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean estabanActivas = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            List<Producto> lista = productoService.listarTodos();
            for (Producto p : lista) if (p.getProveedor() != null) p.getProveedor().getNombre();   // lo que hace la tabla
            assertThat(lista.size()).isGreaterThanOrEqualTo(7);
            // Una consulta con JOIN FETCH; nunca una por producto
            assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
        } finally {
            stats.setStatisticsEnabled(estabanActivas);
        }
    }
}
