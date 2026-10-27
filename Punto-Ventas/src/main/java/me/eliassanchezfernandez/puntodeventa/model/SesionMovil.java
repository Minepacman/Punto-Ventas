package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Sesión de la interfaz web móvil. Del token que recibe el teléfono solo se
 * guarda su hash SHA-256 (si se filtrara la BD, los tokens no servirían).
 *
 * Las fechas se guardan en UTC para que "48 horas" sean exactamente 48 horas
 * aunque cambie el horario de verano. expira == null significa sesión
 * permanente (el dueño eligió "mantener la sesión siempre abierta").
 */
@Entity
@Table(name = "sesiones_movil")
@Getter
@Setter
@NoArgsConstructor
public class SesionMovil {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "cajero_id", nullable = false)
    private Long cajeroId;

    /** UTC */
    @Column(nullable = false)
    private LocalDateTime creada;

    /** UTC; null = permanente */
    @Column
    private LocalDateTime expira;

    /** UTC */
    @Column(name = "ultimo_uso")
    private LocalDateTime ultimoUso;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 250)
    private String userAgent;

    @Column(nullable = false)
    private boolean revocada;

    public boolean isPermanente() {
        return expira == null;
    }
}
