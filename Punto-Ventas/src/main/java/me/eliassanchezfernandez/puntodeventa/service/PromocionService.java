package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.repository.PromocionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Promociones por producto.
 *
 * VentaService consulta aquí la promoción vigente para calcular
 * el descuento de cada renglón del ticket.
 */
@Service
public class PromocionService {

    @Autowired private PromocionRepository promocionRepo;

    // ── Consultas ─────────────────────────────────────────────────────────

    public List<Promocion> listarTodas() {
        return promocionRepo.findAllByOrderByActivaDescFechaInicioDesc();
    }

    /**
     * Promoción aplicable hoy a un producto. Si hay varias vigentes,
     * se elige la que más beneficia al cliente (menor precio final).
     */
    public Optional<Promocion> promocionVigente(Producto producto) {
        return promocionVigente(producto, LocalDate.now());
    }

    public Optional<Promocion> promocionVigente(Producto producto, LocalDate fecha) {
        if (producto == null || producto.getId() == null) return Optional.empty();
        return promocionRepo.findByProductoIdAndActivaTrue(producto.getId()).stream()
                .filter(pr -> pr.estaVigente(fecha))
                .min(Comparator.comparingDouble(
                        pr -> pr.precioConPromocion(producto.getPrecioVenta())));
    }

    /** Precio unitario de hoy para el producto, ya con promoción si aplica */
    public double precioVigente(Producto producto) {
        return promocionVigente(producto)
                .map(pr -> pr.precioConPromocion(producto.getPrecioVenta()))
                .orElse(producto.getPrecioVenta());
    }

    // ── Altas / cambios ───────────────────────────────────────────────────

    @Transactional
    public Promocion guardar(Promocion promo) {
        validar(promo);
        return promocionRepo.save(promo);
    }

    @Transactional
    public void eliminar(Long id) {
        promocionRepo.deleteById(id);
    }

    // ── Validación ────────────────────────────────────────────────────────

    private void validar(Promocion p) {
        if (p.getProducto() == null) {
            throw new NegocioException("Selecciona el producto de la promoción.");
        }
        if (p.getTipo() == null) {
            throw new NegocioException("Selecciona el tipo de promoción.");
        }
        if (p.getValor() <= 0) {
            throw new NegocioException("El valor de la promoción debe ser mayor a cero.");
        }
        if (p.getTipo() == Promocion.Tipo.PORCENTAJE && p.getValor() > 100) {
            throw new NegocioException("El porcentaje de descuento no puede ser mayor a 100.");
        }
        if (p.getTipo() == Promocion.Tipo.PRECIO_FIJO
                && p.getValor() >= p.getProducto().getPrecioVenta()) {
            throw new NegocioException(String.format(
                    "El precio promocional debe ser menor al precio de venta ($%.2f).",
                    p.getProducto().getPrecioVenta()));
        }
        if (p.getFechaInicio() == null) {
            throw new NegocioException("La fecha de inicio es obligatoria.");
        }
        if (p.getFechaFin() != null && p.getFechaFin().isBefore(p.getFechaInicio())) {
            throw new NegocioException("La fecha de fin no puede ser anterior a la de inicio.");
        }
        if (p.getDescripcion() != null) {
            p.setDescripcion(p.getDescripcion().trim());
        }
    }
}
