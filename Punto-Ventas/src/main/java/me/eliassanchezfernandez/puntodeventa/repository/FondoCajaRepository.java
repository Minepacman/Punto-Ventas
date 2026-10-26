package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.FondoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FondoCajaRepository extends JpaRepository<FondoCaja, Long> {

    /** Fondo vigente del día: el último registrado (la corrección del admin gana) */
    Optional<FondoCaja> findFirstByFechaOrderByRegistradoEnDesc(LocalDate fecha);

    List<FondoCaja> findByFechaOrderByRegistradoEnAsc(LocalDate fecha);

    boolean existsByCajeroId(Long cajeroId);
}
