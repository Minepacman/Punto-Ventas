package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Devolucion;
import me.eliassanchezfernandez.puntodeventa.model.DevolucionDetalle;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.DetalleVentaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.DevolucionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.FacturaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.util.CalculoIva;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Devoluciones parciales: el cliente regresa algunos artículos de una venta cobrada y se le
 * reembolsa lo que pagó por ellos. A diferencia de cancelar (toda la venta deja de contar), la
 * venta sigue activa y la devolución se registra aparte.
 *
 * Reglas:
 *  - Permiso DEVOLVER_VENTAS o la autorización de un solo uso de un administrador; dentro del
 *    mismo plazo que la cancelación (el vigente cuando se hizo la venta); motivo obligatorio.
 *  - No en ventas canceladas ni facturadas (CFDI vigente o pendiente): la nota de crédito (CFDI
 *    de egreso) no está implementada, así que la devolución se haría fuera del SAT.
 *  - Por renglón: hasta lo vendido menos lo ya devuelto (decimales solo en granel / producto
 *    común). El reembolso es lo que de verdad se cobró por unidad (importe del renglón ÷ cantidad,
 *    con descuento, mayoreo o promoción ya aplicados); al devolver lo último del renglón se
 *    reembolsa exactamente lo que falta, para que la suma nunca pase del importe cobrado. El IVA
 *    del reembolso se desglosa a la tasa guardada en el renglón (0 %, 16 %, exento).
 *  - Mercancía: regresa al inventario (movimiento DEVOLUCION) o, si viene dañada/caducada, se
 *    registra como merma (DEVOLUCION + AJUSTE "Merma", la existencia no cambia). El producto
 *    común y los productos sin inventario no tocan existencias.
 *  - Reembolso: en efectivo, como salida de caja «Devolución al cliente» de esta caja (resta en
 *    el efectivo esperado del corte). Vales o notas de crédito no existen todavía.
 *  - Varias cajas: primero se bloquea la fila de la venta con un UPDATE condicionado a que siga
 *    activa (dos cajas que devuelven de la misma venta, o una que devuelve mientras otra cancela,
 *    se forman); luego cada renglón se anota con un UPDATE condicionado a no rebasar lo vendido;
 *    los productos se tocan en orden de id, como al cobrar y cancelar.
 *  - El cupo de promociones "hasta agotar" NO se devuelve en una devolución parcial (sí al cancelar).
 */
@Service
public class DevolucionService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final double TOLERANCIA = 0.0005;

    @Autowired private VentaRepository ventaRepo;
    @Autowired private DetalleVentaRepository detalleRepo;
    @Autowired private DevolucionRepository devolucionRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private FacturaRepository facturaRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private ProductoService productoService;
    @Autowired private VentaService ventaService;
    @Autowired private MovimientoCajaService movimientoCajaService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private IdentidadCaja identidadCaja;
    @Autowired private RutasApp rutas;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    /** Un renglón a devolver: cuál, cuánto y si regresa al anaquel (false = dañado/caducado) */
    public record Linea(Long detalleId, double cantidad, boolean regresaAInventario) { }

    /** Qué se devuelve de una venta y por qué */
    public record Solicitud(Long ventaId, List<Linea> lineas, String motivo, Devolucion.FormaReembolso forma) {
        public Solicitud(Long ventaId, List<Linea> lineas, String motivo) {
            this(ventaId, lineas, motivo, Devolucion.FormaReembolso.EFECTIVO);
        }
    }

    /** Lo que muestra la ventana "Devolver artículos": la venta al día y lo ya devuelto */
    public record Estado(Venta venta, List<Devolucion> devoluciones) { }

    // ── Consulta ──────────────────────────────────────────────────────────

    /** Venta con sus renglones (cantidades devueltas al día) y sus devoluciones (VER_HISTORIAL o DEVOLVER_VENTAS) */
    @Transactional(readOnly = true)
    public Estado estadoDe(Long ventaId) {
        verificarLectura();
        Venta v = ventaRepo.findConDetallesById(ventaId)
                .orElseThrow(() -> new NegocioException("La venta ya no existe."));
        return new Estado(v, devolucionRepo.findConDetallesByVentaId(ventaId));
    }

    /** Una devolución con sus renglones (para el comprobante) */
    @Transactional(readOnly = true)
    public Devolucion obtener(Long devolucionId) {
        verificarLectura();
        return devolucionRepo.findConDetallesById(devolucionId)
                .orElseThrow(() -> new NegocioException("La devolución ya no existe."));
    }

    /**
     * Por qué no se puede devolver nada de la venta (null = sí se puede), sin exigir el permiso:
     * la pantalla lo usa para avisar antes de abrir la ventana.
     */
    @Transactional(readOnly = true)
    public String motivoParaNoDevolver(Venta v) {
        if (v == null) return "Selecciona una venta.";
        if (v.isCancelada()) return "La venta #" + v.getId() + " está cancelada: ya no hay nada que devolver.";
        String facturada = mensajeSiFacturada(v.getId());
        if (facturada != null) return facturada;
        LocalDateTime limite = v.getLimiteCancelacion();
        if (LocalDateTime.now().isAfter(limite)) return mensajeFueraDePlazo(v);
        if (v.estaDevueltaPorCompleto()) return "Ya se devolvieron todos los artículos de la venta #" + v.getId() + ".";
        return null;
    }

    // ── Devolver ──────────────────────────────────────────────────────────

    @Transactional
    public Devolucion devolver(Solicitud s, Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.DEVOLVER_VENTAS, autorizaciones);
        Cajero quien = sesionService.requerirSesion();
        if (s == null || s.ventaId() == null) throw new NegocioException("Indica de qué venta es la devolución.");
        String motivo = s.motivo() == null ? "" : s.motivo().trim();
        if (motivo.isEmpty()) throw new NegocioException("Indica el motivo de la devolución.");
        if (motivo.length() > 250) motivo = motivo.substring(0, 250);
        if (s.forma() != null && s.forma() != Devolucion.FormaReembolso.EFECTIVO) {
            throw new NegocioException("Por ahora el reembolso solo puede ser en efectivo.");
        }
        // Renglones pedidos: sin repetir (se suman) y con cantidad válida
        Map<Long, Linea> pedidas = new LinkedHashMap<>();
        if (s.lineas() != null) {
            for (Linea l : s.lineas()) {
                if (l == null || l.detalleId() == null) continue;
                if (!Double.isFinite(l.cantidad()) || l.cantidad() < 0) {
                    throw new NegocioException("La cantidad a devolver no es válida.");
                }
                if (l.cantidad() <= 0) continue;
                pedidas.merge(l.detalleId(), l, (a, b) -> new Linea(a.detalleId(),
                        VentaService.redondearCantidad(a.cantidad() + b.cantidad()), a.regresaAInventario() && b.regresaAInventario()));
            }
        }
        if (pedidas.isEmpty()) throw new NegocioException("Indica qué artículos y cuántos devuelve el cliente.");
        if (rutas.hayRestauracionPendiente()) {
            throw new NegocioException("Hay un respaldo pendiente de restaurar: cierra y vuelve a abrir el programa "
                    + "antes de registrar devoluciones (no quedarían en la base restaurada).");
        }
        if (rutas.migradaAlServidor()) throw new NegocioException(rutas.mensajeMigrada());

        Venta v = ventaRepo.findConDetallesById(s.ventaId())
                .orElseThrow(() -> new NegocioException("La venta ya no existe."));
        if (v.isCancelada()) {
            throw new NegocioException("La venta #" + v.getId() + " está cancelada: no se puede devolver nada de ella.");
        }
        String facturada = mensajeSiFacturada(v.getId());
        if (facturada != null) throw new NegocioException(facturada);
        if (LocalDateTime.now().isAfter(v.getLimiteCancelacion())) throw new NegocioException(mensajeFueraDePlazo(v));

        // 1) Bloquear la venta (y comprobar que sigue activa EN la base): con varias cajas, quien llegue
        //    después espera aquí y luego ve lo que la primera ya devolvió (o que se canceló)
        LocalDateTime ahora = LocalDateTime.now();
        if (ventaRepo.marcarDevolucion(v.getId(), ahora) == 0) {
            throw new NegocioException("La venta #" + v.getId() + " acaba de cancelarse en otra caja: no se devolvió nada.");
        }
        em.refresh(v);
        for (DetalleVenta d : v.getDetalles()) em.refresh(d);   // cantidades devueltas al día (otra caja pudo devolver)

        Map<Long, DetalleVenta> porId = new LinkedHashMap<>();
        for (DetalleVenta d : v.getDetalles()) porId.put(d.getId(), d);
        Configuracion config = configuracionService.obtener();
        double tasaRespaldo = v.getTasaIva() != null ? v.getTasaIva() : config.getTasaIva();
        Autorizacion aut = sesionService.autorizacionUsada(Permiso.DEVOLVER_VENTAS, autorizaciones);

        Devolucion dev = new Devolucion();
        dev.setFecha(ahora);
        dev.setVentaId(v.getId());
        dev.setVentaFolio(v.getFolioMostrado());
        dev.setMotivo(motivo);
        dev.setFormaReembolso(Devolucion.FormaReembolso.EFECTIVO);
        dev.setCajeroId(quien.getId());
        dev.setCajeroNombre(quien.getNombreCompleto());
        dev.setCaja(identidadCaja.numero());
        if (aut != null && aut.administrador() != null) {
            dev.setAutorizadoPorId(aut.administrador().getId());
            dev.setAutorizadoPor(aut.nombreAdministrador());
        }

        // 2) Cada renglón: validar, calcular el reembolso y anotarlo con el UPDATE condicionado
        double total = 0, ivaTotal = 0;
        Map<Long, double[]> regresan = new TreeMap<>();   // producto → {piezas que regresan, piezas a merma}; por id
        Map<Long, Producto> productos = new LinkedHashMap<>();
        List<String> resumen = new ArrayList<>();
        for (Linea l : pedidas.values()) {
            DetalleVenta d = porId.get(l.detalleId());
            if (d == null) throw new NegocioException("Uno de los artículos no pertenece a la venta #" + v.getId() + ".");
            double cantidad = VentaService.redondearCantidad(l.cantidad());
            validarDecimales(d, cantidad);
            double pendiente = d.getCantidadPorDevolver();
            if (cantidad > pendiente + TOLERANCIA) {
                throw new NegocioException(pendiente <= TOLERANCIA
                        ? "\"" + d.getDescripcionMostrada() + "\" ya se devolvió completo."
                        : String.format("De \"%s\" solo quedan %s por devolver (se vendieron %s).",
                                d.getDescripcionMostrada(), cantidadTexto(pendiente), cantidadTexto(d.getCantidad())));
            }
            boolean esLoUltimo = cantidad >= pendiente - TOLERANCIA;
            double importe = reembolso(d, cantidad, esLoUltimo);
            CalculoIva.Tasa tasa = CalculoIva.tasaGuardada(d, tasaRespaldo);
            double iva = CalculoIva.desglosar(importe, tasa.tasa()).iva();

            if (detalleRepo.devolverSiAlcanza(d.getId(), v.getId(), cantidad, importe) == 0) {
                throw new NegocioException("Otra caja acaba de devolver \"" + d.getDescripcionMostrada()
                        + "\" de esta venta: vuelve a abrir la devolución para ver lo que queda.");
            }

            Producto p = d.getProducto();
            boolean manejaInventario = p != null && p.isUsaInventario() && !d.esProductoComun();
            boolean regresa = manejaInventario && l.regresaAInventario();
            DevolucionDetalle dd = new DevolucionDetalle();
            dd.setDevolucion(dev);
            dd.setDetalleVentaId(d.getId());
            dd.setProductoId(p != null ? p.getId() : null);
            String nombre = d.getDescripcionMostrada();
            dd.setDescripcion(nombre.length() > 150 ? nombre.substring(0, 150) : nombre);
            dd.setCantidad(cantidad);
            dd.setImporte(importe);
            dd.setIva(iva);
            dd.setTasaIva(tasa.tasa());
            dd.setIvaExento(tasa.exento() ? Boolean.TRUE : null);
            dd.setCostoUnitario(d.getCostoUnitarioODefault());
            dd.setRegresaAInventario(regresa);
            dev.getDetalles().add(dd);
            total += importe;
            ivaTotal += iva;
            if (manejaInventario) {
                productos.putIfAbsent(p.getId(), p);
                double[] c = regresan.computeIfAbsent(p.getId(), k -> new double[2]);
                c[regresa ? 0 : 1] += cantidad;
            }
            resumen.add(cantidadTexto(cantidad) + " × " + nombre + " " + Moneda.formato(importe)
                    + (manejaInventario && !regresa ? " (merma)" : ""));
        }
        dev.setTotal(VentaService.redondear(total));
        dev.setIva(VentaService.redondear(ivaTotal));
        Devolucion guardada = devolucionRepo.save(dev);
        ventaRepo.sumarDevuelto(v.getId(), guardada.getTotal());

        // 3) Mercancía: productos en orden de id (el mismo de cobrar y cancelar)
        for (Map.Entry<Long, double[]> e : regresan.entrySet()) {
            Producto p = productos.get(e.getKey());
            double aInventario = VentaService.redondearCantidad(e.getValue()[0]);
            double aMerma = VentaService.redondearCantidad(e.getValue()[1]);
            String origen = "Devolución " + guardada.getFolio() + " de la venta #" + v.getId();
            if (aInventario > 0) {
                productoRepo.sumar(p.getId(), aInventario);   // atómico: no pisa ventas de otras cajas
                double despues = ventaService.releerExistencia(p);
                productoService.registrarMovimiento(p, MovimientoInventario.Tipo.DEVOLUCION, aInventario,
                        VentaService.redondearCantidad(despues - aInventario), despues, origen + " — " + motivo,
                        null, null, v.getId(), aut);
            }
            if (aMerma > 0) {
                // Regresa y sale como merma: la existencia no cambia, pero queda el rastro de la pieza dañada
                double existencia = ventaService.releerExistencia(p);
                double conLaDevuelta = VentaService.redondearCantidad(existencia + aMerma);
                productoService.registrarMovimiento(p, MovimientoInventario.Tipo.DEVOLUCION, aMerma,
                        existencia, conLaDevuelta, origen + " (dañado/caducado) — " + motivo, null, null, v.getId(), aut);
                productoService.registrarMovimiento(p, MovimientoInventario.Tipo.AJUSTE, -aMerma,
                        conLaDevuelta, existencia, "Merma: devuelto dañado/caducado (" + origen + ")", null, null, v.getId(), aut);
            }
        }

        // 4) Reembolso en efectivo: salida de esta caja, cuenta en el corte del turno
        if (guardada.getTotal() > 0.004) {
            MovimientoCaja m = movimientoCajaService.registrarReembolsoDeDevolucion(v.getId(), guardada.getTotal(),
                    "Devolución " + guardada.getFolio() + " de la venta #" + v.getId() + ": " + motivo, aut);
            guardada.setMovimientoCajaId(m.getId());
        }

        auditoriaService.registrar(quien, "VENTA_DEVOLUCION", String.format("%s de la venta #%d — %s — reembolso %s en %s — %s",
                guardada.getFolio(), v.getId(), String.join("; ", resumen), Moneda.formato(guardada.getTotal()),
                guardada.getFormaReembolso().getEtiqueta().toLowerCase(), motivo), aut);
        // Los UPDATE directos no tocan las copias en memoria: se releen para que el resto de la transacción las vea al día
        em.refresh(v);
        for (DetalleVenta d : v.getDetalles()) em.refresh(d);
        if (aut != null) aut.consumir();
        return guardada;
    }

    /**
     * Reembolso de {@code cantidad} piezas de un renglón: lo cobrado por unidad (importe ÷ cantidad,
     * ya con su descuento) × cantidad, a centavos; si es lo último que queda del renglón, exactamente
     * lo que falta reembolsar (así la suma de las devoluciones da el importe cobrado, sin centavos de más).
     */
    public static double reembolso(DetalleVenta d, double cantidad, boolean esLoUltimo) {
        double importe = d.getImporte();
        double yaDevuelto = d.getImporteDevueltoOCero();
        double resta = Math.max(0, VentaService.redondear(importe - yaDevuelto));
        if (esLoUltimo || d.getCantidad() <= 0) return resta;
        double proporcional = VentaService.redondear(importe * cantidad / d.getCantidad());
        return Math.min(resta, Math.max(0, proporcional));
    }

    private static void validarDecimales(DetalleVenta d, double cantidad) {
        boolean admiteDecimales = d.esProductoComun()
                || (d.getProducto() != null && d.getProducto().permiteDecimales())
                || d.getCantidad() != Math.floor(d.getCantidad());
        if (!admiteDecimales && cantidad != Math.floor(cantidad)) {
            throw new NegocioException("\"" + d.getDescripcionMostrada()
                    + "\" se vende por pieza: la cantidad a devolver debe ser un número entero.");
        }
    }

    private String mensajeSiFacturada(Long ventaId) {
        return facturaRepo.findDeVentaEnEstados(ventaId, FacturacionService.ESTADOS_QUE_BLOQUEAN).stream().findFirst()
                .map(f -> String.format("Esta venta está facturada (%s%s): cancela la factura o emite una nota de crédito "
                        + "desde Facturación antes de devolver artículos.", f.getSerieFolio(), f.esGlobal() ? ", global" : ""))
                .orElse(null);
    }

    private static String mensajeFueraDePlazo(Venta v) {
        return String.format("Ya no se pueden devolver artículos de la venta #%d: el plazo de %d horas venció el %s.",
                v.getId(), v.getPlazoCancelacionHorasOPorDefecto(), v.getLimiteCancelacion().format(FMT));
    }

    private void verificarLectura() {
        if (!sesionService.tienePermiso(Permiso.DEVOLVER_VENTAS)) sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
    }

    public static String cantidadTexto(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }
}
