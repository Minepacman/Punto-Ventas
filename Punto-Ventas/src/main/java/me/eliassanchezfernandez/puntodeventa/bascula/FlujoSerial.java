package me.eliassanchezfernandez.puntodeventa.bascula;

import java.io.IOException;

/**
 * Canal serie hacia la báscula (abstracción para poder probar sin hardware:
 * en producción es {@link FlujoJSerialComm}).
 */
public interface FlujoSerial extends AutoCloseable {

    void abrir() throws IOException;

    void escribir(byte[] datos) throws IOException;

    /** Vacía lo que haya llegado antes de pedir un peso nuevo */
    void limpiarEntrada();

    /** Lee lo que haya llegado, esperando hasta {@code esperaMs}; arreglo vacío si no llegó nada */
    byte[] leer(int esperaMs) throws IOException;

    @Override void close();
}
