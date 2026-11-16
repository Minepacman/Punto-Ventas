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

    // El corte resta las devoluciones hechas en el periodo (día de la devolución) en su desglose por
    // tasa de IVA y por departamento: estas consultas dan lo reembolsado agrupado igual que las ventas.

    /** Corte: [tasa, exento, importe reembolsado con IVA, IVA reembolsado] por tasa de las devoluciones de [inicio, fin) */
    @Query("SELECT COALESCE(dd.tasaIva, 0.16), COALESCE(dd.ivaExento, false), SUM(dd.importe), SUM(COALESCE(dd.iva, 0.0)) "
         + "FROM DevolucionDetalle dd JOIN dd.devolucion d "
         + "WHERE d.fecha >= :inicio AND d.fecha < :fin" + FILTRO_CAJA
         + " GROUP BY COALESCE(dd.tasaIva, 0.16), COALESCE(dd.ivaExento, false)")
    List<Object[]> ivaPorTasaEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** Corte: [departamento (null = sin departamento), importe reembolsado] de las devoluciones de [inicio, fin) */
    @Query("SELECT dep.nombre, SUM(dd.importe) FROM DevolucionDetalle dd JOIN dd.devolucion d "
         + "LEFT JOIN Producto p ON p.id = dd.productoId LEFT JOIN p.departamento dep "
         + "WHERE d.fecha >= :inicio AND d.fecha < :fin" + FILTRO_CAJA + " GROUP BY dep.nombre")
    List<Object[]> importesPorDepartamentoEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** Corte: importe reembolsado de "productos comunes" (fuera del catálogo) en [inicio, fin) */
    @Query("SELECT COALESCE(SUM(dd.importe), 0.0) FROM DevolucionDetalle dd JOIN dd.devolucion d, Producto p "
         + "WHERE p.id = dd.productoId AND p.codigoBarras = :codigoComun "
         + "AND d.fecha >= :inicio AND d.fecha < :fin" + FILTRO_CAJA)
    Double importeProductoComunEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja, String codigoComun);
}
