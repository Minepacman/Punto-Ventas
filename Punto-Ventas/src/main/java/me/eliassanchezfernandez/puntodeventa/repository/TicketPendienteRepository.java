package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.TicketPendiente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TicketPendienteRepository extends JpaRepository<TicketPendiente, Long> {

    @Query("SELECT DISTINCT t FROM TicketPendiente t LEFT JOIN FETCH t.lineas l LEFT JOIN FETCH l.producto " +
           "WHERE t.cajero.id = :cajeroId")
    Optional<TicketPendiente> findConLineasByCajeroId(Long cajeroId);

    void deleteByCajeroId(Long cajeroId);

    @Query("SELECT COUNT(l) FROM TicketPendiente t JOIN t.lineas l WHERE l.producto.id = :productoId")
    long contarLineasConProducto(Long productoId);
}
