package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Clima del negocio registrado cada hora (ClimaService). Las ventas no
 * guardan el clima: se les asocia por la hora (fechaHora truncada a la hora).
 * Solo se guarda lo que regresa el servicio público de pronóstico para las
 * coordenadas configuradas; ningún dato de ventas sale del equipo.
 */
@Entity
@Table(name = "clima_registro",
       uniqueConstraints = @UniqueConstraint(name = "uk_clima_fecha_hora", columnNames = "fecha_hora"))
@Getter
@Setter
@NoArgsConstructor
public class ClimaRegistro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Inicio de la hora a la que corresponde (minutos y segundos en cero) */
    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;

    private double temperatura;

    /** Código WMO que regresa el servicio (0 = despejado, 61 = lluvia…) */
    private int codigo;

    /** Despejado, Nublado, Lluvia, Tormenta, Niebla, Nieve */
    @Column(nullable = false, length = 30)
    private String categoria;

    /** Frío, Templado, Calor */
    @Column(nullable = false, length = 20)
    private String banda;

    public ClimaRegistro(LocalDateTime fechaHora, double temperatura, int codigo, String categoria, String banda) {
        this.fechaHora = fechaHora;
        this.temperatura = temperatura;
        this.codigo = codigo;
        this.categoria = categoria;
        this.banda = banda;
    }

    /** "Lluvia, 14 °C" */
    public String getDescripcion() {
        return categoria + ", " + Math.round(temperatura) + " °C";
    }
}
