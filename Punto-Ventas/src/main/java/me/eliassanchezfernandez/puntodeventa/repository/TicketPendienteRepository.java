package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.TicketPendiente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketPendienteRepository extends JpaRepository<TicketPendiente, Long> {

    /** Solo los autoguardados (no los puestos en espera a propósito) */
    String SOLO_AUTOGUARDADOS = " AND (t.enEspera IS NULL OR t.enEspera = false)";

    @Query("SELECT DISTINCT t FROM TicketPendiente t LEFT JOIN FETCH t.lineas l LEFT JOIN FETCH l.producto " +
           "WHERE t.cajero.id = :cajeroId" + SOLO_AUTOGUARDADOS)
    Optional<TicketPendiente> findConLineasByCajeroId(Long cajeroId);

    /** Ticket autoguardado del cajero en UNA caja (el ticket físico se quedó en esa caja) */
    @Query("SELECT DISTINCT t FROM TicketPendiente t LEFT JOIN FETCH t.lineas l LEFT JOIN FETCH l.producto " +
           "WHERE t.cajero.id = :cajeroId AND COALESCE(t.caja, 1) = :caja" + SOLO_AUTOGUARDADOS)
    Optional<TicketPendiente> findConLineasByCajeroIdYCaja(Long cajeroId, int caja);

    /** Tickets puestos en espera en una caja, del más antiguo al más reciente */
    @Query("SELECT DISTINCT t FROM TicketPendiente t LEFT JOIN FETCH t.lineas l LEFT JOIN FETCH l.producto " +
           "WHERE t.enEspera = true AND COALESCE(t.caja, 1) = :caja ORDER BY t.guardadoEn ASC, t.id ASC")
    List<TicketPendiente> findEnEsperaDeCaja(int caja);

    @Query("SELECT DISTINCT t FROM TicketPendiente t LEFT JOIN FETCH t.lineas l LEFT JOIN FETCH l.producto " +
           "WHERE t.id = :id AND t.enEspera = true AND COALESCE(t.caja, 1) = :caja")
    Optional<TicketPendiente> findEnEsperaByIdYCaja(Long id, int caja);

    @Query("SELECT COUNT(t) FROM TicketPendiente t WHERE t.enEspera = true AND COALESCE(t.caja, 1) = :caja")
    long contarEnEsperaDeCaja(int caja);

    void deleteByCajeroId(Long cajeroId);

    @Query("SELECT COUNT(l) FROM TicketPendiente t JOIN t.lineas l WHERE l.producto.id = :productoId")
    long contarLineasConProducto(Long productoId);
}
