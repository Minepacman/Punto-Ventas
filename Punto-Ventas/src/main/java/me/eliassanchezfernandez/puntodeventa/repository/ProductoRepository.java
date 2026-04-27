package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<Producto> findByCantidadActualLessThanEqualAndUsaInventario(
            double minimo, boolean usaInventario);
}
