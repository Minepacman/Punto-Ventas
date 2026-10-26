package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
