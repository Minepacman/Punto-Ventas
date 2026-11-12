package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket sin cobrar. Hay dos usos, distinguidos por {@link #enEspera}:
 *  - autoguardado (enEspera null/false): el ticket en captura se conserva al
 *    cerrar sesión (USR-15) y se restaura cuando el MISMO cajero vuelve a
 *    entrar en la misma caja (uno por cajero y caja);
 *  - en espera (enEspera = true): el cajero lo "aparta" con F11 para atender
 *    a otro cliente y lo recupera después con Shift+F11 (varios por caja; lo
 *    puede recuperar cualquier cajero de esa caja).
 * Solo guarda lo que el cajero capturó (producto, cantidad, mayoreo,
 * descuento manual); los precios se recalculan al restaurarlo.
 */
@Entity
@Table(name = "tickets_pendientes")
@Getter
@Setter
@NoArgsConstructor
public class TicketPendiente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "cajero_id")
    private Cajero cajero;

    @Column(name = "guardado_en", nullable = false)
    private LocalDateTime guardadoEn = LocalDateTime.now();

    /**
     * Número de caja (instalación) que registró la fila. Null en las filas
     * anteriores a "varias cajas", que cuentan como caja 1.
     */
    @Column(name = "caja")
    private Integer caja;

    public int getCajaONumero() {
        return caja == null || caja <= 0 ? 1 : caja;
    }

    /** true = ticket apartado a propósito ("en espera"); null/false = autoguardado al cerrar sesión */
    @Column(name = "en_espera")
    private Boolean enEspera;

    /** Referencia opcional del ticket en espera ("Sra. Lupita", "el de la camioneta") */
    @Column(name = "nota", length = 60)
    private String nota;

    public boolean isEnEspera() {
        return Boolean.TRUE.equals(enEspera);
    }

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Linea> lineas = new ArrayList<>();

    @Entity
    @Table(name = "tickets_pendientes_lineas")
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Linea {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(optional = false)
        @JoinColumn(name = "ticket_id")
        private TicketPendiente ticket;

        @ManyToOne(optional = false)
        @JoinColumn(name = "producto_id")
        private Producto producto;

        @Column(nullable = false)
        private double cantidad;

        @Column(nullable = false)
        private boolean mayoreo;

        @Column(name = "descuento_manual_pct")
        private Double descuentoManualPct;

        @Column(name = "descuento_manual_importe")
        private Double descuentoManualImporte;
    }
}
