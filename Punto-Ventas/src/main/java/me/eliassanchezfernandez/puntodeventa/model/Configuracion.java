package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Configuración del negocio (CFG-01, VEN-04, VEN-14). Una sola fila (id = 1)
 * que ConfiguracionService crea con valores por defecto la primera vez.
 */
@Entity
@Table(name = "configuracion")
@Getter
@Setter
@NoArgsConstructor
public class Configuracion {

    public static final long ID_UNICO = 1L;
    public static final double TASA_IVA_POR_DEFECTO = 0.16;

    @Id
    private Long id = ID_UNICO;

    @Column(name = "nombre_tienda", nullable = false, length = 150)
    private String nombreTienda = "Mi Tienda";

    @Column(length = 250)
    private String direccion;

    /** Tasa de IVA como fracción (0.16 = 16 %). Los precios YA la incluyen. */
    @Column(name = "tasa_iva", nullable = false)
    private double tasaIva = TASA_IVA_POR_DEFECTO;

    /** Horas durante las que una venta puede cancelarse (aplica a ventas futuras) */
    @Column(name = "plazo_cancelacion_horas", nullable = false)
    private int plazoCancelacionHoras = Venta.PLAZO_CANCELACION_HORAS_POR_DEFECTO;

    /** Desglosa un total que ya incluye IVA: regresa la base (subtotal) */
    public double subtotalDe(double totalConIva) {
        return tasaIva <= 0 ? totalConIva : totalConIva / (1 + tasaIva);
    }

    public double ivaDe(double totalConIva) {
        return totalConIva - subtotalDe(totalConIva);
    }
}
