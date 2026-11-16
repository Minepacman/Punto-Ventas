package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Apariencia;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apariencia del programa (Configuración → Apariencia): la de toda la tienda la guarda el
 * administrador; cualquier usuario puede tener la suya "solo para mí" (p. ej. letra más grande),
 * que gana sobre la de la tienda. Ambas quedan en la bitácora. La página móvil la lee de
 * /api/apariencia (con la del usuario que entró) y /api/apariencia/publica (la de la tienda, para el
 * inicio de sesión). Los tickets impresos no la usan.
 */
@Service
public class AparienciaService {

    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private AuditoriaService auditoriaService;

    /** Apariencia de la tienda (sin permiso: la usa la pantalla de inicio de sesión) */
    public Apariencia deTienda() {
        return Apariencia.desdeJson(configuracionService.obtener().getApariencia());
    }

    /** La que ve ese usuario: la suya si la tiene, si no la de la tienda */
    public Apariencia paraUsuario(Cajero cajero) {
        if (cajero != null && cajero.getApariencia() != null && !cajero.getApariencia().isBlank()) {
            return Apariencia.desdeJson(cajero.getApariencia());
        }
        return deTienda();
    }

    /** La del usuario en sesión (o la de la tienda si no hay nadie) */
    public Apariencia vigente() {
        Cajero c = sesionService.getCajeroActual();
        if (c == null) return deTienda();
        Cajero enBd = cajeroRepo.findById(c.getId()).orElse(c);
        return paraUsuario(enBd);
    }

    /** ¿El usuario en sesión tiene una apariencia propia? */
    public boolean tienePropia() {
        Cajero c = sesionService.getCajeroActual();
        if (c == null) return false;
        Cajero enBd = cajeroRepo.findById(c.getId()).orElse(c);
        return enBd.getApariencia() != null && !enBd.getApariencia().isBlank();
    }

    /** Solo el administrador cambia la de toda la tienda */
    @Transactional
    public Apariencia guardarTienda(Apariencia a) {
        sesionService.verificarAdmin();
        Apariencia nueva = a == null ? Apariencia.porDefecto() : a;
        Configuracion c = configuracionService.obtenerParaEditar();
        Apariencia antes = Apariencia.desdeJson(c.getApariencia());
        c.setApariencia(nueva.esPorDefecto() ? null : nueva.aJson());
        configuracionService.guardarDirecto(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "APARIENCIA_MODIFICADA",
                "Toda la tienda: " + resumen(antes) + " → " + resumen(nueva), (Cajero) null);
        return nueva;
    }

    /** Apariencia "solo para mí" del usuario en sesión (null = volver a la de la tienda) */
    @Transactional
    public Apariencia guardarPropia(Apariencia a) {
        Cajero actor = sesionService.requerirSesion();
        Cajero c = cajeroRepo.findById(actor.getId()).orElseThrow();
        c.setApariencia(a == null ? null : a.aJson());
        cajeroRepo.save(c);
        auditoriaService.registrar(actor, "APARIENCIA_PROPIA",
                a == null ? "Vuelve a la apariencia de la tienda" : "Solo para " + c.getUsuario() + ": " + resumen(a),
                (Cajero) null);
        sesionService.refrescarSesion();
        return a == null ? deTienda() : a;
    }

    /** Texto corto para la bitácora */
    public static String resumen(Apariencia a) {
        return a.tema().nombre() + ", " + a.acento() + ", iconos " + a.paqueteIconos() + "/" + a.estiloIconos()
                + ", letra " + a.letra().clave() + " " + a.escala() + " %" + (a.vidrio() ? ", vidrio" : "");
    }
}
