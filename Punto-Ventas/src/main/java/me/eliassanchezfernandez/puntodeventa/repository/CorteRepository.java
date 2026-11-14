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

    /** Último corte de UNA caja (sin los generales), de cualquier día: de ahí arranca el turno actual */
    @Query("SELECT c FROM Corte c WHERE COALESCE(c.caja, 1) = :caja AND (c.general IS NULL OR c.general = false) " +
           "ORDER BY c.realizadoEn DESC, c.id DESC")
    List<Corte> findUltimosDeCaja(int caja, org.springframework.data.domain.Pageable pagina);

    default Optional<Corte> findUltimoDeCaja(int caja) {
        return findUltimosDeCaja(caja, org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst();
    }

    /** Cortes (de todas las cajas, incluidos los generales) entre dos días inclusive, el más reciente primero */
    @Query("SELECT c FROM Corte c WHERE c.fecha >= :desde AND c.fecha <= :hasta ORDER BY c.realizadoEn DESC, c.id DESC")
    List<Corte> findEntreFechas(LocalDate desde, LocalDate hasta);

    /** Cajas que alguna vez han hecho un corte propio (no general) */
    @Query("SELECT DISTINCT COALESCE(c.caja, 1) FROM Corte c WHERE c.general IS NULL OR c.general = false")
    List<Integer> cajasConCorte();

    boolean existsByCajeroId(Long cajeroId);
}
