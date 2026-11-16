package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
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

    // ── Cambios de estado condicionados ───────────────────────────────────
    // Con varias cajas, dos pueden abrir la misma orden en el mismo estado y recibirla (o cancelarla) a la
    // vez. El cambio de estado se hace EN la base y condicionado al estado de partida: solo una caja
    // afecta la fila; la otra recibe 0 y no debe tocar el inventario. En PostgreSQL el UPDATE bloquea la
    // fila hasta terminar la transacción, así la segunda caja espera a la primera y luego ve el estado real.

    /** Pasa la orden a {@code nuevo} solo si está en alguno de los estados {@code desde}. @return filas afectadas (1 o 0) */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE OrdenCompra o SET o.estado = :nuevo WHERE o.id = :id AND o.estado IN :desde")
    int cambiarEstado(Long id, OrdenCompra.Estado nuevo, Collection<OrdenCompra.Estado> desde);

    /** Marca la orden como recibida (con fecha y quién) solo si está en alguno de los estados {@code desde} */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE OrdenCompra o SET o.estado = :recibida, o.fechaRecepcion = :fecha, o.recibidaPor = :quien " +
           "WHERE o.id = :id AND o.estado IN :desde")
    int marcarRecibida(Long id, OrdenCompra.Estado recibida, LocalDateTime fecha, String quien,
                       Collection<OrdenCompra.Estado> desde);
}
