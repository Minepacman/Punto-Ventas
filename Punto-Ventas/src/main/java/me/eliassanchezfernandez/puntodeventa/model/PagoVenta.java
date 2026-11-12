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

    // ── Datos del voucher de tarjeta (captura manual; todos nullable) ─────

    /** Nombre de la terminal bancaria con que se cobró (solo pagos con tarjeta) */
    @Column(name = "terminal_nombre", length = 60)
    private String terminalNombre;

    /** Últimos 4 dígitos de la tarjeta (nunca el número completo) */
    @Column(name = "tarjeta_ultimos4", length = 4)
    private String tarjetaUltimos4;

    /** Número de autorización del voucher */
    @Column(name = "autorizacion", length = 20)
    private String autorizacion;

    public PagoVenta(Venta.FormaPago formaPago, double monto) {
        this.formaPago = formaPago;
        this.monto = monto;
    }

    public PagoVenta(Venta.FormaPago formaPago, double monto, String terminalNombre,
                     String tarjetaUltimos4, String autorizacion) {
        this(formaPago, monto);
        this.terminalNombre = terminalNombre;
        this.tarjetaUltimos4 = tarjetaUltimos4;
        this.autorizacion = autorizacion;
    }

    public boolean tieneDatosTarjeta() {
        return terminalNombre != null || tarjetaUltimos4 != null || autorizacion != null;
    }

    /** "Terminal 1 ····1234 aut. 123456" (lo que se muestra en el historial y el ticket) */
    public String resumenTarjeta() {
        if (!tieneDatosTarjeta()) return "";
        StringBuilder sb = new StringBuilder();
        if (terminalNombre != null) sb.append(terminalNombre);
        if (tarjetaUltimos4 != null) sb.append(sb.length() > 0 ? " " : "").append("****").append(tarjetaUltimos4);
        if (autorizacion != null) sb.append(sb.length() > 0 ? " " : "").append("aut. ").append(autorizacion);
        return sb.toString();
    }

    @Override
    public String toString() {
        return "PagoVenta{" + formaPago + " $" + monto + "}";
    }
}
