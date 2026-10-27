package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Corte;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CorteRepository extends JpaRepository<Corte, Long> {

    List<Corte> findByFechaOrderByRealizadoEnDesc(LocalDate fecha);

    /** Cortes de UNA caja en el día (sin los generales), el más reciente primero */
    @Query("SELECT c FROM Corte c WHERE c.fecha = :fecha AND COALESCE(c.caja, 1) = :caja " +
           "AND (c.general IS NULL OR c.general = false) ORDER BY c.realizadoEn DESC, c.id DESC")
    List<Corte> findDeCaja(LocalDate fecha, int caja);

    Optional<Corte> findFirstByOrderByRealizadoEnDesc();

    boolean existsByCajeroId(Long cajeroId);
}
