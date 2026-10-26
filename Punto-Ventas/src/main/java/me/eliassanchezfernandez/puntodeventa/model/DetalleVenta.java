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
 *
 * Descuentos (PRO-09): en un renglón aplica SOLO UNO de descuento manual,
 * mayoreo o promoción, con esa prioridad. VentaService.recalcularPrecio()
 * decide cuál y deja el resultado en precioVenta / descuento / tipoDescuento.
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

    /** Precio unitario cobrado como base (lista o mayoreo) */
    @Column(name = "precio_venta")
    private double precioVenta;

    /** Precio de lista del producto al momento de la venta (para mostrar el ahorro) */
    @Column(name = "precio_lista")
    private Double precioLista;

    /** Costo unitario al momento de la venta (para calcular la ganancia real) */
    @Column(name = "costo_unitario")
    private Double costoUnitario;

    /** Cantidad; decimal para productos a granel (PRD-08) */
    private double cantidad;

    /** Descuento total del renglón (ya multiplicado por la cantidad) */
    private double descuento;

    /** Qué originó el descuento del renglón */
    @Column(name = "tipo_descuento")
    private TipoDescuento tipoDescuento = TipoDescuento.NINGUNO;

    /** El cajero activó el precio de mayoreo en este renglón */
    @Column(name = "mayoreo")
    private Boolean mayoreo;

    /** Descuento manual capturado como porcentaje (0-100); null = ninguno */
    @Column(name = "descuento_manual_pct")
    private Double descuentoManualPct;

    /** Descuento manual capturado como importe fijo del renglón; null = ninguno */
    @Column(name = "descuento_manual_importe")
    private Double descuentoManualImporte;

    /** Promoción aplicada (id y nombre guardados para el ticket, PRO-10) */
    @Column(name = "promocion_id")
    private Long promocionId;

    @Column(name = "promocion_nombre", length = 150)
    private String promocionNombre;

    /** Piezas de este renglón que entraron a la promoción (límites por ticket / hasta agotar) */
    @Column(name = "piezas_promocion")
    private Double piezasPromocion;

    /** Administrador que autorizó este renglón (mayoreo, descuento manual, bajo costo o sin existencia) */
    @Column(name = "autorizado_por_id")
    private Long autorizadoPorId;

    @Column(name = "autorizado_por_usuario", length = 60)
    private String autorizadoPorUsuario;

    // ── Lógica de dominio ────────────────────────────────────────────────

    public double getImporte() {
        return (precioVenta * cantidad) - descuento;
    }

    public boolean isMayoreo() {
        return Boolean.TRUE.equals(mayoreo);
    }

    public boolean tieneDescuentoManual() {
        return (descuentoManualPct != null && descuentoManualPct > 0)
            || (descuentoManualImporte != null && descuentoManualImporte > 0);
    }

    /** Quita cualquier descuento manual del renglón */
    public void limpiarDescuentoManual() {
        descuentoManualPct = null;
        descuentoManualImporte = null;
    }

    public double getPrecioListaODefault() {
        return precioLista != null ? precioLista : precioVenta;
    }

    public double getCostoUnitarioODefault() {
        if (costoUnitario != null) return costoUnitario;
        return producto != null ? producto.getPrecioCosto() : 0;
    }

    public enum TipoDescuento {
        NINGUNO("—"), PROMOCION("Promoción"), MAYOREO("Mayoreo"), MANUAL("Manual");

        private final String etiqueta;

        TipoDescuento(String etiqueta) { this.etiqueta = etiqueta; }

        public String getEtiqueta() { return etiqueta; }
    }

    @Override
    public String toString() {
        return "DetalleVenta{producto=" + (producto != null ? producto.getCodigoBarras() : null)
                + ", cantidad=" + cantidad + ", precio=" + precioVenta + ", descuento=" + descuento
                + ", tipo=" + tipoDescuento + "}";
    }
}
