package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ProductoClima;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductoClimaRepository extends JpaRepository<ProductoClima, Long> {

    @Query("SELECT pc FROM ProductoClima pc JOIN FETCH pc.producto " +
           "WHERE pc.tipo = :tipo AND pc.clave = :clave ORDER BY pc.indice DESC")
    List<ProductoClima> findPorClave(ProductoClima.Tipo tipo, String clave);

    @Query("SELECT pc FROM ProductoClima pc JOIN FETCH pc.producto ORDER BY pc.indice DESC")
    List<ProductoClima> findTodosOrdenados();
}
