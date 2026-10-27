package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ResumenDiarioRepository extends JpaRepository<ResumenDiario, Long> {

    Optional<ResumenDiario> findByFecha(LocalDate fecha);

    /** Días con ventas en el rango (inclusive), en orden cronológico */
    List<ResumenDiario> findByFechaBetweenOrderByFechaAsc(LocalDate desde, LocalDate hasta);

    @Modifying
    @Query("DELETE FROM ResumenDiario r WHERE r.fecha IN :fechas")
    void deleteByFechas(List<LocalDate> fechas);
}
