package me.eliassanchezfernandez.puntodeventa.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.CuentaClienteMovimiento;
import me.eliassanchezfernandez.puntodeventa.model.CuentaClienteMovimiento.Tipo;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ClienteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.CuentaClienteMovimientoRepository;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Clientes con cuenta de crédito ("fiado").
 *
 * Reglas:
 *  - Cada cambio de saldo es un renglón del libro ({@link CuentaClienteMovimiento}) y, en la
 *    MISMA transacción, un UPDATE atómico de {@code clientes.saldo_deuda}; el saldo es la suma
 *    del libro ({@link #saldoSegunLibro}). Nunca se lee-suma-guarda: con varias cajas, dos ventas
 *    a cuenta del mismo cliente a la vez no se pisan y el límite no se rebasa dos veces
 *    ({@link ClienteRepository#cargarSiCabe}).
 *  - Vender a cuenta: VENDER_A_CREDITO o autorización de un administrador. Rebasar el límite,
 *    cambiar el límite de un cliente o ajustar un saldo: AJUSTAR_CREDITO o autorización (un uso);
 *    cada caso queda en la bitácora.
 *  - Abonos: cualquier usuario con sesión (recibir un pago es trabajo normal de la caja). El abono
 *    en efectivo es además una entrada de caja (cuenta en el corte); con tarjeta o transferencia
 *    solo baja la deuda. Un abono no puede ser mayor que lo que se debe.
 *  - Cancelar una venta a cuenta regresa el cargo (lo que quede pendiente de esa venta).
 */
@Service
public class CuentaClienteService {

    /** Tope de límite y de cada movimiento (evita teclear de más) */
    public static final double MONTO_MAXIMO = 999_999.99;

    /** Formas en que se recibe un abono */
    public static final Set<Venta.FormaPago> FORMAS_ABONO =
            Set.of(Venta.FormaPago.EFECTIVO, Venta.FormaPago.TARJETA, Venta.FormaPago.TRANSFERENCIA);

    @Autowired private ClienteRepository clienteRepo;
    @Autowired private CuentaClienteMovimientoRepository libroRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private MovimientoCajaService movimientoCajaService;
    @Autowired private IdentidadCaja identidadCaja;
    @PersistenceContext private EntityManager em;

    // ── Clientes ──────────────────────────────────────────────────────────

    /** Todos los clientes por nombre (cualquier usuario con sesión: se usan al cobrar) */
    @Transactional(readOnly = true)
    public List<Cliente> listar(boolean incluirInactivos) {
        sesionService.requerirSesion();
        return clienteRepo.findTodosOrdenados().stream()
                .filter(c -> incluirInactivos || c.isActivo())
                .toList();
    }

    /** Busca por nombre (sin acentos ni mayúsculas) o por teléfono (solo dígitos) */
    @Transactional(readOnly = true)
    public List<Cliente> buscar(String texto, boolean incluirInactivos) {
        List<Cliente> todos = listar(incluirInactivos);
        if (texto == null || texto.isBlank()) return todos;
        return todos.stream().filter(c -> coincide(c, texto)).toList();
    }

    static boolean coincide(Cliente c, String texto) {
        String t = normalizar(texto);
        if (t.isEmpty()) return true;
        String digitos = texto.replaceAll("\\D", "");
        if (digitos.length() >= 3 && c.getTelefono() != null
                && c.getTelefono().replaceAll("\\D", "").contains(digitos)) {
            return true;
        }
        String nombre = normalizar(c.getNombre());
        for (String palabra : t.split("\\s+")) {
            if (!nombre.contains(palabra)) return false;
        }
        return true;
    }

    static String normalizar(String s) {
        if (s == null) return "";
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase(Locale.ROOT).trim();
    }

    @Transactional(readOnly = true)
    public Cliente obtener(Long id) {
        sesionService.requerirSesion();
        return buscarCliente(id);
    }

    private Cliente buscarCliente(Long id) {
        if (id == null) throw new NegocioException("Elige un cliente.");
        return clienteRepo.findById(id).orElseThrow(() -> new NegocioException("El cliente ya no existe."));
    }

    /**
     * Alta rápida desde la ventana de cobro: solo el nombre (sin límite). VENDER_A_CREDITO o
     * autorización (no se consume: la consume el cobro).
     */
    @Transactional
    public Cliente crearRapido(String nombre, Collection<Autorizacion> autorizaciones) {
        return guardar(null, nombre, null, null, 0, true, autorizaciones);
    }

    /**
     * Crea (id null) o edita un cliente. VENDER_A_CREDITO o autorización. Cambiar el límite de
     * un cliente que ya existe exige además AJUSTAR_CREDITO (se registra en la bitácora). El
     * saldo nunca se toca aquí. Un cliente inactivo ya no puede comprar a cuenta, pero conserva
     * su deuda y puede abonar.
     *
     * @param limite tope de lo que puede deber; 0 = sin límite
     */
    @Transactional
    public Cliente guardar(Long id, String nombre, String telefono, String nota, double limite, boolean activo,
                           Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.VENDER_A_CREDITO, autorizaciones);
        Cajero quien = sesionService.requerirSesion();
        String n = nombre == null ? "" : nombre.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new NegocioException("Escribe el nombre del cliente.");
        if (n.length() > 150) throw new NegocioException("El nombre es demasiado largo (máximo 150 letras).");
        String tel = telefono == null ? null : telefono.trim();
        if (tel != null && tel.isEmpty()) tel = null;
        if (tel != null) {
            if (!tel.matches("[0-9+()\\-\\s]{1,20}")) {
                throw new NegocioException("El teléfono solo puede llevar números (máximo 20 caracteres).");
            }
        }
        String nt = nota == null ? null : nota.trim();
        if (nt != null && nt.isEmpty()) nt = null;
        if (nt != null && nt.length() > 250) nt = nt.substring(0, 250);
        if (!Double.isFinite(limite) || limite < 0) throw new NegocioException("El límite de crédito no es válido.");
        if (limite > MONTO_MAXIMO) throw new NegocioException("El límite no puede pasar de " + Moneda.formato(MONTO_MAXIMO) + ".");
        double lim = VentaService.redondear(limite);
        // Mismo nombre sin distinguir mayúsculas ni acentos (en Java: LOWER de SQLite no conoce los acentos)
        String clave = normalizar(n);
        if (clienteRepo.findAll().stream().anyMatch(x -> !x.getId().equals(id) && normalizar(x.getNombre()).equals(clave))) {
            throw new NegocioException("Ya hay un cliente llamado «" + n + "». Usa otro nombre (por ejemplo, con su apellido o su calle).");
        }

        if (id == null) {
            Cliente c = new Cliente();
            c.setNombre(n);
            c.setTelefono(tel);
            c.setNota(nt);
            c.setLimiteCredito(lim);
            c.setActivo(activo);
            c.setFechaAlta(LocalDateTime.now());
            Cliente guardado = clienteRepo.save(c);
            auditoriaService.registrar(quien, "CLIENTE_ALTA", "Cliente #" + guardado.getId() + " " + n
                    + (lim > 0 ? " — límite " + Moneda.formato(lim) : " — sin límite"),
                    sesionService.autorizacionUsada(Permiso.VENDER_A_CREDITO, autorizaciones));
            return guardado;
        }

        Cliente c = buscarCliente(id);
        boolean cambiaLimite = Math.abs(c.getLimiteCredito() - lim) > 0.004;
        Autorizacion autLimite = null;
        if (cambiaLimite) {
            sesionService.verificarPermiso(Permiso.AJUSTAR_CREDITO, autorizaciones);
            autLimite = sesionService.autorizacionUsada(Permiso.AJUSTAR_CREDITO, autorizaciones);
        }
        double limiteAntes = c.getLimiteCredito();
        boolean activoAntes = c.isActivo();
        c.setNombre(n);
        c.setTelefono(tel);
        c.setNota(nt);
        c.setLimiteCredito(lim);
        c.setActivo(activo);
        Cliente guardado = clienteRepo.save(c);
        auditoriaService.registrar(quien, "CLIENTE_EDITADO", "Cliente #" + id + " " + n
                + (activoAntes != activo ? (activo ? " — reactivado" : " — desactivado") : ""),
                sesionService.autorizacionUsada(Permiso.VENDER_A_CREDITO, autorizaciones));
        if (cambiaLimite) {
            auditoriaService.registrar(quien, "CLIENTE_LIMITE_CAMBIADO", String.format("Cliente #%d %s: límite %s → %s",
                    id, n, textoLimite(limiteAntes), textoLimite(lim)), autLimite);
            if (autLimite != null) autLimite.consumir();
        }
        return guardado;
    }

    private static String textoLimite(double limite) {
        return limite > 0.004 ? Moneda.formato(limite) : "sin límite";
    }

    // ── Cargo por venta (lo llama VentaService.cobrar en SU transacción) ──

    /** Resultado del cargo: cliente, lo que quedó debiendo y el administrador que autorizó pasar el límite (o null) */
    public record Cargo(Long clienteId, String clienteNombre, double saldoDespues, String autorizoExceso) { }

    /**
     * Antes de tocar inventario: el usuario puede vender a cuenta (o trae autorización) y el
     * cliente existe y está activo. El límite se revisa al cargar, en la base.
     */
    @Transactional
    public Cliente validarParaCargo(Long clienteId, Collection<Autorizacion> autorizaciones) {
        if (clienteId == null) {
            throw new NegocioException("Elige a qué cliente se le anota la cuenta (fiado).");
        }
        sesionService.verificarPermiso(Permiso.VENDER_A_CREDITO, autorizaciones);
        Cliente c = buscarCliente(clienteId);
        if (!c.isActivo()) {
            throw new NegocioException("«" + c.getNombre() + "» está desactivado: ya no se le puede fiar.");
        }
        return c;
    }

    /**
     * Carga a la cuenta del cliente la parte "A cuenta" de una venta ya guardada. Si rebasa
     * el límite exige AJUSTAR_CREDITO (o autorización; la consume el cobro) y lo registra en
     * la bitácora. Debe correr dentro de la transacción del cobro: si falla, no queda nada.
     */
    @Transactional
    public Cargo cargarVenta(Long clienteId, double monto, Long ventaId, String folio,
                             Collection<Autorizacion> autorizaciones) {
        Cajero quien = sesionService.requerirSesion();
        Cliente c = validarParaCargo(clienteId, autorizaciones);
        double m = VentaService.redondear(monto);
        if (!Double.isFinite(m) || m <= 0) throw new NegocioException("El monto a cuenta no es válido.");

        String autorizo = null;
        boolean excedio = false;
        if (clienteRepo.cargarSiCabe(c.getId(), m) == 0) {
            double saldo = saldoEnBase(c.getId());
            refrescar(c);
            if (!c.isActivo()) {
                throw new NegocioException("«" + c.getNombre() + "» está desactivado: ya no se le puede fiar.");
            }
            String detalle = String.format("%s ya debe %s y su límite es %s: con esta venta (%s a cuenta) quedaría debiendo %s.",
                    c.getNombre(), Moneda.formato(saldo), Moneda.formato(c.getLimiteCredito()), Moneda.formato(m),
                    Moneda.formato(VentaService.redondear(saldo + m)));
            try {
                sesionService.verificarPermiso(Permiso.AJUSTAR_CREDITO, autorizaciones);
            } catch (PermisoRequeridoException e) {
                throw new PermisoRequeridoException(Permiso.AJUSTAR_CREDITO, detalle + " " + e.getMessage());
            }
            Autorizacion aut = sesionService.autorizacionUsada(Permiso.AJUSTAR_CREDITO, autorizaciones);
            clienteRepo.mover(c.getId(), m);
            autorizo = aut != null ? aut.nombreAdministrador() : null;
            excedio = true;
            auditoriaService.registrar(quien, "CREDITO_LIMITE_EXCEDIDO",
                    "Venta " + (folio != null ? folio : "#" + ventaId) + " — " + detalle, aut);
        }
        double despues = saldoEnBase(c.getId());
        refrescar(c);
        CuentaClienteMovimiento mov = nuevo(c.getId(), Tipo.CARGO_VENTA, m, despues, quien,
                "Venta " + (folio != null ? folio : "#" + ventaId));
        mov.setVentaId(ventaId);
        mov.setVentaFolio(folio);
        // Quién permitió pasar el límite: el administrador que autorizó, o quien cobró si tiene el permiso
        mov.setAutorizadoPor(excedio ? (autorizo != null ? autorizo : quien.getNombreCompleto()) : null);
        libroRepo.save(mov);
        return new Cargo(c.getId(), c.getNombre(), despues, autorizo);
    }

    // ── Reversos (cancelación y devolución) ───────────────────────────────

    /**
     * Cancelación de una venta: regresa a la cuenta lo que siga pendiente de esa venta (el cargo
     * menos lo que ya se haya revertido). Si el cliente ya había abonado, puede quedar con saldo a
     * favor. Devuelve el monto revertido (0 si la venta no fue a cuenta). Lo llama
     * VentaService.cancelar dentro de su transacción (ya verificó permisos).
     */
    @Transactional
    public double revertirPorCancelacion(Long ventaId, String folio, String motivo) {
        double total = 0;
        for (Map.Entry<Long, Double> e : pendientePorCliente(ventaId).entrySet()) {
            if (e.getValue() <= 0.004) continue;
            total += aplicarReverso(e.getKey(), e.getValue(), ventaId, folio, Tipo.CANCELACION_VENTA,
                    "Cancelación venta " + (folio != null ? folio : "#" + ventaId)
                            + (motivo != null && !motivo.isBlank() ? " — " + motivo.trim() : ""));
        }
        return VentaService.redondear(total);
    }

    /**
     * Devolución parcial o total de una venta a cuenta: baja la deuda hasta lo que siga pendiente
     * de esa venta Y hasta lo que el cliente deba hoy (una devolución no deja saldo a favor: si el
     * cliente ya había abonado, ese dinero se le regresa en efectivo). Devuelve cuánto se descontó
     * de la cuenta; lo que falte lo devuelve quien llama, en efectivo. Lo llama DevolucionService
     * dentro de su transacción, con sus permisos ya verificados y la fila de la venta ya bloqueada.
     */
    @Transactional
    public double abonarPorDevolucion(Long ventaId, String folio, double monto, String concepto) {
        double restante = VentaService.redondear(monto);
        if (!Double.isFinite(restante) || restante <= 0) return 0;
        double aplicado = 0;
        for (Map.Entry<Long, Double> e : pendientePorCliente(ventaId).entrySet()) {
            if (restante <= 0.004) break;
            if (e.getValue() <= 0.004) continue;
            // Bloquea la fila del cliente (UPDATE sin cambio) antes de leer su saldo: con varias cajas, un
            // abono simultáneo espera y el tope "lo que debe hoy" no se calcula con un saldo viejo
            clienteRepo.mover(e.getKey(), 0.0);
            double debeHoy = Math.max(0, saldoEnBase(e.getKey()));
            double m = VentaService.redondear(Math.min(restante, Math.min(e.getValue(), debeHoy)));
            if (m <= 0.004) continue;
            aplicado += aplicarReverso(e.getKey(), m, ventaId, folio, Tipo.DEVOLUCION,
                    concepto != null && !concepto.isBlank() ? concepto.trim()
                            : "Devolución venta " + (folio != null ? folio : "#" + ventaId));
            restante = VentaService.redondear(restante - m);
        }
        return VentaService.redondear(aplicado);
    }

    /**
     * Cuánto de un reembolso de esta venta iría, como máximo, a la cuenta del cliente: lo que siga
     * cargado de la venta (cargo − cancelaciones/devoluciones ya aplicadas) sin pasar de lo que el
     * cliente deba hoy; 0 si no fue a cuenta. Misma regla que {@link #abonarPorDevolucion}. Solo
     * lectura y sin exigir permiso: la ventana de devoluciones lo usa para anticipar el reparto.
     */
    @Transactional(readOnly = true)
    public double pendienteDeVenta(Long ventaId) {
        double total = 0;
        for (Map.Entry<Long, Double> e : pendientePorCliente(ventaId).entrySet()) {
            if (e.getValue() > 0.004) total += Math.min(e.getValue(), Math.max(0, saldoEnBase(e.getKey())));
        }
        return VentaService.redondear(total);
    }

    /** Lo que sigue cargado por venta, por cliente (cargo − reversos ya hechos) */
    private Map<Long, Double> pendientePorCliente(Long ventaId) {
        Map<Long, Double> r = new LinkedHashMap<>();
        if (ventaId == null) return r;
        for (CuentaClienteMovimiento m : libroRepo.findDeVenta(ventaId)) {
            r.merge(m.getClienteId(), m.getMonto(), Double::sum);
        }
        r.replaceAll((k, v) -> VentaService.redondear(v));
        return r;
    }

    private double aplicarReverso(Long clienteId, double monto, Long ventaId, String folio, Tipo tipo, String concepto) {
        Cajero quien = sesionService.requerirSesion();
        double m = VentaService.redondear(monto);
        clienteRepo.mover(clienteId, -m);
        double despues = saldoEnBase(clienteId);
        clienteRepo.findById(clienteId).ifPresent(this::refrescar);
        CuentaClienteMovimiento mov = nuevo(clienteId, tipo, -m, despues, quien, concepto);
        mov.setVentaId(ventaId);
        mov.setVentaFolio(folio);
        libroRepo.save(mov);
        return m;
    }

    // ── Abonos ────────────────────────────────────────────────────────────

    /** Abono ya registrado: el renglón del libro, el cliente con su saldo nuevo y la entrada de caja (solo efectivo) */
    public record Abono(CuentaClienteMovimiento movimiento, Cliente cliente, MovimientoCaja movimientoCaja) {
        public double saldoDespues() { return movimiento.getSaldoDespues(); }
        public double monto() { return -movimiento.getMonto(); }
    }

    /**
     * El cliente paga (todo o parte de) lo que debe. En efectivo además entra al cajón como
     * "Abono de cliente (fiado)" y cuenta en el efectivo esperado del corte. No puede ser mayor
     * que la deuda actual (comprobado en la base: dos cajas no cobran lo mismo dos veces).
     */
    @Transactional
    public Abono registrarAbono(Long clienteId, double monto, Venta.FormaPago forma, String nota) {
        Cajero quien = sesionService.requerirSesion();
        Cliente c = buscarCliente(clienteId);
        if (forma == null || !FORMAS_ABONO.contains(forma)) {
            throw new NegocioException("El abono se recibe en efectivo, con tarjeta o por transferencia.");
        }
        double m = VentaService.redondear(monto);
        if (!Double.isFinite(m) || m <= 0) throw new NegocioException("Escribe cuánto abona el cliente (mayor que cero).");
        if (m > MONTO_MAXIMO) throw new NegocioException("El abono no puede pasar de " + Moneda.formato(MONTO_MAXIMO) + ".");
        if (clienteRepo.abonarSiDebe(c.getId(), m) == 0) {
            double saldo = saldoEnBase(c.getId());
            throw new NegocioException(saldo <= 0.004
                    ? "«" + c.getNombre() + "» no debe nada."
                    : String.format("El abono (%s) es mayor que lo que debe «%s» (%s).",
                            Moneda.formato(m), c.getNombre(), Moneda.formato(saldo)));
        }
        double despues = saldoEnBase(c.getId());
        refrescar(c);
        String nt = nota == null || nota.isBlank() ? null : nota.trim();
        String concepto = "Abono de " + c.getNombre() + (nt != null ? " — " + nt : "");
        if (concepto.length() > 250) concepto = concepto.substring(0, 250);
        MovimientoCaja mc = null;
        if (forma == Venta.FormaPago.EFECTIVO) {
            mc = movimientoCajaService.registrarAbonoCliente(m, concepto);
        }
        CuentaClienteMovimiento mov = nuevo(c.getId(), Tipo.ABONO, -m, despues, quien, concepto);
        mov.setFormaPago(forma.name());
        mov.setMovimientoCajaId(mc != null ? mc.getId() : null);
        CuentaClienteMovimiento guardado = libroRepo.save(mov);
        auditoriaService.registrar(quien, "CLIENTE_ABONO", String.format("Cliente #%d %s: abono %s (%s); debe ahora %s",
                c.getId(), c.getNombre(), Moneda.formato(m), forma.getEtiqueta().toLowerCase(), Moneda.formato(despues)),
                (Cajero) null);
        return new Abono(guardado, c, mc);
    }

    // ── Ajustes ───────────────────────────────────────────────────────────

    /**
     * Corrige el saldo de un cliente (deuda anterior al sistema, error, descuento perdonado).
     * {@code montoConSigno} positivo aumenta lo que debe; negativo lo disminuye. AJUSTAR_CREDITO
     * o autorización (se consume); el motivo es obligatorio y queda en la bitácora.
     */
    @Transactional
    public CuentaClienteMovimiento ajustar(Long clienteId, double montoConSigno, String motivo,
                                          Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.AJUSTAR_CREDITO, autorizaciones);
        Cajero quien = sesionService.requerirSesion();
        Cliente c = buscarCliente(clienteId);
        double m = VentaService.redondear(montoConSigno);
        if (!Double.isFinite(m) || Math.abs(m) < 0.005) throw new NegocioException("Escribe el monto del ajuste.");
        if (Math.abs(m) > MONTO_MAXIMO) throw new NegocioException("El ajuste no puede pasar de " + Moneda.formato(MONTO_MAXIMO) + ".");
        String mot = motivo == null ? "" : motivo.trim();
        if (mot.isEmpty()) throw new NegocioException("Escribe el motivo del ajuste.");
        if (mot.length() > 200) mot = mot.substring(0, 200);
        Autorizacion aut = sesionService.autorizacionUsada(Permiso.AJUSTAR_CREDITO, autorizaciones);
        double antes = saldoEnBase(c.getId());
        clienteRepo.mover(c.getId(), m);
        double despues = saldoEnBase(c.getId());
        refrescar(c);
        CuentaClienteMovimiento mov = nuevo(c.getId(), Tipo.AJUSTE, m, despues, quien, "Ajuste: " + mot);
        mov.setAutorizadoPor(aut != null ? aut.nombreAdministrador() : null);
        CuentaClienteMovimiento guardado = libroRepo.save(mov);
        auditoriaService.registrar(quien, "CLIENTE_AJUSTE", String.format("Cliente #%d %s: %s%s (%s → %s) — %s",
                c.getId(), c.getNombre(), m > 0 ? "+" : "−", Moneda.formato(Math.abs(m)),
                Moneda.formato(antes), Moneda.formato(despues), mot), aut);
        if (aut != null) aut.consumir();
        return guardado;
    }

    // ── Consultas ─────────────────────────────────────────────────────────

    /** Estado de cuenta de un periodo (desde/hasta null = todo) con saldo inicial y final */
    public record EstadoCuenta(Cliente cliente, LocalDate desde, LocalDate hasta, double saldoInicial,
                               List<CuentaClienteMovimiento> movimientos, double saldoFinal) {
        public double cargos() {
            return VentaService.redondear(movimientos.stream().filter(m -> m.getMonto() > 0).mapToDouble(CuentaClienteMovimiento::getMonto).sum());
        }
        public double abonos() {
            return VentaService.redondear(-movimientos.stream().filter(m -> m.getMonto() < 0).mapToDouble(CuentaClienteMovimiento::getMonto).sum());
        }
    }

    @Transactional(readOnly = true)
    public EstadoCuenta estadoDeCuenta(Long clienteId, LocalDate desde, LocalDate hasta) {
        sesionService.requerirSesion();
        Cliente c = buscarCliente(clienteId);
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        }
        LocalDateTime inicio = desde != null ? desde.atStartOfDay() : LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime fin = hasta != null ? hasta.plusDays(1).atStartOfDay() : LocalDateTime.of(9999, 1, 1, 0, 0);
        double inicial = desde != null ? VentaService.redondear(libroRepo.sumaDeClienteAntesDe(c.getId(), inicio)) : 0;
        List<CuentaClienteMovimiento> movs = new ArrayList<>(libroRepo.findDeClienteEntre(c.getId(), inicio, fin));
        double finalSaldo = VentaService.redondear(inicial + movs.stream().mapToDouble(CuentaClienteMovimiento::getMonto).sum());
        return new EstadoCuenta(c, desde, hasta, inicial, movs, finalSaldo);
    }

    /** Saldo según el libro (debe coincidir con el saldo guardado del cliente) */
    @Transactional(readOnly = true)
    public double saldoSegunLibro(Long clienteId) {
        return VentaService.redondear(libroRepo.sumaDeCliente(clienteId));
    }

    /** Clientes que deben (o con saldo a favor), de mayor a menor deuda */
    @Transactional(readOnly = true)
    public List<Cliente> conSaldo() {
        sesionService.requerirSesion();
        return clienteRepo.findConSaldo();
    }

    /** Lo que deben todos los clientes juntos (sin contar saldos a favor) */
    @Transactional(readOnly = true)
    public double totalPorCobrar() {
        return VentaService.redondear(conSaldo().stream().mapToDouble(Cliente::getSaldoDeuda).filter(s -> s > 0).sum());
    }

    /** Abonos de un periodo: los de efectivo (ya están en las entradas de caja) y los de otros medios */
    public record AbonosPeriodo(double efectivo, double otros) {
        public double total() { return VentaService.redondear(efectivo + otros); }
    }

    @Transactional(readOnly = true)
    public AbonosPeriodo abonosEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja) {
        double efectivo = 0, otros = 0;
        for (Object[] f : libroRepo.abonosPorForma(inicio, fin, caja)) {
            double monto = ((Number) f[1]).doubleValue();
            if (Venta.FormaPago.EFECTIVO.name().equals(f[0])) efectivo += monto; else otros += monto;
        }
        return new AbonosPeriodo(VentaService.redondear(efectivo), VentaService.redondear(otros));
    }

    // ── Apoyo ─────────────────────────────────────────────────────────────

    private double saldoEnBase(Long clienteId) {
        Double s = clienteRepo.saldoDe(clienteId);
        return VentaService.redondear(s != null ? s : 0);
    }

    /** La copia en memoria del cliente vuelve a leer el saldo que cambió el UPDATE */
    private void refrescar(Cliente c) {
        if (c != null && em.contains(c)) em.refresh(c);
    }

    private CuentaClienteMovimiento nuevo(Long clienteId, Tipo tipo, double monto, double saldoDespues,
                                          Cajero quien, String concepto) {
        CuentaClienteMovimiento m = new CuentaClienteMovimiento();
        m.setFecha(LocalDateTime.now());
        m.setClienteId(clienteId);
        m.setTipo(tipo.name());
        m.setMonto(VentaService.redondear(monto));
        m.setSaldoDespues(VentaService.redondear(saldoDespues));
        m.setConcepto(concepto != null && concepto.length() > 250 ? concepto.substring(0, 250) : concepto);
        if (quien != null) {
            m.setCajeroId(quien.getId());
            m.setCajeroNombre(quien.getNombreCompleto());
        }
        m.setCaja(identidadCaja.numero());
        return m;
    }
}
