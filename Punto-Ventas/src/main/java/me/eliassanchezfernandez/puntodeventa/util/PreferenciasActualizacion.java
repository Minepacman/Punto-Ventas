package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

/**
 * Cuándo se buscaron actualizaciones por última vez en ESTA computadora
 * (archivo {@code actualizaciones.properties} en la carpeta de la aplicación,
 * como {@link PreferenciasLogin}). Es local y no de la BD porque cada caja
 * consulta por su cuenta y no vale la pena una transacción para esto.
 * Tolerante a fallos: si no se puede leer o escribir, simplemente se vuelve a
 * buscar en el siguiente arranque.
 */
public final class PreferenciasActualizacion {

    public static final String ARCHIVO = "actualizaciones.properties";
    static final String CLAVE_ULTIMA = "ultima.revision";
    /** Se busca a lo más una vez al día */
    public static final Duration INTERVALO = Duration.ofHours(24);

    private PreferenciasActualizacion() { }

    public static Path archivo(Path carpeta) {
        return carpeta.resolve(ARCHIVO);
    }

    /** Momento de la última búsqueda (epoch ms), si se guardó y es legible */
    public static Optional<Instant> ultimaRevision(Path carpeta) {
        Path archivo = archivo(carpeta);
        if (!Files.isRegularFile(archivo)) return Optional.empty();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(archivo)) {
            p.load(in);
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(p.getProperty(CLAVE_ULTIMA, "").trim())));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** true si nunca se buscó, pasaron 24 h o la fecha guardada es del futuro (reloj cambiado) */
    public static boolean tocaRevisar(Path carpeta, Instant ahora) {
        return ultimaRevision(carpeta)
                .map(u -> u.isAfter(ahora) || Duration.between(u, ahora).compareTo(INTERVALO) >= 0)
                .orElse(true);
    }

    /** Anota que se acaba de buscar (haya o no versión nueva). Nunca lanza. */
    public static void anotarRevision(Path carpeta, Instant ahora) {
        try {
            Files.createDirectories(carpeta);
            Properties p = new Properties();
            p.setProperty(CLAVE_ULTIMA, String.valueOf(ahora.toEpochMilli()));
            Path temporal = carpeta.resolve(ARCHIVO + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporal)) {
                p.store(out, "Ultima busqueda de actualizaciones de esta computadora");
            }
            Files.move(temporal, archivo(carpeta), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("No se pudo anotar la búsqueda de actualizaciones: " + e.getMessage());
        }
    }
}
