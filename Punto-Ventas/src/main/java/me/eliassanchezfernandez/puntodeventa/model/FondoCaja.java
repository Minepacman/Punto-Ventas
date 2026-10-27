package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Fondo de caja capturado por un cajero al iniciar el día en UNA caja (CRT-02; uno por caja y día) */
@Entity
@Table(name = "fondos_caja")
@Getter
@Setter
@NoArgsConstructor
public class FondoCaja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate fecha = LocalDate.now();

    @ManyToOne
    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)
    @JoinColumn(name = "cajero_id")
    private Cajero cajero;

    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    @Column(nullable = false)
    private double monto;

    @Column(name = "registrado_en", nullable = false)
    private LocalDateTime registradoEn = LocalDateTime.now();

    /**
     * Número de caja (instalación) que registró la fila. Null en las filas
     * anteriores a "varias cajas", que cuentan como caja 1.
     */
    @Column(name = "caja")
    private Integer caja;

    public int getCajaONumero() {
        return caja == null || caja <= 0 ? 1 : caja;
    }
}
