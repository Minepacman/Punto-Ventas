package me.eliassanchezfernandez.puntodeventa.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Consultas acotadas para la página móvil. Cada una es UNA transacción de solo lectura
 * (en SQLite: BEGIN DEFERRED, no espera a la caja que cobra) y nunca trae tablas
 * completas a memoria: el filtro, el orden, la página y los totales se resuelven en SQL.
 * Pensado para PCs viejas (2 núcleos, disco duro, ≤4 GB) con años de ventas.
 */
@Service
public class ConsultasMovilService {

    /** Máximo de ventas por página (el teléfono pide más con «Cargar más») */
    public static final int MAX_VENTAS_POR_PAGINA = 100;
    /** Más allá de esta página se pide acotar las fechas (evita OFFSET enormes) */
    public static final int MAX_PAGINA = 1_000;

    @PersistenceContext private EntityManager em;
    @Autowired private SesionService sesionService;
    @Autowired private EstadisticasService estadisticasService;

    // ── Ventas ────────────────────────────────────────────────────────────

    /**
     * Una página de ventas del rango (más recientes primero; incluye canceladas) con renglones
     * y productos cargados, y los totales del rango COMPLETO calculados con una consulta agregada.
     */
    public record PaginaVentas(List<Venta> ventas, boolean hayMas, long ventasEnRango, long canceladas, double importe) { }

    @Transactional(readOnly = true)
    public PaginaVentas ventas(LocalDate desde, LocalDate hasta, int pagina, int limite) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        if (desde == null || hasta == null || hasta.isBefore(desde)) {
            throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        }
        if (pagina < 0 || pagina > MAX_PAGINA) throw new NegocioException("Página fuera de rango. Acota las fechas.");
        if (limite < 1 || limite > MAX_VENTAS_POR_PAGINA) {
            throw new NegocioException("El límite debe ser de 1 a " + MAX_VENTAS_POR_PAGINA + ".");
        }
        LocalDateTime ini = desde.atStartOfDay(), fin = hasta.plusDays(1).atStartOfDay();

        // 1) Solo los ids de la página (+1 para saber si hay más): usa el índice de fecha
        List<Long> ids = new ArrayList<>(em.createQuery(
                        "SELECT v.id FROM Venta v WHERE v.fecha >= :ini AND v.fecha < :fin ORDER BY v.fecha DESC, v.id DESC", Long.class)
                .setParameter("ini", ini).setParameter("fin", fin)
                .setFirstResult(pagina * limite).setMaxResults(limite + 1)
                .getResultList());
        boolean hayMas = ids.size() > limite;
        if (hayMas) ids = ids.subList(0, limite);

        // 2) Esas ventas con renglones y productos en una sola consulta, en el orden de la página
        List<Venta> ventas = new ArrayList<>();
        if (!ids.isEmpty()) {
            Map<Long, Venta> porId = new HashMap<>();
            for (Venta v : em.createQuery("SELECT DISTINCT v FROM Venta v LEFT JOIN FETCH v.detalles d "
                            + "LEFT JOIN FETCH d.producto WHERE v.id IN :ids", Venta.class)
                    .setParameter("ids", ids).getResultList()) {
                porId.put(v.getId(), v);
            }
            for (Long id : ids) {
                Venta v = porId.get(id);
                if (v != null) ventas.add(v);
            }
        }

        // 3) Totales del rango completo sin cargar filas
        Object[] t = em.createQuery("SELECT COUNT(v.id), "
                        + "COALESCE(SUM(CASE WHEN v.cancelada = true THEN 1 ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN v.cancelada = true THEN 0.0 ELSE v.total END), 0.0) "
                        + "FROM Venta v WHERE v.fecha >= :ini AND v.fecha < :fin", Object[].class)
                .setParameter("ini", ini).setParameter("fin", fin)
                .getSingleResult();
        return new PaginaVentas(ventas, hayMas, numero(t[0]).longValue(), numero(t[1]).longValue(),
                ResumenDiario.VentaRedondeo.centavos(numero(t[2]).doubleValue()));
    }

    // ── Inventario ────────────────────────────────────────────────────────

    /** Primeros productos del filtro (existencia baja primero, luego por nombre) y cuántos hay en total */
    public record PaginaProductos(List<Producto> productos, long total) { }

    /**
     * Productos activos que usan inventario cuyo código o descripción contienen el texto (vacío =
     * todos), sin distinguir mayúsculas. Filtro, orden y límite en SQL: con 5 000 productos ya no se
     * carga el catálogo entero para devolver 200.
     */
    @Transactional(readOnly = true)
    public PaginaProductos inventario(String texto, int limite) {
        String q = texto == null ? "" : texto.trim();
        String filtro = " FROM Producto p WHERE (p.activo IS NULL OR p.activo = true) AND p.usaInventario = true";
        if (!q.isEmpty()) {
            filtro += " AND (LOWER(p.codigoBarras) LIKE LOWER(:patron) ESCAPE '!'"
                    + " OR LOWER(p.descripcion) LIKE LOWER(:patron) ESCAPE '!')";
        }
        TypedQuery<Long> cuenta = em.createQuery("SELECT COUNT(p.id)" + filtro, Long.class);
        TypedQuery<Producto> lista = em.createQuery("SELECT p" + filtro
                + " ORDER BY CASE WHEN p.cantidadActual <= p.cantidadMinima THEN 0 ELSE 1 END, LOWER(p.descripcion), p.id",
                Producto.class);
        if (!q.isEmpty()) {
            String patron = "%" + escaparLike(q.toLowerCase(Locale.ROOT)) + "%";
            cuenta.setParameter("patron", patron);
            lista.setParameter("patron", patron);
        }
        long total = cuenta.getSingleResult();
        List<Producto> productos = total == 0 ? List.of() : lista.setMaxResults(Math.max(1, limite)).getResultList();
        return new PaginaProductos(productos, total);
    }

    static String escaparLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    // ── Estadísticas ──────────────────────────────────────────────────────

    /** Todo lo que muestra la pestaña Estadísticas del teléfono para un periodo */
    public record EstadisticasMovil(EstadisticasService.Periodo periodo, EstadisticasService.Totales totales,
                                    Optional<EstadisticasService.Comparacion> comparacion,
                                    List<EstadisticasService.ProductoTop> top,
                                    Map<me.eliassanchezfernandez.puntodeventa.model.Franja, EstadisticasService.Totales> franjas,
                                    Map<LocalDate, Double> serie, Double ganancia) { }

    /**
     * Lee los resúmenes del periodo UNA vez (antes: una por totales, franjas y serie, y ocho
     * periodos más para las cuatro comparaciones, dos de ellos de un año) y solo el periodo
     * anterior de la comparación que se muestra. La ganancia se suma en SQL.
     */
    @Transactional(readOnly = true)
    public EstadisticasMovil estadisticas(EstadisticasService.Periodo p, String etiquetaComparacion, boolean conGanancia) {
        sesionService.verificarPermiso(Permiso.VER_HISTORIAL);
        List<ResumenDiario> resumenes = estadisticasService.resumenesEntre(p);
        EstadisticasService.Totales totales = EstadisticasService.totalesDe(resumenes);
        Double ganancia = null;
        if (conGanancia) {
            sesionService.verificarPermiso(Permiso.VER_GANANCIAS);
            ganancia = ganancia(p.desde(), p.hasta());
        }
        return new EstadisticasMovil(p, totales, estadisticasService.comparacion(etiquetaComparacion, totales),
                estadisticasService.topProductos(p, 5), EstadisticasService.porFranjaDe(resumenes),
                EstadisticasService.serieDiariaDe(p, resumenes), ganancia);
    }

    /**
     * Ganancia estimada del rango (sin canceladas): importe del renglón menos su costo (el
     * guardado al vender o, si falta, el costo actual del producto). Una consulta agregada; el
     * redondeo a centavos se hace sobre la suma (antes se redondeaba cada renglón: puede
     * diferir en centavos con granel).
     */
    double ganancia(LocalDate desde, LocalDate hasta) {
        Number g = em.createQuery("SELECT COALESCE(SUM((d.precioVenta * d.cantidad - d.descuento) "
                        + "- COALESCE(d.costoUnitario, p.precioCosto) * d.cantidad), 0.0) "
                        + "FROM DetalleVenta d JOIN d.venta v JOIN d.producto p "
                        + "WHERE v.fecha >= :ini AND v.fecha < :fin AND (v.cancelada IS NULL OR v.cancelada = false)", Number.class)
                .setParameter("ini", desde.atStartOfDay()).setParameter("fin", hasta.plusDays(1).atStartOfDay())
                .getSingleResult();
        return ResumenDiario.VentaRedondeo.centavos(numero(g).doubleValue());
    }

    private static Number numero(Object o) {
        return o instanceof Number n ? n : 0;
    }
}
