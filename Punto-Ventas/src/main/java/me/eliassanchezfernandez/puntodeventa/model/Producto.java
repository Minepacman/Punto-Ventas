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

    /**
     * Unidad de medida del catálogo (Configuración). Nullable: los productos
     * anteriores no tienen unidad y siguen guiándose por tipoVenta.
     */
    @ManyToOne
    @JoinColumn(name = "unidad_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Unidad unidad;

    /**
     * Producto descontinuado = inactivo: no aparece en Ventas (PRD-09).
     * Nullable para las filas anteriores a este campo (null = activo).
     */
    @Column(name = "activo")
    private Boolean activo;

    // ── Datos fiscales (CFDI 4.0) ─────────────────────────────────────────
    // Todos opcionales: si están vacíos, al facturar se usa la clave por defecto
    // del departamento (c_ClaveProdServ) y la de la unidad de medida (c_ClaveUnidad).

    /** Clave de producto o servicio del SAT (c_ClaveProdServ, 8 dígitos) */
    @Column(name = "clave_prod_serv", length = 8)
    private String claveProdServ;

    /** Clave de unidad del SAT (c_ClaveUnidad, ej. H87, KGM) */
    @Column(name = "clave_unidad", length = 3)
    private String claveUnidad;

    /** Objeto de impuesto (c_ObjetoImp): 01 no objeto, 02 sí objeto (por defecto), 03 sí objeto sin desglose */
    @Column(name = "objeto_imp", length = 2)
    private String objetoImp;

    // ── Inventario ───────────────────────────────────────────────────────

    @Column(name = "usa_inventario")
    private boolean usaInventario = true;

    @Column(name = "cantidad_actual")
    private double cantidadActual;

    @Column(name = "cantidad_minima")
    private double cantidadMinima;

    // ── Lógica de dominio ────────────────────────────────────────────────

    public boolean isActivo() {
        return activo == null || activo;
    }

    /**
     * Cantidades decimales (PRD-08): las dicta la unidad de medida si tiene;
     * si no, el tipo de venta (los productos a granel).
     */
    public boolean permiteDecimales() {
        return unidad != null ? unidad.isPermiteDecimales() : tipoVenta == TipoVenta.GRANEL;
    }

    // ── Enum interno ─────────────────────────────────────────────────────

    public enum TipoVenta {
        UNIDAD, GRANEL, PAQUETE
    }
}
