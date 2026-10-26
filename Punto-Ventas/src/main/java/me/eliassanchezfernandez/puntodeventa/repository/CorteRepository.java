package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Corte;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CorteRepository extends JpaRepository<Corte, Long> {

    List<Corte> findByFechaOrderByRealizadoEnDesc(LocalDate fecha);

    Optional<Corte> findFirstByOrderByRealizadoEnDesc();

    boolean existsByCajeroId(Long cajeroId);
}
