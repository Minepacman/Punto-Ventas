package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrdenCompraRepository extends JpaRepository<OrdenCompra, Long> {

    /** Más recientes primero, con proveedor y líneas (para el total; el producto de cada línea se pide al abrir la orden) */
    @Query("SELECT DISTINCT o FROM OrdenCompra o JOIN FETCH o.proveedor LEFT JOIN FETCH o.lineas ORDER BY o.fecha DESC, o.id DESC")
    List<OrdenCompra> findTodasConProveedor();

    @Query("SELECT DISTINCT o FROM OrdenCompra o JOIN FETCH o.proveedor " +
           "LEFT JOIN FETCH o.lineas l LEFT JOIN FETCH l.producto WHERE o.id = :id")
    Optional<OrdenCompra> findConLineasById(Long id);

    boolean existsByProveedorId(Long proveedorId);

    /** ¿Algún producto con ese id está en una orden de compra? (impide eliminarlo) */
    @Query("SELECT COUNT(l) > 0 FROM OrdenCompraLinea l WHERE l.producto.id = :productoId")
    boolean productoEnOrdenes(Long productoId);
}
