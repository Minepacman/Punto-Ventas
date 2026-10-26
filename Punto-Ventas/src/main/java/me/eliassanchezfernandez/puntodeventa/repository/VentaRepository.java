package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Los rangos de fechas son semiabiertos [inicio, fin): así una venta
 * registrada exactamente a medianoche cae en un solo día.
 */
@Repository
public interface VentaRepository extends JpaRepository<Venta, Long> {

    @Query("SELECT v FROM Venta v WHERE v.fecha >= :inicio AND v.fecha < :fin ORDER BY v.fecha DESC")
    List<Venta> findEntre(LocalDateTime inicio, LocalDateTime fin);

    /**
     * Ventas de un período con sus renglones y productos ya cargados
     * (JOIN FETCH) para poder mostrarlos fuera de la transacción.
     */
    @Query("SELECT DISTINCT v FROM Venta v " +
           "LEFT JOIN FETCH v.detalles d " +
           "LEFT JOIN FETCH d.producto p " +
           "LEFT JOIN FETCH p.departamento " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin " +
           "ORDER BY v.fecha DESC")
    List<Venta> findConDetallesEntre(LocalDateTime inicio, LocalDateTime fin);

    @Query("SELECT SUM(v.total) FROM Venta v WHERE v.fecha >= :inicio AND v.fecha < :fin")
    Double sumTotalEntre(LocalDateTime inicio, LocalDateTime fin);
}
