package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA genera la implementación SQL automáticamente.
 * No necesitas escribir ninguna consulta para los métodos básicos.
 */
@Repository
public interface ProductoRepository extends JpaRepository<Producto, Long> {

    Optional<Producto> findByCodigoBarras(String codigoBarras);

    // Las consultas de catálogo excluyen el producto del sistema "Producto común" (model/ProductoComun):
    // existe solo para que los renglones tecleados en Ventas tengan producto; no se lista, edita ni vende solo.
    String SIN_PRODUCTO_COMUN = " p.codigoBarras <> '" + me.eliassanchezfernandez.puntodeventa.model.ProductoComun.CODIGO + "' ";

    @Query("SELECT p FROM Producto p WHERE LOWER(p.descripcion) LIKE LOWER(CONCAT('%', :texto, '%')) AND" + SIN_PRODUCTO_COMUN)
    List<Producto> findByDescripcionContainingIgnoreCase(String texto);

    /** Búsqueda libre: coincide por código de barras o por descripción */
    @Query("SELECT p FROM Producto p WHERE (LOWER(p.codigoBarras) LIKE LOWER(CONCAT('%', :codigo, '%')) " +
           "OR LOWER(p.descripcion) LIKE LOWER(CONCAT('%', :descripcion, '%'))) AND" + SIN_PRODUCTO_COMUN)
    List<Producto> findByCodigoBarrasContainingIgnoreCaseOrDescripcionContainingIgnoreCase(
            String codigo, String descripcion);

    @Query("SELECT p FROM Producto p WHERE" + SIN_PRODUCTO_COMUN + "ORDER BY p.descripcion ASC")
    List<Producto> findAllByOrderByDescripcionAsc();

    /** Productos del catálogo (sin el producto del sistema), para saber si la tienda ya tiene alguno */
    @Query("SELECT COUNT(p) FROM Producto p WHERE" + SIN_PRODUCTO_COMUN)
    long contarCatalogo();

    /** Productos con inventario cuya existencia ya llegó (o bajó) al mínimo */
    @Query("SELECT p FROM Producto p WHERE p.usaInventario = true " +
           "AND p.cantidadActual <= p.cantidadMinima ORDER BY p.descripcion")
    List<Producto> findConStockBajo();

    List<Producto> findByUsaInventarioTrueOrderByDescripcionAsc();

    long countByDepartamentoId(Long departamentoId);

    // ── Existencia: UPDATE atómicos ──────────────────────────────────────
    // Con varias cajas contra un servidor, dos cajas pueden vender el mismo producto al mismo tiempo.
    // "Leer, restar en memoria y guardar" perdería una de las dos ventas; restar EN la base de datos
    // (y condicionar a que alcance) es seguro en PostgreSQL y en SQLite. El llamador vuelve a leer la
    // fila (refresh) para conocer la existencia resultante.

    /** Resta si alcanza (tolerancia de milésimas por los decimales a granel). @return 1 si restó, 0 si no alcanzaba */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Producto p SET p.cantidadActual = p.cantidadActual - :cantidad " +
           "WHERE p.id = :id AND p.cantidadActual >= :cantidad - 0.000001")
    int descontarSiAlcanza(Long id, double cantidad);

    /** Resta aunque quede negativo (venta sin existencia autorizada) */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Producto p SET p.cantidadActual = p.cantidadActual - :cantidad WHERE p.id = :id")
    int descontar(Long id, double cantidad);

    /** Suma (entradas, cancelaciones) */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Producto p SET p.cantidadActual = p.cantidadActual + :cantidad WHERE p.id = :id")
    int sumar(Long id, double cantidad);

    /**
     * Deja la existencia redondeada a milésimas SOLO si nadie la cambió desde que
     * se leyó (comparar-e-intercambiar): quita residuos de punto flotante sin
     * pisar la venta de otra caja. @return 1 si se ajustó
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Producto p SET p.cantidadActual = :redondeada WHERE p.id = :id AND p.cantidadActual = :leida")
    int fijarSiNoCambio(Long id, double leida, double redondeada);

    long countByUnidadId(Long unidadId);
}
