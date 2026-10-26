package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {

    List<Auditoria> findTop200ByOrderByFechaDesc();

    List<Auditoria> findByAccionOrderByFechaDesc(String accion);

    boolean existsByUsuarioIdOrAutorizadoPorId(Long usuarioId, Long autorizadoPorId);
}
