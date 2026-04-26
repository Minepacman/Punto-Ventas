package me.eliassanchezfernandez.puntodeventa.model;


import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Renglón de un ticket: qué producto, cuánto y a qué precio.
 */
@Entity
@Table(name = "detalle_ventas")
@Data
@NoArgsConstructor
public class DetalleVenta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "venta_id")
    private Venta venta;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    // Snapshot del precio en el momento de la venta
    @Column(name = "precio_venta")
    private double precioVenta;

    private int cantidad;
    private double descuento;

    public double getImporte() {
        return (precioVenta * cantidad) - descuento;
    }
}