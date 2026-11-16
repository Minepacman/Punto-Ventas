package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.HistorialPrecio;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;
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
    @Autowired private IdentidadCaja                  identidadCaja;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.ProductoImagenRepository imagenRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.util.RutasApp rutas;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    // ── Consultas ─────────────────────────────────────────────────────────

    /** Todos los productos con su proveedor habitual cargado (una consulta; la tabla de Productos lo muestra) */
    public List<Producto> listarTodos() {
        return productoRepo.findAllConProveedorOrderByDescripcionAsc();
    }

    /** Cuántos productos activos tienen a cada proveedor como habitual (proveedorId → cuenta); una consulta */
    public java.util.Map<Long, Long> productosActivosPorProveedor() {
        java.util.Map<Long, Long> r = new java.util.HashMap<>();
        for (Object[] f : productoRepo.contarActivosPorProveedor()) {
            if (f[0] != null) r.put(((Number) f[0]).longValue(), ((Number) f[1]).longValue());
        }
        return r;
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
    /**
     * Mismo criterio que {@link #buscar} (código o descripción que contengan el texto, sin
     * distinguir mayúsculas) sobre una lista ya cargada: el buscador F6 consulta la base una vez
     * y filtra en memoria con cada tecla.
     */
    public static List<Producto> filtrar(List<Producto> productos, String texto) {
        String t = texto == null ? "" : texto.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return new java.util.ArrayList<>(productos);
        List<Producto> resultado = new java.util.ArrayList<>();
        for (Producto p : productos) {
            String codigo = p.getCodigoBarras(), descripcion = p.getDescripcion();
            if ((codigo != null && codigo.toLowerCase(java.util.Locale.ROOT).contains(t))
                    || (descripcion != null && descripcion.toLowerCase(java.util.Locale.ROOT).contains(t))) {
                resultado.add(p);
            }
        }
        return resultado;
    }

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
        // El código del producto común es del sistema (Ctrl/Cmd+P en Ventas): no se da de alta ni se edita a mano
        if (producto.getCodigoBarras() != null
                && me.eliassanchezfernandez.puntodeventa.model.ProductoComun.CODIGO.equalsIgnoreCase(producto.getCodigoBarras().trim())) {
            throw new NegocioException("Ese código está reservado para «Producto común» (Ctrl+P en Ventas). Usa otro código.");
        }
        if (producto.getId() == null) {
            if (producto.getActivo() == null) producto.setActivo(true);
            // IVA propuesto por el departamento (ej. "Frutas y verduras" → 0 %) solo en el alta y solo si
            // no se capturó uno (ni siquiera "usar el de la tienda"); después cada producto conserva el suyo
            if (producto.getIva() == null) producto.setIva(ivaPorDefectoDe(producto.getDepartamento()));
            resolverProveedor(producto, null);
            // La existencia inicial es inventario que "aparece": exige el mismo permiso que una entrada
            if (producto.isUsaInventario() && producto.getCantidadActual() > 0) {
                sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO);
            }
            Producto guardado = productoRepo.save(producto);
            if (guardado.isUsaInventario() && guardado.getCantidadActual() > 0) {
                // La existencia inicial queda ligada al proveedor habitual elegido (si lo hay)
                Proveedor prov = guardado.getProveedor();
                MovimientoInventario m = registrarMovimiento(guardado, MovimientoInventario.Tipo.ALTA_INICIAL,
                        guardado.getCantidadActual(), 0, guardado.getCantidadActual(),
                        "Alta de producto", prov != null ? prov.getNombre() : null, null, null);
                if (prov != null) {
                    m.setProveedorId(prov.getId());
                    movimientoRepo.save(m);
                }
            }
            return guardado;
        }

        Producto enBd = productoRepo.findById(producto.getId())
                .orElseThrow(() -> new NegocioException("El producto ya no existe."));
        Proveedor proveedorAntes = enBd.getProveedor();
        resolverProveedor(producto, enBd);
        boolean cambiaProveedor = !mismoId(proveedorAntes, producto.getProveedor());

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
        // Quien no manda el IVA (importación, móvil) no lo borra: se conserva el que tenía
        if (producto.getIva() == null) producto.setIva(enBd.getIva());
        TipoIva ivaAntes = enBd.getIvaOTienda();
        boolean cambiaIva = ivaAntes != producto.getIvaOTienda();

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
        if (cambiaProveedor) {
            auditoriaService.registrar(sesionService.getCajeroActual(), "PRODUCTO_PROVEEDOR_CAMBIADO",
                    guardado.getCodigoBarras() + " " + guardado.getDescripcion() + ": proveedor "
                            + nombreProveedor(proveedorAntes) + " → " + nombreProveedor(guardado.getProveedor()), (Cajero) null);
        }
        if (cambiaIva) {
            auditoriaService.registrar(sesionService.getCajeroActual(), ACCION_IVA_CAMBIADO,
                    guardado.getCodigoBarras() + " " + guardado.getDescripcion() + ": "
                            + ivaAntes.getEtiqueta() + " → " + guardado.getIvaOTienda().getEtiqueta(), (Cajero) null);
        }
        return guardado;
    }

    // ── IVA de varios productos a la vez ─────────────────────────────────

    /** Acción de bitácora al cambiar el IVA de uno o varios productos */
    public static final String ACCION_IVA_CAMBIADO = "PRODUCTO_IVA_CAMBIADO";

    /** Productos por UPDATE (SQLite y PostgreSQL aceptan listas IN mucho más grandes; esto es holgado) */
    private static final int LOTE_IVA = 500;

    /**
     * Pone el mismo IVA a varios productos (Productos → "Cambiar IVA a los seleccionados…").
     * EDITAR_PRODUCTOS, una sola transacción (o se cambian todos o ninguno) y un renglón de
     * bitácora con cuántos cambiaron y cuáles. Los que ya tenían ese IVA no se tocan.
     * {@link TipoIva#TIENDA} se guarda tal cual ("usar el de la tienda").
     * El UPDATE solo escribe la columna iva: no pisa existencias que otra caja esté moviendo.
     *
     * @return cuántos productos cambiaron de IVA
     */
    @Transactional
    public int cambiarIva(java.util.Collection<Long> productoIds, TipoIva nuevo) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        return cambiarIvaSinPermiso(productoIds, nuevo, null);
    }

    /**
     * Aplica el IVA que propone el departamento a TODOS sus productos (los ya existentes; los
     * nuevos ya lo reciben al darse de alta). Sin propuesta = "usar el de la tienda".
     * EDITAR_PRODUCTOS, una transacción, bitácora.
     *
     * @return cuántos productos cambiaron de IVA
     */
    @Transactional
    public int aplicarIvaDelDepartamento(Long departamentoId) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        Departamento d = departamentoRepo.findById(departamentoId)
                .orElseThrow(() -> new NegocioException("El departamento ya no existe."));
        TipoIva iva = TipoIva.oTienda(d.getIvaPorDefecto());
        List<Long> ids = em.createQuery(
                        "SELECT p.id FROM Producto p WHERE p.departamento.id = :d", Long.class)
                .setParameter("d", departamentoId)
                .getResultList();
        return cambiarIvaSinPermiso(ids, iva, "departamento \"" + d.getNombre() + "\"");
    }

    private int cambiarIvaSinPermiso(java.util.Collection<Long> productoIds, TipoIva nuevo, String origen) {
        if (nuevo == null) throw new NegocioException("Elige el IVA que se pondrá a los productos.");
        if (productoIds == null || productoIds.isEmpty()) return 0;
        List<Long> ids = productoIds.stream().filter(java.util.Objects::nonNull).distinct().toList();

        // Leer primero (id, código, descripción, IVA) para saber cuáles cambian y anotarlos en la bitácora
        List<Object[]> filas = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i += LOTE_IVA) {
            filas.addAll(em.createQuery(
                            "SELECT p.id, p.codigoBarras, p.descripcion, p.iva FROM Producto p WHERE p.id IN :ids",
                            Object[].class)
                    .setParameter("ids", ids.subList(i, Math.min(ids.size(), i + LOTE_IVA)))
                    .getResultList());
        }
        List<Long> aCambiar = new java.util.ArrayList<>();
        java.util.Map<TipoIva, Integer> antes = new java.util.EnumMap<>(TipoIva.class);
        StringBuilder cuales = new StringBuilder();
        for (Object[] f : filas) {
            TipoIva actual = TipoIva.oTienda((TipoIva) f[3]);
            if (actual == nuevo) continue;
            aCambiar.add((Long) f[0]);
            antes.merge(actual, 1, Integer::sum);
            if (cuales.length() < 600) {
                if (cuales.length() > 0) cuales.append(", ");
                cuales.append(f[1]).append(' ').append(f[2]);
            }
        }
        if (aCambiar.isEmpty()) return 0;

        // Que los productos ya cargados en esta sesión no escriban después su IVA viejo
        em.flush();
        for (int i = 0; i < aCambiar.size(); i += LOTE_IVA) {
            em.createQuery("UPDATE Producto p SET p.iva = :iva WHERE p.id IN :ids")
                    .setParameter("iva", nuevo)
                    .setParameter("ids", aCambiar.subList(i, Math.min(aCambiar.size(), i + LOTE_IVA)))
                    .executeUpdate();
        }
        em.clear();

        StringBuilder detalle = new StringBuilder();
        detalle.append(aCambiar.size()).append(aCambiar.size() == 1 ? " producto" : " productos")
               .append(" → ").append(nuevo.getEtiqueta());
        if (origen != null) detalle.append(" (IVA del ").append(origen).append(')');
        detalle.append(". Antes: ");
        antes.forEach((t, n) -> detalle.append(t.getEtiqueta()).append(" ×").append(n).append("; "));
        detalle.append(cuales);
        if (aCambiar.size() > 1 && cuales.length() >= 600) detalle.append(", …");
        auditoriaService.registrar(sesionService.getCajeroActual(), ACCION_IVA_CAMBIADO, detalle.toString(), (Cajero) null);
        return aCambiar.size();
    }

    /**
     * Deja en el producto el proveedor habitual del catálogo (o null). Un proveedor desactivado
     * solo se acepta si el producto ya lo tenía (al editar no se le obliga a cambiarlo); elegirlo
     * de nuevo no, porque ya no surte.
     */
    private void resolverProveedor(Producto producto, Producto enBd) {
        if (producto.getProveedor() == null) return;
        Long id = producto.getProveedor().getId();
        if (id == null) { producto.setProveedor(null); return; }
        Proveedor prov = proveedorRepo.findById(id)
                .orElseThrow(() -> new NegocioException("El proveedor ya no existe."));
        boolean yaLoTenia = enBd != null && mismoId(enBd.getProveedor(), prov);
        if (!prov.isActivo() && !yaLoTenia) {
            throw new NegocioException("El proveedor \"" + prov.getNombre() + "\" está desactivado: elige otro o reactívalo en Proveedores.");
        }
        producto.setProveedor(prov);
    }

    private static boolean mismoId(Proveedor a, Proveedor b) {
        if (a == null || b == null) return a == b;
        return a.getId() != null && a.getId().equals(b.getId());
    }

    private static String nombreProveedor(Proveedor p) {
        return p == null ? "ninguno" : p.getNombre();
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
        if (imagenRepo.existsById(id)) imagenRepo.deleteById(id);   // su foto se va con él
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
        return aplicarEntrada(productoId, cantidad, proveedor.trim(), null, null, costoUnitario, autorizaciones, true);
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
        return aplicarEntrada(productoId, cantidad, proveedor.getNombre(), proveedor, motivo,
                costoUnitario, autorizaciones, true);
    }

    /**
     * Una línea de una orden de compra que se está recibiendo. A diferencia de
     * {@link #registrarEntradaDeProveedor} NO consume la autorización (la orden
     * tiene varias líneas y una sola autorización debe cubrirlas todas: la
     * consume {@link OrdenCompraService#recibir} al terminar) y tolera que el
     * proveedor se haya desactivado después de hacer el pedido.
     */
    @Transactional
    public Producto registrarEntradaDeOrden(Long productoId, double cantidad, Proveedor proveedor,
                                            String motivo, double costoUnitario,
                                            Collection<Autorizacion> autorizaciones) {
        if (proveedor == null) throw new NegocioException("La orden no tiene proveedor.");
        return aplicarEntrada(productoId, cantidad, proveedor.getNombre(), proveedor, motivo,
                costoUnitario, autorizaciones, false);
    }

    /**
     * @param proveedor proveedor del catálogo (null en entradas con el nombre escrito a mano). Si el
     *        producto aún no tiene proveedor habitual, esta entrada se lo asigna; si ya tiene uno
     *        (elegido por el usuario o por una entrada anterior) no se toca.
     * @param consumirAutorizacion true cuando esta entrada es la acción completa que se autorizó
     *        (la autorización de un solo uso se gasta aquí); false cuando forma parte de una
     *        operación mayor que la consumirá al final.
     */
    private Producto aplicarEntrada(Long productoId, double cantidad, String proveedorNombre, Proveedor proveedor,
                                    String motivo, double costoUnitario, Collection<Autorizacion> autorizaciones,
                                    boolean consumirAutorizacion) {
        Long proveedorId = proveedor != null ? proveedor.getId() : null;
        sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        if (rutas.migradaAlServidor()) throw new NegocioException(rutas.mensajeMigrada());
        if (!Double.isFinite(cantidad) || cantidad <= 0) {
            throw new NegocioException("La cantidad a agregar debe ser mayor a cero.");
        }
        if (!Double.isFinite(costoUnitario) || costoUnitario < 0) {
            throw new NegocioException("El costo unitario no puede ser negativo.");
        }

        Producto p = obtenerConInventario(productoId);
        if (!p.isActivo()) {
            throw new NegocioException("\"" + p.getDescripcion() + "\" está desactivado: actívalo en Productos antes de darle entrada.");
        }
        validarDecimales(p, cantidad);
        if (costoUnitario > 0 && Math.abs(costoUnitario - p.getPrecioCosto()) > 0.0001) {
            registrarCambioPrecio(p, "COSTO", p.getPrecioCosto(), costoUnitario);
            p.setPrecioCosto(costoUnitario);
        }
        // Primera entrada con proveedor del catálogo: queda como proveedor habitual (solo si estaba vacío)
        if (proveedor != null && p.getProveedor() == null) p.setProveedor(proveedor);
        productoRepo.save(p);
        // La existencia se suma EN la base (atómico): una venta simultánea en otra caja no se pierde
        productoRepo.sumar(p.getId(), cantidad);
        em.refresh(p);
        double antes = VentaService.redondearCantidad(p.getCantidadActual() - cantidad);
        Producto guardado = p;

        Autorizacion aut = sesionService.autorizacionUsada(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        MovimientoInventario m = registrarMovimiento(guardado, MovimientoInventario.Tipo.ENTRADA, cantidad, antes,
                guardado.getCantidadActual(), motivo, proveedorNombre, costoUnitario, null, aut);
        if (proveedorId != null) {
            m.setProveedorId(proveedorId);
            movimientoRepo.save(m);
        }
        auditarSiAutorizado("ENTRADA_INVENTARIO", guardado, aut, consumirAutorizacion);
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
        if (rutas.migradaAlServidor()) throw new NegocioException(rutas.mensajeMigrada());
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
        auditarSiAutorizado("AJUSTE_INVENTARIO", guardado, aut, true);
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
        m.setCaja(identidadCaja.numero());
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

    /** IVA que el departamento propone a sus productos nuevos; null si no tiene (o no hay departamento) */
    public me.eliassanchezfernandez.puntodeventa.model.TipoIva ivaPorDefectoDe(Departamento depto) {
        if (depto == null) return null;
        if (depto.getIvaPorDefecto() == null && depto.getId() != null) {
            // Puede venir sin cargar (solo el id, p. ej. desde la importación o el móvil)
            return departamentoRepo.findById(depto.getId()).map(Departamento::getIvaPorDefecto).orElse(null);
        }
        return depto.getIvaPorDefecto();
    }

    @Transactional
    public Departamento guardarDepartamento(Departamento d) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        if (d.getNombre() == null || d.getNombre().isBlank()) {
            throw new NegocioException("El nombre del departamento es obligatorio.");
        }
        if (d.getIvaPorDefecto() == me.eliassanchezfernandez.puntodeventa.model.TipoIva.TIENDA) {
            d.setIvaPorDefecto(null);   // "usar el de la tienda" es lo mismo que no proponer nada
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

    /**
     * Si la acción se hizo con autorización de admin: queda en bitácora y, si esta
     * acción era la operación completa, la autorización se consume (una orden de
     * compra de varias líneas la consume al final, no en cada línea).
     */
    private void auditarSiAutorizado(String accion, Producto p, Autorizacion aut, boolean consumir) {
        if (aut != null) {
            auditoriaService.registrar(sesionService.getCajeroActual(), accion,
                    p.getCodigoBarras() + " " + p.getDescripcion(), aut);
            if (consumir) aut.consumir();
        }
    }

    /**
     * Aviso (no bloqueo, decisión del dueño) cuando el precio de mayoreo es mayor
     * que el precio normal: la pantalla lo muestra y el usuario puede confirmar.
     * @return el texto del aviso, o vacío si no hay nada raro
     */
    public static Optional<String> advertenciaMayoreo(Producto p) {
        if (p == null || p.getPrecioMayoreo() <= 0 || p.getPrecioMayoreo() <= p.getPrecioVenta() + 0.0001) {
            return Optional.empty();
        }
        return Optional.of(String.format("El precio de mayoreo (%s) es MAYOR que el precio de venta (%s): "
                + "quien compre por mayoreo pagará más caro. ¿Guardar de todos modos?",
                me.eliassanchezfernandez.puntodeventa.util.Moneda.formato(p.getPrecioMayoreo()),
                me.eliassanchezfernandez.puntodeventa.util.Moneda.formato(p.getPrecioVenta())));
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
        // Claves SAT (opcionales): solo se revisa el formato; el PAC las valida contra el catálogo al facturar
        try {
            p.setClaveProdServ(me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.normalizarProdServ(p.getClaveProdServ()));
            p.setClaveUnidad(me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.normalizarUnidad(p.getClaveUnidad()));
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        if (p.getObjetoImp() != null && p.getObjetoImp().isBlank()) p.setObjetoImp(null);
        if (p.getObjetoImp() != null && !me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.esObjetoImp(p.getObjetoImp())) {
            throw new NegocioException("El objeto de impuesto debe ser 01, 02 o 03.");
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
