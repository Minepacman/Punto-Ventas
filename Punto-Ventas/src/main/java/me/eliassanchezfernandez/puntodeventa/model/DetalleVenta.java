package me.eliassanchezfernandez.puntodeventa.model;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Renglón de un ticket: qué producto, cuánto y a qué precio.
 *
 * Usa @Getter/@Setter (no @Data): los renglones del carrito aún no tienen
 * id, y con equals por campos dos renglones iguales se confundirían en las
 * listas de la UI; además evita la recursión con Venta.
 */
@Entity
@Table(name = "detalle_ventas")
@Getter
@Setter
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

    /** Descuento total del renglón (ya multiplicado por la cantidad) */
    private double descuento;

    public double getImporte() {
        return (precioVenta * cantidad) - descuento;
    }

    @Override
    public String toString() {
        return "DetalleVenta{producto=" + (producto != null ? producto.getCodigoBarras() : null)
                + ", cantidad=" + cantidad + ", precio=" + precioVenta + ", descuento=" + descuento + "}";
    }
}
