package me.eliassanchezfernandez.puntodeventa.service;

import jakarta.annotation.PreDestroy;
import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.impresion.EnvioJavaxPrint;
import me.eliassanchezfernandez.puntodeventa.impresion.EscPos;
import me.eliassanchezfernandez.puntodeventa.impresion.ImpresionException;
import me.eliassanchezfernandez.puntodeventa.impresion.ImpresoraEscPos;
import me.eliassanchezfernandez.puntodeventa.impresion.ImpresoraNinguna;
import me.eliassanchezfernandez.puntodeventa.impresion.ImpresoraSistema;
import me.eliassanchezfernandez.puntodeventa.impresion.ImpresoraTickets;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Impresión de tickets, corte y órdenes de compra, y cajón de dinero.
 *
 * REGLA DE ORO: imprimir NUNCA frena ni hace fallar una venta. Los métodos
 * "Async" corren en un hilo propio (fuera del de JavaFX) y NUNCA lanzan: todo
 * error (impresora apagada, sin papel, sin controlador, sin JavaFX...) vuelve
 * como {@link Resultado} para que la pantalla muestre un aviso. Los permisos se
 * revisan ANTES de pasar al hilo de impresión, en el hilo que llama.
 */
@Service
public class ImpresionService {

    /** Resultado de un intento: impreso, omitido (nada que hacer / sin impresora) o fallo con mensaje para el usuario */
    public record Resultado(Estado estado, String mensaje) {
        public enum Estado { IMPRESO, OMITIDO, FALLO }
        public static Resultado impreso(String m) { return new Resultado(Estado.IMPRESO, m); }
        public static Resultado omitido(String m) { return new Resultado(Estado.OMITIDO, m); }
        public static Resultado fallo(String m)   { return new Resultado(Estado.FALLO, m); }
        public boolean esFallo() { return estado == Estado.FALLO; }
    }

    @Autowired private DispositivosService dispositivos;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private TicketService ticketService;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private VentaService ventaService;
    @Autowired private CajaService cajaService;
    @Autowired private MovimientoCajaService movimientoCajaService;
    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private PlatformTransactionManager transacciones;
    @Autowired private IdentidadCaja identidadCaja;

    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "impresion");
        t.setDaemon(true);
        return t;
    });

    /** Cómo se crea la impresora según la configuración (las pruebas la cambian por una falsa) */
    private Function<ConfiguracionDispositivos, ImpresoraTickets> fabrica = this::crearImpresora;

    /** Cambia la fábrica de impresoras (pruebas); null vuelve a la real */
    public void setFabrica(Function<ConfiguracionDispositivos, ImpresoraTickets> fabrica) {
        this.fabrica = fabrica != null ? fabrica : this::crearImpresora;
    }

    @PreDestroy
    void cerrar() {
        hilo.shutdownNow();
    }

    /** Impresora real para la configuración vigente */
    ImpresoraTickets crearImpresora(ConfiguracionDispositivos c) {
        int cols = TicketService.columnas(configuracionService.obtener().getTicketAnchoMm());
        return switch (c.getModoImpresora()) {
            case TERMICA -> new ImpresoraEscPos(new EnvioJavaxPrint(c.getImpresoraNombre()),
                    EscPos.CodePage.de(c.getImpresoraCodePage()), c.getImpresoraNombre());
            case SISTEMA -> new ImpresoraSistema(c.getImpresoraNombre(), cols);
            default -> new ImpresoraNinguna();
        };
    }

    /** Impresoras instaladas en el equipo (puede tardar: fuera del hilo de JavaFX) */
    public List<String> listarImpresoras() {
        return EnvioJavaxPrint.listarImpresoras();
    }

    // ── Ticket de venta ───────────────────────────────────────────────────

    /**
     * Después de cobrar: imprime el ticket si "imprimir automáticamente" está
     * activo y abre el cajón si hubo efectivo y la opción está activa. No hace
     * nada (OMITIDO) si no hay impresora o ninguna de las dos opciones aplica.
     */
    public CompletableFuture<Resultado> despuesDeCobrarAsync(Long ventaId) {
        // El cajero se toma en el hilo que llama: la bitácora del cajón lo necesita en el de impresión
        final Cajero cajero = sesionService.getCajeroActual();
        return CompletableFuture.supplyAsync(() -> despuesDeCobrar(ventaId, cajero), hilo);
    }

    public Resultado despuesDeCobrar(Long ventaId) {
        return despuesDeCobrar(ventaId, sesionService.getCajeroActual());
    }

    private Resultado despuesDeCobrar(Long ventaId, Cajero cajero) {
        try {
            ConfiguracionDispositivos c = dispositivos.obtener();
            if (c.getModoImpresora() == ConfiguracionDispositivos.ModoImpresora.NINGUNA) {
                return Resultado.omitido("Sin impresora configurada.");
            }
            boolean imprimir = c.isImprimirAutomatico();
            String folio = c.isAbrirCajonEfectivo() && c.getModoImpresora() == ConfiguracionDispositivos.ModoImpresora.TERMICA
                    ? folioSiHuboEfectivo(ventaId) : null;
            boolean cajon = folio != null;
            if (!imprimir && !cajon) return Resultado.omitido("Impresión automática desactivada.");

            ImpresoraTickets impresora = fabrica.apply(c);
            if (imprimir) {
                DocumentoImpresion doc = documentoDeVenta(ventaId, false);
                boolean conCajon = cajon && impresora.soportaCajon();
                impresora.imprimir(doc, c.getCopias(), conCajon);
                if (conCajon) auditarCajonAbierto(cajero, "Venta #" + folio + " (automático al cobrar en efectivo)", null);
                return Resultado.impreso("Ticket impreso.");
            }
            impresora.abrirCajon();
            auditarCajonAbierto(cajero, "Venta #" + folio + " (automático al cobrar en efectivo)", null);
            return Resultado.impreso("Cajón abierto.");
        } catch (Throwable e) {
            return Resultado.fallo(mensaje("La venta se guardó, pero no se pudo imprimir el ticket", e));
        }
    }

    /** Cada apertura del cajón queda en la bitácora; si la bitácora falla, el cajón ya se abrió: no se avisa como error */
    private void auditarCajonAbierto(Cajero cajero, String detalle, Autorizacion aut) {
        try {
            auditoriaService.registrar(cajero, ACCION_CAJON_ABIERTO, detalle, aut);
        } catch (RuntimeException e) {
            // sin bitácora no se detiene la venta ni la impresión
        }
    }

    /**
     * Reimprime una venta (VER_HISTORIAL). El permiso y la bitácora se atienden en el
     * hilo que llama; el trabajo de impresión corre aparte.
     */
    public CompletableFuture<Resultado> reimprimirVentaAsync(Long ventaId) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        auditoriaService.registrar(sesionService.getCajeroActual(), "TICKET_REIMPRESO", "Venta #" + ventaId, (Cajero) null);
        return CompletableFuture.supplyAsync(() -> reimprimirVenta(ventaId), hilo);
    }

    /**
     * Reimprime el ÚLTIMO ticket cobrado en esta caja (se rompió el papel, el
     * cliente lo pidió después). Requiere VER_HISTORIAL o autorización de un
     * administrador; queda en la bitácora. Sin ventas en la caja, devuelve FALLO con aviso.
     */
    public CompletableFuture<Resultado> reimprimirUltimaVentaAsync() {
        return reimprimirUltimaVentaAsync(null);
    }

    /**
     * Igual que {@link #reimprimirUltimaVentaAsync()}, pero exige VER_HISTORIAL (HIS-05)
     * o una autorización de administrador: reimprimir es la vía clásica para
     * "comprobar" con un ticket viejo una compra que no se pagó.
     */
    public CompletableFuture<Resultado> reimprimirUltimaVentaAsync(Autorizacion autorizacion) {
        Cajero cajero = sesionService.requerirSesion();
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL, autorizacion);
        Venta ultima = ventaRepo.findUltimasDeCaja(identidadCaja.numero(), org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        if (ultima == null) {
            return CompletableFuture.completedFuture(Resultado.fallo("Todavía no hay ventas cobradas en esta caja."));
        }
        auditoriaService.registrar(cajero, "TICKET_REIMPRESO", "Última venta #" + ultima.getId(), autorizacion);
        // La autorización es de un solo uso: si cubrió esta reimpresión, ya no sirve para otra
        Autorizacion usada = sesionService.autorizacionUsada(Permiso.VER_HISTORIAL,
                autorizacion == null ? null : java.util.List.of(autorizacion));
        if (usada != null) usada.consumir();
        final Long id = ultima.getId();
        return CompletableFuture.supplyAsync(() -> reimprimirVenta(id), hilo);
    }

    Resultado reimprimirVenta(Long ventaId) {
        try {
            ConfiguracionDispositivos c = dispositivos.obtener();
            if (c.getModoImpresora() == ConfiguracionDispositivos.ModoImpresora.NINGUNA) {
                return Resultado.fallo("No hay impresora configurada (Configuración → Impresora de tickets).");
            }
            DocumentoImpresion doc = documentoDeVenta(ventaId, true);
            fabrica.apply(c).imprimir(doc, 1, false);
            return Resultado.impreso("Ticket reimpreso.");
        } catch (Throwable e) {
            return Resultado.fallo(mensaje("No se pudo reimprimir el ticket", e));
        }
    }

    /** Folio de la venta si se pagó (al menos en parte) en efectivo; null si no hubo efectivo o no existe */
    private String folioSiHuboEfectivo(Long ventaId) {
        TransactionTemplate tx = new TransactionTemplate(transacciones);
        tx.setReadOnly(true);   // solo lee: no debe esperar al bloqueo de escritura (corre tras cada cobro)
        return tx.execute(st -> {
            Venta v = ventaRepo.findConDetallesById(ventaId).orElse(null);
            if (v == null) return null;
            ventaRepo.cargarPagos(List.of(v));
            boolean efectivo = v.getPagos().stream().anyMatch(p -> p.getFormaPago() == Venta.FormaPago.EFECTIVO && p.getMonto() > 0)
                    || (v.getPagos().isEmpty() && v.getFormaPago() == Venta.FormaPago.EFECTIVO);
            return efectivo ? v.getFolioMostrado() : null;
        });
    }

    private DocumentoImpresion documentoDeVenta(Long ventaId, boolean reimpresion) {
        Configuracion cfg = configuracionService.obtener();
        TransactionTemplate tx = new TransactionTemplate(transacciones);
        tx.setReadOnly(true);
        return tx.execute(st -> {
            Venta v = ventaRepo.findConDetallesById(ventaId)
                    .orElseThrow(() -> new NegocioException("La venta #" + ventaId + " ya no existe."));
            ventaRepo.cargarPagos(List.of(v));
            return ticketService.documentoVenta(v, cfg, reimpresion);
        });
    }

    // ── Corte de caja ─────────────────────────────────────────────────────

    /** ¿Hay una impresora configurada? (para ofrecer o no "imprimir comprobante") */
    public boolean hayImpresora() {
        return dispositivos.obtener().getModoImpresora() != ConfiguracionDispositivos.ModoImpresora.NINGUNA;
    }

    /** Imprime el reporte del turno en curso (HACER_CORTE o VER_GANANCIAS; la ganancia solo con VER_GANANCIAS) */
    public CompletableFuture<Resultado> imprimirCorteAsync() {
        return imprimirCorteAsync(true);
    }

    /** @param turno true: turno en curso; false: día completo */
    public CompletableFuture<Resultado> imprimirCorteAsync(boolean turno) {
        // Permisos, datos y bitácora en el hilo que llama (estadoDe ya verifica los permisos)
        final DocumentoImpresion doc;
        try {
            doc = documentoDeCorte(turno);
        } catch (PermisoRequeridoException e) {
            throw e;
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Resultado.fallo(mensaje("No se pudo armar el reporte del corte", e)));
        }
        auditoriaService.registrar(sesionService.getCajeroActual(), "CORTE_IMPRESO",
                (turno ? "Turno en curso del " : "Día ") + LocalDate.now(), (Cajero) null);
        final ConfiguracionDispositivos config = dispositivos.obtener();
        return CompletableFuture.supplyAsync(() -> imprimirDocumento(doc, config, "No se pudo imprimir el corte"), hilo);
    }

    /** Imprime (o reimprime) un corte guardado con el resumen del periodo que cubrió */
    public CompletableFuture<Resultado> imprimirCorteGuardadoAsync(Corte corte) {
        final DocumentoImpresion doc;
        try {
            CajaService.EstadoCaja e = cajaService.estadoDelCorte(corte);
            doc = ticketService.documentoCorte(e, corte, configuracionService.obtener(), sesionService.getNombreCajero());
        } catch (PermisoRequeridoException e) {
            throw e;
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Resultado.fallo(mensaje("No se pudo armar el reporte del corte", e)));
        }
        auditoriaService.registrar(sesionService.getCajeroActual(), "CORTE_IMPRESO", "Corte #" + corte.getId(), (Cajero) null);
        final ConfiguracionDispositivos config = dispositivos.obtener();
        return CompletableFuture.supplyAsync(() -> imprimirDocumento(doc, config, "No se pudo imprimir el corte"), hilo);
    }

    DocumentoImpresion documentoDeCorte() {
        return documentoDeCorte(true);
    }

    DocumentoImpresion documentoDeCorte(boolean turno) {
        Configuracion cfg = configuracionService.obtener();
        CajaService.EstadoCaja e = turno ? cajaService.estadoTurnoActual() : cajaService.estadoDelDia();
        // En el turno en curso el corte anterior es el punto de partida, no el resultado: no se repite
        return ticketService.documentoCorte(e, null, cfg, sesionService.getNombreCajero());
    }

    // ── Entradas y salidas de efectivo ───────────────────────────────────

    /** Comprobante de un movimiento de efectivo (quien puede ver los movimientos; auditado) */
    public CompletableFuture<Resultado> imprimirMovimientoCajaAsync(Long movimientoId) {
        me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja m = movimientoCajaService.obtener(movimientoId);
        DocumentoImpresion doc = ticketService.documentoMovimientoCaja(m, configuracionService.obtener());
        return imprimirDocumentoAsync(doc, "EFECTIVO_COMPROBANTE_IMPRESO", "Movimiento #" + movimientoId);
    }

    // ── Documentos sueltos (orden de compra, prueba) ─────────────────────

    /**
     * Imprime un documento ya armado (orden de compra, etc.). El llamador ya verificó el
     * permiso; la bitácora se escribe aquí, en el hilo que llama.
     */
    public CompletableFuture<Resultado> imprimirDocumentoAsync(DocumentoImpresion doc, String accionAuditoria, String detalle) {
        if (accionAuditoria != null) {
            auditoriaService.registrar(sesionService.getCajeroActual(), accionAuditoria, detalle, (Cajero) null);
        }
        final ConfiguracionDispositivos config = dispositivos.obtener();
        return CompletableFuture.supplyAsync(() -> imprimirDocumento(doc, config, "No se pudo imprimir"), hilo);
    }

    /** La configuración se lee en el hilo que llama: el de impresión no toca la base de datos para esto */
    private Resultado imprimirDocumento(DocumentoImpresion doc, ConfiguracionDispositivos c, String falla) {
        try {
            if (c.getModoImpresora() == ConfiguracionDispositivos.ModoImpresora.NINGUNA) {
                return Resultado.fallo("No hay impresora configurada (Configuración → Impresora de tickets).");
            }
            fabrica.apply(c).imprimir(doc, 1, false);
            return Resultado.impreso("Documento impreso.");
        } catch (Throwable e) {
            return Resultado.fallo(mensaje(falla, e));
        }
    }

    /** Imprime una orden de compra (GESTIONAR_PROVEEDORES; auditado) */
    public CompletableFuture<Resultado> imprimirOrdenCompraAsync(Long ordenId) {
        // obtener() verifica el permiso; el documento se arma aquí, en el hilo que llama
        OrdenCompra orden = ordenCompraService.obtener(ordenId);
        DocumentoImpresion doc = ticketService.documentoOrdenCompra(orden, configuracionService.obtener());
        return imprimirDocumentoAsync(doc, "ORDEN_COMPRA_IMPRESA", "Orden #" + ordenId);
    }

    /** Ticket de prueba con la configuración GUARDADA (ADMIN; auditado) */
    public CompletableFuture<Resultado> imprimirPruebaAsync() {
        sesionService.verificarAdmin();
        Configuracion cfg = configuracionService.obtener();
        int cols = TicketService.columnas(cfg.getTicketAnchoMm());
        DocumentoImpresion doc = new DocumentoImpresion()
                .titulo(cfg.getNombreTienda())
                .centrada("*** PRUEBA DE IMPRESION ***")
                .linea("-".repeat(cols))
                .linea("Acentos: á é í ó ú ñ Ñ ü ¿ ¡")
                .linea("Papel: " + cfg.getTicketAnchoMm() + " mm (" + cols + " columnas)")
                .linea("0123456789".repeat(5).substring(0, cols))
                .linea("-".repeat(cols))
                .centrada("Si lees esto, la impresora funciona.");
        ConfiguracionDispositivos c = dispositivos.obtener();
        return imprimirDocumentoAsync(doc, "IMPRESION_PRUEBA", c.getModoImpresora() + " · " + c.getImpresoraNombre());
    }

    // ── Cajón de dinero ───────────────────────────────────────────────────

    /**
     * Acción de bitácora de cada apertura del cajón: automática al cobrar en efectivo
     * ("Venta #folio") o manual desde Ventas / Configuración ("Manual, …"). Las manuales se
     * anotan al pedirlas (si el pulso falla el cajero ve el aviso); la automática, al mandar el pulso.
     */
    public static final String ACCION_CAJON_ABIERTO = "CAJON_ABIERTO";

    /** Largo máximo del motivo que escribe un cajero al abrir el cajón */
    public static final int MOTIVO_MAXIMO = 120;

    /** Qué pasa con el dinero cuando un cajero sin permiso abre el cajón */
    public enum MovimientoAlAbrir {
        SOLO_CAMBIO("Solo cambio de billetes (no entra ni sale dinero)"),
        ENTRA      ("Entra dinero"),
        SALE       ("Sale dinero");

        private final String etiqueta;
        MovimientoAlAbrir(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
        public boolean mueveDinero() { return this != SOLO_CAMBIO; }
    }

    /**
     * Motivos frecuentes para elegir con un clic. Cada uno sugiere si entra o sale
     * dinero y bajo qué categoría se registra en el corte (null: lo decide el cajero).
     */
    public enum MotivoRapido {
        DAR_CAMBIO     ("Dar cambio al cliente",  MovimientoAlAbrir.SOLO_CAMBIO, null),
        METER_CAMBIO   ("Meter cambio / fondo",   MovimientoAlAbrir.ENTRA, MovimientoCaja.Categoria.CAMBIO),
        RETIRO         ("Retiro de efectivo",     MovimientoAlAbrir.SALE,  MovimientoCaja.Categoria.RETIRO),
        PAGO_PROVEEDOR ("Pago a proveedor",       MovimientoAlAbrir.SALE,  MovimientoCaja.Categoria.PAGO_PROVEEDOR),
        OTRO           ("Otro",                   null, null);

        private final String etiqueta;
        private final MovimientoAlAbrir movimiento;
        private final MovimientoCaja.Categoria categoria;
        MotivoRapido(String etiqueta, MovimientoAlAbrir movimiento, MovimientoCaja.Categoria categoria) {
            this.etiqueta = etiqueta; this.movimiento = movimiento; this.categoria = categoria;
        }
        public String getEtiqueta() { return etiqueta; }
        /** Movimiento sugerido (null: el cajero lo elige) */
        public MovimientoAlAbrir getMovimiento() { return movimiento; }
        public MovimientoCaja.Categoria getCategoria() { return categoria; }
    }

    /**
     * Lo que declara un cajero SIN el permiso ABRIR_CAJON para abrir el cajón:
     * motivo obligatorio, si entra/sale dinero o solo cambia billetes, y el monto
     * cuando se mueve dinero. {@code categoria} es opcional (se infiere del tipo).
     */
    public record AperturaCajon(String motivo, MovimientoAlAbrir movimiento, Double monto,
                                MovimientoCaja.Categoria categoria) {
        public static AperturaCajon soloCambio(String motivo) {
            return new AperturaCajon(motivo, MovimientoAlAbrir.SOLO_CAMBIO, null, null);
        }
        public static AperturaCajon entra(String motivo, double monto) {
            return new AperturaCajon(motivo, MovimientoAlAbrir.ENTRA, monto, null);
        }
        public static AperturaCajon sale(String motivo, double monto) {
            return new AperturaCajon(motivo, MovimientoAlAbrir.SALE, monto, null);
        }
    }

    /**
     * Abre el cajón por el pulso de la impresora SIN pedir nada más. Requiere
     * ABRIR_CAJON (propio o autorizado); queda en la bitácora con quién lo pidió y
     * quién autorizó. El permiso y la bitácora van en el hilo que llama; el pulso, en
     * el de impresión. Si el pulso falla se anota también (CAJON_FALLO).
     */
    public CompletableFuture<Resultado> abrirCajonAsync(Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.ABRIR_CAJON, autorizaciones);
        Autorizacion aut = sesionService.autorizacionUsada(Permiso.ABRIR_CAJON, autorizaciones);
        Cajero cajero = sesionService.getCajeroActual();
        String detalle = aut != null ? "Manual, autorizado por un administrador" : "Manual, con permiso (sin motivo)";
        auditoriaService.registrar(cajero, ACCION_CAJON_ABIERTO, detalle, aut);
        if (aut != null) aut.consumir();
        return pulsoAsync(dispositivos.obtener());
    }

    /**
     * Abre el cajón para un cajero que NO tiene ABRIR_CAJON: en vez de una autorización
     * se le pide el motivo y si entra o sale dinero (con el monto). Si se mueve dinero
     * se registra como entrada/salida de efectivo del turno (sale en el corte) sin exigir
     * MOVIMIENTOS_CAJA; "solo cambio de billetes" no toca el efectivo esperado. Todo queda
     * en la bitácora con el cajero. Quien sí tiene el permiso también puede usarla.
     * Sin impresora térmica configurada no se registra nada: el cajón no se puede abrir.
     * Valida en el hilo que llama ({@link NegocioException}); el pulso va al de impresión.
     */
    public CompletableFuture<Resultado> abrirCajonConMotivoAsync(AperturaCajon a) {
        Cajero cajero = sesionService.requerirSesion();
        ConfiguracionDispositivos config = dispositivos.obtener();
        if (config.getModoImpresora() != ConfiguracionDispositivos.ModoImpresora.TERMICA) {
            return CompletableFuture.completedFuture(Resultado.fallo(
                    "No hay impresora térmica configurada: el cajón solo se abre con el pulso de una impresora ESC/POS "
                    + "(Configuración → Impresora de tickets). No se registró nada."));
        }
        if (a == null || a.motivo() == null || a.motivo().isBlank()) {
            throw new NegocioException("Escribe el motivo por el que abres el cajón (por ejemplo «Dar cambio»).");
        }
        String motivo = a.motivo().trim();
        if (motivo.length() > MOTIVO_MAXIMO) motivo = motivo.substring(0, MOTIVO_MAXIMO);
        if (a.movimiento() == null) {
            throw new NegocioException("Indica si entra dinero, sale dinero o solo cambias billetes.");
        }
        MovimientoCaja movimiento = null;
        if (a.movimiento().mueveDinero()) {
            if (a.monto() == null || !Double.isFinite(a.monto()) || a.monto() <= 0) {
                throw new NegocioException(a.movimiento() == MovimientoAlAbrir.ENTRA
                        ? "Indica cuánto dinero entra." : "Indica cuánto dinero sale.");
            }
            MovimientoCaja.Tipo tipo = a.movimiento() == MovimientoAlAbrir.ENTRA
                    ? MovimientoCaja.Tipo.ENTRADA : MovimientoCaja.Tipo.SALIDA;
            MovimientoCaja.Categoria categoria = a.categoria() != null && a.categoria().getTipo() == tipo ? a.categoria()
                    : (tipo == MovimientoCaja.Tipo.ENTRADA ? MovimientoCaja.Categoria.OTRA_ENTRADA : MovimientoCaja.Categoria.OTRA_SALIDA);
            movimiento = movimientoCajaService.registrarAlAbrirCajon(tipo, categoria, a.monto(), motivo);
        } else if (a.monto() != null && a.monto() > 0) {
            throw new NegocioException("Si solo cambias billetes no hay monto: elige «Entra dinero» o «Sale dinero» si se movió efectivo.");
        }
        String detalle = "Manual, con motivo: «" + motivo + "» · " + (movimiento == null
                ? "solo cambio de billetes"
                : (movimiento.esEntrada() ? "entra " : "sale ") + Moneda.formato(movimiento.getMonto())
                  + " (movimiento #" + movimiento.getId() + ")");
        auditoriaService.registrar(cajero, ACCION_CAJON_ABIERTO, detalle, (Cajero) null);
        return pulsoAsync(config);
    }

    /** Pulso al cajón en el hilo de impresión (la bitácora ya quedó escrita en el hilo que llamó) */
    private CompletableFuture<Resultado> pulsoAsync(ConfiguracionDispositivos config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                fabrica.apply(config).abrirCajon();
                return Resultado.impreso("Cajón abierto.");
            } catch (Throwable e) {
                return Resultado.fallo(mensaje("No se pudo abrir el cajón", e));
            }
        }, hilo);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String mensaje(String prefijo, Throwable e) {
        String detalle = e instanceof ImpresionException || e instanceof NegocioException ? e.getMessage()
                : (e instanceof LinkageError || e instanceof java.awt.HeadlessException
                        ? "el soporte de impresión no está disponible en este equipo"
                        : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        return prefijo + ": " + detalle;
    }
}
