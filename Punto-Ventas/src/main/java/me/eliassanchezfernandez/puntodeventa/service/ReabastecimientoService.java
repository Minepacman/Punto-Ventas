package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.MadurezDatos;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reabastecimiento (pestaña Estadísticas → Reabastecimiento):
 *
 *  - Productos rezagados: con existencia pero sin venta en N días, o con
 *    tanta existencia que tardaría meses en venderse (inventario inmovilizado).
 *  - Frecuencia de surtido y pedido sugerido: con la venta promedio diaria de
 *    los últimos {@link #VENTANA_DIAS} días, la existencia y el mínimo, cada
 *    producto recibe "cada cuántos días conviene surtirlo" y "cuánto pedir",
 *    agrupado por el proveedor que lo surtió por última vez (entradas de
 *    inventario). Sin proveedor conocido se agrupa por departamento.
 *
 * Todo se calcula a partir de los agregados (producto_diario + hoy en vivo):
 * nunca se recorren las ventas. Exige VER_HISTORIAL; los costos solo salen si
 * quien consulta puede verlos (VER_GANANCIAS o GESTIONAR_PROVEEDORES). Crear
 * la orden borrador pasa por OrdenCompraService (GESTIONAR_PROVEEDORES).
 *
 * Las fórmulas son funciones estáticas (probadas sin base de datos).
 */
@Service
public class ReabastecimientoService {

    /** Días de ventas con los que se calcula la venta promedio diaria */
    public static final int VENTANA_DIAS = 30;
    /** Un producto con existencia y sin venta en estos días es "rezagado" (por defecto) */
    public static final int DIAS_SIN_VENTA = 30;
    /** Con existencia para más de estos días de venta, el inventario cuenta como inmovilizado */
    public static final int COBERTURA_EXCESIVA_DIAS = 90;
    /** Días de venta que se quieren tener por arriba del mínimo si el proveedor no tiene días de visita */
    public static final int HORIZONTE_DIAS = 7;
    /** Nombre del grupo de los productos sin proveedor conocido */
    public static final String SIN_PROVEEDOR = "Sin proveedor";

    @Autowired private ProductoRepository             productoRepo;
    @Autowired private ProductoDiarioRepository       productoDiarioRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private ProveedorRepository            proveedorRepo;
    @Autowired private EstadisticasService            estadisticasService;
    @Autowired private AgregadosService               agregadosService;
    @Autowired private OrdenCompraService             ordenCompraService;
    @Autowired private SesionService                  sesionService;

    // ── Tipos ────────────────────────────────────────────────────────────

    /** Qué tan urgente es pedir el producto (en este orden) */
    public enum Urgencia {
        AGOTADO("Agotado"),
        BAJO_MINIMO("Bajo el mínimo"),
        PRONTO("Se acaba antes del próximo surtido"),
        OK("Con existencia");

        private final String etiqueta;
        Urgencia(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
    }

    /**
     * Un producto en el plan de surtido.
     * @param ventaDiaria    piezas vendidas por día (promedio de la ventana)
     * @param diasCobertura  para cuántos días alcanza la existencia (null = no se vende, no se puede estimar)
     * @param frecuenciaDias cada cuántos días conviene surtirlo (null = no se vende)
     * @param pedido         piezas a pedir ahora (0 = nada por ahora)
     * @param costoPedido    pedido × costo unitario (0 si no se pueden ver costos)
     */
    public record LineaSurtido(Producto producto, double ventaDiaria, double existencia, double minimo,
                               Double diasCobertura, Integer frecuenciaDias, double pedido,
                               Urgencia urgencia, double costoPedido, String motivo) {
        public boolean hayQuePedir() { return pedido > 0; }
    }

    /** Productos de un proveedor (o de un departamento sin proveedor) con su horizonte de surtido */
    public record GrupoSurtido(Proveedor proveedor, String nombre, String diasVisita, int horizonteDias,
                               List<LineaSurtido> lineas) {
        public boolean tieneProveedor() { return proveedor != null; }
        public int productosAPedir() { return (int) lineas.stream().filter(LineaSurtido::hayQuePedir).count(); }
        public double costoPedido() {
            return ResumenDiario.VentaRedondeo.centavos(lineas.stream().mapToDouble(LineaSurtido::costoPedido).sum());
        }
        public List<LineaSurtido> aPedir() { return lineas.stream().filter(LineaSurtido::hayQuePedir).toList(); }
    }

    /** El plan completo */
    public record PlanSurtido(List<GrupoSurtido> grupos, int diasVentana, boolean conCostos, LocalDate hoy) {
        public int productosAPedir() { return grupos.stream().mapToInt(GrupoSurtido::productosAPedir).sum(); }
        public double costoPedido() {
            return ResumenDiario.VentaRedondeo.centavos(grupos.stream().mapToDouble(GrupoSurtido::costoPedido).sum());
        }
    }

    /**
     * Un producto rezagado.
     * @param ultimaVenta   último día con venta (null = sin ventas registradas)
     * @param diasSinVenta  días desde la última venta (o desde la primera venta de la tienda si nunca se vendió)
     * @param diasCobertura días que tardaría en venderse la existencia al ritmo de la ventana (null = no se vende)
     * @param valorVenta    existencia × precio de venta
     * @param valorCosto    existencia × costo (0 si no se pueden ver costos)
     */
    public record ProductoRezagado(Producto producto, LocalDate ultimaVenta, long diasSinVenta, double existencia,
                                   double piezasVentana, Double diasCobertura, double valorVenta, double valorCosto,
                                   String motivo) { }

    public record Rezagados(List<ProductoRezagado> productos, int diasSinVenta, boolean conCostos,
                            double totalValorVenta, double totalValorCosto) { }

    // ── Fórmulas (funciones puras) ───────────────────────────────────────

    /**
     * Calcula la línea de surtido de un producto.
     *
     * - venta diaria = piezas de la ventana ÷ días de la ventana;
     * - cobertura = existencia ÷ venta diaria;
     * - objetivo (lo que conviene tener después de surtir) = el mayor entre
     *   el doble del mínimo y "mínimo + venta diaria × horizonte";
     * - frecuencia = cada cuántos días se consume lo que hay entre el objetivo y el mínimo;
     * - pedido = objetivo − existencia, solo si está agotado, bajo el mínimo o
     *   se acaba antes del próximo surtido (cobertura ≤ horizonte).
     */
    public static LineaSurtido calcularLinea(Producto p, double piezasVentana, int diasVentana, int horizonteDias) {
        int dias = Math.max(1, diasVentana);
        int horizonte = Math.max(1, horizonteDias);
        double ventaDiaria = ResumenDiario.VentaRedondeo.milesimas(Math.max(0, piezasVentana) / dias);
        double existencia = p.getCantidadActual();
        double minimo = Math.max(0, p.getCantidadMinima());

        Double cobertura = null;
        if (ventaDiaria > 0) cobertura = Math.max(0, existencia) / ventaDiaria;

        double objetivo = Math.max(minimo * 2, minimo + ventaDiaria * horizonte);
        Integer frecuencia = ventaDiaria > 0 ? (int) Math.ceil((objetivo - minimo) / ventaDiaria - 1e-9) : null;
        if (frecuencia != null && frecuencia < 1) frecuencia = 1;

        Urgencia urgencia;
        if (objetivo <= 0) urgencia = Urgencia.OK;                         // ni mínimo ni ventas: nada que planear
        else if (existencia <= 0) urgencia = Urgencia.AGOTADO;
        else if (existencia <= minimo) urgencia = Urgencia.BAJO_MINIMO;
        else if (cobertura != null && cobertura <= horizonte) urgencia = Urgencia.PRONTO;
        else urgencia = Urgencia.OK;

        double pedido = 0;
        if (urgencia != Urgencia.OK) pedido = redondearPedido(p, Math.max(0, objetivo - existencia));
        if (pedido <= 0) urgencia = Urgencia.OK;

        String motivo = switch (urgencia) {
            case AGOTADO     -> "Agotado" + (ventaDiaria > 0 ? ", se venden " + cantidad(ventaDiaria) + " al día" : "");
            case BAJO_MINIMO -> "Existencia " + cantidad(existencia) + ", mínimo " + cantidad(minimo);
            case PRONTO      -> "Alcanza para " + Math.round(cobertura) + " día(s) y el próximo surtido es en " + horizonte;
            case OK          -> cobertura == null ? "Sin ventas en la ventana" : "Alcanza para " + Math.round(cobertura) + " día(s)";
        };
        double costo = ResumenDiario.VentaRedondeo.centavos(pedido * Math.max(0, p.getPrecioCosto()));
        return new LineaSurtido(p, ventaDiaria, existencia, minimo, cobertura, frecuencia, pedido, urgencia, costo, motivo);
    }

    /**
     * Horizonte de surtido de un proveedor: si visita N días a la semana, el
     * intervalo entre visitas (7 ÷ N, redondeado hacia arriba); si no tiene
     * días de visita, {@link #HORIZONTE_DIAS}.
     */
    public static int horizonteDe(Proveedor proveedor) {
        if (proveedor == null) return HORIZONTE_DIAS;
        int visitas = proveedor.getDias().size();
        return visitas <= 0 ? HORIZONTE_DIAS : (int) Math.ceil(7.0 / visitas);
    }

    /** Piezas enteras si el producto no admite decimales; milésimas si sí */
    static double redondearPedido(Producto p, double cantidad) {
        if (cantidad <= 0) return 0;
        return p.permiteDecimales() ? Math.round(cantidad * 1000.0) / 1000.0 : Math.ceil(cantidad - 1e-9);
    }

    // ── Plan de surtido por proveedor ────────────────────────────────────

    /** Plan de surtido de todos los productos activos con inventario. VER_HISTORIAL. */
    @Transactional(readOnly = true)
    public PlanSurtido planSurtido() {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        LocalDate hoy = estadisticasService.hoy();
        MadurezDatos madurez = estadisticasService.madurezActual();
        int diasVentana = diasVentanaEfectiva(madurez);
        Map<Long, Double> piezas = piezasPorProducto(hoy);
        Map<Long, Proveedor> proveedores = proveedoresPorId();
        Map<Long, Proveedor> proveedorDeProducto = proveedorPorProducto(proveedores);

        Map<String, List<LineaSurtido>> lineasPorGrupo = new HashMap<>();
        Map<String, GrupoSurtido> cabeceras = new HashMap<>();
        for (Producto p : productoRepo.findByUsaInventarioTrueOrderByDescripcionAsc()) {
            if (!p.isActivo()) continue;
            Proveedor prov = proveedorDeProducto.get(p.getId());
            String clave;
            GrupoSurtido cabecera;
            if (prov != null) {
                clave = "P" + prov.getId();
                cabecera = new GrupoSurtido(prov, prov.getNombre(), prov.etiquetaDias(), horizonteDe(prov), null);
            } else {
                String depto = p.getDepartamento() != null ? p.getDepartamento().getNombre() : "Sin departamento";
                clave = "D" + depto.toLowerCase(Locale.ROOT);
                cabecera = new GrupoSurtido(null, SIN_PROVEEDOR + " · " + depto, "—", HORIZONTE_DIAS, null);
            }
            cabeceras.putIfAbsent(clave, cabecera);
            lineasPorGrupo.computeIfAbsent(clave, k -> new ArrayList<>())
                    .add(calcularLinea(p, piezas.getOrDefault(p.getId(), 0.0), diasVentana, cabecera.horizonteDias()));
        }

        boolean conCostos = puedeVerCostos();
        List<GrupoSurtido> grupos = new ArrayList<>();
        for (Map.Entry<String, GrupoSurtido> e : cabeceras.entrySet()) {
            List<LineaSurtido> lineas = lineasPorGrupo.get(e.getKey());
            lineas.sort(Comparator.comparing(LineaSurtido::urgencia)
                    .thenComparing(l -> l.producto().getDescripcion(), String.CASE_INSENSITIVE_ORDER));
            if (!conCostos) lineas = lineas.stream().map(ReabastecimientoService::sinCosto).toList();
            GrupoSurtido c = e.getValue();
            grupos.add(new GrupoSurtido(c.proveedor(), c.nombre(), c.diasVisita(), c.horizonteDias(), List.copyOf(lineas)));
        }
        // Proveedores primero (por nombre), los grupos sin proveedor al final
        grupos.sort(Comparator.comparing((GrupoSurtido g) -> g.proveedor() == null)
                .thenComparing(GrupoSurtido::nombre, String.CASE_INSENSITIVE_ORDER));
        return new PlanSurtido(grupos, diasVentana, conCostos, hoy);
    }

    /**
     * Crea una orden de compra en BORRADOR con las líneas que haya que pedir
     * (pedido > 0) al costo actual de cada producto. Pasa por
     * OrdenCompraService: exige GESTIONAR_PROVEEDORES y queda en la bitácora.
     */
    @Transactional
    public OrdenCompra crearOrdenBorrador(Long proveedorId, List<LineaSurtido> lineas, String notas) {
        List<OrdenCompraService.LineaOrden> pedido = new ArrayList<>();
        if (lineas != null) {
            for (LineaSurtido l : lineas) {
                if (l.hayQuePedir()) {
                    pedido.add(new OrdenCompraService.LineaOrden(l.producto().getId(), l.pedido(),
                            Math.max(0, l.producto().getPrecioCosto())));
                }
            }
        }
        if (pedido.isEmpty()) throw new NegocioException("No hay productos que pedir en este grupo.");
        return ordenCompraService.crear(proveedorId, pedido, notas);
    }

    // ── Productos rezagados ──────────────────────────────────────────────

    /**
     * Productos activos con existencia que no se han vendido en {@code diasSinVenta}
     * días, o cuya existencia alcanza para más de {@link #COBERTURA_EXCESIVA_DIAS}
     * días al ritmo actual. Un producto que nunca se ha vendido cuenta los días
     * desde la primera venta de la tienda. VER_HISTORIAL.
     */
    @Transactional(readOnly = true)
    public Rezagados productosRezagados(int diasSinVenta) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        int umbral = Math.max(1, diasSinVenta);
        LocalDate hoy = estadisticasService.hoy();
        MadurezDatos madurez = estadisticasService.madurezActual();
        int diasVentana = diasVentanaEfectiva(madurez);
        Map<Long, Double> piezas = piezasPorProducto(hoy);

        Map<Long, LocalDate> ultimaVenta = new HashMap<>();
        for (Object[] f : productoDiarioRepo.ultimaVentaPorProducto()) {
            ultimaVenta.put(((Number) f[0]).longValue(), aFecha(f[1]));
        }
        for (ProductoDiario pd : agregadosService.calcularDia(hoy).productos()) {
            if (pd.getPiezas() > 0) ultimaVenta.put(pd.getProducto().getId(), hoy);
        }

        boolean conCostos = puedeVerCostos();
        List<ProductoRezagado> lista = new ArrayList<>();
        double totalVenta = 0, totalCosto = 0;
        for (Producto p : productoRepo.findByUsaInventarioTrueOrderByDescripcionAsc()) {
            if (!p.isActivo() || p.getCantidadActual() <= 0) continue;
            LocalDate ultima = ultimaVenta.get(p.getId());
            long dias = ultima != null ? ChronoUnit.DAYS.between(ultima, hoy) : madurez.diasDesdePrimeraVenta();
            double enVentana = piezas.getOrDefault(p.getId(), 0.0);
            double ventaDiaria = enVentana / Math.max(1, diasVentana);
            Double cobertura = ventaDiaria > 0 ? p.getCantidadActual() / ventaDiaria : null;

            String motivo = motivoRezago(ultima, dias, umbral, cobertura, madurez.hayVentas());
            if (motivo == null) continue;
            double valorVenta = ResumenDiario.VentaRedondeo.centavos(p.getCantidadActual() * Math.max(0, p.getPrecioVenta()));
            double valorCosto = conCostos ? ResumenDiario.VentaRedondeo.centavos(p.getCantidadActual() * Math.max(0, p.getPrecioCosto())) : 0;
            totalVenta += valorVenta;
            totalCosto += valorCosto;
            lista.add(new ProductoRezagado(p, ultima, dias, p.getCantidadActual(), enVentana, cobertura, valorVenta, valorCosto, motivo));
        }
        // Lo que más dinero tiene detenido primero (al costo si se puede ver; si no, a precio de venta)
        lista.sort(Comparator.comparingDouble((ProductoRezagado r) -> conCostos ? r.valorCosto() : r.valorVenta()).reversed()
                .thenComparing(Comparator.comparingLong(ProductoRezagado::diasSinVenta).reversed())
                .thenComparing(r -> r.producto().getDescripcion(), String.CASE_INSENSITIVE_ORDER));
        return new Rezagados(lista, umbral, conCostos, ResumenDiario.VentaRedondeo.centavos(totalVenta),
                ResumenDiario.VentaRedondeo.centavos(totalCosto));
    }

    /**
     * Por qué un producto es rezagado (null = no lo es). Función pura.
     * @param ultimaVenta  último día con venta (null = nunca)
     * @param dias         días sin venta
     * @param cobertura    días de existencia al ritmo actual (null = no se vende)
     * @param hayVentas    si la tienda ya tiene ventas (sin ventas no hay con qué juzgar)
     */
    public static String motivoRezago(LocalDate ultimaVenta, long dias, int umbral, Double cobertura, boolean hayVentas) {
        if (!hayVentas) return null;
        if (ultimaVenta == null && dias >= umbral) return "Sin ventas registradas en " + dias + " días";
        if (ultimaVenta != null && dias >= umbral) return "Sin venta desde hace " + dias + " días";
        if (cobertura != null && cobertura >= COBERTURA_EXCESIVA_DIAS) {
            return "Rotación baja: existencia para " + Math.round(cobertura) + " días";
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Ventana efectiva: en las primeras semanas se divide entre los días que realmente hay (no entre 30) */
    static int diasVentanaEfectiva(MadurezDatos madurez) {
        if (!madurez.hayVentas()) return VENTANA_DIAS;
        return (int) Math.max(1, Math.min(VENTANA_DIAS, madurez.diasDesdePrimeraVenta() + 1));
    }

    /** Piezas vendidas por producto en la ventana (agregados + hoy en vivo) */
    private Map<Long, Double> piezasPorProducto(LocalDate hoy) {
        Map<Long, Double> piezas = new HashMap<>();
        Periodo ventana = Periodo.entre(hoy.minusDays(VENTANA_DIAS - 1L), hoy);
        for (ProductoDiario pd : estadisticasService.productosDiariosEntre(ventana)) {
            piezas.merge(pd.getProducto().getId(), pd.getPiezas(), Double::sum);
        }
        return piezas;
    }

    private Map<Long, Proveedor> proveedoresPorId() {
        Map<Long, Proveedor> r = new LinkedHashMap<>();
        for (Proveedor p : proveedorRepo.findAllByOrderByNombreAsc()) r.put(p.getId(), p);
        return r;
    }

    /** Proveedor de cada producto según su última entrada (por id, o por nombre en entradas antiguas) */
    private Map<Long, Proveedor> proveedorPorProducto(Map<Long, Proveedor> proveedores) {
        Map<String, Proveedor> porNombre = new HashMap<>();
        for (Proveedor p : proveedores.values()) porNombre.put(p.getNombre().trim().toLowerCase(Locale.ROOT), p);
        Map<Long, Proveedor> r = new HashMap<>();
        for (Object[] f : movimientoRepo.ultimoProveedorPorProducto(MovimientoInventario.Tipo.ENTRADA)) {
            long productoId = ((Number) f[0]).longValue();
            Proveedor prov = null;
            if (f[1] != null) prov = proveedores.get(((Number) f[1]).longValue());
            if (prov == null && f[2] != null) prov = porNombre.get(((String) f[2]).trim().toLowerCase(Locale.ROOT));
            if (prov != null) r.put(productoId, prov);
        }
        return r;
    }

    private boolean puedeVerCostos() {
        return sesionService.tienePermiso(Permiso.VER_GANANCIAS) || sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES);
    }

    private static LineaSurtido sinCosto(LineaSurtido l) {
        return new LineaSurtido(l.producto(), l.ventaDiaria(), l.existencia(), l.minimo(), l.diasCobertura(),
                l.frecuenciaDias(), l.pedido(), l.urgencia(), 0, l.motivo());
    }

    private static LocalDate aFecha(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    static String cantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }
}
