package me.eliassanchezfernandez.puntodeventa.importacion;

import me.eliassanchezfernandez.puntodeventa.importacion.OpcionesImportacion.Duplicados;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.HistorialPrecio;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.BaseServicioTest;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Importación de productos y proveedores contra la base de datos de pruebas */
class ImportacionServiceTest extends BaseServicioTest {

    static final Path RECURSOS = Path.of("src/test/resources/importacion");

    @Autowired private ImportacionService servicio;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private DepartamentoRepository departamentoRepo;
    @Autowired private AuditoriaRepository auditoriaRepo;
    @Autowired private ProveedorRepository proveedorRepo;

    @TempDir Path tmp;

    private PlanImportacion preparar(String archivo, Duplicados dup, boolean existencias) throws IOException {
        TablaImportada t = servicio.leer(RECURSOS.resolve(archivo));
        Origen o = Origen.detectar(t.encabezados(true));
        Mapeo m = o.mapeoAutomatico(t.encabezados(true), CampoProducto.values());
        return servicio.preparar(t, o, m, new OpcionesImportacion(dup, true, existencias, true));
    }

    private Producto porCodigo(String codigo) {
        return productoRepo.findByCodigoBarras(codigo).orElseThrow();
    }

    // ── Alta completa ────────────────────────────────────────────────────

    @Test
    void importaCatalogoDeAbarrotesPdvConDepartamentosMovimientosYBitacora() throws Exception {
        iniciarComoAdmin();
        PlanImportacion plan = preparar("abarrotes-pdv.csv", Duplicados.OMITIR, true);
        assertThat(plan.aCrear()).isEqualTo(5);
        assertThat(plan.departamentosNuevos()).containsExactly("Refrescos", "Botanas", "Lácteos", "Jugos");

        AtomicInteger ultimoProgreso = new AtomicInteger();
        ResumenImportacion r = servicio.aplicar(plan, List.of(), ultimoProgreso::set);

        assertThat(r.creados()).isEqualTo(5);
        assertThat(r.actualizados()).isZero();
        assertThat(r.errores()).isEmpty();
        assertThat(r.departamentosCreados()).isEqualTo(4);
        assertThat(r.movimientosInventario()).as("4 con existencia > 0 (el jugo venía en -2 → 0)").isEqualTo(4);
        assertThat(ultimoProgreso.get()).isEqualTo(5);

        Producto coca = porCodigo("7501055300136");
        assertThat(coca.getDescripcion()).isEqualTo("Coca-Cola 600 ml");
        assertThat(coca.getPrecioCosto()).isEqualTo(12.5);
        assertThat(coca.getPrecioVenta()).isEqualTo(18);
        assertThat(coca.getPrecioMayoreo()).isEqualTo(16.5);
        assertThat(coca.getDepartamento().getNombre()).isEqualTo("Refrescos");
        assertThat(coca.getCantidadActual()).isEqualTo(24);
        assertThat(coca.getCantidadMinima()).isEqualTo(6);
        assertThat(coca.isActivo()).isTrue();
        assertThat(coca.isUsaInventario()).isTrue();
        List<MovimientoInventario> movs = movimientoRepo.findByProductoIdOrderByFechaDesc(coca.getId());
        assertThat(movs).hasSize(1);
        assertThat(movs.get(0).getTipo()).isEqualTo(MovimientoInventario.Tipo.ALTA_INICIAL);
        assertThat(movs.get(0).getCantidad()).isEqualTo(24);
        assertThat(movs.get(0).getExistenciaDespues()).isEqualTo(24);
        assertThat(movs.get(0).getMotivo()).contains("Abarrotes PDV").contains("abarrotes-pdv.csv");
        assertThat(movs.get(0).getUsuarioNombre()).isNotBlank();

        Producto pan = porCodigo("7501000111112");
        assertThat(pan.getDepartamento()).as("- Sin Departamento -").isNull();
        Producto jugo = porCodigo("7501055363506");
        assertThat(jugo.getCantidadActual()).isZero();
        assertThat(movimientoRepo.findByProductoIdOrderByFechaDesc(jugo.getId())).isEmpty();

        assertThat(departamentoRepo.findByNombreIgnoreCase("Lácteos")).isPresent();
        List<Auditoria> bitacora = auditoriaRepo.findByAccionOrderByFechaDesc("IMPORTACION_PRODUCTOS");
        assertThat(bitacora).hasSize(1);
        assertThat(bitacora.get(0).getDetalle()).contains("abarrotes-pdv.csv").contains("5 productos nuevos");
    }

    @Test
    void segundaImportacionOmiteActualizaPreciosOActualizaTodoSegunLaOpcion() throws Exception {
        iniciarComoAdmin();
        servicio.aplicar(preparar("abarrotes-pdv.csv", Duplicados.OMITIR, true), List.of(), null);
        Producto coca = porCodigo("7501055300136");
        long idCoca = coca.getId();

        // Omitir: nada cambia
        ResumenImportacion omitir = servicio.aplicar(preparar("eleventa-export.csv", Duplicados.OMITIR, true), List.of(), null);
        assertThat(omitir.omitidos()).isEqualTo(2);
        assertThat(omitir.creados()).as("jitomate y azúcar son nuevos").isEqualTo(2);
        assertThat(porCodigo("7501055300136").getId()).isEqualTo(idCoca);
        assertThat(porCodigo("2001").permiteDecimales()).as("GRANEL de eleventa → unidad Kilogramo").isTrue();
        assertThat(porCodigo("2001").getUnidad().getNombre()).isEqualTo("Kilogramo");
        assertThat(porCodigo("2001").getClaveUnidad()).isEqualTo("KGM");
        assertThat(porCodigo("2001").getClaveProdServ()).isEqualTo("50405700");

        // Solo precios: con un archivo de precios nuevos
        Path precios = tmp.resolve("precios.csv");
        Files.writeString(precios, "Código,Descripción,Precio venta,Existencia\n7501055300136,OTRA DESCRIPCION,19.5,999\n", StandardCharsets.UTF_8);
        TablaImportada t = servicio.leer(precios);
        Mapeo m = Mapeo.automatico(t.encabezados(true), CampoProducto.values());
        PlanImportacion planPrecios = servicio.preparar(t, Origen.GENERICO, m, new OpcionesImportacion(Duplicados.ACTUALIZAR_PRECIOS, true, true, true));
        assertThat(planPrecios.requiereCambiarPrecios()).isTrue();
        assertThat(planPrecios.requiereAjustarInventario()).as("solo precios: la existencia no se toca").isFalse();
        ResumenImportacion rp = servicio.aplicar(planPrecios, List.of(), null);
        assertThat(rp.actualizados()).isEqualTo(1);
        assertThat(rp.cambiosDePrecio()).isEqualTo(1);
        Producto cocaP = porCodigo("7501055300136");
        assertThat(cocaP.getPrecioVenta()).isEqualTo(19.5);
        assertThat(cocaP.getDescripcion()).isEqualTo("Coca-Cola 600 ml");
        assertThat(cocaP.getCantidadActual()).isEqualTo(24);
        List<HistorialPrecio> historial = productoService.historialPrecios(idCoca);
        assertThat(historial).hasSize(1);
        assertThat(historial.get(0).getPrecioAnterior()).isEqualTo(18);
        assertThat(historial.get(0).getPrecioNuevo()).isEqualTo(19.5);

        // Todo: descripción y existencia (ajuste auditado)
        PlanImportacion planTodo = servicio.preparar(t, Origen.GENERICO, m, new OpcionesImportacion(Duplicados.ACTUALIZAR_TODO, true, true, true));
        assertThat(planTodo.requiereAjustarInventario()).isTrue();
        ResumenImportacion rt = servicio.aplicar(planTodo, List.of(), null);
        assertThat(rt.actualizados()).isEqualTo(1);
        assertThat(rt.movimientosInventario()).isEqualTo(1);
        Producto cocaT = porCodigo("7501055300136");
        assertThat(cocaT.getDescripcion()).isEqualTo("OTRA DESCRIPCION");
        assertThat(cocaT.getCantidadActual()).isEqualTo(999);
        List<MovimientoInventario> movs = movimientoRepo.findByProductoIdOrderByFechaDesc(idCoca);
        assertThat(movs).hasSize(2);
        assertThat(movs.get(0).getTipo()).isEqualTo(MovimientoInventario.Tipo.AJUSTE);
        assertThat(movs.get(0).getCantidad()).isEqualTo(975);
        assertThat(movs.get(0).getExistenciaAntes()).isEqualTo(24);
        assertThat(movs.get(0).getExistenciaDespues()).isEqualTo(999);
    }

    // ── Permisos ─────────────────────────────────────────────────────────

    @Test
    void cajeroSinAjustarInventarioNecesitaAutorizacionParaImportarExistencias() throws Exception {
        iniciarComoCajero("importador", EnumSet.of(Permiso.EDITAR_PRODUCTOS));
        PlanImportacion plan = preparar("abarrotes-pdv.csv", Duplicados.OMITIR, true);
        assertThatThrownBy(() -> servicio.aplicar(plan, List.of(), null))
                .isInstanceOf(PermisoRequeridoException.class)
                .extracting(e -> ((PermisoRequeridoException) e).getPermiso()).isEqualTo(Permiso.AJUSTAR_INVENTARIO);
        assertThat(productoRepo.findByCodigoBarras("7501055300136")).as("nada se escribió").isEmpty();

        // Sin existencias sí puede (solo EDITAR_PRODUCTOS)
        PlanImportacion sinExistencias = preparar("abarrotes-pdv.csv", Duplicados.OMITIR, false);
        assertThat(sinExistencias.requiereAjustarInventario()).isFalse();
        ResumenImportacion r = servicio.aplicar(sinExistencias, List.of(), null);
        assertThat(r.creados()).isEqualTo(5);
        assertThat(porCodigo("7501055300136").getCantidadActual()).isZero();
        assertThat(movimientoRepo.findByProductoIdOrderByFechaDesc(porCodigo("7501055300136").getId())).isEmpty();

        // Con autorización de admin se importan existencias y queda el autorizador en cada movimiento
        Autorizacion aut = autorizacionService.autorizar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA, Permiso.AJUSTAR_INVENTARIO);
        PlanImportacion todo = preparar("abarrotes-pdv.csv", Duplicados.ACTUALIZAR_TODO, true);
        assertThat(todo.requiereCambiarPrecios()).as("mismos precios").isFalse();
        ResumenImportacion r2 = servicio.aplicar(todo, List.of(aut), null);
        assertThat(r2.movimientosInventario()).isEqualTo(4);
        assertThat(aut.isConsumida()).as("de un solo uso, se consume al terminar toda la importación").isTrue();
        MovimientoInventario m = movimientoRepo.findByProductoIdOrderByFechaDesc(porCodigo("7501055300136").getId()).get(0);
        assertThat(m.getAutorizadoPor()).isNotBlank();
        assertThat(m.getUsuarioNombre()).contains("importador");
        Auditoria a = auditoriaRepo.findByAccionOrderByFechaDesc("IMPORTACION_PRODUCTOS").get(0);
        assertThat(a.getAutorizadoPor()).isNotNull();
    }

    @Test
    void cajeroSinCambiarPreciosNoPuedeActualizarPreciosYSinEditarProductosNoPuedeNada() throws Exception {
        iniciarComoAdmin();
        servicio.aplicar(preparar("abarrotes-pdv.csv", Duplicados.OMITIR, false), List.of(), null);

        iniciarComoCajero("soloEditar", EnumSet.of(Permiso.EDITAR_PRODUCTOS));
        PlanImportacion mismosPrecios = preparar("eleventa-export.csv", Duplicados.ACTUALIZAR_PRECIOS, false);
        assertThat(mismosPrecios.requiereCambiarPrecios()).as("eleventa trae los mismos precios: no hace falta CAMBIAR_PRECIOS").isFalse();
        assertThat(servicio.aplicar(mismosPrecios, List.of(), null).cambiosDePrecio()).isZero();

        Path f = tmp.resolve("precio-nuevo.csv");
        Files.writeString(f, "Código,Descripción,Precio venta\n7501055300136,Coca,19.5\n", StandardCharsets.UTF_8);
        TablaImportada t = servicio.leer(f);
        PlanImportacion plan = servicio.preparar(t, Origen.GENERICO, Mapeo.automatico(t.encabezados(true), CampoProducto.values()),
                new OpcionesImportacion(Duplicados.ACTUALIZAR_PRECIOS, true, false, true));
        assertThat(plan.requiereCambiarPrecios()).isTrue();
        assertThatThrownBy(() -> servicio.aplicar(plan, List.of(), null)).isInstanceOf(PermisoRequeridoException.class)
                .extracting(e -> ((PermisoRequeridoException) e).getPermiso()).isEqualTo(Permiso.CAMBIAR_PRECIOS);

        iniciarComoCajero("cajeroRaso", Set.of());
        assertThatThrownBy(() -> servicio.leer(RECURSOS.resolve("abarrotes-pdv.csv"))).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> servicio.prepararProveedores(null, new Mapeo(), true)).isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void faltaUnCampoObligatorioEnElMapeo() throws Exception {
        iniciarComoAdmin();
        TablaImportada t = servicio.leer(RECURSOS.resolve("abarrotes-pdv.csv"));
        Mapeo m = new Mapeo();
        m.asignar(CampoProducto.CODIGO, 0);
        assertThatThrownBy(() -> servicio.preparar(t, Origen.GENERICO, m, OpcionesImportacion.porDefecto()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Descripción").hasMessageContaining("Precio de venta");
    }

    // ── Plantilla y archivos de Excel ────────────────────────────────────

    @Test
    void laPlantillaDeCajaLibreEnCsvYXlsxSeImportaTalCual() throws Exception {
        iniciarComoAdmin();
        for (String ext : new String[] {"csv", "xlsx"}) {
            Path f = tmp.resolve("plantilla." + ext);
            PlantillaImportacion.escribir(f, false);
            TablaImportada t = servicio.leer(f);
            Origen o = Origen.detectar(t.encabezados(true));
            assertThat(o).as(ext).isEqualTo(Origen.CAJA_LIBRE);
            Mapeo m = o.mapeoAutomatico(t.encabezados(true), CampoProducto.values());
            PlanImportacion plan = servicio.preparar(t, o, m, new OpcionesImportacion(Duplicados.ACTUALIZAR_TODO, true, true, true));
            assertThat(plan.errores()).as(ext).isEmpty();
            assertThat(plan.filas()).hasSize(2);
            ResumenImportacion r = servicio.aplicar(plan, List.of(), null);
            assertThat(r.errores()).isEmpty();
        }
        Producto refresco = porCodigo("7501000123456");
        assertThat(refresco.getDescripcion()).isEqualTo("Refresco de cola 600 ml");
        assertThat(refresco.getPrecioMayoreo()).isEqualTo(16.5);
        assertThat(refresco.getClaveProdServ()).isEqualTo("50202306");
        Producto jitomate = porCodigo("2001");
        assertThat(jitomate.getUnidad().getNombre()).isEqualTo("Kilogramo");
        assertThat(jitomate.getTipoVenta()).isEqualTo(Producto.TipoVenta.GRANEL);
        assertThat(jitomate.getCantidadActual()).isEqualTo(7.5);
        assertThat(jitomate.getDepartamento().getNombre()).isEqualTo("Frutas y verduras");
        assertThat(PlantillaImportacion.ayuda(false)).contains("Código de barras (obligatorio)");
    }

    @Test
    void laPlantillaXlsRealDeEleventaSeImporta() throws Exception {
        iniciarComoAdmin();
        PlanImportacion plan = preparar("eleventa-plantilla.xls", Duplicados.OMITIR, true);
        assertThat(plan.origen()).isEqualTo(Origen.ELEVENTA);
        assertThat(plan.errores()).isEmpty();
        ResumenImportacion r = servicio.aplicar(plan, List.of(), null);
        assertThat(r.creados()).isEqualTo(2);
        Producto p1 = porCodigo("PRUEBA01");
        assertThat(p1.getDescripcion()).isEqualTo("Producto de Prueba");
        assertThat(p1.getPrecioVenta()).isEqualTo(15);
        assertThat(p1.getPrecioMayoreo()).isEqualTo(12.5);
        assertThat(p1.getCantidadActual()).isEqualTo(10);
        assertThat(p1.getCantidadMinima()).isEqualTo(3);
        assertThat(p1.getClaveProdServ()).isEqualTo("01010101");
        assertThat(p1.getClaveUnidad()).isEqualTo("EA");
        assertThat(porCodigo("PRUEBA02").getTipoVenta()).isEqualTo(Producto.TipoVenta.GRANEL);
        assertThat(departamentoRepo.findByNombreIgnoreCase("Prueba")).isPresent();
    }

    // ── Volumen ──────────────────────────────────────────────────────────

    @Test
    void cincoMilProductosSeImportanPorLotesConProgreso() throws Exception {
        iniciarComoAdmin();
        StringBuilder sb = new StringBuilder("Código,Descripción,Costo,Precio,Departamento,Existencia\n");
        for (int i = 0; i < 5000; i++) {
            sb.append(7500000000000L + i).append(",Producto ").append(i).append(',').append(i % 50 + 1).append(',')
              .append(i % 50 + 5).append(",Depto ").append(i % 12).append(',').append(i % 7).append('\n');
        }
        Path f = tmp.resolve("grande.csv");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        TablaImportada t = servicio.leer(f);
        Mapeo m = Mapeo.automatico(t.encabezados(true), CampoProducto.values());
        PlanImportacion plan = servicio.preparar(t, Origen.GENERICO, m, OpcionesImportacion.porDefecto());
        assertThat(plan.errores()).isEmpty();
        assertThat(plan.departamentosNuevos()).hasSize(12);

        List<Integer> progresos = new java.util.ArrayList<>();
        long inicio = System.currentTimeMillis();
        ResumenImportacion r = servicio.aplicar(plan, List.of(), progresos::add);
        long ms = System.currentTimeMillis() - inicio;

        assertThat(r.creados()).isEqualTo(5000);
        assertThat(r.errores()).isEmpty();
        assertThat(r.movimientosInventario()).as("los que tienen existencia > 0 (i % 7 != 0)").isEqualTo(5000 - 715);
        assertThat(progresos).as("un aviso por lote de " + ImportacionService.TAMANO_LOTE).hasSize(20);
        assertThat(progresos.get(progresos.size() - 1)).isEqualTo(5000);
        assertThat(productoRepo.count()).isGreaterThanOrEqualTo(5000);
        assertThat(ms).as("5 000 filas deben tardar segundos, no minutos").isLessThan(90_000);
    }

    // ── Proveedores ──────────────────────────────────────────────────────

    @Test
    void importaProveedoresOmitiendoLosQueYaExisten() throws Exception {
        iniciarComoAdmin();
        Path f = tmp.resolve("proveedores.csv");
        Files.write(f, ("Proveedor;Teléfono;Contacto;RFC;Observaciones\r\n"
                + "Bimbo;55 1234 5678;Don Luis;;Visita lunes\r\n"
                + "Coca-Cola FEMSA;;;KOF991231AB1;\r\n"
                + "Sabritas;telefono-malo;;;\r\n"
                + ";55;;;\r\n"
                + "bimbo;;;;repetido en el archivo\r\n").getBytes("windows-1252"));
        proveedorRepo.save(proveedorNuevo("Coca-Cola FEMSA"));

        TablaImportada t = servicio.leer(f);
        Mapeo m = Mapeo.automatico(t.encabezados(true), CampoProveedor.values());
        assertThat(m.columnaDe(CampoProveedor.NOMBRE)).isZero();
        assertThat(m.columnaDe(CampoProveedor.TELEFONO)).isEqualTo(1);
        assertThat(m.columnaDe(CampoProveedor.CONTACTO)).isEqualTo(2);
        assertThat(m.columnaDe(CampoProveedor.RFC)).isEqualTo(3);
        assertThat(m.columnaDe(CampoProveedor.NOTAS)).isEqualTo(4);

        ImportacionService.PlanProveedores plan = servicio.prepararProveedores(t, m, true);
        assertThat(plan.aCrear()).isEqualTo(2);
        assertThat(plan.aOmitir()).isEqualTo(1);
        assertThat(plan.errores()).hasSize(2);

        ResumenImportacion r = servicio.aplicarProveedores(plan, null);
        assertThat(r.creados()).as("Sabritas falla por el teléfono en ProveedorService").isEqualTo(1);
        assertThat(r.omitidos()).isEqualTo(1);
        assertThat(r.errores()).hasSize(3);
        assertThat(r.errores()).anyMatch(e -> e.codigo().equals("Sabritas") && e.mensaje().contains("teléfono"));
        Proveedor bimbo = proveedorRepo.findByNombreIgnoreCase("Bimbo").orElseThrow();
        assertThat(bimbo.getTelefono()).isEqualTo("55 1234 5678");
        assertThat(bimbo.getContacto()).isEqualTo("Don Luis");
        assertThat(bimbo.getNotas()).isEqualTo("Visita lunes");
        assertThat(auditoriaRepo.findByAccionOrderByFechaDesc("IMPORTACION_PROVEEDORES")).hasSize(1);
        assertThat(PlantillaImportacion.encabezadosProveedores()).containsExactly("Nombre", "RFC", "Teléfono", "Contacto", "Notas");
    }

    private static Proveedor proveedorNuevo(String nombre) {
        Proveedor p = new Proveedor();
        p.setNombre(nombre);
        return p;
    }
}
