package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Venta incluida en una factura (una en la factura individual, varias en la
 * global). Sirve para que una venta no se facture dos veces: mientras la
 * factura esté vigente (o pendiente) ninguna otra factura puede incluirla.
 * Se guarda el id de la venta (sin FK) para no tocar la tabla de ventas.
 */
@Entity
@Table(name = "factura_ventas", indexes = @Index(name = "idx_factura_ventas_venta", columnList = "venta_id"))
@Getter
@Setter
@NoArgsConstructor
public class FacturaVenta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "factura_id")
    private Factura factura;

    @Column(name = "venta_id", nullable = false)
    private Long ventaId;

    /** Folio e importe de la venta al momento de facturar (para listados sin cargar la venta) */
    @Column(name = "venta_folio", length = 30)
    private String ventaFolio;

    @Column(name = "venta_total")
    private double ventaTotal;

    public FacturaVenta(Factura factura, Venta venta) {
        this.factura = factura;
        this.ventaId = venta.getId();
        this.ventaFolio = venta.getFolioMostrado();
        this.ventaTotal = venta.getTotal();
    }
}
