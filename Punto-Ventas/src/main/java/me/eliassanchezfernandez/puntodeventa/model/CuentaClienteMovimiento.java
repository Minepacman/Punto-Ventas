package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Renglón del libro de la cuenta de un cliente (fiado). Nunca se edita ni se
 * borra: un error se corrige con un AJUSTE o con el movimiento contrario.
 * {@code monto} lleva signo: positivo aumenta lo que debe (cargo por venta),
 * negativo lo disminuye (abono, cancelación, devolución). El saldo de un
 * cliente es la suma de sus renglones; {@code saldoDespues} es el saldo justo
 * después de este renglón (para el estado de cuenta y el ticket).
 */
@Entity
@Table(name = "cuenta_cliente_movimientos", indexes = {
        @Index(name = "idx_cuenta_cliente_cliente_fecha", columnList = "cliente_id, fecha"),
        @Index(name = "idx_cuenta_cliente_venta", columnList = "venta_id"),
        @Index(name = "idx_cuenta_cliente_fecha", columnList = "fecha")
})
@Getter
@Setter
@NoArgsConstructor
public class CuentaClienteMovimiento {

    public enum Tipo {
        CARGO_VENTA("Venta a cuenta"),
        ABONO("Abono"),
        AJUSTE("Ajuste"),
        CANCELACION_VENTA("Venta cancelada"),
        DEVOLUCION("Devolución");

        private final String etiqueta;
        Tipo(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;

    /** Se guarda como texto (sin restricción CHECK) para poder agregar tipos sin reconstruir la tabla */
    @Column(nullable = false, length = 20)
    private String tipo;

    /** Con signo: + aumenta la deuda, − la disminuye */
    @Column(nullable = false)
    private double monto;

    @Column(name = "saldo_despues", nullable = false)
    private double saldoDespues;

    @Column(length = 250)
    private String concepto;

    /** Venta que originó el cargo (o cuya cancelación/devolución lo revierte) */
    @Column(name = "venta_id")
    private Long ventaId;

    @Column(name = "venta_folio", length = 30)
    private String ventaFolio;

    /** Abonos: cómo pagó (EFECTIVO, TARJETA, TRANSFERENCIA) */
    @Column(name = "forma_pago", length = 20)
    private String formaPago;

    /** Abonos en efectivo: entrada de caja que lo cuenta en el corte */
    @Column(name = "movimiento_caja_id")
    private Long movimientoCajaId;

    @Column(name = "cajero_id")
    private Long cajeroId;

    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    @Column(name = "autorizado_por", length = 100)
    private String autorizadoPor;

    @Column(name = "caja")
    private Integer caja;

    public Tipo getTipoEnum() {
        try {
            return tipo == null ? null : Tipo.valueOf(tipo);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String getTipoEtiqueta() {
        Tipo t = getTipoEnum();
        return t != null ? t.getEtiqueta() : String.valueOf(tipo);
    }

    public Venta.FormaPago getFormaPagoEnum() {
        try {
            return formaPago == null ? null : Venta.FormaPago.valueOf(formaPago);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean esCargo() { return monto > 0; }
}
