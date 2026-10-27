package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.repository.PromocionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Promociones por producto o por departamento (PRO-01..PRO-08).
 *
 * VentaService consulta aquí la promoción vigente para calcular el
 * descuento de cada renglón. Si varias aplican gana la creada más
 * recientemente (PRO-05). Crear/editar/borrar exige CREAR_PROMOCIONES.
 */
@Service
public class PromocionService {

    @Autowired private PromocionRepository promocionRepo;
    @Autowired private SesionService       sesionService;
    @Autowired private AuditoriaService    auditoriaService;

    // ── Consultas ─────────────────────────────────────────────────────────

    public List<Promocion> listarTodas() {
        return promocionRepo.findAllByOrderByActivaDescIdDesc();
    }

    /** Promoción aplicable ahora mismo a un producto (la más reciente de las vigentes) */
    public Optional<Promocion> promocionVigente(Producto producto) {
        return promocionVigente(producto, LocalDateTime.now());
    }

    public Optional<Promocion> promocionVigente(Producto producto, LocalDateTime momento) {
        if (producto == null || producto.getId() == null) return Optional.empty();
        Long depId = producto.getDepartamento() != null ? producto.getDepartamento().getId() : null;
        return promocionRepo.findActivasPara(producto.getId(), depId).stream()
                .filter(pr -> pr.aplicaA(producto))
                .filter(pr -> pr.estaVigente(momento))
                .findFirst();   // ya vienen ordenadas por id DESC = la más reciente primero
    }

    /** Compatibilidad: vigencia evaluada a mediodía de la fecha dada */
    public Optional<Promocion> promocionVigente(Producto producto, LocalDate fecha) {
        return promocionVigente(producto, fecha.atTime(12, 0));
    }

    /** Precio unitario de hoy para el producto, ya con promoción si aplica (solo informativo) */
    public double precioVigente(Producto producto) {
        return promocionVigente(producto)
                .map(pr -> pr.precioConPromocion(producto.getPrecioVenta()))
                .orElse(producto.getPrecioVenta());
    }

    // ── Altas / cambios ───────────────────────────────────────────────────

    @Transactional
    public Promocion guardar(Promocion promo) {
        sesionService.verificarPermiso(Permiso.CREAR_PROMOCIONES);
        validar(promo);
        boolean nueva = promo.getId() == null;
        Promocion guardada = promocionRepo.save(promo);
        auditoriaService.registrar(sesionService.getCajeroActual(),
                nueva ? "PROMOCION_CREADA" : "PROMOCION_MODIFICADA", guardada.getNombre(), (Cajero) null);
        return guardada;
    }

    @Transactional
    public void eliminar(Long id) {
        sesionService.verificarPermiso(Permiso.CREAR_PROMOCIONES);
        promocionRepo.findById(id).ifPresent(p -> {
            promocionRepo.delete(p);
            auditoriaService.registrar(sesionService.getCajeroActual(), "PROMOCION_ELIMINADA", p.getNombre(), (Cajero) null);
        });
    }

    /** Suma piezas vendidas con la promoción ("hasta agotar"). Lo llama VentaService al cobrar. */
    @Transactional
    public void registrarUso(Long promocionId, double piezas) {
        if (promocionId == null || piezas == 0) return;
        promocionRepo.findById(promocionId).ifPresent(p -> {
            p.setPiezasUsadas(Math.max(0, p.getPiezasUsadasOCero() + piezas));
            promocionRepo.save(p);
        });
    }

    // ── Validación ────────────────────────────────────────────────────────

    private void validar(Promocion p) {
        if (p.getProducto() == null && p.getDepartamento() == null) {
            throw new NegocioException("Selecciona el producto o el departamento de la promoción.");
        }
        if (p.getProducto() != null && p.getDepartamento() != null) {
            throw new NegocioException("La promoción aplica a un producto O a un departamento, no a ambos.");
        }
        if (p.getTipo() == null) {
            throw new NegocioException("Selecciona el tipo de promoción.");
        }
        switch (p.getTipo()) {
            case PORCENTAJE -> {
                if (p.getValor() <= 0) throw new NegocioException("El porcentaje debe ser mayor a cero.");
                if (p.getValor() > 100) throw new NegocioException("El porcentaje de descuento no puede ser mayor a 100.");
            }
            case PRECIO_FIJO -> {
                if (p.getProducto() == null) {
                    throw new NegocioException("El precio fijo solo se puede usar en promociones por producto.");
                }
                if (p.getValor() <= 0) throw new NegocioException("El precio promocional debe ser mayor a cero.");
                if (p.getValor() >= p.getProducto().getPrecioVenta()) {
                    throw new NegocioException(String.format(
                            "El precio promocional debe ser menor al precio de venta (%s).",
                            Moneda.formato(p.getProducto().getPrecioVenta())));
                }
                if (p.getValor() < p.getProducto().getPrecioCosto()) {
                    // Vender bajo costo solo con permiso de precios (regla por defecto PRD-06)
                    sesionService.verificarPermiso(Permiso.CAMBIAR_PRECIOS);
                }
            }
            case NXM -> {
                Integer n = p.getCantidadN(), m = p.getCantidadM();
                if (n == null || m == null || n <= 0 || m <= 0) {
                    throw new NegocioException("Indica cuántas piezas lleva (N) y cuántas paga (M), ej. 2x1.");
                }
                if (m >= n) throw new NegocioException("En una promoción NxM, N debe ser mayor que M (ej. 3x2).");
                p.setValor(0);
            }
        }
        if (p.getFechaInicio() == null) {
            throw new NegocioException("La fecha de inicio es obligatoria.");
        }
        if (p.getFechaFin() != null && p.getFechaFin().isBefore(p.getFechaInicio())) {
            throw new NegocioException("La fecha de fin no puede ser anterior a la de inicio.");
        }
        LocalTime ini, fin;
        try {
            ini = Promocion.parsearHora(p.getHoraInicio());
            fin = Promocion.parsearHora(p.getHoraFin());
        } catch (Exception e) {
            throw new NegocioException("El horario debe tener el formato HH:mm (ej. 09:00).");
        }
        if ((ini == null) != (fin == null)) {
            throw new NegocioException("Indica la hora de inicio y la de fin del horario, o deja ambas vacías.");
        }
        if (ini != null && !fin.isAfter(ini)) {
            throw new NegocioException("La hora de fin debe ser posterior a la de inicio.");
        }
        if (p.getDiasSemana() != null && p.getDiasSemana().isBlank()) {
            throw new NegocioException("Selecciona al menos un día de la semana para la promoción.");
        }
        if (p.getDiasSemana() != null && !p.getDiasSemana().isBlank()) {
            for (String dia : p.getDiasSemana().split(",")) {
                try {
                    if (!dia.isBlank()) java.time.DayOfWeek.valueOf(dia.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new NegocioException("Día de la semana no válido: " + dia.trim());
                }
            }
        }
        if (p.getLimitePiezas() != null && p.getLimitePiezas() <= 0) {
            throw new NegocioException("El límite de piezas (hasta agotar) debe ser mayor a cero.");
        }
        if (p.getLimitePorTicket() != null && p.getLimitePorTicket() <= 0) {
            throw new NegocioException("El límite por ticket debe ser mayor a cero.");
        }
        if (p.getDescripcion() != null) {
            p.setDescripcion(p.getDescripcion().trim());
        }
    }
}
