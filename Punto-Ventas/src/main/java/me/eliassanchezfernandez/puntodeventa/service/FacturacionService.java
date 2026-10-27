package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.AlmacenFacturas;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento;
import me.eliassanchezfernandez.puntodeventa.facturacion.ConstructorCfdi;
import me.eliassanchezfernandez.puntodeventa.facturacion.PacException;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi.Formato;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi.Timbrado;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Factura;
import me.eliassanchezfernandez.puntodeventa.model.FacturaVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ClienteFiscalRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionFiscalRepository;
import me.eliassanchezfernandez.puntodeventa.repository.FacturaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Emisión de facturas (CFDI 4.0) de una venta a través del PAC.
 *
 * El timbrado habla con internet, así que NO va dentro de una transacción
 * larga: (1) en una transacción corta se valida y se registra la factura
 * como PENDIENTE con su folio y su referencia (OrderNumber = "V-{id}");
 * (2) fuera de transacción se timbra; (3) en otra transacción corta se marca
 * VIGENTE con el UUID. Si el PAC rechaza de forma definitiva (validación,
 * credenciales) la factura pendiente se elimina: no queda nada a medias. Si
 * no se sabe qué pasó (tiempo agotado, caída), queda PENDIENTE y el siguiente
 * intento consulta al PAC por la referencia antes de volver a timbrar: una
 * venta nunca se timbra dos veces.
 *
 * Los archivos XML y PDF se guardan junto a la base de datos; si no se
 * pudieron bajar en el momento se bajan la primera vez que se piden.
 */
@Service
public class FacturacionService {

    static final List<String> ESTADOS_QUE_BLOQUEAN = List.of(Factura.Estado.VIGENTE.name(),
            Factura.Estado.PENDIENTE.name(), Factura.Estado.CANCELACION_EN_PROCESO.name());

    @Autowired private FacturaRepository facturaRepo;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private ClienteFiscalRepository clienteRepo;
    @Autowired private ConfiguracionFiscalRepository configFiscalRepo;
    @Autowired private ConfiguracionFiscalService fiscalService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private RutasApp rutas;
    @Autowired private PlatformTransactionManager txManager;

    private volatile AlmacenFacturas almacen;

    // ── Consultas ────────────────────────────────────────────────────────

    /** Factura vigente, pendiente o en cancelación que cubre la venta (individual o global) */
    public Optional<Factura> facturaQueBloquea(Long ventaId) {
        if (ventaId == null) return Optional.empty();
        return facturaRepo.findDeVentaEnEstados(ventaId, ESTADOS_QUE_BLOQUEAN).stream().findFirst();
    }

    public List<Factura> facturasDeVenta(Long ventaId) {
        return facturaRepo.findDeVenta(ventaId);
    }

    /** Factura (vigente, pendiente o en proceso) de cada una de las ventas dadas; una sola consulta */
    public java.util.Map<Long, Factura> facturasDeVentas(java.util.Collection<Long> ventaIds) {
        java.util.Map<Long, Factura> r = new java.util.HashMap<>();
        if (ventaIds == null || ventaIds.isEmpty()) return r;
        for (FacturaVenta fv : facturaRepo.facturasDeVentas(ventaIds, ESTADOS_QUE_BLOQUEAN)) r.put(fv.getVentaId(), fv.getFactura());
        return r;
    }

    @Transactional(readOnly = true)
    public List<Factura> facturasEntre(LocalDate desde, LocalDate hasta) {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        if (desde == null) desde = LocalDate.now();
        if (hasta == null) hasta = desde;
        if (hasta.isBefore(desde)) throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        List<Factura> lista = facturaRepo.findEntre(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
        lista.forEach(f -> f.getVentas().size());   // inicializa dentro de la transacción
        return lista;
    }

    public Optional<Factura> porId(Long id) {
        return id == null ? Optional.empty() : facturaRepo.findConVentasById(id);
    }

    public AlmacenFacturas almacen() {
        AlmacenFacturas a = almacen;
        if (a == null) almacen = a = new AlmacenFacturas(rutas.carpetaDatos());
        return a;
    }

    // ── Facturar una venta ───────────────────────────────────────────────

    /**
     * Timbra la venta para el cliente fiscal dado. Hace red: llamar fuera del
     * hilo de JavaFX. Requiere FACTURAR.
     * @param usoCfdi      c_UsoCFDI (null = el preferido del cliente)
     * @param formaPagoSat c_FormaPago (null = la del pago mayor de la venta)
     */
    public Factura facturarVenta(Long ventaId, Long clienteFiscalId, String usoCfdi, String formaPagoSat) {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        Cajero quien = sesionService.requerirSesion();
        ConfiguracionFiscal cfg = fiscalService.obtener();
        if (!cfg.estaLista()) {
            throw new NegocioException("Falta configurar el emisor o las credenciales del PAC en Configuración → Facturación.");
        }
        ProveedorCfdi pac = fiscalService.proveedor();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // 1) Transacción corta: validar, armar el comprobante y dejar la factura PENDIENTE
        Preparada prep = tx.execute(st -> preparar(ventaId, clienteFiscalId, usoCfdi, formaPagoSat, cfg, quien));

        // 2) Red: si ya había un intento sin respuesta, preguntar al PAC antes de volver a timbrar
        Timbrado timbrado;
        try {
            Optional<Timbrado> previo = prep.reintento() ? pac.buscarPorNumeroOrden(prep.documento().numeroOrden()) : Optional.empty();
            timbrado = previo.isPresent() ? previo.get() : pac.timbrar(prep.documento());
        } catch (PacException e) {
            return fallo(prep.factura().getId(), e, quien, tx);
        }

        // 3) Transacción corta: vigente con UUID
        Factura vigente = tx.execute(st -> confirmar(prep.factura().getId(), timbrado, cfg, quien));
        descargarArchivos(vigente, pac);
        return facturaRepo.findConVentasById(vigente.getId()).orElse(vigente);
    }

    private record Preparada(Factura factura, CfdiDocumento documento, boolean reintento) { }

    private Preparada preparar(Long ventaId, Long clienteFiscalId, String usoCfdi, String formaPagoSat,
                               ConfiguracionFiscal cfg, Cajero quien) {
        Venta venta = ventaRepo.findConDetallesById(ventaId)
                .orElseThrow(() -> new NegocioException("La venta ya no existe."));
        ventaRepo.cargarPagos(List.of(venta));
        if (venta.isCancelada()) throw new NegocioException("La venta #" + venta.getId() + " está cancelada; no se puede facturar.");
        if (venta.getDetalles().isEmpty()) throw new NegocioException("La venta no tiene renglones.");
        ClienteFiscal cliente = clienteRepo.findById(clienteFiscalId)
                .orElseThrow(() -> new NegocioException("Elige o registra el cliente fiscal (receptor)."));

        String uso = usoCfdi == null || usoCfdi.isBlank() ? cliente.getUsoCfdi() : usoCfdi.trim().toUpperCase();
        if (!CatalogoSat.esUsoCfdi(uso)) throw new NegocioException("El uso de CFDI no es válido.");
        String forma = formaPagoSat == null || formaPagoSat.isBlank() ? ConstructorCfdi.formaPagoSat(venta) : formaPagoSat.trim();
        if (!CatalogoSat.esFormaPago(forma)) throw new NegocioException("La forma de pago del SAT no es válida.");

        // ¿Ya está facturada o en proceso?
        Factura existente = facturaRepo.findDeVentaEnEstados(venta.getId(), ESTADOS_QUE_BLOQUEAN).stream().findFirst().orElse(null);
        boolean reintento = false;
        Factura f;
        if (existente != null && !existente.isPendiente()) {
            throw new NegocioException("La venta " + venta.getFolioMostrado() + " ya está facturada"
                    + (existente.esGlobal() ? " en la factura global " : " en la factura ") + existente.getSerieFolio()
                    + (existente.getUuid() != null ? " (UUID " + existente.getUuid() + ")" : "")
                    + ". Para volver a facturarla primero cancela esa factura.");
        } else if (existente != null) {
            f = existente;            // intento anterior sin respuesta: mismo folio y referencia
            reintento = true;
        } else {
            f = new Factura();
            f.setTipoEnum(Factura.Tipo.VENTA);
            f.setVenta(venta);
            f.getVentas().add(new FacturaVenta(f, venta));
            f.setSerie(cfg.getSerie());
            f.setFolio(String.valueOf(siguienteFolio()));
            f.setNumeroOrden(ConstructorCfdi.numeroOrdenDe(venta));
            f.setEmitidaPorId(quien.getId());
            f.setEmitidaPorNombre(quien.getNombreCompleto());
        }
        f.setEstadoEnum(Factura.Estado.PENDIENTE);
        f.setSandbox(cfg.isPacSandbox());
        f.setFecha(LocalDateTime.now());
        f.setReceptorRfc(cliente.getRfc());
        f.setReceptorNombre(cliente.getNombre());
        f.setReceptorEmail(cliente.getEmail());
        f.setUsoCfdi(uso);
        f.setFormaPago(forma);
        f.setUltimoError(null);

        CfdiDocumento doc;
        try {
            doc = ConstructorCfdi.paraVenta(venta, cfg, cliente, uso, forma, f.getFolio(),
                    BigDecimal.valueOf(configuracionService.getTasaIva()));
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        f.setSubtotal(doc.subtotal().doubleValue());
        f.setDescuento(doc.descuento().doubleValue());
        f.setIva(doc.totalImpuestos().doubleValue());
        f.setTotal(doc.total().doubleValue());
        Factura guardada = facturaRepo.save(f);
        return new Preparada(guardada, doc, reintento);
    }

    /** Siguiente folio de la serie (consume el contador; dentro de la transacción de preparar) */
    long siguienteFolio() {
        ConfiguracionFiscal c = configFiscalRepo.findById(ConfiguracionFiscal.ID_UNICO).orElseGet(ConfiguracionFiscal::new);
        long siguiente = c.getFolioUltimo() + 1;
        c.setFolioUltimo(siguiente);
        configFiscalRepo.save(c);
        return siguiente;
    }

    Factura confirmar(Long facturaId, Timbrado t, ConfiguracionFiscal cfg, Cajero quien) {
        Factura f = facturaRepo.findById(facturaId).orElseThrow(() -> new NegocioException("La factura ya no existe."));
        f.setEstadoEnum(Factura.Estado.VIGENTE);
        f.setIdExterno(t.idExterno());
        f.setUuid(t.uuid());
        if (t.serie() != null && !t.serie().isBlank()) f.setSerie(t.serie());
        if (t.folio() != null && !t.folio().isBlank()) f.setFolio(t.folio());
        f.setFechaTimbrado(t.fecha());
        f.setUltimoError(null);
        Factura g = facturaRepo.save(f);
        auditoriaService.registrar(quien, "FACTURA_EMITIDA", descripcion(g) + (g.isSandbox() ? " [SANDBOX]" : ""), (Cajero) null);
        return g;
    }

    /** Rechazo definitivo: se elimina la pendiente; ambiguo: queda pendiente con el error */
    private Factura fallo(Long facturaId, PacException e, Cajero quien, TransactionTemplate tx) {
        String resumen = e.getMessage().length() > 480 ? e.getMessage().substring(0, 477) + "..." : e.getMessage();
        if (e.esDefinitivo()) {
            tx.executeWithoutResult(st -> {
                facturaRepo.findById(facturaId).ifPresent(f -> {
                    auditoriaService.registrar(quien, "FACTURA_RECHAZADA", "Folio " + f.getSerieFolio() + " — " + resumen, (Cajero) null);
                    facturaRepo.delete(f);
                });
            });
            throw new NegocioException("El PAC rechazó la factura:\n" + e.getMessage());
        }
        tx.executeWithoutResult(st -> facturaRepo.findById(facturaId).ifPresent(f -> {
            f.setUltimoError(resumen);
            facturaRepo.save(f);
        }));
        throw new NegocioException(e.getMessage() + "\n\nNo se sabe si la factura se timbró. Cuando vuelva el internet, "
                + "vuelve a facturar esta venta: el sistema consultará al PAC antes de timbrar para no duplicarla.");
    }

    // ── Archivos ─────────────────────────────────────────────────────────

    /** Baja XML y PDF si faltan; un fallo solo se anota (la factura ya es válida) */
    void descargarArchivos(Factura f, ProveedorCfdi pac) {
        String error = null;
        for (Formato formato : Formato.values()) {
            String actual = formato == Formato.PDF ? f.getArchivoPdf() : f.getArchivoXml();
            if (almacen().existe(actual)) continue;
            try {
                ProveedorCfdi.Archivo a = pac.descargar(f.getIdExterno(), formato);
                String ruta = almacen().guardar(f.getFechaTimbrado() != null ? f.getFechaTimbrado().getYear() : LocalDate.now().getYear(),
                        f.getSerie(), f.getFolio(), f.getUuid(), formato, a.contenido());
                if (formato == Formato.PDF) f.setArchivoPdf(ruta); else f.setArchivoXml(ruta);
            } catch (PacException | IOException e) {
                error = "No se pudo guardar el " + formato + ": " + e.getMessage();
            }
        }
        final String err = error;
        new TransactionTemplate(txManager).executeWithoutResult(st -> facturaRepo.findById(f.getId()).ifPresent(g -> {
            g.setArchivoPdf(f.getArchivoPdf());
            g.setArchivoXml(f.getArchivoXml());
            g.setUltimoError(err);
            facturaRepo.save(g);
        }));
    }

    /**
     * Ruta del PDF o XML de la factura; si no está en disco lo baja del PAC
     * (hace red). Requiere FACTURAR.
     */
    public Path archivo(Long facturaId, Formato formato) {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        Factura f = facturaRepo.findById(facturaId).orElseThrow(() -> new NegocioException("La factura ya no existe."));
        if (f.getIdExterno() == null) throw new NegocioException("La factura " + f.getSerieFolio() + " no está timbrada.");
        String ruta = formato == Formato.PDF ? f.getArchivoPdf() : f.getArchivoXml();
        if (!almacen().existe(ruta)) {
            descargarArchivos(f, fiscalService.proveedor());
            ruta = formato == Formato.PDF ? f.getArchivoPdf() : f.getArchivoXml();
            if (!almacen().existe(ruta)) {
                throw new NegocioException(f.getUltimoError() != null ? f.getUltimoError() : "No se pudo obtener el archivo.");
            }
        }
        return almacen().resolver(ruta);
    }

    // ── Correo ───────────────────────────────────────────────────────────

    /** Envía la factura por correo mediante el PAC (hace red). Requiere FACTURAR. */
    public void enviarPorCorreo(Long facturaId, String email) {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        Cajero quien = sesionService.requerirSesion();
        Factura f = facturaRepo.findById(facturaId).orElseThrow(() -> new NegocioException("La factura ya no existe."));
        if (f.getIdExterno() == null) throw new NegocioException("La factura no está timbrada.");
        String destino = email == null || email.isBlank() ? f.getReceptorEmail() : email.trim();
        if (destino == null || destino.isBlank() || !destino.contains("@")) {
            throw new NegocioException("Indica un correo electrónico válido.");
        }
        try {
            fiscalService.proveedor().enviarPorCorreo(f.getIdExterno(), destino);
        } catch (PacException e) {
            throw new NegocioException("No se pudo enviar el correo: " + e.getMessage());
        }
        new TransactionTemplate(txManager).executeWithoutResult(st -> facturaRepo.findById(facturaId).ifPresent(g -> {
            g.setEmailEnviado(LocalDateTime.now());
            g.setReceptorEmail(destino);
            facturaRepo.save(g);
            auditoriaService.registrar(quien, "FACTURA_ENVIADA", descripcion(g) + " → " + destino, (Cajero) null);
        }));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    static String descripcion(Factura f) {
        return "Factura " + f.getSerieFolio() + (f.esGlobal() ? " (global)" : f.getVenta() != null ? " de la venta " + f.getVenta().getFolioMostrado() : "")
                + " a " + f.getReceptorRfc() + " por " + me.eliassanchezfernandez.puntodeventa.util.Moneda.formato(f.getTotal())
                + (f.getUuid() != null ? " UUID " + f.getUuid() : "");
    }
}
