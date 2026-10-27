package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unidad de medida del catálogo (Configuración → Unidades de medida).
 * "Permite decimales" decide si un producto con esta unidad se vende por
 * fracciones (kilogramo, litro) o por piezas enteras.
 */
@Entity
@Table(name = "unidades")
@Data
@NoArgsConstructor
public class Unidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String nombre;

    @Column(length = 10)
    private String abreviatura;

    @Column(name = "permite_decimales", nullable = false)
    private boolean permiteDecimales;

    public Unidad(String nombre, String abreviatura, boolean permiteDecimales) {
        this.nombre = nombre;
        this.abreviatura = abreviatura;
        this.permiteDecimales = permiteDecimales;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
