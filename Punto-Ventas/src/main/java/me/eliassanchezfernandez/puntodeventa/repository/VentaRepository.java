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

    /**
     * Marca la venta como cancelada SOLO si sigue activa en la base de datos (UPDATE condicionado).
     * Con varias cajas, dos pueden leer la misma venta "no cancelada" y cancelarla a la vez: la
     * primera afecta 1 fila; la segunda 0 y debe detenerse sin devolver mercancía otra vez. En
     * PostgreSQL el UPDATE además bloquea la fila hasta que la transacción termina, así la otra
     * caja espera y luego ve el estado real. @return 1 si la marcó, 0 si ya estaba cancelada
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("UPDATE Venta v SET v.cancelada = true, v.fechaCancelacion = :fecha, v.motivoCancelacion = :motivo, " +
           "v.canceladaPorId = :quienId, v.canceladaPorNombre = :quienNombre, " +
           "v.cancelacionAutorizadaPorId = :autorizoId, v.cancelacionAutorizadaPor = :autorizoNombre " +
           "WHERE v.id = :id AND (v.cancelada IS NULL OR v.cancelada = false)")
    int marcarCancelada(Long id, LocalDateTime fecha, String motivo, Long quienId, String quienNombre,
                        Long autorizoId, String autorizoNombre);

    /** Primera venta registrada (cancelada o no; para recalcular agregados desde el inicio) */
    Optional<Venta> findFirstByOrderByFechaAsc();

    /** Primera venta ACTIVA (para la madurez de datos de Estadísticas: una venta cancelada no es una venta) */
    @Query("SELECT v FROM Venta v WHERE v.cancelada IS NULL OR v.cancelada = false ORDER BY v.fecha ASC, v.id ASC")
    List<Venta> findPrimerasActivas(org.springframework.data.domain.Pageable pagina);

    default Optional<Venta> findPrimeraActiva() {
        return findPrimerasActivas(org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst();
    }

    /** Última venta cobrada en una caja (cancelada o no), para reimprimir el último ticket */
    @Query("SELECT v FROM Venta v WHERE COALESCE(v.caja, 1) = :caja ORDER BY v.id DESC")
    List<Venta> findUltimasDeCaja(int caja, org.springframework.data.domain.Pageable pagina);

    /** Mayor id de venta (0 si no hay): el siguiente folio es este + 1 */
    @Query("SELECT COALESCE(MAX(v.id), 0) FROM Venta v")
    long maxId();

    boolean existsByCajeroId(Long cajeroId);

    @Query("SELECT SUM(v.total) FROM Venta v WHERE v.fecha >= :inicio AND v.fecha < :fin " +
           "AND (v.cancelada IS NULL OR v.cancelada = false)" + FILTRO_CAJA)
    Double sumTotalEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja);

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
    // Todas aceptan una caja (null = todas las cajas); las ventas anteriores a "varias cajas" (caja null) son de la caja 1.

    String FILTRO_CAJA = " AND (:caja IS NULL OR COALESCE(v.caja, 1) = :caja)";

    /** [n, total, subtotal, iva] de las ventas ACTIVAS del rango (una sola fila) */
    @Query("SELECT COUNT(v), COALESCE(SUM(v.total), 0), COALESCE(SUM(COALESCE(v.subtotal, v.total)), 0), " +
           "COALESCE(SUM(COALESCE(v.iva, 0)), 0) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)" + FILTRO_CAJA)
    List<Object[]> resumenActivas(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /**
     * Vista "Hoy" de Estadísticas: en UNA sola fila y una sola consulta,
     * [n del día completo, total del día completo, n hasta el corte, total hasta el corte]
     * de las ventas ACTIVAS de un día ({@code corte} es la hora de hoy llevada a ese día:
     * así "ayer hasta las 15:00" se compara con "hoy hasta las 15:00"). Sin cargar tickets.
     */
    @Query("SELECT COUNT(v), COALESCE(SUM(v.total), 0), " +
           "COALESCE(SUM(CASE WHEN v.fecha < :corte THEN 1 ELSE 0 END), 0), " +
           "COALESCE(SUM(CASE WHEN v.fecha < :corte THEN v.total ELSE 0.0 END), 0) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)")
    List<Object[]> resumenActivasConCorte(LocalDateTime inicio, LocalDateTime fin, LocalDateTime corte);

    /**
     * [tasa, exento, base, iva] por tasa de IVA de los renglones de ventas ACTIVAS del rango.
     * Los renglones anteriores al IVA por producto (tasa e IVA en null) entran con la tasa de su
     * venta y su IVA se desglosa aquí mismo (importe − importe / (1 + tasa)).
     */
    @Query("SELECT COALESCE(d.tasaIva, v.tasaIva, 0.16), COALESCE(d.ivaExento, false), " +
           "SUM(d.precioVenta * d.cantidad - d.descuento), " +
           "SUM(COALESCE(d.iva, (d.precioVenta * d.cantidad - d.descuento) " +
           "    - (d.precioVenta * d.cantidad - d.descuento) / (1 + COALESCE(d.tasaIva, v.tasaIva, 0.16)))) " +
           "FROM DetalleVenta d JOIN d.venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)" + FILTRO_CAJA +
           " GROUP BY COALESCE(d.tasaIva, v.tasaIva, 0.16), COALESCE(d.ivaExento, false)")
    List<Object[]> ivaPorTasa(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** [n, total] de las ventas CANCELADAS del rango (una sola fila) */
    @Query("SELECT COUNT(v), COALESCE(SUM(v.total), 0) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND v.cancelada = true" + FILTRO_CAJA)
    List<Object[]> resumenCanceladas(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** [formaPago, monto] de los pagos de ventas activas; del efectivo se descuenta el cambio */
    @Query("SELECT p.formaPago, SUM(p.monto - CASE WHEN p.formaPago = :efectivo THEN v.cambio ELSE 0.0 END) " +
           "FROM Venta v JOIN v.pagos p " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)" + FILTRO_CAJA +
           " GROUP BY p.formaPago")
    List<Object[]> pagosPorForma(LocalDateTime inicio, LocalDateTime fin, Venta.FormaPago efectivo, Integer caja);

    /** [formaPago, total] de ventas activas anteriores al pago dividido (sin filas de pago) */
    @Query("SELECT v.formaPago, SUM(v.total) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "AND NOT EXISTS (SELECT p.id FROM PagoVenta p WHERE p.venta = v)" + FILTRO_CAJA +
           " GROUP BY v.formaPago")
    List<Object[]> ventasSinPagosPorForma(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** [departamento (null = sin departamento), importe, costo] de los renglones de ventas activas */
    @Query("SELECT dep.nombre, SUM(d.precioVenta * d.cantidad - d.descuento), " +
           "SUM(COALESCE(d.costoUnitario, p.precioCosto) * d.cantidad) " +
           "FROM DetalleVenta d JOIN d.venta v JOIN d.producto p LEFT JOIN p.departamento dep " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)" + FILTRO_CAJA +
           " GROUP BY dep.nombre ORDER BY SUM(d.precioVenta * d.cantidad - d.descuento) DESC")
    List<Object[]> importesPorDepartamento(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /**
     * [importe, costo] de los renglones de "producto común" (artículos fuera del catálogo tecleados en
     * Ventas, model/ProductoComun) de las ventas activas: el corte los muestra aparte con su ganancia estimada.
     */
    @Query("SELECT COALESCE(SUM(d.precioVenta * d.cantidad - d.descuento), 0), " +
           "COALESCE(SUM(COALESCE(d.costoUnitario, 0) * d.cantidad), 0) " +
           "FROM DetalleVenta d JOIN d.venta v JOIN d.producto p " +
           "WHERE p.codigoBarras = :codigoComun " +
           "AND v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)" + FILTRO_CAJA)
    List<Object[]> importeProductoComun(LocalDateTime inicio, LocalDateTime fin, Integer caja, String codigoComun);

    /** [terminal (null = sin datos), nº de pagos, monto] de los cobros con tarjeta de ventas activas */
    @Query("SELECT p.terminalNombre, COUNT(p), SUM(p.monto) FROM Venta v JOIN v.pagos p " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "AND p.formaPago = :tarjeta" + FILTRO_CAJA + " GROUP BY p.terminalNombre ORDER BY SUM(p.monto) DESC")
    List<Object[]> tarjetaPorTerminal(LocalDateTime inicio, LocalDateTime fin, Venta.FormaPago tarjeta, Integer caja);

    /** Cobros con tarjeta de ventas activas del rango, con su venta, del más antiguo al más reciente */
    @Query("SELECT p FROM PagoVenta p JOIN FETCH p.venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "AND p.formaPago = :tarjeta" + FILTRO_CAJA + " ORDER BY v.fecha ASC")
    List<me.eliassanchezfernandez.puntodeventa.model.PagoVenta> vouchersTarjeta(LocalDateTime inicio, LocalDateTime fin, Venta.FormaPago tarjeta, Integer caja);

    // ── Reportes de Estadísticas: por cajero y por hora (ventas activas, leídas de la tabla de ventas) ──

    /** [nombre del cajero (null = sin dato), nº de ventas, total] de las ventas ACTIVAS del rango */
    @Query("SELECT v.cajeroNombre, COUNT(v), COALESCE(SUM(v.total), 0) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)" + FILTRO_CAJA +
           " GROUP BY v.cajeroNombre ORDER BY SUM(v.total) DESC")
    List<Object[]> ventasPorCajero(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** [fecha, total] de cada venta ACTIVA del rango (solo dos columnas: ligero para agrupar por hora) */
    @Query("SELECT v.fecha, v.total FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)" + FILTRO_CAJA)
    List<Object[]> fechasYTotalesActivas(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** [caja, nº de ventas, total] de las ventas ACTIVAS del rango, por caja (las sin caja cuentan como caja 1) */
    @Query("SELECT COALESCE(v.caja, 1), COUNT(v), COALESCE(SUM(v.total), 0) FROM Venta v " +
           "WHERE v.fecha >= :inicio AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false) " +
           "GROUP BY COALESCE(v.caja, 1) ORDER BY COALESCE(v.caja, 1)")
    List<Object[]> ventasPorCaja(LocalDateTime inicio, LocalDateTime fin);

    /** Números de caja que han registrado ventas alguna vez (para los filtros de pantallas) */
    @Query("SELECT DISTINCT COALESCE(v.caja, 1) FROM Venta v ORDER BY COALESCE(v.caja, 1)")
    List<Integer> cajasConVentas();
}
