package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Un producto pedido en una orden de compra, con su cantidad y costo unitario. */
@Entity
@Table(name = "ordenes_compra_lineas")
@Getter
@Setter
@NoArgsConstructor
public class OrdenCompraLinea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "orden_id")
    private OrdenCompra orden;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    @Column(nullable = false)
    private double cantidad;

    @Column(name = "costo_unitario", nullable = false)
    private double costoUnitario;

    public double getImporte() {
        return Math.round(cantidad * costoUnitario * 100.0) / 100.0;
    }
}
