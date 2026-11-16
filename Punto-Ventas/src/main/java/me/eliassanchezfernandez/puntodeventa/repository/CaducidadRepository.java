package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Caducidad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CaducidadRepository extends JpaRepository<Caducidad, Long> {

    /** Entradas vigentes (no retiradas) de productos que hoy controlan caducidad, activos y con inventario */
    @Query("SELECT c FROM Caducidad c WHERE c.retiradaEn IS NULL AND c.productoId IN "
            + "(SELECT p.id FROM Producto p WHERE p.controlaCaducidad = true AND p.usaInventario = true "
            + "AND (p.activo IS NULL OR p.activo = true))")
    List<Caducidad> vigentesDeProductosControlados();

    @Query("SELECT c FROM Caducidad c WHERE c.retiradaEn IS NULL AND c.productoId = :productoId")
    List<Caducidad> vigentesDe(@Param("productoId") Long productoId);
}
