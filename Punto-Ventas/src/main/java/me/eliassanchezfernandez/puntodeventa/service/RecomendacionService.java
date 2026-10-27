package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.NivelDatos;
import me.eliassanchezfernandez.puntodeventa.model.ParProductos;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoClima;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.repository.ParProductosRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoClimaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
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

/**
 * Motor de recomendaciones para agilizar la venta: dado el carrito actual, el
 * momento y (si lo hay) el clima, regresa pocas sugerencias ordenadas, cada
 * una con su motivo y una confianza.
 *
 * Fuentes (cada una da un puntaje 0..1 por producto):
 *  - canasta: pares "se compran juntos" (confianza × lift) de pares_productos;
 *  - día y hora: lo más vendido este día de la semana en esta franja
 *    (producto_diario, ventana móvil);
 *  - clima aprendido: índice relativo producto × clima (producto_clima);
 *  - genérico: reglas de abarrotes por palabras clave (ReglasGenericas) y
 *    los más vendidos del catálogo.
 *
 * Los pesos dependen del nivel de datos: en Entrenamiento manda lo genérico;
 * con historial manda lo aprendido. La confianza se acota por nivel para que
 * en las primeras semanas las sugerencias sean discretas. Determinista: con
 * los mismos datos y el mismo "ahora" regresa lo mismo (desempate por id).
 *
 * No exige permiso (la usa el cajero) y no expone importes.
 */
@Service
public class RecomendacionService {

    public static final int MAX_SUGERENCIAS = 5;
    /** Por debajo de esto una sugerencia no vale la pena mostrarse */
    static final double PUNTAJE_MINIMO = 0.05;

    @Autowired private ParProductosRepository   paresRepo;
    @Autowired private ProductoDiarioRepository productoDiarioRepo;
    @Autowired private ProductoClimaRepository  productoClimaRepo;
    @Autowired private ProductoRepository       productoRepo;
    @Autowired private EstadisticasService      estadisticasService;

    /** Una sugerencia lista para mostrar */
    public record Sugerencia(Producto producto, String motivo, double confianza, double puntaje) {

        /** Alta / Media / Baja (para la insignia) */
        public String nivelConfianza() {
            if (confianza >= 0.6) return "Alta";
            if (confianza >= 0.3) return "Media";
            return "Baja";
        }
    }

    /** Pesos de cada fuente según el nivel de datos (suman 1) y tope de confianza */
    public record Pesos(double generico, double canasta, double diaHora, double clima, double tope) {

        public static Pesos para(NivelDatos nivel, boolean hayClima) {
            Pesos p = switch (nivel) {
                case SIN_DATOS     -> new Pesos(1.00, 0.00, 0.00, 0.00, 0.35);
                case ENTRENAMIENTO -> new Pesos(0.60, 0.20, 0.10, 0.10, 0.50);
                case PARCIAL       -> new Pesos(0.30, 0.35, 0.20, 0.15, 0.75);
                case SUFICIENTE    -> new Pesos(0.10, 0.45, 0.25, 0.20, 1.00);
            };
            // Sin clima, su peso pasa a día/hora para no castigar la confianza
            return hayClima ? p : new Pesos(p.generico, p.canasta, p.diaHora + p.clima, 0, p.tope);
        }
    }

    // ── API ──────────────────────────────────────────────────────────────

    /** Sugerencias para el carrito en este momento, con el nivel de datos actual */
    @Transactional(readOnly = true)
    public List<Sugerencia> sugerir(Collection<Long> carrito, LocalDateTime ahora, ClimaRegistro clima, int maximo) {
        return sugerir(carrito, ahora, clima, maximo, estadisticasService.nivelDatosActual());
    }

    /**
     * Versión con el nivel explícito (pruebas y simulaciones).
     * @param carrito ids de los productos ya en el ticket (se excluyen)
     * @param clima   clima de la hora, o null si no hay datos
     */
    @Transactional(readOnly = true)
    public List<Sugerencia> sugerir(Collection<Long> carrito, LocalDateTime ahora, ClimaRegistro clima,
                                    int maximo, NivelDatos nivel) {
        Set<Long> enCarrito = carrito == null ? Set.of() : new HashSet<>(carrito);
        Pesos pesos = Pesos.para(nivel, clima != null);
        Map<Long, Candidato> candidatos = new HashMap<>();

        if (pesos.canasta() > 0 && !enCarrito.isEmpty()) {
            acumular(candidatos, puntajesCanasta(enCarrito), pesos.canasta());
        }
        if (pesos.diaHora() > 0) {
            acumular(candidatos, puntajesDiaHora(ahora), pesos.diaHora());
        }
        if (pesos.clima() > 0 && clima != null) {
            acumular(candidatos, puntajesClima(clima), pesos.clima());
        }
        if (pesos.generico() > 0) {
            acumular(candidatos, puntajesGenericos(ahora, clima), pesos.generico());
        }
        for (Long id : enCarrito) candidatos.remove(id);
        if (candidatos.isEmpty()) return List.of();

        // Releer los productos: activos y con existencia (si llevan inventario)
        Map<Long, Producto> productos = new HashMap<>();
        for (Producto p : productoRepo.findAllById(candidatos.keySet())) {
            if (!p.isActivo()) continue;
            if (p.isUsaInventario() && p.getCantidadActual() <= 0) continue;
            productos.put(p.getId(), p);
        }

        List<Sugerencia> lista = new ArrayList<>();
        for (Map.Entry<Long, Candidato> e : candidatos.entrySet()) {
            Producto p = productos.get(e.getKey());
            Candidato c = e.getValue();
            if (p == null || c.puntaje < PUNTAJE_MINIMO) continue;
            double confianza = Math.min(1.0, c.puntaje) * pesos.tope();
            lista.add(new Sugerencia(p, c.motivo, redondear(confianza), redondear(c.puntaje)));
        }
        lista.sort(Comparator.comparingDouble(Sugerencia::puntaje).reversed()
                .thenComparing(s -> s.producto().getId()));
        return lista.subList(0, Math.min(Math.max(0, maximo), lista.size()));
    }

    // ── Fuentes ──────────────────────────────────────────────────────────

    /** Puntaje 0..1 con su motivo (resultado parcial de una fuente) */
    record Aporte(double puntaje, String motivo) { }

    /** Canasta: para cada producto del carrito, lo que suele acompañarlo */
    Map<Long, Aporte> puntajesCanasta(Set<Long> enCarrito) {
        Map<Long, Aporte> r = new LinkedHashMap<>();
        for (ParProductos par : paresRepo.findDesde(enCarrito)) {
            if (par.getLift() < 1.0) continue;                       // juntos menos de lo normal: no sugerir
            long b = par.getProductoB().getId();
            double puntaje = Math.min(1.0, par.getConfianza() * Math.min(1.0, par.getLift() / 2.0) * 1.5);
            Aporte previo = r.get(b);
            if (previo == null || puntaje > previo.puntaje()) {
                r.put(b, new Aporte(puntaje, "Se compra junto con " + par.getProductoA().getDescripcion()));
            }
        }
        return r;
    }

    /** Día y hora: piezas vendidas este día de la semana en esta franja (ventana móvil), normalizadas */
    Map<Long, Aporte> puntajesDiaHora(LocalDateTime ahora) {
        LocalDate hoy = ahora.toLocalDate();
        DayOfWeek dia = ahora.getDayOfWeek();
        Franja franja = Franja.de(ahora);
        Map<Long, Double> piezas = new HashMap<>();
        for (ProductoDiario pd : productoDiarioRepo.findEntre(hoy.minusDays(AgregadosService.VENTANA_DIAS), hoy.minusDays(1))) {
            if (pd.getFecha().getDayOfWeek() != dia) continue;
            double n = pd.piezasDe(franja);
            if (n > 0) piezas.merge(pd.getProducto().getId(), n, Double::sum);
        }
        double maximo = piezas.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        Map<Long, Aporte> r = new LinkedHashMap<>();
        if (maximo <= 0) return r;
        String motivo = "Se vende mucho los " + nombreDia(dia) + " " + franja.getFrase();
        piezas.forEach((id, n) -> r.put(id, new Aporte(n / maximo, motivo)));
        return r;
    }

    /** Clima aprendido: productos cuyo índice relativo con este clima es mayor a 1 */
    Map<Long, Aporte> puntajesClima(ClimaRegistro clima) {
        Map<Long, Aporte> r = new LinkedHashMap<>();
        sumarClima(r, productoClimaRepo.findPorClave(ProductoClima.Tipo.CONDICION, clima.getCategoria()),
                "Se vende más con " + clima.getCategoria().toLowerCase(Locale.ROOT));
        sumarClima(r, productoClimaRepo.findPorClave(ProductoClima.Tipo.BANDA, clima.getBanda()),
                "Se vende más cuando hace " + clima.getBanda().toLowerCase(Locale.ROOT));
        return r;
    }

    private static void sumarClima(Map<Long, Aporte> r, List<ProductoClima> filas, String motivo) {
        for (ProductoClima pc : filas) {
            if (pc.getIndice() <= 1.0) continue;
            double puntaje = Math.min(1.0, pc.getIndice() - 1.0);     // índice 2 = el doble de lo normal = 1.0
            long id = pc.getProducto().getId();
            Aporte previo = r.get(id);
            if (previo == null || puntaje > previo.puntaje()) r.put(id, new Aporte(puntaje, motivo));
        }
    }

    /**
     * Genérico (arranque en frío): reglas por palabras clave sobre el catálogo
     * activo y, con menos peso, los productos más vendidos del historial.
     */
    Map<Long, Aporte> puntajesGenericos(LocalDateTime ahora, ClimaRegistro clima) {
        Map<Long, Aporte> r = new LinkedHashMap<>();
        DayOfWeek dia = ahora.getDayOfWeek();
        Franja franja = Franja.de(ahora);
        String categoria = clima != null ? clima.getCategoria() : null;
        String banda     = clima != null ? clima.getBanda() : null;
        for (Producto p : productoRepo.findAllByOrderByDescripcionAsc()) {
            if (!p.isActivo()) continue;
            for (ReglasGenericas.Coincidencia c : ReglasGenericas.evaluar(p, dia, franja, categoria, banda)) {
                Aporte previo = r.get(p.getId());
                if (previo == null || c.puntaje() > previo.puntaje()) r.put(p.getId(), new Aporte(c.puntaje(), c.motivo()));
            }
        }
        // Más vendidos de la ventana (cualquier día y hora): la mitad del puntaje de una regla
        LocalDate hoy = ahora.toLocalDate();
        Map<Long, Double> piezas = new HashMap<>();
        for (ProductoDiario pd : productoDiarioRepo.findEntre(hoy.minusDays(AgregadosService.VENTANA_DIAS), hoy.minusDays(1))) {
            piezas.merge(pd.getProducto().getId(), pd.getPiezas(), Double::sum);
        }
        double maximo = piezas.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (maximo > 0) {
            piezas.forEach((id, n) -> {
                double puntaje = 0.5 * n / maximo;
                Aporte previo = r.get(id);
                if (previo == null || puntaje > previo.puntaje()) r.put(id, new Aporte(puntaje, "Entre los más vendidos"));
            });
        }
        return r;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Suma ponderada; el motivo mostrado es el de la fuente que más aportó */
    private static void acumular(Map<Long, Candidato> candidatos, Map<Long, Aporte> aportes, double peso) {
        for (Map.Entry<Long, Aporte> e : aportes.entrySet()) {
            double aporte = e.getValue().puntaje() * peso;
            if (aporte <= 0) continue;
            Candidato c = candidatos.computeIfAbsent(e.getKey(), k -> new Candidato());
            c.puntaje += aporte;
            if (aporte > c.mejorAporte) {
                c.mejorAporte = aporte;
                c.motivo = e.getValue().motivo();
            }
        }
    }

    private static final class Candidato {
        double puntaje;
        double mejorAporte;
        String motivo = "";
    }

    static String nombreDia(DayOfWeek dia) {
        String n = dia.getDisplayName(TextStyle.FULL, new Locale("es", "MX")).toLowerCase(Locale.ROOT);
        return n.endsWith("s") ? n : n + "s";   // "los lunes", "los viernes", "los sábados"
    }

    private static double redondear(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
