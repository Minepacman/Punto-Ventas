package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Configuración del negocio (CFG-01, VEN-04, VEN-14). Una sola fila (id = 1)
 * que ConfiguracionService crea con valores por defecto la primera vez.
 *
 * Los campos agregados después (opciones, ticket, formas de pago, moneda,
 * folios, respaldo) son nullable para convivir con la fila ya existente en
 * datos.db (SQLite no agrega columnas NOT NULL sin valor por defecto); cada
 * uno tiene un accesor que devuelve el valor por defecto cuando es null.
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

    // ── Opciones habilitadas ─────────────────────────────────────────────

    /** Pedir el fondo de caja al primer inicio de sesión del día (CRT-02) */
    @Column(name = "pedir_fondo_caja")
    private Boolean pedirFondoCaja;

    /** Avisar en Ventas cuando una venta deja un producto en su mínimo (INV-03) */
    @Column(name = "aviso_stock_bajo")
    private Boolean avisoStockBajo;

    /** Permitir productos a granel (cantidades decimales) */
    @Column(name = "permitir_granel")
    private Boolean permitirGranel;

    /** Pedir confirmación antes de cobrar */
    @Column(name = "confirmar_cobro")
    private Boolean confirmarCobro;

    // ── Ticket ───────────────────────────────────────────────────────────

    @Column(name = "ticket_encabezado", length = 250)
    private String ticketEncabezado;

    @Column(name = "ticket_pie", length = 250)
    private String ticketPie;

    @Column(name = "ticket_rfc", length = 20)
    private String ticketRfc;

    @Column(name = "ticket_telefono", length = 30)
    private String ticketTelefono;

    @Column(name = "ticket_agradecimiento", length = 150)
    private String ticketAgradecimiento;

    /** Ancho del papel en mm: 58 u 80 */
    @Column(name = "ticket_ancho_mm")
    private Integer ticketAnchoMm;

    // ── Formas de pago ───────────────────────────────────────────────────

    @Column(name = "pago_efectivo")
    private Boolean pagoEfectivo;

    @Column(name = "pago_tarjeta")
    private Boolean pagoTarjeta;

    @Column(name = "pago_transferencia")
    private Boolean pagoTransferencia;

    @Column(name = "pago_vales")
    private Boolean pagoVales;

    // ── Moneda ───────────────────────────────────────────────────────────

    @Column(name = "moneda_simbolo", length = 5)
    private String monedaSimbolo;

    @Column(name = "moneda_codigo", length = 5)
    private String monedaCodigo;

    // ── Folios (solo presentación; nunca cambian los id de las ventas) ───

    @Column(name = "folio_prefijo", length = 10)
    private String folioPrefijo;

    /** Se suma al id de la venta para formar el número de folio mostrado */
    @Column(name = "folio_desfase")
    private Integer folioDesfase;

    // ── Respaldo automático ──────────────────────────────────────────────

    @Column(name = "respaldo_auto")
    private Boolean respaldoAuto;

    @Column(name = "respaldo_carpeta", length = 500)
    private String respaldoCarpeta;

    @Column(name = "respaldo_copias")
    private Integer respaldoCopias;

    @Column(name = "respaldo_ultimo")
    private LocalDate respaldoUltimo;

    // ── Estadísticas, sugerencias y clima ────────────────────────────────

    /** Mostrar el panel de sugerencias en la pantalla de Ventas (null = sí) */
    @Column(name = "mostrar_sugerencias")
    private Boolean mostrarSugerencias;

    /** Ubicación del negocio: lo ÚNICO que se envía al servicio de clima */
    @Column(name = "latitud")
    private Double latitud;

    @Column(name = "longitud")
    private Double longitud;

    @Column(name = "ciudad", length = 100)
    private String ciudad;

    /** Consultar el clima cada hora (apagado hasta que haya coordenadas) */
    @Column(name = "clima_activo")
    private Boolean climaActivo;

    /** Última vez que se recalcularon los agregados de Estadísticas (lo anota el sistema) */
    @Column(name = "estadisticas_actualizado")
    private LocalDateTime estadisticasActualizado;

    // ── Acceso móvil (servidor web local para teléfonos en la misma red) ──

    /** Servidor móvil encendido al abrir el programa (null = apagado) */
    @Column(name = "movil_activo")
    private Boolean movilActivo;

    /** Puerto del servidor móvil (null = 8085) */
    @Column(name = "movil_puerto")
    private Integer movilPuerto;

    public static final int MOVIL_PUERTO_POR_DEFECTO = 8085;

    public boolean isMovilActivo() { return movilActivo != null && movilActivo; }
    public int getMovilPuerto()    { return movilPuerto != null ? movilPuerto : MOVIL_PUERTO_POR_DEFECTO; }

    public static final int TICKET_ANCHO_POR_DEFECTO = 80;
    public static final int RESPALDO_COPIAS_POR_DEFECTO = 7;

    public boolean isPedirFondoCaja()    { return pedirFondoCaja == null || pedirFondoCaja; }
    public boolean isAvisoStockBajo()    { return avisoStockBajo == null || avisoStockBajo; }
    public boolean isPermitirGranel()    { return permitirGranel == null || permitirGranel; }
    public boolean isConfirmarCobro()    { return confirmarCobro != null && confirmarCobro; }

    public int getTicketAnchoMm()        { return ticketAnchoMm != null ? ticketAnchoMm : TICKET_ANCHO_POR_DEFECTO; }

    public boolean isPagoEfectivo()      { return pagoEfectivo == null || pagoEfectivo; }
    public boolean isPagoTarjeta()       { return pagoTarjeta == null || pagoTarjeta; }
    public boolean isPagoTransferencia() { return pagoTransferencia != null && pagoTransferencia; }
    public boolean isPagoVales()         { return pagoVales != null && pagoVales; }

    public String getMonedaSimbolo()     { return monedaSimbolo != null && !monedaSimbolo.isBlank() ? monedaSimbolo : "$"; }
    public String getMonedaCodigo()      { return monedaCodigo != null && !monedaCodigo.isBlank() ? monedaCodigo : "MXN"; }

    public String getFolioPrefijo()      { return folioPrefijo != null ? folioPrefijo : ""; }
    public int getFolioDesfase()         { return folioDesfase != null ? folioDesfase : 0; }

    public boolean isRespaldoAuto()      { return respaldoAuto != null && respaldoAuto; }
    public int getRespaldoCopias()       { return respaldoCopias != null ? respaldoCopias : RESPALDO_COPIAS_POR_DEFECTO; }

    public boolean isMostrarSugerencias() { return mostrarSugerencias == null || mostrarSugerencias; }
    public boolean isClimaActivo()        { return climaActivo != null && climaActivo && tieneUbicacion(); }
    public boolean tieneUbicacion()       { return latitud != null && longitud != null; }

    /** Folio que se muestra para la venta con ese id (prefijo + id + desfase) */
    public String folioDe(long ventaId) {
        return getFolioPrefijo() + (ventaId + getFolioDesfase());
    }

    /** ¿Esta forma de pago está habilitada? CREDITO nunca (no hay módulo de clientes) */
    public boolean permiteFormaPago(Venta.FormaPago forma) {
        if (forma == null) return false;
        return switch (forma) {
            case EFECTIVO      -> isPagoEfectivo();
            case TARJETA       -> isPagoTarjeta();
            case TRANSFERENCIA -> isPagoTransferencia();
            case VALES         -> isPagoVales();
            case CREDITO       -> false;
        };
    }

    /** Desglosa un total que ya incluye IVA: regresa la base (subtotal) */
    public double subtotalDe(double totalConIva) {
        return tasaIva <= 0 ? totalConIva : totalConIva / (1 + tasaIva);
    }

    public double ivaDe(double totalConIva) {
        return totalConIva - subtotalDe(totalConIva);
    }
}
