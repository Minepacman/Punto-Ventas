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
 * Con varias cajas cada corte es de UNA caja; el administrador puede además
 * guardar un corte general que suma todas.
 *
 * Desde la versión con turnos, un corte de caja cubre el periodo
 * [{@code inicio}, {@code realizadoEn}) que va desde el corte anterior de esa
 * caja (o desde el inicio del día con fondo capturado); {@code efectivoInicial}
 * es con lo que empezó el periodo (el fondo o lo que dejó el corte anterior) y
 * {@code efectivoDejado} lo que queda en el cajón para el turno siguiente. Las
 * filas anteriores no tienen estas columnas (null): eran acumuladas del día y
 * se muestran tal cual.
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

    /** Inicio del periodo que cubre el corte (null en cortes antiguos = 00:00 del día) */
    @Column(name = "inicio")
    private LocalDateTime inicio;

    /** Efectivo con el que empezó el periodo: fondo del día o lo dejado por el corte anterior */
    @Column(name = "efectivo_inicial")
    private Double efectivoInicial;

    /** Entradas y salidas de efectivo (no ventas) del periodo */
    @Column(name = "entradas_efectivo")
    private Double entradasEfectivo;

    @Column(name = "salidas_efectivo")
    private Double salidasEfectivo;

    /** Efectivo que se dejó en el cajón para el siguiente turno (lo demás se retiró) */
    @Column(name = "efectivo_dejado")
    private Double efectivoDejado;

    public LocalDateTime getInicioOInicioDelDia() { return inicio != null ? inicio : fecha.atStartOfDay(); }
    public double getEfectivoInicialOFondo()      { return efectivoInicial != null ? efectivoInicial : fondoInicial; }
    public double getEntradasOCero()              { return entradasEfectivo == null ? 0 : entradasEfectivo; }
    public double getSalidasOCero()               { return salidasEfectivo == null ? 0 : salidasEfectivo; }
    /** Lo que quedó en caja: lo declarado, o el fondo en cortes antiguos (convención) */
    public double getEfectivoDejadoOFondo()       { return efectivoDejado != null ? efectivoDejado : fondoInicial; }
    /** Lo que se retiró del cajón al hacer el corte: contado − dejado */
    public double getRetiro()                     { return Math.round((efectivoContado - getEfectivoDejadoOFondo()) * 100.0) / 100.0; }
    /** true si el periodo no empieza a las 00:00 (hubo un corte antes en el día o viene de la noche anterior) */
    public boolean esTurno()                      { return inicio != null && !inicio.equals(fecha.atStartOfDay()); }

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

    /** Transferencias y vales del día (null en cortes anteriores a esta columna = 0) */
    @Column(name = "ventas_transferencia")
    private Double ventasTransferencia;

    @Column(name = "ventas_vales")
    private Double ventasVales;

    public double getVentasTransferenciaOCero() { return ventasTransferencia == null ? 0 : ventasTransferencia; }
    public double getVentasValesOCero()         { return ventasVales == null ? 0 : ventasVales; }

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

    /** efectivo inicial + ventas en efectivo + entradas − salidas del periodo */
    @Column(name = "efectivo_esperado", nullable = false)
    private double efectivoEsperado;

    @Column(name = "efectivo_contado", nullable = false)
    private double efectivoContado;

    /** contado − esperado: negativo = faltante, positivo = sobrante */
    @Column(nullable = false)
    private double diferencia;

    @Column(length = 250)
    private String observaciones;

    /**
     * Número de caja (instalación) que registró la fila. Null en las filas
     * anteriores a "varias cajas", que cuentan como caja 1.
     */
    @Column(name = "caja")
    private Integer caja;

    public int getCajaONumero() {
        return caja == null || caja <= 0 ? 1 : caja;
    }

    /**
     * Corte GENERAL (todas las cajas, solo ADMIN): suma las ventas y los fondos
     * de todas las cajas del día. Null o false = corte de una caja.
     */
    @Column(name = "general")
    private Boolean general;

    public boolean isGeneral() {
        return Boolean.TRUE.equals(general);
    }

    /** "Caja 2" o "Todas las cajas" */
    public String getEtiquetaCaja() {
        return isGeneral() ? "Todas las cajas" : "Caja " + getCajaONumero();
    }

    public boolean hayFaltante()  { return diferencia < -0.005; }
    public boolean haySobrante()  { return diferencia >  0.005; }
}
