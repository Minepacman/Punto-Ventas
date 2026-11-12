package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.TicketPendiente;
import me.eliassanchezfernandez.puntodeventa.repository.TicketPendienteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tickets sin cobrar:
 *  - el autoguardado, que se conserva al cerrar sesión y se restaura cuando el
 *    MISMO cajero vuelve a entrar en la MISMA caja (USR-15): la mercancía se
 *    quedó en esa caja;
 *  - los "en espera" (F11): el cajero aparta el ticket para atender a otro
 *    cliente y lo recupera después (Shift+F11); varios por caja, con una nota
 *    opcional, y los puede recuperar cualquier cajero de esa caja.
 * Los renglones restaurados no traen precios: el controlador los recalcula
 * con VentaService.
 */
@Service
public class TicketPendienteService {

    /** Tope de tickets en espera por caja (evita que se acumulen olvidados) */
    public static final int MAX_EN_ESPERA = 20;
    public static final int MAX_LARGO_NOTA = 60;

    @Autowired private TicketPendienteRepository ticketRepo;
    @Autowired private SesionService             sesionService;
    @Autowired private IdentidadCaja             identidadCaja;

    /** Guarda (reemplazando el anterior) el ticket autoguardado del cajero en sesión */
    @Transactional
    public void guardar(List<DetalleVenta> detalles) {
        Cajero cajero = sesionService.requerirSesion();
        descartar(cajero.getId());
        if (detalles == null || detalles.isEmpty()) return;
        ticketRepo.save(armar(cajero, detalles, false, null));
    }

    private TicketPendiente armar(Cajero cajero, List<DetalleVenta> detalles, boolean enEspera, String nota) {
        TicketPendiente t = new TicketPendiente();
        t.setCajero(cajero);
        t.setCaja(identidadCaja.numero());
        t.setEnEspera(enEspera ? Boolean.TRUE : null);
        t.setNota(nota);
        for (DetalleVenta d : detalles) {
            if (d.getProducto() == null || d.getProducto().getId() == null) continue;
            TicketPendiente.Linea l = new TicketPendiente.Linea();
            l.setTicket(t);
            l.setProducto(d.getProducto());
            l.setCantidad(d.getCantidad());
            l.setMayoreo(d.isMayoreo());
            l.setDescuentoManualPct(d.getDescuentoManualPct());
            l.setDescuentoManualImporte(d.getDescuentoManualImporte());
            t.getLineas().add(l);
        }
        return t;
    }

    private static List<DetalleVenta> aDetalles(TicketPendiente ticket) {
        List<DetalleVenta> detalles = new ArrayList<>();
        ticket.getLineas().forEach(l -> {
            if (!l.getProducto().isActivo()) return;   // producto descontinuado mientras tanto
            DetalleVenta d = new DetalleVenta();
            d.setProducto(l.getProducto());
            d.setCantidad(l.getCantidad());
            d.setMayoreo(l.isMayoreo());
            d.setDescuentoManualPct(l.getDescuentoManualPct());
            d.setDescuentoManualImporte(l.getDescuentoManualImporte());
            detalles.add(d);
        });
        return detalles;
    }

    // ── Tickets en espera (F11 / Shift+F11) ──────────────────────────────

    /** Resumen de un ticket en espera para la lista de "Recuperar" */
    public record EnEspera(Long id, java.time.LocalDateTime guardadoEn, String nota, String cajero,
                           int renglones, double piezas, List<DetalleVenta> detalles) { }

    /**
     * Aparta el ticket en captura con una nota opcional. Requiere sesión; no
     * toca inventario (la mercancía sigue sin venderse hasta que se cobre).
     */
    @Transactional
    public EnEspera ponerEnEspera(List<DetalleVenta> detalles, String nota) {
        Cajero cajero = sesionService.requerirSesion();
        if (detalles == null || detalles.stream().noneMatch(d -> d.getProducto() != null && d.getProducto().getId() != null)) {
            throw new NegocioException("El ticket está vacío: no hay nada que poner en espera.");
        }
        if (ticketRepo.contarEnEsperaDeCaja(identidadCaja.numero()) >= MAX_EN_ESPERA) {
            throw new NegocioException("Ya hay " + MAX_EN_ESPERA + " tickets en espera en esta caja. Recupera o descarta alguno (Shift+F11).");
        }
        String n = nota == null ? null : nota.trim();
        if (n != null && n.isEmpty()) n = null;
        if (n != null && n.length() > MAX_LARGO_NOTA) {
            throw new NegocioException("La nota del ticket en espera no puede pasar de " + MAX_LARGO_NOTA + " caracteres.");
        }
        TicketPendiente t = ticketRepo.save(armar(cajero, detalles, true, n));
        return resumen(t);
    }

    /** Tickets en espera de ESTA caja, del más antiguo al más reciente */
    @Transactional(readOnly = true)
    public List<EnEspera> listarEnEspera() {
        return ticketRepo.findEnEsperaDeCaja(identidadCaja.numero()).stream().map(TicketPendienteService::resumen).toList();
    }

    public int contarEnEspera() {
        return (int) ticketRepo.contarEnEsperaDeCaja(identidadCaja.numero());
    }

    /**
     * Saca el ticket de la espera y devuelve sus renglones (sin precios) para
     * ponerlos en captura. Si ya no existe (otro cajero lo recuperó) lanza
     * NegocioException.
     */
    @Transactional
    public List<DetalleVenta> recuperarEnEspera(Long id) {
        sesionService.requerirSesion();
        TicketPendiente t = ticketRepo.findEnEsperaByIdYCaja(id, identidadCaja.numero())
                .orElseThrow(() -> new NegocioException("Ese ticket en espera ya no existe (quizá ya se recuperó)."));
        List<DetalleVenta> detalles = aDetalles(t);
        ticketRepo.delete(t);
        return detalles;
    }

    /** Descarta un ticket en espera sin cobrarlo (requiere sesión) */
    @Transactional
    public void descartarEnEspera(Long id) {
        sesionService.requerirSesion();
        ticketRepo.findEnEsperaByIdYCaja(id, identidadCaja.numero()).ifPresent(ticketRepo::delete);
    }

    private static EnEspera resumen(TicketPendiente t) {
        List<DetalleVenta> detalles = aDetalles(t);
        double piezas = detalles.stream().mapToDouble(DetalleVenta::getCantidad).sum();
        return new EnEspera(t.getId(), t.getGuardadoEn(), t.getNota(),
                t.getCajero() != null ? t.getCajero().getNombreCompleto() : "", detalles.size(), piezas, detalles);
    }

    /**
     * Renglones del ticket pendiente del cajero en sesión (sin precios).
     * El ticket se conserva hasta que se cobre o se cancele.
     */
    @Transactional(readOnly = true)
    public List<DetalleVenta> recuperar() {
        Cajero cajero = sesionService.getCajeroActual();
        if (cajero == null) return List.of();
        Optional<TicketPendiente> t = ticketRepo.findConLineasByCajeroIdYCaja(cajero.getId(), identidadCaja.numero());
        return t.map(TicketPendienteService::aDetalles).orElseGet(ArrayList::new);
    }

    public boolean hayPendiente() {
        Cajero cajero = sesionService.getCajeroActual();
        return cajero != null && ticketRepo.findConLineasByCajeroIdYCaja(cajero.getId(), identidadCaja.numero()).isPresent();
    }

    /** Descarta el ticket pendiente del cajero en ESTA caja (el de otras cajas se conserva) */
    @Transactional
    public void descartar(Long cajeroId) {
        if (cajeroId == null) return;
        ticketRepo.findConLineasByCajeroIdYCaja(cajeroId, identidadCaja.numero()).ifPresent(ticketRepo::delete);
    }

    @Transactional
    public void descartarDelCajeroActual() {
        Cajero cajero = sesionService.getCajeroActual();
        if (cajero != null) descartar(cajero.getId());
    }
}
