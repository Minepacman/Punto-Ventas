package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CajeroRepository extends JpaRepository<Cajero, Long> {

    /** Busca un cajero activo por su nombre de usuario (case-insensitive) */
    Optional<Cajero> findByUsuarioIgnoreCaseAndActivoTrue(String usuario);

    /** Busca por usuario sin importar si está activo (para validar duplicados) */
    Optional<Cajero> findByUsuarioIgnoreCase(String usuario);

    boolean existsByRol(Cajero.Rol rol);

    boolean existsByRolAndActivoTrue(Cajero.Rol rol);

    long countByRolAndActivoTrue(Cajero.Rol rol);

    List<Cajero> findAllByOrderByUsuarioAsc();

    /** ¿Algún administrador activo tiene código de recuperación? (lo consulta el login, sin sesión) */
    boolean existsByRolAndActivoTrueAndRecuperacionHashIsNotNull(Cajero.Rol rol);

    /** Usuarios con código de recuperación vigente (para anularlos todos tras restaurar un respaldo) */
    List<Cajero> findByRecuperacionHashIsNotNull();
}
