package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Entradas y salidas de efectivo. Rangos semiabiertos [inicio, fin) y filtro
 * por caja ({@code null} = todas; las filas sin caja son de la caja 1), igual
 * que las consultas del corte en {@link VentaRepository}.
 */
@Repository
public interface MovimientoCajaRepository extends JpaRepository<MovimientoCaja, Long> {

    String FILTRO_CAJA = " AND (:caja IS NULL OR COALESCE(m.caja, 1) = :caja)";

    @Query("SELECT m FROM MovimientoCaja m WHERE m.fecha >= :inicio AND m.fecha < :fin" + FILTRO_CAJA
            + " ORDER BY m.fecha ASC, m.id ASC")
    List<MovimientoCaja> findEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** [categoria, suma] de los movimientos del rango */
    @Query("SELECT m.categoria, COALESCE(SUM(m.monto), 0) FROM MovimientoCaja m "
            + "WHERE m.fecha >= :inicio AND m.fecha < :fin" + FILTRO_CAJA + " GROUP BY m.categoria")
    List<Object[]> sumasPorCategoria(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** Cajas que han registrado movimientos en el rango */
    @Query("SELECT DISTINCT COALESCE(m.caja, 1) FROM MovimientoCaja m WHERE m.fecha >= :inicio AND m.fecha < :fin")
    List<Integer> cajasEntre(LocalDateTime inicio, LocalDateTime fin);

    boolean existsByCajeroId(Long cajeroId);
}
