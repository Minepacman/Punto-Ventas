package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Vale;
import me.eliassanchezfernandez.puntodeventa.model.ValeMovimiento;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ValeMovimientoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ValeRepository;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vales de compra (notas de crédito de la tienda).
 *
 * Reglas:
 *  - Se entregan en una devolución («Darle un vale para otra compra», {@link DevolucionService}),
 *    que ya verificó DEVOLVER_VENTAS. No sale efectivo del cajón: el corte los muestra aparte.
 *  - El código es de 8 caracteres al azar, sin letras que se confundan (sin 0/O, 1/I/L), y se
 *    imprime con su código de barras. Al buscarlo da igual mayúsculas, espacios o guiones.
 *  - Se usan como forma de pago «Vale de la tienda» en la ventana de cobro (quien puede vender
 *    puede cobrar con un vale). Se puede usar en partes: lo que sobra queda en el vale y se imprime
 *    en el ticket. Nunca se da cambio en efectivo de un vale (cuenta como pago distinto de efectivo).
 *  - Vencimiento opcional (Configuración → Negocio, 0 = no vencen); un vale vencido no se acepta.
 *  - Varias cajas: el saldo baja con un UPDATE condicionado a que alcance (dos cajas no gastan el
 *    mismo saldo dos veces). Cancelar una venta pagada con vale regresa lo usado al vale.
 *  - Cada cambio queda en el libro del vale ({@link ValeMovimiento}) y en la bitácora.
 */
@Service
public class ValeService {

    /** Letras y números que no se confunden al leerlos ni al teclearlos */
    static final String ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    public static final int LARGO_CODIGO = 8;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final SecureRandom AZAR = new SecureRandom();

    @Autowired private ValeRepository valeRepo;
    @Autowired private ValeMovimientoRepository libroRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private IdentidadCaja identidadCaja;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    // ── Códigos ───────────────────────────────────────────────────────────

    /** Lo tecleado o escaneado, en la forma guardada: mayúsculas, sin espacios ni guiones */
    public static String normalizar(String texto) {
        if (texto == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : texto.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /** ¿Tiene forma de código de vale? (8 caracteres del alfabeto, con al menos una letra) */
    public static boolean pareceCodigo(String texto) {
        String n = normalizar(texto);
        if (n.length() != LARGO_CODIGO) return false;
        boolean letra = false;
        for (char c : n.toCharArray()) {
            if (ALFABETO.indexOf(c) < 0) return false;
            if (Character.isLetter(c)) letra = true;
        }
        return letra;
    }

    static String generarCodigo() {
        while (true) {
            StringBuilder sb = new StringBuilder(LARGO_CODIGO);
            for (int i = 0; i < LARGO_CODIGO; i++) sb.append(ALFABETO.charAt(AZAR.nextInt(ALFABETO.length())));
            if (pareceCodigo(sb.toString())) return sb.toString();   // con al menos una letra: no se confunde con un monto
        }
    }

    // ── Consulta ──────────────────────────────────────────────────────────

    /**
     * Lo que la ventana de cobro muestra al escanear un vale. {@code problema} es null si se puede
     * usar; si no, dice por qué en palabras simples (no existe, ya se usó completo, venció).
     */
    public record Consulta(Long id, String codigo, double monto, double saldo, LocalDate vence, String problema) {
        public boolean sePuedeUsar() { return problema == null; }
        public String codigoMostrado() { return Vale.mostrar(codigo); }
        /** Cuánto se usaría para pagar {@code total} (todo el saldo o solo lo que haga falta) */
        public double aUsar(double total) { return VentaService.redondear(Math.max(0, Math.min(saldo, total))); }
    }

    /** Busca un vale por su código (cualquiera con sesión: es parte de cobrar) */
    @Transactional(readOnly = true)
    public Consulta consultar(String codigoTecleado) {
        sesionService.requerirSesion();
        String codigo = normalizar(codigoTecleado);
        if (codigo.isEmpty()) return new Consulta(null, codigo, 0, 0, null, "Escanea o escribe el código del vale.");
        Vale v = valeRepo.findByCodigo(codigo).orElse(null);
        if (v == null) {
            return new Consulta(null, codigo, 0, 0, null, "No existe un vale con el código " + Vale.mostrar(codigo)
                    + ". Revisa que esté bien escrito.");
        }
        return new Consulta(v.getId(), v.getCodigo(), v.getMonto(), VentaService.redondear(v.getSaldo()), v.getVence(),
                problemaDe(v, LocalDate.now()));
    }

    private static String problemaDe(Vale v, LocalDate hoy) {
        if (v.estaAgotado()) return "El vale " + v.getCodigoMostrado() + " ya se usó completo (no le queda saldo).";
        if (v.estaVencido(hoy)) return "El vale " + v.getCodigoMostrado() + " venció el " + v.getVence().format(FMT) + ".";
        return null;
    }

    /** Un vale por id (para imprimirlo) */
    @Transactional(readOnly = true)
    public Vale obtener(Long id) {
        sesionService.requerirSesion();
        return valeRepo.findById(id).orElseThrow(() -> new NegocioException("El vale ya no existe."));
    }

    @Transactional(readOnly = true)
    public List<ValeMovimiento> movimientos(Long valeId) {
        sesionService.requerirSesion();
        return libroRepo.findDeVale(valeId);
    }

    /** Vales entregados en un periodo y caja (null = todas): {cuántos, cuánto} */
    @Transactional(readOnly = true)
    public double[] emitidosEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja) {
        Object[] f = valeRepo.emitidosEntre(inicio, fin, caja).get(0);
        return new double[] { ((Number) f[0]).doubleValue(), VentaService.redondear(((Number) f[1]).doubleValue()) };
    }

    // ── Entregar (devolución) ─────────────────────────────────────────────

    /**
     * Entrega un vale por {@code monto}. Lo llama DevolucionService dentro de su transacción, con
     * DEVOLVER_VENTAS (o la autorización) ya verificado.
     */
    @Transactional
    public Vale emitir(double monto, Long ventaId, String ventaFolio, Long devolucionId, String clienteNombre) {
        Cajero quien = sesionService.requerirSesion();
        double m = VentaService.redondear(monto);
        if (!Double.isFinite(m) || m <= 0) throw new NegocioException("El vale debe ser por más de cero.");
        String codigo;
        do { codigo = generarCodigo(); } while (valeRepo.existsByCodigo(codigo));
        int dias = configuracionService.obtener().getValeVigenciaDiasOCero();
        Vale v = new Vale();
        v.setCodigo(codigo);
        v.setFecha(LocalDateTime.now());
        v.setMonto(m);
        v.setSaldo(m);
        v.setVence(dias > 0 ? LocalDate.now().plusDays(dias) : null);
        v.setVentaId(ventaId);
        v.setVentaFolio(ventaFolio);
        v.setDevolucionId(devolucionId);
        v.setClienteNombre(clienteNombre == null || clienteNombre.isBlank() ? null
                : (clienteNombre.length() > 150 ? clienteNombre.substring(0, 150) : clienteNombre));
        v.setCajeroId(quien.getId());
        v.setCajeroNombre(quien.getNombreCompleto());
        v.setCaja(identidadCaja.numero());
        Vale guardado = valeRepo.save(v);
        anotar(guardado.getId(), ValeMovimiento.Tipo.EMISION, m, m, ventaId, ventaFolio, quien);
        auditoriaService.registrar(quien, "VALE_EMITIDO", String.format("Vale %s (%s) por %s%s%s",
                guardado.getCodigoMostrado(), guardado.getFolio(), Moneda.formato(m),
                ventaFolio != null ? " — devolución de la venta " + ventaFolio : "",
                guardado.getVence() != null ? " — vence " + guardado.getVence().format(FMT) : ""), (Cajero) null);
        return guardado;
    }

    // ── Usar (cobro) ──────────────────────────────────────────────────────

    /**
     * Revisa antes de tocar nada (inventario, cupo) que el vale exista, no haya vencido y le
     * alcance; devuelve el código normalizado. El descuento real va en {@link #usarEnVenta}.
     * Sin @Transactional propio: corre dentro de la transacción de escritura del cobro.
     */
    public String validarParaCobro(String codigoTecleado, double monto) {
        String codigo = normalizar(codigoTecleado);
        if (codigo.isEmpty()) throw new NegocioException("Escanea o escribe el código del vale de la tienda.");
        Vale v = valeRepo.findByCodigo(codigo).orElseThrow(() -> new NegocioException(
                "No existe un vale con el código " + Vale.mostrar(codigo) + ". Revisa que esté bien escrito."));
        String problema = problemaDe(v, LocalDate.now());
        if (problema != null) throw new NegocioException(problema);
        if (monto > v.getSaldo() + 0.004) {
            throw new NegocioException(String.format("Al vale %s solo le quedan %s (se quiso usar %s).",
                    v.getCodigoMostrado(), Moneda.formato(v.getSaldo()), Moneda.formato(monto)));
        }
        return codigo;
    }

    /**
     * Descuenta del vale lo pagado con él en la venta ya guardada y anota en el pago lo que le
     * queda. Lo llama VentaService.cobrar dentro de su transacción. Si otra caja acaba de usar
     * el saldo, falla y toda la venta se revierte.
     */
    @Transactional
    public void usarEnVenta(Venta venta, PagoVenta pago) {
        Cajero quien = sesionService.requerirSesion();
        double m = VentaService.redondear(pago.getMonto());
        String codigo = normalizar(pago.getValeCodigo());
        Vale v = valeRepo.findByCodigo(codigo).orElseThrow(() -> new NegocioException(
                "No existe un vale con el código " + Vale.mostrar(codigo) + "."));
        if (v.estaVencido(LocalDate.now())) {
            throw new NegocioException("El vale " + v.getCodigoMostrado() + " venció el " + v.getVence().format(FMT) + ".");
        }
        if (valeRepo.usarSiAlcanza(v.getId(), m) == 0) {
            em.refresh(v);
            throw new NegocioException(String.format("Al vale %s ya no le alcanza: le quedan %s y se quiso usar %s "
                    + "(quizá se acaba de usar en otra caja). No se cobró nada.",
                    v.getCodigoMostrado(), Moneda.formato(v.getSaldo()), Moneda.formato(m)));
        }
        double despues = VentaService.redondear(saldoEnBase(v.getId()));
        em.refresh(v);
        pago.setValeCodigo(v.getCodigo());
        pago.setValeSaldo(despues);
        anotar(v.getId(), ValeMovimiento.Tipo.CANJE, -m, despues, venta.getId(), venta.getFolioMostrado(), quien);
        auditoriaService.registrar(quien, "VALE_USADO", String.format("Vale %s: %s en la venta %s; le quedan %s",
                v.getCodigoMostrado(), Moneda.formato(m), venta.getFolioMostrado(), Moneda.formato(despues)), (Cajero) null);
    }

    /**
     * Cancelar una venta pagada con vale: lo usado regresa a cada vale (lo que siga sin regresar).
     * Lo llama VentaService.cancelar dentro de su transacción. Devuelve el total regresado.
     */
    @Transactional
    public double revertirPorCancelacion(Long ventaId, String folio) {
        Map<Long, Double> porVale = new LinkedHashMap<>();
        for (ValeMovimiento m : libroRepo.findUsosDeVenta(ventaId)) porVale.merge(m.getValeId(), m.getMonto(), Double::sum);
        if (porVale.isEmpty()) return 0;
        Cajero quien = sesionService.requerirSesion();
        double total = 0;
        for (Map.Entry<Long, Double> e : new java.util.TreeMap<>(porVale).entrySet()) {   // por id: orden de bloqueo fijo
            double regresa = VentaService.redondear(-e.getValue());
            if (regresa <= 0.004) continue;
            valeRepo.regresar(e.getKey(), regresa);
            double despues = VentaService.redondear(saldoEnBase(e.getKey()));
            anotar(e.getKey(), ValeMovimiento.Tipo.CANCELACION_VENTA, regresa, despues, ventaId, folio, quien);
            valeRepo.findById(e.getKey()).ifPresent(v -> {
                em.refresh(v);
                auditoriaService.registrar(quien, "VALE_REGRESADO", String.format("Vale %s: regresan %s por la cancelación de la venta %s; tiene %s",
                        v.getCodigoMostrado(), Moneda.formato(regresa), folio != null ? folio : "#" + ventaId, Moneda.formato(despues)),
                        (Cajero) null);
            });
            total += regresa;
        }
        return VentaService.redondear(total);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private double saldoEnBase(Long valeId) {
        Double s = valeRepo.saldoDe(valeId);
        return s != null ? s : 0.0;
    }

    private void anotar(Long valeId, ValeMovimiento.Tipo tipo, double monto, double saldoDespues,
                        Long ventaId, String ventaFolio, Cajero quien) {
        ValeMovimiento m = new ValeMovimiento();
        m.setFecha(LocalDateTime.now());
        m.setValeId(valeId);
        m.setTipoEnum(tipo);
        m.setMonto(VentaService.redondear(monto));
        m.setSaldoDespues(saldoDespues);
        m.setVentaId(ventaId);
        m.setVentaFolio(ventaFolio);
        m.setCajeroId(quien != null ? quien.getId() : null);
        m.setCajeroNombre(quien != null ? quien.getNombreCompleto() : null);
        m.setCaja(identidadCaja.numero());
        libroRepo.save(m);
    }
}
