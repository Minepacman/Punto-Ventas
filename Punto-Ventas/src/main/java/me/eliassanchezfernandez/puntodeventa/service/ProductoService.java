package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.HistorialPrecio;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.DetalleVentaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.HistorialPrecioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.PromocionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.UnidadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Catálogo de productos, departamentos e inventario.
 *
 * Las validaciones de negocio viven aquí (no en los controladores) y
 * se reportan con NegocioException, cuyo mensaje se muestra al usuario.
 *
 * Permisos (USR-13, PRD-11, PRD-17, INV-11):
 *  - EDITAR_PRODUCTOS   → crear/editar/eliminar/desactivar productos y departamentos
 *  - CAMBIAR_PRECIOS    → modificar precios de un producto existente
 *  - AJUSTAR_INVENTARIO → entradas y ajustes de existencia
 * Todo cambio de existencia queda en la bitácora de inventario (INV-07) y
 * todo cambio de precio en el historial de precios (PRD-11).
 */
@Service
public class ProductoService {

    @Autowired private ProductoRepository             productoRepo;
    @Autowired private DepartamentoRepository         departamentoRepo;
    @Autowired private DetalleVentaRepository         detalleRepo;
    @Autowired private PromocionRepository            promocionRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private HistorialPrecioRepository      historialPrecioRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.TicketPendienteRepository ticketPendienteRepo;
    @Autowired private SesionService                  sesionService;
    @Autowired private AuditoriaService               auditoriaService;
    @Autowired private UnidadRepository               unidadRepo;
    @Autowired private ConfiguracionService           configuracionService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository proveedorRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.OrdenCompraRepository ordenCompraRepo;

    // ── Consultas ─────────────────────────────────────────────────────────

    public List<Producto> listarTodos() {
        return productoRepo.findAllByOrderByDescripcionAsc();
    }

    public List<Producto> buscarPorNombre(String texto) {
        return productoRepo.findByDescripcionContainingIgnoreCase(texto);
    }

    /** Búsqueda libre por código o descripción (texto vacío = todos), incluye inactivos */
    public List<Producto> buscar(String texto) {
        if (texto == null || texto.isBlank()) return listarTodos();
        String t = texto.trim();
        return productoRepo
                .findByCodigoBarrasContainingIgnoreCaseOrDescripcionContainingIgnoreCase(t, t);
    }

    /** Igual que buscar() pero solo productos activos (para la pantalla de Ventas) */
    public List<Producto> buscarParaVenta(String texto) {
        return buscar(texto).stream().filter(Producto::isActivo).toList();
    }

    public Optional<Producto> buscarPorCodigo(String codigoBarras) {
        if (codigoBarras == null || codigoBarras.isBlank()) return Optional.empty();
        return productoRepo.findByCodigoBarras(codigoBarras.trim());
    }

    public Optional<Producto> buscarPorId(Long id) {
        return productoRepo.findById(id);
    }

    /** Productos con inventario cuya existencia ya llegó al mínimo (o menos) */
    public List<Producto> productosConStockBajo() {
        return productoRepo.findConStockBajo().stream().filter(Producto::isActivo).toList();
    }

    /** Todos los productos que manejan inventario (para la pantalla F4) */
    public List<Producto> productosConInventario() {
        return productoRepo.findByUsaInventarioTrueOrderByDescripcionAsc();
    }

    /** true si el producto maneja inventario y está en o por debajo del mínimo */
    public static boolean tieneStockBajo(Producto p) {
        return p.isUsaInventario() && p.getCantidadActual() <= p.getCantidadMinima();
    }

    public List<MovimientoInventario> movimientosEntre(LocalDate desde, LocalDate hasta) {
        if (desde == null) desde = LocalDate.now();
        if (hasta == null) hasta = desde;
        if (hasta.isBefore(desde)) {
            throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        }
        return movimientoRepo.findEntre(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
    }

    public List<MovimientoInventario> movimientosDe(Long productoId) {
        return movimientoRepo.findByProductoIdOrderByFechaDesc(productoId);
    }

    public List<HistorialPrecio> historialPrecios(Long productoId) {
        return historialPrecioRepo.findByProductoIdOrderByFechaDesc(productoId);
    }

    // ── Altas / cambios ───────────────────────────────────────────────────

    /**
     * Crea o modifica un producto (EDITAR_PRODUCTOS). En edición:
     *  - cambiar un precio exige CAMBIAR_PRECIOS y queda en el historial;
     *  - la existencia NO se toma del objeto recibido si el producto ya
     *    manejaba inventario (solo cambia desde Inventario o con ventas);
     *  - si empieza a usar inventario, la cantidad capturada es el alta inicial.
     * En alta nueva con existencia > 0 se registra un movimiento ALTA_INICIAL.
     */
    @Transactional
    public Producto guardar(Producto producto) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        validar(producto);
        if (producto.getId() == null) {
            if (producto.getActivo() == null) producto.setActivo(true);
            Producto guardado = productoRepo.save(producto);
            if (guardado.isUsaInventario() && guardado.getCantidadActual() > 0) {
                registrarMovimiento(guardado, MovimientoInventario.Tipo.ALTA_INICIAL,
                        guardado.getCantidadActual(), 0, guardado.getCantidadActual(),
                        "Alta de producto", null, null, null);
            }
            return guardado;
        }

        Producto enBd = productoRepo.findById(producto.getId())
                .orElseThrow(() -> new NegocioException("El producto ya no existe."));

        // Precios: permiso + historial
        boolean cambiaPrecios = enBd.getPrecioVenta()   != producto.getPrecioVenta()
                             || enBd.getPrecioCosto()   != producto.getPrecioCosto()
                             || enBd.getPrecioMayoreo() != producto.getPrecioMayoreo();
        if (cambiaPrecios) {
            sesionService.verificarPermiso(Permiso.CAMBIAR_PRECIOS);
            registrarCambioPrecio(enBd, "VENTA",   enBd.getPrecioVenta(),   producto.getPrecioVenta());
            registrarCambioPrecio(enBd, "COSTO",   enBd.getPrecioCosto(),   producto.getPrecioCosto());
            registrarCambioPrecio(enBd, "MAYOREO", enBd.getPrecioMayoreo(), producto.getPrecioMayoreo());
        }

        // Existencia: solo Inventario la cambia (INV-06). Activar o desactivar el
        // inventario de un producto con existencia exige AJUSTAR_INVENTARIO y
        // deja movimiento, para que el stock nunca aparezca ni desaparezca sin rastro.
        double existenciaAntes = enBd.getCantidadActual();
        boolean empiezaInventario = !enBd.isUsaInventario() && producto.isUsaInventario();
        boolean dejaInventario    = enBd.isUsaInventario() && !producto.isUsaInventario();
        if (enBd.isUsaInventario() && producto.isUsaInventario()) {
            producto.setCantidadActual(existenciaAntes);
        }
        if (empiezaInventario && producto.getCantidadActual() > 0) {
            sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO);
        }
        if (dejaInventario) {
            if (existenciaAntes > 0) sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO);
            producto.setCantidadActual(0);
        }
        if (producto.getActivo() == null) producto.setActivo(enBd.isActivo());

        Producto guardado = productoRepo.save(producto);

        if (empiezaInventario && guardado.getCantidadActual() > 0) {
            registrarMovimiento(guardado, MovimientoInventario.Tipo.ALTA_INICIAL,
                    guardado.getCantidadActual(), 0, guardado.getCantidadActual(),
                    "Activación de inventario", null, null, null);
        }
        if (dejaInventario && existenciaAntes > 0) {
            registrarMovimiento(guardado, MovimientoInventario.Tipo.AJUSTE,
                    -existenciaAntes, existenciaAntes, 0,
                    "Dejó de usar inventario", null, null, null);
        }
        return guardado;
    }

    /** Desactivar = descontinuar: deja de aparecer en Ventas (PRD-09) */
    @Transactional
    public Producto cambiarActivo(Long id, boolean activo) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        Producto p = productoRepo.findById(id)
                .orElseThrow(() -> new NegocioException("El producto ya no existe."));
        p.setActivo(activo);
        auditoriaService.registrar(sesionService.getCajeroActual(),
                activo ? "PRODUCTO_ACTIVADO" : "PRODUCTO_DESACTIVADO", p.getCodigoBarras(), (Cajero) null);
        return productoRepo.save(p);
    }

    @Transactional
    public void eliminar(Long id) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        Producto p = productoRepo.findById(id)
                .orElseThrow(() -> new NegocioException("El producto ya no existe."));

        // SQLite no valida llaves foráneas por defecto: si borramos un producto
        // con ventas, el historial quedaría con referencias rotas.
        if (detalleRepo.existsByProductoId(id)) {
            throw new NegocioException(
                    "No se puede eliminar \"" + p.getDescripcion() + "\" porque ya tiene " +
                    "ventas registradas. Desactívalo para que deje de aparecer en Ventas.");
        }
        if (movimientoRepo.existsByProductoId(id) || historialPrecioRepo.existsByProductoId(id)) {
            throw new NegocioException(
                    "No se puede eliminar \"" + p.getDescripcion() + "\" porque tiene movimientos de " +
                    "inventario o historial de precios. Desactívalo para que deje de aparecer en Ventas.");
        }
        if (ordenCompraRepo.productoEnOrdenes(id)) {
            throw new NegocioException(
                    "No se puede eliminar \"" + p.getDescripcion() + "\" porque está en una orden de compra. "
                    + "Desactívalo para que deje de aparecer en Ventas.");
        }
        if (ticketPendienteRepo.contarLineasConProducto(id) > 0) {
            throw new NegocioException(
                    "No se puede eliminar \"" + p.getDescripcion() + "\": está en un ticket pendiente de cobrar.");
        }
        promocionRepo.deleteByProductoId(id);
        productoRepo.delete(p);
        auditoriaService.registrar(sesionService.getCajeroActual(), "PRODUCTO_ELIMINADO",
                p.getCodigoBarras() + " " + p.getDescripcion(), (Cajero) null);
    }

    // ── Inventario ────────────────────────────────────────────────────────

    /**
     * Entrada de mercancía (INV-04): suma unidades, registra proveedor y
     * costo unitario, y actualiza el costo del producto al de esta compra
     * (último costo). Requiere AJUSTAR_INVENTARIO o autorización.
     */
    @Transactional
    public Producto registrarEntrada(Long productoId, double cantidad, String proveedor,
                                     double costoUnitario, Collection<Autorizacion> autorizaciones) {
        if (proveedor == null || proveedor.isBlank()) {
            throw new NegocioException("Indica el proveedor de la entrada.");
        }
        return aplicarEntrada(productoId, cantidad, proveedor.trim(), null, null, costoUnitario, autorizaciones);
    }

    /**
     * Entrada de mercancía de un proveedor del catálogo. La bitácora guarda el
     * id y también el nombre (historial legible aunque luego se renombre el
     * proveedor). {@code motivo} es opcional (p. ej. "Orden de compra #12").
     */
    @Transactional
    public Producto registrarEntradaDeProveedor(Long productoId, double cantidad, Long proveedorId,
                                                String motivo, double costoUnitario,
                                                Collection<Autorizacion> autorizaciones) {
        if (proveedorId == null) throw new NegocioException("Elige el proveedor de la entrada.");
        Proveedor proveedor = proveedorRepo.findById(proveedorId)
                .orElseThrow(() -> new NegocioException("El proveedor ya no existe."));
        if (!proveedor.isActivo()) {
            throw new NegocioException("El proveedor \"" + proveedor.getNombre() + "\" está desactivado.");
        }
        return aplicarEntrada(productoId, cantidad, proveedor.getNombre(), proveedor.getId(), motivo,
                costoUnitario, autorizaciones);
    }

    private Producto aplicarEntrada(Long productoId, double cantidad, String proveedorNombre, Long proveedorId,
                                    String motivo, double costoUnitario, Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        if (!Double.isFinite(cantidad) || cantidad <= 0) {
            throw new NegocioException("La cantidad a agregar debe ser mayor a cero.");
        }
        if (!Double.isFinite(costoUnitario) || costoUnitario < 0) {
            throw new NegocioException("El costo unitario no puede ser negativo.");
        }

        Producto p = obtenerConInventario(productoId);
        validarDecimales(p, cantidad);
        double antes = p.getCantidadActual();
        p.setCantidadActual(antes + cantidad);

        if (costoUnitario > 0 && Math.abs(costoUnitario - p.getPrecioCosto()) > 0.0001) {
            registrarCambioPrecio(p, "COSTO", p.getPrecioCosto(), costoUnitario);
            p.setPrecioCosto(costoUnitario);
        }
        Producto guardado = productoRepo.save(p);

        Autorizacion aut = sesionService.autorizacionUsada(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        MovimientoInventario m = registrarMovimiento(guardado, MovimientoInventario.Tipo.ENTRADA, cantidad, antes,
                guardado.getCantidadActual(), motivo, proveedorNombre, costoUnitario, null, aut);
        if (proveedorId != null) {
            m.setProveedorId(proveedorId);
            movimientoRepo.save(m);
        }
        auditarSiAutorizado("ENTRADA_INVENTARIO", guardado, aut);
        return guardado;
    }


    /**
     * Ajuste manual (INV-05): fija la existencia en un valor exacto con un
     * motivo obligatorio (merma, robo, caducidad, conteo...).
     */
    @Transactional
    public Producto ajustarStock(Long productoId, double nuevaCantidad, String motivo,
                                 Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        if (!Double.isFinite(nuevaCantidad) || nuevaCantidad < 0) {
            throw new NegocioException("La existencia no puede ser negativa.");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new NegocioException("Indica el motivo del ajuste (merma, robo, caducidad, conteo...).");
        }
        Producto p = obtenerConInventario(productoId);
        validarDecimales(p, nuevaCantidad);
        double antes = p.getCantidadActual();
        p.setCantidadActual(nuevaCantidad);
        Producto guardado = productoRepo.save(p);

        Autorizacion aut = sesionService.autorizacionUsada(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        registrarMovimiento(guardado, MovimientoInventario.Tipo.AJUSTE, nuevaCantidad - antes, antes,
                nuevaCantidad, motivo.trim(), null, null, null, aut);
        auditarSiAutorizado("AJUSTE_INVENTARIO", guardado, aut);
        return guardado;
    }


    /**
     * Registra un movimiento en la bitácora de inventario. Lo usan también
     * VentaService (ventas y cancelaciones). No cambia la existencia.
     */
    @Transactional
    public MovimientoInventario registrarMovimiento(Producto producto, MovimientoInventario.Tipo tipo,
                                                    double cantidad, double antes, double despues,
                                                    String motivo, String proveedor, Double costoUnitario,
                                                    Long ventaId) {
        return registrarMovimiento(producto, tipo, cantidad, antes, despues, motivo, proveedor,
                costoUnitario, ventaId, null);
    }

    @Transactional
    public MovimientoInventario registrarMovimiento(Producto producto, MovimientoInventario.Tipo tipo,
                                                    double cantidad, double antes, double despues,
                                                    String motivo, String proveedor, Double costoUnitario,
                                                    Long ventaId, Autorizacion autorizacion) {
        Cajero actor = sesionService.getCajeroActual();
        MovimientoInventario m = new MovimientoInventario();
        m.setProducto(producto);
        m.setTipo(tipo);
        m.setCantidad(cantidad);
        m.setExistenciaAntes(antes);
        m.setExistenciaDespues(despues);
        m.setUsuarioId(actor != null ? actor.getId() : null);
        m.setUsuarioNombre(sesionService.getNombreCajero());
        if (autorizacion != null && autorizacion.administrador() != null) {
            m.setAutorizadoPorId(autorizacion.administrador().getId());
            m.setAutorizadoPor(autorizacion.nombreAdministrador());
        }
        m.setMotivo(motivo);
        m.setProveedor(proveedor);
        m.setCostoUnitario(costoUnitario);
        m.setVentaId(ventaId);
        return movimientoRepo.save(m);
    }

    private Producto obtenerConInventario(Long productoId) {
        Producto p = productoRepo.findById(productoId)
                .orElseThrow(() -> new NegocioException("El producto ya no existe."));
        if (!p.isUsaInventario()) {
            throw new NegocioException(
                    "\"" + p.getDescripcion() + "\" no maneja inventario.");
        }
        return p;
    }

    /** Solo los productos a granel aceptan cantidades con decimales */
    public static void validarDecimales(Producto p, double cantidad) {
        if (!p.permiteDecimales() && cantidad != Math.floor(cantidad)) {
            throw new NegocioException("\"" + p.getDescripcion()
                    + "\" se vende por pieza: la cantidad debe ser un número entero.");
        }
    }

    // ── Departamentos ─────────────────────────────────────────────────────

    public List<Departamento> listarDepartamentos() {
        return departamentoRepo.findAllByOrderByNombreAsc();
    }

    @Transactional
    public Departamento guardarDepartamento(Departamento d) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        if (d.getNombre() == null || d.getNombre().isBlank()) {
            throw new NegocioException("El nombre del departamento es obligatorio.");
        }
        d.setNombre(d.getNombre().trim());
        departamentoRepo.findByNombreIgnoreCase(d.getNombre())
                .filter(otro -> !otro.getId().equals(d.getId()))
                .ifPresent(otro -> {
                    throw new NegocioException(
                            "Ya existe un departamento llamado \"" + otro.getNombre() + "\".");
                });
        return departamentoRepo.save(d);
    }

    @Transactional
    public void eliminarDepartamento(Departamento d) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        long enUso = productoRepo.countByDepartamentoId(d.getId());
        if (enUso > 0) {
            throw new NegocioException(
                    "No se puede eliminar \"" + d.getNombre() + "\": " + enUso +
                    " producto(s) pertenecen a este departamento.");
        }
        if (promocionRepo.existsByDepartamentoId(d.getId())) {
            throw new NegocioException(
                    "No se puede eliminar \"" + d.getNombre() + "\": tiene promociones por departamento. " +
                    "Elimina o cambia esas promociones primero.");
        }
        departamentoRepo.delete(d);
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void registrarCambioPrecio(Producto p, String campo, double anterior, double nuevo) {
        if (Math.abs(anterior - nuevo) < 0.0001) return;
        historialPrecioRepo.save(new HistorialPrecio(p, campo, anterior, nuevo, sesionService.getCajeroActual()));
    }

    /** Si la acción se hizo con autorización de admin: queda en bitácora y la autorización se consume */
    private void auditarSiAutorizado(String accion, Producto p, Autorizacion aut) {
        if (aut != null) {
            auditoriaService.registrar(sesionService.getCajeroActual(), accion,
                    p.getCodigoBarras() + " " + p.getDescripcion(), aut);
            aut.consumir();
        }
    }

    // ── Validación ────────────────────────────────────────────────────────

    private void validar(Producto p) {
        if (p.getCodigoBarras() == null || p.getCodigoBarras().isBlank()) {
            throw new NegocioException("El código de barras es obligatorio.");
        }
        if (p.getDescripcion() == null || p.getDescripcion().isBlank()) {
            throw new NegocioException("La descripción es obligatoria.");
        }
        p.setCodigoBarras(p.getCodigoBarras().trim());
        p.setDescripcion(p.getDescripcion().trim());

        if (p.getPrecioCosto() < 0 || p.getPrecioVenta() < 0 || p.getPrecioMayoreo() < 0) {
            throw new NegocioException("Los precios no pueden ser negativos.");
        }
        if (p.getPrecioVenta() <= 0) {
            throw new NegocioException("El precio de venta debe ser mayor a cero.");
        }
        if (p.isUsaInventario() && (p.getCantidadActual() < 0 || p.getCantidadMinima() < 0)) {
            throw new NegocioException("Las cantidades de inventario no pueden ser negativas.");
        }
        if (p.getTipoVenta() == null) {
            p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        }
        // La unidad de medida manda sobre el tipo de venta: decimales = a granel
        if (p.getUnidad() != null) {
            Unidad u = unidadRepo.findById(p.getUnidad().getId())
                    .orElseThrow(() -> new NegocioException("La unidad de medida ya no existe."));
            p.setUnidad(u);
            if (u.isPermiteDecimales()) {
                p.setTipoVenta(Producto.TipoVenta.GRANEL);
            } else if (p.getTipoVenta() == Producto.TipoVenta.GRANEL) {
                p.setTipoVenta(Producto.TipoVenta.UNIDAD);
            }
        }
        // Con la venta a granel apagada no se pueden crear productos a granel nuevos
        // (los que ya lo eran se pueden seguir editando)
        if (p.permiteDecimales() && !configuracionService.isPermitirGranel()) {
            boolean yaEraGranel = p.getId() != null
                    && productoRepo.findById(p.getId()).map(Producto::permiteDecimales).orElse(false);
            if (!yaEraGranel) {
                throw new NegocioException("La venta a granel está deshabilitada en Configuración → Opciones habilitadas.");
            }
        }
        if (p.isUsaInventario()) {
            validarDecimales(p, p.getCantidadActual());
        }

        productoRepo.findByCodigoBarras(p.getCodigoBarras())
                .filter(otro -> !otro.getId().equals(p.getId()))
                .ifPresent(otro -> {
                    throw new NegocioException(
                            "Ya existe un producto con el código " + otro.getCodigoBarras() +
                            " (" + otro.getDescripcion() + ").");
                });
    }
}
