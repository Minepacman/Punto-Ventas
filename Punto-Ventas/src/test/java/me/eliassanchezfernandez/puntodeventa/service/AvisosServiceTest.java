package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService.Aviso;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService.Avisos;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService.Tipo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Avisos de la campana móvil: proveedores de hoy y mañana (importes solo con permiso),
 * productos que se acaban (por ritmo de venta o por mínimo), hora pico con historial
 * suficiente, caché de 5 minutos y orden por prioridad.
 */
class AvisosServiceTest extends BaseServicioTest {

    @Autowired private AvisosService    avisosService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private VentaRepository  ventaRepo;
    @Autowired private AgregadosService agregadosService;

    private final String sufijo = UUID.randomUUID().toString().substring(0, 6);

    @BeforeEach
    void sinCache() {
        avisosService.invalidarCache();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private String nombre(String base) {
        return base + " " + sufijo;
    }

    /** Venta directa en la BD con fecha controlada (cobrar() siempre usa "ahora" y descuenta stock) */
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

    /** `porDia` piezas cada uno de los últimos 14 días (hoy incluido) */
    private void ventasDiarias(Producto p, double porDia) {
        LocalDate hoy = LocalDate.now();
        for (int d = 0; d < AvisosService.DIAS_VENTANA_STOCK; d++) venta(hoy.minusDays(d).atTime(0, 5), p, porDia);
    }

    private Producto productoConMinimo(String nombre, double stock, double minimo) {
        Producto p = producto(nombre, 10, stock);
        p.setCantidadMinima(minimo);
        return productoService.guardar(p);
    }

    private static List<Aviso> deTipo(Avisos a, Tipo tipo) {
        return a.avisos().stream().filter(x -> x.tipo() == tipo).toList();
    }

    private static Optional<Aviso> conTitulo(Avisos a, String titulo) {
        return a.avisos().stream().filter(x -> x.titulo().equals(titulo)).findFirst();
    }

    private static Optional<Aviso> stockDe(Avisos a, Producto p) {
        return a.avisos().stream().filter(x -> x.id().equals("stock-" + p.getId())).findFirst();
    }

    // ── Proveedores ──────────────────────────────────────────────────────

    @Test
    void avisaQuienEntregaOPasaHoyYMananaYSoloQuienVeCostosRecibeLaNotaMedia() throws Exception {
        iniciarComoAdmin();
        LocalDate hoy = LocalDate.now();
        DayOfWeek dHoy = hoy.getDayOfWeek(), dManana = hoy.plusDays(1).getDayOfWeek(), dPasado = hoy.plusDays(2).getDayOfWeek();
        Proveedor bimbo = proveedorService.guardar(null, nombre("Bimbo"), null, "55 1234 5678", "Don Luis", EnumSet.of(dHoy), Set.of(), null);
        Proveedor sabritas = proveedorService.guardar(null, nombre("Sabritas"), null, null, null, EnumSet.of(dPasado), EnumSet.of(dHoy), null);
        Proveedor lala = proveedorService.guardar(null, nombre("Lala"), null, null, null, EnumSet.of(dManana), Set.of(), null);
        Proveedor coca = proveedorService.guardar(null, nombre("Coca"), null, null, null, Set.of(), EnumSet.of(dManana), null);
        proveedorService.guardar(null, nombre("Pasado"), null, null, null, EnumSet.of(dPasado), Set.of(), null);
        Proveedor inactivo = proveedorService.guardar(null, nombre("Inactivo"), null, null, null, EnumSet.of(dHoy), Set.of(), null);
        proveedorService.cambiarActivo(inactivo.getId(), false);
        // Sabritas tiene una nota de 10 × 12.5 = $125 (dinero: solo con permiso)
        Producto papas = producto(nombre("Papas"), 15, 0);
        productoService.registrarEntradaDeProveedor(papas.getId(), 10, sabritas.getId(), null, 12.5, null);

        Avisos admin = avisosService.avisos();
        assertThat(admin.hoy()).isEqualTo(hoy);
        assertThat(admin.costosVisibles()).isTrue();
        Aviso entrega = conTitulo(admin, "Hoy entrega " + bimbo.getNombre()).orElseThrow();
        assertThat(entrega.tipo()).isEqualTo(Tipo.PROVEEDOR);
        assertThat(entrega.prioridad()).isEqualTo(1);
        assertThat(entrega.icono()).isEqualTo("camion");
        assertThat(entrega.detalle()).contains("Llega la mercancía").contains("Contacto: Don Luis");
        assertThat(entrega.accion().seccion()).isEqualTo("proveedores");
        assertThat(entrega.accion().proveedorId()).isEqualTo(bimbo.getId());

        Aviso preventa = conTitulo(admin, "Hoy pasa el preventa de " + sabritas.getNombre()).orElseThrow();
        assertThat(preventa.prioridad()).isEqualTo(1);
        assertThat(preventa.icono()).isEqualTo("pedido");
        assertThat(preventa.detalle()).contains("Prepara el pedido").contains("$125.00");

        Aviso manana = conTitulo(admin, "Mañana viene " + lala.getNombre()).orElseThrow();
        assertThat(manana.prioridad()).isEqualTo(2);
        Aviso mananaPre = conTitulo(admin, "Mañana pasa el preventa de " + coca.getNombre()).orElseThrow();
        assertThat(mananaPre.prioridad()).isEqualTo(2);

        List<String> titulos = admin.avisos().stream().map(Aviso::titulo).toList();
        assertThat(titulos).noneMatch(t -> t.contains(nombre("Pasado"))).noneMatch(t -> t.contains(nombre("Inactivo")));
        assertThat(titulos).noneMatch(t -> t.contains(nombre("Sabritas")) && t.startsWith("Hoy entrega"));   // entrega pasado mañana, no hoy
        // Prioridad 1 (hoy) antes que 2 (mañana)
        assertThat(admin.avisos().indexOf(entrega)).isLessThan(admin.avisos().indexOf(manana));

        // Un cajero sin permisos recibe los mismos avisos pero sin un solo importe
        iniciarComoCajero("cajero_avisos_" + sufijo, Set.of());
        Avisos cajero = avisosService.avisos();
        assertThat(cajero.costosVisibles()).isFalse();
        assertThat(conTitulo(cajero, "Hoy entrega " + bimbo.getNombre())).isPresent();
        Aviso prevCajero = conTitulo(cajero, "Hoy pasa el preventa de " + sabritas.getNombre()).orElseThrow();
        assertThat(prevCajero.detalle()).contains("Prepara el pedido").doesNotContain("$");
        for (Aviso a : cajero.avisos()) assertThat(a.titulo() + " " + a.detalle()).as(a.id()).doesNotContain("$");

        // Con VER_GANANCIAS (sin gestionar proveedores) sí ve la nota media
        iniciarComoCajero("ganancias_avisos_" + sufijo, Set.of(Permiso.VER_GANANCIAS));
        assertThat(conTitulo(avisosService.avisos(), "Hoy pasa el preventa de " + sabritas.getNombre()).orElseThrow().detalle()).contains("$125.00");
    }

    // ── Se está acabando ─────────────────────────────────────────────────

    @Test
    void avisaLosProductosQueSeAcabanAlRitmoDeVentaOBajoElMinimo() throws Exception {
        iniciarComoAdmin();
        Producto coca = productoConMinimo(nombre("Coca-Cola 600 ml"), 3, 1);     // vende 8/día, quedan 3 → menos de 2 días
        ventasDiarias(coca, 8);
        Producto agotado = productoConMinimo(nombre("Sabritas"), 0, 0);          // agotado y se vende: aviso
        ventasDiarias(agotado, 4);
        Producto surtido = productoConMinimo(nombre("Leche"), 100, 2);           // vende 8/día, quedan 100 → tranquilo
        ventasDiarias(surtido, 8);
        Producto jabon = productoConMinimo(nombre("Jabón"), 2, 5);               // sin ventas, pero bajo su mínimo
        Producto nuncaSurtido = productoConMinimo(nombre("Nunca"), 0, 0);        // agotado, sin ventas y sin mínimo: no es aviso
        Producto sinInventario = productoConMinimo(nombre("Servicio"), 0, 0);    // no usa inventario: nunca avisa
        sinInventario.setUsaInventario(false);
        productoService.guardar(sinInventario);
        ventasDiarias(sinInventario, 9);
        Producto granel = producto(nombre("Jitomate"), 30, 1.5, Producto.TipoVenta.GRANEL, null);   // 4 kg/día, queda 1.5 kg
        ventasDiarias(granel, 4);
        agregadosService.recalcularTodo();   // los días pasados se leen de los agregados; hoy en vivo (dentro de la transacción de prueba)

        Avisos a = avisosService.avisos();
        Aviso c = stockDe(a, coca).orElseThrow();
        assertThat(c.tipo()).isEqualTo(Tipo.STOCK);
        assertThat(c.prioridad()).isEqualTo(1);
        assertThat(c.titulo()).isEqualTo(coca.getDescripcion());
        assertThat(c.detalle()).isEqualTo("Quedan 3 y vendes ~8 al día.");
        assertThat(c.accion().seccion()).isEqualTo("inventario");
        assertThat(c.accion().buscar()).isEqualTo(coca.getDescripcion());

        Aviso ag = stockDe(a, agotado).orElseThrow();
        assertThat(ag.prioridad()).isEqualTo(1);
        assertThat(ag.detalle()).isEqualTo("Agotado, vendes ~4 al día.");

        Aviso j = stockDe(a, jabon).orElseThrow();
        assertThat(j.prioridad()).as("bajo el mínimo pero sin ritmo conocido: menos urgente").isEqualTo(2);
        assertThat(j.detalle()).isEqualTo("Quedan 2 (mínimo 5).");

        Aviso g = stockDe(a, granel).orElseThrow();
        assertThat(g.detalle()).isEqualTo("Quedan 1.5 kg y vendes ~4 kg al día.");

        assertThat(stockDe(a, surtido)).as("100 piezas a 8 al día no se acaban").isEmpty();
        assertThat(stockDe(a, nuncaSurtido)).as("agotado sin ventas ni mínimo no es aviso").isEmpty();
        assertThat(stockDe(a, sinInventario)).as("sin inventario no hay nada que avisar").isEmpty();

        // Los agotados y los que se acaban van antes que los que solo están bajo el mínimo
        List<Aviso> stock = deTipo(a, Tipo.STOCK);
        assertThat(stock.indexOf(ag)).isLessThan(stock.indexOf(j));
        assertThat(stock.indexOf(c)).isLessThan(stock.indexOf(j));
    }

    @Test
    void enumeraComoMaximoCincoProductosYResumeElResto() throws Exception {
        iniciarComoAdmin();
        // Siete agotados que se venden muchísimo: encabezan la lista por encima de cualquier otro dato
        for (int i = 1; i <= 7; i++) {
            Producto p = productoConMinimo(nombre("Agotadísimo " + i), 0, 0);
            ventasDiarias(p, 500 + i);
        }
        agregadosService.recalcularTodo();
        Avisos a = avisosService.avisos();
        List<Aviso> stock = deTipo(a, Tipo.STOCK);
        List<Aviso> productos = stock.stream().filter(x -> !x.id().equals("stock-mas")).toList();
        assertThat(productos).hasSize(AvisosService.MAX_PRODUCTOS);
        assertThat(productos).allMatch(x -> x.titulo().startsWith("Agotadísimo "));
        assertThat(productos.get(0).titulo()).as("más venta diaria primero").isEqualTo(nombre("Agotadísimo 7"));
        Aviso mas = stock.stream().filter(x -> x.id().equals("stock-mas")).findFirst().orElseThrow();
        assertThat(mas.titulo()).startsWith("Otros ").contains("productos con poca existencia");
        assertThat(mas.accion().filtro()).isEqualTo("bajo");
        assertThat(stock.indexOf(mas)).as("el resumen va al final del grupo").isEqualTo(stock.size() - 1);
    }

    @Test
    void laClaveDeUrgenciaYLosTextosSonPuros() {
        Producto p = new Producto();
        p.setId(1L);
        p.setDescripcion("X");
        p.setCantidadActual(3);
        p.setCantidadMinima(1);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        AvisosService.ProductoEscaso e = AvisosService.evaluar(p, 8);
        assertThat(e).isNotNull();
        assertThat(e.cobertura()).isEqualTo(0.375);
        assertThat(AvisosService.claveUrgencia(e)).isEqualTo(0.375);
        assertThat(AvisosService.evaluar(p, 1)).as("3 piezas a 1 por día alcanzan 3 días y está sobre el mínimo").isNull();
        p.setCantidadMinima(3);
        AvisosService.ProductoEscaso m = AvisosService.evaluar(p, 1);
        assertThat(m).isNotNull();
        assertThat(AvisosService.claveUrgencia(m)).as("bajo mínimo: después de los que se acaban (>= 2)").isGreaterThanOrEqualTo(2);

        assertThat(AvisosService.cantidad(8)).isEqualTo("8");
        assertThat(AvisosService.cantidad(7.96)).isEqualTo("8");
        assertThat(AvisosService.cantidad(2.5)).isEqualTo("2.5");
        assertThat(AvisosService.cantidad(0.5)).isEqualTo("0.5");
        assertThat(AvisosService.cantidad(0.25)).isEqualTo("0.25");
        assertThat(AvisosService.cantidad(12.4)).isEqualTo("12");
        assertThat(AvisosService.cantidad(0)).isEqualTo("0");
        assertThat(AvisosService.dinero(1845.5)).isEqualTo("$1,845.50");
    }

    // ── Hora pico ────────────────────────────────────────────────────────

    @Test
    void laFranjaPicoEsPuraYExigeQueDestaque() {
        double[] h = new double[24];
        h[18] = 500; h[19] = 700; h[10] = 100; h[12] = 150; h[16] = 200;
        assertThat(AvisosService.ventanaPico(h)).containsExactly(18, 20);
        assertThat(AvisosService.ventanaPico(new double[24])).as("sin ventas").isNull();
        double[] plano = new double[24];
        for (int i = 8; i < 20; i++) plano[i] = 100;
        assertThat(AvisosService.ventanaPico(plano)).as("todo igual: no hay pico").isNull();
        double[] solo = new double[24];
        solo[9] = 50;
        assertThat(AvisosService.ventanaPico(solo)).as("una sola hora con venta: esa es la franja").containsExactly(9, 11);
        double[] fin = new double[24];
        fin[22] = 40; fin[23] = 90; fin[8] = 10; fin[9] = 10;
        assertThat(AvisosService.ventanaPico(fin)).as("la ventana no se sale del día").containsExactly(22, 24);
        assertThat(AvisosService.plural(DayOfWeek.SATURDAY)).isEqualTo("sábados");
        assertThat(AvisosService.plural(DayOfWeek.MONDAY)).isEqualTo("lunes");
    }

    @Test
    void laHoraPicoSaleSoloConHistorialYAntesDeQueTermineLaFranja() throws Exception {
        iniciarComoAdmin();
        LocalDate hoy = LocalDate.now();
        Producto p = producto(nombre("Pan"), 10, 1000);
        // Sin 30 días de historial: nada de hora pico (aunque haya ventas)
        venta(hoy.minusDays(3).atTime(18, 10), p, 1);
        if (ventaRepo.findPrimeraActiva().map(v -> v.getFecha().toLocalDate().isAfter(hoy.minusDays(AvisosService.DIAS_MINIMOS_HORA_PICO))).orElse(true)) {
            assertThat(deTipo(avisosService.avisos(hoy.atTime(9, 0)), Tipo.HORA_PICO)).as("sin historial suficiente").isEmpty();
        }
        avisosService.invalidarCache();

        // Ocho mismos días de la semana hacia atrás, con el grueso de la venta de 18:00 a 20:00
        for (int s = 1; s <= AvisosService.SEMANAS_HORA_PICO; s++) {
            LocalDate d = hoy.minusWeeks(s);
            venta(d.atTime(10, 15), p, 1);
            venta(d.atTime(18, 20), p, 6);
            venta(d.atTime(19, 5), p, 6);
            venta(d.atTime(19, 40), p, 5);
        }
        agregadosService.recalcularTodo();
        Avisos manana = avisosService.avisos(hoy.atTime(9, 0));
        List<Aviso> pico = deTipo(manana, Tipo.HORA_PICO);
        assertThat(pico).hasSize(1);
        assertThat(pico.get(0).titulo()).isEqualTo("Los " + AvisosService.plural(hoy.getDayOfWeek()) + " vendes más de 18:00 a 20:00");
        assertThat(pico.get(0).detalle()).contains("8 semanas");
        assertThat(pico.get(0).prioridad()).isEqualTo(3);
        assertThat(pico.get(0).accion()).isNull();
        assertThat(manana.avisos().indexOf(pico.get(0))).as("informativo: al final").isEqualTo(manana.avisos().size() - 1);

        // Dentro de la franja sigue; pasada la franja ya no sirve de nada
        assertThat(deTipo(avisosService.avisos(hoy.atTime(19, 30)), Tipo.HORA_PICO)).hasSize(1);
        assertThat(deTipo(avisosService.avisos(hoy.atTime(20, 0)), Tipo.HORA_PICO)).isEmpty();
        assertThat(deTipo(avisosService.avisos(hoy.atTime(22, 0)), Tipo.HORA_PICO)).isEmpty();
    }

    // ── Caché y sesión ───────────────────────────────────────────────────

    @Test
    void elCalculoSeGuardaCincoMinutosYSeRenuevaAlInvalidarloOAlCambiarDeDia() throws Exception {
        iniciarComoAdmin();
        LocalDateTime ahora = LocalDate.now().atTime(11, 0);
        Avisos primero = avisosService.avisos(ahora);
        Producto nuevo = productoConMinimo(nombre("Tardío"), 1, 5);
        assertThat(stockDe(avisosService.avisos(ahora.plusMinutes(4)), nuevo)).as("4 min después sigue la misma foto").isEmpty();
        assertThat(avisosService.avisos(ahora.plusMinutes(4)).generado()).isEqualTo(primero.generado());
        assertThat(stockDe(avisosService.avisos(ahora.plusMinutes(5)), nuevo)).as("a los 5 min se recalcula").isPresent();
        Avisos otroDia = avisosService.avisos(ahora.plusDays(1).withHour(0).withMinute(10));
        assertThat(otroDia.hoy()).isEqualTo(LocalDate.now().plusDays(1));
        avisosService.invalidarCache();
        assertThat(avisosService.avisos(ahora.plusDays(1).withHour(0).withMinute(11)).generado()).isAfter(otroDia.generado());
    }

    @Test
    void sinSesionNoHayAvisos() {
        sesionService.cerrarSesion();
        assertThatThrownBy(() -> avisosService.avisos()).isInstanceOf(RuntimeException.class);
    }
}
