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

    /**
     * Clave de unidad del SAT (c_ClaveUnidad) que se usa al facturar los
     * productos con esta unidad, salvo que el producto tenga la suya. Nullable:
     * si falta, CatalogoSat la deduce del nombre (Pieza → H87, Kilogramo → KGM...).
     */
    @Column(name = "clave_unidad", length = 3)
    private String claveUnidad;

    public Unidad(String nombre, String abreviatura, boolean permiteDecimales) {
        this.nombre = nombre;
        this.abreviatura = abreviatura;
        this.permiteDecimales = permiteDecimales;
    }

    public Unidad(String nombre, String abreviatura, boolean permiteDecimales, String claveUnidad) {
        this(nombre, abreviatura, permiteDecimales);
        this.claveUnidad = claveUnidad;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
