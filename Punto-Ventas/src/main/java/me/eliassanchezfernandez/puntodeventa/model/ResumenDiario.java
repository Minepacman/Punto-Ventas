package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Agregado precalculado: ventas de UN día (sin canceladas), con el desglose
 * por franja horaria. Lo llena AgregadosService (trabajo nocturno, arranque o
 * botón "Recalcular") y lo lee la pantalla de Estadísticas. Una fila por día;
 * los días sin ventas no tienen fila.
 */
@Entity
@Table(name = "resumen_diario",
       uniqueConstraints = @UniqueConstraint(name = "uk_resumen_diario_fecha", columnNames = "fecha"))
@Getter
@Setter
@NoArgsConstructor
public class ResumenDiario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate fecha;

    private double total;
    private int    transacciones;
    private double piezas;

    // ── Por franja (total vendido y número de tickets) ───────────────────

    @Column(name = "total_manana")    private double totalManana;
    @Column(name = "total_mediodia")  private double totalMediodia;
    @Column(name = "total_tarde")     private double totalTarde;
    @Column(name = "total_noche")     private double totalNoche;
    @Column(name = "total_madrugada") private double totalMadrugada;

    @Column(name = "trans_manana")    private int transManana;
    @Column(name = "trans_mediodia")  private int transMediodia;
    @Column(name = "trans_tarde")     private int transTarde;
    @Column(name = "trans_noche")     private int transNoche;
    @Column(name = "trans_madrugada") private int transMadrugada;

    public ResumenDiario(LocalDate fecha) {
        this.fecha = fecha;
    }

    // ── Lógica de dominio ────────────────────────────────────────────────

    /** Suma una venta al día y a su franja */
    public void sumarVenta(Franja franja, double importe, double piezasVenta) {
        total += importe;
        transacciones++;
        piezas += piezasVenta;
        switch (franja) {
            case MANANA    -> { totalManana    += importe; transManana++; }
            case MEDIODIA  -> { totalMediodia  += importe; transMediodia++; }
            case TARDE     -> { totalTarde     += importe; transTarde++; }
            case NOCHE     -> { totalNoche     += importe; transNoche++; }
            case MADRUGADA -> { totalMadrugada += importe; transMadrugada++; }
        }
    }

    public double totalDe(Franja franja) {
        return switch (franja) {
            case MANANA    -> totalManana;
            case MEDIODIA  -> totalMediodia;
            case TARDE     -> totalTarde;
            case NOCHE     -> totalNoche;
            case MADRUGADA -> totalMadrugada;
        };
    }

    public int transaccionesDe(Franja franja) {
        return switch (franja) {
            case MANANA    -> transManana;
            case MEDIODIA  -> transMediodia;
            case TARDE     -> transTarde;
            case NOCHE     -> transNoche;
            case MADRUGADA -> transMadrugada;
        };
    }

    public double getTicketPromedio() {
        return transacciones == 0 ? 0 : total / transacciones;
    }

    /** Redondea los importes a centavos (los acumulados en double dejan residuos) */
    public void redondear() {
        total          = VentaRedondeo.centavos(total);
        totalManana    = VentaRedondeo.centavos(totalManana);
        totalMediodia  = VentaRedondeo.centavos(totalMediodia);
        totalTarde     = VentaRedondeo.centavos(totalTarde);
        totalNoche     = VentaRedondeo.centavos(totalNoche);
        totalMadrugada = VentaRedondeo.centavos(totalMadrugada);
        piezas         = VentaRedondeo.milesimas(piezas);
    }

    /** Redondeos compartidos por los agregados (mismo criterio que VentaService) */
    public static final class VentaRedondeo {
        private VentaRedondeo() { }
        public static double centavos(double v)  { return Math.round(v * 100.0) / 100.0; }
        public static double milesimas(double v) { return Math.round(v * 1000.0) / 1000.0; }
    }
}
