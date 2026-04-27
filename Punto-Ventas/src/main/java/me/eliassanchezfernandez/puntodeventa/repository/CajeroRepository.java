package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CajeroRepository extends JpaRepository<Cajero, Long> {

    /** Busca un cajero activo por su nombre de usuario (case-insensitive) */
    Optional<Cajero> findByUsuarioIgnoreCaseAndActivoTrue(String usuario);
}