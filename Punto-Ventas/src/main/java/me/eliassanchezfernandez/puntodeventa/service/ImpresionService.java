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
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
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
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private PlatformTransactionManager transacciones;

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
        return CompletableFuture.supplyAsync(() -> despuesDeCobrar(ventaId), hilo);
    }

    public Resultado despuesDeCobrar(Long ventaId) {
        try {
            ConfiguracionDispositivos c = dispositivos.obtener();
            if (c.getModoImpresora() == ConfiguracionDispositivos.ModoImpresora.NINGUNA) {
                return Resultado.omitido("Sin impresora configurada.");
            }
            boolean imprimir = c.isImprimirAutomatico();
            boolean cajon = c.isAbrirCajonEfectivo() && c.getModoImpresora() == ConfiguracionDispositivos.ModoImpresora.TERMICA
                    && huboEfectivo(ventaId);
            if (!imprimir && !cajon) return Resultado.omitido("Impresión automática desactivada.");

            ImpresoraTickets impresora = fabrica.apply(c);
            if (imprimir) {
                DocumentoImpresion doc = documentoDeVenta(ventaId, false);
                impresora.imprimir(doc, c.getCopias(), cajon && impresora.soportaCajon());
                return Resultado.impreso("Ticket impreso.");
            }
            impresora.abrirCajon();
            return Resultado.impreso("Cajón abierto.");
        } catch (Throwable e) {
            return Resultado.fallo(mensaje("La venta se guardó, pero no se pudo imprimir el ticket", e));
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

    private boolean huboEfectivo(Long ventaId) {
        return new TransactionTemplate(transacciones).execute(st -> {
            Venta v = ventaRepo.findConDetallesById(ventaId).orElse(null);
            if (v == null) return false;
            ventaRepo.cargarPagos(List.of(v));
            return v.getPagos().stream().anyMatch(p -> p.getFormaPago() == Venta.FormaPago.EFECTIVO && p.getMonto() > 0)
                    || (v.getPagos().isEmpty() && v.getFormaPago() == Venta.FormaPago.EFECTIVO);
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

    /** Imprime el reporte del corte de hoy (HACER_CORTE o VER_GANANCIAS; la ganancia solo con VER_GANANCIAS) */
    public CompletableFuture<Resultado> imprimirCorteAsync() {
        // Permisos, datos y bitácora en el hilo que llama (resumenDe ya verifica los permisos)
        final DocumentoImpresion doc;
        try {
            doc = documentoDeCorte();
        } catch (PermisoRequeridoException e) {
            throw e;
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Resultado.fallo(mensaje("No se pudo armar el reporte del corte", e)));
        }
        auditoriaService.registrar(sesionService.getCajeroActual(), "CORTE_IMPRESO",
                "Corte del " + LocalDate.now(), (Cajero) null);
        final ConfiguracionDispositivos config = dispositivos.obtener();
        return CompletableFuture.supplyAsync(() -> imprimirDocumento(doc, config, "No se pudo imprimir el corte"), hilo);
    }

    DocumentoImpresion documentoDeCorte() {
        Configuracion cfg = configuracionService.obtener();
        VentaService.ResumenCorte r = ventaService.resumenDe(LocalDate.now());
        double fondo = cajaService.fondoVigenteDelDia(LocalDate.now());
        Corte ultimo = cajaService.cortesDeHoy().stream().findFirst().orElse(null);
        return ticketService.documentoCorte(r, fondo, ultimo, cfg, sesionService.getNombreCajero());
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
     * Abre el cajón por el pulso de la impresora. Requiere ABRIR_CAJON (propio o
     * autorizado); queda en la bitácora con quién lo pidió y quién autorizó.
     * El permiso y la bitácora van en el hilo que llama; el pulso, en el de impresión.
     */
    public CompletableFuture<Resultado> abrirCajonAsync(Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.ABRIR_CAJON, autorizaciones);
        Autorizacion aut = sesionService.autorizacionUsada(Permiso.ABRIR_CAJON, autorizaciones);
        auditoriaService.registrar(sesionService.getCajeroActual(), "CAJON_ABIERTO", "Apertura manual del cajón", aut);
        final ConfiguracionDispositivos config = dispositivos.obtener();
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
