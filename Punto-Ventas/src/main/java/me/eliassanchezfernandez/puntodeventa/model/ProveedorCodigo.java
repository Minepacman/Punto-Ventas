package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Lo que la tienda le enseñó al lector de tickets: cómo llama ESTE proveedor a un producto de la tienda.
 *
 * Se aprende al registrar un ticket (el usuario confirma o elige el producto) y la siguiente vez el
 * renglón se reconoce solo. La {@code clave} es "C:" + código del proveedor cuando el ticket lo trae
 * ("C:300062064") o "T:" + la descripción sin espacios ni signos cuando no ("T:MZCHOCOGT").
 * {@code piezasPorUnidad}: cuántas piezas de la tienda trae una unidad del ticket ("8/2L" a $238 = 8 botellas;
 * "PAQUETE ... 24PZS" = 24 latas). Se pregunta una vez y se recuerda.
 */
@Entity
@Table(name = "proveedor_codigos",
       uniqueConstraints = @UniqueConstraint(columnNames = {"proveedor_id", "clave"}),
       indexes = @Index(name = "idx_proveedor_codigos_producto", columnList = "producto_id"))
@Getter
@Setter
@NoArgsConstructor
public class ProveedorCodigo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "proveedor_id", nullable = false)
    private Proveedor proveedor;

    @Column(nullable = false, length = 140)
    private String clave;

    @Column(name = "codigo_proveedor", length = 40)
    private String codigoProveedor;

    @Column(name = "descripcion_proveedor", length = 150)
    private String descripcionProveedor;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id", nullable = false)
    private Producto producto;

    /** Piezas de la tienda por cada unidad del ticket (1 = pieza suelta) */
    @Column(name = "piezas_por_unidad", nullable = false)
    private int piezasPorUnidad = 1;

    /** Último costo por pieza que se registró con este renglón */
    @Column(name = "ultimo_costo")
    private Double ultimoCosto;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    /** Cuántas veces se ha confirmado (sube con cada ticket) */
    @Column(nullable = false)
    private int veces = 1;

    public static String claveDe(String codigoProveedor, String descripcion) {
        if (codigoProveedor != null && !codigoProveedor.isBlank()) return "C:" + codigoProveedor.trim().toUpperCase();
        String t = me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TextoTicket.clave(descripcion);
        return "T:" + (t.length() > 130 ? t.substring(0, 130) : t);
    }
}
