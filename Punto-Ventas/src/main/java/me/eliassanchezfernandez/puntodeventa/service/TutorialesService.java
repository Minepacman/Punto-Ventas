package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tutoriales que se muestran una sola vez por usuario (hoy: el de la pestaña
 * Estadísticas). La marca vive en la fila del usuario (columna nullable de
 * {@code cajeros}), así se respeta en cualquier caja y sobrevive a reinstalar.
 * Requiere sesión iniciada; no exige permiso.
 */
@Service
public class TutorialesService {

    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private SesionService    sesionService;

    /** ¿El usuario de la sesión ya vio el tutorial de Estadísticas? */
    @Transactional(readOnly = true)
    public boolean estadisticasVisto() {
        Cajero actual = sesionService.requerirSesion();
        return cajeroRepo.findById(actual.getId()).map(Cajero::isTutorialEstadisticasVisto)
                .orElse(actual.isTutorialEstadisticasVisto());
    }

    /** Marca el tutorial de Estadísticas como visto para el usuario de la sesión (idempotente) */
    @Transactional
    public void marcarEstadisticasVisto() {
        Cajero actual = sesionService.requerirSesion();
        cajeroRepo.findById(actual.getId()).ifPresent(c -> {
            if (!c.isTutorialEstadisticasVisto()) {
                c.setTutorialEstadisticasVisto(true);
                cajeroRepo.save(c);
            }
        });
        actual.setTutorialEstadisticasVisto(true);   // el objeto de la sesión puede estar desligado de JPA
    }

    /** Vuelve a mostrar el tutorial la próxima vez (por si el dueño quiere repetirlo a un usuario) */
    @Transactional
    public void reiniciarEstadisticas() {
        Cajero actual = sesionService.requerirSesion();
        cajeroRepo.findById(actual.getId()).ifPresent(c -> {
            c.setTutorialEstadisticasVisto(false);
            cajeroRepo.save(c);
        });
        actual.setTutorialEstadisticasVisto(false);
    }
}
