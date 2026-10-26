package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Ticket sin cobrar que se conserva al cerrar sesión (USR-15) y se restaura
 * cuando el MISMO cajero vuelve a entrar. Solo guarda lo que el cajero
 * capturó (producto, cantidad, mayoreo, descuento manual); los precios se
 * recalculan al restaurarlo.
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
