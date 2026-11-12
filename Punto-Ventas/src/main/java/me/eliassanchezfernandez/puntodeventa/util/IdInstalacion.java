package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Identificador aleatorio de ESTA instalación ({@code instalacion.id} junto a
 * {@code conexion.properties}). Solo sirve para que el desarrollador anote en
 * su libro desde qué computadoras se ha pedido soporte con una misma clave de
 * donador; no se firma, no se valida y no ata la clave a la máquina (las PCs
 * se reinstalan y los negocios tienen varias cajas). Si no se puede leer ni
 * crear, se informa "sin id". Nunca lanza.
 */
public final class IdInstalacion {

    public static final String ARCHIVO = "instalacion.id";
    public static final String SIN_ID = "sin id";

    private IdInstalacion() { }

    /** "7F3A-91C2": 8 hex aleatorios, creado la primera vez */
    public static String obtener(Path carpeta) {
        Path archivo = carpeta.resolve(ARCHIVO);
        try {
            if (Files.isRegularFile(archivo)) {
                String leido = normalizar(Files.readString(archivo, StandardCharsets.US_ASCII));
                if (leido != null) return leido;
            }
            String nuevo = generar();
            Files.createDirectories(carpeta);
            Files.writeString(archivo, nuevo + System.lineSeparator(), StandardCharsets.US_ASCII);
            return nuevo;
        } catch (IOException | RuntimeException e) {
            return SIN_ID;
        }
    }

    static String generar() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        return String.format("%02X%02X-%02X%02X", b[0], b[1], b[2], b[3]);
    }

    /** Acepta "7F3A-91C2" o "7f3a91c2"; null si el archivo tiene otra cosa */
    static String normalizar(String texto) {
        if (texto == null) return null;
        String t = texto.trim().toUpperCase(Locale.ROOT).replace("-", "");
        if (!t.matches("[0-9A-F]{8}")) return null;
        return t.substring(0, 4) + "-" + t.substring(4);
    }
}
