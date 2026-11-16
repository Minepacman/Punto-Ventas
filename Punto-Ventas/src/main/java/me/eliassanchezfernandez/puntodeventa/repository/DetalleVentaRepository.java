package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DetalleVentaRepository extends JpaRepository<DetalleVenta, Long> {

    /** ¿El producto aparece en alguna venta? (si sí, no debe borrarse) */
    boolean existsByProductoId(Long productoId);

    /**
     * Suma una devolución al renglón SOLO si no rebasa lo vendido (UPDATE condicionado, con
     * medio milésimo de tolerancia para granel): aunque dos cajas devuelvan el mismo renglón a la
     * vez, entre las dos nunca devuelven más de lo que se vendió.
     * @return 1 si se anotó, 0 si ya no alcanzaba (o el renglón no es de esa venta)
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "UPDATE DetalleVenta d SET d.cantidadDevuelta = COALESCE(d.cantidadDevuelta, 0.0) + :cantidad, " +
            "d.importeDevuelto = COALESCE(d.importeDevuelto, 0.0) + :importe " +
            "WHERE d.id = :id AND d.venta.id = :ventaId " +
            "AND COALESCE(d.cantidadDevuelta, 0.0) + :cantidad <= d.cantidad + 0.0005")
    int devolverSiAlcanza(Long id, Long ventaId, double cantidad, double importe);
}
