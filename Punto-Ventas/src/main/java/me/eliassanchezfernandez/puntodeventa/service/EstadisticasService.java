package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.NivelDatos;
import me.eliassanchezfernandez.puntodeventa.model.ParProductos;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoClima;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ClimaRegistroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ParProductosRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoClimaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ResumenDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Servicio de Estadísticas (CRT-10): lee los agregados que mantiene
 * AgregadosService y completa "hoy" en vivo, para que la pantalla nunca
 * recorra todas las ventas. Todas las consultas de ventas exigen
 * VER_HISTORIAL (igual que el historial de ventas, HIS-03); aquí no aparece
 * ningún costo ni ganancia, por eso no se pide VER_GANANCIAS.
 *
 * El "nivel de datos" (cold start) es el que ya existía: días transcurridos
 * desde la primera venta. Lectura de una sola fila; no requiere permiso.
 */
@Service
public class EstadisticasService {

    @Autowired private VentaRepository          ventaRepository;
    @Autowired private ResumenDiarioRepository  resumenRepo;
    @Autowired private ProductoDiarioRepository productoDiarioRepo;
    @Autowired private ParProductosRepository   paresRepo;
    @Autowired private ProductoClimaRepository  productoClimaRepo;
    @Autowired private ClimaRegistroRepository  climaRepo;
    @Autowired private AgregadosService         agregadosService;
    @Autowired private SesionService            sesionService;

    /** Reloj inyectable; por defecto el del sistema (la prueba unitaria lo instancia sin Spring) */
    @Autowired(required = false)
    private Clock reloj = Clock.systemDefaultZone();

    // ── Nivel y madurez de datos (cold start) ────────────────────────────

    /** Días de historial a partir de los cuales las recomendaciones se consideran confiables (~un mes) */
    public static final int DIAS_CONFIABLES = 30;
    /** Hasta este día (exclusivo) el nivel es "Entrenamiento" */
    public static final int DIAS_ENTRENAMIENTO = 7;

    /** Nivel según la fecha de la primera venta registrada; SIN_DATOS si no hay ventas */
    public NivelDatos nivelDatosActual() {
        return ventaRepository.findPrimeraActiva()
                .map(v -> nivelDatos(ChronoUnit.DAYS.between(v.getFecha().toLocalDate(), LocalDate.now(reloj))))
                .orElse(NivelDatos.SIN_DATOS);
    }

    /** Función pura: 0–6 días = entrenamiento, 7–29 = parcial, 30 o más = suficiente */
    public NivelDatos nivelDatos(long dias) {
        if (dias < DIAS_ENTRENAMIENTO) return NivelDatos.ENTRENAMIENTO;
        if (dias < DIAS_CONFIABLES)    return NivelDatos.PARCIAL;
        return NivelDatos.SUFICIENTE;
    }

    /**
     * Madurez de los datos: cuántos días lleva la tienda vendiendo (desde la
     * primera venta), en cuántos de ellos hubo ventas, el nivel y un factor
     * 0..1 con el que se acota la confianza de las recomendaciones.
     *
     * @param diasDesdePrimeraVenta días de calendario desde la primera venta (0 si la primera venta fue hoy)
     * @param diasConVentas         días distintos con al menos una venta activa
     */
    public record MadurezDatos(long diasDesdePrimeraVenta, long diasConVentas, NivelDatos nivel) {

        public static final MadurezDatos SIN_DATOS = new MadurezDatos(0, 0, NivelDatos.SIN_DATOS);

        public boolean hayVentas()  { return nivel != NivelDatos.SIN_DATOS; }
        public boolean confiable()  { return hayVentas() && diasDesdePrimeraVenta >= DIAS_CONFIABLES; }
        public long diasFaltantes() { return Math.max(0, DIAS_CONFIABLES - diasDesdePrimeraVenta); }

        /**
         * Factor 0..1 que crece linealmente con los días de historial (día 0 = 0,
         * día 30 o más = 1). Multiplica el tope de confianza de las recomendaciones.
         */
        public double factorConfianza() {
            if (!hayVentas()) return 0;
            return Math.min(1.0, Math.max(0.0, (double) diasDesdePrimeraVenta / DIAS_CONFIABLES));
        }

        /** Porcentaje 0..100 del factor de confianza (para la pantalla) */
        public int porcentajeConfianza() { return (int) Math.round(factorConfianza() * 100); }

        /** Texto para la pantalla: "Datos de N días…" */
        public String mensaje() {
            if (!hayVentas()) return "Aún no hay ventas registradas. Las estadísticas aparecerán con las primeras ventas.";
            String dias = diasDesdePrimeraVenta == 1 ? "1 día" : diasDesdePrimeraVenta + " días";
            if (confiable()) {
                return "Datos de " + dias + " (" + diasConVentas + " con ventas): hay historial suficiente, "
                        + "las estadísticas y recomendaciones son confiables.";
            }
            return "Datos de " + dias + " (" + diasConVentas + " con ventas): las recomendaciones serán más "
                    + "confiables a partir de " + DIAS_CONFIABLES + " días (faltan " + diasFaltantes() + "). "
                    + "Mientras tanto el modelo se está entrenando con las ventas.";
        }
    }

    /** Madurez actual: lectura de la primera venta, de resumen_diario y de "hoy" en vivo. No requiere permiso. */
    @Transactional(readOnly = true)
    public MadurezDatos madurezActual() {
        Optional<Venta> primera = ventaRepository.findPrimeraActiva();
        if (primera.isEmpty()) return MadurezDatos.SIN_DATOS;
        LocalDate hoy = hoy();
        long dias = Math.max(0, ChronoUnit.DAYS.between(primera.get().getFecha().toLocalDate(), hoy));
        long conVentas = resumenRepo.countByFechaBefore(hoy);
        if (agregadosService.calcularDia(hoy).resumen().getTransacciones() > 0) conVentas++;
        return new MadurezDatos(dias, Math.max(conVentas, 1), nivelDatos(dias));
    }

    public LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    // ── Periodos ─────────────────────────────────────────────────────────

    /** Rango de fechas inclusive con su nombre para la pantalla */
    public record Periodo(LocalDate desde, LocalDate hasta, String etiqueta) {

        public Periodo {
            if (desde == null || hasta == null) throw new NegocioException("Indica las dos fechas del periodo.");
            if (hasta.isBefore(desde)) throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
            if (etiqueta == null) etiqueta = desde.equals(hasta) ? desde.toString() : desde + " a " + hasta;
        }

        public static Periodo dia(LocalDate dia)   { return new Periodo(dia, dia, "Hoy"); }
        /** Lunes de la semana → hoy */
        public static Periodo semana(LocalDate hoy) { return new Periodo(hoy.with(DayOfWeek.MONDAY), hoy, "Semana"); }
        public static Periodo mes(LocalDate hoy)    { return new Periodo(hoy.withDayOfMonth(1), hoy, "Mes"); }
        public static Periodo anio(LocalDate hoy)   { return new Periodo(hoy.withDayOfYear(1), hoy, "Año"); }
        public static Periodo entre(LocalDate d, LocalDate h) { return new Periodo(d, h, null); }

        public long dias() { return ChronoUnit.DAYS.between(desde, hasta) + 1; }
        public boolean incluye(LocalDate f) { return !f.isBefore(desde) && !f.isAfter(hasta); }
    }

    /** Totales de un periodo */
    public record Totales(double total, int transacciones, double piezas) {
        public static final Totales CERO = new Totales(0, 0, 0);
        public double ticketPromedio() { return transacciones == 0 ? 0 : total / transacciones; }
    }

    /**
     * Comparación entre dos periodos. variacionPct es null cuando el periodo
     * anterior no tuvo ventas (no hay con qué comparar; nunca se divide entre 0).
     */
    public record Comparacion(String etiqueta, String detalle, Periodo periodoActual, Periodo periodoAnterior,
                              Totales actual, Totales anterior) {
        public Double variacionPct() {
            if (anterior.total() <= 0) return null;
            return (actual.total() - anterior.total()) / anterior.total() * 100.0;
        }
    }

    public record ProductoTop(Producto producto, double piezas, double importe) { }

    /** Cómo se ordena el ranking de productos más vendidos */
    public enum CriterioTop {
        IMPORTE("Por importe"), PIEZAS("Por piezas");

        private final String etiqueta;
        CriterioTop(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
    }

    /** Ranking del periodo con el total del periodo (para el "% del total") */
    public record RankingProductos(List<ProductoTop> productos, double piezasTotal, double importeTotal, CriterioTop criterio) {
        public double porcentaje(ProductoTop t) {
            double total = criterio == CriterioTop.PIEZAS ? piezasTotal : importeTotal;
            double valor = criterio == CriterioTop.PIEZAS ? t.piezas() : t.importe();
            return total <= 0 ? 0 : valor / total * 100.0;
        }
    }

    public record VentasClima(String categoria, int ventas, double importe) { }

    // ── Resúmenes por día (agregados + hoy en vivo) ──────────────────────

    /**
     * Resúmenes diarios del periodo: los días pasados vienen de resumen_diario
     * y "hoy" se calcula en vivo de las ventas. Solo días con ventas.
     */
    @Transactional(readOnly = true)
    public List<ResumenDiario> resumenesEntre(Periodo p) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        LocalDate hoy = hoy();
        List<ResumenDiario> lista = new ArrayList<>();
        if (p.desde().isBefore(hoy)) {
            LocalDate hasta = p.hasta().isBefore(hoy) ? p.hasta() : hoy.minusDays(1);
            lista.addAll(resumenRepo.findByFechaBetweenOrderByFechaAsc(p.desde(), hasta));
        }
        if (p.incluye(hoy)) {
            ResumenDiario deHoy = agregadosService.calcularDia(hoy).resumen();
            if (deHoy.getTransacciones() > 0) lista.add(deHoy);
        }
        // Días futuros del periodo (p. ej. un rango personalizado) no tienen ventas
        return lista;
    }

    @Transactional(readOnly = true)
    public Totales totales(Periodo p) {
        return totalesDe(resumenesEntre(p));
    }

    private static Totales totalesDe(List<ResumenDiario> resumenes) {
        double total = 0, piezas = 0;
        int trans = 0;
        for (ResumenDiario r : resumenes) {
            total += r.getTotal();
            piezas += r.getPiezas();
            trans += r.getTransacciones();
        }
        return new Totales(ResumenDiario.VentaRedondeo.centavos(total), trans, ResumenDiario.VentaRedondeo.milesimas(piezas));
    }

    /** Total por cada día del periodo, con ceros en los días sin ventas (para la gráfica) */
    @Transactional(readOnly = true)
    public Map<LocalDate, Double> serieDiaria(Periodo p) {
        Map<LocalDate, Double> serie = new TreeMap<>();
        for (LocalDate d = p.desde(); !d.isAfter(p.hasta()); d = d.plusDays(1)) serie.put(d, 0.0);
        for (ResumenDiario r : resumenesEntre(p)) serie.put(r.getFecha(), r.getTotal());
        return serie;
    }

    /** Total vendido por día de la semana (índice 0 = lunes … 6 = domingo) */
    @Transactional(readOnly = true)
    public double[] porDiaSemana(Periodo p) {
        double[] r = new double[7];
        for (ResumenDiario d : resumenesEntre(p)) r[d.getFecha().getDayOfWeek().getValue() - 1] += d.getTotal();
        return r;
    }

    /** Día de la semana con más ventas en el periodo (null si no hubo ventas) */
    public static DayOfWeek diaConMasVentas(double[] porDiaSemana) {
        int mejor = -1;
        for (int i = 0; i < porDiaSemana.length; i++) {
            if (porDiaSemana[i] > 0 && (mejor < 0 || porDiaSemana[i] > porDiaSemana[mejor])) mejor = i;
        }
        return mejor < 0 ? null : DayOfWeek.of(mejor + 1);
    }

    /** Total y tickets por franja horaria */
    @Transactional(readOnly = true)
    public Map<Franja, Totales> porFranja(Periodo p) {
        Map<Franja, double[]> acumulado = new EnumMap<>(Franja.class);
        for (Franja f : Franja.values()) acumulado.put(f, new double[2]);
        for (ResumenDiario d : resumenesEntre(p)) {
            for (Franja f : Franja.values()) {
                acumulado.get(f)[0] += d.totalDe(f);
                acumulado.get(f)[1] += d.transaccionesDe(f);
            }
        }
        Map<Franja, Totales> r = new EnumMap<>(Franja.class);
        acumulado.forEach((f, a) -> r.put(f, new Totales(ResumenDiario.VentaRedondeo.centavos(a[0]), (int) a[1], 0)));
        return r;
    }

    /** Mapa de calor [día de la semana 0..6][franja en el orden de Franja.values()] con el total vendido */
    @Transactional(readOnly = true)
    public double[][] mapaCalor(Periodo p) {
        return mapaCalorDe(resumenesEntre(p));
    }

    /** Función pura (probada): suma los resúmenes en la rejilla día × franja */
    public static double[][] mapaCalorDe(List<ResumenDiario> resumenes) {
        Franja[] franjas = Franja.values();
        double[][] m = new double[7][franjas.length];
        for (ResumenDiario d : resumenes) {
            int dia = d.getFecha().getDayOfWeek().getValue() - 1;
            for (int f = 0; f < franjas.length; f++) m[dia][f] += d.totalDe(franjas[f]);
        }
        return m;
    }

    /** Intensidad 0..3 de una celda respecto al máximo del mapa (0 = sin ventas) */
    public static int intensidad(double valor, double maximo) {
        if (valor <= 0 || maximo <= 0) return 0;
        double r = valor / maximo;
        if (r >= 0.66) return 3;
        if (r >= 0.33) return 2;
        return 1;
    }

    // ── Productos más vendidos ───────────────────────────────────────────

    /** Ranking por importe del periodo (producto_diario + hoy en vivo) */
    @Transactional(readOnly = true)
    public List<ProductoTop> topProductos(Periodo p, int maximo) {
        return ranking(p, maximo, CriterioTop.IMPORTE).productos();
    }

    /** Ranking del periodo por importe o por piezas, con los totales del periodo para el "% del total" */
    @Transactional(readOnly = true)
    public RankingProductos ranking(Periodo p, int maximo, CriterioTop criterio) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        CriterioTop c = criterio == null ? CriterioTop.IMPORTE : criterio;
        Map<Long, double[]> acumulado = new HashMap<>();
        Map<Long, Producto> productos = new HashMap<>();
        double piezasTotal = 0, importeTotal = 0;
        for (ProductoDiario pd : productosDiariosEntre(p)) {
            Producto prod = pd.getProducto();
            productos.put(prod.getId(), prod);
            double[] a = acumulado.computeIfAbsent(prod.getId(), k -> new double[2]);
            a[0] += pd.getPiezas();
            a[1] += pd.getImporte();
            piezasTotal += pd.getPiezas();
            importeTotal += pd.getImporte();
        }
        List<ProductoTop> lista = new ArrayList<>();
        acumulado.forEach((id, a) -> lista.add(new ProductoTop(productos.get(id),
                ResumenDiario.VentaRedondeo.milesimas(a[0]), ResumenDiario.VentaRedondeo.centavos(a[1]))));
        Comparator<ProductoTop> orden = c == CriterioTop.PIEZAS
                ? Comparator.comparingDouble(ProductoTop::piezas).reversed()
                        .thenComparing(Comparator.comparingDouble(ProductoTop::importe).reversed())
                : Comparator.comparingDouble(ProductoTop::importe).reversed()
                        .thenComparing(Comparator.comparingDouble(ProductoTop::piezas).reversed());
        lista.sort(orden.thenComparing(t -> t.producto().getId()));
        return new RankingProductos(lista.subList(0, Math.min(maximo, lista.size())),
                ResumenDiario.VentaRedondeo.milesimas(piezasTotal), ResumenDiario.VentaRedondeo.centavos(importeTotal), c);
    }

    /** Filas producto-día del periodo; "hoy" en vivo */
    @Transactional(readOnly = true)
    public List<ProductoDiario> productosDiariosEntre(Periodo p) {
        LocalDate hoy = hoy();
        List<ProductoDiario> lista = new ArrayList<>();
        if (p.desde().isBefore(hoy)) {
            LocalDate hasta = p.hasta().isBefore(hoy) ? p.hasta() : hoy.minusDays(1);
            lista.addAll(productoDiarioRepo.findEntre(p.desde(), hasta));
        }
        if (p.incluye(hoy)) lista.addAll(agregadosService.calcularDia(hoy).productos());
        return lista;
    }

    // ── Comparaciones ────────────────────────────────────────────────────

    /**
     * Las cuatro comparaciones de la pantalla: hoy vs el mismo día de la
     * semana pasada; semana, mes y año "a la fecha" contra el mismo tramo
     * del periodo anterior (así un martes no se compara con una semana completa).
     */
    @Transactional(readOnly = true)
    public List<Comparacion> comparaciones() {
        LocalDate hoy = hoy();
        List<Comparacion> r = new ArrayList<>();
        r.add(comparar("Hoy", "vs. el mismo día de la semana pasada",
                Periodo.dia(hoy), Periodo.dia(hoy.minusWeeks(1))));
        r.add(comparar("Semana", "vs. el mismo tramo de la semana pasada",
                Periodo.semana(hoy), new Periodo(hoy.with(DayOfWeek.MONDAY).minusWeeks(1), hoy.minusWeeks(1), "Semana anterior")));
        LocalDate mesAnterior = hoy.minusMonths(1);
        r.add(comparar("Mes", "vs. los mismos días del mes pasado",
                Periodo.mes(hoy), new Periodo(mesAnterior.withDayOfMonth(1), mesAnterior, "Mes anterior")));
        LocalDate anioAnterior = hoy.minusYears(1);
        r.add(comparar("Año", "vs. los mismos días del año pasado",
                Periodo.anio(hoy), new Periodo(anioAnterior.withDayOfYear(1), anioAnterior, "Año anterior")));
        return r;
    }

    private Comparacion comparar(String etiqueta, String detalle, Periodo actual, Periodo anterior) {
        return new Comparacion(etiqueta, detalle, actual, anterior, totales(actual), totales(anterior));
    }

    // ── Recomendaciones y clima (lectura para la pantalla) ───────────────

    /** Pares "se compran juntos" más frecuentes (A → B, sin repetir el par invertido) */
    @Transactional(readOnly = true)
    public List<ParProductos> paresTop(int maximo) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        List<ParProductos> r = new ArrayList<>();
        java.util.Set<String> vistos = new java.util.HashSet<>();
        for (ParProductos p : paresRepo.findTodosOrdenados()) {
            long a = p.getProductoA().getId(), b = p.getProductoB().getId();
            if (!vistos.add(Math.min(a, b) + "-" + Math.max(a, b))) continue;
            r.add(p);
            if (r.size() >= maximo) break;
        }
        return r;
    }

    /** Correlación producto × clima aprendida (índice relativo), de mayor a menor */
    @Transactional(readOnly = true)
    public List<ProductoClima> correlacionClima() {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        return productoClimaRepo.findTodosOrdenados();
    }

    /**
     * Ventas del periodo agrupadas por la categoría del clima de su hora.
     * Las ventas sin clima registrado se agrupan como "Sin datos de clima".
     */
    @Transactional(readOnly = true)
    public List<VentasClima> ventasPorClima(Periodo p) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        LocalDateTime inicio = p.desde().atStartOfDay(), fin = p.hasta().plusDays(1).atStartOfDay();
        Map<LocalDateTime, ClimaRegistro> porHora = new HashMap<>();
        for (ClimaRegistro c : climaRepo.findByFechaHoraGreaterThanEqualAndFechaHoraLessThanOrderByFechaHoraAsc(inicio, fin)) {
            porHora.put(c.getFechaHora().truncatedTo(ChronoUnit.HOURS), c);
        }
        Map<String, double[]> acumulado = new LinkedHashMap<>();
        for (Venta v : ventaRepository.findActivasEntre(inicio, fin)) {
            ClimaRegistro c = porHora.get(v.getFecha().truncatedTo(ChronoUnit.HOURS));
            String clave = c != null ? c.getCategoria() : "Sin datos de clima";
            double[] a = acumulado.computeIfAbsent(clave, k -> new double[2]);
            a[0]++;
            a[1] += v.getTotal();
        }
        List<VentasClima> r = new ArrayList<>();
        acumulado.forEach((k, a) -> r.add(new VentasClima(k, (int) a[0], ResumenDiario.VentaRedondeo.centavos(a[1]))));
        r.sort(Comparator.comparingDouble(VentasClima::importe).reversed());
        return r;
    }

    // ── Ventas por cajero y por hora ─────────────────────────────────────

    /** Ventas de un cajero en el periodo (sin las canceladas) */
    public record VentasCajero(String cajero, int ventas, double total, double ticketPromedio, double porcentaje) { }

    /** Ventas de una caja en el periodo (sin las canceladas) */
    public record VentasPorCaja(int caja, int ventas, double total, double ticketPromedio, double porcentaje) {
        public String etiqueta() { return "Caja " + caja; }
    }

    /** Ventas que cayeron en una hora del día (0–23) en el periodo (sin las canceladas) */
    public record VentasHora(int hora, int ventas, double total, double porcentaje) {
        /** "14:00 - 14:59" */
        public String rango() { return String.format("%02d:00 - %02d:59", hora, hora); }
    }

    /**
     * Total, número de ventas y ticket promedio de cada cajero del periodo, de mayor a menor.
     * Lee directo de las ventas (incluye hoy). Las ventas canceladas no cuentan. VER_HISTORIAL.
     */
    @Transactional(readOnly = true)
    public List<VentasCajero> ventasPorCajero(Periodo p) {
        return ventasPorCajero(p, null);
    }

    /** Igual, de una sola caja ({@code caja} null = todas) */
    @Transactional(readOnly = true)
    public List<VentasCajero> ventasPorCajero(Periodo p, Integer caja) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        List<Object[]> filas = ventaRepository.ventasPorCajero(p.desde().atStartOfDay(), p.hasta().plusDays(1).atStartOfDay(), caja);
        double granTotal = filas.stream().mapToDouble(f -> ((Number) f[2]).doubleValue()).sum();
        List<VentasCajero> r = new ArrayList<>();
        for (Object[] f : filas) {
            int ventas = ((Number) f[1]).intValue();
            double total = ResumenDiario.VentaRedondeo.centavos(((Number) f[2]).doubleValue());
            r.add(new VentasCajero(f[0] != null ? (String) f[0] : "Sin cajero", ventas, total,
                    ventas == 0 ? 0 : ResumenDiario.VentaRedondeo.centavos(total / ventas),
                    granTotal <= 0 ? 0 : total / granTotal * 100.0));
        }
        return r;
    }

    /**
     * Ventas del periodo agrupadas por la hora en que se hicieron (solo las horas con ventas,
     * de la más temprana a la más tarde). Las canceladas no cuentan. VER_HISTORIAL.
     */
    @Transactional(readOnly = true)
    public List<VentasHora> ventasPorHora(Periodo p) {
        return ventasPorHora(p, null);
    }

    /** Igual, de una sola caja ({@code caja} null = todas) */
    @Transactional(readOnly = true)
    public List<VentasHora> ventasPorHora(Periodo p, Integer caja) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        double[] total = new double[24];
        int[] ventas = new int[24];
        for (Object[] f : ventaRepository.fechasYTotalesActivas(p.desde().atStartOfDay(), p.hasta().plusDays(1).atStartOfDay(), caja)) {
            LocalDateTime fecha = (LocalDateTime) f[0];
            total[fecha.getHour()] += ((Number) f[1]).doubleValue();
            ventas[fecha.getHour()]++;
        }
        double granTotal = 0;
        for (double t : total) granTotal += t;
        List<VentasHora> r = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            if (ventas[h] == 0) continue;
            r.add(new VentasHora(h, ventas[h], ResumenDiario.VentaRedondeo.centavos(total[h]),
                    granTotal <= 0 ? 0 : total[h] / granTotal * 100.0));
        }
        return r;
    }

    /**
     * Total, número de ventas y ticket promedio de cada caja del periodo (varias
     * cajas contra un servidor). Las ventas canceladas no cuentan. VER_HISTORIAL.
     */
    @Transactional(readOnly = true)
    public List<VentasPorCaja> ventasPorCaja(Periodo p) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        List<Object[]> filas = ventaRepository.ventasPorCaja(p.desde().atStartOfDay(), p.hasta().plusDays(1).atStartOfDay());
        double granTotal = filas.stream().mapToDouble(f -> ((Number) f[2]).doubleValue()).sum();
        List<VentasPorCaja> r = new ArrayList<>();
        for (Object[] f : filas) {
            int ventas = ((Number) f[1]).intValue();
            double total = ResumenDiario.VentaRedondeo.centavos(((Number) f[2]).doubleValue());
            r.add(new VentasPorCaja(((Number) f[0]).intValue(), ventas, total,
                    ventas == 0 ? 0 : ResumenDiario.VentaRedondeo.centavos(total / ventas),
                    granTotal <= 0 ? 0 : total / granTotal * 100.0));
        }
        return r;
    }

    /** Números de caja que han vendido alguna vez (para el filtro de la pantalla) */
    @Transactional(readOnly = true)
    public List<Integer> cajasConVentas() {
        return ventaRepository.cajasConVentas();
    }
}
