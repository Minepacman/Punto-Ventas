package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Bitácora de acciones sensibles (USR-18). Solo inserta; nunca borra.
 * Las entradas de seguridad (login fallido, bloqueo) se guardan en una
 * transacción independiente para que no se pierdan si la operación
 * principal se revierte.
 *
 * No depende de SesionService (evita el ciclo): quien registra pasa al actor.
 */
@Service
public class AuditoriaService {

    @Autowired private AuditoriaRepository auditoriaRepo;

    /** Registra dentro de la transacción actual, con actor y autorizador por id/login/nombre */
    @Transactional
    public void registrar(Cajero actor, String accion, String detalle, Cajero autorizador) {
        auditoriaRepo.save(new Auditoria(actor, accion, recortar(detalle), autorizador));
    }

    /** Igual, tomando al autorizador de la autorización usada (puede ser null) */
    @Transactional
    public void registrar(Cajero actor, String accion, String detalle, Autorizacion autorizacion) {
        registrar(actor, accion, detalle, autorizacion != null ? autorizacion.administrador() : null);
    }

    /** Evento del sistema (sin usuario en sesión) dentro de la transacción actual */
    @Transactional
    public void registrarSistema(String accion, String detalle) {
        auditoriaRepo.save(new Auditoria("sistema", accion, recortar(detalle)));
    }

    /**
     * Eventos de seguridad del login/autorización. En el flujo real no hay
     * transacción externa, así que cada fila se confirma de inmediato. NO se
     * usa REQUIRES_NEW: SQLite admite un solo escritor y una segunda conexión
     * dentro de una transacción que ya escribió se quedaría bloqueada.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void registrarIndependiente(String usuarioLogin, String accion, String detalle) {
        auditoriaRepo.save(new Auditoria(usuarioLogin, accion, recortar(detalle)));
    }

    public List<Auditoria> ultimas() {
        return auditoriaRepo.findTop200ByOrderByFechaDesc();
    }

    private static String recortar(String detalle) {
        if (detalle == null) return null;
        return detalle.length() > 500 ? detalle.substring(0, 497) + "..." : detalle;
    }
}
