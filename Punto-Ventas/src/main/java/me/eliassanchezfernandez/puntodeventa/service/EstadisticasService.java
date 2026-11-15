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
import me.eliassanchezfernandez.puntodeventa.util.FechasComparacion;
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
     * Resúmenes diarios del periodo: los días ya consolidados vienen de
     * resumen_diario y los demás se calculan en vivo de las ventas. Solo días con ventas.
     *
     * Se calculan en vivo (M-14) no solo "hoy", sino todos los días que pueden
     * haber cambiado desde la última corrida de {@link AgregadosService}: el día
     * de esa corrida y los posteriores (la corrida de arranque suele ser a media
     * mañana, y de madrugada "ayer" aún no está consolidado) y los días con
     * ventas canceladas después de la corrida (la cancelación mueve el día de la
     * venta original).
     */
    @Transactional(readOnly = true)
    public List<ResumenDiario> resumenesEntre(Periodo p) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        DiasEnVivo vivo = diasEnVivo(p);
        List<ResumenDiario> lista = new ArrayList<>();
        if (vivo.hastaConsolidado != null) {
            for (ResumenDiario r : resumenRepo.findByFechaBetweenOrderByFechaAsc(p.desde(), vivo.hastaConsolidado)) {
                if (!vivo.sueltos.contains(r.getFecha())) lista.add(r);
            }
        }
        Map<LocalDate, AgregadosService.DiaCalculado> calculados = new TreeMap<>();
        if (vivo.desdeVivo != null) calculados.putAll(agregadosService.calcularDias(vivo.desdeVivo, vivo.hastaVivo));
        for (LocalDate d : vivo.sueltos) calculados.putAll(agregadosService.calcularDias(d, d));
        for (AgregadosService.DiaCalculado d : calculados.values()) {
            if (d.resumen().getTransacciones() > 0) lista.add(d.resumen());
        }
        lista.sort(Comparator.comparing(ResumenDiario::getFecha));
        // Días futuros del periodo (p. ej. un rango personalizado) no tienen ventas
        return lista;
    }

    /** Qué parte de un periodo se lee de los agregados y qué parte se calcula en vivo */
    record DiasEnVivo(LocalDate hastaConsolidado, LocalDate desdeVivo, LocalDate hastaVivo, java.util.Set<LocalDate> sueltos) { }

    DiasEnVivo diasEnVivo(Periodo p) {
        LocalDate hoy = hoy();
        LocalDate hastaReal = p.hasta().isBefore(hoy) ? p.hasta() : hoy;
        Optional<LocalDateTime> ultima = agregadosService.ultimaActualizacion();
        // Sin corrida previa no hay nada consolidado: todo en vivo
        LocalDate primerDiaVivo = ultima.map(LocalDateTime::toLocalDate).orElse(p.desde());
        if (primerDiaVivo.isAfter(hoy)) primerDiaVivo = hoy;   // reloj atrasado: nunca dejar "hoy" fuera del cálculo en vivo
        LocalDate desdeVivo = primerDiaVivo.isAfter(p.desde()) ? primerDiaVivo : p.desde();
        boolean hayVivo = !desdeVivo.isAfter(hastaReal);
        LocalDate hastaConsolidado = desdeVivo.isAfter(p.desde()) ? desdeVivo.minusDays(1) : null;
        if (hastaConsolidado != null && hastaConsolidado.isAfter(hastaReal)) hastaConsolidado = hastaReal;
        java.util.Set<LocalDate> sueltos = new java.util.TreeSet<>();
        if (ultima.isPresent() && hastaConsolidado != null) {
            for (LocalDateTime f : ventaRepository.findFechasAfectadasDesde(ultima.get().minusMinutes(5))) {
                LocalDate d = f.toLocalDate();
                if (p.incluye(d) && !d.isAfter(hastaConsolidado)) sueltos.add(d);
            }
        }
        return new DiasEnVivo(hastaConsolidado, hayVivo ? desdeVivo : null, hastaReal, sueltos);
    }

    @Transactional(readOnly = true)
    public Totales totales(Periodo p) {
        return totalesDe(resumenesEntre(p));
    }

    /** Totales de una lista de resúmenes ya leída (función pura) */
    public static Totales totalesDe(List<ResumenDiario> resumenes) {
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
        return serieDiariaDe(p, resumenesEntre(p));
    }

    /** serieDiaria() sobre resúmenes ya leídos (función pura) */
    public static Map<LocalDate, Double> serieDiariaDe(Periodo p, List<ResumenDiario> resumenes) {
        Map<LocalDate, Double> serie = new TreeMap<>();
        for (LocalDate d = p.desde(); !d.isAfter(p.hasta()); d = d.plusDays(1)) serie.put(d, 0.0);
        for (ResumenDiario r : resumenes) serie.put(r.getFecha(), r.getTotal());
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
        return porFranjaDe(resumenesEntre(p));
    }

    /** porFranja() sobre resúmenes ya leídos (función pura) */
    public static Map<Franja, Totales> porFranjaDe(List<ResumenDiario> resumenes) {
        Map<Franja, double[]> acumulado = new EnumMap<>(Franja.class);
        for (Franja f : Franja.values()) acumulado.put(f, new double[2]);
        for (ResumenDiario d : resumenes) {
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

    /** Filas producto-día del periodo; los días no consolidados (ver {@link #resumenesEntre}) en vivo */
    @Transactional(readOnly = true)
    public List<ProductoDiario> productosDiariosEntre(Periodo p) {
        DiasEnVivo vivo = diasEnVivo(p);
        List<ProductoDiario> lista = new ArrayList<>();
        if (vivo.hastaConsolidado != null) {
            for (ProductoDiario pd : productoDiarioRepo.findEntre(p.desde(), vivo.hastaConsolidado)) {
                if (!vivo.sueltos.contains(pd.getFecha())) lista.add(pd);
            }
        }
        Map<LocalDate, AgregadosService.DiaCalculado> calculados = new TreeMap<>();
        if (vivo.desdeVivo != null) calculados.putAll(agregadosService.calcularDias(vivo.desdeVivo, vivo.hastaVivo));
        for (LocalDate d : vivo.sueltos) calculados.putAll(agregadosService.calcularDias(d, d));
        for (AgregadosService.DiaCalculado d : calculados.values()) lista.addAll(d.productos());
        return lista;
    }

    // ── Vista "Hoy": venta y tickets de hoy contra ayer, la semana pasada y hace un año ──

    /** Total vendido y número de tickets (ventas activas) de un día o de parte de él */
    public record CifrasDia(double total, int tickets) {
        public static final CifrasDia CERO = new CifrasDia(0, 0);
    }

    /** Con qué parte del día de referencia se compara "hoy" */
    public enum AlcanceComparacion {
        /** Hay tickets de ese día: se compara hasta la misma hora de hoy */
        HASTA_LA_HORA,
        /** Solo quedó el resumen del día (sin tickets): se compara el día completo */
        DIA_COMPLETO,
        /** La tienda ya vendía, pero ese día no hubo ventas */
        SIN_VENTAS,
        /** Ese día la tienda aún no había registrado su primera venta */
        LA_TIENDA_NO_VENDIA
    }

    /**
     * Una fila de comparación de la vista Hoy. La variación se calcula contra
     * las cifras de hoy que le pase la pantalla; es null cuando no hay con qué
     * comparar (sin referencia o referencia en cero: nunca se divide entre 0).
     */
    public record ComparacionDia(String etiqueta, LocalDate dia, CifrasDia referencia, AlcanceComparacion alcance) {

        public boolean hayReferencia() {
            return alcance == AlcanceComparacion.HASTA_LA_HORA || alcance == AlcanceComparacion.DIA_COMPLETO;
        }

        public Double variacionTotalPct(CifrasDia hoy) {
            if (!hayReferencia() || referencia.total() <= 0) return null;
            return (hoy.total() - referencia.total()) / referencia.total() * 100.0;
        }

        public Double variacionTicketsPct(CifrasDia hoy) {
            if (!hayReferencia() || referencia.tickets() <= 0) return null;
            return (hoy.tickets() - referencia.tickets()) / (double) referencia.tickets() * 100.0;
        }

        public double diferenciaTotal(CifrasDia hoy)  { return ResumenDiario.VentaRedondeo.centavos(hoy.total() - referencia.total()); }
        public int    diferenciaTickets(CifrasDia hoy) { return hoy.tickets() - referencia.tickets(); }
    }

    /**
     * Lo que muestra la vista Hoy: las cifras de hoy (todo lo vendido hasta ahora)
     * y tres comparaciones. {@code hasta} es la hora a la que se cortan los días
     * de referencia (la hora de ahora).
     */
    public record ResumenHoy(LocalDate hoy, java.time.LocalTime hasta, CifrasDia cifras, List<ComparacionDia> comparaciones) { }

    /** Vista Hoy con la hora actual. VER_HISTORIAL (los totales, como en el historial de ventas). */
    @Transactional(readOnly = true)
    public ResumenHoy resumenHoy() {
        return resumenHoy(LocalDateTime.now(reloj));
    }

    /**
     * Vista Hoy a una hora dada (la pantalla pasa "ahora"; las pruebas, una hora fija).
     * Cuatro consultas de una fila cada una (hoy y los tres días de referencia) más
     * la primera venta; nunca se cargan tickets. Las ventas canceladas no cuentan,
     * igual que en el corte; el "producto común" es una venta como cualquier otra.
     */
    @Transactional(readOnly = true)
    public ResumenHoy resumenHoy(LocalDateTime ahora) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        LocalDate hoy = ahora.toLocalDate();
        java.time.LocalTime hora = ahora.toLocalTime();
        Object[] f = ventaRepository.resumenActivas(hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay(), null).get(0);
        CifrasDia deHoy = new CifrasDia(ResumenDiario.VentaRedondeo.centavos(((Number) f[1]).doubleValue()), ((Number) f[0]).intValue());
        LocalDate primeraVenta = ventaRepository.findPrimeraActiva().map(v -> v.getFecha().toLocalDate()).orElse(null);
        List<ComparacionDia> comparaciones = List.of(
                compararDia(FechasComparacion.etiquetaAyer(), FechasComparacion.ayer(hoy), hora, primeraVenta),
                compararDia(FechasComparacion.etiquetaSemanaPasada(hoy), FechasComparacion.mismoDiaSemanaPasada(hoy), hora, primeraVenta),
                compararDia(FechasComparacion.etiquetaHaceUnAnio(), FechasComparacion.hoyHaceUnAnio(hoy), hora, primeraVenta));
        return new ResumenHoy(hoy, hora, deHoy, comparaciones);
    }

    private ComparacionDia compararDia(String etiqueta, LocalDate dia, java.time.LocalTime hora, LocalDate primeraVenta) {
        if (!FechasComparacion.laTiendaYaVendia(primeraVenta, dia)) {
            return new ComparacionDia(etiqueta, dia, CifrasDia.CERO, AlcanceComparacion.LA_TIENDA_NO_VENDIA);
        }
        Object[] f = ventaRepository.resumenActivasConCorte(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay(), dia.atTime(hora)).get(0);
        if (((Number) f[0]).intValue() > 0) {
            CifrasDia hastaLaHora = new CifrasDia(ResumenDiario.VentaRedondeo.centavos(((Number) f[3]).doubleValue()), ((Number) f[2]).intValue());
            return new ComparacionDia(etiqueta, dia, hastaLaHora, AlcanceComparacion.HASTA_LA_HORA);
        }
        // Sin tickets de ese día en la tabla de ventas: si quedó el resumen diario, se compara el día completo
        Optional<ResumenDiario> resumen = resumenRepo.findByFecha(dia);
        if (resumen.isPresent() && resumen.get().getTransacciones() > 0) {
            return new ComparacionDia(etiqueta, dia, new CifrasDia(resumen.get().getTotal(), resumen.get().getTransacciones()),
                    AlcanceComparacion.DIA_COMPLETO);
        }
        return new ComparacionDia(etiqueta, dia, CifrasDia.CERO, AlcanceComparacion.SIN_VENTAS);
    }

    // ── Comparaciones ────────────────────────────────────────────────────

    /**
     * Las cuatro comparaciones de la pantalla: hoy vs el mismo día de la
     * semana pasada; semana, mes y año "a la fecha" contra el mismo tramo
     * del periodo anterior (así un martes no se compara con una semana completa).
     */
    @Transactional(readOnly = true)
    public List<Comparacion> comparaciones() {
        List<Comparacion> r = new ArrayList<>();
        for (DefComparacion d : definicionesComparacion(hoy())) {
            r.add(new Comparacion(d.etiqueta(), d.detalle(), d.actual(), d.anterior(), totales(d.actual()), totales(d.anterior())));
        }
        return r;
    }

    /**
     * Una sola de las comparaciones (Hoy, Semana, Mes o Año) con los totales del periodo actual
     * ya calculados por quien llama: solo consulta el periodo anterior. Para el móvil, que
     * muestra una a la vez (calcular las cuatro incluye dos años completos).
     */
    @Transactional(readOnly = true)
    public Optional<Comparacion> comparacion(String etiqueta, Totales actual) {
        for (DefComparacion d : definicionesComparacion(hoy())) {
            if (d.etiqueta().equals(etiqueta)) {
                return Optional.of(new Comparacion(d.etiqueta(), d.detalle(), d.actual(), d.anterior(), actual, totales(d.anterior())));
            }
        }
        return Optional.empty();
    }

    /** Qué se compara contra qué (sin consultar nada) */
    record DefComparacion(String etiqueta, String detalle, Periodo actual, Periodo anterior) { }

    static List<DefComparacion> definicionesComparacion(LocalDate hoy) {
        LocalDate mesAnterior = hoy.minusMonths(1);
        LocalDate anioAnterior = hoy.minusYears(1);
        return List.of(
                new DefComparacion("Hoy", "vs. el mismo día de la semana pasada",
                        Periodo.dia(hoy), Periodo.dia(hoy.minusWeeks(1))),
                new DefComparacion("Semana", "vs. el mismo tramo de la semana pasada",
                        Periodo.semana(hoy), new Periodo(hoy.with(DayOfWeek.MONDAY).minusWeeks(1), hoy.minusWeeks(1), "Semana anterior")),
                new DefComparacion("Mes", "vs. los mismos días del mes pasado",
                        Periodo.mes(hoy), new Periodo(mesAnterior.withDayOfMonth(1), mesAnterior, "Mes anterior")),
                new DefComparacion("Año", "vs. los mismos días del año pasado",
                        Periodo.anio(hoy), new Periodo(anioAnterior.withDayOfYear(1), anioAnterior, "Año anterior")));
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
