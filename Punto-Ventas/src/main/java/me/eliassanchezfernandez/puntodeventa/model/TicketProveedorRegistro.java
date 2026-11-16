package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Un ticket de proveedor ya registrado como entrada de mercancía. Sirve para no registrar dos veces
 * el mismo ticket (mismo proveedor y folio) y para consultar después qué se leyó.
 */
@Entity
@Table(name = "tickets_proveedor",
       indexes = {
           @Index(name = "idx_tickets_proveedor_folio", columnList = "proveedor_id, folio"),
           // Dos cajas no pueden registrar a la vez el mismo folio del mismo proveedor con la misma repetición: la
           // segunda falla en la base (y deshace su entrada) en vez de duplicar la mercancía. Índice único y no
           // restricción: SQLite no acepta "alter table add constraint" y Hibernate sí crea índices en tablas ya existentes.
           @Index(name = "uk_tickets_proveedor_folio_rep", columnList = "proveedor_id, folio, repeticion", unique = true) })
@Getter
@Setter
@NoArgsConstructor
public class TicketProveedorRegistro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "proveedor_id", nullable = false)
    private Proveedor proveedor;

    /** Nombre del proveedor al registrar (legible aunque luego se renombre) */
    @Column(name = "proveedor_nombre", length = 150)
    private String proveedorNombre;

    @Column(length = 60)
    private String folio;
    /** 1 la primera vez que se registra este folio del proveedor, 2 si el usuario confirmó que es otra entrega, etc. */
    @Column(name = "repeticion")
    private Integer repeticion;

    @Column(name = "fecha_ticket")
    private LocalDate fechaTicket;

    @Column(name = "registrado_en", nullable = false)
    private LocalDateTime registradoEn = LocalDateTime.now();

    @Column(name = "usuario_nombre", length = 100)
    private String usuarioNombre;

    @Column(nullable = false)
    private int renglones;

    /** Total impreso en el ticket (null si no se veía) */
    private Double total;

    /** Lo que se registró en costos (suma de renglones con impuestos y cargos repartidos) */
    @Column(name = "total_registrado", nullable = false)
    private double totalRegistrado;

    /** "Tesseract", "texto escrito"… */
    @Column(length = 40)
    private String origen;

    /** Texto leído, para revisar después qué entendió el lector */
    @Column(length = 20000)
    private String texto;
}
