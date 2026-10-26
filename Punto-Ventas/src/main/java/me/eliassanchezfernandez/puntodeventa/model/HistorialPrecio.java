package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Historial de cambios de precio de un producto (PRD-11) */
@Entity
@Table(name = "historial_precios")
@Getter
@Setter
@NoArgsConstructor
public class HistorialPrecio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    /** VENTA | COSTO | MAYOREO */
    @Column(nullable = false, length = 20)
    private String campo;

    @Column(name = "precio_anterior", nullable = false)
    private double precioAnterior;

    @Column(name = "precio_nuevo", nullable = false)
    private double precioNuevo;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(name = "usuario_nombre", length = 100)
    private String usuarioNombre;

    public HistorialPrecio(Producto producto, String campo, double anterior, double nuevo, Cajero usuario) {
        this.producto = producto;
        this.campo = campo;
        this.precioAnterior = anterior;
        this.precioNuevo = nuevo;
        if (usuario != null) {
            this.usuarioId = usuario.getId();
            this.usuarioNombre = usuario.getNombreCompleto();
        }
    }
}
