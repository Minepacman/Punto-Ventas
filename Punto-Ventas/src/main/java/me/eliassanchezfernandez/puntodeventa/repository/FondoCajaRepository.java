package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.FondoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FondoCajaRepository extends JpaRepository<FondoCaja, Long> {

    /** Fondo vigente del día (todas las cajas juntas): el último registrado (la corrección del admin gana) */
    Optional<FondoCaja> findFirstByFechaOrderByRegistradoEnDesc(LocalDate fecha);

    /** Fondos de UNA caja en el día, del más reciente al más antiguo (las filas sin caja son de la caja 1) */
    @Query("SELECT f FROM FondoCaja f WHERE f.fecha = :fecha AND COALESCE(f.caja, 1) = :caja ORDER BY f.registradoEn DESC, f.id DESC")
    List<FondoCaja> findDeCaja(LocalDate fecha, int caja);

    List<FondoCaja> findByFechaOrderByRegistradoEnAsc(LocalDate fecha);

    boolean existsByCajeroId(Long cajeroId);
}
