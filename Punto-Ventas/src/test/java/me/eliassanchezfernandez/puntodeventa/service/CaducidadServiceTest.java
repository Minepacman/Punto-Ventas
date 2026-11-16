package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Caducidad;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush;
import me.eliassanchezfernandez.puntodeventa.repository.CaducidadRepository;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService.Aviso;
import me.eliassanchezfernandez.puntodeventa.service.CaducidadService.LoteEstimado;
import me.eliassanchezfernandez.puntodeventa.service.CaducidadService.PorCaducar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Caducidades (versión sencilla): estimado PEPS por fecha de caducidad, ventana de "Por caducar",
 * entradas con y sin fecha (Inventario, orden de compra, teléfono), permiso para retirar, aviso de
 * la campana y su notificación de las 9:00.
 */
class CaducidadServiceTest extends BaseServicioTest {

    @Autowired private CaducidadService      caducidadService;
    @Autowired private CaducidadRepository   caducidadRepo;
    @Autowired private AvisosService         avisosService;
    @Autowired private ProgramadorAvisosPush programador;
    @Autowired private ProveedorService      proveedorService;
    @Autowired private OrdenCompraService    ordenCompraService;
    @Autowired private ConfiguracionService  configuracionService;

    private final LocalDate hoy = LocalDate.now();

    @BeforeEach
    void sinCache() {
        avisosService.invalidarCache();
    }

    private Producto conCaducidad(String nombre, double stock) {
        Producto p = producto(nombre, 20, stock);
        p.setControlaCaducidad(true);
        return productoService.guardar(p);
    }

    private static Caducidad lote(long id, LocalDate caduca, double cantidad, LocalDateTime entrada) {
        Caducidad c = new Caducidad();
        c.setId(id);
        c.setFechaCaducidad(caduca);
        c.setCantidadEntrada(cantidad);
        c.setFechaEntrada(entrada);
        return c;
    }

    /** Lote directo en la BD (para fechas ya pasadas, que una entrada no acepta) */
    private Caducidad loteEnBd(Producto p, LocalDate caduca, double cantidad) {
        Caducidad c = new Caducidad();
        c.setProductoId(p.getId());
        c.setFechaCaducidad(caduca);
        c.setCantidadEntrada(cantidad);
        c.setFechaEntrada(LocalDateTime.now().minusDays(20));
        c.setOrigen(Caducidad.Origen.MANUAL.name());
        return caducidadRepo.save(c);
    }

    // ── Estimado PEPS ─────────────────────────────────────────────────────

    @Test
    void laExistenciaSeAsignaPrimeroALoQueCaducaMasTardeYLoDemasSeSuponeVendido() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 10, 0);
        Caducidad pronto = lote(1, hoy.plusDays(3), 10, t);
        Caducidad tarde = lote(2, hoy.plusDays(30), 10, t.plusDays(2));
        Caducidad medio = lote(3, hoy.plusDays(10), 10, t.plusDays(1));

        List<LoteEstimado> r = CaducidadService.estimar(15, List.of(pronto, tarde, medio));
        // Ordenado del más urgente al menos urgente
        assertThat(r).extracting(le -> le.lote().getId()).containsExactly(1L, 3L, 2L);
        assertThat(r).extracting(LoteEstimado::estimado).containsExactly(0.0, 5.0, 10.0);

        assertThat(CaducidadService.estimar(100, List.of(pronto, tarde, medio)))
                .extracting(LoteEstimado::estimado).containsExactly(10.0, 10.0, 10.0);   // nunca más de lo que entró
        assertThat(CaducidadService.estimar(-4, List.of(pronto, tarde)))
                .extracting(LoteEstimado::estimado).containsExactly(0.0, 0.0);
    }

    @Test
    void lasEntradasRetiradasNoRecibenExistenciaYAIgualFechaLaMasNuevaVaPrimero() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 10, 0);
        Caducidad vieja = lote(1, hoy.plusDays(5), 4, t);
        Caducidad nueva = lote(2, hoy.plusDays(5), 4, t.plusHours(5));
        Caducidad retirada = lote(3, hoy.plusDays(50), 10, t);
        retirada.setRetiradaEn(t.plusDays(1));

        List<LoteEstimado> r = CaducidadService.estimar(6, List.of(vieja, nueva, retirada));
        assertThat(r).extracting(le -> le.lote().getId()).containsExactly(1L, 2L);
        assertThat(r).extracting(LoteEstimado::estimado).containsExactly(2.0, 4.0);
    }

    // ── Entradas con y sin fecha ──────────────────────────────────────────

    @Test
    void unaEntradaConFechaDeUnProductoQueControlaCaducidadCreaSuRegistro() throws Exception {
        iniciarComoAdmin();
        Producto leche = conCaducidad("Leche cad", 0);
        Proveedor lala = proveedorService.guardar(null, "Lala cad", null, null, null, Set.of(), null);

        productoService.registrarEntradaDeProveedor(leche.getId(), 12, lala.getId(), null, 18, null, hoy.plusDays(6));
        productoService.registrarEntradaDeProveedor(leche.getId(), 5, lala.getId(), null, 18, null, null);   // sin fecha: nada

        List<Caducidad> lotes = caducidadRepo.vigentesDe(leche.getId());
        assertThat(lotes).hasSize(1);
        Caducidad c = lotes.get(0);
        assertThat(c.getFechaCaducidad()).isEqualTo(hoy.plusDays(6));
        assertThat(c.getCantidadEntrada()).isEqualTo(12);
        assertThat(c.getOrigen()).isEqualTo("MANUAL");
        assertThat(c.getMovimientoId()).isNotNull();
        assertThat(productoRepo.findById(leche.getId()).orElseThrow().getCantidadActual()).isEqualTo(17);
        // Existencia 17: los 12 con fecha se suponen aún en el anaquel
        assertThat(caducidadService.lotesDe(leche.getId())).extracting(LoteEstimado::estimado).containsExactly(12.0);
        assertThat(caducidadService.resumenLotes(leche.getId())).startsWith("Caduca ").contains("~12");
    }

    @Test
    void enUnProductoSinControlLaFechaSeIgnoraYUnaFechaPasadaSeRechaza() throws Exception {
        iniciarComoAdmin();
        Producto pan = producto("Pan sin control", 10, 0);
        productoService.registrarEntrada(pan.getId(), 10, "Bimbo", 5, null, hoy.plusDays(3), Caducidad.Origen.MOVIL);
        assertThat(caducidadRepo.vigentesDe(pan.getId())).isEmpty();

        Producto yogurt = conCaducidad("Yogurt cad", 0);
        assertThatThrownBy(() -> productoService.registrarEntrada(yogurt.getId(), 3, "Lala", 5, null,
                hoy.minusDays(1), Caducidad.Origen.MOVIL))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya pasó");
        assertThatThrownBy(() -> CaducidadService.validarFecha(hoy.plusYears(20), hoy))
                .isInstanceOf(NegocioException.class).hasMessageContaining("demasiado lejos");
        CaducidadService.validarFecha(hoy, hoy);    // caduca hoy: se acepta
        CaducidadService.validarFecha(null, hoy);   // sin fecha: se acepta
    }

    @Test
    void laEntradaDesdeElTelefonoGuardaElOrigen() throws Exception {
        iniciarComoAdmin();
        Producto queso = conCaducidad("Queso cad", 0);
        productoService.registrarEntrada(queso.getId(), 2, "No indicado (móvil)", 0, null, hoy.plusDays(4), Caducidad.Origen.MOVIL);
        assertThat(caducidadRepo.vigentesDe(queso.getId())).singleElement()
                .extracting(Caducidad::getOrigen).isEqualTo("MOVIL");
    }

    @Test
    void alRecibirUnaOrdenCadaLineaPuedeLlevarSuFecha() throws Exception {
        iniciarComoAdmin();
        Proveedor lala = proveedorService.guardar(null, "Lala orden cad", null, null, null, Set.of(), null);
        Producto leche = conCaducidad("Leche orden", 0);
        Producto crema = conCaducidad("Crema orden", 0);
        OrdenCompra o = ordenCompraService.crear(lala.getId(), List.of(
                new OrdenCompraService.LineaOrden(leche.getId(), 10, 18),
                new OrdenCompraService.LineaOrden(crema.getId(), 4, 25)), null);
        Long lineaLeche = o.getLineas().stream().filter(l -> l.getProducto().getId().equals(leche.getId())).findFirst().orElseThrow().getId();

        ordenCompraService.recibir(o.getId(), null, Map.of(lineaLeche, hoy.plusDays(8)));

        assertThat(caducidadRepo.vigentesDe(leche.getId())).singleElement().satisfies(c -> {
            assertThat(c.getOrigen()).isEqualTo("ORDEN");
            assertThat(c.getCantidadEntrada()).isEqualTo(10);
            assertThat(c.getFechaCaducidad()).isEqualTo(hoy.plusDays(8));
        });
        assertThat(caducidadRepo.vigentesDe(crema.getId())).isEmpty();
    }

    @Test
    void quienNoControlaLaCaducidadEnElFormularioNoLaApagaAlGuardar() throws Exception {
        iniciarComoAdmin();
        Producto p = conCaducidad("Jamón cad", 0);
        // Como lo arma una pantalla que no conoce el campo (p. ej. importación): objeto nuevo con el mismo id
        Producto copia = new Producto();
        copia.setId(p.getId());
        copia.setCodigoBarras(p.getCodigoBarras());
        copia.setDescripcion("Jamón cad 2");
        copia.setPrecioVenta(p.getPrecioVenta());
        copia.setPrecioCosto(p.getPrecioCosto());
        copia.setUsaInventario(true);
        assertThat(copia.getControlaCaducidad()).isNull();
        productoService.guardar(copia);
        assertThat(productoRepo.findById(p.getId()).orElseThrow().isControlaCaducidad()).isTrue();
    }

    // ── Por caducar ───────────────────────────────────────────────────────

    @Test
    void porCaducarIncluyeLoQueVenceDentroDeLaVentanaYLoYaCaducadoConExistenciaEstimada() throws Exception {
        iniciarComoAdmin();
        Proveedor lala = proveedorService.guardar(null, "Lala ventana", null, null, null, Set.of(), null);
        Producto leche = conCaducidad("Leche ventana", 0);
        productoService.registrarEntradaDeProveedor(leche.getId(), 6, lala.getId(), null, 18, null, hoy.plusDays(3));
        productoService.registrarEntradaDeProveedor(leche.getId(), 6, lala.getId(), null, 18, null, hoy.plusDays(12));
        Producto yogurt = conCaducidad("Yogurt ventana", 0);
        productoService.registrarEntradaDeProveedor(yogurt.getId(), 4, lala.getId(), null, 8, null, hoy.plusDays(20));
        loteEnBd(yogurt, hoy.minusDays(2), 4);   // ya caducado: la existencia (4) se la queda el de más tarde

        List<PorCaducar> semana = caducidadService.porCaducar(hoy, 7);
        assertThat(semana).extracting(PorCaducar::producto).containsExactly("Leche ventana");
        assertThat(semana.get(0).estimado()).isEqualTo(6);
        assertThat(semana.get(0).dias()).isEqualTo(3);
        assertThat(semana.get(0).caducado()).isFalse();

        // Se vendió todo lo de la primera fecha (quedan 6 de 12): ya no está por caducar
        productoService.ajustarStock(leche.getId(), 6, "Conteo físico", null);
        assertThat(caducidadService.porCaducar(hoy, 7)).isEmpty();
        // Con 14 días entra la segunda entrada de leche
        assertThat(caducidadService.porCaducar(hoy, 14)).extracting(PorCaducar::producto).containsExactly("Leche ventana");

        // Con más existencia que la entrada de más tarde, lo caducado sí tiene algo y sale en rojo
        productoService.registrarEntrada(yogurt.getId(), 2, "Lala", 8, null);   // existencia 6: 4 al del día 20 y 2 al caducado
        List<PorCaducar> conCaducado = caducidadService.porCaducar(hoy, 7);
        assertThat(conCaducado).singleElement().satisfies(p -> {
            assertThat(p.producto()).isEqualTo("Yogurt ventana");
            assertThat(p.caducado()).isTrue();
            assertThat(p.estimado()).isEqualTo(2);
            assertThat(p.dias()).isEqualTo(-2);
        });
    }

    @Test
    void losDiasDeAvisoSeConfiguranConPermisoDeInventarioYDentroDeRango() throws Exception {
        iniciarComoAdmin();
        assertThat(caducidadService.diasAviso()).isEqualTo(7);
        configuracionService.guardarDiasAvisoCaducidad(14);
        assertThat(caducidadService.diasAviso()).isEqualTo(14);
        assertThatThrownBy(() -> configuracionService.guardarDiasAvisoCaducidad(0)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardarDiasAvisoCaducidad(91)).isInstanceOf(NegocioException.class);

        iniciarComoCajero("cajeraCad1", Set.of());
        assertThatThrownBy(() -> configuracionService.guardarDiasAvisoCaducidad(5)).isInstanceOf(PermisoRequeridoException.class);
    }

    // ── Retirar / Ya no queda ─────────────────────────────────────────────

    @Test
    void retirarExigePermisoDeInventarioYDejaUnaMermaCaducado() throws Exception {
        iniciarComoAdmin();
        Producto leche = conCaducidad("Leche retirar", 0);
        productoService.registrarEntrada(leche.getId(), 8, "Lala", 18, null, hoy.plusDays(1), Caducidad.Origen.MANUAL);
        Caducidad c = caducidadRepo.vigentesDe(leche.getId()).get(0);

        iniciarComoCajero("cajeraCad2", Set.of(Permiso.VER_HISTORIAL));
        assertThatThrownBy(() -> caducidadService.retirar(c.getId(), null, null)).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> caducidadService.marcarSinExistencia(c.getId(), null)).isInstanceOf(PermisoRequeridoException.class);
        assertThat(productoRepo.findById(leche.getId()).orElseThrow().getCantidadActual()).isEqualTo(8);

        // Con la autorización de un administrador sí (y se consume)
        Autorizacion aut = new Autorizacion(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow(),
                Permiso.AJUSTAR_INVENTARIO, LocalDateTime.now());
        Producto despues = caducidadService.retirar(c.getId(), 3.0, List.of(aut));
        assertThat(aut.isConsumida()).isTrue();
        assertThat(despues.getCantidadActual()).isEqualTo(5);
        MovimientoInventario m = productoService.movimientosDe(leche.getId()).stream()
                .filter(x -> x.getTipo() == MovimientoInventario.Tipo.AJUSTE).findFirst().orElseThrow();
        assertThat(m.getMotivo()).startsWith(CaducidadService.MOTIVO_CADUCADO);
        assertThat(m.getCantidad()).isEqualTo(-3);
        Caducidad cerrada = caducidadRepo.findById(c.getId()).orElseThrow();
        assertThat(cerrada.isVigente()).isFalse();
        assertThat(cerrada.getRetiradaCantidad()).isEqualTo(3);
        assertThatThrownBy(() -> caducidadService.retirar(c.getId(), null, List.of(aut)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void retirarSinCantidadDaDeBajaLoEstimadoYYaNoQuedaNoMueveElInventario() throws Exception {
        iniciarComoAdmin();
        Producto pan = conCaducidad("Pan retirar", 0);
        productoService.registrarEntrada(pan.getId(), 5, "Bimbo", 10, null, hoy.plusDays(2), Caducidad.Origen.MANUAL);
        productoService.registrarEntrada(pan.getId(), 5, "Bimbo", 10, null, hoy.plusDays(9), Caducidad.Origen.MANUAL);
        productoService.ajustarStock(pan.getId(), 7, "Conteo físico", null);   // 5 al del día 9, 2 al del día 2
        List<Caducidad> lotes = caducidadRepo.vigentesDe(pan.getId());
        Caducidad pronto = lotes.stream().filter(l -> l.getFechaCaducidad().equals(hoy.plusDays(2))).findFirst().orElseThrow();
        Caducidad tarde = lotes.stream().filter(l -> l.getFechaCaducidad().equals(hoy.plusDays(9))).findFirst().orElseThrow();

        assertThat(caducidadService.retirar(pronto.getId(), null, null).getCantidadActual()).isEqualTo(5);

        caducidadService.marcarSinExistencia(tarde.getId(), null);
        assertThat(productoRepo.findById(pan.getId()).orElseThrow().getCantidadActual()).isEqualTo(5);
        assertThat(caducidadRepo.vigentesDe(pan.getId())).isEmpty();
        assertThatThrownBy(() -> caducidadService.retirar(tarde.getId(), 1.0, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya se había retirado");
    }

    @Test
    void noSePuedeRetirarMasDeLaExistencia() throws Exception {
        iniciarComoAdmin();
        Producto p = conCaducidad("Crema retirar", 0);
        productoService.registrarEntrada(p.getId(), 2, "Lala", 10, null, hoy.plusDays(2), Caducidad.Origen.MANUAL);
        Caducidad c = caducidadRepo.vigentesDe(p.getId()).get(0);
        assertThatThrownBy(() -> caducidadService.retirar(c.getId(), 5.0, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("existencia");
    }

    // ── Avisos y notificación ─────────────────────────────────────────────

    @Test
    void elAvisoCuentaProductosNoEntradasYDiceSiAlgoYaCaduco() {
        PorCaducar a = new PorCaducar(1, 10, "Leche", "1", "pza", hoy.plusDays(2), 3, 3, 2);
        PorCaducar b = new PorCaducar(2, 10, "Leche", "1", "pza", hoy.plusDays(5), 3, 3, 5);
        PorCaducar c = new PorCaducar(3, 11, "Yogurt", "2", "pza", hoy.minusDays(1), 1, 1, -1);

        Aviso semana = AvisosService.avisoCaducidad(List.of(a, b), 7);
        assertThat(semana.titulo()).isEqualTo("1 producto caduca esta semana");
        assertThat(semana.detalle()).isEqualTo("Leche.");
        assertThat(semana.prioridad()).isEqualTo(2);
        assertThat(semana.tipo()).isEqualTo(AvisosService.Tipo.CADUCIDAD);
        assertThat(semana.accion().filtro()).isEqualTo("caducar");

        Aviso conCaducado = AvisosService.avisoCaducidad(List.of(a, b, c), 10);
        assertThat(conCaducado.titulo()).isEqualTo("2 productos caducan en los próximos 10 días");
        assertThat(conCaducado.detalle()).isEqualTo("1 ya caducó: retíralo de la venta. Leche, Yogurt.");
        assertThat(conCaducado.prioridad()).isEqualTo(1);

        assertThat(AvisosService.avisoCaducidad(List.of(), 7)).isNull();
    }

    @Test
    void laCampanaYLaNotificacionDeLas9LlevanLasCaducidades() throws Exception {
        iniciarComoAdmin();
        Producto leche = conCaducidad("Leche aviso", 0);
        productoService.registrarEntrada(leche.getId(), 6, "Lala", 18, null, hoy.plusDays(2), Caducidad.Origen.MANUAL);
        avisosService.invalidarCache();

        Aviso aviso = avisosService.avisos().avisos().stream()
                .filter(a -> a.tipo() == AvisosService.Tipo.CADUCIDAD).findFirst().orElseThrow();
        assertThat(aviso.id()).isEqualTo(AvisosService.ID_CADUCIDAD);
        assertThat(aviso.titulo()).isEqualTo("1 producto caduca esta semana");

        // Se programa desde las 9:00 (el primer corte de stock), no antes
        LocalDateTime ocho = hoy.atTime(8, 30), nueve = hoy.atTime(9, 0);
        assertThat(programador.pendientesDe(ocho)).filteredOn(p -> p.aviso().tipo() == AvisosService.Tipo.CADUCIDAD)
                .singleElement().satisfies(p -> assertThat(p.due()).isFalse());
        assertThat(programador.pendientesDe(nueve)).filteredOn(p -> p.aviso().tipo() == AvisosService.Tipo.CADUCIDAD)
                .singleElement().satisfies(p -> assertThat(p.due()).isTrue());

        NotificacionesPushService.Mensaje m = ProgramadorAvisosPush.mensaje(aviso);
        assertThat(m.titulo()).isEqualTo("1 producto caduca esta semana");
        assertThat(m.cuerpo()).isEqualTo("Leche aviso.");
        assertThat(m.url()).isEqualTo("/#/inventario?filtro=caducar");
    }

    @Test
    void laPreferenciaDeCaducidadEsPorTelefonoYLasSuscripcionesViejasLaTienenEncendida() {
        SuscripcionPush s = new SuscripcionPush();
        assertThat(s.quiere("CADUCIDAD")).isTrue();
        s.setCaducidad(null);                    // fila anterior a la columna
        assertThat(s.quiere("CADUCIDAD")).isTrue();
        s.setCaducidad(false);
        assertThat(s.quiere("CADUCIDAD")).isFalse();
        assertThat(s.quiere("STOCK")).isTrue();
    }
}
