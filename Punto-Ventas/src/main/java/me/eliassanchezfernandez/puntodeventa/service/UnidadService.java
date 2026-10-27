package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.UnidadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Catálogo de unidades de medida (Configuración → Unidades de medida).
 * Se siembra con Pieza, Kilogramo, Gramo, Litro, Metro y Caja la primera vez
 * (tabla vacía). Solo ADMIN cambia el catálogo; cada cambio queda en la bitácora.
 *
 * "Permite decimales" decide cómo se vende un producto con esa unidad
 * (ProductoService la hace coincidir con el tipo de venta A granel), por eso
 * no se puede cambiar ni borrar una unidad que ya usan productos.
 */
@Service
public class UnidadService {

    @Autowired private UnidadRepository   unidadRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private SesionService      sesionService;
    @Autowired private AuditoriaService   auditoriaService;

    /** Unidades que se crean con la primera ejecución */
    static final List<Unidad> PREDETERMINADAS = List.of(
            new Unidad("Pieza", "pza", false),
            new Unidad("Kilogramo", "kg", true),
            new Unidad("Gramo", "g", true),
            new Unidad("Litro", "L", true),
            new Unidad("Metro", "m", true),
            new Unidad("Caja", "caja", false));

    @EventListener(ContextRefreshedEvent.class)
    @Transactional
    public void sembrarPredeterminadas() {
        if (unidadRepo.count() > 0) return;
        for (Unidad u : PREDETERMINADAS) {
            unidadRepo.save(new Unidad(u.getNombre(), u.getAbreviatura(), u.isPermiteDecimales()));
        }
    }

    public List<Unidad> listar() {
        return unidadRepo.findAllByOrderByNombreAsc();
    }

    public Optional<Unidad> buscarPorNombre(String nombre) {
        return nombre == null ? Optional.empty() : unidadRepo.findByNombreIgnoreCase(nombre.trim());
    }

    /** Alta (id null) o cambio de una unidad */
    @Transactional
    public Unidad guardar(Unidad unidad) {
        sesionService.verificarAdmin();
        if (unidad.getNombre() == null || unidad.getNombre().isBlank()) {
            throw new NegocioException("El nombre de la unidad es obligatorio.");
        }
        String nombre = unidad.getNombre().trim();
        if (nombre.length() > 50) throw new NegocioException("El nombre de la unidad es demasiado largo (máx. 50).");
        String abrev = unidad.getAbreviatura() == null || unidad.getAbreviatura().isBlank()
                ? null : unidad.getAbreviatura().trim();
        if (abrev != null && abrev.length() > 10) {
            throw new NegocioException("La abreviatura puede tener hasta 10 caracteres.");
        }
        unidadRepo.findByNombreIgnoreCase(nombre)
                .filter(otra -> !otra.getId().equals(unidad.getId()))
                .ifPresent(otra -> {
                    throw new NegocioException("Ya existe una unidad llamada \"" + otra.getNombre() + "\".");
                });

        Unidad guardada;
        String accion;
        String detalle;
        if (unidad.getId() == null) {
            guardada = unidadRepo.save(new Unidad(nombre, abrev, unidad.isPermiteDecimales()));
            accion = "UNIDAD_CREADA";
            detalle = descripcion(guardada);
        } else {
            Unidad enBd = unidadRepo.findById(unidad.getId())
                    .orElseThrow(() -> new NegocioException("La unidad ya no existe."));
            long enUso = productoRepo.countByUnidadId(enBd.getId());
            if (enUso > 0 && enBd.isPermiteDecimales() != unidad.isPermiteDecimales()) {
                throw new NegocioException("No se puede cambiar \"permite decimales\" de \"" + enBd.getNombre()
                        + "\": " + enUso + " producto(s) la usan.");
            }
            detalle = descripcion(enBd) + " → ";
            enBd.setNombre(nombre);
            enBd.setAbreviatura(abrev);
            enBd.setPermiteDecimales(unidad.isPermiteDecimales());
            guardada = unidadRepo.save(enBd);
            accion = "UNIDAD_MODIFICADA";
            detalle += descripcion(guardada);
        }
        auditoriaService.registrar(sesionService.getCajeroActual(), accion, detalle, (Cajero) null);
        return guardada;
    }

    @Transactional
    public void eliminar(Long id) {
        sesionService.verificarAdmin();
        Unidad u = unidadRepo.findById(id).orElseThrow(() -> new NegocioException("La unidad ya no existe."));
        long enUso = productoRepo.countByUnidadId(id);
        if (enUso > 0) {
            throw new NegocioException("No se puede eliminar \"" + u.getNombre() + "\": " + enUso
                    + " producto(s) la usan.");
        }
        unidadRepo.delete(u);
        auditoriaService.registrar(sesionService.getCajeroActual(), "UNIDAD_ELIMINADA", descripcion(u), (Cajero) null);
    }

    private static String descripcion(Unidad u) {
        return u.getNombre() + (u.getAbreviatura() != null ? " (" + u.getAbreviatura() + ")" : "")
                + ", decimales=" + u.isPermiteDecimales();
    }
}
