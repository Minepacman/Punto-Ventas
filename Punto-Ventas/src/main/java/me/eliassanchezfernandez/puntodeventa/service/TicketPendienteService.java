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
 * Ticket sin cobrar que se conserva al cerrar sesión y se restaura cuando
 * el MISMO cajero vuelve a entrar en la MISMA caja (USR-15): la mercancía se
 * quedó en esa caja. Los renglones restaurados no traen precios: el
 * controlador los recalcula con VentaService.
 */
@Service
public class TicketPendienteService {

    @Autowired private TicketPendienteRepository ticketRepo;
    @Autowired private SesionService             sesionService;
    @Autowired private IdentidadCaja             identidadCaja;

    /** Guarda (reemplazando el anterior) el ticket del cajero en sesión */
    @Transactional
    public void guardar(List<DetalleVenta> detalles) {
        Cajero cajero = sesionService.requerirSesion();
        descartar(cajero.getId());
        if (detalles == null || detalles.isEmpty()) return;

        TicketPendiente t = new TicketPendiente();
        t.setCajero(cajero);
        t.setCaja(identidadCaja.numero());
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
        ticketRepo.save(t);
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
        List<DetalleVenta> detalles = new ArrayList<>();
        t.ifPresent(ticket -> ticket.getLineas().forEach(l -> {
            if (!l.getProducto().isActivo()) return;   // producto descontinuado mientras tanto
            DetalleVenta d = new DetalleVenta();
            d.setProducto(l.getProducto());
            d.setCantidad(l.getCantidad());
            d.setMayoreo(l.isMayoreo());
            d.setDescuentoManualPct(l.getDescuentoManualPct());
            d.setDescuentoManualImporte(l.getDescuentoManualImporte());
            detalles.add(d);
        }));
        return detalles;
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
