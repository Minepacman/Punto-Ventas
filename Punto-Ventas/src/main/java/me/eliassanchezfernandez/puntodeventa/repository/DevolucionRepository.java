package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Devolucion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DevolucionRepository extends JpaRepository<Devolucion, Long> {

    /** Filtro por caja (null = todas); las filas sin caja cuentan como caja 1 */
    String FILTRO_CAJA = " AND (:caja IS NULL OR COALESCE(d.caja, 1) = :caja)";

    @Query("SELECT DISTINCT d FROM Devolucion d LEFT JOIN FETCH d.detalles WHERE d.ventaId = :ventaId ORDER BY d.id ASC")
    List<Devolucion> findConDetallesByVentaId(Long ventaId);

    @Query("SELECT d FROM Devolucion d LEFT JOIN FETCH d.detalles WHERE d.id = :id")
    Optional<Devolucion> findConDetallesById(Long id);

    /**
     * Corte: cuántas devoluciones, cuánto se reembolsó, cuánto IVA iba incluido y cuánto de lo
     * reembolsado se descontó de cuentas de clientes (fiado, no salió del cajón) en [inicio, fin)
     */
    @Query("SELECT COUNT(d), COALESCE(SUM(d.total), 0.0), COALESCE(SUM(d.iva), 0.0), COALESCE(SUM(d.aCuenta), 0.0) FROM Devolucion d "
         + "WHERE d.fecha >= :inicio AND d.fecha < :fin" + FILTRO_CAJA)
    List<Object[]> resumenEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** Corte: costo de la mercancía devuelta que regresó al anaquel (la de merma no se recupera) */
    @Query("SELECT COALESCE(SUM(dd.costoUnitario * dd.cantidad), 0.0) FROM DevolucionDetalle dd JOIN dd.devolucion d "
         + "WHERE dd.regresaAInventario = true AND dd.costoUnitario IS NOT NULL "
         + "AND d.fecha >= :inicio AND d.fecha < :fin" + FILTRO_CAJA)
    Double costoRecuperadoEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja);
}
