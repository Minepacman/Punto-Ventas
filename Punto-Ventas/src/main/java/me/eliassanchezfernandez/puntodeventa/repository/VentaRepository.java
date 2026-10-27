package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Los rangos de fechas son semiabiertos [inicio, fin): así una venta
 * registrada exactamente a medianoche cae en un solo día.
 * Las ventas canceladas se conservan pero NO cuentan en los totales.
 */
@Repository
public interface VentaRepository extends JpaRepository<Venta, Long> {

    @Query("SELECT v FROM Venta v WHERE v.fecha >= :inicio AND v.fecha < :fin ORDER BY v.fecha DESC")
    List<Venta> findEntre(LocalDateTime inicio, LocalDateTime fin);

    /**
     * Ventas de un período (incluidas las canceladas, con su estado) con sus
     * renglones, productos y pagos ya cargados para poder mostrarlos fuera de
     * la transacción.
     */
    @Query("SELECT DISTINCT v FROM Venta v " +
           "LEFT JOIN FETCH v.detalles d " +
           "LEFT JOIN FETCH d.producto p " +
           "LEFT JOIN FETCH p.departamento " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin " +
           "ORDER BY v.fecha DESC")
    List<Venta> findConDetallesEntre(LocalDateTime inicio, LocalDateTime fin);

    @Query("SELECT DISTINCT v FROM Venta v LEFT JOIN FETCH v.pagos WHERE v IN :ventas")
    List<Venta> cargarPagos(List<Venta> ventas);

    @Query("SELECT DISTINCT v FROM Venta v " +
           "LEFT JOIN FETCH v.detalles d LEFT JOIN FETCH d.producto " +
           "WHERE v.id = :id")
    Optional<Venta> findConDetallesById(Long id);

    /** Primera venta registrada (para el nivel de datos de Estadísticas) */
    Optional<Venta> findFirstByOrderByFechaAsc();

    /** Mayor id de venta (0 si no hay): el siguiente folio es este + 1 */
    @Query("SELECT COALESCE(MAX(v.id), 0) FROM Venta v")
    long maxId();

    boolean existsByCajeroId(Long cajeroId);

    @Query("SELECT SUM(v.total) FROM Venta v WHERE v.fecha >= :inicio AND v.fecha < :fin " +
           "AND (v.cancelada IS NULL OR v.cancelada = false)")
    Double sumTotalEntre(LocalDateTime inicio, LocalDateTime fin);

    // ── Estadísticas (agregados) ─────────────────────────────────────────

    /**
     * Ventas ACTIVAS (no canceladas) de un rango con renglones y productos,
     * para calcular los agregados. Sin orden: el servicio agrupa por día.
     */
    @Query("SELECT DISTINCT v FROM Venta v " +
           "LEFT JOIN FETCH v.detalles d " +
           "LEFT JOIN FETCH d.producto " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin " +
           "AND (v.cancelada IS NULL OR v.cancelada = false)")
    List<Venta> findActivasConDetallesEntre(LocalDateTime inicio, LocalDateTime fin);

    /** Ventas activas de un rango SIN renglones (fecha y total: ventas por clima) */
    @Query("SELECT v FROM Venta v WHERE v.fecha >= :inicio AND v.fecha < :fin " +
           "AND (v.cancelada IS NULL OR v.cancelada = false)")
    List<Venta> findActivasEntre(LocalDateTime inicio, LocalDateTime fin);

    /**
     * Fechas de venta cuyos agregados cambiaron desde un momento dado: ventas
     * registradas después, o canceladas después (la cancelación mueve el día
     * de la venta original, no el de la cancelación).
     */
    @Query("SELECT DISTINCT v.fecha FROM Venta v WHERE v.fecha >= :desde " +
           "OR (v.fechaCancelacion IS NOT NULL AND v.fechaCancelacion >= :desde)")
    List<LocalDateTime> findFechasAfectadasDesde(LocalDateTime desde);

    // ── Corte del día: totales calculados en SQL (sin cargar tickets ni renglones) ──

    /** [n, total, subtotal, iva] de las ventas ACTIVAS del rango (una sola fila) */
    @Query("SELECT COUNT(v), COALESCE(SUM(v.total), 0), COALESCE(SUM(COALESCE(v.subtotal, v.total)), 0), " +
           "COALESCE(SUM(COALESCE(v.iva, 0)), 0) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)")
    List<Object[]> resumenActivas(LocalDateTime inicio, LocalDateTime fin);

    /** [n, total] de las ventas CANCELADAS del rango (una sola fila) */
    @Query("SELECT COUNT(v), COALESCE(SUM(v.total), 0) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND v.cancelada = true")
    List<Object[]> resumenCanceladas(LocalDateTime inicio, LocalDateTime fin);

    /** [formaPago, monto] de los pagos de ventas activas; del efectivo se descuenta el cambio */
    @Query("SELECT p.formaPago, SUM(p.monto - CASE WHEN p.formaPago = :efectivo THEN v.cambio ELSE 0.0 END) " +
           "FROM Venta v JOIN v.pagos p " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "GROUP BY p.formaPago")
    List<Object[]> pagosPorForma(LocalDateTime inicio, LocalDateTime fin, Venta.FormaPago efectivo);

    /** [formaPago, total] de ventas activas anteriores al pago dividido (sin filas de pago) */
    @Query("SELECT v.formaPago, SUM(v.total) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "AND NOT EXISTS (SELECT p.id FROM PagoVenta p WHERE p.venta = v) " +
           "GROUP BY v.formaPago")
    List<Object[]> ventasSinPagosPorForma(LocalDateTime inicio, LocalDateTime fin);

    /** [departamento (null = sin departamento), importe, costo] de los renglones de ventas activas */
    @Query("SELECT dep.nombre, SUM(d.precioVenta * d.cantidad - d.descuento), " +
           "SUM(COALESCE(d.costoUnitario, p.precioCosto) * d.cantidad) " +
           "FROM DetalleVenta d JOIN d.venta v JOIN d.producto p LEFT JOIN p.departamento dep " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "GROUP BY dep.nombre ORDER BY SUM(d.precioVenta * d.cantidad - d.descuento) DESC")
    List<Object[]> importesPorDepartamento(LocalDateTime inicio, LocalDateTime fin);

    /** [terminal (null = sin datos), nº de pagos, monto] de los cobros con tarjeta de ventas activas */
    @Query("SELECT p.terminalNombre, COUNT(p), SUM(p.monto) FROM Venta v JOIN v.pagos p " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "AND p.formaPago = :tarjeta GROUP BY p.terminalNombre ORDER BY SUM(p.monto) DESC")
    List<Object[]> tarjetaPorTerminal(LocalDateTime inicio, LocalDateTime fin, Venta.FormaPago tarjeta);

    /** Cobros con tarjeta de ventas activas del rango, con su venta, del más antiguo al más reciente */
    @Query("SELECT p FROM PagoVenta p JOIN FETCH p.venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "AND p.formaPago = :tarjeta ORDER BY v.fecha ASC")
    List<me.eliassanchezfernandez.puntodeventa.model.PagoVenta> vouchersTarjeta(LocalDateTime inicio, LocalDateTime fin, Venta.FormaPago tarjeta);

    // ── Reportes de Estadísticas: por cajero y por hora (ventas activas, leídas de la tabla de ventas) ──

    /** [nombre del cajero (null = sin dato), nº de ventas, total] de las ventas ACTIVAS del rango */
    @Query("SELECT v.cajeroNombre, COUNT(v), COALESCE(SUM(v.total), 0) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "GROUP BY v.cajeroNombre ORDER BY SUM(v.total) DESC")
    List<Object[]> ventasPorCajero(LocalDateTime inicio, LocalDateTime fin);

    /** [fecha, total] de cada venta ACTIVA del rango (solo dos columnas: ligero para agrupar por hora) */
    @Query("SELECT v.fecha, v.total FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)")
    List<Object[]> fechasYTotalesActivas(LocalDateTime inicio, LocalDateTime fin);
}
