package me.eliassanchezfernandez.puntodeventa.impresion;

/** Falla al enviar algo a la impresora (no encontrada, sin papel, sin respuesta...). El mensaje es para el usuario. */
public class ImpresionException extends Exception {
    public ImpresionException(String mensaje) { super(mensaje); }
    public ImpresionException(String mensaje, Throwable causa) { super(mensaje, causa); }
}
