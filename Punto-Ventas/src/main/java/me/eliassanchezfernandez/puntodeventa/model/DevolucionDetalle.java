package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Renglón de una {@link Devolucion}: qué renglón de la venta, cuánto se devolvió, cuánto se
 * reembolsó (lo cobrado de verdad por unidad, con descuentos y promociones ya aplicados) y si
 * la mercancía regresó al inventario o se fue a merma (dañada/caducada).
 */
@Entity
@Table(name = "devolucion_detalles", indexes = {
        @Index(name = "idx_devolucion_detalles_detalle", columnList = "detalle_venta_id")
})
@Getter
@Setter
@NoArgsConstructor
public class DevolucionDetalle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "devolucion_id")
    private Devolucion devolucion;

    @Column(name = "detalle_venta_id", nullable = false)
    private Long detalleVentaId;

    @Column(name = "producto_id")
    private Long productoId;

    /** Nombre mostrado al vender (en un producto común, lo tecleado) */
    @Column(length = 150)
    private String descripcion;

    @Column(nullable = false)
    private double cantidad;

    /** Reembolso del renglón con IVA incluido */
    @Column(nullable = false)
    private double importe;

    /** IVA incluido en {@link #importe}, a la tasa guardada en el renglón de la venta */
    @Column(name = "iva")
    private Double iva;

    @Column(name = "tasa_iva")
    private Double tasaIva;

    @Column(name = "iva_exento")
    private Boolean ivaExento;

    /** Costo unitario con que se vendió (para la ganancia del corte) */
    @Column(name = "costo_unitario")
    private Double costoUnitario;

    /** true: regresó al inventario; false: dañado/caducado (merma) o el producto no maneja inventario */
    @Column(name = "regresa_a_inventario", nullable = false)
    private boolean regresaAInventario;

    /** Costo que vuelve al anaquel (cantidad × costo) si la mercancía regresó al inventario */
    public double getCostoRecuperado() {
        return regresaAInventario && costoUnitario != null ? costoUnitario * cantidad : 0.0;
    }
}
