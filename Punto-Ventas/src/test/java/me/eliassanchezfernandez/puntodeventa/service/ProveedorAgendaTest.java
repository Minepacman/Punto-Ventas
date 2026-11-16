package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService.Agenda;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService.ResumenProveedor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agenda de proveedores: días de entrega y de preventa, próxima visita, nota media
 * (gasto promedio por visita en 90 días) y quién puede ver o cambiar qué.
 */
class ProveedorAgendaTest extends BaseServicioTest {

    @Autowired private ProveedorService proveedorService;
    @Autowired private ProveedorRepository proveedorRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private AuditoriaService auditoriaService;

    private static final LocalDate MIERCOLES = LocalDate.of(2026, 11, 11);   // 2026-11-11 es miércoles

    // ── Próxima fecha (puro, sin BD) ─────────────────────────────────────

    @Test
    void laProximaFechaIncluyeHoyYDaLaVueltaALaSemana() {
        assertThat(MIERCOLES.getDayOfWeek()).isEqualTo(WEDNESDAY);

        assertThat(ProveedorService.proximaFecha(EnumSet.of(WEDNESDAY), MIERCOLES)).as("hoy cuenta").isEqualTo(MIERCOLES);
        assertThat(ProveedorService.proximaFecha(EnumSet.of(MONDAY, FRIDAY), MIERCOLES)).isEqualTo(MIERCOLES.plusDays(2));   // viernes 13
        assertThat(ProveedorService.proximaFecha(EnumSet.of(MONDAY), MIERCOLES)).as("da la vuelta").isEqualTo(MIERCOLES.plusDays(5)); // lunes 16
        assertThat(ProveedorService.proximaFecha(EnumSet.of(TUESDAY), MIERCOLES)).as("ayer → la semana que viene").isEqualTo(MIERCOLES.plusDays(6));
        assertThat(ProveedorService.proximaFecha(EnumSet.of(SUNDAY, SATURDAY), MIERCOLES)).isEqualTo(MIERCOLES.plusDays(3));   // sábado antes que domingo
        assertThat(ProveedorService.proximaFecha(Set.of(), MIERCOLES)).isNull();
        assertThat(ProveedorService.proximaFecha(null, MIERCOLES)).isNull();
    }

    @Test
    void losDiasDePreventaSeGuardanApartDeLosDeEntrega() throws Exception {
        iniciarComoAdmin();
        Proveedor p = proveedorService.guardar(null, "Sabritas agenda", null, null, "Preventista Paco",
                EnumSet.of(THURSDAY), EnumSet.of(TUESDAY), "Levanta pedido martes, entrega jueves");

        Proveedor leido = proveedorRepo.findById(p.getId()).orElseThrow();
        assertThat(leido.getDias()).containsExactly(THURSDAY);
        assertThat(leido.getPreventa()).containsExactly(TUESDAY);
        assertThat(leido.getDiasPreventa()).isEqualTo("2");
        assertThat(leido.etiquetaPreventa()).isEqualTo("Mar");
        assertThat(leido.preventaEn(TUESDAY)).isTrue();
        assertThat(leido.visitaEn(TUESDAY)).as("la preventa no es una entrega").isFalse();
        assertThat(auditoriaService.ultimas().get(0).getDetalle()).contains("preventa=Mar").contains("entrega=Jue");

        // El guardar "anterior" (importación, código viejo) conserva la preventa al editar
        Proveedor editado = proveedorService.guardar(p.getId(), "Sabritas agenda", null, "55 1111", null, EnumSet.of(FRIDAY), null);
        assertThat(editado.getDias()).containsExactly(FRIDAY);
        assertThat(editado.getPreventa()).as("no se perdió").containsExactly(TUESDAY);

        // Quitar la preventa deja la columna en null (sin preventa)
        Proveedor sinPreventa = proveedorService.guardar(p.getId(), "Sabritas agenda", null, null, null, EnumSet.of(FRIDAY), Set.of(), null);
        assertThat(sinPreventa.getPreventa()).isEmpty();
        assertThat(sinPreventa.getDiasPreventa()).isNull();
        assertThat(sinPreventa.etiquetaPreventa()).isEqualTo("—");
    }

    // ── Agenda: próximas visitas y nota media ────────────────────────────

    private ResumenProveedor resumenDe(Agenda a, Proveedor p) {
        return a.proveedores().stream().filter(r -> r.proveedor().getId().equals(p.getId())).findFirst().orElseThrow();
    }

    /** Atrasa la fecha del movimiento más reciente de ese producto */
    private void atrasarUltimaEntrada(Producto producto, int dias) {
        MovimientoInventario m = movimientoRepo.findByProductoIdOrderByFechaDesc(producto.getId()).get(0);
        m.setFecha(LocalDateTime.now().minusDays(dias).withHour(10));
        movimientoRepo.saveAndFlush(m);
    }

    @Test
    void laAgendaCalculaProximasVisitasYLaNotaMediaPorVisita() throws Exception {
        iniciarComoAdmin();
        LocalDate hoy = LocalDate.now();
        DayOfWeek diaHoy = hoy.getDayOfWeek();
        DayOfWeek pasado = hoy.plusDays(2).getDayOfWeek();
        DayOfWeek ayer = hoy.minusDays(1).getDayOfWeek();

        Proveedor bimbo = proveedorService.guardar(null, "Bimbo agenda", null, null, null, EnumSet.of(diaHoy, pasado), EnumSet.of(ayer), null);
        Proveedor lala = proveedorService.guardar(null, "Lala agenda", null, null, null, EnumSet.of(pasado), Set.of(), null);
        Proveedor sinDias = proveedorService.guardar(null, "Sin días agenda", null, null, null, Set.of(), Set.of(), null);
        Proveedor inactivo = proveedorService.guardar(null, "Inactivo agenda", null, null, null, EnumSet.of(diaHoy), Set.of(), null);
        proveedorService.cambiarActivo(inactivo.getId(), false);

        Producto pan = producto("Pan agenda", 30, 0);
        Producto leche = producto("Leche agenda", 25, 0);
        // Nota 1 (hoy): dos entradas de Bimbo el mismo día = una sola nota de 10×12 + 5×4 = 140
        productoService.registrarEntradaDeProveedor(pan.getId(), 10, bimbo.getId(), null, 12, null);
        productoService.registrarEntradaDeProveedor(leche.getId(), 5, bimbo.getId(), null, 4, null);
        // Nota 2 (hace 10 días): 6 × 10 = 60
        productoService.registrarEntradaDeProveedor(pan.getId(), 6, bimbo.getId(), null, 10, null);
        atrasarUltimaEntrada(pan, 10);
        // Fuera de la ventana (hace 100 días): no cuenta aunque sea carísima
        productoService.registrarEntradaDeProveedor(pan.getId(), 100, bimbo.getId(), null, 99, null);
        atrasarUltimaEntrada(pan, 100);
        // Lala solo tiene una entrada SIN costo: no dice nada del gasto
        productoService.registrarEntradaDeProveedor(leche.getId(), 3, lala.getId(), null, 0, null);

        Agenda a = proveedorService.agenda();
        assertThat(a.hoy()).isEqualTo(hoy);
        assertThat(a.costosVisibles()).as("el admin ve costos").isTrue();
        assertThat(a.puedeEditar()).isTrue();
        assertThat(a.proveedores()).extracting(r -> r.proveedor().getNombre())
                .contains("Bimbo agenda", "Lala agenda", "Sin días agenda")
                .doesNotContain("Inactivo agenda");

        ResumenProveedor rb = resumenDe(a, bimbo);
        assertThat(rb.proximaEntrega()).as("entrega hoy").isEqualTo(hoy);
        assertThat(rb.proximaPreventa()).as("la preventa de 'ayer' cae dentro de 6 días").isEqualTo(hoy.plusDays(6));
        assertThat(rb.notaMedia()).isNotNull();
        assertThat(rb.notaMedia().notas()).as("dos visitas en 90 días").isEqualTo(2);
        assertThat(rb.notaMedia().promedio()).isEqualTo(100.0);          // (140 + 60) / 2
        assertThat(rb.notaMedia().ultima()).isEqualTo(hoy);

        ResumenProveedor rl = resumenDe(a, lala);
        assertThat(rl.proximaEntrega()).isEqualTo(hoy.plusDays(2));
        assertThat(rl.proximaPreventa()).as("sin preventa").isNull();
        assertThat(rl.notaMedia()).as("sin entradas con costo: sin datos aún").isNull();

        ResumenProveedor rs = resumenDe(a, sinDias);
        assertThat(rs.proximaEntrega()).isNull();
        assertThat(rs.proximaPreventa()).isNull();
        assertThat(rs.notaMedia()).isNull();
    }

    @Test
    void laOrdenDeCompraRecibidaCuentaComoUnaNotaSinDuplicarse() throws Exception {
        iniciarComoAdmin();
        Proveedor prov = proveedorService.guardar(null, "Orden agenda", null, null, null, EnumSet.of(MONDAY), Set.of(), null);
        Producto p1 = producto("Galletas agenda", 20, 0);
        Producto p2 = producto("Refresco agenda", 18, 0);
        var orden = ordenCompraService.crear(prov.getId(), List.of(
                new OrdenCompraService.LineaOrden(p1.getId(), 10, 8),            // 80
                new OrdenCompraService.LineaOrden(p2.getId(), 24, 10)), null);   // 240
        ordenCompraService.recibir(orden.getId(), null);

        // Las dos líneas entran el mismo día con su costo: una sola nota de 320, no dos ni el doble
        ResumenProveedor r = resumenDe(proveedorService.agenda(), prov);
        assertThat(r.notaMedia()).isNotNull();
        assertThat(r.notaMedia().notas()).isEqualTo(1);
        assertThat(r.notaMedia().promedio()).isEqualTo(320.0);
    }

    @Autowired private OrdenCompraService ordenCompraService;

    // ── Permisos ─────────────────────────────────────────────────────────

    @Test
    void elCajeroVeLosDiasPeroNoLosCostosNiPuedeGuardar() throws Exception {
        iniciarComoAdmin();
        Proveedor prov = proveedorService.guardar(null, "Permisos agenda", null, null, null, EnumSet.of(MONDAY), EnumSet.of(SUNDAY), null);
        Producto p = producto("Jabón agenda", 15, 0);
        productoService.registrarEntradaDeProveedor(p.getId(), 4, prov.getId(), null, 7.5, null);

        iniciarComoCajero("cajero_agenda", Set.of());
        Agenda a = proveedorService.agenda();
        assertThat(a.costosVisibles()).isFalse();
        assertThat(a.puedeEditar()).isFalse();
        ResumenProveedor r = resumenDe(a, prov);
        assertThat(r.proveedor().getDias()).containsExactly(MONDAY);
        assertThat(r.proveedor().getPreventa()).containsExactly(SUNDAY);
        assertThat(r.proximaEntrega()).isNotNull();
        assertThat(r.notaMedia()).as("sin VER_GANANCIAS ni GESTIONAR_PROVEEDORES no hay nota media").isNull();
        assertThatThrownBy(() -> proveedorService.guardar(null, "Intruso", null, null, null, Set.of(), Set.of(), null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> proveedorService.guardar(prov.getId(), "Permisos agenda", null, null, null, EnumSet.of(FRIDAY), Set.of(), null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThat(proveedorRepo.findById(prov.getId()).orElseThrow().getDias()).as("no cambió nada").containsExactly(MONDAY);

        // Con VER_GANANCIAS ve la nota media aunque no gestione proveedores
        iniciarComoCajero("ganancias_agenda", Set.of(Permiso.VER_GANANCIAS));
        Agenda g = proveedorService.agenda();
        assertThat(g.costosVisibles()).isTrue();
        assertThat(g.puedeEditar()).isFalse();
        assertThat(resumenDe(g, prov).notaMedia().promedio()).isEqualTo(30.0);

        // Con GESTIONAR_PROVEEDORES ve costos y edita
        iniciarComoCajero("gestor_agenda", Set.of(Permiso.GESTIONAR_PROVEEDORES));
        Agenda ge = proveedorService.agenda();
        assertThat(ge.costosVisibles()).isTrue();
        assertThat(ge.puedeEditar()).isTrue();
        Proveedor editado = proveedorService.guardar(prov.getId(), "Permisos agenda", null, null, null, EnumSet.of(FRIDAY), EnumSet.of(WEDNESDAY), null);
        assertThat(editado.getDias()).containsExactly(FRIDAY);
        assertThat(editado.getPreventa()).containsExactly(WEDNESDAY);
    }

    @Test
    void sinSesionNoHayAgenda() {
        sesionService.cerrarSesion();
        assertThatThrownBy(() -> proveedorService.agenda()).isInstanceOf(RuntimeException.class);
    }
}
