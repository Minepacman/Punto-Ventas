package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Promoción aplicable a un producto durante un rango de fechas.
 *
 * Dos tipos:
 *  - PORCENTAJE  → valor = % de descuento sobre el precio de venta (ej. 10 = 10 %)
 *  - PRECIO_FIJO → valor = precio unitario promocional (ej. 25.00)
 *
 * Una promoción solo se aplica si está activa y la fecha de hoy
 * está dentro de [fechaInicio, fechaFin]. fechaFin = null → sin vencimiento.
 */
@Entity
@Table(name = "promociones")
@Data
@NoArgsConstructor
public class Promocion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    /** Texto corto para identificarla en la UI (ej. "Oferta de fin de semana") */
    @Column(length = 150)
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tipo tipo = Tipo.PORCENTAJE;

    /** Porcentaje (0-100) o precio fijo unitario, según el tipo */
    @Column(nullable = false)
    private double valor;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio = LocalDate.now();

    /** null = la promoción no vence */
    @Column(name = "fecha_fin")
    private LocalDate fechaFin;

    @Column(nullable = false)
    private boolean activa = true;

    // ── Lógica de dominio ────────────────────────────────────────────────

    /** true si la promoción está activa y la fecha cae dentro de su vigencia */
    public boolean estaVigente(LocalDate fecha) {
        if (!activa || fecha == null) return false;
        if (fechaInicio != null && fecha.isBefore(fechaInicio)) return false;
        if (fechaFin != null && fecha.isAfter(fechaFin)) return false;
        return true;
    }

    /**
     * Precio unitario resultante de aplicar la promoción a un precio base.
     * Nunca regresa un precio mayor al base ni menor a cero.
     */
    public double precioConPromocion(double precioBase) {
        double precio = switch (tipo) {
            case PORCENTAJE  -> precioBase * (1 - valor / 100.0);
            case PRECIO_FIJO -> valor;
        };
        return Math.max(0, Math.min(precioBase, precio));
    }

    // ── Enum interno ─────────────────────────────────────────────────────

    public enum Tipo {
        PORCENTAJE, PRECIO_FIJO
    }
}
