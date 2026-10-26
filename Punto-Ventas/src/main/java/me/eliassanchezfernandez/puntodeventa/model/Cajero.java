package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Entidad que representa a un usuario del sistema (cajero, gerente o
 * administrador).
 *
 * La contraseña siempre se almacena como hash BCrypt, NUNCA en texto plano.
 *
 * Permisos: cada rol tiene un conjunto por defecto (Permiso.porDefecto) y el
 * administrador puede conceder o revocar permisos individuales (USR-16).
 * Los campos nuevos de esta versión son opcionales (nullable) para que las
 * filas ya existentes en datos.db sigan funcionando con ddl-auto=update.
 */
@Entity
@Table(name = "cajeros")
@Getter
@Setter
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

    /** ROL del usuario (se guarda como texto vía RolConverter) */
    @Column(nullable = false)
    private Rol rol = Rol.CAJERO;

    @Column(nullable = false)
    private boolean activo = true;

    /** true = al iniciar sesión debe definir una contraseña nueva (USR-06) */
    @Column(name = "debe_cambiar_contrasena")
    private Boolean debeCambiarContrasena;

    /** Intentos fallidos consecutivos de inicio de sesión (USR-04) */
    @Column(name = "intentos_fallidos")
    private Integer intentosFallidos;

    /**
     * Bloqueo TEMPORAL por intentos fallidos: hasta esta fecha/hora no puede
     * entrar (USR-04). Siempre vence solo (con espera creciente); un
     * administrador puede quitarlo antes.
     */
    @Column(name = "bloqueado_hasta")
    private LocalDateTime bloqueadoHasta;

    /** Permisos agregados a los del rol */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cajero_permisos", joinColumns = @JoinColumn(name = "cajero_id"))
    @Column(name = "permiso", nullable = false)
    private Set<Permiso> permisosConcedidos = new HashSet<>();

    /** Permisos quitados de los del rol */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cajero_permisos_revocados", joinColumns = @JoinColumn(name = "cajero_id"))
    @Column(name = "permiso", nullable = false)
    private Set<Permiso> permisosRevocados = new HashSet<>();

    // ── Lógica de dominio ────────────────────────────────────────────────

    public boolean isDebeCambiarContrasena() {
        return Boolean.TRUE.equals(debeCambiarContrasena);
    }

    public boolean isBloqueado() {
        return bloqueadoHasta != null && LocalDateTime.now().isBefore(bloqueadoHasta);
    }

    public int getIntentosFallidosOCero() {
        return intentosFallidos == null ? 0 : intentosFallidos;
    }

    /** Permisos efectivos = (por defecto del rol ∪ concedidos) − revocados. ADMIN: todos. */
    public Set<Permiso> getPermisosEfectivos() {
        if (rol == Rol.ADMIN) return EnumSet.allOf(Permiso.class);
        Set<Permiso> efectivos = EnumSet.noneOf(Permiso.class);
        efectivos.addAll(Permiso.porDefecto(rol));
        efectivos.addAll(permisosConcedidos);
        efectivos.removeAll(permisosRevocados);
        return efectivos;
    }

    public boolean tienePermiso(Permiso permiso) {
        return permiso != null && getPermisosEfectivos().contains(permiso);
    }

    @Override
    public String toString() {
        return "Cajero{id=" + id + ", usuario=" + usuario + ", rol=" + rol + ", activo=" + activo + "}";
    }

    // ── Enum de roles ─────────────────────────────────────────────────────

    public enum Rol {
        /** Acceso completo: configuración, usuarios, corte, reportes */
        ADMIN("Administrador"),
        /** Encargado: edita productos y promociones, desactiva usuarios; más auditado */
        GERENTE("Gerente"),
        /** Acceso a ventas e inventario básico */
        CAJERO("Cajero");

        private final String etiqueta;

        Rol(String etiqueta) { this.etiqueta = etiqueta; }

        public String getEtiqueta() { return etiqueta; }
    }
}
