package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Abre enlaces en el navegador (o el cliente de correo) del sistema.
 *
 * <p>{@code HostServices.showDocument} de JavaFX no hizo nada en el programa instalado en macOS (v1.0.0,
 * comprobado por el dueño: «Descargar» y «Ver en GitHub» no abrían el navegador), así que
 * aquí se usa el comando del propio sistema: {@code open} (macOS), {@code rundll32} (Windows) o
 * {@code xdg-open} (Linux). La URL va como argumento separado (sin shell), y solo se aceptan
 * {@code https:} y {@code mailto:}.
 */
public final class Navegador {

    private Navegador() {
    }

    /** ¿Se puede abrir? Solo https (con host) y mailto; nada de file:, javascript:, http: ni rutas locales. */
    public static boolean esSegura(String url) {
        if (url == null || url.isBlank() || url.length() > 4000) return false;
        if (url.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) return false;
        try {
            URI u = URI.create(url.trim());
            String esquema = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return switch (esquema) {
                case "https" -> u.getHost() != null && !u.getHost().isBlank();
                case "mailto" -> true;
                default -> false;
            };
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Comando del sistema para abrir la URL, o vacío si no hay uno conocido */
    static Optional<List<String>> comando(String sistema, String url) {
        String so = sistema == null ? "" : sistema.toLowerCase(Locale.ROOT);
        if (so.contains("mac")) return Optional.of(List.of("open", url));
        if (so.contains("win")) return Optional.of(List.of("rundll32", "url.dll,FileProtocolHandler", url));
        if (so.contains("nux") || so.contains("nix") || so.contains("bsd")) return Optional.of(List.of("xdg-open", url));
        return Optional.empty();
    }

    /**
     * Intenta abrir la URL con el comando del sistema.
     *
     * @return true si el comando se lanzó (no garantiza que el navegador la haya mostrado)
     */
    public static boolean abrir(String url) {
        if (!esSegura(url)) return false;
        Optional<List<String>> cmd = comando(System.getProperty("os.name"), url.trim());
        if (cmd.isEmpty()) return false;
        try {
            Process p = new ProcessBuilder(cmd.get())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
