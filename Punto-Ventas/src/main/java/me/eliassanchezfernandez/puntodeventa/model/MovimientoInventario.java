package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Registra cada cambio de stock: entradas manuales, ventas, ajustes e importaciones.
 */

@Entity
@Table(name = "movimientos_inventario")
@Data
@NoArgsConstructor
public class MovimientoInventario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    /** ENTRADA | SALIDA_VENTA | AJUSTE | IMPORTACION */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoMovimiento tipo;

    @Column(nullable = false)
    private double cantidad;

    @Column(name = "cantidad_anterior")
    private double cantidadAnterior;

    @Column(name = "cantidad_nueva")
    private double cantidadNueva;

    @Column(name = "usuario", length = 100)
    private String usuario;

    @Column(length = 255)
    private String nota;

    public enum TipoMovimiento {
        ENTRADA, SALIDA_VENTA, AJUSTE, IMPORTACION
    }
}