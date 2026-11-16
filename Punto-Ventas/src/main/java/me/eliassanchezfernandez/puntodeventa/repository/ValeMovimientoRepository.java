package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ValeMovimiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ValeMovimientoRepository extends JpaRepository<ValeMovimiento, Long> {

    @Query("SELECT m FROM ValeMovimiento m WHERE m.valeId = :valeId ORDER BY m.id ASC")
    List<ValeMovimiento> findDeVale(Long valeId);

    /** Canjes y reversos de una venta (lo que una cancelación debe regresar a cada vale) */
    @Query("SELECT m FROM ValeMovimiento m WHERE m.ventaId = :ventaId AND m.tipo IN ('CANJE', 'CANCELACION_VENTA') ORDER BY m.id ASC")
    List<ValeMovimiento> findUsosDeVenta(Long ventaId);
}
