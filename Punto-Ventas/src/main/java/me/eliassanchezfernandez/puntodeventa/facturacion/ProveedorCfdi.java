package me.eliassanchezfernandez.puntodeventa.facturacion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Proveedor Autorizado de Certificación (PAC): timbra, entrega archivos,
 * cancela y envía por correo. Hoy lo implementa {@link FacturamaProveedor};
 * otro PAC solo tiene que implementar esta interfaz.
 *
 * Toda falla es {@link PacException}: con {@code esDefinitivo()} se sabe si
 * el comprobante seguro NO se timbró (error de validación o de credenciales)
 * o si no se sabe (tiempo agotado, caída del servidor): en ese caso se
 * consulta por {@link #buscarPorNumeroOrden} antes de reintentar.
 */
public interface ProveedorCfdi {

    /** Comprobante ya timbrado en el PAC */
    record Timbrado(String idExterno, String uuid, String serie, String folio, LocalDateTime fecha, BigDecimal total) { }

    /** Archivo descargado (PDF o XML) */
    record Archivo(byte[] contenido, String tipoContenido) { }

    enum Formato { PDF, XML }

    /** Resultado de la cancelación: aceptada de inmediato, o en espera del receptor (plazo del SAT) */
    record Cancelacion(boolean aceptada, boolean enEspera, String estado, String mensaje) { }

    /** c_MotivoCancelacion */
    enum MotivoCancelacion {
        ERRORES_CON_RELACION("01", "Comprobante emitido con errores con relación (se sustituye)"),
        ERRORES_SIN_RELACION("02", "Comprobante emitido con errores sin relación"),
        NO_SE_LLEVO_A_CABO("03", "No se llevó a cabo la operación"),
        OPERACION_NOMINATIVA_GLOBAL("04", "Operación nominativa relacionada en una factura global");

        private final String clave;
        private final String descripcion;

        MotivoCancelacion(String clave, String descripcion) { this.clave = clave; this.descripcion = descripcion; }

        public String getClave() { return clave; }
        public String getDescripcion() { return descripcion; }
        public boolean requiereSustitucion() { return this == ERRORES_CON_RELACION; }
        @Override public String toString() { return clave + " — " + descripcion; }

        public static MotivoCancelacion porClave(String clave) {
            for (MotivoCancelacion m : values()) if (m.clave.equals(clave)) return m;
            throw new IllegalArgumentException("Motivo de cancelación desconocido: " + clave);
        }
    }

    Timbrado timbrar(CfdiDocumento documento) throws PacException;

    /** Comprobante ya timbrado con esa referencia (numeroOrden), si existe: evita duplicados al reintentar */
    Optional<Timbrado> buscarPorNumeroOrden(String numeroOrden) throws PacException;

    Archivo descargar(String idExterno, Formato formato) throws PacException;

    /** @param uuidSustitucion obligatorio con motivo 01; null en los demás */
    Cancelacion cancelar(String idExterno, MotivoCancelacion motivo, String uuidSustitucion) throws PacException;

    void enviarPorCorreo(String idExterno, String email) throws PacException;

    /** Verifica credenciales y conectividad; lanza PacException si algo falla */
    void probarConexion() throws PacException;
}
