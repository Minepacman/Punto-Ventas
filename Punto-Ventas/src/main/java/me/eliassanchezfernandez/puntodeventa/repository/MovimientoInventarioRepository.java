package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MovimientoInventarioRepository extends JpaRepository<MovimientoInventario, Long> {

    /** Más reciente primero; el id desempata movimientos del mismo instante */
    @Query("SELECT m FROM MovimientoInventario m WHERE m.producto.id = :productoId ORDER BY m.fecha DESC, m.id DESC")
    List<MovimientoInventario> findByProductoIdOrderByFechaDesc(Long productoId);

    boolean existsByProductoId(Long productoId);

    boolean existsByUsuarioId(Long usuarioId);

    @Query("SELECT m FROM MovimientoInventario m JOIN FETCH m.producto " +
           "WHERE m.fecha >= :inicio AND m.fecha < :fin ORDER BY m.fecha DESC, m.id DESC")
    List<MovimientoInventario> findEntre(LocalDateTime inicio, LocalDateTime fin);
}
