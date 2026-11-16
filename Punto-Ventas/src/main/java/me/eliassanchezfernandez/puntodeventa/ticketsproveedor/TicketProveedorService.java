package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.HistorialPrecio;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.ProveedorCodigo;
import me.eliassanchezfernandez.puntodeventa.model.TicketProveedorRegistro;
import me.eliassanchezfernandez.puntodeventa.ocr.MotorOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.ResultadoOcr;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.HistorialPrecioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorCodigoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.repository.TicketProveedorRegistroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AuditoriaService;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Leer un ticket de proveedor y convertirlo en entrada de mercancía.
 *
 * <ol>
 *   <li>{@link #leerFoto} / {@link #leerTexto}: foto → texto (motor OCR, si está instalado) → {@link TicketLeido}.</li>
 *   <li>{@link #preparar}: reconoce al proveedor y propone, por renglón, el producto de la tienda (lo aprendido
 *       primero, luego código de barras, luego parecido de la descripción) y cuántas piezas trae cada unidad.</li>
 *   <li>{@link #aplicar}: en UNA transacción da entrada a todo (mismo camino que una entrada manual: existencia,
 *       costo, historial de precios, bitácora; AJUSTAR_INVENTARIO o autorización de un solo uso), crea los
 *       productos nuevos, recuerda lo confirmado y devuelve qué productos subieron o bajaron de costo.</li>
 *   <li>{@link #actualizarPreciosVenta}: sube los precios de venta que el usuario marcó (EDITAR_PRODUCTOS y
 *       CAMBIAR_PRECIOS, con historial y bitácora).</li>
 * </ol>
 *
 * Costos e impuestos: el costo por pieza que se guarda es lo que de verdad costó la pieza puesta en la tienda:
 * importe del renglón (ya con su descuento) × factor del ticket ÷ piezas. El factor reparte lo que el ticket
 * cobra aparte (IEPS/IVA no incluidos en los precios, costo de distribución) y los descuentos globales, para
 * comparar contra el precio de venta, que en la tienda siempre incluye impuestos.
 */
@Service
public class TicketProveedorService {

    /** Ganancia sobre el costo que se propone cuando no hay de dónde sacarla */
    public static final double MARGEN_POR_DEFECTO = 0.30;
    /** Debajo de esta ganancia sobre el costo, un producto que acaba de entrar sale en Avisos */
    public static final double MARGEN_BAJO = 0.10;

    @Autowired private ProveedorRepository proveedorRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private DepartamentoRepository departamentoRepo;
    @Autowired private ProveedorCodigoRepository codigoRepo;
    @Autowired private TicketProveedorRegistroRepository registroRepo;
    @Autowired private HistorialPrecioRepository historialRepo;
    @Autowired private ProductoService productoService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private ObjectProvider<MotorOcr> motores;
    @PersistenceContext private EntityManager em;

    // ── Lectura ────────────────────────────────────────────────────────────

    public record Lectura(List<TicketLeido> tickets, ResultadoOcr ocr, String origen) { }

    /** ¿Hay un motor OCR instalado? Si no, la pantalla ofrece escribir o pegar el texto */
    public boolean hayLectorDeFotos() {
        MotorOcr m = motores.getIfAvailable();
        return m != null && m.disponible();
    }

    /** Lee la foto con el motor OCR (puede tardar; llamarlo fuera del hilo de la pantalla) */
    public Lectura leerFoto(byte[] foto) throws IOException {
        sesionService.requerirSesion();
        MotorOcr motor = motores.getIfAvailable();
        if (motor == null || !motor.disponible()) throw new NegocioException("La lectura de fotos no está instalada en esta computadora: escribe o pega el texto del ticket.");
        if (foto == null || foto.length == 0) throw new NegocioException("La foto está vacía.");
        ResultadoOcr r = motor.leer(foto);
        List<TicketLeido> tickets = r.renglones() != null && !r.renglones().isEmpty()
                ? InterpreteTicket.interpretar(r.renglones())
                : InterpreteTicket.interpretar(r.textoCompleto());
        return new Lectura(tickets, r, motor.nombre());
    }

    public List<TicketLeido> leerTexto(String texto) {
        sesionService.requerirSesion();
        if (texto == null || texto.isBlank()) throw new NegocioException("Escribe o pega el texto del ticket.");
        return InterpreteTicket.interpretar(texto);
    }

    // ── Revisión ───────────────────────────────────────────────────────────

    /**
     * @param origen APRENDIDO (ya se había confirmado con este proveedor), CODIGO_BARRAS, PARECIDO (por la descripción)
     */
    public record Propuesta(Long productoId, String descripcion, double puntaje, String origen,
                            Integer piezasPorUnidad, double costoActual, double precioVenta, Long departamentoId) { }

    /**
     * Un renglón listo para revisar.
     * @param elegida           producto propuesto (null = no se encontró: crear o buscar)
     * @param dudoso            hay que confirmarlo (parecido no tan claro u otro casi igual)
     * @param piezasPorUnidad   piezas que se suman por cada unidad del ticket (1 = pieza)
     * @param preguntarEmpaque  el ticket sugiere un paquete y aún no se sabe si trae piezas sueltas o paquetes
     * @param costoPorPieza     costo por pieza que se registraría (con impuestos/cargos repartidos)
     * @param departamentoSugeridoId / precioVentaSugerido para "Crear producto"
     */
    public record RenglonRevision(LineaTicket linea, Propuesta elegida, List<Propuesta> candidatos, boolean dudoso,
                                  int piezasPorUnidad, boolean preguntarEmpaque, double costoPorPieza,
                                  Long departamentoSugeridoId, double precioVentaSugerido) { }

    public record Revision(TicketLeido ticket, Long proveedorId, String proveedorNombre, String motivoProveedor,
                           String nombreSugeridoNuevo, List<RenglonRevision> renglones, boolean costosVisibles,
                           String avisoRepetido) { }

    /**
     * Reconoce proveedor y productos. {@code proveedorId} fuerza un proveedor (el usuario ya lo eligió).
     * No escribe nada.
     */
    @Transactional(readOnly = true)
    public Revision preparar(TicketLeido t, Long proveedorId) {
        sesionService.requerirSesion();
        Proveedor prov = null;
        String motivo = null;
        if (proveedorId != null) {
            prov = proveedorRepo.findById(proveedorId).orElse(null);
            motivo = "elegido por ti";
        }
        if (prov == null) {
            List<Proveedor> activos = proveedorRepo.findByActivoTrueOrderByNombreAsc();
            List<DetectorProveedor.ProveedorRef> refs = activos.stream()
                    .map(p -> new DetectorProveedor.ProveedorRef(p.getId(), p.getNombre(), p.getRfc())).toList();
            DetectorProveedor.Deteccion d = DetectorProveedor.detectar(t, refs);
            if (d != null) {
                prov = proveedorRepo.findById(d.proveedor().id()).orElse(null);
                motivo = d.motivo();
            }
            if (prov == null) {
                Long porCodigos = proveedorPorLoAprendido(t);
                if (porCodigos != null) {
                    prov = proveedorRepo.findById(porCodigos).orElse(null);
                    motivo = "porque ya conoce los productos de este ticket";
                }
            }
        }
        List<Producto> catalogo = productoRepo.findAllByOrderByDescripcionAsc().stream().filter(Producto::isActivo).toList();
        List<EmparejadorProductos.ProductoRef> refs = catalogo.stream()
                .map(p -> new EmparejadorProductos.ProductoRef(p.getId(), p.getDescripcion(), p.getCodigoBarras(),
                        p.getDepartamento() != null ? p.getDepartamento().getId() : null)).toList();
        Map<Long, Producto> porId = new HashMap<>();
        for (Producto p : catalogo) porId.put(p.getId(), p);
        Map<String, ProveedorCodigo> aprendido = new HashMap<>();
        if (prov != null) for (ProveedorCodigo c : codigoRepo.findByProveedorId(prov.getId())) aprendido.put(c.getClave(), c);

        double factor = t.getCuadre() != null && t.getCuadre().cuadra() ? factorSeguro(t.getCuadre().factorCosto()) : 1.0;
        Long deptoProveedor = prov == null ? null : departamentoMasComun(prov.getId(), catalogo);
        Map<Long, Double> margenes = new HashMap<>();
        List<RenglonRevision> renglones = new ArrayList<>();
        for (LineaTicket l : t.getLineas()) {
            renglones.add(revisarRenglon(l, aprendido, refs, porId, factor, deptoProveedor, margenes, catalogo));
        }
        String repetido = null;
        if (prov != null && t.getFolio() != null) repetido = avisoRepetido(prov.getId(), t.getFolio());
        String sugeridoNuevo = prov != null ? null : nombreSugerido(t);
        return new Revision(t, prov == null ? null : prov.getId(), prov == null ? null : prov.getNombre(), motivo,
                sugeridoNuevo, renglones, proveedorService.puedeVerCostos(), repetido);
    }

    // ── Proveedor nuevo desde el ticket ───────────────────────────────────

    /** Lo que el lector vio del proveedor y la tienda aún no tiene (cualquier campo puede venir vacío) */
    public record SugerenciaProveedor(String nombre, String rfc) { }

    public static SugerenciaProveedor sugerenciaProveedorNuevo(TicketLeido t) {
        if (t == null) return new SugerenciaProveedor(null, null);
        String rfc = t.getRfcs().isEmpty() ? null : t.getRfcs().get(0).toUpperCase().replace(" ", "");
        return new SugerenciaProveedor(nombreSugerido(t), rfc);
    }

    /** @param creado false = ya existía (mismo RFC o mismo nombre) y no se creó otro */
    public record AltaProveedor(Proveedor proveedor, boolean creado) { }

    /**
     * Da de alta al proveedor de un ticket (permiso GESTIONAR_PROVEEDORES, que revisa
     * {@link ProveedorService#guardar}). Si ya hay uno con el mismo RFC o el mismo nombre (sin importar
     * mayúsculas ni acentos) NO crea otro: lo devuelve para que se use ese. El RFC queda guardado, así que
     * el siguiente ticket del mismo proveedor se reconoce solo.
     */
    @Transactional
    public AltaProveedor altaProveedorDeTicket(String nombre, String rfc, String telefono) {
        sesionService.verificarPermiso(me.eliassanchezfernandez.puntodeventa.model.Permiso.GESTIONAR_PROVEEDORES);
        String rfcClave = rfc == null ? "" : rfc.toUpperCase().replace(" ", "");
        String nombreClave = ProveedorService.clave(nombre);
        for (Proveedor p : proveedorRepo.findAll()) {
            boolean mismoRfc = !rfcClave.isEmpty() && p.getRfc() != null && p.getRfc().equalsIgnoreCase(rfcClave);
            boolean mismoNombre = !nombreClave.isEmpty() && ProveedorService.clave(p.getNombre()).equals(nombreClave);
            if (mismoRfc || mismoNombre) return new AltaProveedor(p, false);
        }
        Proveedor nuevo = proveedorService.guardar(null, nombre, rfc, telefono, null, java.util.Set.of(), java.util.Set.of(),
                "Dado de alta al leer un ticket.");
        return new AltaProveedor(nuevo, true);
    }

    private RenglonRevision revisarRenglon(LineaTicket l, Map<String, ProveedorCodigo> aprendido,
                                           List<EmparejadorProductos.ProductoRef> refs, Map<Long, Producto> porId,
                                           double factor, Long deptoProveedor, Map<Long, Double> margenes,
                                           List<Producto> catalogo) {
        Propuesta elegida = null;
        boolean dudoso = false;
        List<Propuesta> candidatos = new ArrayList<>();
        Integer piezasAprendidas = null;

        ProveedorCodigo c = aprendido.get(ProveedorCodigo.claveDe(l.getCodigoProveedor(), l.getDescripcion()));
        if (c == null && l.getCodigoProveedor() != null) c = aprendido.get(ProveedorCodigo.claveDe(null, l.getDescripcion()));
        if (c != null && porId.containsKey(c.getProducto().getId())) {
            Producto p = porId.get(c.getProducto().getId());
            elegida = propuesta(p, 1.0, "APRENDIDO", c.getPiezasPorUnidad());
            piezasAprendidas = c.getPiezasPorUnidad();
            candidatos.add(elegida);
        }
        if (elegida == null && l.getCodigoProveedor() != null && l.getCodigoProveedor().matches("\\d{8}|\\d{12,14}")) {
            Producto p = productoRepo.findByCodigoBarras(l.getCodigoProveedor()).filter(Producto::isActivo).orElse(null);
            if (p != null) { elegida = propuesta(p, 0.95, "CODIGO_BARRAS", null); candidatos.add(elegida); }
        }
        List<EmparejadorProductos.Candidato> parecidos = EmparejadorProductos.candidatos(l.getDescripcion(), refs, 5);
        for (EmparejadorProductos.Candidato cand : parecidos) {
            if (candidatos.stream().anyMatch(x -> x.productoId().equals(cand.producto().id()))) continue;
            candidatos.add(propuesta(porId.get(cand.producto().id()), cand.puntaje(), "PARECIDO", null));
        }
        if (elegida == null && !parecidos.isEmpty()) {
            EmparejadorProductos.Candidato primero = parecidos.get(0);
            if (primero.puntaje() >= EmparejadorProductos.DUDOSO) {
                elegida = propuesta(porId.get(primero.producto().id()), primero.puntaje(), "PARECIDO", null);
                boolean empate = parecidos.size() > 1 && parecidos.get(1).puntaje() > primero.puntaje() - EmparejadorProductos.EMPATE;
                dudoso = primero.puntaje() < EmparejadorProductos.CLARO || empate;
            }
        }

        // Piezas por unidad: lo aprendido manda; si el ticket sugiere paquete, se decide por el costo actual
        int piezas = 1;
        boolean preguntar = false;
        if (piezasAprendidas != null) {
            piezas = piezasAprendidas;
        } else if (l.getEmpaque() != null && l.getEmpaque() > 1) {
            preguntar = true;
            double precioUnidad = l.getCantidad() > 0 ? l.getImporte() * factor / l.getCantidad() : 0;
            Producto p = elegida == null ? null : porId.get(elegida.productoId());
            if (l.getCantidad() != Math.floor(l.getCantidad())) {
                piezas = l.getEmpaque();                                   // 0.5 paquete, 0.05 paquetes: es paquete
            } else if (p != null && p.getPrecioCosto() > 0) {
                double comoPaquete = Math.abs(precioUnidad / l.getEmpaque() - p.getPrecioCosto());
                double comoPieza = Math.abs(precioUnidad - p.getPrecioCosto());
                piezas = comoPaquete < comoPieza ? l.getEmpaque() : 1;
            } else if (p != null && p.getPrecioVenta() > 0) {
                piezas = precioUnidad > p.getPrecioVenta() * 1.5 ? l.getEmpaque() : 1;
            }
        }
        double costoPieza = costoPorPieza(l.getImporte(), factor, l.getCantidad(), piezas);
        Long depto = null;
        if (elegida != null && elegida.departamentoId() != null) depto = elegida.departamentoId();
        else if (!candidatos.isEmpty() && candidatos.get(0).departamentoId() != null && candidatos.get(0).puntaje() >= 0.35) depto = candidatos.get(0).departamentoId();
        else depto = deptoProveedor;
        Long d = depto;
        double margen = margenes.computeIfAbsent(d == null ? -1L : d, k -> margenHabitual(d, catalogo));
        double sugerido = redondearArriba50(costoPieza * (1 + margen));
        return new RenglonRevision(l, elegida, candidatos, dudoso, piezas, preguntar, costoPieza, depto, sugerido);
    }

    private Propuesta propuesta(Producto p, double puntaje, String origen, Integer piezas) {
        return new Propuesta(p.getId(), p.getDescripcion(), puntaje, origen, piezas, p.getPrecioCosto(), p.getPrecioVenta(),
                p.getDepartamento() != null ? p.getDepartamento().getId() : null);
    }

    /** Proveedor que ya conoce más claves de este ticket (para tickets sin nombre impreso) */
    private Long proveedorPorLoAprendido(TicketLeido t) {
        Set<String> claves = new HashSet<>();
        for (LineaTicket l : t.getLineas()) {
            claves.add(ProveedorCodigo.claveDe(l.getCodigoProveedor(), l.getDescripcion()));
            claves.add(ProveedorCodigo.claveDe(null, l.getDescripcion()));
        }
        if (claves.isEmpty()) return null;
        Long mejor = null;
        long cuenta = 0;
        for (Object[] fila : codigoRepo.contarPorProveedor(claves)) {
            long n = ((Number) fila[1]).longValue();
            if (n > cuenta) { cuenta = n; mejor = ((Number) fila[0]).longValue(); }
        }
        if (mejor == null) return null;
        Proveedor p = proveedorRepo.findById(mejor).orElse(null);
        return p != null && p.isActivo() ? mejor : null;
    }

    private static String nombreSugerido(TicketLeido t) {
        if (t.getProveedorPorFormato() != null) return t.getProveedorPorFormato();
        for (String e : t.getEncabezado()) {
            String s = e.trim();
            if (s.contains(":") || s.matches(".*\\d.*")) continue;
            if (s.replaceAll("[^\\p{L}]", "").length() >= 5) return s;
        }
        return null;
    }

    private String avisoRepetido(Long proveedorId, String folio) {
        List<TicketProveedorRegistro> previos = registroRepo.findByProveedorIdAndFolioOrderByRegistradoEnDesc(proveedorId, folio);
        if (previos.isEmpty()) return null;
        TicketProveedorRegistro r = previos.get(0);
        return "Este ticket (folio " + folio + ") ya se registró el "
                + r.getRegistradoEn().format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm"))
                + (r.getUsuarioNombre() != null ? " (" + r.getUsuarioNombre() + ")" : "") + ".";
    }

    private Long departamentoMasComun(Long proveedorId, List<Producto> catalogo) {
        Map<Long, Integer> cuenta = new HashMap<>();
        for (Producto p : catalogo) {
            if (p.getProveedor() != null && proveedorId.equals(p.getProveedor().getId()) && p.getDepartamento() != null) {
                cuenta.merge(p.getDepartamento().getId(), 1, Integer::sum);
            }
        }
        return cuenta.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /**
     * Ganancia habitual de la tienda sobre el costo (mediana de precio/costo − 1) en ese departamento;
     * con menos de 3 productos usa toda la tienda; sin datos, {@link #MARGEN_POR_DEFECTO}.
     */
    public double margenHabitual(Long departamentoId) {
        return margenHabitual(departamentoId, productoRepo.findAllByOrderByDescripcionAsc());
    }

    /**
     * Ganancia habitual de cada departamento con UNA lectura del catálogo (para sugerir el precio de un
     * producto nuevo al cambiarle el departamento en el teléfono). La llave -1 es la de «sin departamento».
     */
    @Transactional(readOnly = true)
    public Map<Long, Double> margenesHabituales() {
        sesionService.requerirSesion();
        List<Producto> catalogo = productoRepo.findAllByOrderByDescripcionAsc();
        Map<Long, Double> r = new LinkedHashMap<>();
        r.put(-1L, margenHabitual(null, catalogo));
        for (Departamento d : departamentoRepo.findAll()) r.put(d.getId(), margenHabitual(d.getId(), catalogo));
        return r;
    }

    static double margenHabitual(Long departamentoId, List<Producto> catalogo) {
        List<Double> delDepto = new ArrayList<>(), todos = new ArrayList<>();
        for (Producto p : catalogo) {
            if (!p.isActivo() || p.getPrecioCosto() <= 0 || p.getPrecioVenta() <= p.getPrecioCosto()) continue;
            double m = p.getPrecioVenta() / p.getPrecioCosto() - 1;
            if (m > 3) continue;                                         // costos mal capturados
            todos.add(m);
            if (departamentoId != null && p.getDepartamento() != null && departamentoId.equals(p.getDepartamento().getId())) delDepto.add(m);
        }
        List<Double> base = delDepto.size() >= 3 ? delDepto : todos;
        if (base.size() < 3) return MARGEN_POR_DEFECTO;
        base.sort(Double::compare);
        double mediana = base.size() % 2 == 1 ? base.get(base.size() / 2)
                : (base.get(base.size() / 2 - 1) + base.get(base.size() / 2)) / 2;
        return Math.max(0.05, Math.min(2.0, mediana));
    }

    // ── Cuentas (públicas para la pantalla y las pruebas) ──────────────────

    /** Redondea hacia arriba al siguiente múltiplo de 50 centavos (17.03 → 17.50; 18.00 → 18.00) */
    public static double redondearArriba50(double v) {
        if (v <= 0) return 0;
        return Math.ceil(v * 2 - 1e-6) / 2.0;
    }

    /** Costo de una pieza: importe del renglón × factor del ticket ÷ (unidades × piezas por unidad), a centavos */
    public static double costoPorPieza(double importe, double factor, double cantidad, int piezasPorUnidad) {
        double piezas = cantidad * Math.max(1, piezasPorUnidad);
        if (piezas <= 0) return 0;
        return TextoTicket.centavos(importe * factor / piezas);
    }

    static double factorSeguro(double f) {
        return Double.isFinite(f) && f >= 0.5 && f <= 2.0 ? f : 1.0;
    }

    // ── Registrar la entrada ───────────────────────────────────────────────

    public record ProductoNuevo(String descripcion, String codigoBarras, Long departamentoId, double precioVenta) { }

    /**
     * @param productoId producto de la tienda (null si se crea con {@code nuevo})
     * @param cantidad   unidades del ticket (0.5 paquete, 6 piezas…)
     * @param importe    lo pagado por el renglón ya con su descuento (sin repartir impuestos/cargos)
     * @param recordar   aprender este renglón para la próxima vez
     */
    public record RenglonAplicar(String codigoProveedor, String descripcionTicket, Long productoId, ProductoNuevo nuevo,
                                 double cantidad, int piezasPorUnidad, double importe, boolean recordar) { }

    /**
     * @param factorCosto reparto de impuestos no incluidos, cargos y descuentos globales (ver {@link Cuadre}); 1 = nada
     * @param registrarAunqueSeRepita el usuario confirmó que el folio repetido es otra entrega
     */
    public record Solicitud(Long proveedorId, String folio, LocalDate fecha, Double total, double factorCosto,
                            String origen, String texto, List<RenglonAplicar> renglones, boolean registrarAunqueSeRepita) { }

    public record CambioCosto(Long productoId, String descripcion, double costoAntes, double costoAhora, double porcentaje,
                              double precioVenta, Double gananciaAntes, Double gananciaAhora, double precioSugerido) { }

    public record Resultado(Long registroId, int renglones, double piezas, double totalRegistrado,
                            List<CambioCosto> subieron, List<CambioCosto> bajaron, List<Long> productosNuevos,
                            boolean costosVisibles, int cambiosOcultos) { }

    private record Antes(double costo, double precioVenta, String descripcion) { }

    /**
     * Registra todo el ticket en una transacción: o entran todos los renglones o ninguno.
     * Permiso AJUSTAR_INVENTARIO (o autorización de un solo uso, que cubre todo el ticket y se consume al final);
     * crear productos exige además EDITAR_PRODUCTOS.
     */
    @Transactional
    public Resultado aplicar(Solicitud s, Collection<Autorizacion> autorizaciones) {
        sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        if (s == null || s.proveedorId() == null) throw new NegocioException("Elige el proveedor del ticket.");
        Proveedor prov = proveedorRepo.findById(s.proveedorId()).orElseThrow(() -> new NegocioException("El proveedor ya no existe."));
        if (!prov.isActivo()) throw new NegocioException("El proveedor \"" + prov.getNombre() + "\" está desactivado.");
        if (s.renglones() == null || s.renglones().isEmpty()) throw new NegocioException("El ticket no tiene productos para registrar.");
        String folio = s.folio() == null || s.folio().isBlank() ? null : s.folio().trim();
        if (folio != null && folio.length() > 60) folio = folio.substring(0, 60);
        int repeticion = 1;
        if (folio != null) {
            List<TicketProveedorRegistro> previos = registroRepo.findByProveedorIdAndFolioOrderByRegistradoEnDesc(prov.getId(), folio);
            if (!previos.isEmpty() && !s.registrarAunqueSeRepita()) {
                throw new NegocioException(avisoRepetido(prov.getId(), folio) + " Si de verdad es otra entrega, marca «Registrar de todos modos».");
            }
            repeticion = previos.size() + 1;
        }
        double factor = factorSeguro(s.factorCosto());
        for (RenglonAplicar r : s.renglones()) {
            if (r.productoId() == null && r.nuevo() == null) {
                throw new NegocioException("Falta elegir el producto de la tienda para «" + r.descripcionTicket() + "».");
            }
            if (!Double.isFinite(r.cantidad()) || r.cantidad() <= 0) {
                throw new NegocioException("La cantidad de «" + r.descripcionTicket() + "» debe ser mayor a cero.");
            }
            if (r.piezasPorUnidad() < 1) throw new NegocioException("Las piezas por unidad de «" + r.descripcionTicket() + "» deben ser 1 o más.");
            if (!Double.isFinite(r.importe()) || r.importe() < 0) throw new NegocioException("El importe de «" + r.descripcionTicket() + "» no puede ser negativo.");
        }

        // Cómo estaban antes (para avisar qué subió de costo)
        Map<Long, Antes> antes = new LinkedHashMap<>();
        for (RenglonAplicar r : s.renglones()) {
            if (r.productoId() == null || antes.containsKey(r.productoId())) continue;
            Producto p = productoRepo.findById(r.productoId()).orElseThrow(() -> new NegocioException("Un producto elegido ya no existe."));
            antes.put(p.getId(), new Antes(p.getPrecioCosto(), p.getPrecioVenta(), p.getDescripcion()));
        }

        String motivo = "Ticket de " + prov.getNombre() + (folio != null ? " folio " + folio : "");
        if (motivo.length() > 250) motivo = motivo.substring(0, 250);
        List<Long> nuevos = new ArrayList<>();
        double piezasTotal = 0, totalRegistrado = 0;
        for (RenglonAplicar r : s.renglones()) {
            double piezas = redondearPiezas(r.cantidad() * r.piezasPorUnidad());
            double costo = costoPorPieza(r.importe(), factor, r.cantidad(), r.piezasPorUnidad());
            Long productoId = r.productoId();
            if (productoId == null) {
                productoId = crearProducto(r.nuevo(), prov, costo, r.codigoProveedor()).getId();
                nuevos.add(productoId);
            }
            Producto p = productoRepo.findById(productoId).orElseThrow();
            if (!p.permiteDecimales() && piezas != Math.floor(piezas)) {
                throw new NegocioException("«" + p.getDescripcion() + "» se vende por pieza y el renglón da " + piezas
                        + " piezas (" + r.cantidad() + " × " + r.piezasPorUnidad() + "). Corrige la cantidad o las piezas por unidad.");
            }
            productoService.registrarEntradaDeTicket(productoId, piezas, prov, motivo, costo, autorizaciones);
            piezasTotal += piezas;
            totalRegistrado += costo * piezas;
            if (r.recordar()) recordar(prov, r, p, costo);
        }

        TicketProveedorRegistro reg = new TicketProveedorRegistro();
        reg.setProveedor(prov);
        reg.setProveedorNombre(prov.getNombre());
        reg.setFolio(folio);
        reg.setFechaTicket(s.fecha());
        reg.setRegistradoEn(LocalDateTime.now());
        reg.setUsuarioNombre(sesionService.getNombreCajero());
        reg.setRenglones(s.renglones().size());
        reg.setTotal(s.total());
        reg.setTotalRegistrado(TextoTicket.centavos(totalRegistrado));
        reg.setOrigen(s.origen() == null ? null : (s.origen().length() > 40 ? s.origen().substring(0, 40) : s.origen()));
        reg.setTexto(s.texto() == null ? null : (s.texto().length() > 19000 ? s.texto().substring(0, 19000) : s.texto()));
        reg.setRepeticion(repeticion);
        try {
            reg = registroRepo.saveAndFlush(reg);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Otra caja registró este mismo folio entre la revisión de arriba y este insert (restricción única en la
            // base): se deshace toda la entrada de mercancía de esta caja
            throw new NegocioException("Otra caja acaba de registrar este mismo ticket (folio " + folio + ") del proveedor "
                    + prov.getNombre() + ". No se registró nada aquí: revisa el historial antes de volver a intentarlo.");
        }

        Autorizacion aut = sesionService.autorizacionUsada(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        auditoriaService.registrar(sesionService.getCajeroActual(), "TICKET_PROVEEDOR_REGISTRADO",
                String.format(Locale.ROOT, "%s: %d renglón(es), %s piezas, $%.2f%s%s", motivo, s.renglones().size(),
                        formatoPiezas(piezasTotal), totalRegistrado, nuevos.isEmpty() ? "" : ", " + nuevos.size() + " producto(s) nuevo(s)",
                        s.registrarAunqueSeRepita() ? " (folio repetido confirmado)" : ""), aut);
        if (aut != null) aut.consumir();

        // ¿Qué cambió de costo?
        List<CambioCosto> subieron = new ArrayList<>(), bajaron = new ArrayList<>();
        for (Map.Entry<Long, Antes> e : antes.entrySet()) {
            Producto p = productoRepo.findById(e.getKey()).orElseThrow();
            CambioCosto c = cambioCosto(p.getId(), e.getValue().descripcion(), e.getValue().costo(), p.getPrecioCosto(), p.getPrecioVenta());
            if (c == null) continue;
            (c.costoAhora() > c.costoAntes() ? subieron : bajaron).add(c);
        }
        subieron.sort(Comparator.comparingDouble(CambioCosto::porcentaje).reversed());
        bajaron.sort(Comparator.comparingDouble(CambioCosto::porcentaje));
        boolean visibles = proveedorService.puedeVerCostos();
        int ocultos = visibles ? 0 : subieron.size() + bajaron.size();
        return new Resultado(reg.getId(), s.renglones().size(), piezasTotal, TextoTicket.centavos(totalRegistrado),
                visibles ? subieron : List.of(), visibles ? bajaron : List.of(), nuevos, visibles, ocultos);
    }

    /** Cambio de costo con la sugerencia de precio que conserva la ganancia que tenía; null si no cambió */
    public static CambioCosto cambioCosto(Long productoId, String descripcion, double costoAntes, double costoAhora, double precioVenta) {
        if (costoAntes <= 0 || Math.abs(costoAhora - costoAntes) < 0.005) return null;
        double pct = (costoAhora - costoAntes) / costoAntes;
        Double gananciaAntes = costoAntes > 0 ? (precioVenta - costoAntes) / costoAntes : null;
        Double gananciaAhora = costoAhora > 0 ? (precioVenta - costoAhora) / costoAhora : null;
        double sugerido = redondearArriba50(costoAhora * (precioVenta / costoAntes));
        if (precioVenta <= 0) sugerido = redondearArriba50(costoAhora * (1 + MARGEN_POR_DEFECTO));
        return new CambioCosto(productoId, descripcion, costoAntes, costoAhora, pct, precioVenta, gananciaAntes, gananciaAhora, sugerido);
    }

    private static double redondearPiezas(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static String formatoPiezas(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.3f", v);
    }

    private Producto crearProducto(ProductoNuevo n, Proveedor prov, double costo, String codigoProveedor) {
        if (n.descripcion() == null || n.descripcion().isBlank()) throw new NegocioException("Escribe el nombre del producto nuevo.");
        if (!(n.precioVenta() > 0)) throw new NegocioException("Escribe el precio de venta de «" + n.descripcion() + "».");
        Producto p = new Producto();
        String codigo = n.codigoBarras() == null || n.codigoBarras().isBlank() ? codigoInterno(prov, codigoProveedor) : n.codigoBarras().trim();
        if (productoRepo.findByCodigoBarras(codigo).isPresent()) {
            throw new NegocioException("El código " + codigo + " ya es de otro producto: escribe otro o déjalo vacío.");
        }
        p.setCodigoBarras(codigo);
        p.setDescripcion(n.descripcion().trim());
        p.setPrecioVenta(n.precioVenta());
        p.setPrecioCosto(costo);
        p.setUsaInventario(true);
        p.setCantidadActual(0);
        p.setCantidadMinima(0);
        p.setProveedor(prov);
        if (n.departamentoId() != null) {
            Departamento d = departamentoRepo.findById(n.departamentoId()).orElse(null);
            p.setDepartamento(d);
        }
        return productoService.guardar(p);
    }

    /** Código para un producto sin código de barras: "PRV" + proveedor + clave del proveedor (o al azar) */
    private String codigoInterno(Proveedor prov, String codigoProveedor) {
        String base = "PRV" + prov.getId() + "-" + (codigoProveedor != null && !codigoProveedor.isBlank()
                ? codigoProveedor.replaceAll("[^A-Za-z0-9]", "")
                : UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        String codigo = base;
        int n = 2;
        while (productoRepo.findByCodigoBarras(codigo).isPresent()) codigo = base + "-" + n++;
        return codigo;
    }

    private void recordar(Proveedor prov, RenglonAplicar r, Producto p, double costo) {
        String clave = ProveedorCodigo.claveDe(r.codigoProveedor(), r.descripcionTicket());
        if (clave.length() <= 2) return;
        ProveedorCodigo c = codigoRepo.findByProveedorIdAndClave(prov.getId(), clave).orElse(null);
        if (c == null) {
            c = new ProveedorCodigo();
            c.setProveedor(prov);
            c.setClave(clave);
            c.setVeces(1);
        } else {
            c.setVeces(c.getVeces() + 1);
        }
        c.setCodigoProveedor(r.codigoProveedor() == null ? null : recorte(r.codigoProveedor(), 40));
        c.setDescripcionProveedor(recorte(r.descripcionTicket(), 150));
        c.setProducto(p);
        c.setPiezasPorUnidad(Math.max(1, r.piezasPorUnidad()));
        c.setUltimoCosto(costo);
        c.setFecha(LocalDateTime.now());
        codigoRepo.save(c);
    }

    private static String recorte(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** Lo aprendido con un proveedor (para revisarlo u olvidarlo) */
    @Transactional(readOnly = true)
    public List<ProveedorCodigo> aprendidoDe(Long proveedorId) {
        sesionService.requerirSesion();
        return codigoRepo.findByProveedorId(proveedorId);
    }

    /** Olvida una relación mal confirmada (GESTIONAR_PROVEEDORES o AJUSTAR_INVENTARIO) */
    @Transactional
    public void olvidar(Long proveedorCodigoId) {
        if (!sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES)) sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO);
        codigoRepo.findById(proveedorCodigoId).ifPresent(c -> {
            codigoRepo.delete(c);
            auditoriaService.registrar(sesionService.getCajeroActual(), "TICKET_PROVEEDOR_OLVIDADO",
                    c.getDescripcionProveedor() + " → " + c.getProducto().getDescripcion(), (me.eliassanchezfernandez.puntodeventa.model.Cajero) null);
        });
    }

    // ── Precios de venta ───────────────────────────────────────────────────

    public record NuevoPrecio(Long productoId, double precioVenta) { }

    /**
     * Cambia los precios de venta elegidos en "Subieron de costo". EDITAR_PRODUCTOS y CAMBIAR_PRECIOS;
     * cada cambio queda en el historial de precios y uno en la bitácora con todos.
     * @return cuántos cambiaron
     */
    @Transactional
    public int actualizarPreciosVenta(List<NuevoPrecio> precios) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        sesionService.verificarPermiso(Permiso.CAMBIAR_PRECIOS);
        if (precios == null || precios.isEmpty()) return 0;
        List<String> detalle = new ArrayList<>();
        int n = 0;
        for (NuevoPrecio np : precios) {
            if (!Double.isFinite(np.precioVenta()) || np.precioVenta() <= 0) throw new NegocioException("El precio de venta debe ser mayor a cero.");
            Producto p = productoRepo.findById(np.productoId()).orElseThrow(() -> new NegocioException("Un producto ya no existe."));
            double antes = p.getPrecioVenta();
            if (Math.abs(antes - np.precioVenta()) < 0.005) continue;
            historialRepo.save(new HistorialPrecio(p, "VENTA", antes, np.precioVenta(), sesionService.getCajeroActual()));
            p.setPrecioVenta(np.precioVenta());
            productoRepo.save(p);
            detalle.add(String.format(Locale.ROOT, "%s %.2f → %.2f", p.getDescripcion(), antes, np.precioVenta()));
            n++;
        }
        if (n > 0) {
            String d = String.join("; ", detalle);
            auditoriaService.registrar(sesionService.getCajeroActual(), "PRECIOS_ACTUALIZADOS_POR_COSTO",
                    d.length() > 900 ? d.substring(0, 900) + "…" : d, (me.eliassanchezfernandez.puntodeventa.model.Cajero) null);
        }
        return n;
    }

    // ── Aviso de ganancia baja ─────────────────────────────────────────────

    public record GananciaBaja(Long productoId, String descripcion, double costo, double precioVenta, double ganancia) { }

    /** Productos que recibieron mercancía en los últimos {@code dias} días y quedaron con ganancia menor a {@link #MARGEN_BAJO} */
    @Transactional(readOnly = true)
    public List<GananciaBaja> gananciaBajaTrasEntradas(int dias) {
        List<Producto> ps = em.createQuery(
                "select distinct p from MovimientoInventario m join m.producto p where m.tipo = :tipo and m.fecha >= :desde "
                        + "and p.precioCosto > 0 and p.precioVenta < p.precioCosto * :factor", Producto.class)
                .setParameter("tipo", MovimientoInventario.Tipo.ENTRADA)
                .setParameter("desde", LocalDateTime.now().minusDays(dias))
                .setParameter("factor", 1 + MARGEN_BAJO)
                .getResultList();
        List<GananciaBaja> out = new ArrayList<>();
        for (Producto p : ps) {
            if (!p.isActivo()) continue;
            out.add(new GananciaBaja(p.getId(), p.getDescripcion(), p.getPrecioCosto(), p.getPrecioVenta(),
                    (p.getPrecioVenta() - p.getPrecioCosto()) / p.getPrecioCosto()));
        }
        out.sort(Comparator.comparingDouble(GananciaBaja::ganancia));
        return out;
    }
}
