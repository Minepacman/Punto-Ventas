package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.FondoCaja;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CorteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.FondoCajaRepository;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fondo de caja y cortes (CRT-02, CRT-06), por caja.
 *
 *  - Si la opción "pedir fondo de caja" está apagada en Configuración, el login
 *    no lo solicita (el fondo del día cuenta como 0).
 *  - Hay UN fondo por caja y por día (regla "solo al iniciar el día"): lo
 *    captura quien hace el primer inicio de sesión del día en ESA caja y queda
 *    en la BD; nadie más lo vuelve a capturar ni se pierde al reiniciar. Solo
 *    un ADMIN puede corregirlo, y la corrección es un registro nuevo (nunca
 *    update): el fondo vigente es el último registrado ese día en esa caja.
 *  - El corte es un registro guardado: efectivo esperado vs. contado, con
 *    faltante/sobrante, usuario y fecha. Puede hacerse varias veces al día. Con
 *    varias cajas cada corte es de la caja donde se hace; el administrador
 *    puede además hacer un corte GENERAL que suma todas las cajas.
 *  - Las filas anteriores a "varias cajas" no tienen caja y cuentan como caja 1.
 */
@Service
public class CajaService {

    @Autowired private FondoCajaRepository fondoRepo;
    @Autowired private CorteRepository     corteRepo;
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
     * Guarda un corte del día de ESTA caja con el efectivo contado (CRT-06).
     * Requiere HACER_CORTE. La ganancia se guarda siempre en el registro
     * (la pantalla la oculta a quien no tenga VER_GANANCIAS).
     */
    @Transactional
    public Corte hacerCorte(double efectivoContado, String observaciones) {
        sesionService.verificarPermiso(Permiso.HACER_CORTE);
        Cajero cajero = sesionService.requerirSesion();
        int caja = cajaActual();
        VentaService.ResumenCorte r = ventaService.resumenCompletoDe(LocalDate.now(), caja);
        double fondo = fondoVigenteDelDia(LocalDate.now(), caja);
        Corte c = armarCorte(cajero, r, fondo, efectivoContado, observaciones);
        c.setCaja(caja);
        c.setGeneral(false);
        return guardarCorte(cajero, c, "CORTE_REALIZADO", IdentidadCaja.etiqueta(caja));
    }

    /**
     * Corte GENERAL del día (solo ADMIN): ventas de todas las cajas y la suma
     * de sus fondos, contra el efectivo contado de todas juntas.
     */
    @Transactional
    public Corte hacerCorteGeneral(double efectivoContado, String observaciones) {
        sesionService.verificarAdmin();
        Cajero admin = sesionService.requerirSesion();
        VentaService.ResumenCorte r = ventaService.resumenCompletoDe(LocalDate.now(), null);
        double fondo = fondoTotalDelDia(LocalDate.now());
        Corte c = armarCorte(admin, r, fondo, efectivoContado, observaciones);
        c.setCaja(cajaActual());   // desde qué caja se hizo
        c.setGeneral(true);
        return guardarCorte(admin, c, "CORTE_GENERAL", "todas las cajas");
    }

    private static Corte armarCorte(Cajero cajero, VentaService.ResumenCorte r, double fondo,
                                    double efectivoContado, String observaciones) {
        if (!Double.isFinite(efectivoContado) || efectivoContado < 0) {
            throw new NegocioException("El efectivo contado no puede ser negativo.");
        }
        Corte c = new Corte();
        c.setCajero(cajero);
        c.setCajeroNombre(cajero.getNombreCompleto());
        c.setFondoInicial(fondo);
        c.setVentasEfectivo(r.efectivo);
        c.setVentasTarjeta(r.tarjeta);
        c.setVentasTotales(r.ventasTotales);
        c.setNumVentas(r.numVentas);
        c.setNumCanceladas(r.numCanceladas);
        c.setGanancia(r.ganancia);
        c.setIva(r.iva);
        c.setEfectivoEsperado(VentaService.redondear(fondo + r.efectivo));
        c.setEfectivoContado(VentaService.redondear(efectivoContado));
        c.setDiferencia(VentaService.redondear(efectivoContado - c.getEfectivoEsperado()));
        c.setObservaciones(observaciones == null || observaciones.isBlank() ? null : observaciones.trim());
        return c;
    }

    private Corte guardarCorte(Cajero cajero, Corte c, String accion, String alcance) {
        Corte guardado = corteRepo.save(c);
        auditoriaService.registrar(cajero, accion, String.format(
                "%s: esperado %s, contado %s, diferencia %s", alcance,
                Moneda.formato(c.getEfectivoEsperado()), Moneda.formato(c.getEfectivoContado()),
                Moneda.formato(c.getDiferencia())), (Cajero) null);
        return guardado;
    }
}
