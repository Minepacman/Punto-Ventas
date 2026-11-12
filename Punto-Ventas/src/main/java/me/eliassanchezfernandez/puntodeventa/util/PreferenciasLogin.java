package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Preferencias locales de la pantalla de inicio de sesión (por computadora, no
 * por usuario): hoy solo el ÚLTIMO USUARIO que entró, para que el cajero
 * habitual solo teclee su contraseña. Nunca se guarda la contraseña.
 *
 * Vive en la carpeta de la aplicación (junto a conexion.properties) y es
 * tolerante a fallos: si no se puede leer o escribir, el login funciona igual.
 */
public final class PreferenciasLogin {

    public static final String ARCHIVO = "login.properties";
    static final String CLAVE_ULTIMO_USUARIO = "ultimo.usuario";
    static final int LARGO_MAXIMO_USUARIO = 60;

    private PreferenciasLogin() { }

    public static Path archivo(Path carpeta) {
        return carpeta.resolve(ARCHIVO);
    }

    /** Último usuario que inició sesión en esta computadora, si se guardó */
    public static Optional<String> ultimoUsuario(Path carpeta) {
        Path archivo = archivo(carpeta);
        if (!Files.isRegularFile(archivo)) return Optional.empty();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(archivo)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        String usuario = p.getProperty(CLAVE_ULTIMO_USUARIO, "").trim();
        if (usuario.isEmpty() || usuario.length() > LARGO_MAXIMO_USUARIO) return Optional.empty();
        return Optional.of(usuario);
    }

    /** Recuerda al usuario (vacío o null = olvidar). Nunca lanza. */
    public static void guardarUltimoUsuario(Path carpeta, String usuario) {
        String limpio = usuario == null ? "" : usuario.trim();
        if (limpio.length() > LARGO_MAXIMO_USUARIO) limpio = limpio.substring(0, LARGO_MAXIMO_USUARIO);
        try {
            Files.createDirectories(carpeta);
            Properties p = new Properties();
            if (!limpio.isEmpty()) p.setProperty(CLAVE_ULTIMO_USUARIO, limpio);
            Path destino = archivo(carpeta);
            Path temporal = carpeta.resolve(ARCHIVO + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporal)) {
                p.store(out, "Preferencias del inicio de sesion de esta computadora (no contiene contrasenas)");
            }
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("No se pudo guardar el último usuario del inicio de sesión: " + e.getMessage());
        }
    }

    public static void olvidarUltimoUsuario(Path carpeta) {
        guardarUltimoUsuario(carpeta, null);
    }
}
