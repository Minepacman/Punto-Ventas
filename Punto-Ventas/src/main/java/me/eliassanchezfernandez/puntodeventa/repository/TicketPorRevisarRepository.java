package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.TicketPorRevisar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TicketPorRevisarRepository extends JpaRepository<TicketPorRevisar, Long> {

    /** Resumen sin la foto ni la lectura (las listas nunca cargan los bytes) */
    record Resumen(Long id, LocalDateTime creadoEn, String usuarioNombre, Long proveedorId, String nota) { }

    @Query("select new me.eliassanchezfernandez.puntodeventa.repository.TicketPorRevisarRepository$Resumen("
            + "t.id, t.creadoEn, t.usuarioNombre, t.proveedorId, t.nota) "
            + "from TicketPorRevisar t where t.estado = :estado order by t.creadoEn asc, t.id asc")
    List<Resumen> resumenes(@Param("estado") TicketPorRevisar.Estado estado);

    long countByEstado(TicketPorRevisar.Estado estado);

    /** Cierra el pendiente solo si sigue pendiente (con varias cajas, la segunda que lo intente no lo pisa) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TicketPorRevisar t set t.estado = :estado, t.resueltoEn = :cuando, t.resueltoPor = :quien, t.registroId = :registro "
            + "where t.id = :id and t.estado = :pendiente")
    int cerrar(@Param("id") Long id, @Param("estado") TicketPorRevisar.Estado estado, @Param("cuando") LocalDateTime cuando,
               @Param("quien") String quien, @Param("registro") Long registro,
               @Param("pendiente") TicketPorRevisar.Estado pendiente);
}
