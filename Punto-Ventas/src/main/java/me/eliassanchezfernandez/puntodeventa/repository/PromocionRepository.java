package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PromocionRepository extends JpaRepository<Promocion, Long> {

    /** Promociones activas de un producto (la vigencia por fecha se evalúa en el servicio) */
    List<Promocion> findByProductoIdAndActivaTrue(Long productoId);

    List<Promocion> findByProductoId(Long productoId);

    List<Promocion> findAllByOrderByActivaDescFechaInicioDesc();

    void deleteByProductoId(Long productoId);
}
