package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Factura electrónica (CFDI 4.0) emitida por el PAC: de una venta
 * ({@link Tipo#VENTA}) o global de público en general ({@link Tipo#GLOBAL}).
 * Las ventas que cubre están en {@link FacturaVenta} (una en la individual,
 * varias en la global). Nunca se borra una factura VIGENTE o CANCELADA; la
 * PENDIENTE (enviada al PAC sin respuesta clara) se resuelve al reintentar y la
 * que el PAC rechazó de forma definitiva se elimina.
 *
 * Tipo y estado se guardan como texto (sin CHECK) para poder agregar valores.
 */
@Entity
@Table(name = "facturas", indexes = {
        @Index(name = "idx_facturas_fecha", columnList = "fecha"),
        @Index(name = "idx_facturas_estado", columnList = "estado")})
@Getter
@Setter
@NoArgsConstructor
public class Factura {

    public enum Tipo { VENTA, GLOBAL }

    public enum Estado {
        /** Enviada al PAC; no se sabe si se timbró (tiempo agotado). Se verifica antes de reintentar. */
        PENDIENTE("Pendiente de confirmar"),
        VIGENTE("Vigente"),
        /** Cancelación solicitada; el receptor puede aceptarla o rechazarla (plazo del SAT) */
        CANCELACION_EN_PROCESO("Cancelación en proceso"),
        CANCELADA("Cancelada");

        private final String etiqueta;
        Estado(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String tipo = Tipo.VENTA.name();

    @Column(nullable = false, length = 30)
    private String estado = Estado.PENDIENTE.name();

    /** Venta facturada (null en la factura global; el detalle está en ventas) */
    @ManyToOne
    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)
    @JoinColumn(name = "venta_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Venta venta;

    @OneToMany(mappedBy = "factura", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FacturaVenta> ventas = new ArrayList<>();

    // ── Identificación en el PAC / SAT ───────────────────────────────────

    /** Id del comprobante en el PAC (Facturama) */
    @Column(name = "id_externo", length = 80)
    private String idExterno;

    /** Folio fiscal del SAT */
    @Column(length = 40)
    private String uuid;

    @Column(length = 10)
    private String serie;

    @Column(length = 20)
    private String folio;

    /** Referencia enviada al PAC (OrderNumber) para detectar duplicados al reintentar */
    @Column(name = "numero_orden", length = 60)
    private String numeroOrden;

    /** true si se timbró en el sandbox (sin validez fiscal) */
    @Column(name = "sandbox")
    private Boolean sandbox;

    // ── Datos del comprobante ────────────────────────────────────────────

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(name = "fecha_timbrado")
    private LocalDateTime fechaTimbrado;

    private double subtotal;

    private double descuento;

    private double iva;

    private double total;

    @Column(name = "receptor_rfc", length = 13)
    private String receptorRfc;

    @Column(name = "receptor_nombre", length = 254)
    private String receptorNombre;

    @Column(name = "receptor_email", length = 254)
    private String receptorEmail;

    @Column(name = "uso_cfdi", length = 4)
    private String usoCfdi;

    @Column(name = "forma_pago", length = 2)
    private String formaPago;

    @Column(name = "metodo_pago", length = 3)
    private String metodoPago = "PUE";

    // ── Factura global ───────────────────────────────────────────────────

    @Column(length = 2)
    private String periodicidad;

    @Column(length = 2)
    private String meses;

    @Column(length = 4)
    private String anio;

    @Column(name = "periodo_desde")
    private LocalDate periodoDesde;

    @Column(name = "periodo_hasta")
    private LocalDate periodoHasta;

    // ── Archivos (ruta relativa a la carpeta de facturas) ────────────────

    @Column(name = "archivo_xml", length = 300)
    private String archivoXml;

    @Column(name = "archivo_pdf", length = 300)
    private String archivoPdf;

    @Column(name = "email_enviado")
    private LocalDateTime emailEnviado;

    // ── Quién y errores ──────────────────────────────────────────────────

    @Column(name = "emitida_por_id")
    private Long emitidaPorId;

    @Column(name = "emitida_por_nombre", length = 100)
    private String emitidaPorNombre;

    @Column(name = "ultimo_error", length = 500)
    private String ultimoError;

    // ── Cancelación ──────────────────────────────────────────────────────

    @Column(name = "fecha_cancelacion")
    private LocalDateTime fechaCancelacion;

    @Column(name = "motivo_cancelacion", length = 2)
    private String motivoCancelacion;

    @Column(name = "uuid_sustitucion", length = 40)
    private String uuidSustitucion;

    @Column(name = "cancelada_por_id")
    private Long canceladaPorId;

    @Column(name = "cancelada_por_nombre", length = 100)
    private String canceladaPorNombre;

    // ── Accesores ────────────────────────────────────────────────────────

    public Tipo getTipoEnum() {
        try { return Tipo.valueOf(tipo); } catch (IllegalArgumentException | NullPointerException e) { return Tipo.VENTA; }
    }

    public void setTipoEnum(Tipo t) { this.tipo = t.name(); }

    public Estado getEstadoEnum() {
        try { return Estado.valueOf(estado); } catch (IllegalArgumentException | NullPointerException e) { return Estado.PENDIENTE; }
    }

    public void setEstadoEnum(Estado e) { this.estado = e.name(); }

    public boolean isVigente()   { return getEstadoEnum() == Estado.VIGENTE; }
    public boolean isCancelada() { return getEstadoEnum() == Estado.CANCELADA; }
    public boolean isPendiente() { return getEstadoEnum() == Estado.PENDIENTE; }
    public boolean esGlobal()    { return getTipoEnum() == Tipo.GLOBAL; }
    public boolean isSandbox()   { return sandbox != null && sandbox; }

    /** Bloquea la cancelación de la venta: vigente o con cancelación todavía no aceptada */
    public boolean bloqueaVenta() {
        Estado e = getEstadoEnum();
        return e == Estado.VIGENTE || e == Estado.PENDIENTE || e == Estado.CANCELACION_EN_PROCESO;
    }

    /** "A-12" para pantallas */
    public String getSerieFolio() {
        String s = serie != null ? serie : "";
        String f = folio != null ? folio : (id != null ? String.valueOf(id) : "");
        return s.isEmpty() ? f : s + "-" + f;
    }

    public boolean tieneArchivos() {
        return archivoXml != null && archivoPdf != null;
    }

    @Override
    public String toString() {
        return "Factura{" + getSerieFolio() + ", " + estado + ", uuid=" + uuid + ", total=" + total + "}";
    }
}
