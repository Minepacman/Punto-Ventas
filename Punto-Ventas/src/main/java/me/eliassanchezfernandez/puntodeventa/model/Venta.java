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
 */
@Entity
@Table(name = "ventas")
@Getter
@Setter
@NoArgsConstructor
public class Venta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    /** EFECTIVO | TARJETA | CREDITO */
    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pago")
    private FormaPago formaPago = FormaPago.EFECTIVO;

    private double total;
    private double pagoCon;
    private double cambio;

    /**
     * Cajero que cobró. Sin constraint de FK en la BD porque SQLite no
     * permite agregarla con ALTER TABLE a la tabla ya existente.
     */
    @ManyToOne
    @JoinColumn(name = "cajero_id",
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Cajero cajero;

    /** Nombre del cajero al momento de la venta (snapshot para el historial) */
    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    @OneToMany(mappedBy = "venta", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DetalleVenta> detalles = new ArrayList<>();

    public enum FormaPago { EFECTIVO, TARJETA, CREDITO }

    @Override
    public String toString() {
        return "Venta{id=" + id + ", fecha=" + fecha + ", total=" + total
                + ", cajero=" + cajeroNombre + ", renglones=" + detalles.size() + "}";
    }
}
