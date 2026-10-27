package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Agregado precalculado: piezas e importe vendidos de UN producto en UN día
 * (sin canceladas), con las piezas por franja horaria. Alimenta el ranking de
 * productos más vendidos y las recomendaciones por día y hora.
 */
@Entity
@Table(name = "producto_diario",
       uniqueConstraints = @UniqueConstraint(name = "uk_producto_diario", columnNames = {"fecha", "producto_id"}),
       indexes = @Index(name = "idx_producto_diario_fecha", columnList = "fecha"))
@Getter
@Setter
@NoArgsConstructor
public class ProductoDiario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate fecha;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Producto producto;

    private double piezas;
    private double importe;

    @Column(name = "piezas_manana")    private double piezasManana;
    @Column(name = "piezas_mediodia")  private double piezasMediodia;
    @Column(name = "piezas_tarde")     private double piezasTarde;
    @Column(name = "piezas_noche")     private double piezasNoche;
    @Column(name = "piezas_madrugada") private double piezasMadrugada;

    public ProductoDiario(LocalDate fecha, Producto producto) {
        this.fecha = fecha;
        this.producto = producto;
    }

    public void sumar(Franja franja, double cantidad, double importeRenglon) {
        piezas  += cantidad;
        importe += importeRenglon;
        switch (franja) {
            case MANANA    -> piezasManana    += cantidad;
            case MEDIODIA  -> piezasMediodia  += cantidad;
            case TARDE     -> piezasTarde     += cantidad;
            case NOCHE     -> piezasNoche     += cantidad;
            case MADRUGADA -> piezasMadrugada += cantidad;
        }
    }

    public double piezasDe(Franja franja) {
        return switch (franja) {
            case MANANA    -> piezasManana;
            case MEDIODIA  -> piezasMediodia;
            case TARDE     -> piezasTarde;
            case NOCHE     -> piezasNoche;
            case MADRUGADA -> piezasMadrugada;
        };
    }

    public void redondear() {
        importe         = ResumenDiario.VentaRedondeo.centavos(importe);
        piezas          = ResumenDiario.VentaRedondeo.milesimas(piezas);
        piezasManana    = ResumenDiario.VentaRedondeo.milesimas(piezasManana);
        piezasMediodia  = ResumenDiario.VentaRedondeo.milesimas(piezasMediodia);
        piezasTarde     = ResumenDiario.VentaRedondeo.milesimas(piezasTarde);
        piezasNoche     = ResumenDiario.VentaRedondeo.milesimas(piezasNoche);
        piezasMadrugada = ResumenDiario.VentaRedondeo.milesimas(piezasMadrugada);
    }
}
