package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.FondoCaja;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CorteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.FondoCajaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Fondo de caja y cortes (CRT-02, CRT-06).
 *
 *  - Hay UN fondo de caja por día (regla "solo al iniciar el día"): lo
 *    captura quien hace el primer inicio de sesión del día y queda en la BD;
 *    nadie más lo vuelve a capturar ni se pierde al reiniciar. Solo un ADMIN
 *    puede corregirlo, y la corrección es un registro nuevo (nunca update):
 *    el fondo vigente es el último registrado ese día.
 *  - El corte es un registro guardado: efectivo esperado vs. contado, con
 *    faltante/sobrante, usuario y fecha. Puede hacerse varias veces al día.
 */
@Service
public class CajaService {

    @Autowired private FondoCajaRepository fondoRepo;
    @Autowired private CorteRepository     corteRepo;
    @Autowired private SesionService       sesionService;
    @Autowired private VentaService        ventaService;
    @Autowired private AuditoriaService    auditoriaService;

    // ── Fondo de caja ─────────────────────────────────────────────────────

    /** Fondo vigente del día (el último registrado: la corrección del admin gana) */
    public Optional<FondoCaja> fondoDelDia(LocalDate fecha) {
        return fondoRepo.findFirstByFechaOrderByRegistradoEnDesc(fecha);
    }

    /** ¿Todavía nadie ha capturado el fondo de hoy? (se pide una vez al día) */
    public boolean requiereFondo(Cajero cajero) {
        return fondoDelDia(LocalDate.now()).isEmpty();
    }

    /** Monto del fondo vigente de hoy (0 si no hay) */
    public double fondoDelCajeroActual() {
        return fondoVigenteDelDia(LocalDate.now());
    }

    public double fondoVigenteDelDia(LocalDate fecha) {
        return fondoDelDia(fecha).map(FondoCaja::getMonto).orElse(0.0);
    }

    /** Alta del fondo del día (una sola vez). Se usa desde el login. */
    @Transactional
    public FondoCaja registrarFondo(Cajero cajero, double monto) {
        if (cajero == null) throw new NegocioException("No hay una sesión activa.");
        validarMonto(monto);
        Optional<FondoCaja> existente = fondoDelDia(LocalDate.now());
        if (existente.isPresent()) {
            throw new NegocioException(String.format(
                    "El fondo de caja de hoy ya fue registrado ($%.2f por %s). Solo un administrador puede corregirlo.",
                    existente.get().getMonto(), existente.get().getCajeroNombre()));
        }
        FondoCaja guardado = fondoRepo.save(nuevoFondo(cajero, monto));
        auditoriaService.registrar(cajero, "FONDO_REGISTRADO", String.format("$%.2f", monto), (Cajero) null);
        return guardado;
    }

    /** Corrección del fondo del día (solo ADMIN): registro nuevo con rastro */
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
                "$%.2f → $%.2f (%s)", anterior, montoNuevo, motivo.trim()), (Cajero) null);
        return guardado;
    }

    private static FondoCaja nuevoFondo(Cajero cajero, double monto) {
        FondoCaja f = new FondoCaja();
        f.setFecha(LocalDate.now());
        f.setCajero(cajero);
        f.setCajeroNombre(cajero.getNombreCompleto());
        f.setMonto(VentaService.redondear(monto));
        return f;
    }

    private static void validarMonto(double monto) {
        if (!Double.isFinite(monto) || monto < 0) {
            throw new NegocioException("El fondo de caja no puede ser negativo.");
        }
    }

    // ── Corte ─────────────────────────────────────────────────────────────

    public List<Corte> cortesDeHoy() {
        return corteRepo.findByFechaOrderByRealizadoEnDesc(LocalDate.now());
    }

    public Optional<Corte> ultimoCorte() {
        return corteRepo.findFirstByOrderByRealizadoEnDesc();
    }

    /**
     * Guarda un corte del día con el efectivo contado (CRT-06).
     * Requiere HACER_CORTE. La ganancia se guarda siempre en el registro
     * (la pantalla la oculta a quien no tenga VER_GANANCIAS).
     */
    @Transactional
    public Corte hacerCorte(double efectivoContado, String observaciones) {
        sesionService.verificarPermiso(Permiso.HACER_CORTE);
        if (!Double.isFinite(efectivoContado) || efectivoContado < 0) {
            throw new NegocioException("El efectivo contado no puede ser negativo.");
        }
        Cajero cajero = sesionService.requerirSesion();
        VentaService.ResumenCorte r = ventaService.resumenCompletoDe(LocalDate.now());
        double fondo = fondoVigenteDelDia(LocalDate.now());

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
        Corte guardado = corteRepo.save(c);

        auditoriaService.registrar(cajero, "CORTE_REALIZADO", String.format(
                "esperado $%.2f, contado $%.2f, diferencia $%.2f",
                c.getEfectivoEsperado(), c.getEfectivoContado(), c.getDiferencia()), (Cajero) null);
        return guardado;
    }
}
