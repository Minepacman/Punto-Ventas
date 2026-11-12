package me.eliassanchezfernandez.puntodeventa.impresion;

/** Canal por el que salen los bytes crudos hacia la impresora (en las pruebas es un canal falso). */
@FunctionalInterface
public interface EnvioRaw {
    void enviar(byte[] datos) throws ImpresionException;
}
