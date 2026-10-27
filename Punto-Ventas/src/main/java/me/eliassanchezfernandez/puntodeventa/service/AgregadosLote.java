package me.eliassanchezfernandez.puntodeventa.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.ResumenDiario;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ResumenDiarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Operaciones por lotes de {@link AgregadosService}. Es un bean aparte para que
 * cada llamada sea SU PROPIA transacción (el proxy de Spring no actúa en las
 * llamadas internas de una clase): un mes de ventas por transacción, con el
 * contexto de persistencia vaciado al final, así el recálculo de años de
 * historial no acumula memoria ni retiene el candado de escritura de SQLite.
 */
@Service
public class AgregadosLote {

    @Autowired private VentaRepository          ventaRepo;
    @Autowired private ResumenDiarioRepository  resumenRepo;
    @Autowired private ProductoDiarioRepository productoDiarioRepo;
    @Autowired private ConfiguracionRepository  configuracionRepo;
    @Autowired private ConfiguracionService     configuracionService;
    @PersistenceContext private EntityManager   em;

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

    /** Recalcula y guarda los días de [desde, hasta] (reemplazando lo que hubiera). Una transacción. */
    @Transactional
    public int reemplazarRango(LocalDate desde, LocalDate hasta) {
        List<LocalDate> fechas = new ArrayList<>();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) fechas.add(d);
        resumenRepo.deleteByFechas(fechas);
        productoDiarioRepo.deleteByFechas(fechas);
        int n = guardarDias(calcularDias(desde, hasta));
        em.flush();
        em.clear();        // no arrastrar las ventas del mes a la siguiente vuelta
        return n;
    }

    /** Recalcula y guarda solo estas fechas. Una transacción. */
    @Transactional
    public int reemplazarFechas(List<LocalDate> fechas) {
        List<LocalDate> copia = new ArrayList<>(fechas);
        resumenRepo.deleteByFechas(copia);
        productoDiarioRepo.deleteByFechas(copia);
        int n = 0;
        for (LocalDate f : copia) n += guardarDias(calcularDias(f, f));
        em.flush();
        em.clear();
        return n;
    }

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
            for (DetalleVenta d : v.getDetalles()) {
                Producto p = d.getProducto();
                if (p == null) continue;
                piezas += d.getCantidad();
                delDia.computeIfAbsent(p.getId(), k -> new ProductoDiario(dia, p))
                      .sumar(franja, d.getCantidad(), d.getImporte());
            }
            resumenes.computeIfAbsent(dia, ResumenDiario::new).sumarVenta(franja, v.getTotal(), piezas);
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

    private int guardarDias(Map<LocalDate, AgregadosService.DiaCalculado> dias) {
        for (AgregadosService.DiaCalculado d : dias.values()) {
            resumenRepo.save(d.resumen());
            productoDiarioRepo.saveAll(d.productos());
        }
        return dias.size();
    }
}
