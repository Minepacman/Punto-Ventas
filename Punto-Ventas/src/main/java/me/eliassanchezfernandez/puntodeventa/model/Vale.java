package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Vale de compra (nota de crédito de la tienda): en lugar de devolver efectivo por una
 * devolución, se le entrega al cliente un vale impreso con un código (y su código de barras)
 * que se usa como forma de pago en una compra siguiente. Puede usarse en partes: el
 * {@link #saldo} baja con cada compra y sube si se cancela una venta pagada con él.
 *
 * El saldo solo cambia con UPDATE condicionados ({@code ValeRepository}), nunca leyendo y
 * guardando: dos cajas que cobran con el mismo vale a la vez no pueden gastarlo dos veces.
 * Cada cambio queda en el libro ({@link ValeMovimiento}). Nunca se borra. Lo arma {@code ValeService}.
 */
@Entity
@Table(name = "vales", indexes = {
        @Index(name = "idx_vales_fecha", columnList = "fecha"),
        @Index(name = "idx_vales_devolucion", columnList = "devolucion_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Vale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Código corto, sin letras que se confundan (sin 0/O, 1/I/L): 8 caracteres, se muestra como "K7M2-9QXA" */
    @Column(nullable = false, length = 20, unique = true)
    private String codigo;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    /** Lo que valía al emitirse */
    @Column(nullable = false)
    private double monto;

    /** Lo que queda por usar */
    @Column(nullable = false)
    private double saldo;

    /** Último día en que se puede usar (null = no vence) */
    @Column(name = "vence")
    private LocalDate vence;

    /** Venta y devolución de la que salió (sin FK: SQLite no la agrega a tablas existentes) */
    @Column(name = "venta_id")
    private Long ventaId;

    @Column(name = "venta_folio", length = 30)
    private String ventaFolio;

    @Column(name = "devolucion_id")
    private Long devolucionId;

    /** Cliente de la venta de origen, si lo había (solo informativo: cualquiera puede usar el vale) */
    @Column(name = "cliente_nombre", length = 150)
    private String clienteNombre;

    @Column(name = "cajero_id")
    private Long cajeroId;

    @Column(name = "cajero_nombre", length = 100)
    private String cajeroNombre;

    @Column(name = "caja")
    private Integer caja;

    public int getCajaONumero() {
        return caja == null || caja <= 0 ? 1 : caja;
    }

    /** ¿Ya pasó su último día? */
    public boolean estaVencido(LocalDate hoy) {
        return vence != null && hoy != null && hoy.isAfter(vence);
    }

    public boolean estaAgotado() {
        return saldo <= 0.004;
    }

    /** "K7M2-9QXA" */
    public String getCodigoMostrado() {
        return mostrar(codigo);
    }

    /** Código con guion a la mitad para leerlo y dictarlo fácil */
    public static String mostrar(String codigo) {
        if (codigo == null) return "";
        return codigo.length() == 8 ? codigo.substring(0, 4) + "-" + codigo.substring(4) : codigo;
    }

    /** Folio del vale: "V-" + id */
    public String getFolio() {
        return "V-" + id;
    }

    @Override
    public String toString() {
        return "Vale{" + codigo + ", monto=" + monto + ", saldo=" + saldo + "}";
    }
}
