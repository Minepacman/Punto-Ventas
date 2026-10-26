package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Encabezado de una venta (ticket).
 *
 * Usa @Getter/@Setter en lugar de @Data a propósito: equals/hashCode por
 * identidad evitan la recursión Venta ↔ DetalleVenta y que dos ventas
 * nuevas (id = null) se consideren "iguales".
 *
 * Una venta nunca se borra ni se edita; se CANCELA (VEN-14): queda marcada
 * con motivo, quién la canceló y quién autorizó, y se excluye de los totales.
 * Los campos nuevos son nullable para convivir con las filas ya existentes.
 */
@Entity
@Table(name = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class Venta {

    public static final int PLAZO_CANCELACION_HORAS_POR_DEFECTO = 48;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    /**
     * Forma de pago principal (la de mayor monto). El desglose real está en
     * {@link #pagos} (VEN-07, pago dividido). Se guarda como texto.
     */
    @Column(name = "forma_pago")
    private FormaPago formaPago = FormaPago.EFECTIVO;

    private double total;
    private double pagoCon;
    private double cambio;

    /** Base e impuesto desglosados del total (los precios YA incluyen IVA) */
    @Column(name = "subtotal")
    private Double subtotal;

    @Column(name = "iva")
    private Double iva;

    /** Tasa de IVA vigente al momento de la venta (ej. 0.16) */
    @Column(name = "tasa_iva")
    private Double tasaIva;

    /**
     * Cajero que cobró. Sin constraint de FK en la BD porque SQLite no
     * permite agregarla con ALTER TABLE a la tabla ya existente.
     */
    @ManyToOne
    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)
    @JoinColumn(name = "cajero_id",
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Cajero cajero;

    /** Nombre del cajero al momento de la venta (snapshot para el historial) */
    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    /** Administrador que autorizó algo en esta venta (venta sin existencia, etc.) */
    @Column(name = "autorizada_por_nombre", length = 100)
    private String autorizadaPorNombre;

    // ── Cancelación ──────────────────────────────────────────────────────

    @Column(name = "cancelada")
    private Boolean cancelada;

    @Column(name = "fecha_cancelacion")
    private LocalDateTime fechaCancelacion;

    @Column(name = "motivo_cancelacion", length = 250)
    private String motivoCancelacion;

    @Column(name = "cancelada_por_id")
    private Long canceladaPorId;

    @Column(name = "cancelada_por_nombre", length = 100)
    private String canceladaPorNombre;

    @Column(name = "cancelacion_autorizada_por_id")
    private Long cancelacionAutorizadaPorId;

    @Column(name = "cancelacion_autorizada_por", length = 100)
    private String cancelacionAutorizadaPor;

    /** Plazo de cancelación vigente cuando se hizo la venta (VEN-19) */
    @Column(name = "plazo_cancelacion_horas")
    private Integer plazoCancelacionHoras;

    // ── Renglones y pagos ────────────────────────────────────────────────

    @OneToMany(mappedBy = "venta", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DetalleVenta> detalles = new ArrayList<>();

    @OneToMany(mappedBy = "venta", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PagoVenta> pagos = new ArrayList<>();

    /** Productos que con ESTA venta cruzaron a existencia baja (no se persiste; INV-03) */
    @Transient
    private List<Producto> alertasStockBajo = new ArrayList<>();

    // ── Lógica de dominio ────────────────────────────────────────────────

    public boolean isCancelada() {
        return Boolean.TRUE.equals(cancelada);
    }

    public boolean estaActiva() {
        return !isCancelada();
    }

    public int getPlazoCancelacionHorasOPorDefecto() {
        return plazoCancelacionHoras != null ? plazoCancelacionHoras : PLAZO_CANCELACION_HORAS_POR_DEFECTO;
    }

    /** Hasta cuándo se puede cancelar (fecha de venta + plazo vigente en ese momento) */
    public LocalDateTime getLimiteCancelacion() {
        return fecha.plusHours(getPlazoCancelacionHorasOPorDefecto());
    }

    /** Monto pagado con una forma de pago concreta (suma de los pagos) */
    public double montoPagadoCon(FormaPago forma) {
        return pagos.stream()
                .filter(p -> p.getFormaPago() == forma)
                .mapToDouble(PagoVenta::getMonto)
                .sum();
    }

    public double getSubtotalODefault() {
        return subtotal != null ? subtotal : total;
    }

    public double getIvaODefault() {
        return iva != null ? iva : 0.0;
    }

    public enum FormaPago {
        EFECTIVO("Efectivo"), TARJETA("Tarjeta"), CREDITO("Crédito");

        private final String etiqueta;

        FormaPago(String etiqueta) { this.etiqueta = etiqueta; }

        public String getEtiqueta() { return etiqueta; }
    }

    @Override
    public String toString() {
        return "Venta{id=" + id + ", fecha=" + fecha + ", total=" + total
                + ", cajero=" + cajeroNombre + ", renglones=" + detalles.size()
                + (isCancelada() ? ", CANCELADA" : "") + "}";
    }
}
