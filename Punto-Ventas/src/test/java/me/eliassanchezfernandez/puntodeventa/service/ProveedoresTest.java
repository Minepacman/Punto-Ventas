package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Catálogo de proveedores, entradas con proveedor, visitas del día y migración de los textos antiguos. */
class ProveedoresTest extends BaseServicioTest {

    @Autowired private ProveedorService proveedorService;
    @Autowired private ProveedorRepository proveedorRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private AuditoriaService auditoriaService;

    @BeforeEach
    void iniciar() throws Exception {
        iniciarComoAdmin();
    }

    private Proveedor proveedor(String nombre) {
        return proveedorService.guardar(null, nombre, null, null, null, Set.of(), null);
    }

    // ── Alta, edición y validación ───────────────────────────────────────

    @Test
    void creaYEditaUnProveedorConTodosSusDatos() {
        Proveedor p = proveedorService.guardar(null, "  Bimbo   del Centro ", "bim840101abc", "55 1234-5678", "Don Luis",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), "Pedidos antes de las 5");

        assertThat(p.getId()).isNotNull();
        assertThat(p.getNombre()).isEqualTo("Bimbo del Centro");        // sin espacios de sobra
        assertThat(p.getRfc()).isEqualTo("BIM840101ABC");
        assertThat(p.getDias()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
        assertThat(p.etiquetaDias()).isEqualTo("Lun, Jue");
        assertThat(p.isActivo()).isTrue();

        Proveedor editado = proveedorService.guardar(p.getId(), "Bimbo del Centro", null, "55 9999", "Doña Ana",
                EnumSet.of(DayOfWeek.FRIDAY), null);
        assertThat(editado.getContacto()).isEqualTo("Doña Ana");
        assertThat(editado.getRfc()).isNull();
        assertThat(editado.getDias()).containsExactly(DayOfWeek.FRIDAY);
        assertThat(auditoriaService.ultimas()).extracting(a -> a.getAccion())
                .contains("PROVEEDOR_CREADO", "PROVEEDOR_EDITADO");
    }

    @Test
    void validaNombreRfcTelefonoYDuplicados() {
        proveedor("Sabritas");

        assertThatThrownBy(() -> proveedor("  ")).isInstanceOf(NegocioException.class).hasMessageContaining("obligatorio");
        assertThatThrownBy(() -> proveedor("SABRITAS")).isInstanceOf(NegocioException.class).hasMessageContaining("Ya existe");
        assertThatThrownBy(() -> proveedor("  sábritas ")).isInstanceOf(NegocioException.class).hasMessageContaining("Ya existe");
        assertThatThrownBy(() -> proveedorService.guardar(null, "X", "CORTO", null, null, Set.of(), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("RFC");
        assertThatThrownBy(() -> proveedorService.guardar(null, "Y", null, "abc<script>", null, Set.of(), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("teléfono");
        assertThatThrownBy(() -> proveedor("n".repeat(151))).isInstanceOf(NegocioException.class);

        // Editar sin cambiar el nombre no choca consigo mismo
        Proveedor s = proveedorRepo.findByNombreIgnoreCase("sabritas").orElseThrow();
        assertThat(proveedorService.guardar(s.getId(), "Sabritas", null, null, "Pedro", Set.of(), null).getContacto())
                .isEqualTo("Pedro");
    }

    @Test
    void desactivarOcultaDelComboPeroConservaElProveedor() {
        Proveedor p = proveedor("Coca Cola");
        assertThat(proveedorService.listarActivos()).extracting(Proveedor::getNombre).contains("Coca Cola");

        proveedorService.cambiarActivo(p.getId(), false);

        assertThat(proveedorService.listarActivos()).extracting(Proveedor::getNombre).doesNotContain("Coca Cola");
        assertThat(proveedorService.listarTodos()).extracting(Proveedor::getNombre).contains("Coca Cola");
        assertThat(auditoriaService.ultimas()).extracting(a -> a.getAccion()).contains("PROVEEDOR_DESACTIVADO");
        proveedorService.cambiarActivo(p.getId(), true);
        assertThat(proveedorService.listarActivos()).extracting(Proveedor::getNombre).contains("Coca Cola");
    }

    // ── Permisos ──────────────────────────────────────────────────────────

    @Test
    void sinElPermisoNoSeAdministranProveedoresPeroSiSePuedenElegirEnUnaEntrada() throws Exception {
        proveedor("Lala");
        iniciarComoCajero("cajeraP", EnumSet.of(Permiso.AJUSTAR_INVENTARIO));

        assertThatThrownBy(() -> proveedor("Nuevo")).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> proveedorService.listarTodos()).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> proveedorService.cambiarActivo(1L, false)).isInstanceOf(PermisoRequeridoException.class);
        assertThat(proveedorService.listarActivos()).extracting(Proveedor::getNombre).contains("Lala");
    }

    @Test
    void elGerenteAdministraProveedoresPorDefecto() throws Exception {
        crearUsuario("gerenteP", Cajero.Rol.GERENTE, EnumSet.copyOf(Permiso.porDefecto(Cajero.Rol.GERENTE)));
        iniciarComo("gerenteP", CLAVE_PRUEBA);
        assertThat(proveedor("Pepsi").getId()).isNotNull();
    }

    // ── Visitas de hoy ────────────────────────────────────────────────────

    @Test
    void losQueVisitanHoySonLosActivosConEseDia() {
        DayOfWeek hoy = java.time.LocalDate.now().getDayOfWeek();
        DayOfWeek otro = hoy.plus(3);
        Proveedor visita = proveedorService.guardar(null, "Visita hoy", null, null, null, EnumSet.of(hoy, otro), null);
        proveedorService.guardar(null, "Visita otro día", null, null, null, EnumSet.of(otro), null);
        Proveedor inactivo = proveedorService.guardar(null, "Visita hoy pero inactivo", null, null, null, EnumSet.of(hoy), null);
        proveedorService.cambiarActivo(inactivo.getId(), false);

        assertThat(proveedorService.queVisitanHoy()).extracting(Proveedor::getNombre)
                .contains("Visita hoy").doesNotContain("Visita otro día", "Visita hoy pero inactivo");
        assertThat(proveedorService.queVisitanEn(otro)).extracting(Proveedor::getId).contains(visita.getId());
    }

    // ── Entrada de inventario con proveedor ──────────────────────────────

    @Test
    void laEntradaGuardaElProveedorDelCatalogoEnElMovimiento() {
        Proveedor prov = proveedor("Gamesa");
        Producto p = producto("Galletas", 20, 5);

        productoService.registrarEntradaDeProveedor(p.getId(), 10, prov.getId(), null, 8.5, null);

        assertThat(stockActual(p)).isEqualTo(15);
        MovimientoInventario m = productoService.movimientosDe(p.getId()).get(0);
        assertThat(m.getTipo()).isEqualTo(MovimientoInventario.Tipo.ENTRADA);
        assertThat(m.getProveedorId()).isEqualTo(prov.getId());
        assertThat(m.getProveedor()).isEqualTo("Gamesa");
        assertThat(m.getCostoUnitario()).isEqualTo(8.5);
    }

    @Test
    void noSePuedeRecibirDeUnProveedorDesactivadoNiInexistenteYLaEntradaDeTextoSigueFuncionando() {
        Proveedor prov = proveedor("Viejo");
        Producto p = producto("Pan", 10, 5);
        proveedorService.cambiarActivo(prov.getId(), false);

        assertThatThrownBy(() -> productoService.registrarEntradaDeProveedor(p.getId(), 1, prov.getId(), null, 1, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("desactivado");
        assertThatThrownBy(() -> productoService.registrarEntradaDeProveedor(p.getId(), 1, 99999L, null, 1, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no existe");
        assertThatThrownBy(() -> productoService.registrarEntradaDeProveedor(p.getId(), 1, null, null, 1, null))
                .isInstanceOf(NegocioException.class);

        productoService.registrarEntrada(p.getId(), 2, "Texto libre", 3, null);   // la forma antigua se conserva
        assertThat(stockActual(p)).isEqualTo(7);
    }

    // ── Migración de textos libres ───────────────────────────────────────

    private MovimientoInventario entradaConTexto(Producto p, String texto) {
        MovimientoInventario m = new MovimientoInventario();
        m.setProducto(p);
        m.setTipo(MovimientoInventario.Tipo.ENTRADA);
        m.setCantidad(1);
        m.setExistenciaAntes(0);
        m.setExistenciaDespues(1);
        m.setProveedor(texto);
        m.setFecha(LocalDateTime.now());
        return movimientoRepo.save(m);
    }

    @Test
    void laMigracionCreaUnProveedorPorNombreUnicoYLigaLasEntradasSinTocarElTexto() {
        Producto p = producto("Refresco", 20, 5);
        MovimientoInventario a = entradaConTexto(p, "Bimbo");
        MovimientoInventario b = entradaConTexto(p, "BIMBO ");
        MovimientoInventario c = entradaConTexto(p, "bímbo");
        MovimientoInventario d = entradaConTexto(p, "Sabritas");
        entradaConTexto(p, "   ");                       // vacío: se ignora
        entradaConTexto(p, null);

        int creados = proveedorService.migrarDesdeTextosDeEntradas();

        assertThat(creados).isEqualTo(2);
        Proveedor bimbo = proveedorRepo.findByNombreIgnoreCase("Bimbo").orElseThrow();
        Proveedor sabritas = proveedorRepo.findByNombreIgnoreCase("Sabritas").orElseThrow();
        assertThat(movimientoRepo.findById(a.getId()).orElseThrow().getProveedorId()).isEqualTo(bimbo.getId());
        assertThat(movimientoRepo.findById(b.getId()).orElseThrow().getProveedorId()).isEqualTo(bimbo.getId());
        assertThat(movimientoRepo.findById(c.getId()).orElseThrow().getProveedorId()).isEqualTo(bimbo.getId());
        assertThat(movimientoRepo.findById(d.getId()).orElseThrow().getProveedorId()).isEqualTo(sabritas.getId());
        // El texto original es historial: no cambia
        assertThat(movimientoRepo.findById(b.getId()).orElseThrow().getProveedor()).isEqualTo("BIMBO ");
        assertThat(movimientoRepo.findById(c.getId()).orElseThrow().getProveedor()).isEqualTo("bímbo");
        assertThat(auditoriaService.ultimas()).extracting(x -> x.getAccion()).contains("PROVEEDORES_MIGRADOS");
    }

    @Test
    void laMigracionEsIdempotenteYReusaLosProveedoresQueYaExisten() {
        Producto p = producto("Leche", 25, 5);
        Proveedor existente = proveedor("Lala");
        entradaConTexto(p, "LALA");
        entradaConTexto(p, "Alpura");

        assertThat(proveedorService.migrarDesdeTextosDeEntradas()).isEqualTo(1);     // solo Alpura
        assertThat(proveedorService.migrarDesdeTextosDeEntradas()).isZero();         // segunda vez: nada
        assertThat(proveedorRepo.findAll().stream().filter(x -> "lala".equalsIgnoreCase(x.getNombre()))).hasSize(1);
        assertThat(movimientoRepo.existsByProveedorId(existente.getId())).isTrue();
    }

    @Test
    void laMigracionCorreSinSesionYConNombresLargos() {
        Producto p = producto("Atún", 18, 5);
        entradaConTexto(p, "Distribuidora " + "X".repeat(200));
        sesionService.cerrarSesion();                    // el arranque no tiene usuario

        assertThat(proveedorService.migrarDesdeTextosDeEntradas()).isEqualTo(1);

        Proveedor largo = proveedorRepo.findAll().stream().filter(x -> x.getNombre().startsWith("Distribuidora")).findFirst().orElseThrow();
        assertThat(largo.getNombre().length()).isLessThanOrEqualTo(150);
    }

    @Test
    void sinEntradasConTextoLaMigracionNoCreaNada() {
        assertThat(proveedorService.migrarDesdeTextosDeEntradas()).isZero();
        assertThat(proveedorRepo.count()).isZero();
    }
}
