package me.eliassanchezfernandez.puntodeventa.bascula;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link FlujoSerial} sobre jSerialComm (8 bits de datos, 1 de paro, sin
 * paridad: lo habitual en básculas comerciales). jSerialComm extrae su librería
 * nativa la primera vez que se usa; si el equipo no la soporta, las llamadas
 * lanzan un Error de enlace que {@link BasculaService} convierte en un aviso.
 */
public class FlujoJSerialComm implements FlujoSerial {

    private final String puerto;
    private final int baudios;
    private SerialPort serial;

    public FlujoJSerialComm(String puerto, int baudios) {
        this.puerto = puerto;
        this.baudios = baudios;
    }

    /** Puertos serie del equipo (COM3, /dev/tty.usbserial...). Vacío si no hay o no se puede consultar. */
    public static List<String> listarPuertos() {
        List<String> nombres = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) nombres.add(p.getSystemPortName());
        return nombres;
    }

    @Override
    public void abrir() throws IOException {
        serial = SerialPort.getCommPort(puerto);
        serial.setComPortParameters(baudios, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        serial.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
        if (!serial.openPort()) {
            throw new IOException("No se pudo abrir el puerto " + puerto + " (¿está en uso o no hay permiso?).");
        }
    }

    @Override
    public void escribir(byte[] datos) throws IOException {
        if (serial.writeBytes(datos, datos.length) < 0) throw new IOException("No se pudo escribir en " + puerto + ".");
    }

    @Override
    public void limpiarEntrada() {
        byte[] descarte = new byte[256];
        while (serial.bytesAvailable() > 0) serial.readBytes(descarte, descarte.length);
    }

    @Override
    public byte[] leer(int esperaMs) throws IOException {
        serial.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, Math.max(1, esperaMs), 0);
        byte[] buffer = new byte[256];
        int n = serial.readBytes(buffer, buffer.length);
        if (n < 0) throw new IOException("Se perdió la conexión con " + puerto + ".");
        return java.util.Arrays.copyOf(buffer, n);
    }

    @Override
    public void close() {
        if (serial != null) serial.closePort();
    }
}
