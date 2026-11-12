package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MovimientoInventarioRepository extends JpaRepository<MovimientoInventario, Long> {

    /** Más reciente primero; el id desempata movimientos del mismo instante */
    @Query("SELECT m FROM MovimientoInventario m WHERE m.producto.id = :productoId ORDER BY m.fecha DESC, m.id DESC")
    List<MovimientoInventario> findByProductoIdOrderByFechaDesc(Long productoId);

    boolean existsByProductoId(Long productoId);

    boolean existsByUsuarioId(Long usuarioId);

    @Query("SELECT m FROM MovimientoInventario m JOIN FETCH m.producto " +
           "WHERE m.fecha >= :inicio AND m.fecha < :fin ORDER BY m.fecha DESC, m.id DESC")
    List<MovimientoInventario> findEntre(LocalDateTime inicio, LocalDateTime fin);

    // ── Proveedores ──────────────────────────────────────────────────────

    /** Textos de proveedor distintos (tal como se escribieron) de las entradas de mercancía */
    @Query("SELECT DISTINCT m.proveedor FROM MovimientoInventario m " +
           "WHERE m.tipo = :tipo AND m.proveedor IS NOT NULL AND TRIM(m.proveedor) <> ''")
    List<String> proveedoresEscritos(MovimientoInventario.Tipo tipo);

    /** Liga a un proveedor las entradas antiguas que tenían ese texto exacto (no cambia el texto) */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MovimientoInventario m SET m.proveedorId = :proveedorId " +
           "WHERE m.tipo = :tipo AND m.proveedorId IS NULL AND m.proveedor = :texto")
    int ligarProveedor(Long proveedorId, String texto, MovimientoInventario.Tipo tipo);

    /** Productos que este proveedor ya surtió (por id, o por nombre en las entradas anteriores a los proveedores) */
    @Query("SELECT DISTINCT m.producto FROM MovimientoInventario m " +
           "WHERE m.tipo = :tipo AND (m.proveedorId = :proveedorId " +
           "OR (m.proveedorId IS NULL AND LOWER(TRIM(m.proveedor)) = :nombreMinusculas))")
    List<me.eliassanchezfernandez.puntodeventa.model.Producto> productosSurtidosPor(
            Long proveedorId, String nombreMinusculas, MovimientoInventario.Tipo tipo);

    boolean existsByProveedorId(Long proveedorId);

    /**
     * Todas las entradas con proveedor, de la más reciente a la más antigua: [productoId,
     * proveedorId (puede ser null), proveedor (texto)]. Quien la lee se queda con la primera
     * fila de cada producto cuyo proveedor exista en el catálogo (una entrada "No indicado
     * (móvil)" no debe quitarle al producto su proveedor real).
     */
    @Query("SELECT m.producto.id, m.proveedorId, m.proveedor FROM MovimientoInventario m " +
           "WHERE m.tipo = :tipo AND (m.proveedorId IS NOT NULL OR (m.proveedor IS NOT NULL AND TRIM(m.proveedor) <> '')) " +
           "ORDER BY m.producto.id, m.id DESC")
    List<Object[]> entradasConProveedorRecientesPrimero(MovimientoInventario.Tipo tipo);

    /** Primer movimiento de cada producto [productoId, fecha]: desde cuándo existe en el inventario */
    @Query("SELECT m.producto.id, MIN(m.fecha) FROM MovimientoInventario m GROUP BY m.producto.id")
    List<Object[]> primerMovimientoPorProducto();

    /**
     * La ÚLTIMA entrada con proveedor de cada producto: [productoId, proveedorId (puede ser null),
     * proveedor (texto)]. Una fila por producto; sirve para saber quién surte cada producto.
     */
    @Query("SELECT m.producto.id, m.proveedorId, m.proveedor FROM MovimientoInventario m " +
           "WHERE m.id IN (SELECT MAX(m2.id) FROM MovimientoInventario m2 WHERE m2.tipo = :tipo " +
           "AND (m2.proveedorId IS NOT NULL OR (m2.proveedor IS NOT NULL AND TRIM(m2.proveedor) <> '')) " +
           "GROUP BY m2.producto.id)")
    List<Object[]> ultimoProveedorPorProducto(MovimientoInventario.Tipo tipo);
}
