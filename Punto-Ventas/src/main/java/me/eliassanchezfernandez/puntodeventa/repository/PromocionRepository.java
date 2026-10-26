package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PromocionRepository extends JpaRepository<Promocion, Long> {

    /** Promociones activas de un producto (la vigencia por fecha/hora se evalúa en el servicio) */
    List<Promocion> findByProductoIdAndActivaTrue(Long productoId);

    /** Promociones activas de un departamento completo */
    List<Promocion> findByDepartamentoIdAndActivaTrue(Long departamentoId);

    /** Activas que cubren a un producto, por producto o por su departamento, la más reciente primero */
    @Query("SELECT p FROM Promocion p LEFT JOIN p.producto pr LEFT JOIN p.departamento d " +
           "WHERE p.activa = true AND (pr.id = :productoId OR d.id = :departamentoId) " +
           "ORDER BY p.id DESC")
    List<Promocion> findActivasPara(Long productoId, Long departamentoId);

    List<Promocion> findByProductoId(Long productoId);

    boolean existsByDepartamentoId(Long departamentoId);

    List<Promocion> findAllByOrderByActivaDescIdDesc();

    void deleteByProductoId(Long productoId);
}
