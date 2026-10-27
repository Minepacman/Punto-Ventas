package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.SesionMovil;
import me.eliassanchezfernandez.puntodeventa.servidor.ServidorMovilService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Administración del acceso móvil desde el escritorio (Configuración → Acceso
 * móvil). Todo es solo ADMIN y queda en la bitácora. No es transaccional a
 * propósito: arrancar o detener el servidor no debe ocurrir dentro de una
 * transacción de BD.
 */
@Service
public class AccesoMovilService {

    @Autowired private SesionService        sesionService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionMovilService   sesionMovilService;
    @Autowired private ServidorMovilService servidor;
    @Autowired private AuditoriaService     auditoriaService;

    /**
     * Enciende o apaga el servidor EN VIVO y guarda la preferencia. Si el
     * puerto está ocupado lanza NegocioException y no se guarda "activo".
     */
    public Configuracion configurar(boolean activo, int puerto) {
        sesionService.verificarAdmin();
        ConfiguracionService.validarPuertoMovil(puerto);

        int puertoAnterior = servidor.estaActivo() ? servidor.getPuerto() : -1;
        if (activo) {
            if (servidor.estaActivo() && puertoAnterior != puerto) servidor.detener();
            if (!servidor.estaActivo()) {
                try {
                    servidor.iniciar(puerto);
                } catch (NegocioException e) {
                    if (puertoAnterior > 0 && puertoAnterior != puerto) {
                        try { servidor.iniciar(puertoAnterior); } catch (NegocioException ignorada) { /* queda apagado */ }
                    }
                    throw e;
                }
            }
        } else {
            servidor.detener();
        }
        try {
            return configuracionService.guardarAccesoMovil(activo, puerto);
        } catch (RuntimeException e) {
            if (activo) servidor.detener();
            throw e;
        }
    }

    /** Sesión abierta con el nombre de su usuario, lista para mostrar */
    public record SesionInfo(SesionMovil sesion, String usuario) { }

    public List<SesionInfo> sesionesActivas() {
        sesionService.verificarAdmin();
        return sesionMovilService.listarActivas().stream()
                .map(s -> new SesionInfo(s, sesionMovilService.cajeroDe(s)
                        .map(c -> c.getUsuario() + " (" + c.getNombreCompleto() + ")").orElse("(usuario eliminado)")))
                .toList();
    }

    public Configuracion obtenerConfiguracion() {
        return configuracionService.obtener();
    }

    public boolean servidorActivo() { return servidor.estaActivo(); }

    public int puertoEnUso() { return servidor.getPuerto(); }

    public List<String> urlsLan(int puerto) { return servidor.urlsLan(puerto); }

    public void revocar(Long sesionId) {
        sesionService.verificarAdmin();
        sesionMovilService.revocarPorId(sesionId).ifPresent(s ->
                auditoriaService.registrar(sesionService.getCajeroActual(), "SESION_MOVIL_REVOCADA",
                        "Sesión móvil id " + s.getId() + " (usuario id " + s.getCajeroId() + ", ip " + s.getIp() + ")",
                        (me.eliassanchezfernandez.puntodeventa.model.Cajero) null));
    }

    public int revocarTodas() {
        sesionService.verificarAdmin();
        int n = sesionMovilService.revocarTodas();
        auditoriaService.registrar(sesionService.getCajeroActual(), "SESION_MOVIL_REVOCADA",
                "Se cerraron todas las sesiones móviles (" + n + ")",
                (me.eliassanchezfernandez.puntodeventa.model.Cajero) null);
        return n;
    }
}
