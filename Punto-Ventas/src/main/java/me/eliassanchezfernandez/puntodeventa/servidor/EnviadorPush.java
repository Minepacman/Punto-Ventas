package me.eliassanchezfernandez.puntodeventa.servidor;

import java.io.IOException;

/**
 * Entrega de un mensaje Web Push ya cifrado al servicio de push del navegador (RFC 8030).
 * Interfaz para poder sustituirla en pruebas; la real es {@link EnviadorPushHttp}.
 */
public interface EnviadorPush {

    /** Respuesta del servicio de push */
    record Resultado(int estado, String detalle) {
        public boolean ok() { return estado >= 200 && estado < 300; }
        /** 404/410: la suscripción ya no existe (el usuario quitó el permiso o borró la app): hay que olvidarla */
        public boolean suscripcionMuerta() { return estado == 404 || estado == 410; }
    }

    /**
     * @param endpoint      URL https del servicio de push (ya validada contra la lista de servicios conocidos)
     * @param cuerpo        mensaje cifrado aes128gcm (RFC 8291)
     * @param authorization cabecera VAPID ("vapid t=..., k=...")
     * @param ttlSegundos   cuánto puede guardarlo el servicio si el teléfono está apagado
     * @param urgente       Urgency: high (hoy) o normal
     */
    Resultado enviar(String endpoint, byte[] cuerpo, String authorization, int ttlSegundos, boolean urgente) throws IOException;
}
