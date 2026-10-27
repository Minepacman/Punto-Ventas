package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ParProductos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ParProductosRepository extends JpaRepository<ParProductos, Long> {

    /** Pares que salen de los productos dados (A en el carrito → B sugerido) */
    @Query("SELECT p FROM ParProductos p JOIN FETCH p.productoA JOIN FETCH p.productoB " +
           "WHERE p.productoA.id IN :ids ORDER BY p.confianza DESC")
    List<ParProductos> findDesde(Collection<Long> ids);

    /** Todos los pares con sus productos, de mayor a menor co-ocurrencia (pantalla) */
    @Query("SELECT p FROM ParProductos p JOIN FETCH p.productoA JOIN FETCH p.productoB " +
           "ORDER BY p.coOcurrencias DESC, p.lift DESC")
    List<ParProductos> findTodosOrdenados();
}
