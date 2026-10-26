package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Bitácora de seguridad y de acciones sensibles (USR-18): intentos fallidos,
 * bloqueos, autorizaciones de administrador, cancelaciones, cambios de
 * permisos, etc. Solo se insertan filas; nunca se editan ni borran.
 *
 * Quién hizo y quién autorizó se guardan por id y por login (único), además
 * del nombre para mostrar, para que una disputa no dependa de un nombre
 * repetido o cambiado.
 */
@Entity
@Table(name = "auditoria")
@Getter
@Setter
@NoArgsConstructor
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(name = "usuario_id")
    private Long usuarioId;

    /** Login del usuario en sesión (o el usuario capturado en el login) */
    @Column(name = "usuario_login", length = 60)
    private String usuarioLogin;

    @Column(name = "usuario_nombre", length = 100)
    private String usuarioNombre;

    /** Clave corta del evento, ej. LOGIN_FALLIDO, VENTA_CANCELADA */
    @Column(nullable = false, length = 60)
    private String accion;

    @Column(length = 500)
    private String detalle;

    /** Administrador que autorizó la acción con su clave, si aplica */
    @Column(name = "autorizado_por_id")
    private Long autorizadoPorId;

    @Column(name = "autorizado_por_login", length = 60)
    private String autorizadoPorLogin;

    @Column(name = "autorizado_por", length = 100)
    private String autorizadoPor;

    /** Evento registrado por el sistema o por un usuario anónimo del login */
    public Auditoria(String usuarioNombre, String accion, String detalle) {
        this.usuarioNombre = usuarioNombre;
        this.usuarioLogin  = usuarioNombre;
        this.accion = accion;
        this.detalle = detalle;
    }

    public Auditoria(Cajero actor, String accion, String detalle, Cajero autorizador) {
        if (actor != null) {
            this.usuarioId     = actor.getId();
            this.usuarioLogin  = actor.getUsuario();
            this.usuarioNombre = actor.getNombreCompleto();
        }
        if (autorizador != null) {
            this.autorizadoPorId    = autorizador.getId();
            this.autorizadoPorLogin = autorizador.getUsuario();
            this.autorizadoPor      = autorizador.getNombreCompleto();
        }
        this.accion = accion;
        this.detalle = detalle;
    }
}
