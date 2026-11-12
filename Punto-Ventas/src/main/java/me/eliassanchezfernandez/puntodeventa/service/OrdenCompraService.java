package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra.Estado;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompraLinea;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.OrdenCompraRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.util.ExportarCsv;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Órdenes de compra a proveedores.
 *
 * Crear, editar, enviar, cancelar, sugerir y exportar: GESTIONAR_PROVEEDORES.
 * Recibir: AJUSTAR_INVENTARIO (propio o autorizado), porque suma al inventario.
 * Todo cambio de estado queda en la bitácora.
 *
 * Recibir aplica TODAS las líneas como entradas de inventario (con su
 * movimiento y el costo de la compra) en UNA transacción: si una línea falla
 * (producto que ya no maneja inventario, proveedor desactivado...) no se
 * aplica ninguna y la orden sigue como estaba.
 */
@Service
public class OrdenCompraService {

    public static final int MAX_LINEAS = 500;

    /** Una línea que pide quien captura la orden */
    public record LineaOrden(Long productoId, double cantidad, double costoUnitario) { }

    /** Una línea que propone "Sugerir pedido" */
    public record LineaSugerida(Producto producto, double cantidad, double costoUnitario, String motivo) { }

    /** Qué productos propone "Sugerir pedido" */
    public enum ModoSugerencia {
        BAJO_MINIMO("Todos los productos bajo el mínimo"),
        BAJO_MINIMO_DEL_PROVEEDOR("Bajo el mínimo que este proveedor ya surtió"),
        BAJO_MINIMO_Y_DEL_PROVEEDOR("Bajo el mínimo + lo que este proveedor ya surtió");

        private final String etiqueta;
        ModoSugerencia(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
        @Override public String toString() { return etiqueta; }
    }

    @Autowired private OrdenCompraRepository ordenRepo;
    @Autowired private ProveedorRepository proveedorRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private ProductoService productoService;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;

    // ── Consultas ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OrdenCompra> listar() {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        return ordenRepo.findTodasConProveedor();
    }

    /** Orden con proveedor y líneas (con producto) ya cargados */
    @Transactional(readOnly = true)
    public OrdenCompra obtener(Long id) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        return cargar(id);
    }

    private OrdenCompra cargar(Long id) {
        return ordenRepo.findConLineasById(id).orElseThrow(() -> new NegocioException("La orden de compra ya no existe."));
    }

    // ── Sugerir pedido ────────────────────────────────────────────────────

    /**
     * Propone qué pedir: los productos en o por debajo de su mínimo (para volver
     * al doble del mínimo) y, según el modo, solo los que este proveedor ya
     * surtió antes, o además los que surtió aunque aún no estén bajos (se pide el
     * mínimo). No guarda nada: la pantalla lo revisa y lo convierte en orden.
     */
    @Transactional(readOnly = true)
    public List<LineaSugerida> sugerirPedido(Long proveedorId, ModoSugerencia modo) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        Proveedor proveedor = proveedorRepo.findById(proveedorId)
                .orElseThrow(() -> new NegocioException("El proveedor ya no existe."));
        ModoSugerencia m = modo != null ? modo : ModoSugerencia.BAJO_MINIMO;

        Set<Long> surtidos = new HashSet<>();
        List<Producto> delProveedor = new ArrayList<>();
        if (m != ModoSugerencia.BAJO_MINIMO) {
            delProveedor = movimientoRepo.productosSurtidosPor(proveedor.getId(),
                    proveedor.getNombre().trim().toLowerCase(), MovimientoInventario.Tipo.ENTRADA);
            for (Producto p : delProveedor) surtidos.add(p.getId());
        }

        Map<Long, LineaSugerida> r = new LinkedHashMap<>();
        for (Producto p : productoRepo.findConStockBajo()) {
            if (!p.isActivo()) continue;
            if (m == ModoSugerencia.BAJO_MINIMO_DEL_PROVEEDOR && !surtidos.contains(p.getId())) continue;
            r.put(p.getId(), new LineaSugerida(p, cantidadParaReponer(p), p.getPrecioCosto(),
                    String.format("Existencia %s, mínimo %s", cantidad(p.getCantidadActual()), cantidad(p.getCantidadMinima()))));
        }
        if (m == ModoSugerencia.BAJO_MINIMO_Y_DEL_PROVEEDOR) {
            for (Producto p : delProveedor) {
                if (!p.isActivo() || !p.isUsaInventario() || r.containsKey(p.getId())) continue;
                r.put(p.getId(), new LineaSugerida(p, redondearPedido(p, Math.max(p.getCantidadMinima(), 1)),
                        p.getPrecioCosto(), "Lo surte este proveedor"));
            }
        }
        return new ArrayList<>(r.values());
    }

    /** Para volver al doble del mínimo (al menos mínimo + 1); piezas enteras si no es a granel */
    static double cantidadParaReponer(Producto p) {
        double objetivo = Math.max(p.getCantidadMinima() * 2, p.getCantidadMinima() + 1);
        return redondearPedido(p, Math.max(1, objetivo - p.getCantidadActual()));
    }

    private static double redondearPedido(Producto p, double cantidad) {
        return p.permiteDecimales() ? Math.round(cantidad * 1000.0) / 1000.0 : Math.ceil(cantidad - 1e-9);
    }

    // ── Altas y cambios ───────────────────────────────────────────────────

    @Transactional
    public OrdenCompra crear(Long proveedorId, List<LineaOrden> lineas, String notas) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        Proveedor proveedor = (proveedorId == null ? java.util.Optional.<Proveedor>empty() : proveedorRepo.findById(proveedorId))
                .orElseThrow(() -> new NegocioException("Elige el proveedor de la orden."));
        if (!proveedor.isActivo()) {
            throw new NegocioException("El proveedor \"" + proveedor.getNombre() + "\" está desactivado.");
        }
        OrdenCompra o = new OrdenCompra();
        o.setProveedor(proveedor);
        o.setCreadaPor(sesionService.getNombreCajero());
        o.setNotas(notasLimpias(notas));
        reemplazarLineas(o, lineas);
        OrdenCompra g = ordenRepo.save(o);
        auditoriaService.registrar(sesionService.getCajeroActual(), "ORDEN_COMPRA_CREADA",
                "Orden #" + g.getId() + " a " + proveedor.getNombre() + ", " + g.getLineas().size() + " línea(s)", (Cajero) null);
        return g;
    }

    /** Cambia las líneas y notas de una orden en BORRADOR */
    @Transactional
    public OrdenCompra actualizar(Long ordenId, List<LineaOrden> lineas, String notas) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        OrdenCompra o = cargar(ordenId);
        if (!o.esEditable()) {
            throw new NegocioException("Solo se puede editar una orden en borrador (esta está " + o.getEstado().getEtiqueta().toLowerCase() + ").");
        }
        o.setNotas(notasLimpias(notas));
        reemplazarLineas(o, lineas);
        OrdenCompra g = ordenRepo.save(o);
        auditoriaService.registrar(sesionService.getCajeroActual(), "ORDEN_COMPRA_EDITADA",
                "Orden #" + g.getId() + ", " + g.getLineas().size() + " línea(s)", (Cajero) null);
        return g;
    }

    @Transactional
    public OrdenCompra marcarEnviada(Long ordenId) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        OrdenCompra o = cargar(ordenId);
        if (o.getEstado() != Estado.BORRADOR) {
            throw new NegocioException("Solo una orden en borrador se puede marcar como enviada.");
        }
        if (o.getLineas().isEmpty()) throw new NegocioException("La orden no tiene productos.");
        o.setEstado(Estado.ENVIADA);
        OrdenCompra g = ordenRepo.save(o);
        auditoriaService.registrar(sesionService.getCajeroActual(), "ORDEN_COMPRA_ENVIADA",
                "Orden #" + g.getId() + " a " + g.getProveedor().getNombre(), (Cajero) null);
        return g;
    }

    @Transactional
    public OrdenCompra cancelar(Long ordenId) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        OrdenCompra o = cargar(ordenId);
        if (o.getEstado() != Estado.BORRADOR && o.getEstado() != Estado.ENVIADA) {
            throw new NegocioException("Una orden " + o.getEstado().getEtiqueta().toLowerCase() + " ya no se puede cancelar.");
        }
        o.setEstado(Estado.CANCELADA);
        OrdenCompra g = ordenRepo.save(o);
        auditoriaService.registrar(sesionService.getCajeroActual(), "ORDEN_COMPRA_CANCELADA",
                "Orden #" + g.getId() + " a " + g.getProveedor().getNombre(), (Cajero) null);
        return g;
    }

    /**
     * Recibe la mercancía: cada línea entra al inventario (con movimiento, costo
     * y proveedor) y la orden queda RECIBIDA, todo en una transacción. Requiere
     * AJUSTAR_INVENTARIO o la autorización de un administrador.
     */
    @Transactional
    public OrdenCompra recibir(Long ordenId, Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        OrdenCompra o = cargar(ordenId);
        if (o.getEstado() == Estado.RECIBIDA) throw new NegocioException("La orden #" + ordenId + " ya fue recibida.");
        if (o.getEstado() == Estado.CANCELADA) throw new NegocioException("La orden #" + ordenId + " está cancelada.");
        if (o.getLineas().isEmpty()) throw new NegocioException("La orden no tiene productos.");

        for (OrdenCompraLinea l : o.getLineas()) {
            try {
                productoService.registrarEntradaDeProveedor(l.getProducto().getId(), l.getCantidad(),
                        o.getProveedor().getId(), "Orden de compra #" + o.getId(), l.getCostoUnitario(), autorizaciones);
            } catch (PermisoRequeridoException e) {
                throw e;
            } catch (NegocioException e) {
                // Se relanza con el producto: la transacción completa se revierte
                throw new NegocioException("No se pudo recibir la orden (no se aplicó ninguna línea): \""
                        + l.getProducto().getDescripcion() + "\" — " + e.getMessage());
            }
        }
        o.setEstado(Estado.RECIBIDA);
        o.setFechaRecepcion(LocalDateTime.now());
        o.setRecibidaPor(sesionService.getNombreCajero());
        OrdenCompra g = ordenRepo.save(o);
        Autorizacion aut = sesionService.autorizacionUsada(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        auditoriaService.registrar(sesionService.getCajeroActual(), "ORDEN_COMPRA_RECIBIDA",
                "Orden #" + g.getId() + " de " + g.getProveedor().getNombre() + ": " + g.getLineas().size()
                        + " línea(s), " + g.getTotal() + " en compras", aut);
        return g;
    }

    // ── Exportar ──────────────────────────────────────────────────────────

    public static final List<String> ENCABEZADOS_CSV = List.of("Código", "Producto", "Cantidad", "Costo unitario", "Importe");

    /** Texto CSV de la orden (con neutralización de fórmulas): una fila por línea y una de total */
    @Transactional(readOnly = true)
    public String aCsv(Long ordenId) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        return ExportarCsv.generar(ENCABEZADOS_CSV, filas(cargar(ordenId)));
    }

    /** Filas (como texto) de las líneas de la orden más el total, para CSV, Excel o PDF */
    public static List<List<String>> filas(OrdenCompra o) {
        List<List<String>> filas = new ArrayList<>();
        for (OrdenCompraLinea l : o.getLineas()) {
            filas.add(List.of(l.getProducto().getCodigoBarras(), l.getProducto().getDescripcion(), cantidad(l.getCantidad()),
                    String.format("%.2f", l.getCostoUnitario()), String.format("%.2f", l.getImporte())));
        }
        filas.add(List.of("", "TOTAL", "", "", String.format("%.2f", o.getTotal())));
        return filas;
    }

    /** Guarda la orden como CSV (UTF-8 con BOM, para Excel) y lo anota en la bitácora */
    @Transactional(readOnly = true)
    public void exportarCsv(Long ordenId, Path destino) throws IOException {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        OrdenCompra o = cargar(ordenId);
        ExportarCsv.escribir(destino, ENCABEZADOS_CSV, filas(o));
        auditoriaService.registrar(sesionService.getCajeroActual(), "ORDEN_COMPRA_EXPORTADA",
                "Orden #" + o.getId() + " a CSV", (Cajero) null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Valida las líneas (producto con inventario, cantidad y costo), junta repetidos y las deja en la orden */
    private void reemplazarLineas(OrdenCompra o, List<LineaOrden> lineas) {
        if (lineas == null || lineas.isEmpty()) throw new NegocioException("Agrega al menos un producto a la orden.");
        if (lineas.size() > MAX_LINEAS) throw new NegocioException("Una orden admite máximo " + MAX_LINEAS + " productos.");
        Map<Long, double[]> juntas = new LinkedHashMap<>();   // productoId → [cantidad, costo]
        for (LineaOrden l : lineas) {
            if (l == null || l.productoId() == null) throw new NegocioException("Hay una línea sin producto.");
            if (!Double.isFinite(l.cantidad()) || l.cantidad() <= 0) {
                throw new NegocioException("La cantidad de cada producto debe ser mayor a cero.");
            }
            if (!Double.isFinite(l.costoUnitario()) || l.costoUnitario() < 0) {
                throw new NegocioException("El costo de cada producto no puede ser negativo.");
            }
            double[] a = juntas.computeIfAbsent(l.productoId(), k -> new double[] { 0, l.costoUnitario() });
            a[0] += l.cantidad();
            a[1] = l.costoUnitario();   // gana el último costo capturado
        }
        List<OrdenCompraLinea> nuevas = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : juntas.entrySet()) {
            Producto p = productoRepo.findById(e.getKey())
                    .orElseThrow(() -> new NegocioException("Un producto de la orden ya no existe."));
            if (!p.isUsaInventario()) {
                throw new NegocioException("\"" + p.getDescripcion() + "\" no maneja inventario y no se puede pedir.");
            }
            if (!p.isActivo()) {
                throw new NegocioException("\"" + p.getDescripcion() + "\" está desactivado.");
            }
            ProductoService.validarDecimales(p, e.getValue()[0]);
            OrdenCompraLinea l = new OrdenCompraLinea();
            l.setOrden(o);
            l.setProducto(p);
            l.setCantidad(Math.round(e.getValue()[0] * 1000.0) / 1000.0);
            l.setCostoUnitario(Math.round(e.getValue()[1] * 100.0) / 100.0);
            nuevas.add(l);
        }
        o.getLineas().clear();
        o.getLineas().addAll(nuevas);
    }

    private static String notasLimpias(String notas) {
        if (notas == null || notas.isBlank()) return null;
        ConfiguracionService.validarLargo(notas.trim(), 300, "Las notas", true);
        return notas.trim();
    }

    static String cantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }
}
