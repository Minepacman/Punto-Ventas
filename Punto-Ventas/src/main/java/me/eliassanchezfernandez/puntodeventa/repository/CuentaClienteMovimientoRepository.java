package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.CuentaClienteMovimiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Libro de las cuentas de clientes (fiado). Rangos [inicio, fin); caja null = todas. */
@Repository
public interface CuentaClienteMovimientoRepository extends JpaRepository<CuentaClienteMovimiento, Long> {

    @Query("SELECT m FROM CuentaClienteMovimiento m WHERE m.clienteId = :clienteId ORDER BY m.fecha ASC, m.id ASC")
    List<CuentaClienteMovimiento> findDeCliente(Long clienteId);

    @Query("SELECT m FROM CuentaClienteMovimiento m WHERE m.clienteId = :clienteId "
            + "AND m.fecha >= :inicio AND m.fecha < :fin ORDER BY m.fecha ASC, m.id ASC")
    List<CuentaClienteMovimiento> findDeClienteEntre(Long clienteId, LocalDateTime inicio, LocalDateTime fin);

    /** Saldo según el libro (suma de todos los renglones del cliente) */
    @Query("SELECT COALESCE(SUM(m.monto), 0) FROM CuentaClienteMovimiento m WHERE m.clienteId = :clienteId")
    double sumaDeCliente(Long clienteId);

    /** Saldo del cliente antes de una fecha (saldo inicial del estado de cuenta) */
    @Query("SELECT COALESCE(SUM(m.monto), 0) FROM CuentaClienteMovimiento m WHERE m.clienteId = :clienteId AND m.fecha < :antes")
    double sumaDeClienteAntesDe(Long clienteId, LocalDateTime antes);

    /** Renglones de una venta (el cargo y lo que ya se revirtió por cancelación o devolución) */
    @Query("SELECT m FROM CuentaClienteMovimiento m WHERE m.ventaId = :ventaId ORDER BY m.id ASC")
    List<CuentaClienteMovimiento> findDeVenta(Long ventaId);

    /** [formaPago, suma de abonos (positiva)] del rango */
    @Query("SELECT m.formaPago, COALESCE(SUM(-m.monto), 0) FROM CuentaClienteMovimiento m "
            + "WHERE m.tipo = 'ABONO' AND m.fecha >= :inicio AND m.fecha < :fin "
            + "AND (:caja IS NULL OR COALESCE(m.caja, 1) = :caja) GROUP BY m.formaPago")
    List<Object[]> abonosPorForma(LocalDateTime inicio, LocalDateTime fin, Integer caja);
}
