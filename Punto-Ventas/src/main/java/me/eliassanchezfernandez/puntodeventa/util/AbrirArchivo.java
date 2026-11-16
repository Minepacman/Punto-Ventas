package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Abre con el programa del sistema un archivo (PDF o XML) o una carpeta que sean de la propia aplicación.
 *
 * <p>{@link Navegador} solo acepta {@code https:} y {@code mailto:}, así que las facturas (PDF/XML en la
 * carpeta {@code facturas}) no se podían abrir desde el programa. Aquí se permite abrir archivos locales,
 * pero con reglas cerradas:
 * <ul>
 *   <li>Solo archivos regulares con extensión {@code .pdf} o {@code .xml}.</li>
 *   <li>Solo dentro de alguna de las carpetas que el llamador declara como propias (facturas, registros,
 *       respaldos o una carpeta temporal donde el programa acaba de escribir). Se compara la <b>ruta real</b>
 *       ({@link Path#toRealPath}) del archivo y de la carpeta, así que un enlace simbólico que apunte fuera
 *       de la carpeta se rechaza.</li>
 *   <li>Carpetas: solo las declaradas (o una subcarpeta suya, p. ej. {@code facturas/2026}).</li>
 *   <li>El comando es el mismo de {@link Navegador} ({@code open} / {@code rundll32 url.dll,FileProtocolHandler}
 *       / {@code xdg-open}) con la ruta como argumento aparte: nunca pasa por un shell.</li>
 * </ul>
 * Si algo no se puede abrir devuelve {@code false} sin lanzar; el llamador muestra la ruta o ofrece «Guardar como…».
 */
public final class AbrirArchivo {

    /** Extensiones (en minúsculas) que se permiten abrir con el programa del sistema */
    public static final Set<String> EXTENSIONES = Set.of("pdf", "xml");

    private AbrirArchivo() {
    }

    /**
     * Ruta real del archivo si cumple todas las reglas (archivo regular, extensión permitida y dentro de una de
     * las carpetas), o vacío. No lanza.
     */
    public static Optional<Path> archivoPermitido(Path archivo, Collection<Path> carpetasPermitidas) {
        if (archivo == null) return Optional.empty();
        Path real;
        try {
            real = archivo.toRealPath();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(real) || !tieneRutaSana(real)) return Optional.empty();
        if (!EXTENSIONES.contains(extension(real))) return Optional.empty();
        for (Path carpeta : carpetasReales(carpetasPermitidas)) {
            if (real.startsWith(carpeta) && !real.equals(carpeta)) return Optional.of(real);
        }
        return Optional.empty();
    }

    /**
     * Ruta real de la carpeta si es una de las permitidas o una subcarpeta de ellas, o vacío. No lanza.
     */
    public static Optional<Path> carpetaPermitida(Path carpeta, Collection<Path> carpetasPermitidas) {
        if (carpeta == null) return Optional.empty();
        Path real;
        try {
            real = carpeta.toRealPath();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (!Files.isDirectory(real) || !tieneRutaSana(real)) return Optional.empty();
        for (Path permitida : carpetasReales(carpetasPermitidas)) {
            if (real.startsWith(permitida)) return Optional.of(real);
        }
        return Optional.empty();
    }

    /**
     * Abre el archivo con el programa predeterminado del sistema.
     *
     * @return true si el comando se lanzó (no garantiza que haya un visor de PDF instalado)
     */
    public static boolean abrir(Path archivo, Collection<Path> carpetasPermitidas) {
        return archivoPermitido(archivo, carpetasPermitidas).map(AbrirArchivo::lanzar).orElse(false);
    }

    /** Abre la carpeta en el explorador de archivos del sistema; mismo criterio de retorno que {@link #abrir}. */
    public static boolean abrirCarpeta(Path carpeta, Collection<Path> carpetasPermitidas) {
        return carpetaPermitida(carpeta, carpetasPermitidas).map(AbrirArchivo::lanzar).orElse(false);
    }

    /** Comando del sistema para abrir la ruta ya validada (sin shell); vacío si no hay uno conocido */
    static Optional<List<String>> comando(String sistema, Path rutaReal) {
        return Navegador.comando(sistema, rutaReal.toString());
    }

    private static boolean lanzar(Path real) {
        Optional<List<String>> cmd = comando(System.getProperty("os.name"), real);
        return cmd.isPresent() && Navegador.ejecutar(cmd.get());
    }

    /** Rutas reales de las carpetas permitidas que existen (las que no, se ignoran) */
    private static List<Path> carpetasReales(Collection<Path> carpetas) {
        List<Path> reales = new ArrayList<>();
        if (carpetas == null) return reales;
        for (Path c : carpetas) {
            if (c == null) continue;
            try {
                Path real = c.toRealPath();
                if (Files.isDirectory(real)) reales.add(real);
            } catch (IOException | RuntimeException ignorada) {
                // carpeta inexistente o ilegible: no habilita nada
            }
        }
        return reales;
    }

    static String extension(Path ruta) {
        Path nombre = ruta.getFileName();
        if (nombre == null) return "";
        String n = nombre.toString();
        int punto = n.lastIndexOf('.');
        if (punto <= 0 || punto == n.length() - 1) return "";
        return n.substring(punto + 1).toLowerCase(Locale.ROOT);
    }

    /** Sin caracteres de control (saltos de línea, etc.) en la ruta que irá como argumento del comando */
    private static boolean tieneRutaSana(Path ruta) {
        String s = ruta.toString();
        return !s.isBlank() && s.length() <= 4000 && s.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }
}
