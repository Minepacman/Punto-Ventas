package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Corte de caja guardado (CRT-06): efectivo esperado contra efectivo contado,
 * con faltante/sobrante, quién lo hizo y cuándo. Puede haber varios al día.
 */
@Entity
@Table(name = "cortes")
@Getter
@Setter
@NoArgsConstructor
public class Corte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Día al que corresponde el corte */
    @Column(nullable = false)
    private LocalDate fecha = LocalDate.now();

    @Column(name = "realizado_en", nullable = false)
    private LocalDateTime realizadoEn = LocalDateTime.now();

    @ManyToOne
    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)
    @JoinColumn(name = "cajero_id")
    private Cajero cajero;

    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    @Column(name = "fondo_inicial", nullable = false)
    private double fondoInicial;

    @Column(name = "ventas_efectivo", nullable = false)
    private double ventasEfectivo;

    @Column(name = "ventas_tarjeta", nullable = false)
    private double ventasTarjeta;

    @Column(name = "ventas_totales", nullable = false)
    private double ventasTotales;

    @Column(name = "num_ventas", nullable = false)
    private int numVentas;

    @Column(name = "num_canceladas", nullable = false)
    private int numCanceladas;

    @Column(nullable = false)
    private double ganancia;

    @Column(nullable = false)
    private double iva;

    /** fondo + ventas en efectivo */
    @Column(name = "efectivo_esperado", nullable = false)
    private double efectivoEsperado;

    @Column(name = "efectivo_contado", nullable = false)
    private double efectivoContado;

    /** contado − esperado: negativo = faltante, positivo = sobrante */
    @Column(nullable = false)
    private double diferencia;

    @Column(length = 250)
    private String observaciones;

    public boolean hayFaltante()  { return diferencia < -0.005; }
    public boolean haySobrante()  { return diferencia >  0.005; }
}
