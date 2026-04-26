package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lógica de negocio de ventas.
 *
 * Todas las operaciones que modifican la BD deben ser @Transactional
 * para que SQLite aplique los cambios en bloque (o revierta si falla algo).
 */
@Service
public class VentaService {

    @Autowired private VentaRepository    ventaRepo;
    @Autowired private ProductoRepository productoRepo;

    // ── Búsqueda de productos ─────────────────────────────────────────────

    public Optional<Producto> buscarPorCodigo(String codigo) {
        return productoRepo.findByCodigoBarras(codigo);
    }

    public List<Producto> buscarPorNombre(String texto) {
        return productoRepo.findByDescripcionContainingIgnoreCase(texto);
    }

    // ── Registro de una venta completa ────────────────────────────────────

    @Transactional
    public Venta cobrar(List<DetalleVenta> detalles,
                        Venta.FormaPago formaPago,
                        double montoPagado,
                        String cajero) {

        double total = detalles.stream()
                .mapToDouble(DetalleVenta::getImporte)
                .sum();

        Venta venta = new Venta();
        venta.setFormaPago(formaPago);
        venta.setTotal(total);
        venta.setPagoCon(montoPagado);
        venta.setCambio(montoPagado - total);
        venta.setCajeroNombre(cajero);

        detalles.forEach(d -> {
            d.setVenta(venta);
            venta.getDetalles().add(d);

            // Descontar inventario
            if (d.getProducto().isUsaInventario()) {
                Producto p = d.getProducto();
                p.setCantidadActual(p.getCantidadActual() - d.getCantidad());
                productoRepo.save(p);
            }
        });

        return ventaRepo.save(venta);
    }

    // ── Corte del día ─────────────────────────────────────────────────────

    public double totalVentasHoy() {
        LocalDateTime inicio = LocalDate.now().atStartOfDay();
        LocalDateTime fin    = inicio.plusDays(1);
        Double total = ventaRepo.sumTotalByFechaBetween(inicio, fin);
        return total != null ? total : 0.0;
    }
}