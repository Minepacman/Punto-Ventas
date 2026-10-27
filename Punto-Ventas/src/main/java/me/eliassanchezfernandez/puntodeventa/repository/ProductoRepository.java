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

    List<Producto> findByDescripcionContainingIgnoreCase(String texto);

    /** Búsqueda libre: coincide por código de barras o por descripción */
    List<Producto> findByCodigoBarrasContainingIgnoreCaseOrDescripcionContainingIgnoreCase(
            String codigo, String descripcion);

    List<Producto> findAllByOrderByDescripcionAsc();

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
