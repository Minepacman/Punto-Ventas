package me.eliassanchezfernandez.puntodeventa.facturacion;

/**
 * Falla al hablar con el PAC. El mensaje está en español y es apto para
 * mostrarse al usuario; nunca incluye credenciales.
 */
public class PacException extends Exception {

    public enum Tipo {
        /** Sin internet, tiempo agotado: NO se sabe si el PAC recibió la petición */
        RED,
        /** Usuario/contraseña rechazados o cuenta sin saldo (definitivo) */
        AUTENTICACION,
        /** El PAC o el SAT rechazaron los datos (definitivo) */
        VALIDACION,
        /** Error del servidor del PAC (5xx): puede haberse procesado o no */
        SERVIDOR,
        /** Respuesta que no se pudo interpretar */
        RESPUESTA
    }

    private final Tipo tipo;
    private final int codigoHttp;

    public PacException(Tipo tipo, String mensaje) {
        this(tipo, mensaje, 0, null);
    }

    public PacException(Tipo tipo, String mensaje, int codigoHttp, Throwable causa) {
        super(mensaje, causa);
        this.tipo = tipo;
        this.codigoHttp = codigoHttp;
    }

    public Tipo getTipo() { return tipo; }
    public int getCodigoHttp() { return codigoHttp; }

    /** true si es seguro que el comprobante NO quedó timbrado (se puede reintentar sin consultar) */
    public boolean esDefinitivo() {
        return tipo == Tipo.AUTENTICACION || tipo == Tipo.VALIDACION;
    }
}
