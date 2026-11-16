package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Avisos del día para la página móvil (la "campana"): qué proveedor entrega o pasa a
 * preventa hoy y mañana, qué productos se están acabando al ritmo al que se venden y,
 * con suficiente historial, la hora pico de este día de la semana.
 *
 * Los datos de fondo (consultas) se calculan una vez y se guardan en memoria
 * {@link #VIGENCIA_CACHE}; cada usuario recibe la misma lista salvo los importes
 * (nota media del proveedor), que solo ve quien puede ver costos
 * (VER_GANANCIAS o GESTIONAR_PROVEEDORES), igual que en la agenda de proveedores.
 * Nada aquí exige permiso: cualquier usuario con sesión recibe sus avisos.
 */
@Service
public class AvisosService {

    /** Ventana para el ritmo de venta de un producto (piezas por día) */
    public static final int DIAS_VENTANA_STOCK = 14;
    /** Se avisa cuando la existencia alcanza para menos de estos días */
    public static final double DIAS_COBERTURA_AVISO = 2.0;
    /** Productos que se enumeran; el resto se resume en un solo aviso */
    public static final int MAX_PRODUCTOS = 5;
    /** Semanas hacia atrás para la hora pico de este día de la semana */
    public static final int SEMANAS_HORA_PICO = 8;
    /** Sin este historial no se habla de hora pico */
    public static final int DIAS_MINIMOS_HORA_PICO = 30;
    /** Días (del mismo día de la semana) con ventas que hacen falta para fiarse del patrón */
    public static final int MIN_DIAS_HORA_PICO = 3;
    /** La franja pico debe vender al menos este múltiplo de lo que vende una franja promedio */
    static final double FACTOR_PICO = 1.25;
    static final Duration VIGENCIA_CACHE = Duration.ofMinutes(5);

    public enum Tipo {
        PROVEEDOR("Proveedores"),
        STOCK("Se está acabando"),
        HORA_PICO("Hora pico"),
        CADUCIDAD("Por caducar"),
        GANANCIA("Ganancia baja"),
        TICKETS("Tickets por revisar");

        private final String etiqueta;
        Tipo(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
    }

    /** A dónde lleva el aviso en la página móvil (null = solo informa) */
    public record Accion(String seccion, Long proveedorId, String filtro, String buscar) {
        public static Accion proveedor(Long id) { return new Accion("proveedores", id, null, null); }
        public static Accion inventario(String filtro, String buscar) { return new Accion("inventario", null, filtro, buscar); }
    }

    /**
     * @param id        estable dentro del día (la página recuerda los descartados por id)
     * @param prioridad 1 = hoy / urgente, 2 = mañana o bajo el mínimo, 3 = informativo
     * @param icono     nombre del icono de la página (camion, pedido, paquete, reloj)
     */
    public record Aviso(String id, Tipo tipo, int prioridad, String icono, String titulo, String detalle, Accion accion) { }

    public record Avisos(LocalDate hoy, LocalDateTime generado, boolean costosVisibles, List<Aviso> avisos) { }

    /** Franja de dos horas [desde, hasta) con más venta en ese día de la semana */
    public record HoraPico(DayOfWeek dia, int desde, int hasta, int diasConVentas) { }

    // ── Datos de fondo (sin entidades: la caché sobrevive a la transacción) ──

    record VisitaProveedor(long id, String nombre, String contacto, String telefono, boolean preventa, boolean hoy,
                           Double notaMedia) { }

    record ProductoEscaso(long id, String nombre, String unidad, double existencia, double minimo, double ventaDiaria,
                          Double cobertura) { }

    record Base(LocalDate hoy, LocalDateTime generado, List<VisitaProveedor> visitas, List<ProductoEscaso> escasos,
                int escasosTotal, HoraPico horaPico, List<CaducidadService.PorCaducar> caducidades, int diasCaducidad) { }

    /** Id del aviso de caducidades: uno al día (las notificaciones no lo repiten aunque cambie la cuenta) */
    public static final String ID_CADUCIDAD = "caducidad-semana";

    @Autowired private ProveedorRepository  proveedorRepo;
    @Autowired private ProductoRepository   productoRepo;
    @Autowired private VentaRepository      ventaRepo;
    @Autowired private ProveedorService     proveedorService;
    @Autowired private EstadisticasService  estadisticasService;
    @Autowired private SesionService        sesionService;
    @Autowired private CaducidadService     caducidadService;
    @Autowired private Clock                reloj;
    @Autowired private me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService ticketProveedorService;

    @Autowired private me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketsPorRevisarService ticketsPorRevisar;

    private volatile Base cache;

    /** Olvida lo calculado: la próxima consulta vuelve a la base (pruebas y cambios que no pueden esperar) */
    public void invalidarCache() {
        cache = null;
    }

    /** Avisos del usuario de la sesión, ordenados por prioridad. Requiere sesión. */
    @Transactional(readOnly = true)
    public Avisos avisos() {
        return avisos(LocalDateTime.now(reloj));
    }

    @Transactional(readOnly = true)
    Avisos avisos(LocalDateTime ahora) {
        sesionService.requerirSesion();
        boolean costos = proveedorService.puedeVerCostos();
        Base b = base(ahora);
        List<Aviso> lista = new ArrayList<>();
        for (VisitaProveedor v : b.visitas()) lista.add(avisoProveedor(v, costos));
        avisosStock(b, lista);
        if (costos) avisosGanancia(lista);
        Aviso tickets = avisoTicketsPorRevisar();
        if (tickets != null) lista.add(tickets);
        Aviso cad = avisoCaducidad(b.caducidades(), b.diasCaducidad());
        if (cad != null) lista.add(cad);
        if (b.horaPico() != null && ahora.getHour() < b.horaPico().hasta()) lista.add(avisoHoraPico(b.horaPico()));
        // Orden estable: por prioridad y, dentro de ella, como se armó (proveedores por nombre, productos por urgencia)
        lista.sort(Comparator.comparingInt(Aviso::prioridad));
        return new Avisos(b.hoy(), b.generado(), costos, List.copyOf(lista));
    }

    /** Días hacia atrás en que una entrada de mercancía con ganancia baja sigue avisando */
    public static final int DIAS_AVISO_GANANCIA = 7;

    /**
     * Productos que acaban de recibir mercancía y quedaron ganando menos del 10 % sobre el costo
     * (el proveedor subió el costo y el precio de venta no se ha movido). Solo para quien ve costos.
     */
    private void avisosGanancia(List<Aviso> lista) {
        List<me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService.GananciaBaja> bajas;
        try {
            bajas = ticketProveedorService.gananciaBajaTrasEntradas(DIAS_AVISO_GANANCIA);
        } catch (RuntimeException e) {
            return;                                    // un aviso no debe tumbar los demás
        }
        int n = 0;
        for (var g : bajas) {
            if (n++ >= MAX_PRODUCTOS) break;
            String detalle = String.format(java.util.Locale.ROOT, "Cuesta $%.2f y se vende en $%.2f (ganas %s): considera subirle el precio.",
                    g.costo(), g.precioVenta(), g.ganancia() < 0 ? "nada, pierdes" : Math.round(g.ganancia() * 100) + " %");
            lista.add(new Aviso("ganancia-" + g.productoId(), Tipo.GANANCIA, 2, "sube", g.descripcion(), detalle,
                    Accion.inventario(null, g.descripcion())));
        }
    }

    /** Fotos de tickets mandadas desde el teléfono que esperan revisión en la computadora (fuera de la caché: al momento) */
    private Aviso avisoTicketsPorRevisar() {
        long n;
        try {
            n = ticketsPorRevisar.cuantosPendientes();
        } catch (RuntimeException e) {
            return null;
        }
        if (n <= 0) return null;
        return new Aviso("tickets-por-revisar", Tipo.TICKETS, 2, "recibo",
                n == 1 ? "1 ticket de proveedor por revisar" : n + " tickets de proveedor por revisar",
                "Se mandaron desde el teléfono. En la computadora: Inventario → Por revisar.", null);
    }

    /** Hora pico de hoy (null si no hay patrón): la usa el programador de notificaciones para avisar 30 min antes */
    @Transactional(readOnly = true)
    public HoraPico horaPicoHoy(LocalDateTime ahora) {
        return base(ahora).horaPico();
    }

    // ── Caché ─────────────────────────────────────────────────────────────

    private Base base(LocalDateTime ahora) {
        Base b = cache;
        if (vigente(b, ahora)) return b;
        synchronized (this) {
            b = cache;
            if (vigente(b, ahora)) return b;
            b = calcular(ahora);
            cache = b;
            return b;
        }
    }

    private static boolean vigente(Base b, LocalDateTime ahora) {
        if (b == null || !b.hoy().equals(ahora.toLocalDate())) return false;
        Duration edad = Duration.between(b.generado(), ahora);
        return !edad.isNegative() && edad.compareTo(VIGENCIA_CACHE) < 0;
    }

    Base calcular(LocalDateTime ahora) {
        LocalDate hoy = ahora.toLocalDate();
        Optional<LocalDate> primeraVenta = ventaRepo.findPrimeraActiva().map(v -> v.getFecha().toLocalDate());
        List<ProductoEscaso> escasos = productosEscasos(hoy, primeraVenta);
        List<ProductoEscaso> recorte = escasos.subList(0, Math.min(MAX_PRODUCTOS, escasos.size()));
        int diasCad = caducidadService.diasAviso();
        return new Base(hoy, ahora, visitasProveedores(hoy), List.copyOf(recorte), escasos.size(), horaPico(hoy, primeraVenta),
                List.copyOf(caducidadService.porCaducar(hoy, diasCad)), diasCad);
    }

    // ── Proveedores: hoy y mañana ─────────────────────────────────────────

    List<VisitaProveedor> visitasProveedores(LocalDate hoy) {
        DayOfWeek dHoy = hoy.getDayOfWeek(), dManana = hoy.plusDays(1).getDayOfWeek();
        Map<Long, ProveedorService.NotaMedia> notas = proveedorService.notasMedias(hoy);
        List<VisitaProveedor> r = new ArrayList<>();
        for (Proveedor p : proveedorRepo.findByActivoTrueOrderByNombreAsc()) {
            ProveedorService.NotaMedia n = notas.get(p.getId());
            Double nota = n == null ? null : n.promedio();
            if (p.visitaEn(dHoy))      r.add(visita(p, false, true, nota));
            if (p.preventaEn(dHoy))    r.add(visita(p, true, true, nota));
            if (p.visitaEn(dManana))   r.add(visita(p, false, false, nota));
            if (p.preventaEn(dManana)) r.add(visita(p, true, false, nota));
        }
        return r;
    }

    private static VisitaProveedor visita(Proveedor p, boolean preventa, boolean hoy, Double nota) {
        return new VisitaProveedor(p.getId(), p.getNombre(), limpio(p.getContacto()), limpio(p.getTelefono()), preventa, hoy, nota);
    }

    private static String limpio(String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }

    static Aviso avisoProveedor(VisitaProveedor v, boolean costos) {
        String titulo;
        StringBuilder detalle = new StringBuilder();
        if (v.preventa()) {
            titulo = (v.hoy() ? "Hoy pasa el preventa de " : "Mañana pasa el preventa de ") + v.nombre();
            detalle.append(v.hoy() ? "Prepara el pedido: revisa qué se está acabando." : "Ve pensando el pedido para mañana.");
            if (costos && v.notaMedia() != null) detalle.append(" Suele comprarse ").append(dinero(v.notaMedia())).append(" por visita.");
        } else {
            titulo = (v.hoy() ? "Hoy entrega " : "Mañana viene ") + v.nombre();
            detalle.append(v.hoy() ? "Llega la mercancía: revísala y regístrala al recibirla." : "Llega la mercancía mañana.");
        }
        if (!v.contacto().isEmpty()) detalle.append(" Contacto: ").append(v.contacto()).append('.');
        else if (!v.telefono().isEmpty()) detalle.append(" Tel. ").append(v.telefono()).append('.');
        String id = "prov-" + (v.preventa() ? "p" : "e") + "-" + v.id() + (v.hoy() ? "-hoy" : "-manana");
        return new Aviso(id, Tipo.PROVEEDOR, v.hoy() ? 1 : 2, v.preventa() ? "pedido" : "camion", titulo, detalle.toString(),
                Accion.proveedor(v.id()));
    }

    // ── Productos que se acaban ───────────────────────────────────────────

    /**
     * Productos activos con inventario cuya existencia alcanza para menos de
     * {@link #DIAS_COBERTURA_AVISO} días al ritmo de la ventana, o que ya están en
     * o bajo su mínimo (si tienen mínimo). Un producto agotado sin ventas y sin
     * mínimo no es aviso: nunca se ha surtido o ya no se maneja.
     * Ordenados de más a menos urgente.
     */
    List<ProductoEscaso> productosEscasos(LocalDate hoy, Optional<LocalDate> primeraVenta) {
        int dias = DIAS_VENTANA_STOCK;
        if (primeraVenta.isPresent()) {
            dias = (int) Math.max(1, Math.min(DIAS_VENTANA_STOCK, ChronoUnit.DAYS.between(primeraVenta.get(), hoy) + 1));
        }
        Map<Long, Double> piezas = new HashMap<>();
        Periodo ventana = Periodo.entre(hoy.minusDays(DIAS_VENTANA_STOCK - 1L), hoy);
        for (ProductoDiario pd : estadisticasService.productosDiariosEntre(ventana)) {
            piezas.merge(pd.getProducto().getId(), pd.getPiezas(), Double::sum);
        }
        List<ProductoEscaso> r = new ArrayList<>();
        for (Producto p : productoRepo.findByUsaInventarioTrueOrderByDescripcionAsc()) {
            if (!p.isActivo()) continue;
            double ventaDiaria = piezas.getOrDefault(p.getId(), 0.0) / dias;
            ProductoEscaso e = evaluar(p, ventaDiaria);
            if (e != null) r.add(e);
        }
        r.sort(Comparator.comparingDouble(AvisosService::claveUrgencia)
                .thenComparing(Comparator.comparingDouble(ProductoEscaso::ventaDiaria).reversed())
                .thenComparing(ProductoEscaso::nombre, String.CASE_INSENSITIVE_ORDER));
        return r;
    }

    /** null si el producto no es aviso */
    static ProductoEscaso evaluar(Producto p, double ventaDiaria) {
        double existencia = p.getCantidadActual();
        double minimo = Math.max(0, p.getCantidadMinima());
        Double cobertura = ventaDiaria > 0 ? Math.max(0, existencia) / ventaDiaria : null;
        boolean porRitmo = cobertura != null && cobertura < DIAS_COBERTURA_AVISO;
        boolean porMinimo = minimo > 0 && existencia <= minimo;
        if (!porRitmo && !porMinimo) return null;
        return new ProductoEscaso(p.getId(), p.getDescripcion(), unidadDe(p), existencia, minimo, ventaDiaria, cobertura);
    }

    /** Menor = más urgente: primero por días de cobertura (0 = agotado), después los que solo están bajo el mínimo */
    static double claveUrgencia(ProductoEscaso e) {
        if (e.cobertura() != null && e.cobertura() < DIAS_COBERTURA_AVISO) return e.cobertura();
        return DIAS_COBERTURA_AVISO + (e.minimo() > 0 ? Math.max(0, e.existencia()) / e.minimo() : 1);
    }

    private static void avisosStock(Base b, List<Aviso> lista) {
        for (ProductoEscaso e : b.escasos()) lista.add(avisoStock(e));
        int resto = b.escasosTotal() - b.escasos().size();
        if (resto > 0) {
            lista.add(new Aviso("stock-mas", Tipo.STOCK, 2, "paquete",
                    resto == 1 ? "Otro producto con poca existencia" : "Otros " + resto + " productos con poca existencia",
                    "Ábrelos en Inventario con el filtro de stock bajo.", Accion.inventario("bajo", null)));
        }
    }

    static Aviso avisoStock(ProductoEscaso e) {
        boolean agotado = e.existencia() <= 0;
        boolean porRitmo = e.cobertura() != null && e.cobertura() < DIAS_COBERTURA_AVISO;
        String detalle;
        if (agotado) {
            detalle = "Agotado" + (e.ventaDiaria() > 0 ? ", vendes ~" + cantidad(e.ventaDiaria()) + unidadTexto(e) + " al día." : ".");
        } else if (porRitmo) {
            detalle = "Quedan " + cantidad(e.existencia()) + unidadTexto(e) + " y vendes ~" + cantidad(e.ventaDiaria()) + unidadTexto(e) + " al día.";
        } else {
            detalle = "Quedan " + cantidad(e.existencia()) + unidadTexto(e) + " (mínimo " + cantidad(e.minimo()) + ").";
        }
        int prioridad = agotado || porRitmo ? 1 : 2;
        return new Aviso("stock-" + e.id(), Tipo.STOCK, prioridad, "paquete", e.nombre(), detalle, Accion.inventario(null, e.nombre()));
    }

    private static String unidadTexto(ProductoEscaso e) {
        return e.unidad().equals("pza") ? "" : " " + e.unidad();
    }

    static String unidadDe(Producto p) {
        if (p.getUnidad() != null && p.getUnidad().getAbreviatura() != null && !p.getUnidad().getAbreviatura().isBlank()) {
            return p.getUnidad().getAbreviatura().trim();
        }
        return p.getTipoVenta() == Producto.TipoVenta.GRANEL ? "kg" : "pza";
    }

    /** "8", "0.5", "1.25": sin ceros de sobra; menos de 1 con un decimal significativo */
    static String cantidad(double v) {
        if (v >= 1) {
            long redondo = Math.round(v);
            if (Math.abs(v - redondo) < 0.05 || v >= 10) return String.valueOf(redondo);
            return String.format(Locale.ROOT, "%.1f", v);
        }
        if (v <= 0) return "0";
        String s = String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static String dinero(double v) {
        return "$" + String.format(Locale.ROOT, "%,.2f", v);
    }

    // ── Caducidades ───────────────────────────────────────────────────────

    /**
     * Un solo aviso con lo que caduca dentro de {@code dias} días o ya caducó (null si no hay nada):
     * "3 productos caducan esta semana" (o "en los próximos N días"). Cuenta productos, no
     * entradas. Prioridad 1 si algo ya caducó, 2 si no. Función pura.
     */
    static Aviso avisoCaducidad(List<CaducidadService.PorCaducar> lotes, int dias) {
        if (lotes == null || lotes.isEmpty()) return null;
        java.util.LinkedHashSet<String> nombres = new java.util.LinkedHashSet<>();
        java.util.Set<Long> productos = new java.util.HashSet<>(), caducados = new java.util.HashSet<>();
        for (CaducidadService.PorCaducar l : lotes) {
            productos.add(l.productoId());
            nombres.add(l.producto());
            if (l.caducado()) caducados.add(l.productoId());
        }
        int n = productos.size();
        String plazo = dias == 7 ? "esta semana" : dias == 1 ? "hoy o mañana" : "en los próximos " + dias + " días";
        String titulo = n == 1 ? "1 producto caduca " + plazo : n + " productos caducan " + plazo;
        StringBuilder detalle = new StringBuilder();
        if (!caducados.isEmpty()) {
            detalle.append(caducados.size() == 1 ? "1 ya caducó: retíralo de la venta. " : caducados.size() + " ya caducaron: retíralos de la venta. ");
        }
        List<String> lista = new ArrayList<>(nombres);
        detalle.append(String.join(", ", lista.subList(0, Math.min(MAX_PRODUCTOS, lista.size()))));
        if (lista.size() > MAX_PRODUCTOS) detalle.append(" y ").append(lista.size() - MAX_PRODUCTOS).append(" más");
        detalle.append('.');
        return new Aviso(ID_CADUCIDAD, Tipo.CADUCIDAD, caducados.isEmpty() ? 2 : 1, "calendario", titulo, detalle.toString(),
                Accion.inventario("caducar", null));
    }

    // ── Hora pico de este día de la semana ────────────────────────────────

    /**
     * Franja de dos horas con más venta en los últimos {@link #SEMANAS_HORA_PICO} mismos
     * días de la semana (hoy no cuenta: va a medias). Solo con {@link #DIAS_MINIMOS_HORA_PICO}
     * días de historial y {@link #MIN_DIAS_HORA_PICO} de esos días con ventas.
     */
    HoraPico horaPico(LocalDate hoy, Optional<LocalDate> primeraVenta) {
        if (primeraVenta.isEmpty() || ChronoUnit.DAYS.between(primeraVenta.get(), hoy) < DIAS_MINIMOS_HORA_PICO) return null;
        double[] porHora = new double[24];
        int diasConVentas = 0;
        for (int s = 1; s <= SEMANAS_HORA_PICO; s++) {
            LocalDate dia = hoy.minusWeeks(s);
            boolean hubo = false;
            for (Object[] f : ventaRepo.fechasYTotalesActivas(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay(), null)) {
                porHora[((LocalDateTime) f[0]).getHour()] += ((Number) f[1]).doubleValue();
                hubo = true;
            }
            if (hubo) diasConVentas++;
        }
        if (diasConVentas < MIN_DIAS_HORA_PICO) return null;
        int[] ventana = ventanaPico(porHora);
        return ventana == null ? null : new HoraPico(hoy.getDayOfWeek(), ventana[0], ventana[1], diasConVentas);
    }

    /**
     * Franja de dos horas consecutivas con más venta: {desde, hasta} con hasta = desde + 2.
     * null si no hay ventas o la franja no destaca ({@link #FACTOR_PICO} veces una franja
     * promedio entre las horas con venta). Función pura.
     */
    static int[] ventanaPico(double[] porHora) {
        double total = 0;
        int horasConVenta = 0;
        for (double v : porHora) { total += v; if (v > 0) horasConVenta++; }
        if (total <= 0) return null;
        int mejor = -1;
        double mejorSuma = 0;
        for (int h = 0; h <= 22; h++) {
            double suma = porHora[h] + porHora[h + 1];
            // A igual suma gana la que empieza en la hora más fuerte (una sola hora con venta: la franja arranca ahí)
            if (suma > mejorSuma || (suma == mejorSuma && mejor >= 0 && porHora[h] > porHora[mejor])) { mejorSuma = suma; mejor = h; }
        }
        if (mejor < 0) return null;
        double franjaPromedio = total / Math.max(1, horasConVenta) * 2;
        if (horasConVenta > 2 && mejorSuma < franjaPromedio * FACTOR_PICO) return null;
        return new int[] { mejor, mejor + 2 };
    }

    static Aviso avisoHoraPico(HoraPico p) {
        String titulo = "Los " + plural(p.dia()) + " vendes más de " + hora(p.desde()) + " a " + hora(p.hasta());
        String detalle = "Según las últimas " + p.diasConVentas() + " semanas con ventas. Ten cambio y la caja lista.";
        return new Aviso("pico-" + p.dia().getValue(), Tipo.HORA_PICO, 3, "reloj", titulo, detalle, null);
    }

    static String plural(DayOfWeek d) {
        return switch (d) {
            case MONDAY    -> "lunes";
            case TUESDAY   -> "martes";
            case WEDNESDAY -> "miércoles";
            case THURSDAY  -> "jueves";
            case FRIDAY    -> "viernes";
            case SATURDAY  -> "sábados";
            case SUNDAY    -> "domingos";
        };
    }

    private static String hora(int h) {
        return String.format(Locale.ROOT, "%02d:00", h);
    }
}
