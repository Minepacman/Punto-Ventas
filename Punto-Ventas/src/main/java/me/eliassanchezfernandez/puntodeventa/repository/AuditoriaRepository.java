package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {

    List<Auditoria> findTop200ByOrderByFechaDesc();

    /** Entradas en [desde, hasta) de la más reciente a la más vieja; el Pageable pone el tope */
    List<Auditoria> findByFechaGreaterThanEqualAndFechaLessThanOrderByFechaDescIdDesc(
            LocalDateTime desde, LocalDateTime hasta, Pageable pageable);

    List<Auditoria> findByAccionOrderByFechaDesc(String accion);

    boolean existsByUsuarioIdOrAutorizadoPorId(Long usuarioId, Long autorizadoPorId);
}
