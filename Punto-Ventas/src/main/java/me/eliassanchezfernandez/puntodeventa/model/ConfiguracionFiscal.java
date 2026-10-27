package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Datos del emisor y del PAC para facturar (Configuración → Facturación).
 * Una sola fila (id = 1) en tabla aparte de {@link Configuracion}, como los
 * dispositivos. Todas las columnas son nullable: el accesor da el valor por
 * defecto. Las credenciales del PAC se guardan CIFRADAS (ver
 * {@code facturacion.BovedaCredenciales}); nunca en claro ni en la bitácora.
 */
@Entity
@Table(name = "configuracion_fiscal")
@Getter
@Setter
@NoArgsConstructor
public class ConfiguracionFiscal {

    public static final long ID_UNICO = 1L;
    public static final String SERIE_POR_DEFECTO = "A";

    @Id
    private Long id = ID_UNICO;

    // ── Emisor ───────────────────────────────────────────────────────────

    @Column(name = "emisor_rfc", length = 13)
    private String emisorRfc;

    /** Razón social o nombre exactamente como en la CSF del negocio */
    @Column(name = "emisor_nombre", length = 254)
    private String emisorNombre;

    /** c_RegimenFiscal del negocio (ej. 626 RESICO, 612 PF con actividad empresarial, 601 PM) */
    @Column(name = "emisor_regimen", length = 3)
    private String emisorRegimen;

    /** Código postal = lugar de expedición */
    @Column(name = "emisor_codigo_postal", length = 5)
    private String emisorCodigoPostal;

    /** Serie de las facturas (debe existir en la cuenta de Facturama) */
    @Column(name = "serie", length = 10)
    private String serie;

    /** Último folio emitido en la serie (el siguiente es +1) */
    @Column(name = "folio_ultimo")
    private Long folioUltimo;

    // ── PAC (Facturama) ──────────────────────────────────────────────────

    /** Usuario de la API de Facturama, cifrado con la llave de la instalación */
    @Column(name = "pac_usuario_cifrado", length = 500)
    private String pacUsuarioCifrado;

    /** Contraseña de la API de Facturama, cifrada con la llave de la instalación */
    @Column(name = "pac_contrasena_cifrada", length = 500)
    private String pacContrasenaCifrada;

    /** true = apisandbox (pruebas, sin validez fiscal); false = producción. Null = sandbox. */
    @Column(name = "pac_sandbox")
    private Boolean pacSandbox;

    // ── Accesores con valor por defecto ──────────────────────────────────

    public boolean isPacSandbox()  { return pacSandbox == null || pacSandbox; }

    public String getSerie()       { return serie != null && !serie.isBlank() ? serie : SERIE_POR_DEFECTO; }

    public long getFolioUltimo()   { return folioUltimo != null ? folioUltimo : 0L; }

    public boolean tieneCredenciales() {
        return pacUsuarioCifrado != null && !pacUsuarioCifrado.isBlank()
            && pacContrasenaCifrada != null && !pacContrasenaCifrada.isBlank();
    }

    /** Emisor completo: RFC, nombre, régimen y código postal */
    public boolean tieneEmisor() {
        return emisorRfc != null && !emisorRfc.isBlank()
            && emisorNombre != null && !emisorNombre.isBlank()
            && emisorRegimen != null && !emisorRegimen.isBlank()
            && emisorCodigoPostal != null && !emisorCodigoPostal.isBlank();
    }

    /** Se puede facturar: hay emisor y credenciales */
    public boolean estaLista() {
        return tieneEmisor() && tieneCredenciales();
    }
}
