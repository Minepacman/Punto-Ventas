package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.FondoCaja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CorteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.FondoCajaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoCajaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Fondo de caja, estado del efectivo y cortes (CRT-02, CRT-06, CRT-08, CRT-09), por caja.
 *
 *  - Si la opción "pedir fondo de caja" está apagada en Configuración, el login
 *    no lo solicita (el fondo del día cuenta como 0).
 *  - Hay UN fondo por caja y por día (regla "solo al iniciar el día"): lo
 *    captura quien hace el primer inicio de sesión del día en ESA caja y queda
 *    en la BD; nadie más lo vuelve a capturar ni se pierde al reiniciar. Solo
 *    un ADMIN puede corregirlo, y la corrección es un registro nuevo (nunca
 *    update): el fondo vigente es el último registrado ese día en esa caja.
 *  - <b>El corte de caja es por TURNO</b>: cubre desde el corte anterior de esa
 *    caja hasta ahora. El efectivo esperado es lo que había al empezar el turno
 *    (el fondo del día, o lo que el corte anterior dejó en el cajón) más las
 *    ventas en efectivo del turno, más las entradas y menos las salidas de
 *    efectivo. Al guardar el corte se captura lo contado y cuánto se deja en el
 *    cajón para el siguiente turno; la diferencia (contado − dejado) es el
 *    retiro. Reglas del inicio del turno ({@link #turnoActual}):
 *      · si hay un corte anterior de la caja y es posterior al primer fondo
 *        capturado hoy (o no hay fondo hoy), el turno empieza en ese corte con
 *        lo que dejó (así un turno que cruza la medianoche no se pierde);
 *      · si hay fondo capturado hoy y ningún corte después de él, el turno
 *        empieza con el día (00:00) y con el fondo;
 *      · sin fondo hoy ni cortes previos: desde las 00:00 con 0.
 *  - El "reporte del día" ({@link #estadoDelDia}) sigue existiendo: suma todo
 *    el día calendario (ventas, entradas, salidas, fondo y retiros de los cortes).
 *  - Con varias cajas cada corte es de la caja donde se hace; el administrador
 *    puede además hacer un corte GENERAL: lo que debe haber ahora en TODOS los
 *    cajones (la suma del esperado del turno actual de cada caja).
 *  - Las filas anteriores a "varias cajas" no tienen caja y cuentan como caja 1.
 */
@Service
public class CajaService {

    @Autowired private FondoCajaRepository fondoRepo;
    @Autowired private CorteRepository     corteRepo;
    @Autowired private MovimientoCajaRepository movimientoRepo;
    @Autowired private VentaRepository     ventaRepo;
    @Autowired private SesionService       sesionService;
    @Autowired private VentaService        ventaService;
    @Autowired private AuditoriaService    auditoriaService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private IdentidadCaja       identidadCaja;

    /** Número de la caja en la que corre este programa */
    public int cajaActual() {
        return identidadCaja.numero();
    }

    // ── Fondo de caja ─────────────────────────────────────────────────────

    /** Fondo vigente del día en ESTA caja (el último registrado: la corrección del admin gana) */
    public Optional<FondoCaja> fondoDelDia(LocalDate fecha) {
        return fondoDelDia(fecha, cajaActual());
    }

    public Optional<FondoCaja> fondoDelDia(LocalDate fecha, int caja) {
        return fondoRepo.findDeCaja(fecha, caja).stream().findFirst();
    }

    /** Primer fondo capturado en el día en esa caja (marca el arranque del día; las correcciones no lo mueven) */
    private Optional<FondoCaja> primerFondoDelDia(LocalDate fecha, int caja) {
        List<FondoCaja> fondos = fondoRepo.findDeCaja(fecha, caja);
        return fondos.isEmpty() ? Optional.empty() : Optional.of(fondos.get(fondos.size() - 1));
    }

    /** ¿Todavía nadie ha capturado el fondo de hoy en esta caja? (se pide una vez al día por caja) */
    public boolean requiereFondo(Cajero cajero) {
        if (!configuracionService.isPedirFondoCaja()) return false;   // opción deshabilitada
        return fondoDelDia(LocalDate.now()).isEmpty();
    }

    /** Monto del fondo vigente de hoy en esta caja (0 si no hay) */
    public double fondoDelCajeroActual() {
        return fondoVigenteDelDia(LocalDate.now());
    }

    public double fondoVigenteDelDia(LocalDate fecha) {
        return fondoVigenteDelDia(fecha, cajaActual());
    }

    public double fondoVigenteDelDia(LocalDate fecha, int caja) {
        return fondoDelDia(fecha, caja).map(FondoCaja::getMonto).orElse(0.0);
    }

    /** Fondo vigente de cada caja en el día (caja → monto), solo las cajas que registraron fondo */
    public Map<Integer, Double> fondosPorCaja(LocalDate fecha) {
        Map<Integer, FondoCaja> vigente = new LinkedHashMap<>();
        for (FondoCaja f : fondoRepo.findByFechaOrderByRegistradoEnAsc(fecha)) {
            vigente.put(f.getCajaONumero(), f);   // el último registrado de cada caja gana
        }
        Map<Integer, Double> montos = new java.util.TreeMap<>();
        vigente.forEach((caja, f) -> montos.put(caja, f.getMonto()));
        return montos;
    }

    /** Suma de los fondos vigentes de todas las cajas del día (para el corte general) */
    public double fondoTotalDelDia(LocalDate fecha) {
        return VentaService.redondear(fondosPorCaja(fecha).values().stream().mapToDouble(Double::doubleValue).sum());
    }

    /** Alta del fondo del día en esta caja (una sola vez). Se usa desde el login. */
    @Transactional
    public FondoCaja registrarFondo(Cajero cajero, double monto) {
        if (cajero == null) throw new NegocioException("No hay una sesión activa.");
        validarMonto(monto);
        Optional<FondoCaja> existente = fondoDelDia(LocalDate.now());
        if (existente.isPresent()) {
            throw new NegocioException(String.format(
                    "El fondo de hoy de la %s ya fue registrado (%s por %s). Solo un administrador puede corregirlo.",
                    IdentidadCaja.etiqueta(cajaActual()),
                    Moneda.formato(existente.get().getMonto()), existente.get().getCajeroNombre()));
        }
        FondoCaja guardado = fondoRepo.save(nuevoFondo(cajero, monto));
        auditoriaService.registrar(cajero, "FONDO_REGISTRADO",
                IdentidadCaja.etiqueta(cajaActual()) + ": " + Moneda.formato(monto), (Cajero) null);
        return guardado;
    }

    /** Corrección del fondo del día de esta caja (solo ADMIN): registro nuevo con rastro */
    @Transactional
    public FondoCaja corregirFondo(double montoNuevo, String motivo) {
        sesionService.verificarAdmin();
        validarMonto(montoNuevo);
        if (motivo == null || motivo.isBlank()) {
            throw new NegocioException("Indica el motivo de la corrección del fondo.");
        }
        Cajero admin = sesionService.requerirSesion();
        double anterior = fondoVigenteDelDia(LocalDate.now());
        FondoCaja guardado = fondoRepo.save(nuevoFondo(admin, montoNuevo));
        auditoriaService.registrar(admin, "FONDO_CORREGIDO", String.format(
                "%s: %s → %s (%s)", IdentidadCaja.etiqueta(cajaActual()),
                Moneda.formato(anterior), Moneda.formato(montoNuevo), motivo.trim()), (Cajero) null);
        return guardado;
    }

    private FondoCaja nuevoFondo(Cajero cajero, double monto) {
        FondoCaja f = new FondoCaja();
        f.setFecha(LocalDate.now());
        f.setCajero(cajero);
        f.setCajeroNombre(cajero.getNombreCompleto());
        f.setMonto(VentaService.redondear(monto));
        f.setCaja(cajaActual());
        return f;
    }

    private static void validarMonto(double monto) {
        if (!Double.isFinite(monto) || monto < 0) {
            throw new NegocioException("El fondo de caja no puede ser negativo.");
        }
    }

    // ── Turno actual y estado del efectivo ────────────────────────────────

    /** Dónde empieza el turno en curso de una caja y con cuánto efectivo */
    public record Turno(LocalDateTime inicio, double efectivoInicial, Corte corteAnterior, boolean inicialEsFondo) {
        public boolean empiezaConElDia(LocalDate dia) { return inicio.equals(dia.atStartOfDay()); }
    }

    /** Turno en curso de ESTA caja (ver las reglas en la cabecera de la clase) */
    public Turno turnoActual() {
        return turnoActual(cajaActual(), LocalDate.now());
    }

    public Turno turnoActual(int caja, LocalDate hoy) {
        Optional<Corte> ultimo = corteRepo.findUltimoDeCaja(caja);
        Optional<FondoCaja> fondoHoy = primerFondoDelDia(hoy, caja);
        LocalDateTime inicioDia = hoy.atStartOfDay();
        if (ultimo.isPresent() && (fondoHoy.isEmpty() || !ultimo.get().getRealizadoEn().isBefore(fondoHoy.get().getRegistradoEn()))) {
            Corte c = ultimo.get();
            // El turno sigue desde el último corte: el mismo día, o la noche anterior si nadie capturó fondo hoy
            return new Turno(c.getRealizadoEn(), c.getEfectivoDejadoOFondo(), c, false);
        }
        if (fondoHoy.isPresent()) {
            // Día nuevo con fondo capturado: empieza con el día (o tras un corte de hoy anterior al fondo) y con el fondo
            LocalDateTime inicio = ultimo.map(Corte::getRealizadoEn).filter(r -> r.isAfter(inicioDia)).orElse(inicioDia);
            return new Turno(inicio, fondoVigenteDelDia(hoy, caja), ultimo.filter(c -> c.getRealizadoEn().isAfter(inicioDia)).orElse(null), true);
        }
        return new Turno(inicioDia, 0, null, true);
    }

    /**
     * Estado del efectivo de una caja en un periodo: ventas (resumen), entradas y
     * salidas, cortes y lo que debe haber en el cajón. Lo arma {@link #estadoDe}.
     */
    public static final class EstadoCaja {
        public VentaService.ResumenCorte ventas;
        public int caja;
        public LocalDateTime inicio;
        public LocalDateTime fin;
        /** true: turno actual (desde el corte anterior); false: día completo */
        public boolean turno;
        /** Corte del que arranca el turno (null si el periodo empieza con el día) */
        public Corte corteAnterior;
        /** Fondo vigente del día */
        public double fondo;
        /** Con lo que empezó el periodo: fondo (día) o lo dejado por el corte anterior (turno) */
        public double efectivoInicial;
        public boolean efectivoInicialEsFondo;
        public double entradas;
        public double salidas;
        public final Map<MovimientoCaja.Categoria, Double> porCategoria = new EnumMap<>(MovimientoCaja.Categoria.class);
        public final List<MovimientoCaja> movimientos = new ArrayList<>();
        /** Cortes de la caja dentro del periodo (en el día completo: los del día; en el turno: ninguno) */
        public final List<Corte> cortes = new ArrayList<>();
        /** Suma de retiros (contado − dejado) de esos cortes */
        public double retiros;

        public double monto(MovimientoCaja.Categoria c) { return porCategoria.getOrDefault(c, 0.0); }

        /** Efectivo que debe haber en el cajón al final del periodo */
        public double esperado() {
            return VentaService.redondear(efectivoInicial + ventas.efectivo + entradas - salidas - retiros);
        }
    }

    /** Estado del turno en curso de ESTA caja (HACER_CORTE o VER_GANANCIAS) */
    @Transactional(readOnly = true)
    public EstadoCaja estadoTurnoActual() {
        return estadoDe(cajaActual(), LocalDate.now(), true, true);
    }

    /** Estado del día completo de ESTA caja (HACER_CORTE o VER_GANANCIAS) */
    @Transactional(readOnly = true)
    public EstadoCaja estadoDelDia() {
        return estadoDe(cajaActual(), LocalDate.now(), false, true);
    }

    /**
     * @param turno          true: desde el inicio del turno actual hasta ahora; false: el día calendario completo
     * @param conPermisos    true: exige HACER_CORTE o VER_GANANCIAS y oculta la ganancia a quien no deba verla;
     *                       false: uso interno (guardar el corte, calcular el efectivo antes de un movimiento)
     */
    @Transactional(readOnly = true)
    public EstadoCaja estadoDe(int caja, LocalDate dia, boolean turno, boolean conPermisos) {
        LocalDateTime fin = dia.equals(LocalDate.now()) ? ahora() : dia.plusDays(1).atStartOfDay();
        if (turno) {
            Turno t = turnoActual(caja, dia);
            return estadoDe(caja, t.inicio(), fin, t, conPermisos);
        }
        return estadoDe(caja, dia.atStartOfDay(), fin, null, conPermisos);
    }

    /** Estado del periodo que cubrió un corte guardado (para reimprimirlo) */
    @Transactional(readOnly = true)
    public EstadoCaja estadoDelCorte(Corte c) {
        if (c.isGeneral()) {
            EstadoCaja e = new EstadoCaja();
            e.ventas = ventaService.resumenEntre(c.getFecha().atStartOfDay(), c.getRealizadoEn(), null);
            e.caja = c.getCajaONumero();
            e.inicio = c.getFecha().atStartOfDay();
            e.fin = c.getRealizadoEn();
            e.fondo = c.getFondoInicial();
            e.efectivoInicial = c.getEfectivoInicialOFondo();
            e.efectivoInicialEsFondo = true;
            e.entradas = c.getEntradasOCero();
            e.salidas = c.getSalidasOCero();
            e.movimientos.addAll(movimientoRepo.findEntre(e.inicio, e.fin, null));
            return e;
        }
        Turno t = new Turno(c.getInicioOInicioDelDia(), c.getEfectivoInicialOFondo(), null, c.getInicioOInicioDelDia().equals(c.getFecha().atStartOfDay()));
        return estadoDe(c.getCajaONumero(), t.inicio(), c.getRealizadoEn(), t, true);
    }

    private EstadoCaja estadoDe(int caja, LocalDateTime inicio, LocalDateTime fin, Turno turno, boolean conPermisos) {
        EstadoCaja e = new EstadoCaja();
        e.caja = caja;
        e.inicio = inicio;
        e.fin = fin;
        e.turno = turno != null;
        e.ventas = conPermisos ? ventaService.resumenEntre(inicio, fin, caja) : ventaService.resumenCompletoEntre(inicio, fin, caja);
        LocalDate dia = fin.toLocalDate();
        e.fondo = fondoVigenteDelDia(dia, caja);
        if (turno != null) {
            e.corteAnterior = turno.corteAnterior();
            e.efectivoInicial = turno.efectivoInicial();
            e.efectivoInicialEsFondo = turno.inicialEsFondo();
        } else {
            e.efectivoInicial = e.fondo;
            e.efectivoInicialEsFondo = true;
            e.cortes.addAll(corteRepo.findDeCaja(dia, caja));
            e.retiros = VentaService.redondear(e.cortes.stream().mapToDouble(Corte::getRetiro).sum());
        }
        sumarMovimientos(e, inicio, fin, caja);
        return e;
    }

    private void sumarMovimientos(EstadoCaja e, LocalDateTime inicio, LocalDateTime fin, Integer caja) {
        for (Object[] f : movimientoRepo.sumasPorCategoria(inicio, fin, caja)) {
            MovimientoCaja.Categoria cat = (MovimientoCaja.Categoria) f[0];
            double monto = VentaService.redondear(((Number) f[1]).doubleValue());
            e.porCategoria.merge(cat, monto, Double::sum);
            if (cat.getTipo() == MovimientoCaja.Tipo.ENTRADA) e.entradas += monto; else e.salidas += monto;
        }
        e.entradas = VentaService.redondear(e.entradas);
        e.salidas  = VentaService.redondear(e.salidas);
        e.movimientos.addAll(movimientoRepo.findEntre(inicio, fin, caja));
    }

    /** Efectivo que debe haber ahora en ESTA caja (sin permisos: lo anota cada movimiento de efectivo) */
    double efectivoEsperadoAhora(int caja) {
        return estadoDe(caja, LocalDate.now(), true, false).esperado();
    }

    /** Estado de TODAS las cajas en el día (corte general, solo ADMIN): ventas del día y lo que debe haber ahora en cada cajón */
    @Transactional(readOnly = true)
    public EstadoCaja estadoGeneralDelDia() {
        sesionService.verificarAdmin();
        return estadoGeneral(LocalDate.now());
    }

    private EstadoCaja estadoGeneral(LocalDate dia) {
        LocalDateTime inicio = dia.atStartOfDay();
        LocalDateTime fin = dia.equals(LocalDate.now()) ? ahora() : dia.plusDays(1).atStartOfDay();
        EstadoCaja e = new EstadoCaja();
        e.caja = cajaActual();
        e.inicio = inicio;
        e.fin = fin;
        e.turno = false;
        e.ventas = ventaService.resumenCompletoEntre(inicio, fin, null);
        e.fondo = fondoTotalDelDia(dia);
        e.efectivoInicial = e.fondo;
        e.efectivoInicialEsFondo = true;
        sumarMovimientos(e, inicio, fin, null);
        // Lo que debe haber en todos los cajones = suma del esperado del turno en curso de cada caja
        double esperado = 0;
        for (int caja : cajasConActividad(dia)) {
            esperado += estadoDe(caja, dia, true, false).esperado();
        }
        // Se expresa como "retiros" para que esperado() = fondo + efectivo + entradas − salidas − retiros cuadre
        e.retiros = VentaService.redondear(e.efectivoInicial + e.ventas.efectivo + e.entradas - e.salidas - esperado);
        for (Corte c : corteRepo.findByFechaOrderByRealizadoEnDesc(dia)) if (!c.isGeneral()) e.cortes.add(c);
        return e;
    }

    /** Cajas con ventas, fondo o movimientos en el día, o que alguna vez hicieron corte (su cajón puede tener dinero) */
    List<Integer> cajasConActividad(LocalDate dia) {
        LocalDateTime inicio = dia.atStartOfDay(), fin = dia.plusDays(1).atStartOfDay();
        TreeSet<Integer> cajas = new TreeSet<>();
        cajas.add(cajaActual());
        for (Object[] f : ventaRepo.ventasPorCaja(inicio, fin)) cajas.add(((Number) f[0]).intValue());
        cajas.addAll(fondosPorCaja(dia).keySet());
        cajas.addAll(movimientoRepo.cajasEntre(inicio, fin));
        cajas.addAll(corteRepo.cajasConCorte());
        return new ArrayList<>(cajas);
    }

    // ── Corte ─────────────────────────────────────────────────────────────

    /** Cortes de hoy de TODAS las cajas (incluidos los generales), el más reciente primero */
    public List<Corte> cortesDeHoy() {
        return corteRepo.findByFechaOrderByRealizadoEnDesc(LocalDate.now());
    }

    /** Cortes de hoy de esta caja (sin los generales), el más reciente primero */
    public List<Corte> cortesDeHoyDeEstaCaja() {
        return corteRepo.findDeCaja(LocalDate.now(), cajaActual());
    }

    public Optional<Corte> ultimoCorte() {
        return corteRepo.findFirstByOrderByRealizadoEnDesc();
    }

    /**
     * Historial de cortes entre dos días (inclusive): los de esta caja y los
     * generales; con una sola caja, todos. Requiere HACER_CORTE o VER_GANANCIAS.
     */
    @Transactional(readOnly = true)
    public List<Corte> cortesEntre(LocalDate desde, LocalDate hasta) {
        if (!sesionService.tienePermiso(Permiso.HACER_CORTE)) sesionService.verificarPermiso(Permiso.VER_GANANCIAS);
        if (desde == null) desde = LocalDate.now();
        if (hasta == null) hasta = desde;
        if (hasta.isBefore(desde)) throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        if (java.time.temporal.ChronoUnit.DAYS.between(desde, hasta) > 366) {
            throw new NegocioException("El historial de cortes se consulta por periodos de hasta un año.");
        }
        int caja = cajaActual();
        boolean multi = identidadCaja.esMultiCaja();
        List<Corte> lista = new ArrayList<>();
        for (Corte c : corteRepo.findEntreFechas(desde, hasta)) {
            if (!multi || c.isGeneral() || c.getCajaONumero() == caja) lista.add(c);
        }
        return lista;
    }

    /** Compatibilidad: corte dejando en el cajón el fondo del día (lo demás se retira) */
    @Transactional
    public Corte hacerCorte(double efectivoContado, String observaciones) {
        return hacerCorte(efectivoContado, null, observaciones);
    }

    /**
     * Guarda el corte del TURNO en curso de ESTA caja (CRT-06): desde el corte
     * anterior (o el inicio del día) hasta ahora. Requiere HACER_CORTE. La
     * ganancia se guarda siempre en el registro (la pantalla la oculta a quien
     * no tenga VER_GANANCIAS).
     *
     * @param efectivoContado lo que hay en el cajón al contar
     * @param efectivoDejado  lo que se deja en el cajón para el siguiente turno;
     *                        null = el fondo del día (si lo contado no alcanza, todo lo contado)
     */
    @Transactional
    public Corte hacerCorte(double efectivoContado, Double efectivoDejado, String observaciones) {
        sesionService.verificarPermiso(Permiso.HACER_CORTE);
        Cajero cajero = sesionService.requerirSesion();
        int caja = cajaActual();
        validarContado(efectivoContado);
        LocalDateTime ahora = ahora();
        LocalDate hoy = ahora.toLocalDate();
        Turno t = turnoActual(caja, hoy);
        EstadoCaja e = estadoDe(caja, t.inicio(), ahora, t, false);
        double dejado = efectivoDejado != null ? efectivoDejado : Math.min(e.fondo, efectivoContado);
        if (!Double.isFinite(dejado) || dejado < 0) {
            throw new NegocioException("El efectivo que se deja en caja no puede ser negativo.");
        }
        if (dejado > efectivoContado + 0.005) {
            throw new NegocioException(String.format("No puedes dejar en caja %s si solo contaste %s.",
                    Moneda.formato(dejado), Moneda.formato(efectivoContado)));
        }
        Corte c = armarCorte(cajero, e, efectivoContado, observaciones);
        c.setRealizadoEn(ahora);
        c.setFecha(hoy);
        c.setEfectivoDejado(VentaService.redondear(dejado));
        c.setCaja(caja);
        c.setGeneral(false);
        return guardarCorte(cajero, c, "CORTE_REALIZADO", IdentidadCaja.etiqueta(caja)
                + (t.empiezaConElDia(hoy) ? "" : ", turno desde " + t.inicio().format(FMT)));
    }

    /**
     * Corte GENERAL del día (solo ADMIN): ventas del día de todas las cajas y la
     * suma de sus fondos, contra el efectivo contado de todas juntas. Lo esperado
     * es lo que debe haber AHORA en todos los cajones (suma del turno en curso de
     * cada caja), así los retiros de los cortes de turno ya hechos no se piden dos veces.
     */
    @Transactional
    public Corte hacerCorteGeneral(double efectivoContado, String observaciones) {
        sesionService.verificarAdmin();
        Cajero admin = sesionService.requerirSesion();
        validarContado(efectivoContado);
        EstadoCaja e = estadoGeneral(LocalDate.now());
        Corte c = armarCorte(admin, e, efectivoContado, observaciones);
        c.setRealizadoEn(e.fin);
        c.setCaja(cajaActual());   // desde qué caja se hizo
        c.setGeneral(true);
        return guardarCorte(admin, c, "CORTE_GENERAL", "todas las cajas");
    }

    /**
     * Límite superior de un periodo "hasta ahora". Los rangos son semiabiertos
     * [inicio, fin) y la base guarda las fechas con milisegundos: para que una
     * venta o un movimiento registrados en este mismo milisegundo cuenten (y un
     * corte los cubra y no se repitan en el siguiente turno, que arranca en
     * {@code realizadoEn}), el límite se corre un milisegundo hacia adelante.
     */
    static LocalDateTime ahora() {
        return LocalDateTime.now().plus(1, java.time.temporal.ChronoUnit.MILLIS);
    }

    private static void validarContado(double efectivoContado) {
        if (!Double.isFinite(efectivoContado) || efectivoContado < 0) {
            throw new NegocioException("El efectivo contado no puede ser negativo.");
        }
    }

    private static final java.time.format.DateTimeFormatter FMT = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private static Corte armarCorte(Cajero cajero, EstadoCaja e, double efectivoContado, String observaciones) {
        VentaService.ResumenCorte r = e.ventas;
        Corte c = new Corte();
        c.setCajero(cajero);
        c.setCajeroNombre(cajero.getNombreCompleto());
        c.setInicio(e.inicio);
        c.setFondoInicial(e.fondo);
        c.setEfectivoInicial(VentaService.redondear(e.efectivoInicial));
        c.setEntradasEfectivo(e.entradas);
        c.setSalidasEfectivo(e.salidas);
        c.setVentasEfectivo(r.efectivo);
        c.setVentasTarjeta(r.tarjeta);
        c.setVentasTransferencia(r.transferencia);
        c.setVentasVales(r.vales);
        c.setVentasTotales(r.ventasTotales);
        c.setNumVentas(r.numVentas);
        c.setNumCanceladas(r.numCanceladas);
        c.setDevoluciones(r.numDevoluciones > 0 ? r.totalDevoluciones : null);
        c.setGanancia(r.ganancia);
        c.setIva(r.iva);
        c.setEfectivoEsperado(e.esperado());
        c.setEfectivoContado(VentaService.redondear(efectivoContado));
        c.setDiferencia(VentaService.redondear(efectivoContado - c.getEfectivoEsperado()));
        c.setObservaciones(observaciones == null || observaciones.isBlank() ? null : observaciones.trim());
        return c;
    }

    private Corte guardarCorte(Cajero cajero, Corte c, String accion, String alcance) {
        Corte guardado = corteRepo.save(c);
        auditoriaService.registrar(cajero, accion, String.format(
                "%s: esperado %s, contado %s, diferencia %s%s", alcance,
                Moneda.formato(c.getEfectivoEsperado()), Moneda.formato(c.getEfectivoContado()),
                Moneda.formato(c.getDiferencia()),
                c.getEfectivoDejado() != null ? ", queda en caja " + Moneda.formato(c.getEfectivoDejado()) : ""), (Cajero) null);
        return guardado;
    }
}
