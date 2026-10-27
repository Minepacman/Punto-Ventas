package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UnidadRepository extends JpaRepository<Unidad, Long> {

    Optional<Unidad> findByNombreIgnoreCase(String nombre);

    List<Unidad> findAllByOrderByNombreAsc();
}
