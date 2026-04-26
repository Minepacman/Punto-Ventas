package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidad JPA que representa un producto del catálogo.
 * Hibernate crea la tabla "productos" en SQLite automáticamente.
 */
@Entity
@Table(name = "productos")
@Data
@NoArgsConstructor
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo_barras", unique = true, nullable = false, length = 50)
    private String codigoBarras;

    @Column(nullable = false, length = 200)
    private String descripcion;

    /** UNIDAD | GRANEL | PAQUETE */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_venta")
    private TipoVenta tipoVenta = TipoVenta.UNIDAD;

    @Column(name = "precio_costo")
    private double precioCosto;

    @Column(name = "precio_venta")
    private double precioVenta;

    @Column(name = "precio_mayoreo")
    private double precioMayoreo;

    @ManyToOne
    @JoinColumn(name = "departamento_id")
    private Departamento departamento;

    // ── Inventario ───────────────────────────────────────────────────────

    @Column(name = "usa_inventario")
    private boolean usaInventario = true;

    @Column(name = "cantidad_actual")
    private double cantidadActual;

    @Column(name = "cantidad_minima")
    private double cantidadMinima;

    // ── Enum interno ─────────────────────────────────────────────────────

    public enum TipoVenta {
        UNIDAD, GRANEL, PAQUETE
    }
}