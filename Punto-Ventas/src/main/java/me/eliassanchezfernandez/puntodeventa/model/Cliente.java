package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Cliente de la tienda con cuenta de crédito ("fiado").
 *
 * El saldo NUNCA se cambia con un set: lo mueve {@code CuentaClienteService}
 * con un UPDATE atómico ({@code saldo_deuda = saldo_deuda + monto}, con el
 * límite en la misma condición) y en la misma transacción escribe el renglón
 * del libro {@link CuentaClienteMovimiento}. El saldo guardado es la suma de
 * ese libro; existe para poder respetar el límite entre varias cajas a la vez.
 *
 * {@code limiteCredito} 0 = sin límite (la columna ya existía como NOT NULL).
 * Los campos nuevos son nullable para convivir con la tabla existente.
 */
@Entity
@Table(name = "clientes")
@org.hibernate.annotations.DynamicUpdate   // editar nombre/teléfono nunca reescribe el saldo con un valor viejo
@Getter
@Setter
@NoArgsConstructor
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(length = 20)
    private String telefono;

    /** Tope de lo que puede deber; 0 = sin límite */
    @Column(name = "limite_credito")
    private double limiteCredito;

    /** Lo que debe ahora (suma del libro); solo lo cambia CuentaClienteService */
    @Column(name = "saldo_deuda")
    private double saldoDeuda;

    @Column(name = "nota", length = 250)
    private String nota;

    /** null = activo (filas anteriores) */
    @Column(name = "activo")
    private Boolean activo;

    @Column(name = "fecha_alta")
    private LocalDateTime fechaAlta;

    public boolean isActivo() {
        return activo == null || activo;
    }

    public boolean tieneLimite() {
        return limiteCredito > 0.004;
    }

    /** Cuánto más se le puede fiar (null = sin límite); nunca negativo */
    public Double getDisponible() {
        if (!tieneLimite()) return null;
        return Math.max(0, Math.round((limiteCredito - saldoDeuda) * 100.0) / 100.0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cliente c)) return false;
        return id != null && id.equals(c.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return nombre;
    }
}
