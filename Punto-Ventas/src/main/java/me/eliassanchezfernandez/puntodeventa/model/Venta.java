package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Encabezado de una venta (ticket).
 */
@Entity
@Table(name = "ventas")
@Data
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

    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    @OneToMany(mappedBy = "venta", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DetalleVenta> detalles = new ArrayList<>();

    public enum FormaPago { EFECTIVO, TARJETA, CREDITO }
}