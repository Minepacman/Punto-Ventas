package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuración del negocio guardada en la BD (CFG-01, VEN-04, VEN-14).
 * Una sola fila; se crea con valores por defecto la primera vez.
 */
@Service
public class ConfiguracionService {

    @Autowired private ConfiguracionRepository configuracionRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;

    @Value("${app.nombre-tienda:Mi Tienda}")
    private String nombreTiendaInicial;

    /** Configuración vigente (la crea si no existe) */
    @Transactional
    public Configuracion obtener() {
        return configuracionRepo.findById(Configuracion.ID_UNICO).orElseGet(() -> {
            Configuracion c = new Configuracion();
            c.setNombreTienda(nombreTiendaInicial != null && !nombreTiendaInicial.isBlank()
                    ? nombreTiendaInicial : "Mi Tienda");
            return configuracionRepo.save(c);
        });
    }

    public String getNombreTienda() {
        return obtener().getNombreTienda();
    }

    /** Tasa de IVA como fracción (0.16) */
    public double getTasaIva() {
        return obtener().getTasaIva();
    }

    public int getPlazoCancelacionHoras() {
        return obtener().getPlazoCancelacionHoras();
    }

    /**
     * Solo ADMIN. tasaIvaPorcentaje se captura como 16 (no 0.16).
     * El plazo de cancelación nuevo solo aplica a ventas futuras (VEN-19).
     */
    @Transactional
    public Configuracion guardar(String nombreTienda, String direccion,
                                 double tasaIvaPorcentaje, int plazoCancelacionHoras) {
        sesionService.verificarAdmin();

        if (nombreTienda == null || nombreTienda.isBlank()) {
            throw new NegocioException("El nombre del negocio es obligatorio.");
        }
        if (!Double.isFinite(tasaIvaPorcentaje) || tasaIvaPorcentaje < 0 || tasaIvaPorcentaje > 100) {
            throw new NegocioException("La tasa de IVA debe estar entre 0 y 100 %.");
        }
        if (plazoCancelacionHoras < 0 || plazoCancelacionHoras > 24 * 365) {
            throw new NegocioException("El plazo de cancelación debe estar entre 0 y 8760 horas.");
        }

        Configuracion c = obtener();
        String antes = String.format("tienda=%s, iva=%.2f%%, plazo=%dh",
                c.getNombreTienda(), c.getTasaIva() * 100, c.getPlazoCancelacionHoras());
        c.setNombreTienda(nombreTienda.trim());
        c.setDireccion(direccion == null || direccion.isBlank() ? null : direccion.trim());
        c.setTasaIva(Math.round(tasaIvaPorcentaje * 10000.0) / 1000000.0);
        c.setPlazoCancelacionHoras(plazoCancelacionHoras);
        Configuracion guardada = configuracionRepo.save(c);

        auditoriaService.registrar(sesionService.getCajeroActual(), "CONFIGURACION_MODIFICADA",
                "antes: " + antes, (me.eliassanchezfernandez.puntodeventa.model.Cajero) null);
        return guardada;
    }
}
