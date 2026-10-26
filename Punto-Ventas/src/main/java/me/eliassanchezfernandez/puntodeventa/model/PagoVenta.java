package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una parte del pago de una venta (VEN-07, pago dividido):
 * por ejemplo $150 en efectivo + $100 con tarjeta.
 */
@Entity
@Table(name = "pagos_venta")
@Getter
@Setter
@NoArgsConstructor
public class PagoVenta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "venta_id")
    private Venta venta;

    @Column(name = "forma_pago", nullable = false)
    private Venta.FormaPago formaPago;

    @Column(nullable = false)
    private double monto;

    public PagoVenta(Venta.FormaPago formaPago, double monto) {
        this.formaPago = formaPago;
        this.monto = monto;
    }

    @Override
    public String toString() {
        return "PagoVenta{" + formaPago + " $" + monto + "}";
    }
}
