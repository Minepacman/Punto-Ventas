package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Foto de un ticket de proveedor que alguien mandó desde el teléfono «a la computadora para revisar»
 * (con lo que se leyó, si se pudo leer). En Inventario se abre en «Leer ticket» ya cargada; queda
 * REGISTRADO al darle entrada o DESCARTADO si no sirve. La foto vive EN la base (como las fotos de
 * producto) para que con varias cajas cualquiera la vea; byte[] sin @Lob = blob en SQLite, bytea en PostgreSQL.
 */
@Entity
@Table(name = "tickets_pendientes_revision",
       indexes = @Index(name = "idx_tickets_pendientes_revision_estado", columnList = "estado"))
@Getter
@Setter
@NoArgsConstructor
public class TicketPorRevisar {

    public enum Estado { PENDIENTE, REGISTRADO, DESCARTADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "foto", nullable = false)
    private byte[] foto;

    @Column(name = "foto_mime", nullable = false, length = 40)
    private String fotoMime;

    /** Lo que leyó el OCR (JSON: origen, texto y renglones con su posición); null si no se pudo leer */
    @Column(name = "lectura", length = 200000)
    private String lectura;

    /** Proveedor que eligió quien la mandó (si lo eligió) */
    @Column(name = "proveedor_id")
    private Long proveedorId;

    @Column(name = "nota", length = 300)
    private String nota;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private Estado estado = Estado.PENDIENTE;

    @Column(name = "creado_en", nullable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(name = "usuario_nombre", length = 100)
    private String usuarioNombre;

    @Column(name = "resuelto_en")
    private LocalDateTime resueltoEn;

    @Column(name = "resuelto_por", length = 100)
    private String resueltoPor;

    /** Entrada registrada con este ticket (tickets_proveedor.id) */
    @Column(name = "registro_id")
    private Long registroId;
}
