package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Agregado precalculado de "se compran juntos" (canasta de mercado) sobre la
 * ventana móvil de las últimas semanas. Una fila por par ORDENADO (A → B):
 *
 *  - coOcurrencias: tickets que llevaron A y B;
 *  - soporte:   coOcurrencias / tickets de la ventana;
 *  - confianza: coOcurrencias / tickets con A  (P(B | A));
 *  - lift:      confianza / P(B)  (más de 1 = B se vende más de lo normal con A).
 */
@Entity
@Table(name = "pares_productos",
       indexes = @Index(name = "idx_pares_producto_a", columnList = "producto_a_id"))
@Getter
@Setter
@NoArgsConstructor
public class ParProductos {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_a_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Producto productoA;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_b_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Producto productoB;

    @Column(name = "co_ocurrencias")
    private int coOcurrencias;

    private double soporte;
    private double confianza;
    private double lift;

    public ParProductos(Producto a, Producto b, int coOcurrencias, double soporte, double confianza, double lift) {
        this.productoA = a;
        this.productoB = b;
        this.coOcurrencias = coOcurrencias;
        this.soporte = soporte;
        this.confianza = confianza;
        this.lift = lift;
    }
}
