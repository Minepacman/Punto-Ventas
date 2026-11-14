package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Categoria;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Tipo;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoCajaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Entradas y salidas de efectivo del cajón (CRT-08): meter cambio, retirar
 * dinero, pagar a un proveedor o un gasto, devolver efectivo por una
 * cancelación. Requiere MOVIMIENTOS_CAJA o la autorización puntual de un
 * administrador (se consume al registrar). Cada movimiento queda con cajero,
 * caja, hora, motivo, cuánto había y cuánto queda, y en la bitácora; suma o
 * resta en el efectivo esperado del corte ({@link CajaService}).
 */
@Service
public class MovimientoCajaService {

    /** Tope por movimiento: evita teclear de más (p. ej. 500000 en lugar de 500) */
    public static final double MONTO_MAXIMO = 999_999.99;

    @Autowired private MovimientoCajaRepository movimientoRepo;
    @Autowired private ProveedorRepository proveedorRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private CajaService cajaService;
    @Autowired private IdentidadCaja identidadCaja;

    /** Datos de un movimiento a registrar; los opcionales pueden ir null */
    public record Solicitud(Tipo tipo, Categoria categoria, double monto, String concepto,
                            Long proveedorId, Long ordenCompraId, Long ventaId) {
        public static Solicitud entrada(Categoria c, double monto, String concepto) {
            return new Solicitud(Tipo.ENTRADA, c, monto, concepto, null, null, null);
        }
        public static Solicitud salida(Categoria c, double monto, String concepto) {
            return new Solicitud(Tipo.SALIDA, c, monto, concepto, null, null, null);
        }
        public static Solicitud pagoProveedor(Long proveedorId, Long ordenCompraId, double monto, String concepto) {
            return new Solicitud(Tipo.SALIDA, Categoria.PAGO_PROVEEDOR, monto, concepto, proveedorId, ordenCompraId, null);
        }
        public static Solicitud devolucion(Long ventaId, double monto, String concepto) {
            return new Solicitud(Tipo.SALIDA, Categoria.DEVOLUCION, monto, concepto, null, null, ventaId);
        }
    }

    /** Atajo: entrada o salida simple */
    @Transactional
    public MovimientoCaja registrar(Tipo tipo, Categoria categoria, double monto, String concepto,
                                    Collection<Autorizacion> autorizaciones) {
        return registrar(new Solicitud(tipo, categoria, monto, concepto, null, null, null), autorizaciones);
    }

    @Transactional
    public MovimientoCaja registrar(Solicitud s, Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.MOVIMIENTOS_CAJA, autorizaciones);
        Cajero quien = sesionService.requerirSesion();
        if (s == null || s.tipo() == null || s.categoria() == null) {
            throw new NegocioException("Indica si es entrada o salida y para qué es.");
        }
        if (s.categoria().getTipo() != s.tipo()) {
            throw new NegocioException("«" + s.categoria().getEtiqueta() + "» no es una " + s.tipo().getEtiqueta().toLowerCase() + ".");
        }
        if (!Double.isFinite(s.monto()) || s.monto() <= 0) {
            throw new NegocioException("El monto debe ser mayor que cero.");
        }
        if (s.monto() > MONTO_MAXIMO) {
            throw new NegocioException("El monto no puede pasar de " + Moneda.formato(MONTO_MAXIMO) + ".");
        }
        String concepto = s.concepto() == null ? "" : s.concepto().trim();
        Proveedor proveedor = null;
        if (s.categoria() == Categoria.PAGO_PROVEEDOR) {
            if (s.proveedorId() != null) {
                proveedor = proveedorRepo.findById(s.proveedorId())
                        .orElseThrow(() -> new NegocioException("El proveedor ya no existe."));
            }
            if (proveedor == null && concepto.isEmpty()) {
                throw new NegocioException("Indica a qué proveedor se le pagó.");
            }
            if (concepto.isEmpty()) concepto = "Pago a " + proveedor.getNombre();
        }
        if (concepto.isEmpty()) {
            if (s.categoria() == Categoria.CAMBIO || s.categoria() == Categoria.RETIRO) {
                concepto = s.categoria().getEtiqueta();   // estos dos se explican solos
            } else {
                throw new NegocioException("Escribe el motivo del movimiento (por ejemplo «Pago de luz»).");
            }
        }
        if (concepto.length() > 250) concepto = concepto.substring(0, 250);

        int caja = cajaService.cajaActual();
        double antes = cajaService.efectivoEsperadoAhora(caja);
        MovimientoCaja m = new MovimientoCaja();
        m.setFecha(LocalDateTime.now());
        m.setTipo(s.tipo());
        m.setCategoria(s.categoria());
        m.setMonto(VentaService.redondear(s.monto()));
        m.setConcepto(concepto);
        m.setCajero(quien);
        m.setCajeroNombre(quien.getNombreCompleto());
        m.setCaja(caja);
        if (proveedor != null) {
            m.setProveedorId(proveedor.getId());
            m.setProveedorNombre(proveedor.getNombre());
        }
        m.setOrdenCompraId(s.ordenCompraId());
        m.setVentaId(s.ventaId());
        m.setEfectivoAntes(VentaService.redondear(antes));
        m.setEfectivoDespues(VentaService.redondear(antes + m.getMontoConSigno()));
        Autorizacion aut = sesionService.autorizacionUsada(Permiso.MOVIMIENTOS_CAJA, autorizaciones);
        if (aut != null && aut.administrador() != null) {
            m.setAutorizadoPorId(aut.administrador().getId());
            m.setAutorizadoPor(aut.nombreAdministrador());
        }
        MovimientoCaja guardado = movimientoRepo.save(m);
        auditoriaService.registrar(quien, s.tipo() == Tipo.ENTRADA ? "EFECTIVO_ENTRADA" : "EFECTIVO_SALIDA", String.format(
                "%s #%d %s: %s — %s%s (en caja %s → %s)", IdentidadCaja.etiqueta(caja), guardado.getId(),
                s.categoria().getEtiqueta(), Moneda.formato(m.getMonto()), concepto,
                m.getProveedorNombre() != null ? " [" + m.getProveedorNombre() + "]" : "",
                Moneda.formato(m.getEfectivoAntes()), Moneda.formato(m.getEfectivoDespues())), aut);
        if (aut != null) aut.consumir();
        return guardado;
    }

    /** Un movimiento por id (para imprimir su comprobante) */
    @Transactional(readOnly = true)
    public MovimientoCaja obtener(Long id) {
        verificarLectura();
        return movimientoRepo.findById(id).orElseThrow(() -> new NegocioException("El movimiento ya no existe."));
    }

    /** Movimientos de ESTA caja entre dos días inclusive (HACER_CORTE, VER_GANANCIAS o MOVIMIENTOS_CAJA) */
    @Transactional(readOnly = true)
    public List<MovimientoCaja> movimientosEntre(LocalDate desde, LocalDate hasta) {
        verificarLectura();
        if (desde == null) desde = LocalDate.now();
        if (hasta == null) hasta = desde;
        if (hasta.isBefore(desde)) throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        return movimientoRepo.findEntre(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay(), identidadCaja.numero());
    }

    /** Movimientos de hoy de ESTA caja */
    @Transactional(readOnly = true)
    public List<MovimientoCaja> movimientosDeHoy() {
        return movimientosEntre(LocalDate.now(), LocalDate.now());
    }

    /**
     * Efectivo que debe haber ahora en esta caja, si quien pregunta puede verlo
     * (HACER_CORTE, VER_GANANCIAS o MOVIMIENTOS_CAJA); vacío si no.
     */
    @Transactional(readOnly = true)
    public Optional<Double> efectivoEsperadoSiPuedeVerlo() {
        if (!puedeLeer()) return Optional.empty();
        return Optional.of(cajaService.efectivoEsperadoAhora(cajaService.cajaActual()));
    }

    private boolean puedeLeer() {
        return sesionService.tienePermiso(Permiso.HACER_CORTE) || sesionService.tienePermiso(Permiso.VER_GANANCIAS)
                || sesionService.tienePermiso(Permiso.MOVIMIENTOS_CAJA);
    }

    private void verificarLectura() {
        if (!puedeLeer()) sesionService.verificarPermiso(Permiso.MOVIMIENTOS_CAJA);
    }
}
