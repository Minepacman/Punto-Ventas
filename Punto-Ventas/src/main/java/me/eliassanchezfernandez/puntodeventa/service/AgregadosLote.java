package me.eliassanchezfernandez.puntodeventa.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.ParProductos;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoClima;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ClimaRegistroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ParProductosRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoClimaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ResumenDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Transacciones de {@link AgregadosService}. Es un bean aparte para que cada llamada
 * sea SU PROPIA transacción (el proxy de Spring no actúa en las llamadas internas de
 * una clase), y separa dos clases de trabajo:
 *
 *  - <b>Cálculo</b> ({@link #calcularDias}, {@link #calcularFechas}, {@link #calcularVentana}):
 *    {@code @Transactional(readOnly = true)}. En SQLite abre con {@code BEGIN} (DEFERRED,
 *    ver ConexionSqlite): lee las ventas y agrupa SIN tomar el candado de escritura, así
 *    la caja sigue cobrando mientras se recorren semanas de tickets.
 *  - <b>Escritura</b> ({@link #reemplazarDias}, {@link #reemplazarPares},
 *    {@link #reemplazarProductoClima}, {@link #borrarTodo}): transacciones cortas que solo
 *    borran e insertan lo ya calculado; el candado se retiene milisegundos.
 *
 * Antes cada mes se calculaba DENTRO de la transacción de escritura (y la ventana de 28
 * días también): el {@code BEGIN IMMEDIATE} tomaba el candado al empezar y una venta en
 * curso esperaba a que terminara todo el recorrido. Los resultados son los mismos: lo
 * que cambia es cuándo se toma el candado.
 */
@Service
public class AgregadosLote {

    @Autowired private VentaRepository          ventaRepo;
    @Autowired private ResumenDiarioRepository  resumenRepo;
    @Autowired private ProductoDiarioRepository productoDiarioRepo;
    @Autowired private ParProductosRepository   paresRepo;
    @Autowired private ProductoClimaRepository  productoClimaRepo;
    @Autowired private ClimaRegistroRepository  climaRepo;
    @Autowired private ConfiguracionRepository  configuracionRepo;
    @Autowired private ConfiguracionService     configuracionService;
    @PersistenceContext private EntityManager   em;

    /** Pares y correlación con el clima de la ventana móvil, ya calculados y listos para guardar */
    public record Ventana(List<ParProductos> pares, List<ProductoClima> clima) { }

    // ── Escritura (transacciones cortas) ─────────────────────────────────

    /** Borra todos los agregados diarios y vacía la marca de actualización (una transacción) */
    @Transactional
    public void borrarTodo() {
        resumenRepo.deleteAllInBatch();
        productoDiarioRepo.deleteAllInBatch();
        configuracionRepo.findById(me.eliassanchezfernandez.puntodeventa.model.Configuracion.ID_UNICO).ifPresent(c -> {
            c.setEstadisticasActualizado(null);
            configuracionRepo.save(c);
        });
        configuracionService.invalidarCache();
    }

    /**
     * Reemplaza los agregados de {@code fechas} por los días ya calculados (los días del
     * rango sin ventas simplemente quedan sin fila). Una transacción corta. @return días guardados
     */
    @Transactional
    public int reemplazarDias(List<LocalDate> fechas, Collection<AgregadosService.DiaCalculado> dias) {
        List<LocalDate> copia = new ArrayList<>(fechas);
        resumenRepo.deleteByFechas(copia);
        productoDiarioRepo.deleteByFechas(copia);
        int n = 0;
        for (AgregadosService.DiaCalculado d : dias) {
            resumenRepo.save(d.resumen());
            productoDiarioRepo.saveAll(d.productos());
            n++;
        }
        em.flush();
        em.clear();        // no arrastrar lo guardado a la siguiente vuelta
        return n;
    }

    /** Sustituye todos los pares de la canasta por los dados. Una transacción corta. */
    @Transactional
    public void reemplazarPares(List<ParProductos> pares) {
        paresRepo.deleteAllInBatch();
        paresRepo.saveAll(pares);
    }

    /** Sustituye toda la correlación producto × clima por la dada. Una transacción corta. */
    @Transactional
    public void reemplazarProductoClima(List<ProductoClima> porClima) {
        productoClimaRepo.deleteAllInBatch();
        productoClimaRepo.saveAll(porClima);
    }

    // ── Cálculo (solo lectura: sin candado de escritura) ─────────────────

    /** Días con ventas del rango [desde, hasta] (inclusive), cada uno con su resumen y productos */
    @Transactional(readOnly = true)
    public Map<LocalDate, AgregadosService.DiaCalculado> calcularDias(LocalDate desde, LocalDate hasta) {
        List<Venta> ventas = ventaRepo.findActivasConDetallesEntre(desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
        Map<LocalDate, ResumenDiario> resumenes = new TreeMap<>();
        Map<LocalDate, Map<Long, ProductoDiario>> productos = new HashMap<>();

        for (Venta v : ventas) {
            LocalDate dia = v.getFecha().toLocalDate();
            Franja franja = Franja.de(v.getFecha());
            double piezas = 0;
            Map<Long, ProductoDiario> delDia = productos.computeIfAbsent(dia, k -> new LinkedHashMap<>());
            // Las devoluciones parciales restan en el día de la venta (lo devuelto no se vendió)
            for (DetalleVenta d : v.getDetalles()) {
                Producto p = d.getProducto();
                if (p == null) continue;
                double cantidad = AgregadosService.cantidadNeta(d);
                double importe = AgregadosService.importeNeto(d);
                if (cantidad <= 0 && importe <= 0) continue;   // renglón devuelto completo: no se vendió
                piezas += cantidad;
                delDia.computeIfAbsent(p.getId(), k -> new ProductoDiario(dia, p))
                      .sumar(franja, cantidad, importe);
            }
            resumenes.computeIfAbsent(dia, ResumenDiario::new).sumarVenta(franja, v.getTotalNeto(), piezas);
        }

        Map<LocalDate, AgregadosService.DiaCalculado> resultado = new TreeMap<>();
        for (Map.Entry<LocalDate, ResumenDiario> e : resumenes.entrySet()) {
            e.getValue().redondear();
            List<ProductoDiario> lista = new ArrayList<>(productos.getOrDefault(e.getKey(), Map.of()).values());
            lista.forEach(ProductoDiario::redondear);
            lista.sort(Comparator.comparingDouble(ProductoDiario::getImporte).reversed());
            resultado.put(e.getKey(), new AgregadosService.DiaCalculado(e.getValue(), lista));
        }
        return resultado;
    }

    /** Solo estas fechas (sueltas, quizá de meses distintos), cada una con su consulta; sin guardar */
    @Transactional(readOnly = true)
    public Map<LocalDate, AgregadosService.DiaCalculado> calcularFechas(List<LocalDate> fechas) {
        Map<LocalDate, AgregadosService.DiaCalculado> resultado = new TreeMap<>();
        for (LocalDate f : fechas) resultado.putAll(calcularDias(f, f));
        return resultado;
    }

    /** Canasta y clima de las ventas activas de [inicio, fin), calculados sin guardar */
    @Transactional(readOnly = true)
    public Ventana calcularVentana(LocalDateTime inicio, LocalDateTime fin) {
        List<Venta> ventas = ventaRepo.findActivasConDetallesEntre(inicio, fin);
        List<ParProductos> pares = AgregadosService.calcularPares(ventas);
        List<ClimaRegistro> clima = climaRepo
                .findByFechaHoraGreaterThanEqualAndFechaHoraLessThanOrderByFechaHoraAsc(inicio, fin);
        return new Ventana(pares, AgregadosService.calcularProductoClima(ventas, clima));
    }
}
