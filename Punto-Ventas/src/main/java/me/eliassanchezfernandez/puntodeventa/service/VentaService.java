package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lógica de negocio de ventas.
 *
 * Todas las operaciones que modifican la BD deben ser @Transactional
 * para que SQLite aplique los cambios en bloque (o revierta si falla algo).
 *
 * cobrar() NO confía en los precios/descuentos que trae el ticket de la UI:
 * dentro de la transacción vuelve a leer cada producto, recalcula el precio
 * y la promoción vigente, y toma al cajero de la sesión activa.
 */
@Service
public class VentaService {

    @Autowired private VentaRepository    ventaRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private CajeroRepository   cajeroRepo;
    @Autowired private PromocionService   promocionService;
    @Autowired private SesionService      sesionService;

    // ── Búsqueda de productos ─────────────────────────────────────────────

    public Optional<Producto> buscarPorCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) return Optional.empty();
        return productoRepo.findByCodigoBarras(codigo.trim());
    }

    public List<Producto> buscarPorNombre(String texto) {
        return productoRepo.findByDescripcionContainingIgnoreCase(texto);
    }

    // ── Armado del ticket ─────────────────────────────────────────────────

    /**
     * Crea un renglón del ticket con el precio de lista del producto
     * y el descuento de la promoción vigente (si la hay).
     */
    public DetalleVenta crearDetalle(Producto producto, int cantidad) {
        if (cantidad <= 0) {
            throw new NegocioException("La cantidad debe ser mayor a cero.");
        }
        validarStock(producto, cantidad);

        DetalleVenta d = new DetalleVenta();
        d.setProducto(producto);
        d.setPrecioVenta(producto.getPrecioVenta());
        d.setCantidad(cantidad);
        aplicarPromocion(d);
        return d;
    }

    /**
     * Recalcula el descuento del renglón según la promoción vigente del
     * producto, tomando como base el precio unitario que tenga el renglón
     * (precio de lista o de mayoreo). Llamar siempre que cambie la cantidad
     * o el precio.
     */
    public void aplicarPromocion(DetalleVenta d) {
        Optional<Promocion> promo = promocionService.promocionVigente(d.getProducto());
        double descuento = promo
                .map(pr -> (d.getPrecioVenta() - pr.precioConPromocion(d.getPrecioVenta()))
                           * d.getCantidad())
                .orElse(0.0);
        d.setDescuento(redondear(Math.max(0, descuento)));
    }

    /**
     * Fija el precio unitario del renglón a partir del producto que tiene
     * asignado: precio de lista, o precio de mayoreo si el renglón ya lo
     * traía y el producto lo tiene registrado. Cualquier otro precio se
     * descarta. Después recalcula la promoción.
     */
    public void recalcularPrecio(DetalleVenta d) {
        Producto p = d.getProducto();
        boolean esMayoreo = p.getPrecioMayoreo() > 0
                && Math.abs(d.getPrecioVenta() - p.getPrecioMayoreo()) < 0.005;
        d.setPrecioVenta(esMayoreo ? p.getPrecioMayoreo() : p.getPrecioVenta());
        aplicarPromocion(d);
    }

    public double calcularTotal(Collection<DetalleVenta> detalles) {
        return redondear(detalles.stream().mapToDouble(DetalleVenta::getImporte).sum());
    }

    /**
     * Verifica que haya existencia suficiente (solo para productos con
     * inventario). Lanza StockInsuficienteException si no alcanza.
     */
    public void validarStock(Producto producto, int cantidadDeseada) {
        if (!producto.isUsaInventario()) return;
        if (cantidadDeseada > producto.getCantidadActual()) {
            throw new StockInsuficienteException(producto, cantidadDeseada);
        }
    }

    // ── Registro de una venta completa ────────────────────────────────────

    /**
     * Guarda la venta con sus renglones y descuenta el inventario,
     * todo en una sola transacción.
     *
     *  - El cajero se toma de la sesión activa (no de la UI).
     *  - Precio y promoción de cada renglón se recalculan desde la BD.
     *  - Si un producto aparece en varios renglones, el stock se valida
     *    contra la SUMA de sus cantidades.
     *  - Si algo falla se lanza NegocioException y NO se guarda nada.
     */
    @Transactional
    public Venta cobrar(List<DetalleVenta> detalles,
                        Venta.FormaPago formaPago,
                        double montoPagado) {

        if (detalles == null || detalles.isEmpty()) {
            throw new NegocioException("El ticket está vacío.");
        }
        Cajero cajeroSesion = sesionService.getCajeroActual();
        if (cajeroSesion == null) {
            throw new NegocioException("No hay una sesión activa. Vuelve a iniciar sesión para cobrar.");
        }
        if (formaPago == null) formaPago = Venta.FormaPago.EFECTIVO;
        if (!Double.isFinite(montoPagado) || montoPagado < 0) {
            throw new NegocioException("El monto pagado no es válido.");
        }

        // 1) Releer cada producto y recalcular precio + promoción desde la BD
        Map<Long, Producto> productosPorId   = new LinkedHashMap<>();
        Map<Long, Integer>  cantidadPorProducto = new LinkedHashMap<>();
        for (DetalleVenta d : detalles) {
            if (d.getCantidad() <= 0) {
                throw new NegocioException("La cantidad debe ser mayor a cero.");
            }
            if (d.getProducto() == null || d.getProducto().getId() == null) {
                throw new NegocioException("Hay un renglón del ticket sin producto.");
            }
            Long id = d.getProducto().getId();
            Producto p = productosPorId.computeIfAbsent(id, k ->
                    productoRepo.findById(k).orElseThrow(() -> new NegocioException(
                            "El producto \"" + d.getProducto().getDescripcion() + "\" ya no existe.")));
            d.setProducto(p);
            recalcularPrecio(d);

            if (d.getDescuento() < 0 || d.getImporte() < 0) {
                throw new NegocioException(
                        "El importe de \"" + p.getDescripcion() + "\" no puede ser negativo.");
            }
            cantidadPorProducto.merge(id, d.getCantidad(), Integer::sum);
        }

        // 2) Validar TODO el stock (sumando renglones repetidos) antes de tocar nada
        for (Map.Entry<Long, Integer> e : cantidadPorProducto.entrySet()) {
            validarStock(productosPorId.get(e.getKey()), e.getValue());
        }

        // 3) Total y pago con los importes ya recalculados
        double total = calcularTotal(detalles);
        if (formaPago == Venta.FormaPago.EFECTIVO && montoPagado + 0.001 < total) {
            throw new NegocioException(String.format(
                    "El pago ($%.2f) es menor que el total ($%.2f).", montoPagado, total));
        }
        if (formaPago != Venta.FormaPago.EFECTIVO) {
            montoPagado = total; // con tarjeta o crédito se cobra exactamente el total
        }

        // 4) Armar la venta y descontar inventario
        Venta venta = new Venta();
        venta.setFecha(LocalDateTime.now());
        venta.setFormaPago(formaPago);
        venta.setTotal(total);
        venta.setPagoCon(redondear(montoPagado));
        venta.setCambio(redondear(montoPagado - total));
        venta.setCajero(cajeroRepo.findById(cajeroSesion.getId()).orElse(null));
        venta.setCajeroNombre(cajeroSesion.getNombreCompleto());

        for (Map.Entry<Long, Integer> e : cantidadPorProducto.entrySet()) {
            Producto p = productosPorId.get(e.getKey());
            if (p.isUsaInventario()) {
                p.setCantidadActual(p.getCantidadActual() - e.getValue());
                productoRepo.save(p);
            }
        }
        for (DetalleVenta d : detalles) {
            d.setVenta(venta);
            venta.getDetalles().add(d);
        }

        return ventaRepo.save(venta);
    }

    // ── Historial ─────────────────────────────────────────────────────────

    /** Ventas entre dos fechas (inclusive), con sus renglones cargados */
    public List<Venta> ventasEntre(LocalDate desde, LocalDate hasta) {
        if (desde == null) desde = LocalDate.now();
        if (hasta == null) hasta = desde;
        if (hasta.isBefore(desde)) {
            throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        }
        return ventaRepo.findConDetallesEntre(
                desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
    }

    // ── Corte del día ─────────────────────────────────────────────────────

    public double totalVentasHoy() {
        LocalDateTime inicio = LocalDate.now().atStartOfDay();
        LocalDateTime fin    = inicio.plusDays(1);
        Double total = ventaRepo.sumTotalEntre(inicio, fin);
        return total != null ? total : 0.0;
    }

    /** Resumen de las ventas de hoy para la pantalla de Corte */
    public ResumenCorte resumenDelDia() {
        ResumenCorte r = new ResumenCorte();
        for (Venta v : ventasEntre(LocalDate.now(), LocalDate.now())) {
            r.ventasTotales += v.getTotal();
            switch (v.getFormaPago()) {
                case EFECTIVO -> r.efectivo += v.getTotal();
                case TARJETA  -> r.tarjeta  += v.getTotal();
                case CREDITO  -> r.credito  += v.getTotal();
            }
            for (DetalleVenta d : v.getDetalles()) {
                Producto p = d.getProducto();
                r.ganancia += d.getImporte() - p.getPrecioCosto() * d.getCantidad();
                Departamento dep = p.getDepartamento();
                String nombre = dep != null ? dep.getNombre() : "Sin departamento";
                r.porDepartamento.merge(nombre, d.getImporte(), Double::sum);
            }
        }
        r.ventasTotales = redondear(r.ventasTotales);
        r.efectivo      = redondear(r.efectivo);
        r.tarjeta       = redondear(r.tarjeta);
        r.credito       = redondear(r.credito);
        r.ganancia      = redondear(r.ganancia);
        return r;
    }

    /** Totales del día agrupados; es un simple contenedor de datos */
    public static class ResumenCorte {
        public double ventasTotales;
        public double efectivo;
        public double tarjeta;
        public double credito;
        public double ganancia;
        public final Map<String, Double> porDepartamento = new LinkedHashMap<>();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Redondea a centavos para evitar residuos de punto flotante */
    public static double redondear(double monto) {
        return Math.round(monto * 100.0) / 100.0;
    }
}
