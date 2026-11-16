package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Renglón del libro de un vale de compra: su emisión, cada compra pagada con él y cada
 * cancelación que le regresa saldo. Nunca se edita ni se borra. {@code monto} lleva signo:
 * positivo sube el saldo (emisión, venta cancelada), negativo lo baja (compra).
 */
@Entity
@Table(name = "vales_movimientos", indexes = {
        @Index(name = "idx_vales_mov_vale", columnList = "vale_id"),
        @Index(name = "idx_vales_mov_venta", columnList = "venta_id"),
        @Index(name = "idx_vales_mov_fecha", columnList = "fecha")
})
@Getter
@Setter
@NoArgsConstructor
public class ValeMovimiento {

    public enum Tipo {
        EMISION("Vale entregado"),
        CANJE("Compra pagada con el vale"),
        CANCELACION_VENTA("Venta cancelada: regresa al vale");

        private final String etiqueta;
        Tipo(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(name = "vale_id", nullable = false)
    private Long valeId;

    /** Se guarda como texto (sin restricción CHECK) para poder agregar tipos sin reconstruir la tabla */
    @Column(nullable = false, length = 20)
    private String tipo;

    /** Con signo: + sube el saldo, − lo baja */
    @Column(nullable = false)
    private double monto;

    @Column(name = "saldo_despues", nullable = false)
    private double saldoDespues;

    /** Venta en que se usó (canje / cancelación) o de la que salió (emisión) */
    @Column(name = "venta_id")
    private Long ventaId;

    @Column(name = "venta_folio", length = 30)
    private String ventaFolio;

    @Column(name = "cajero_id")
    private Long cajeroId;

    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    @Column(name = "caja")
    private Integer caja;

    public Tipo getTipoEnum() {
        try {
            return tipo == null ? null : Tipo.valueOf(tipo);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setTipoEnum(Tipo t) {
        this.tipo = t == null ? null : t.name();
    }
}
