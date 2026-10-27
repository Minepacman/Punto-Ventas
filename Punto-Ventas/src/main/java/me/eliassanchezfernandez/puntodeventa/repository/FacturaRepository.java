package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Factura;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FacturaRepository extends JpaRepository<Factura, Long> {

    @Query("SELECT f FROM Factura f WHERE f.fecha >= :inicio AND f.fecha < :fin ORDER BY f.fecha DESC")
    List<Factura> findEntre(LocalDateTime inicio, LocalDateTime fin);

    /** Facturas que incluyen la venta, en los estados dados (vigente / pendiente / en proceso) */
    @Query("SELECT DISTINCT f FROM Factura f JOIN f.ventas fv WHERE fv.ventaId = :ventaId AND f.estado IN :estados ORDER BY f.id DESC")
    List<Factura> findDeVentaEnEstados(Long ventaId, Collection<String> estados);

    /** Todas las facturas que alguna vez incluyeron la venta (historial) */
    @Query("SELECT DISTINCT f FROM Factura f JOIN f.ventas fv WHERE fv.ventaId = :ventaId ORDER BY f.id DESC")
    List<Factura> findDeVenta(Long ventaId);

    /** Ids de las ventas (de la lista dada) que ya están en una factura vigente, pendiente o en proceso */
    @Query("SELECT fv.ventaId FROM FacturaVenta fv JOIN fv.factura f WHERE fv.ventaId IN :ventaIds AND f.estado IN :estados")
    List<Long> ventasYaFacturadas(Collection<Long> ventaIds, Collection<String> estados);

    /** Relación venta → factura (vigente, pendiente o en proceso) de las ventas dadas, con la factura cargada */
    @Query("SELECT fv FROM FacturaVenta fv JOIN FETCH fv.factura f WHERE fv.ventaId IN :ventaIds AND f.estado IN :estados")
    List<me.eliassanchezfernandez.puntodeventa.model.FacturaVenta> facturasDeVentas(Collection<Long> ventaIds, Collection<String> estados);

    Optional<Factura> findByUuid(String uuid);

    List<Factura> findByEstadoOrderByIdAsc(String estado);

    @Query("SELECT DISTINCT f FROM Factura f LEFT JOIN FETCH f.ventas WHERE f.id = :id")
    Optional<Factura> findConVentasById(Long id);
}
