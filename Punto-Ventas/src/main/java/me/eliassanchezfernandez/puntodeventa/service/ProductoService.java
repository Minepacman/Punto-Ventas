package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.DetalleVentaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.PromocionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Catálogo de productos, departamentos e inventario.
 *
 * Las validaciones de negocio viven aquí (no en los controladores) y
 * se reportan con NegocioException, cuyo mensaje se muestra al usuario.
 */
@Service
public class ProductoService {

    @Autowired private ProductoRepository     productoRepo;
    @Autowired private DepartamentoRepository departamentoRepo;
    @Autowired private DetalleVentaRepository detalleRepo;
    @Autowired private PromocionRepository    promocionRepo;

    // ── Consultas ─────────────────────────────────────────────────────────

    public List<Producto> listarTodos() {
        return productoRepo.findAllByOrderByDescripcionAsc();
    }

    public List<Producto> buscarPorNombre(String texto) {
        return productoRepo.findByDescripcionContainingIgnoreCase(texto);
    }

    /** Búsqueda libre por código o descripción (texto vacío = todos) */
    public List<Producto> buscar(String texto) {
        if (texto == null || texto.isBlank()) return listarTodos();
        String t = texto.trim();
        return productoRepo
                .findByCodigoBarrasContainingIgnoreCaseOrDescripcionContainingIgnoreCase(t, t);
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
        return productoRepo.findConStockBajo();
    }

    /** Todos los productos que manejan inventario (para la pantalla F4) */
    public List<Producto> productosConInventario() {
        return productoRepo.findByUsaInventarioTrueOrderByDescripcionAsc();
    }

    /** true si el producto maneja inventario y está en o por debajo del mínimo */
    public static boolean tieneStockBajo(Producto p) {
        return p.isUsaInventario() && p.getCantidadActual() <= p.getCantidadMinima();
    }

    // ── Altas / cambios ───────────────────────────────────────────────────

    @Transactional
    public Producto guardar(Producto producto) {
        validar(producto);
        return productoRepo.save(producto);
    }

    @Transactional
    public void eliminar(Long id) {
        Producto p = productoRepo.findById(id)
                .orElseThrow(() -> new NegocioException("El producto ya no existe."));

        // SQLite no valida llaves foráneas por defecto: si borramos un producto
        // con ventas, el historial quedaría con referencias rotas.
        if (detalleRepo.existsByProductoId(id)) {
            throw new NegocioException(
                    "No se puede eliminar \"" + p.getDescripcion() + "\" porque ya tiene " +
                    "ventas registradas. Puedes dejarlo sin inventario o cambiar su descripción.");
        }
        promocionRepo.deleteByProductoId(id);
        productoRepo.delete(p);
    }

    // ── Inventario ────────────────────────────────────────────────────────

    /** Entrada de mercancía: suma unidades a la existencia actual */
    @Transactional
    public Producto agregarStock(Long productoId, double cantidad) {
        if (cantidad <= 0) {
            throw new NegocioException("La cantidad a agregar debe ser mayor a cero.");
        }
        Producto p = obtenerConInventario(productoId);
        p.setCantidadActual(p.getCantidadActual() + cantidad);
        return productoRepo.save(p);
    }

    /** Ajuste manual: fija la existencia en un valor exacto (conteo físico) */
    @Transactional
    public Producto ajustarStock(Long productoId, double nuevaCantidad) {
        if (nuevaCantidad < 0) {
            throw new NegocioException("La existencia no puede ser negativa.");
        }
        Producto p = obtenerConInventario(productoId);
        p.setCantidadActual(nuevaCantidad);
        return productoRepo.save(p);
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

    // ── Departamentos ─────────────────────────────────────────────────────

    public List<Departamento> listarDepartamentos() {
        return departamentoRepo.findAllByOrderByNombreAsc();
    }

    @Transactional
    public Departamento guardarDepartamento(Departamento d) {
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
        long enUso = productoRepo.countByDepartamentoId(d.getId());
        if (enUso > 0) {
            throw new NegocioException(
                    "No se puede eliminar \"" + d.getNombre() + "\": " + enUso +
                    " producto(s) pertenecen a este departamento.");
        }
        departamentoRepo.delete(d);
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

        productoRepo.findByCodigoBarras(p.getCodigoBarras())
                .filter(otro -> !otro.getId().equals(p.getId()))
                .ifPresent(otro -> {
                    throw new NegocioException(
                            "Ya existe un producto con el código " + otro.getCodigoBarras() +
                            " (" + otro.getDescripcion() + ").");
                });
    }
}
