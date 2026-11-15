package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Devolución de algunos artículos de una venta cobrada (devolución parcial). La venta
 * sigue activa y cuenta en el corte del día en que se vendió; la devolución cuenta en el
 * corte de la caja y el turno en que se hizo ("Devoluciones: −$X") y el reembolso en
 * efectivo es una salida de caja ({@link MovimientoCaja.Categoria#DEVOLUCION}). Nunca se
 * borra ni se edita. La arma {@code DevolucionService}.
 */
@Entity
@Table(name = "devoluciones", indexes = {
        @Index(name = "idx_devoluciones_fecha", columnList = "fecha"),
        @Index(name = "idx_devoluciones_venta", columnList = "venta_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Devolucion {

    /** Cómo se le regresó el dinero al cliente. Por ahora solo efectivo (vales / notas de crédito: pendiente) */
    public enum FormaReembolso {
        EFECTIVO("Efectivo");

        private final String etiqueta;
        FormaReembolso(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    /** Venta de la que salen los artículos (sin FK: SQLite no la agrega a tablas existentes) */
    @Column(name = "venta_id", nullable = false)
    private Long ventaId;

    /** Folio de la venta tal como se mostró (snapshot para el comprobante) */
    @Column(name = "venta_folio", length = 30)
    private String ventaFolio;

    @Column(nullable = false, length = 250)
    private String motivo;

    /** Lo reembolsado (con IVA): suma de los renglones */
    @Column(nullable = false)
    private double total;

    /** IVA incluido en lo reembolsado (cada renglón a la tasa con que se vendió) */
    @Column(name = "iva")
    private Double iva;

    @Enumerated(EnumType.STRING)
    @Column(name = "forma_reembolso", nullable = false, length = 20)
    private FormaReembolso formaReembolso = FormaReembolso.EFECTIVO;

    /** Salida de efectivo que registró el reembolso (null si el total fue $0) */
    @Column(name = "movimiento_caja_id")
    private Long movimientoCajaId;

    @Column(name = "cajero_id")
    private Long cajeroId;

    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    /** Administrador que autorizó (cuando quien la hizo no tenía DEVOLVER_VENTAS) */
    @Column(name = "autorizado_por_id")
    private Long autorizadoPorId;

    @Column(name = "autorizado_por", length = 100)
    private String autorizadoPor;

    /** Caja (instalación) donde se hizo y de cuyo cajón salió el dinero */
    @Column(name = "caja")
    private Integer caja;

    @OneToMany(mappedBy = "devolucion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<DevolucionDetalle> detalles = new ArrayList<>();

    public int getCajaONumero() {
        return caja == null || caja <= 0 ? 1 : caja;
    }

    public double getIvaOCero() {
        return iva != null ? iva : 0.0;
    }

    /** Folio del comprobante: "D-" + id */
    public String getFolio() {
        return "D-" + id;
    }

    @Override
    public String toString() {
        return "Devolucion{id=" + id + ", venta=" + ventaId + ", total=" + total + ", renglones=" + detalles.size() + "}";
    }
}
