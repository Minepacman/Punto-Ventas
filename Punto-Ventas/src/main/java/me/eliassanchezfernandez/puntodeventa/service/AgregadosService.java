package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.ParProductos;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoClima;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Precálculo de los agregados de Estadísticas (CRT-10), para que la pantalla
 * y las sugerencias no recorran todas las ventas cada vez:
 *
 *  - resumen_diario / producto_diario: por día, se recalculan SOLO los días
 *    cuyas ventas cambiaron (ventas nuevas o canceladas) desde la última
 *    actualización; "hoy" lo calcula en vivo quien lo necesita
 *    ({@link #calcularDia}).
 *  - pares_productos y producto_clima: sobre la ventana móvil de las últimas
 *    {@link #VENTANA_DIAS} días; se rehacen completos en cada corrida (son
 *    pocos datos y así reflejan las cancelaciones sin lógica extra).
 *
 * Corre todas las noches a las 03:00, al arrancar si está desactualizado y
 * con el botón "Recalcular" de Estadísticas. Las ventas canceladas nunca
 * cuentan. No exige permiso: lo ejecuta el sistema; la pantalla exige
 * VER_HISTORIAL para mostrar los resultados.
 */
@Service
public class AgregadosService {

    /** Ventana móvil de las recomendaciones aprendidas (cuatro semanas) */
    public static final int VENTANA_DIAS = 28;
    /** Un par de productos necesita al menos estos tickets juntos para contar */
    public static final int MIN_CO_OCURRENCIAS = 2;
    /** Pares que se conservan por producto (los de mayor confianza) */
    public static final int MAX_PARES_POR_PRODUCTO = 10;
    /** Piezas mínimas de un producto bajo un clima para calcular su índice */
    public static final double MIN_PIEZAS_CLIMA = 2;
    /** Hora del recálculo nocturno; antes de esa hora el día anterior cuenta como "al día" */
    static final LocalTime HORA_RECALCULO = LocalTime.of(3, 0);
    /** Días por transacción en la actualización incremental */
    static final int LOTE_DIAS = 31;

    @Autowired private VentaRepository          ventaRepo;
    @Autowired private AgregadosLote            lote;
    @Autowired private ConfiguracionRepository  configuracionRepo;
    @Autowired private SesionService            sesionService;
    @Autowired private ConfiguracionService     configuracionService;
    @Autowired private AuditoriaService         auditoriaService;
    @Autowired private Clock                    reloj;

    /** Referencia al proxy de Spring: los trabajos programados deben entrar por él para ser transaccionales */
    @Autowired @Lazy private AgregadosService   self;

    @Value("${app.estadisticas.jobs:true}")
    private boolean trabajosActivos;

    /** Espera antes del recálculo de arranque (ms); en pruebas se pone en 0 */
    @Value("${app.estadisticas.arranque-demora-ms:20000}")
    private long demoraArranqueMs;

    /** Evita dos recálculos simultáneos (botón + trabajo programado) */
    private final Object candado = new Object();

    // ── Trabajos programados ─────────────────────────────────────────────

    /** Al arrancar: si los agregados no están al día, se actualizan en segundo plano */
    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (!trabajosActivos) return;
        Thread t = new Thread(() -> {
            try {
                // No competir con el arranque ni con las primeras ventas: esperar y trabajar con baja prioridad
                if (demoraArranqueMs > 0) Thread.sleep(demoraArranqueMs);
                if (estaDesactualizado()) self.actualizar();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                System.err.println("No se pudieron actualizar los agregados de Estadísticas: " + e.getMessage());
            }
        }, "estadisticas-arranque");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Recálculo nocturno */
    @Scheduled(cron = "0 0 3 * * *")
    public void trabajoNocturno() {
        if (!trabajosActivos) return;
        try {
            self.actualizar();
        } catch (RuntimeException e) {
            System.err.println("Falló el recálculo nocturno de Estadísticas: " + e.getMessage());
        }
    }

    /** ¿La última actualización es anterior al último corte de las 03:00? */
    public boolean estaDesactualizado() {
        LocalDateTime ultima = configuracion().getEstadisticasActualizado();
        if (ultima == null) return true;
        LocalDateTime ahora = LocalDateTime.now(reloj);
        LocalDateTime corte = ahora.toLocalTime().isBefore(HORA_RECALCULO)
                ? ahora.toLocalDate().minusDays(1).atTime(HORA_RECALCULO)
                : ahora.toLocalDate().atTime(HORA_RECALCULO);
        return ultima.isBefore(corte);
    }

    public Optional<LocalDateTime> ultimaActualizacion() {
        return Optional.ofNullable(configuracion().getEstadisticasActualizado());
    }

    // ── Puntos de entrada ────────────────────────────────────────────────

    /** Botón "Recalcular": exige VER_HISTORIAL, rehace todo y queda en la bitácora */
    public void recalcularManual() {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        Cajero quien = sesionService.getCajeroActual();
        Resultado r = recalcularTodo();
        auditoriaService.registrar(quien, "ESTADISTICAS_RECALCULADAS",
                String.format("días=%d, pares=%d, clima=%d", r.dias(), r.pares(), r.paresClima()), (Cajero) null);
    }

    /**
     * Rehace TODOS los agregados desde la primera venta. NO es una sola
     * transacción: va de mes en mes (cada mes se calcula en una transacción de
     * solo lectura y se guarda en otra corta de escritura, vía
     * {@link AgregadosLote}) para no retener el candado de escritura de SQLite
     * ni acumular años de ventas en memoria. Si se interrumpe a la mitad, la
     * marca de "actualizado" queda vacía y el siguiente arranque lo repite.
     */
    public Resultado recalcularTodo() {
        synchronized (candado) {
            Cronometro crono = new Cronometro();
            crono.escribiendo(lote::borrarTodo);  // también vacía la marca de actualización
            LocalDate hoy = LocalDate.now(reloj);
            Optional<Venta> primera = ventaRepo.findFirstByOrderByFechaAsc();
            int dias = 0;
            if (primera.isPresent()) {
                LocalDate d = primera.get().getFecha().toLocalDate();
                while (!d.isAfter(hoy)) {
                    LocalDate fin = d.plusMonths(1).withDayOfMonth(1).minusDays(1);
                    if (fin.isAfter(hoy)) fin = hoy;
                    dias += reemplazarRango(d, fin, crono);
                    d = fin.plusDays(1);
                }
            }
            Resultado ventana = recalcularVentana(crono);
            marcarActualizado();
            Resultado r = new Resultado(dias, ventana.pares(), ventana.paresClima());
            ultimosTiempos = crono.tiempos();
            System.out.println("ℹ️  Estadísticas: agregados recalculados por completo (" + r + "; " + ultimosTiempos + ")");
            return r;
        }
    }

    /**
     * Actualización incremental: solo los días con ventas nuevas o canceladas
     * desde la última corrida (o todo, si nunca se ha corrido), más la ventana
     * móvil completa.
     */
    public Resultado actualizar() {
        synchronized (candado) {
            LocalDateTime ultima = configuracion().getEstadisticasActualizado();
            if (ultima == null) return recalcularTodo();

            // Un pequeño traslape por si una venta se guardó justo al terminar la corrida anterior
            LocalDateTime desde = ultima.minusMinutes(5);
            Set<LocalDate> fechas = new TreeSet<>();
            for (LocalDateTime f : ventaRepo.findFechasAfectadasDesde(desde)) fechas.add(f.toLocalDate());

            Cronometro crono = new Cronometro();
            int dias = 0;
            List<LocalDate> pendientes = new ArrayList<>();
            for (LocalDate f : fechas) {
                pendientes.add(f);
                if (pendientes.size() == LOTE_DIAS) { dias += reemplazarFechas(pendientes, crono); pendientes.clear(); }
            }
            if (!pendientes.isEmpty()) dias += reemplazarFechas(pendientes, crono);
            Resultado ventana = recalcularVentana(crono);
            marcarActualizado();
            Resultado r = new Resultado(dias, ventana.pares(), ventana.paresClima());
            ultimosTiempos = crono.tiempos();
            System.out.println("ℹ️  Estadísticas: agregados actualizados (" + r + "; " + ultimosTiempos + ")");
            return r;
        }
    }

    /** Un mes: se calcula leyendo (sin candado de escritura) y se guarda en una transacción corta */
    private int reemplazarRango(LocalDate desde, LocalDate hasta, Cronometro crono) {
        List<LocalDate> fechas = new ArrayList<>();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) fechas.add(d);
        Map<LocalDate, DiaCalculado> dias = crono.leyendo(() -> lote.calcularDias(desde, hasta));
        return crono.escribiendo(() -> lote.reemplazarDias(fechas, dias.values()));
    }

    /** Fechas sueltas (actualización incremental): igual, cálculo sin candado y escritura corta */
    private int reemplazarFechas(List<LocalDate> fechas, Cronometro crono) {
        List<LocalDate> copia = new ArrayList<>(fechas);
        Map<LocalDate, DiaCalculado> dias = crono.leyendo(() -> lote.calcularFechas(copia));
        return crono.escribiendo(() -> lote.reemplazarDias(copia, dias.values()));
    }

    // ── Cálculo por día (también lo usa Estadísticas para "hoy" en vivo) ─

    /** Resumen y productos de UN día calculados directamente de las ventas (sin guardar) */
    @Transactional(readOnly = true)
    public DiaCalculado calcularDia(LocalDate dia) {
        Map<LocalDate, DiaCalculado> m = calcularDias(dia, dia);
        return m.getOrDefault(dia, new DiaCalculado(new ResumenDiario(dia), List.of()));
    }

    /** Días con ventas del rango [desde, hasta] (inclusive), cada uno con su resumen y productos */
    public Map<LocalDate, DiaCalculado> calcularDias(LocalDate desde, LocalDate hasta) {
        return lote.calcularDias(desde, hasta);
    }

    // ── Ventana móvil: canasta y clima ───────────────────────────────────

    /**
     * Rehace pares_productos y producto_clima con las ventas de la ventana. NO es una
     * transacción: las ventas se leen y agrupan en una transacción de solo lectura
     * (sin el candado de escritura de SQLite) y lo calculado se guarda en dos
     * transacciones cortas. Antes todo iba en una sola transacción de escritura y la
     * caja no podía cobrar mientras se recorrían las cuatro semanas de tickets.
     */
    public Resultado recalcularVentana() {
        Cronometro crono = new Cronometro();
        Resultado r = recalcularVentana(crono);
        ultimosTiempos = crono.tiempos();
        return r;
    }

    private Resultado recalcularVentana(Cronometro crono) {
        LocalDate hoy = LocalDate.now(reloj);
        LocalDateTime inicio = hoy.minusDays(VENTANA_DIAS - 1).atStartOfDay();
        LocalDateTime fin = hoy.plusDays(1).atStartOfDay();
        AgregadosLote.Ventana ventana = crono.leyendo(() -> lote.calcularVentana(inicio, fin));
        crono.escribiendo(() -> lote.reemplazarPares(ventana.pares()));
        crono.escribiendo(() -> lote.reemplazarProductoClima(ventana.clima()));
        return new Resultado(0, ventana.pares().size(), ventana.clima().size());
    }

    /** Cuánto duró la última corrida: cálculo sin candado y escritura con candado (para el registro y las pruebas) */
    public Tiempos ultimosTiempos() {
        return ultimosTiempos;
    }

    private volatile Tiempos ultimosTiempos = new Tiempos(0, 0);

    /**
     * Milisegundos de la corrida leyendo y agrupando ventas ({@code msCalculo}, sin candado de
     * escritura) y milisegundos en transacciones de escritura ({@code msEscritura}: borrar e
     * insertar los agregados, incluida la espera si otra transacción tenía el candado).
     */
    public record Tiempos(long msCalculo, long msEscritura) {
        @Override public String toString() {
            return "cálculo sin candado " + msCalculo + " ms, escritura con candado " + msEscritura + " ms";
        }
    }

    /** Acumula el tiempo de cada fase de una corrida */
    private static final class Cronometro {
        private long nsCalculo, nsEscritura;

        <T> T leyendo(java.util.function.Supplier<T> fase) {
            long t = System.nanoTime();
            try { return fase.get(); } finally { nsCalculo += System.nanoTime() - t; }
        }

        <T> T escribiendo(java.util.function.Supplier<T> fase) {
            long t = System.nanoTime();
            try { return fase.get(); } finally { nsEscritura += System.nanoTime() - t; }
        }

        void escribiendo(Runnable fase) {
            escribiendo(() -> { fase.run(); return null; });
        }

        Tiempos tiempos() {
            return new Tiempos(nsCalculo / 1_000_000, nsEscritura / 1_000_000);
        }
    }

    /**
     * Canasta de mercado sobre los tickets dados. Función sin efectos
     * (probada con un conjunto conocido): regresa los pares ordenados A → B
     * con soporte, confianza y lift, conservando los MAX_PARES_POR_PRODUCTO
     * de mayor confianza por producto A.
     */
    public static List<ParProductos> calcularPares(List<Venta> ventas) {
        Map<Long, Integer> ticketsPorProducto = new HashMap<>();
        Map<Long, Map<Long, Integer>> juntos = new HashMap<>();   // a → (b → tickets con ambos), a < b
        Map<Long, Producto> productos = new HashMap<>();
        int tickets = 0;

        for (Venta v : ventas) {
            if (v.isCancelada()) continue;
            TreeMap<Long, Producto> enTicket = new TreeMap<>();
            for (DetalleVenta d : v.getDetalles()) {
                // El "producto común" (artículos sueltos fuera del catálogo) no forma pares: no es un producto real
                if (d.getProducto() != null && d.getProducto().getId() != null && !d.esProductoComun()) {
                    enTicket.put(d.getProducto().getId(), d.getProducto());
                }
            }
            if (enTicket.isEmpty()) continue;
            tickets++;
            productos.putAll(enTicket);
            List<Long> ids = new ArrayList<>(enTicket.keySet());
            for (int i = 0; i < ids.size(); i++) {
                ticketsPorProducto.merge(ids.get(i), 1, Integer::sum);
                for (int j = i + 1; j < ids.size(); j++) {
                    juntos.computeIfAbsent(ids.get(i), k -> new HashMap<>()).merge(ids.get(j), 1, Integer::sum);
                }
            }
        }
        if (tickets == 0) return List.of();

        Map<Long, List<ParProductos>> porA = new HashMap<>();
        for (Map.Entry<Long, Map<Long, Integer>> ea : juntos.entrySet()) {
            for (Map.Entry<Long, Integer> eb : ea.getValue().entrySet()) {
                int n = eb.getValue();
                if (n < MIN_CO_OCURRENCIAS) continue;
                long a = ea.getKey(), b = eb.getKey();
                double soporte = (double) n / tickets;
                agregarPar(porA, productos, a, b, n, soporte, ticketsPorProducto, tickets);
                agregarPar(porA, productos, b, a, n, soporte, ticketsPorProducto, tickets);
            }
        }
        List<ParProductos> resultado = new ArrayList<>();
        for (List<ParProductos> lista : porA.values()) {
            lista.sort(Comparator.comparingDouble(ParProductos::getConfianza).reversed()
                    .thenComparing(Comparator.comparingDouble(ParProductos::getLift).reversed())
                    .thenComparing(p -> p.getProductoB().getId()));
            resultado.addAll(lista.subList(0, Math.min(MAX_PARES_POR_PRODUCTO, lista.size())));
        }
        resultado.sort(Comparator.comparingInt(ParProductos::getCoOcurrencias).reversed()
                .thenComparing(p -> p.getProductoA().getId()).thenComparing(p -> p.getProductoB().getId()));
        return resultado;
    }

    private static void agregarPar(Map<Long, List<ParProductos>> porA, Map<Long, Producto> productos,
                                   long a, long b, int n, double soporte,
                                   Map<Long, Integer> ticketsPorProducto, int tickets) {
        double confianza = (double) n / ticketsPorProducto.get(a);
        double probB = (double) ticketsPorProducto.get(b) / tickets;
        double lift = confianza / probB;
        porA.computeIfAbsent(a, k -> new ArrayList<>()).add(new ParProductos(productos.get(a), productos.get(b), n,
                redondear4(soporte), redondear4(confianza), redondear4(lift)));
    }

    /**
     * Correlación producto × clima. A cada venta se le asigna el registro de
     * clima de su hora (si lo hay); el índice compara la participación del
     * producto bajo ese clima con su participación general en las ventas
     * CON clima registrado.
     */
    public static List<ProductoClima> calcularProductoClima(List<Venta> ventas, List<ClimaRegistro> clima) {
        if (clima.isEmpty()) return List.of();
        Map<LocalDateTime, ClimaRegistro> porHora = new HashMap<>();
        for (ClimaRegistro c : clima) porHora.put(c.getFechaHora().truncatedTo(ChronoUnit.HOURS), c);

        Map<Long, Producto> productos = new HashMap<>();
        Map<Long, Double> piezasProducto = new HashMap<>();
        double piezasTotal = 0;
        // (tipo, clave) → producto → piezas  y  (tipo, clave) → piezas totales
        Map<String, Map<Long, Double>> piezasPorClave = new TreeMap<>();
        Map<String, Double> totalPorClave = new HashMap<>();

        for (Venta v : ventas) {
            if (v.isCancelada()) continue;
            ClimaRegistro c = porHora.get(v.getFecha().truncatedTo(ChronoUnit.HOURS));
            if (c == null) continue;
            String[] claves = { ProductoClima.Tipo.CONDICION + "|" + c.getCategoria(),
                                ProductoClima.Tipo.BANDA + "|" + c.getBanda() };
            for (DetalleVenta d : v.getDetalles()) {
                Producto p = d.getProducto();
                if (p == null || p.getId() == null || d.esProductoComun()) continue;   // el común no "depende" del clima
                productos.put(p.getId(), p);
                piezasProducto.merge(p.getId(), d.getCantidad(), Double::sum);
                piezasTotal += d.getCantidad();
                for (String clave : claves) {
                    piezasPorClave.computeIfAbsent(clave, k -> new HashMap<>()).merge(p.getId(), d.getCantidad(), Double::sum);
                    totalPorClave.merge(clave, d.getCantidad(), Double::sum);
                }
            }
        }
        if (piezasTotal <= 0) return List.of();

        List<ProductoClima> resultado = new ArrayList<>();
        for (Map.Entry<String, Map<Long, Double>> e : piezasPorClave.entrySet()) {
            String[] partes = e.getKey().split("\\|", 2);
            ProductoClima.Tipo tipo = ProductoClima.Tipo.valueOf(partes[0]);
            double totalClave = totalPorClave.get(e.getKey());
            for (Map.Entry<Long, Double> ep : e.getValue().entrySet()) {
                double piezas = ep.getValue();
                if (piezas < MIN_PIEZAS_CLIMA) continue;
                double participacionClima   = piezas / totalClave;
                double participacionGeneral = piezasProducto.get(ep.getKey()) / piezasTotal;
                double indice = participacionGeneral > 0 ? participacionClima / participacionGeneral : 0;
                resultado.add(new ProductoClima(productos.get(ep.getKey()), tipo, partes[1],
                        ResumenDiario.VentaRedondeo.milesimas(piezas), redondear4(indice)));
            }
        }
        resultado.sort(Comparator.comparingDouble(ProductoClima::getIndice).reversed()
                .thenComparing(pc -> pc.getProducto().getId()));
        return resultado;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void marcarActualizado() {
        Configuracion c = configuracion();
        c.setEstadisticasActualizado(LocalDateTime.now(reloj));
        configuracionRepo.save(c);
        configuracionService.invalidarCache();
    }

    /** La fila de configuración (sin pasar por ConfiguracionService para no depender de la sesión) */
    private Configuracion configuracion() {
        return configuracionRepo.findById(Configuracion.ID_UNICO).orElseGet(() -> configuracionRepo.save(new Configuracion()));
    }

    private static double redondear4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Resumen de un día con sus productos (ordenados por importe) */
    public record DiaCalculado(ResumenDiario resumen, List<ProductoDiario> productos) { }

    /** Qué se recalculó (para la bitácora y las pruebas) */
    public record Resultado(int dias, int pares, int paresClima) { }
}
