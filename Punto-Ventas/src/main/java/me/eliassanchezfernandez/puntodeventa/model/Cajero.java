package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidad que representa a un usuario del sistema (cajero o administrador).
 *
 * La contraseña siempre se almacena como hash BCrypt,
 * NUNCA en texto plano.
 */

@Entity
@Table(name = "cajeros")
@Data
@NoArgsConstructor
public class Cajero {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre de usuario para el login (único, sin espacios recomendado) */
    @Column(nullable = false, unique = true, length = 60)
    private String usuario;

    /** Nombre real para mostrar en la UI */
    @Column(name = "nombre_completo", nullable = false, length = 150)
    private String nombreCompleto;

    /** Hash BCrypt de la contraseña — nunca texto plano */
    @Column(nullable = false, length = 255)
    private String contrasenaHash;

    /** ROL del usuario: ADMIN puede acceder a configuración y corte */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rol rol = Rol.CAJERO;

    @Column(nullable = false)
    private boolean activo = true;

    // ── Enum de roles ─────────────────────────────────────────────────────

    public enum Rol {
        /** Acceso completo: configuración, corte, reportes */
        ADMIN,
        /** Acceso solo a ventas e inventario básico */
        CAJERO
    }
}