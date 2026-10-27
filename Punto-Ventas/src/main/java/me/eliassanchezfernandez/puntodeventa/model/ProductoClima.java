package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Agregado precalculado: cuánto se vende cada producto bajo cada condición
 * climática (Lluvia, Despejado…) o banda de temperatura (Frío, Templado,
 * Calor) en la ventana móvil, y su índice relativo:
 *
 *   índice = participación del producto con ese clima / participación general.
 *
 * Un índice de 1.8 significa que con ese clima el producto pesa 80 % más de
 * lo normal en las ventas. Solo hay filas cuando se registró el clima de la
 * hora de la venta (clima_registro).
 */
@Entity
@Table(name = "producto_clima",
       indexes = @Index(name = "idx_producto_clima_clave", columnList = "tipo, clave"))
@Getter
@Setter
@NoArgsConstructor
public class ProductoClima {

    /** CONDICION = categoría del tiempo; BANDA = banda de temperatura */
    public enum Tipo { CONDICION, BANDA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Producto producto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Tipo tipo;

    /** "Lluvia", "Calor", ... (ver ClimaService) */
    @Column(nullable = false, length = 30)
    private String clave;

    private double piezas;
    private double indice;

    public ProductoClima(Producto producto, Tipo tipo, String clave, double piezas, double indice) {
        this.producto = producto;
        this.tipo = tipo;
        this.clave = clave;
        this.piezas = piezas;
        this.indice = indice;
    }
}
