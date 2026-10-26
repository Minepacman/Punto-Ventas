package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.TicketPendienteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lógica de negocio de ventas.
 *
 * Todas las operaciones que modifican la BD deben ser @Transactional
 * para que SQLite aplique los cambios en bloque (o revierta si falla algo).
 *
 * cobrar() NO confía en los precios/descuentos que trae el ticket de la UI:
 * dentro de la transacción vuelve a leer cada producto, recalcula precio,
 * descuento y promoción, vuelve a verificar permisos y toma al cajero de
 * la sesión activa.
 *
 * Descuentos (PRO-09): por renglón aplica SOLO UNO, con esta prioridad:
 * descuento manual > mayoreo > promoción automática.
 */
@Service
public class VentaService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Autowired private VentaRepository           ventaRepo;
    @Autowired private ProductoRepository        productoRepo;
    @Autowired private CajeroRepository          cajeroRepo;
    @Autowired private TicketPendienteRepository ticketPendienteRepo;
    @Autowired private PromocionService          promocionService;
    @Autowired private ProductoService           productoService;
    @Autowired private ConfiguracionService      configuracionService;
    @Autowired private SesionService             sesionService;
    @Autowired private AuditoriaService          auditoriaService;

    // ── Búsqueda de productos ─────────────────────────────────────────────

    /** Producto ACTIVO por código exacto (los descontinuados no se venden) */
    public Optional<Producto> buscarPorCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) return Optional.empty();
        return productoRepo.findByCodigoBarras(codigo.trim()).filter(Producto::isActivo);
    }

    public List<Producto> buscarPorNombre(String texto) {
        return productoRepo.findByDescripcionContainingIgnoreCase(texto).stream()
                .filter(Producto::isActivo).toList();
    }

    // ── Armado del ticket ─────────────────────────────────────────────────

    /** Crea un renglón validando existencia (sin permitir venta sin stock) */
    public DetalleVenta crearDetalle(Producto producto, double cantidad) {
        return crearDetalle(producto, cantidad, false);
    }

    /**
     * Crea un renglón del ticket con el precio de lista del producto y la
     * promoción vigente (si la hay).
     * @param permitirSinStock true cuando el usuario tiene VENDER_SIN_STOCK
     *        o ya obtuvo autorización; cobrar() lo vuelve a verificar.
     */
    public DetalleVenta crearDetalle(Producto producto, double cantidad, boolean permitirSinStock) {
        validarCantidad(producto, cantidad);
        if (!producto.isActivo()) {
            throw new NegocioException("\"" + producto.getDescripcion() + "\" está desactivado y no se puede vender.");
        }
        if (!permitirSinStock) validarStock(producto, cantidad);

        DetalleVenta d = new DetalleVenta();
        d.setProducto(producto);
        d.setCantidad(cantidad);
        recalcularPrecio(d);
        return d;
    }

    /**
     * Fija precio, descuento y tipo de descuento del renglón a partir del
     * producto que tiene asignado y de lo que el cajero marcó (mayoreo o
     * descuento manual). Solo UNO aplica (PRO-09):
     *   1) descuento manual, 2) mayoreo, 3) promoción automática.
     * Llamar siempre que cambie la cantidad, el producto o las marcas.
     */
    public void recalcularPrecio(DetalleVenta d) {
        recalcularPrecio(d, new java.util.HashMap<>());
    }

    /**
     * Recalcula TODOS los renglones del ticket en orden, compartiendo entre
     * ellos los límites de cada promoción (máximo por ticket y "hasta agotar").
     */
    public void recalcularTicket(List<DetalleVenta> detalles) {
        Map<Long, Double> usadas = new java.util.HashMap<>();
        for (DetalleVenta d : detalles) recalcularPrecio(d, usadas);
    }

    /**
     * @param piezasUsadasEnTicket piezas que otros renglones del mismo ticket ya
     *        tomaron de cada promoción (id → piezas); se actualiza aquí
     */
    public void recalcularPrecio(DetalleVenta d, Map<Long, Double> piezasUsadasEnTicket) {
        Producto p = d.getProducto();
        double lista = p.getPrecioVenta();
        d.setPrecioLista(lista);
        d.setCostoUnitario(p.getPrecioCosto());
        d.setPromocionId(null);
        d.setPromocionNombre(null);
        d.setPiezasPromocion(null);

        if (d.tieneDescuentoManual()) {
            d.setPrecioVenta(lista);
            double bruto = lista * d.getCantidad();
            double descuento = d.getDescuentoManualPct() != null && d.getDescuentoManualPct() > 0
                    ? bruto * Math.min(100, d.getDescuentoManualPct()) / 100.0
                    : Math.min(bruto, d.getDescuentoManualImporte() != null ? d.getDescuentoManualImporte() : 0);
            d.setDescuento(redondear(Math.max(0, descuento)));
            d.setTipoDescuento(DetalleVenta.TipoDescuento.MANUAL);
            return;
        }

        if (d.isMayoreo() && p.getPrecioMayoreo() > 0) {
            d.setPrecioVenta(p.getPrecioMayoreo());
            d.setDescuento(0);
            d.setTipoDescuento(DetalleVenta.TipoDescuento.MAYOREO);
            return;
        }
        d.setMayoreo(false);
        d.setPrecioVenta(lista);

        Optional<Promocion> promo = promocionService.promocionVigente(p);
        double piezas = 0, descuento = 0;
        if (promo.isPresent()) {
            double yaUsadas = piezasUsadasEnTicket.getOrDefault(promo.get().getId(), 0.0);
            piezas = promo.get().piezasConPromocion(d.getCantidad(), yaUsadas);
            descuento = promo.get().descuentoPara(lista, d.getCantidad(), piezas);
        }
        if (promo.isPresent() && descuento > 0) {
            d.setDescuento(redondear(descuento));
            d.setTipoDescuento(DetalleVenta.TipoDescuento.PROMOCION);
            d.setPromocionId(promo.get().getId());
            d.setPromocionNombre(promo.get().getNombre());
            d.setPiezasPromocion(piezas);
            piezasUsadasEnTicket.merge(promo.get().getId(), piezas, Double::sum);
        } else {
            d.setDescuento(0);
            d.setTipoDescuento(DetalleVenta.TipoDescuento.NINGUNO);
        }
    }

    /** Compatibilidad: antes solo recalculaba la promoción */
    public void aplicarPromocion(DetalleVenta d) {
        recalcularPrecio(d);
    }

    /** Activa/desactiva mayoreo en el renglón (APLICAR_MAYOREO). Quita el descuento manual. */
    public void aplicarMayoreo(DetalleVenta d, boolean activar, Collection<Autorizacion> autorizaciones) {
        if (activar) {
            sesionService.verificarPermiso(Permiso.APLICAR_MAYOREO, autorizaciones);
            if (d.getProducto().getPrecioMayoreo() <= 0) {
                throw new NegocioException("\"" + d.getProducto().getDescripcion()
                        + "\" no tiene precio de mayoreo registrado.");
            }
            d.limpiarDescuentoManual();
        }
        d.setMayoreo(activar);
        recalcularPrecio(d);
    }

    /**
     * Descuento manual por renglón (DESCUENTO_MANUAL): porcentaje (0-100) O
     * importe fijo. Quita el mayoreo del renglón. Pasar ambos en 0/null
     * elimina el descuento.
     */
    public void aplicarDescuentoManual(DetalleVenta d, Double porcentaje, Double importe,
                                       Collection<Autorizacion> autorizaciones) {
        boolean quitar = (porcentaje == null || porcentaje <= 0) && (importe == null || importe <= 0);
        if (!quitar) {
            sesionService.verificarPermiso(Permiso.DESCUENTO_MANUAL, autorizaciones);
            if (porcentaje != null && (!Double.isFinite(porcentaje) || porcentaje > 100)) {
                throw new NegocioException("El porcentaje de descuento debe estar entre 0 y 100.");
            }
            if (importe != null && !Double.isFinite(importe)) {
                throw new NegocioException("El importe de descuento no es válido.");
            }
            if (importe != null && importe > d.getProducto().getPrecioVenta() * d.getCantidad() + 0.001) {
                throw new NegocioException("El descuento no puede ser mayor al importe del renglón.");
            }
        }
        d.setDescuentoManualPct(porcentaje != null && porcentaje > 0 ? porcentaje : null);
        d.setDescuentoManualImporte(porcentaje != null && porcentaje > 0 ? null
                : (importe != null && importe > 0 ? importe : null));
        if (!quitar) d.setMayoreo(false);
        recalcularPrecio(d);
    }

    public double calcularTotal(Collection<DetalleVenta> detalles) {
        return redondear(detalles.stream().mapToDouble(DetalleVenta::getImporte).sum());
    }

    /** Cantidad > 0, finita y entera salvo productos a granel (PRD-08) */
    public void validarCantidad(Producto producto, double cantidad) {
        if (!Double.isFinite(cantidad) || cantidad <= 0) {
            throw new NegocioException("La cantidad debe ser mayor a cero.");
        }
        if (cantidad > 100000) {
            throw new NegocioException("La cantidad es demasiado grande.");
        }
        ProductoService.validarDecimales(producto, cantidad);
    }

    /**
     * Verifica que haya existencia suficiente (solo para productos con
     * inventario). Lanza StockInsuficienteException si no alcanza.
     */
    public void validarStock(Producto producto, double cantidadDeseada) {
        if (!producto.isUsaInventario()) return;
        if (cantidadDeseada > producto.getCantidadActual() + 0.000001) {
            throw new StockInsuficienteException(producto, cantidadDeseada);
        }
    }

    /** ¿El usuario puede vender sin existencia (permiso propio o autorización)? */
    public boolean puedeVenderSinStock(Collection<Autorizacion> autorizaciones) {
        return sesionService.tienePermiso(Permiso.VENDER_SIN_STOCK)
                || Autorizacion.buscar(autorizaciones, Permiso.VENDER_SIN_STOCK) != null;
    }

    // ── Registro de una venta completa ────────────────────────────────────

    /** Compatibilidad: una sola forma de pago */
    @Transactional
    public Venta cobrar(List<DetalleVenta> detalles, Venta.FormaPago formaPago, double montoPagado) {
        return cobrar(detalles, List.of(new PagoVenta(formaPago, montoPagado)), null);
    }

    /**
     * Guarda la venta con sus renglones y pagos, y descuenta el inventario,
     * todo en una sola transacción.
     *
     *  - El cajero se toma de la sesión activa (no de la UI).
     *  - Precio, descuento y promoción de cada renglón se recalculan desde la BD
     *    y se vuelven a verificar los permisos (mayoreo, descuento manual,
     *    venta bajo costo, venta sin existencia) con las autorizaciones dadas.
     *  - Si un producto aparece en varios renglones, el stock se valida
     *    contra la SUMA de sus cantidades.
     *  - Pago dividido (VEN-07): la suma de los pagos cubre el total; las
     *    formas distintas de efectivo no pueden exceder el total; el cambio
     *    sale del efectivo.
     *  - Si algo falla se lanza NegocioException y NO se guarda nada.
     */
    @Transactional
    public Venta cobrar(List<DetalleVenta> detalles, List<PagoVenta> pagos,
                        Collection<Autorizacion> autorizaciones) {

        if (detalles == null || detalles.isEmpty()) {
            throw new NegocioException("El ticket está vacío.");
        }
        Cajero cajeroSesion = sesionService.requerirSesion();
        Configuracion config = configuracionService.obtener();
        Set<String> autorizadores = new LinkedHashSet<>();

        // 1) Releer cada producto y recalcular precio + descuento desde la BD
        //    (los límites de cada promoción se comparten entre los renglones del ticket)
        Map<Long, Producto> productosPorId      = new LinkedHashMap<>();
        Map<Long, Double>   cantidadPorProducto = new LinkedHashMap<>();
        Map<Long, Double>   piezasPromoUsadas   = new java.util.HashMap<>();
        for (DetalleVenta d : detalles) {
            if (d.getProducto() == null || d.getProducto().getId() == null) {
                throw new NegocioException("Hay un renglón del ticket sin producto.");
            }
            Long id = d.getProducto().getId();
            Producto p = productosPorId.computeIfAbsent(id, k ->
                    productoRepo.findById(k).orElseThrow(() -> new NegocioException(
                            "El producto \"" + d.getProducto().getDescripcion() + "\" ya no existe.")));
            if (!p.isActivo()) {
                throw new NegocioException("\"" + p.getDescripcion() + "\" está desactivado y no se puede vender.");
            }
            validarCantidad(p, d.getCantidad());
            d.setProducto(p);
            recalcularPrecio(d, piezasPromoUsadas);

            if (d.getDescuento() < 0 || d.getImporte() < -0.001) {
                throw new NegocioException(
                        "El importe de \"" + p.getDescripcion() + "\" no puede ser negativo.");
            }
            // Permisos del renglón (se vuelven a verificar aquí, no solo en la UI)
            d.setAutorizadoPorId(null);
            d.setAutorizadoPorUsuario(null);
            if (d.getTipoDescuento() == DetalleVenta.TipoDescuento.MANUAL) {
                sesionService.verificarPermiso(Permiso.DESCUENTO_MANUAL, autorizaciones);
                marcarAutorizador(d, autorizadores, sesionService.autorizacionUsada(Permiso.DESCUENTO_MANUAL, autorizaciones));
            }
            if (d.getTipoDescuento() == DetalleVenta.TipoDescuento.MAYOREO) {
                sesionService.verificarPermiso(Permiso.APLICAR_MAYOREO, autorizaciones);
                marcarAutorizador(d, autorizadores, sesionService.autorizacionUsada(Permiso.APLICAR_MAYOREO, autorizaciones));
            }
            // Vender por debajo del costo (mayoreo o descuento manual) exige CAMBIAR_PRECIOS
            boolean precioElegidoPorUsuario = d.getTipoDescuento() == DetalleVenta.TipoDescuento.MANUAL
                                           || d.getTipoDescuento() == DetalleVenta.TipoDescuento.MAYOREO;
            double unitarioCobrado = d.getImporte() / d.getCantidad();
            if (precioElegidoPorUsuario && p.getPrecioCosto() > 0 && unitarioCobrado + 0.0001 < p.getPrecioCosto()) {
                try {
                    sesionService.verificarPermiso(Permiso.CAMBIAR_PRECIOS, autorizaciones);
                } catch (PermisoRequeridoException e) {
                    // Se relanza como permiso requerido para que la UI ofrezca la autorización
                    throw new PermisoRequeridoException(Permiso.CAMBIAR_PRECIOS, String.format(
                            "\"%s\" quedaría por debajo de su costo ($%.2f por unidad). %s",
                            p.getDescripcion(), p.getPrecioCosto(), e.getMessage()));
                }
                marcarAutorizador(d, autorizadores, sesionService.autorizacionUsada(Permiso.CAMBIAR_PRECIOS, autorizaciones));
            }
            cantidadPorProducto.merge(id, d.getCantidad(), Double::sum);
        }

        // 2) Stock (sumando renglones repetidos). Sin existencia solo con permiso/autorización.
        boolean ventaSinStock = false;
        for (Map.Entry<Long, Double> e : cantidadPorProducto.entrySet()) {
            Producto p = productosPorId.get(e.getKey());
            try {
                validarStock(p, e.getValue());
            } catch (StockInsuficienteException sinStock) {
                try {
                    sesionService.verificarPermiso(Permiso.VENDER_SIN_STOCK, autorizaciones);
                } catch (PermisoRequeridoException pe) {
                    // Permiso requerido (no solo "sin stock") para que la UI ofrezca la autorización
                    throw new PermisoRequeridoException(Permiso.VENDER_SIN_STOCK,
                            sinStock.getMessage() + " " + pe.getMessage());
                }
                ventaSinStock = true;
                Autorizacion aut = sesionService.autorizacionUsada(Permiso.VENDER_SIN_STOCK, autorizaciones);
                for (DetalleVenta d : detalles) {
                    if (d.getProducto().getId().equals(e.getKey())) marcarAutorizador(d, autorizadores, aut);
                }
            }
        }

        // 3) Total y pagos con los importes ya recalculados
        double total = calcularTotal(detalles);
        List<PagoVenta> pagosValidos = validarPagos(pagos, total);
        double totalPagado = redondear(pagosValidos.stream().mapToDouble(PagoVenta::getMonto).sum());
        Venta.FormaPago formaPrincipal = pagosValidos.stream()
                .max(java.util.Comparator.comparingDouble(PagoVenta::getMonto))
                .map(PagoVenta::getFormaPago).orElse(Venta.FormaPago.EFECTIVO);

        // 4) Armar la venta
        Venta venta = new Venta();
        venta.setFecha(LocalDateTime.now());
        venta.setFormaPago(formaPrincipal);
        venta.setTotal(total);
        venta.setPagoCon(totalPagado);
        venta.setCambio(redondear(totalPagado - total));
        venta.setTasaIva(config.getTasaIva());
        venta.setSubtotal(redondear(config.subtotalDe(total)));
        venta.setIva(redondear(total - venta.getSubtotal()));
        venta.setPlazoCancelacionHoras(config.getPlazoCancelacionHoras());
        venta.setCajero(cajeroRepo.findById(cajeroSesion.getId()).orElse(null));
        venta.setCajeroNombre(cajeroSesion.getNombreCompleto());
        venta.setAutorizadaPorNombre(autorizadores.isEmpty() ? null : String.join(", ", autorizadores));

        for (PagoVenta pago : pagosValidos) {
            pago.setVenta(venta);
            venta.getPagos().add(pago);
        }
        for (DetalleVenta d : detalles) {
            d.setVenta(venta);
            venta.getDetalles().add(d);
        }
        Venta guardada = ventaRepo.save(venta);

        // 5) Descontar inventario con bitácora y consumir cupo de promociones
        for (Map.Entry<Long, Double> e : cantidadPorProducto.entrySet()) {
            Producto p = productosPorId.get(e.getKey());
            if (p.isUsaInventario()) {
                double antes = p.getCantidadActual();
                p.setCantidadActual(redondearCantidad(antes - e.getValue()));
                productoRepo.save(p);
                productoService.registrarMovimiento(p, MovimientoInventario.Tipo.VENTA, -e.getValue(),
                        antes, p.getCantidadActual(), "Venta #" + guardada.getId(), null, null, guardada.getId());
                // Aviso solo si ESTA venta lo hizo cruzar el mínimo (INV-03)
                if (antes > p.getCantidadMinima() && p.getCantidadActual() <= p.getCantidadMinima()) {
                    guardada.getAlertasStockBajo().add(p);
                }
            }
        }
        for (DetalleVenta d : detalles) {
            if (d.getPromocionId() != null && d.getPiezasPromocion() != null) {
                promocionService.registrarUso(d.getPromocionId(), d.getPiezasPromocion());
            }
        }

        // 6) El ticket pendiente del cajero ya se cobró
        ticketPendienteRepo.deleteByCajeroId(cajeroSesion.getId());

        if (!autorizadores.isEmpty() || ventaSinStock) {
            auditoriaService.registrar(cajeroSesion, "VENTA_AUTORIZADA",
                    "Venta #" + guardada.getId() + (ventaSinStock ? " (sin existencia)" : "")
                            + " — autorizó: " + String.join(", ", autorizadores),
                    autorizaciones == null ? null : autorizaciones.stream().filter(a -> a != null).findFirst().orElse(null));
        }
        // Las autorizaciones del ticket se agotan al cobrarlo (un solo uso)
        Autorizacion.consumirTodas(autorizaciones);
        return guardada;
    }

    /** Pagos: montos válidos, sin crédito, suma ≥ total y no-efectivo ≤ total */
    private List<PagoVenta> validarPagos(List<PagoVenta> pagos, double total) {
        if (pagos == null || pagos.isEmpty()) {
            throw new NegocioException("Indica cómo pagó el cliente.");
        }
        List<PagoVenta> validos = new ArrayList<>();
        double noEfectivo = 0;
        for (PagoVenta p : pagos) {
            if (p == null || p.getFormaPago() == null) continue;
            if (!Double.isFinite(p.getMonto()) || p.getMonto() < 0) {
                throw new NegocioException("El monto pagado no es válido.");
            }
            if (p.getMonto() <= 0) continue;
            if (p.getFormaPago() == Venta.FormaPago.CREDITO) {
                throw new NegocioException("La venta a crédito no está disponible hasta contar con el módulo de clientes.");
            }
            if (p.getFormaPago() != Venta.FormaPago.EFECTIVO) noEfectivo += p.getMonto();
            validos.add(new PagoVenta(p.getFormaPago(), redondear(p.getMonto())));
        }
        if (validos.isEmpty()) {
            throw new NegocioException("El monto pagado no es válido.");
        }
        double suma = validos.stream().mapToDouble(PagoVenta::getMonto).sum();
        if (noEfectivo > total + 0.001) {
            throw new NegocioException(String.format(
                    "El pago con tarjeta ($%.2f) no puede ser mayor que el total ($%.2f).", noEfectivo, total));
        }
        if (suma + 0.001 < total) {
            throw new NegocioException(String.format(
                    "El pago ($%.2f) es menor que el total ($%.2f).", suma, total));
        }
        return validos;
    }

    /** Productos de la venta que quedaron en o por debajo de su mínimo (INV-03) */
    public List<Producto> productosConStockBajoDe(Venta venta) {
        List<Producto> bajos = new ArrayList<>();
        Set<Long> vistos = new LinkedHashSet<>();
        for (DetalleVenta d : venta.getDetalles()) {
            Long id = d.getProducto().getId();
            if (!vistos.add(id)) continue;
            productoRepo.findById(id)
                    .filter(ProductoService::tieneStockBajo)
                    .ifPresent(bajos::add);
        }
        return bajos;
    }

    // ── Cancelación (VEN-14, VEN-19) ──────────────────────────────────────

    /**
     * Marca la venta como cancelada (nunca se borra), regresa la mercancía
     * al inventario y la excluye de los totales. Requiere CANCELAR_VENTA o
     * autorización de administrador, dentro del plazo vigente cuando se
     * hizo la venta.
     */
    @Transactional
    public Venta cancelar(Long ventaId, String motivo, Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.CANCELAR_VENTA, autorizaciones);
        Cajero quien = sesionService.requerirSesion();
        if (motivo == null || motivo.isBlank()) {
            throw new NegocioException("Indica el motivo de la cancelación.");
        }
        Venta v = ventaRepo.findConDetallesById(ventaId)
                .orElseThrow(() -> new NegocioException("La venta ya no existe."));
        if (v.isCancelada()) {
            throw new NegocioException("La venta #" + v.getId() + " ya estaba cancelada.");
        }
        LocalDateTime limite = v.getLimiteCancelacion();
        if (LocalDateTime.now().isAfter(limite)) {
            throw new NegocioException(String.format(
                    "La venta #%d ya no se puede cancelar: el plazo de %d horas venció el %s.",
                    v.getId(), v.getPlazoCancelacionHorasOPorDefecto(), limite.format(FMT)));
        }

        // Regresar mercancía
        Map<Long, Double> porProducto = new LinkedHashMap<>();
        for (DetalleVenta d : v.getDetalles()) {
            porProducto.merge(d.getProducto().getId(), d.getCantidad(), Double::sum);
        }
        for (Map.Entry<Long, Double> e : porProducto.entrySet()) {
            Producto p = productoRepo.findById(e.getKey()).orElse(null);
            if (p == null || !p.isUsaInventario()) continue;
            double antes = p.getCantidadActual();
            p.setCantidadActual(redondearCantidad(antes + e.getValue()));
            productoRepo.save(p);
            productoService.registrarMovimiento(p, MovimientoInventario.Tipo.CANCELACION, e.getValue(),
                    antes, p.getCantidadActual(), "Cancelación venta #" + v.getId(), null, null, v.getId());
        }
        // Devolver cupo de promociones "hasta agotar"
        for (DetalleVenta d : v.getDetalles()) {
            if (d.getPromocionId() != null && d.getPiezasPromocion() != null) {
                promocionService.registrarUso(d.getPromocionId(), -d.getPiezasPromocion());
            }
        }

        Autorizacion aut = sesionService.autorizacionUsada(Permiso.CANCELAR_VENTA, autorizaciones);
        v.setCancelada(true);
        v.setFechaCancelacion(LocalDateTime.now());
        v.setMotivoCancelacion(motivo.trim());
        v.setCanceladaPorId(quien.getId());
        v.setCanceladaPorNombre(quien.getNombreCompleto());
        if (aut != null) {
            v.setCancelacionAutorizadaPorId(aut.administrador().getId());
            v.setCancelacionAutorizadaPor(aut.nombreAdministrador());
        }
        Venta guardada = ventaRepo.save(v);

        auditoriaService.registrar(quien, "VENTA_CANCELADA",
                "Venta #" + v.getId() + " — " + motivo.trim(), aut);
        if (aut != null) aut.consumir();
        return guardada;
    }

    // ── Historial ─────────────────────────────────────────────────────────

    /**
     * Ventas entre dos fechas (inclusive), incluidas las canceladas (con su
     * estado), con renglones y pagos cargados. Requiere VER_HISTORIAL.
     */
    @Transactional(readOnly = true)
    public List<Venta> ventasEntre(LocalDate desde, LocalDate hasta) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        return ventasEntreInterno(desde, hasta);
    }

    /** Misma consulta sin exigir permiso (uso interno: corte, ventas del día) */
    @Transactional(readOnly = true)
    protected List<Venta> ventasEntreInterno(LocalDate desde, LocalDate hasta) {
        if (desde == null) desde = LocalDate.now();
        if (hasta == null) hasta = desde;
        if (hasta.isBefore(desde)) {
            throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        }
        List<Venta> ventas = ventaRepo.findConDetallesEntre(
                desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
        if (!ventas.isEmpty()) ventaRepo.cargarPagos(ventas);   // misma sesión: inicializa v.pagos
        return ventas;
    }

    // ── Corte del día ─────────────────────────────────────────────────────

    /** Total vendido hoy sin contar canceladas */
    public double totalVentasHoy() {
        LocalDateTime inicio = LocalDate.now().atStartOfDay();
        LocalDateTime fin    = inicio.plusDays(1);
        Double total = ventaRepo.sumTotalEntre(inicio, fin);
        return total != null ? total : 0.0;
    }

    /**
     * Resumen de las ventas de hoy para la pantalla de Corte (excluye
     * canceladas). Requiere HACER_CORTE o VER_GANANCIAS; la ganancia solo
     * se incluye si el usuario tiene VER_GANANCIAS.
     */
    @Transactional(readOnly = true)
    public ResumenCorte resumenDelDia() {
        return resumenDe(LocalDate.now());
    }

    @Transactional(readOnly = true)
    public ResumenCorte resumenDe(LocalDate dia) {
        if (!sesionService.tienePermiso(Permiso.HACER_CORTE)) {
            sesionService.verificarPermiso(Permiso.VER_GANANCIAS);
        }
        ResumenCorte r = resumenCompletoDe(dia);
        if (!sesionService.tienePermiso(Permiso.VER_GANANCIAS)) {
            r.ganancia = 0;
            r.gananciaVisible = false;
        }
        return r;
    }

    /** Resumen sin filtro de permisos (uso interno: CajaService.hacerCorte) */
    @Transactional(readOnly = true)
    public ResumenCorte resumenCompletoDe(LocalDate dia) {
        ResumenCorte r = new ResumenCorte();
        for (Venta v : ventasEntreInterno(dia, dia)) {
            if (v.isCancelada()) {
                r.numCanceladas++;
                r.totalCanceladas += v.getTotal();
                continue;
            }
            r.numVentas++;
            r.ventasTotales += v.getTotal();
            r.subtotal += v.getSubtotalODefault();
            r.iva      += v.getIvaODefault();
            if (v.getPagos().isEmpty()) {
                // ventas anteriores al pago dividido: todo a su forma de pago
                sumarPago(r, v.getFormaPago(), v.getTotal());
            } else {
                double cambio = v.getCambio();
                for (PagoVenta p : v.getPagos()) {
                    double monto = p.getMonto();
                    if (p.getFormaPago() == Venta.FormaPago.EFECTIVO) monto -= cambio; // el cambio sale del efectivo
                    sumarPago(r, p.getFormaPago(), monto);
                }
            }
            for (DetalleVenta d : v.getDetalles()) {
                Producto p = d.getProducto();
                r.ganancia += d.getImporte() - d.getCostoUnitarioODefault() * d.getCantidad();
                Departamento dep = p.getDepartamento();
                String nombre = dep != null ? dep.getNombre() : "Sin departamento";
                r.porDepartamento.merge(nombre, d.getImporte(), Double::sum);
            }
        }
        r.ventasTotales   = redondear(r.ventasTotales);
        r.subtotal        = redondear(r.subtotal);
        r.iva             = redondear(r.iva);
        r.efectivo        = redondear(r.efectivo);
        r.tarjeta         = redondear(r.tarjeta);
        r.credito         = redondear(r.credito);
        r.ganancia        = redondear(r.ganancia);
        r.totalCanceladas = redondear(r.totalCanceladas);
        r.productosBajos.addAll(productoService.productosConStockBajo());
        return r;
    }

    private static void sumarPago(ResumenCorte r, Venta.FormaPago forma, double monto) {
        if (forma == null) forma = Venta.FormaPago.EFECTIVO;
        switch (forma) {
            case EFECTIVO -> r.efectivo += monto;
            case TARJETA  -> r.tarjeta  += monto;
            case CREDITO  -> r.credito  += monto;
        }
    }

    /** Totales del día agrupados; es un simple contenedor de datos */
    public static class ResumenCorte {
        public double ventasTotales;
        public double subtotal;
        public double iva;
        public double efectivo;
        public double tarjeta;
        public double credito;
        public double ganancia;
        public int    numVentas;
        public int    numCanceladas;
        public double totalCanceladas;
        /** false cuando quien consulta no tiene VER_GANANCIAS (ganancia = 0) */
        public boolean gananciaVisible = true;
        public final Map<String, Double> porDepartamento = new LinkedHashMap<>();
        public final List<Producto> productosBajos = new ArrayList<>();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Deja en el renglón quién autorizó (id + login) y acumula el nombre para la venta */
    private static void marcarAutorizador(DetalleVenta d, Set<String> autorizadores, Autorizacion aut) {
        if (aut == null || aut.administrador() == null) return;
        d.setAutorizadoPorId(aut.administrador().getId());
        d.setAutorizadoPorUsuario(aut.administrador().getUsuario());
        autorizadores.add(aut.nombreAdministrador());
    }

    /** Redondea a centavos para evitar residuos de punto flotante */
    public static double redondear(double monto) {
        return Math.round(monto * 100.0) / 100.0;
    }

    /** Redondea cantidades a milésimas (granel) */
    public static double redondearCantidad(double cantidad) {
        return Math.round(cantidad * 1000.0) / 1000.0;
    }
}
