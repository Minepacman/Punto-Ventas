package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.AlmacenFacturas;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento;
import me.eliassanchezfernandez.puntodeventa.facturacion.ConstructorCfdi;
import me.eliassanchezfernandez.puntodeventa.facturacion.PacException;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi.Formato;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi.MotivoCancelacion;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Emisión de facturas (CFDI 4.0) a través del PAC: de una venta y global de
 * público en general.
 *
 * El timbrado habla con internet, así que NO va dentro de una transacción
 * larga: (1) en una transacción corta se valida y se registra la factura
 * como PENDIENTE con su folio y su referencia (OrderNumber); (2) fuera de
 * transacción se timbra; (3) en otra transacción corta se marca VIGENTE con
 * el UUID. Si el PAC rechaza de forma definitiva (validación, credenciales)
 * la factura pendiente se elimina: no queda nada a medias. Si no se sabe qué
 * pasó (tiempo agotado, caída), queda PENDIENTE y el siguiente intento
 * consulta al PAC por la referencia antes de volver a timbrar: una venta
 * nunca se timbra dos veces.
 *
 * Los archivos XML y PDF se guardan junto a la base de datos; si no se
 * pudieron bajar en el momento se bajan la primera vez que se piden.
 */
@Service
public class FacturacionService {

    static final List<String> ESTADOS_QUE_BLOQUEAN = List.of(Factura.Estado.VIGENTE.name(),
            Factura.Estado.PENDIENTE.name(), Factura.Estado.CANCELACION_EN_PROCESO.name());

    private static final DateTimeFormatter FECHA_CORTA = DateTimeFormatter.ofPattern("yyyyMMdd");

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
    public Map<Long, Factura> facturasDeVentas(Collection<Long> ventaIds) {
        Map<Long, Factura> r = new HashMap<>();
        if (ventaIds == null || ventaIds.isEmpty()) return r;
        for (FacturaVenta fv : facturaRepo.facturasDeVentas(ventaIds, ESTADOS_QUE_BLOQUEAN)) r.put(fv.getVentaId(), fv.getFactura());
        return r;
    }

    @Transactional(readOnly = true)
    public List<Factura> facturasEntre(LocalDate desde, LocalDate hasta) {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        validarRango(desde, hasta);
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
        Contexto ctx = iniciar();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Preparada prep = tx.execute(st -> prepararVenta(ventaId, clienteFiscalId, usoCfdi, formaPagoSat, ctx));
        return emitir(prep, ctx, tx);
    }

    private record Contexto(Cajero quien, ConfiguracionFiscal cfg, ProveedorCfdi pac) { }

    private record Preparada(Factura factura, CfdiDocumento documento, boolean reintento) { }

    private Contexto iniciar() {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        Cajero quien = sesionService.requerirSesion();
        ConfiguracionFiscal cfg = fiscalService.obtener();
        if (!cfg.estaLista()) {
            throw new NegocioException("Falta configurar el emisor o las credenciales del PAC en Configuración → Facturación.");
        }
        return new Contexto(quien, cfg, fiscalService.proveedor());
    }

    private Preparada prepararVenta(Long ventaId, Long clienteFiscalId, String usoCfdi, String formaPagoSat, Contexto ctx) {
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
            f.setSerie(ctx.cfg().getSerie());
            f.setFolio(String.valueOf(siguienteFolio()));
            f.setNumeroOrden(ConstructorCfdi.numeroOrdenDe(venta));
            f.setEmitidaPorId(ctx.quien().getId());
            f.setEmitidaPorNombre(ctx.quien().getNombreCompleto());
        }
        f.setEstadoEnum(Factura.Estado.PENDIENTE);
        f.setSandbox(ctx.cfg().isPacSandbox());
        f.setFecha(LocalDateTime.now());
        f.setReceptorRfc(cliente.getRfc());
        f.setReceptorNombre(cliente.getNombre());
        f.setReceptorEmail(cliente.getEmail());
        f.setUsoCfdi(uso);
        f.setFormaPago(forma);
        f.setUltimoError(null);

        CfdiDocumento doc;
        try {
            doc = ConstructorCfdi.paraVenta(venta, ctx.cfg(), cliente, uso, forma, f.getFolio(), tasaPorDefecto());
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        copiarImportes(f, doc);
        return new Preparada(facturaRepo.save(f), doc, reintento);
    }

    // ── Factura global (público en general) ─────────────────────────────

    /** Resultado de la selección de ventas para la factura global */
    public record SeleccionGlobal(List<Venta> ventas, int canceladas, int yaFacturadas, double total) { }

    /**
     * Ventas del periodo que entrarían en la factura global: activas (no
     * canceladas) y que no estén ya en una factura vigente, pendiente o en
     * proceso (individual o global). Requiere FACTURAR.
     */
    @Transactional(readOnly = true)
    public SeleccionGlobal ventasParaGlobal(LocalDate desde, LocalDate hasta) {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        validarRango(desde, hasta);
        List<Venta> todas = ventaRepo.findEntre(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
        int canceladas = 0;
        List<Venta> activas = new ArrayList<>();
        for (Venta v : todas) {
            if (v.isCancelada()) canceladas++; else activas.add(v);
        }
        Set<Long> facturadas = activas.isEmpty() ? Set.of()
                : new HashSet<>(facturaRepo.ventasYaFacturadas(activas.stream().map(Venta::getId).toList(), ESTADOS_QUE_BLOQUEAN));
        List<Venta> elegibles = new ArrayList<>();
        for (Venta v : activas) if (!facturadas.contains(v.getId())) elegibles.add(v);
        elegibles.sort(java.util.Comparator.comparing(Venta::getFecha));
        double total = elegibles.stream().mapToDouble(Venta::getTotal).sum();
        return new SeleccionGlobal(elegibles, canceladas, facturadas.size(), VentaService.redondear(total));
    }

    /**
     * Timbra la factura global del periodo (receptor XAXX010101000, régimen
     * 616, uso S01, CP del emisor). Hace red. Requiere FACTURAR.
     * @param periodicidad c_Periodicidad: 01 diario, 02 semanal, 03 quincenal, 04 mensual, 05 bimestral
     */
    public Factura facturarGlobal(LocalDate desde, LocalDate hasta, String periodicidad) {
        Contexto ctx = iniciar();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Preparada prep = tx.execute(st -> prepararGlobal(desde, hasta, periodicidad, ctx));
        return emitir(prep, ctx, tx);
    }

    private Preparada prepararGlobal(LocalDate desde, LocalDate hasta, String periodicidad, Contexto ctx) {
        validarPeriodo(desde, hasta, periodicidad);
        String numeroOrden = "G-" + desde.format(FECHA_CORTA) + "-" + hasta.format(FECHA_CORTA) + "-" + periodicidad;

        // Un intento anterior sin respuesta para el MISMO periodo se retoma con sus mismas ventas
        Factura pendiente = facturaRepo.findByEstadoOrderByIdAsc(Factura.Estado.PENDIENTE.name()).stream()
                .filter(f -> f.esGlobal() && numeroOrden.equals(f.getNumeroOrden())).findFirst().orElse(null);
        List<Venta> ventas;
        Factura f;
        boolean reintento = pendiente != null;
        if (reintento) {
            f = pendiente;
            ventas = new ArrayList<>();
            for (FacturaVenta fv : f.getVentas()) ventaRepo.findById(fv.getVentaId()).ifPresent(ventas::add);
            ventas.sort(java.util.Comparator.comparing(Venta::getFecha));
            if (ventas.isEmpty()) throw new NegocioException("La factura global pendiente no tiene ventas.");
        } else {
            SeleccionGlobal sel = ventasParaGlobal(desde, hasta);
            ventas = sel.ventas();
            if (ventas.isEmpty()) {
                throw new NegocioException("No hay ventas por facturar en el periodo" + (sel.yaFacturadas() > 0
                        ? " (las " + sel.yaFacturadas() + " ventas activas ya están facturadas)." : "."));
            }
            f = new Factura();
            f.setTipoEnum(Factura.Tipo.GLOBAL);
            for (Venta v : ventas) f.getVentas().add(new FacturaVenta(f, v));
            f.setSerie(ctx.cfg().getSerie());
            f.setFolio(String.valueOf(siguienteFolio()));
            f.setNumeroOrden(numeroOrden);
            f.setEmitidaPorId(ctx.quien().getId());
            f.setEmitidaPorNombre(ctx.quien().getNombreCompleto());
        }
        String meses = mesesDe(periodicidad, hasta);
        String anio = ConstructorCfdi.anioDe(hasta);
        f.setEstadoEnum(Factura.Estado.PENDIENTE);
        f.setSandbox(ctx.cfg().isPacSandbox());
        f.setFecha(LocalDateTime.now());
        f.setReceptorRfc(me.eliassanchezfernandez.puntodeventa.facturacion.Rfc.PUBLICO_EN_GENERAL);
        f.setReceptorNombre("PUBLICO EN GENERAL");
        f.setUsoCfdi(CatalogoSat.USO_SIN_EFECTOS);
        f.setFormaPago("01");
        f.setPeriodicidad(periodicidad);
        f.setMeses(meses);
        f.setAnio(anio);
        f.setPeriodoDesde(desde);
        f.setPeriodoHasta(hasta);
        f.setUltimoError(null);

        CfdiDocumento doc;
        try {
            doc = ConstructorCfdi.global(ventas, ctx.cfg(), periodicidad, meses, anio, f.getFolio(), numeroOrden, tasaPorDefecto());
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        copiarImportes(f, doc);
        return new Preparada(facturaRepo.save(f), doc, reintento);
    }

    /** Periodicidad válida y rango acorde (diario = un día, semanal ≤ 7 días, quincenal ≤ 16, mensual y bimestral dentro del/los mes(es)) */
    static void validarPeriodo(LocalDate desde, LocalDate hasta, String periodicidad) {
        validarRango(desde, hasta);
        if (hasta.isAfter(LocalDate.now())) throw new NegocioException("El periodo no puede terminar en el futuro.");
        if (periodicidad == null || CatalogoSat.PERIODICIDADES.stream().noneMatch(p -> p.clave().equals(periodicidad))) {
            throw new NegocioException("Elige la periodicidad de la factura global (diario, semanal, quincenal, mensual o bimestral).");
        }
        long dias = ChronoUnit.DAYS.between(desde, hasta) + 1;
        switch (periodicidad) {
            case "01" -> { if (dias != 1) throw new NegocioException("Con periodicidad diaria el periodo debe ser un solo día."); }
            case "02" -> { if (dias > 7) throw new NegocioException("Con periodicidad semanal el periodo no puede pasar de 7 días."); }
            case "03" -> { if (dias > 16) throw new NegocioException("Con periodicidad quincenal el periodo no puede pasar de 16 días."); }
            case "04" -> { if (desde.getYear() != hasta.getYear() || desde.getMonth() != hasta.getMonth())
                    throw new NegocioException("Con periodicidad mensual el periodo debe estar dentro de un mismo mes."); }
            case "05" -> { if (desde.getYear() != hasta.getYear() || (desde.getMonthValue() - 1) / 2 != (hasta.getMonthValue() - 1) / 2)
                    throw new NegocioException("Con periodicidad bimestral el periodo debe caer en el mismo bimestre (ene-feb, mar-abr, ...)."); }
            default -> { }
        }
    }

    /** c_Meses: 01-12 el mes en que termina el periodo; bimestral 13-18 (13 = ene-feb ... 18 = nov-dic) */
    static String mesesDe(String periodicidad, LocalDate hasta) {
        if ("05".equals(periodicidad)) return String.valueOf(13 + (hasta.getMonthValue() - 1) / 2);
        return ConstructorCfdi.mesesDe(hasta);
    }

    // ── Emisión común ────────────────────────────────────────────────────

    private Factura emitir(Preparada prep, Contexto ctx, TransactionTemplate tx) {
        Timbrado timbrado;
        try {
            Optional<Timbrado> previo = prep.reintento() ? ctx.pac().buscarPorNumeroOrden(prep.documento().numeroOrden()) : Optional.empty();
            timbrado = previo.isPresent() ? previo.get() : ctx.pac().timbrar(prep.documento());
        } catch (PacException e) {
            return fallo(prep.factura().getId(), e, ctx.quien(), tx);
        }
        Factura vigente = tx.execute(st -> confirmar(prep.factura().getId(), timbrado, ctx.quien()));
        descargarArchivos(vigente, ctx.pac());
        return facturaRepo.findConVentasById(vigente.getId()).orElse(vigente);
    }

    /** Siguiente folio de la serie (consume el contador; dentro de la transacción de preparar) */
    long siguienteFolio() {
        ConfiguracionFiscal c = configFiscalRepo.findById(ConfiguracionFiscal.ID_UNICO).orElseGet(ConfiguracionFiscal::new);
        long siguiente = c.getFolioUltimo() + 1;
        c.setFolioUltimo(siguiente);
        configFiscalRepo.save(c);
        return siguiente;
    }

    Factura confirmar(Long facturaId, Timbrado t, Cajero quien) {
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
            tx.executeWithoutResult(st -> facturaRepo.findById(facturaId).ifPresent(f -> {
                auditoriaService.registrar(quien, "FACTURA_RECHAZADA", "Folio " + f.getSerieFolio() + " — " + resumen, (Cajero) null);
                facturaRepo.delete(f);
            }));
            throw new NegocioException("El PAC rechazó la factura:\n" + e.getMessage());
        }
        tx.executeWithoutResult(st -> facturaRepo.findById(facturaId).ifPresent(f -> {
            f.setUltimoError(resumen);
            facturaRepo.save(f);
        }));
        throw new NegocioException(e.getMessage() + "\n\nNo se sabe si la factura se timbró. Cuando vuelva el internet, "
                + "vuelve a facturar: el sistema consultará al PAC antes de timbrar para no duplicarla.");
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

    // ── Cancelación ──────────────────────────────────────────────────────

    private static final java.util.regex.Pattern UUID_SAT = java.util.regex.Pattern.compile(
            "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$");

    /**
     * Solicita al PAC la cancelación de la factura (hace red). Requiere
     * CANCELAR_FACTURA o autorización de administrador. Con motivo 01 hay que
     * indicar el UUID de la factura que la sustituye. Si el receptor debe
     * aceptar la cancelación queda "en proceso"; mientras tanto la venta sigue
     * bloqueada.
     */
    public Factura cancelarFactura(Long facturaId, MotivoCancelacion motivo, String uuidSustitucion,
                                   Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.CANCELAR_FACTURA, autorizaciones);
        Cajero quien = sesionService.requerirSesion();
        Factura f = facturaRepo.findConVentasById(facturaId).orElseThrow(() -> new NegocioException("La factura ya no existe."));
        if (motivo == null) throw new NegocioException("Indica el motivo de la cancelación (01 a 04).");
        if (f.isCancelada()) throw new NegocioException("La factura " + f.getSerieFolio() + " ya está cancelada.");
        if (f.isPendiente() || f.getIdExterno() == null) {
            throw new NegocioException("La factura " + f.getSerieFolio() + " no está confirmada como timbrada; usa \"Resolver pendiente\".");
        }
        String sustitucion = uuidSustitucion == null ? null : uuidSustitucion.trim().toUpperCase();
        if (sustitucion != null && sustitucion.isEmpty()) sustitucion = null;
        if (motivo.requiereSustitucion() && sustitucion == null) {
            throw new NegocioException("Con el motivo 01 indica el UUID (folio fiscal) de la factura que sustituye a esta.");
        }
        if (sustitucion != null) {
            if (!UUID_SAT.matcher(sustitucion).matches()) throw new NegocioException("El UUID de sustitución no tiene el formato del SAT.");
            if (sustitucion.equals(f.getUuid())) throw new NegocioException("La factura no puede sustituirse a sí misma.");
        }
        if (!motivo.requiereSustitucion()) sustitucion = null;

        ProveedorCfdi.Cancelacion r;
        try {
            r = fiscalService.proveedor().cancelar(f.getIdExterno(), motivo, sustitucion);
        } catch (PacException e) {
            throw new NegocioException("No se pudo cancelar la factura: " + e.getMessage());
        }
        if (!r.aceptada() && !r.enEspera()) {
            throw new NegocioException("El PAC no aceptó la cancelación" + (r.mensaje() != null ? ": " + r.mensaje() : "."));
        }
        Autorizacion aut = sesionService.autorizacionUsada(Permiso.CANCELAR_FACTURA, autorizaciones);
        final String sust = sustitucion;
        Factura g = new TransactionTemplate(txManager).execute(st -> {
            Factura x = facturaRepo.findById(facturaId).orElseThrow(() -> new NegocioException("La factura ya no existe."));
            x.setEstadoEnum(r.enEspera() ? Factura.Estado.CANCELACION_EN_PROCESO : Factura.Estado.CANCELADA);
            x.setFechaCancelacion(LocalDateTime.now());
            x.setMotivoCancelacion(motivo.getClave());
            x.setUuidSustitucion(sust);
            x.setCanceladaPorId(quien.getId());
            x.setCanceladaPorNombre(quien.getNombreCompleto() + (aut != null ? " (autorizó " + aut.nombreAdministrador() + ")" : ""));
            x.setUltimoError(r.enEspera() ? (r.mensaje() != null ? r.mensaje() : "En espera de que el receptor acepte la cancelación.") : null);
            Factura guardada = facturaRepo.save(x);
            auditoriaService.registrar(quien, r.enEspera() ? "FACTURA_CANCELACION_SOLICITADA" : "FACTURA_CANCELADA",
                    descripcion(guardada) + " — motivo " + motivo.getClave() + (sust != null ? " sustituida por " + sust : ""), aut);
            return guardada;
        });
        if (aut != null) aut.consumir();
        return g;
    }

    /**
     * Resuelve una factura PENDIENTE sin volver a timbrar: pregunta al PAC por
     * su referencia; si existe queda VIGENTE, si no se elimina (nunca se
     * timbró). Hace red. Requiere FACTURAR.
     */
    public Optional<Factura> resolverPendiente(Long facturaId) {
        Contexto ctx = iniciar();
        Factura f = facturaRepo.findConVentasById(facturaId).orElseThrow(() -> new NegocioException("La factura ya no existe."));
        if (!f.isPendiente()) throw new NegocioException("La factura " + f.getSerieFolio() + " no está pendiente.");
        Optional<Timbrado> previo;
        try {
            previo = ctx.pac().buscarPorNumeroOrden(f.getNumeroOrden());
        } catch (PacException e) {
            throw new NegocioException("No se pudo consultar al PAC: " + e.getMessage());
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        if (previo.isEmpty()) {
            tx.executeWithoutResult(st -> facturaRepo.findById(facturaId).ifPresent(x -> {
                auditoriaService.registrar(ctx.quien(), "FACTURA_PENDIENTE_DESCARTADA",
                        "Folio " + x.getSerieFolio() + " (" + x.getNumeroOrden() + "): el PAC no la tiene", (Cajero) null);
                facturaRepo.delete(x);
            }));
            return Optional.empty();
        }
        Factura vigente = tx.execute(st -> confirmar(facturaId, previo.get(), ctx.quien()));
        descargarArchivos(vigente, ctx.pac());
        return facturaRepo.findConVentasById(vigente.getId());
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

    private BigDecimal tasaPorDefecto() {
        return BigDecimal.valueOf(configuracionService.getTasaIva());
    }

    private static void copiarImportes(Factura f, CfdiDocumento doc) {
        f.setSubtotal(doc.subtotal().doubleValue());
        f.setDescuento(doc.descuento().doubleValue());
        f.setIva(doc.totalImpuestos().doubleValue());
        f.setTotal(doc.total().doubleValue());
    }

    static void validarRango(LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null) throw new NegocioException("Indica las fechas del periodo.");
        if (hasta.isBefore(desde)) throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
    }

    static String descripcion(Factura f) {
        return "Factura " + f.getSerieFolio() + (f.esGlobal() ? " (global, " + f.getVentas().size() + " ventas)"
                : f.getVenta() != null ? " de la venta " + f.getVenta().getFolioMostrado() : "")
                + " a " + f.getReceptorRfc() + " por " + me.eliassanchezfernandez.puntodeventa.util.Moneda.formato(f.getTotal())
                + (f.getUuid() != null ? " UUID " + f.getUuid() : "");
    }
}
