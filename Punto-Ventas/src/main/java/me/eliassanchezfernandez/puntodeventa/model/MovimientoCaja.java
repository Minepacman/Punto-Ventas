package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Entrada o salida de efectivo del cajón que NO es una venta (CRT-08): meter
 * cambio, retirar dinero, pagar a un proveedor o un gasto desde la caja,
 * devolver efectivo por una cancelación. Suma o resta en el efectivo esperado
 * del corte de la caja donde se registró. Nunca se borra: un error se corrige
 * con el movimiento contrario.
 */
@Entity
@Table(name = "movimientos_caja", indexes = {
        @Index(name = "idx_movimientos_caja_fecha", columnList = "fecha"),
        @Index(name = "idx_movimientos_caja_caja_fecha", columnList = "caja, fecha")
})
@Getter
@Setter
@NoArgsConstructor
public class MovimientoCaja {

    public enum Tipo {
        ENTRADA("Entrada"), SALIDA("Salida");

        private final String etiqueta;
        Tipo(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
        public int signo() { return this == ENTRADA ? 1 : -1; }
    }

    /** Para qué fue el movimiento; cada categoría pertenece a un tipo */
    public enum Categoria {
        CAMBIO         (Tipo.ENTRADA, "Entrada de cambio (morralla)"),
        OTRA_ENTRADA   (Tipo.ENTRADA, "Otra entrada de efectivo"),
        RETIRO         (Tipo.SALIDA,  "Retiro de efectivo"),
        PAGO_PROVEEDOR (Tipo.SALIDA,  "Pago a proveedor"),
        GASTO          (Tipo.SALIDA,  "Gasto de la tienda"),
        DEVOLUCION     (Tipo.SALIDA,  "Devolución al cliente"),
        OTRA_SALIDA    (Tipo.SALIDA,  "Otra salida de efectivo"),
        /** Abono en efectivo a la cuenta de un cliente (fiado); solo lo registra CuentaClienteService */
        ABONO_CLIENTE  (Tipo.ENTRADA, "Abono de cliente (fiado)", false);

        private final Tipo tipo;
        private final String etiqueta;
        private final boolean manual;
        Categoria(Tipo tipo, String etiqueta) { this(tipo, etiqueta, true); }
        Categoria(Tipo tipo, String etiqueta, boolean manual) { this.tipo = tipo; this.etiqueta = etiqueta; this.manual = manual; }
        public Tipo getTipo() { return tipo; }
        public String getEtiqueta() { return etiqueta; }
        /** ¿Se puede elegir en "Entrada/Salida de efectivo"? (los abonos se registran desde Clientes) */
        public boolean isManual() { return manual; }

        /** Categorías que se pueden capturar a mano para ese tipo */
        public static java.util.List<Categoria> de(Tipo tipo) {
            return java.util.Arrays.stream(values()).filter(c -> c.tipo == tipo && c.manual).toList();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Tipo tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Categoria categoria;

    /** Siempre positivo; el tipo dice si suma o resta */
    @Column(nullable = false)
    private double monto;

    @Column(nullable = false, length = 250)
    private String concepto;

    @ManyToOne
    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)
    @JoinColumn(name = "cajero_id")
    private Cajero cajero;

    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    /** Administrador que autorizó (cuando el cajero no tenía el permiso) */
    @Column(name = "autorizado_por_id")
    private Long autorizadoPorId;

    @Column(name = "autorizado_por", length = 100)
    private String autorizadoPor;

    /** Número de caja (instalación) donde se registró */
    @Column(name = "caja")
    private Integer caja;

    /** Proveedor pagado (solo PAGO_PROVEEDOR); se guarda el nombre por si el proveedor se borra */
    @Column(name = "proveedor_id")
    private Long proveedorId;

    @Column(name = "proveedor_nombre", length = 150)
    private String proveedorNombre;

    /** Orden de compra pagada (opcional) */
    @Column(name = "orden_compra_id")
    private Long ordenCompraId;

    /** Venta cuya cancelación originó la devolución (opcional) */
    @Column(name = "venta_id")
    private Long ventaId;

    /** Efectivo esperado en la caja justo antes y después del movimiento (CRT-08); null si no se pudo calcular */
    @Column(name = "efectivo_antes")
    private Double efectivoAntes;

    @Column(name = "efectivo_despues")
    private Double efectivoDespues;

    public int getCajaONumero() {
        return caja == null || caja <= 0 ? 1 : caja;
    }

    /** Monto con signo: positivo entra, negativo sale */
    public double getMontoConSigno() {
        return tipo == Tipo.SALIDA ? -monto : monto;
    }

    public boolean esEntrada() { return tipo == Tipo.ENTRADA; }
    public boolean esSalida()  { return tipo == Tipo.SALIDA; }
}
