package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Bitácora de inventario (INV-07): cada cambio de existencia queda
 * registrado con quién, cuándo, cuánto había y cuánto quedó.
 */
@Entity
@Table(name = "movimientos_inventario")
@Getter
@Setter
@NoArgsConstructor
public class MovimientoInventario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    @Column(nullable = false)
    private Tipo tipo;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    /** Cantidad del movimiento con signo (+ entrada, − salida) */
    @Column(nullable = false)
    private double cantidad;

    @Column(name = "existencia_antes", nullable = false)
    private double existenciaAntes;

    @Column(name = "existencia_despues", nullable = false)
    private double existenciaDespues;

    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(name = "usuario_nombre", length = 100)
    private String usuarioNombre;

    /** Administrador que autorizó el movimiento (si el usuario no tenía permiso) */
    @Column(name = "autorizado_por_id")
    private Long autorizadoPorId;

    @Column(name = "autorizado_por", length = 100)
    private String autorizadoPor;

    /** Motivo del ajuste (merma, robo, caducidad, conteo...) o nota libre */
    @Column(length = 250)
    private String motivo;

    /** Solo entradas: proveedor y costo unitario de compra (INV-04) */
    @Column(length = 150)
    private String proveedor;

    @Column(name = "costo_unitario")
    private Double costoUnitario;

    /** Folio de la venta relacionada (VENTA / CANCELACION) */
    @Column(name = "venta_id")
    private Long ventaId;

    public enum Tipo {
        ENTRADA("Entrada"), AJUSTE("Ajuste"), VENTA("Venta"),
        CANCELACION("Cancelación"), ALTA_INICIAL("Alta inicial");

        private final String etiqueta;

        Tipo(String etiqueta) { this.etiqueta = etiqueta; }

        public String getEtiqueta() { return etiqueta; }
    }

    @Override
    public String toString() {
        return "MovimientoInventario{" + tipo + " " + cantidad + " de "
                + (producto != null ? producto.getCodigoBarras() : null) + "}";
    }
}
