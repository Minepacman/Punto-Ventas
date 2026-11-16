package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Caducidad;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.CaducidadRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Caducidades, versión sencilla (no es inventario por lote).
 *
 * <ul>
 *   <li>Un producto con "Controlar caducidad" acepta, al darle entrada (Inventario, orden de compra
 *       recibida o teléfono), una fecha "Caduca el" opcional; cada entrada con fecha queda como una
 *       fila de {@link Caducidad}. Las entradas sin fecha no crean nada.</li>
 *   <li>Las ventas NO tocan esto. Lo que queda de cada entrada se ESTIMA al leer con
 *       {@link #estimar}: la existencia actual se reparte entre las entradas vigentes empezando por
 *       la que caduca MÁS TARDE (se supone que se vende primero lo que caduca primero). Es una
 *       aproximación: si en la tienda se vendió primero lo nuevo, o hay existencia que entró sin
 *       fecha, el estimado puede no coincidir con lo que hay en el anaquel. Por eso cada entrada se
 *       puede cerrar a mano: "Retirar" (merma con motivo "Caducado", con permiso de inventario) o
 *       "Ya no queda" (solo deja de contar).</li>
 *   <li>"Por caducar" = entradas vigentes con algo estimado que caducan dentro de
 *       {@link Configuracion#getDiasAvisoCaducidad()} días (7 por defecto) o ya caducaron.</li>
 * </ul>
 */
@Service
public class CaducidadService {

    /** Una fecha más lejana que esto casi seguro es un error de captura */
    public static final int MAX_ANIOS_ADELANTE = 10;
    public static final int DIAS_AVISO_MINIMO = 1;
    public static final int DIAS_AVISO_MAXIMO = 90;
    public static final String MOTIVO_CADUCADO = "Caducado";

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired private CaducidadRepository  repo;
    @Autowired private ProductoRepository   productoRepo;
    @Autowired private ProductoService      productoService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionService        sesionService;
    @Autowired private AuditoriaService     auditoriaService;
    @Autowired private Clock                reloj;

    /** Una entrada con lo que se estima que queda de ella */
    public record LoteEstimado(Caducidad lote, double estimado) { }

    /** Fila de "Por caducar" (sin entidades: se usa fuera de la transacción) */
    public record PorCaducar(long caducidadId, long productoId, String producto, String codigo, String unidad,
                             LocalDate fechaCaducidad, double estimado, double existencia, long dias) {
        public boolean caducado() { return dias < 0; }
    }

    // ── Reglas puras ──────────────────────────────────────────────────────

    /**
     * Valida la fecha capturada al dar entrada. null = sin fecha (se acepta: es opcional).
     * No se acepta una fecha que ya pasó (eso no se recibe: se rechaza al proveedor) ni una
     * absurda a más de {@link #MAX_ANIOS_ADELANTE} años.
     */
    public static void validarFecha(LocalDate caducaEl, LocalDate hoy) {
        if (caducaEl == null) return;
        if (caducaEl.isBefore(hoy)) {
            throw new NegocioException("La fecha de caducidad (" + caducaEl.format(FECHA) + ") ya pasó. Revisa la fecha o no recibas esa mercancía.");
        }
        if (caducaEl.isAfter(hoy.plusYears(MAX_ANIOS_ADELANTE))) {
            throw new NegocioException("La fecha de caducidad (" + caducaEl.format(FECHA) + ") está demasiado lejos. Revisa el año.");
        }
    }

    /**
     * Reparte la existencia actual entre las entradas vigentes de UN producto: primero la que
     * caduca más tarde (a igual fecha, la que entró después), cada una hasta lo que entró. Lo que
     * no alcanza a cubrir se supone ya vendido. Existencia negativa cuenta como cero. Función pura;
     * devuelve las entradas ordenadas por fecha de caducidad ascendente (la más urgente primero).
     */
    public static List<LoteEstimado> estimar(double existencia, List<Caducidad> lotes) {
        List<Caducidad> porAsignar = new ArrayList<>(lotes);
        porAsignar.removeIf(c -> !c.isVigente());
        porAsignar.sort(ordenUrgencia().reversed());
        double queda = Math.max(0, existencia);
        List<LoteEstimado> r = new ArrayList<>();
        for (Caducidad c : porAsignar) {
            double asignado = Math.min(Math.max(0, c.getCantidadEntrada()), queda);
            queda -= asignado;
            r.add(new LoteEstimado(c, VentaService.redondearCantidad(asignado)));
        }
        r.sort(Comparator.comparing(LoteEstimado::lote, ordenUrgencia()));
        return r;
    }

    /** Más urgente primero: caduca antes; a igual fecha la que entró antes; luego por id */
    private static Comparator<Caducidad> ordenUrgencia() {
        return Comparator.comparing(Caducidad::getFechaCaducidad)
                .thenComparing(Caducidad::getFechaEntrada, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Caducidad::getId, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /** "Caduca hoy", "Caducó hace 3 días", "Caduca en 5 días" */
    public static String textoDias(long dias) {
        if (dias == 0) return "Caduca hoy";
        if (dias == 1) return "Caduca mañana";
        if (dias == -1) return "Caducó ayer";
        if (dias < 0) return "Caducó hace " + (-dias) + " días";
        return "Caduca en " + dias + " días";
    }

    // ── Registro (lo llama ProductoService dentro de la transacción de la entrada) ──

    /**
     * Anota la entrada con fecha. Solo si el producto controla caducidad y hay fecha; si no, no
     * hace nada. La fecha ya viene validada por {@link #validarFecha}.
     */
    static Caducidad nueva(Producto p, double cantidad, LocalDate caducaEl, Caducidad.Origen origen, Long movimientoId,
                           LocalDateTime ahora) {
        Caducidad c = new Caducidad();
        c.setProductoId(p.getId());
        c.setFechaCaducidad(caducaEl);
        c.setCantidadEntrada(cantidad);
        c.setFechaEntrada(ahora);
        c.setOrigen((origen == null ? Caducidad.Origen.MANUAL : origen).name());
        c.setMovimientoId(movimientoId);
        return c;
    }

    // ── Consultas ─────────────────────────────────────────────────────────

    public LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    /** Días de anticipación de "Por caducar" y del aviso (Configuración; 7 por defecto) */
    public int diasAviso() {
        return configuracionService.obtener().getDiasAvisoCaducidad();
    }

    /** Lo que caduca dentro de {@link #diasAviso()} días o ya caducó, con algo estimado. */
    @Transactional(readOnly = true)
    public List<PorCaducar> porCaducar() {
        return porCaducar(hoy(), diasAviso());
    }

    /**
     * Entradas vigentes de productos que controlan caducidad, con existencia estimada mayor a
     * cero y fecha ≤ hoy + dias (incluye las ya caducadas). Más urgentes primero.
     */
    @Transactional(readOnly = true)
    public List<PorCaducar> porCaducar(LocalDate hoy, int dias) {
        LocalDate limite = hoy.plusDays(Math.max(0, dias));
        Map<Long, List<Caducidad>> porProducto = new HashMap<>();
        for (Caducidad c : repo.vigentesDeProductosControlados()) {
            porProducto.computeIfAbsent(c.getProductoId(), k -> new ArrayList<>()).add(c);
        }
        if (porProducto.isEmpty()) return List.of();
        // Solo hacen falta productos que tengan alguna entrada dentro de la ventana
        List<Long> ids = porProducto.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(c -> !c.getFechaCaducidad().isAfter(limite)))
                .map(Map.Entry::getKey).toList();
        List<PorCaducar> r = new ArrayList<>();
        for (Producto p : productoRepo.findAllById(ids)) {
            for (LoteEstimado le : estimar(p.getCantidadActual(), porProducto.get(p.getId()))) {
                Caducidad c = le.lote();
                if (le.estimado() <= 0 || c.getFechaCaducidad().isAfter(limite)) continue;
                r.add(new PorCaducar(c.getId(), p.getId(), p.getDescripcion(), p.getCodigoBarras(), AvisosService.unidadDe(p),
                        c.getFechaCaducidad(), le.estimado(), p.getCantidadActual(),
                        ChronoUnit.DAYS.between(hoy, c.getFechaCaducidad())));
            }
        }
        r.sort(Comparator.comparing(PorCaducar::fechaCaducidad).thenComparing(PorCaducar::producto, String.CASE_INSENSITIVE_ORDER));
        return r;
    }

    /** Entradas vigentes de un producto con lo estimado de cada una (más urgente primero); para el detalle */
    @Transactional(readOnly = true)
    public List<LoteEstimado> lotesDe(Long productoId) {
        Producto p = productoRepo.findById(productoId).orElse(null);
        if (p == null) return List.of();
        return estimar(p.getCantidadActual(), repo.vigentesDe(productoId));
    }

    /** "Caduca 20/11/2026: ~12 · 05/12/2026: ~6" (solo las que tienen algo estimado); vacío si no hay */
    @Transactional(readOnly = true)
    public String resumenLotes(Long productoId) {
        List<String> partes = new ArrayList<>();
        for (LoteEstimado le : lotesDe(productoId)) {
            if (le.estimado() <= 0) continue;
            partes.add(le.lote().getFechaCaducidad().format(FECHA) + ": ~" + AvisosService.cantidad(le.estimado()));
        }
        return partes.isEmpty() ? "" : "Caduca " + String.join(" · ", partes);
    }

    // ── Acciones (requieren AJUSTAR_INVENTARIO o autorización) ────────────

    /**
     * Retira una entrada caducada: da de baja {@code cantidad} como merma (ajuste con motivo
     * "Caducado ...") y la entrada deja de contar. {@code cantidad} null = lo estimado.
     * Requiere AJUSTAR_INVENTARIO o autorización (la consume el ajuste).
     */
    @Transactional
    public Producto retirar(Long caducidadId, Double cantidad, Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        Caducidad c = vigente(caducidadId);
        Producto p = productoRepo.findById(c.getProductoId())
                .orElseThrow(() -> new NegocioException("El producto ya no existe."));
        double existencia = p.getCantidadActual();
        double q = cantidad != null ? cantidad : estimadoDe(p, c);
        if (!Double.isFinite(q) || q <= 0) {
            throw new NegocioException("No hay existencia que retirar de esta entrada. Usa «Ya no queda» para cerrarla.");
        }
        if (q > existencia + 1e-9) {
            throw new NegocioException("No se pueden retirar " + AvisosService.cantidad(q) + ": la existencia es "
                    + AvisosService.cantidad(existencia) + ".");
        }
        Producto despues = productoService.ajustarStock(p.getId(), VentaService.redondearCantidad(existencia - q),
                MOTIVO_CADUCADO + " (caducidad " + c.getFechaCaducidad().format(FECHA) + ")", autorizaciones);
        cerrar(c, q);
        return despues;
    }

    /** La entrada deja de contar sin mover el inventario ("ya no queda nada de esa fecha") */
    @Transactional
    public void marcarSinExistencia(Long caducidadId, Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        Caducidad c = vigente(caducidadId);
        cerrar(c, null);
        Autorizacion aut = sesionService.autorizacionUsada(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        String nombre = productoRepo.findById(c.getProductoId()).map(Producto::getDescripcion).orElse("#" + c.getProductoId());
        auditoriaService.registrar(sesionService.getCajeroActual(), "CADUCIDAD_CERRADA",
                nombre + ": entrada que caduca el " + c.getFechaCaducidad().format(FECHA) + " marcada como «ya no queda»", aut);
        if (aut != null) aut.consumir();
    }

    private double estimadoDe(Producto p, Caducidad c) {
        return estimar(p.getCantidadActual(), repo.vigentesDe(p.getId())).stream()
                .filter(le -> le.lote().getId().equals(c.getId()))
                .mapToDouble(LoteEstimado::estimado).findFirst().orElse(0);
    }

    private Caducidad vigente(Long caducidadId) {
        Caducidad c = repo.findById(caducidadId)
                .orElseThrow(() -> new NegocioException("Esa entrada ya no existe."));
        if (!c.isVigente()) throw new NegocioException("Esa entrada ya se había retirado.");
        return c;
    }

    private void cerrar(Caducidad c, Double cantidad) {
        Cajero actor = sesionService.getCajeroActual();
        c.setRetiradaEn(LocalDateTime.now(reloj));
        c.setRetiradaPor(actor != null ? sesionService.getNombreCajero() : null);
        c.setRetiradaCantidad(cantidad);
        repo.save(c);
    }
}
