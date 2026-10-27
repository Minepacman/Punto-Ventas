package me.eliassanchezfernandez.puntodeventa.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Dónde viven los archivos de la aplicación: la base de datos SQLite
 * (spring.datasource.url) y la carpeta "datos" junto a ella (logotipo).
 *
 * La restauración de un respaldo no puede reemplazar datos.db con la
 * aplicación abierta (hay conexiones con el archivo abierto), así que el
 * servicio deja el respaldo como "datos.db.restaurar" y {@link
 * #aplicarRestauracionPendiente} lo pone en su lugar al siguiente arranque,
 * antes de que Spring abra la base.
 */
@Component
public class RutasApp {

    public static final String URL_POR_DEFECTO = "jdbc:sqlite:datos.db";
    public static final String SUFIJO_RESTAURAR = ".restaurar";

    @Value("${spring.datasource.url:" + URL_POR_DEFECTO + "}")
    private String urlBd;

    /** Carpeta de datos propia (opcional, p. ej. en pruebas); vacío = junto a la BD */
    @Value("${app.datos-dir:}")
    private String carpetaDatosConfigurada;

    // ── Dónde viven los datos por defecto ─────────────────────────────────

    /** Nombre de la carpeta de datos de la aplicación */
    public static final String NOMBRE_CARPETA = "PuntoDeVenta";
    private static final String PROPIEDAD_URL = "spring.datasource.url";
    private static final String ENTORNO_URL = "SPRING_DATASOURCE_URL";

    /**
     * Carpeta de datos del usuario (escribible sin ser administrador, a diferencia
     * de la carpeta donde se instala el programa):
     * Windows %APPDATA%\PuntoDeVenta · macOS ~/Library/Application Support/PuntoDeVenta ·
     * otros ~/.puntodeventa
     */
    public static Path carpetaAplicacion(String sistema, Map<String, String> entorno, Path casa) {
        String os = sistema == null ? "" : sistema.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appdata = entorno.get("APPDATA");
            Path base = appdata != null && !appdata.isBlank() ? Path.of(appdata) : casa.resolve("AppData").resolve("Roaming");
            return base.resolve(NOMBRE_CARPETA);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return casa.resolve("Library").resolve("Application Support").resolve(NOMBRE_CARPETA);
        }
        return casa.resolve(".puntodeventa");
    }

    public static Path carpetaAplicacion() {
        return carpetaAplicacion(System.getProperty("os.name"), System.getenv(), Path.of(System.getProperty("user.home")));
    }

    /**
     * URL de la base cuando nadie indicó una.
     *  - Si ya existe ./datos.db en la carpeta de trabajo (desarrollo, o instalación anterior que se
     *    ejecutaba desde su carpeta) se SIGUE USANDO esa: no se mueve ni se copia nada, así el archivo
     *    original queda intacto y nunca hay dos copias que se desfasen.
     *  - Si no, la base vive en la carpeta de datos del usuario.
     */
    public static String urlInicial(Path carpetaTrabajo, Path carpetaAplicacion) {
        Path anterior = carpetaTrabajo.resolve("datos.db");
        if (Files.isRegularFile(anterior)) return "jdbc:sqlite:" + anterior.toAbsolutePath();
        return "jdbc:sqlite:" + carpetaAplicacion.resolve("datos.db");
    }

    /** ¿El arranque trae una URL de base de datos explícita? (variable de entorno, -D o argumento) */
    public static boolean hayUrlExplicita(String[] args, Map<String, String> entorno) {
        if (System.getProperty(PROPIEDAD_URL) != null) return true;
        String env = entorno.get(ENTORNO_URL);
        if (env != null && !env.isBlank()) return true;
        if (args != null) {
            for (String a : args) if (a != null && a.startsWith("--" + PROPIEDAD_URL)) return true;
        }
        return false;
    }

    /**
     * Se llama desde Launcher ANTES de Spring: fija spring.datasource.url si nadie la indicó
     * (y crea la carpeta de datos). Devuelve la URL que quedará en uso.
     */
    public static String prepararUrl(String[] args) {
        Map<String, String> entorno = System.getenv();
        if (hayUrlExplicita(args, entorno)) {
            String explicita = System.getProperty(PROPIEDAD_URL);
            if (explicita == null) explicita = entorno.get(ENTORNO_URL);
            return explicita != null ? explicita : URL_POR_DEFECTO;
        }
        Path carpeta = carpetaAplicacion();
        String url = urlInicial(Path.of("").toAbsolutePath(), carpeta);
        try {
            Files.createDirectories(rutaDeUrl(url).getParent());
        } catch (IOException | RuntimeException e) {
            System.err.println("No se pudo crear la carpeta de datos (" + carpeta + "): " + e.getMessage());
        }
        System.setProperty(PROPIEDAD_URL, url);
        return url;
    }

    /** Carpeta sugerida para los respaldos automáticos: junto a la base de datos */
    public Path carpetaRespaldosSugerida() {
        return archivoBd().resolveSibling("respaldos");
    }

    /** Archivo .db en uso (ruta absoluta) */
    public Path archivoBd() {
        return rutaDeUrl(urlBd);
    }

    /** Carpeta "datos" junto a la BD (logotipo); se crea al usarla */
    public Path carpetaDatos() {
        if (carpetaDatosConfigurada != null && !carpetaDatosConfigurada.isBlank()) {
            return Path.of(carpetaDatosConfigurada).toAbsolutePath();
        }
        return archivoBd().resolveSibling("datos");
    }

    /** Respaldo en espera de aplicarse al reiniciar */
    public Path archivoRestauracionPendiente() {
        return hermano(archivoBd(), SUFIJO_RESTAURAR);
    }

    /** datos.db + sufijo, en la misma carpeta (copias de seguridad, pendiente) */
    public static Path hermano(Path bd, String sufijo) {
        return bd.resolveSibling(bd.getFileName() + sufijo);
    }

    /** jdbc:sqlite:datos.db → ruta absoluta; falla si la BD no es un archivo */
    public static Path rutaDeUrl(String url) {
        String prefijo = "jdbc:sqlite:";
        if (url == null || !url.startsWith(prefijo)) {
            throw new IllegalStateException("La base de datos no es un archivo SQLite: " + url);
        }
        String ruta = url.substring(prefijo.length());
        int q = ruta.indexOf('?');
        if (q >= 0) ruta = ruta.substring(0, q);
        if (ruta.isBlank() || ruta.contains(":memory:")) {
            throw new IllegalStateException("La base de datos está en memoria; no hay archivo.");
        }
        return Path.of(ruta).toAbsolutePath();
    }

    private static boolean tieneCabeceraSqlite(Path archivo) {
        byte[] esperado = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try (java.io.InputStream in = Files.newInputStream(archivo)) {
            return java.util.Arrays.equals(in.readNBytes(esperado.length), esperado);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Si hay un respaldo pendiente (datos.db.restaurar) lo pone como base de
     * datos. Se llama desde Launcher ANTES de iniciar Spring.
     * @return true si se restauró
     */
    public static boolean aplicarRestauracionPendiente(Path bd) {
        Path pendiente = hermano(bd, SUFIJO_RESTAURAR);
        if (!Files.isRegularFile(pendiente)) return false;
        // Revisión mínima al aplicar (el archivo pudo cambiar desde que BaseDatosService lo validó):
        // si ya no es una base SQLite se aparta y se conserva la base actual.
        if (!tieneCabeceraSqlite(pendiente)) {
            try {
                Files.move(pendiente, hermano(bd, SUFIJO_RESTAURAR + ".rechazado"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("No se pudo apartar el respaldo pendiente inválido: " + e.getMessage());
            }
            System.err.println("El respaldo pendiente no es una base SQLite válida; se conserva la base actual.");
            return false;
        }
        try {
            // Un -wal o -journal viejo no debe mezclarse con la base restaurada
            for (String sufijo : new String[] {"-wal", "-shm", "-journal"}) {
                Files.deleteIfExists(hermano(bd, sufijo));
            }
            Files.move(pendiente, bd, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("ℹ️  Respaldo restaurado como base de datos: " + bd);
            return true;
        } catch (IOException e) {
            System.err.println("No se pudo aplicar el respaldo pendiente (" + pendiente + "): " + e.getMessage());
            return false;
        }
    }
}
