package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Abrir Caja Libre al encender la computadora (al iniciar sesión el usuario de Windows/macOS/Linux).
 * Viene ENCENDIDO de fábrica en el programa instalado; se apaga en Configuración → "Abrir al
 * encender". La decisión es de cada computadora (archivo {@code inicio.properties} en la carpeta de
 * la aplicación), no de la BD: con varias cajas cada PC decide.
 * <ul>
 *   <li><b>Windows</b>: valor "CajaLibre" en {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}
 *       (con {@code reg.exe}, sin shell; no pide permisos de administrador).</li>
 *   <li><b>macOS</b>: {@code ~/Library/LaunchAgents/me.eliassanchezfernandez.puntodeventa.plist} con
 *       {@code open -a <app>} y RunAtLoad (aparece en Ajustes → General → Ítems de inicio).</li>
 *   <li><b>Linux</b>: {@code ~/.config/autostart/caja-libre.desktop} (estándar XDG).</li>
 * </ul>
 * Solo actúa con el programa instalado ({@code jpackage.app-path}); en {@code gradlew run} no hace
 * nada. En cada arranque se vuelve a escribir la entrada si está encendido, por si el programa se
 * reinstaló en otra carpeta.
 */
public final class InicioAutomatico {

    public static final String ARCHIVO = "inicio.properties";
    static final String CLAVE = "abrir.al.encender";
    public static final String CLAVE_REGISTRO = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    public static final String VALOR_REGISTRO = "CajaLibre";
    public static final String ETIQUETA_MAC = "me.eliassanchezfernandez.puntodeventa";
    public static final String ARCHIVO_LINUX = "caja-libre.desktop";

    public enum Sistema { WINDOWS, MAC, LINUX, OTRO;
        public static Sistema de(String osName) {
            String s = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            if (s.contains("win")) return WINDOWS;
            if (s.contains("mac") || s.contains("darwin")) return MAC;
            if (s.contains("linux") || s.contains("nux") || s.contains("bsd")) return LINUX;
            return OTRO;
        }
    }

    private InicioAutomatico() { }

    // ── Preferencia de esta computadora ─────────────────────────────────────

    /** true / false si alguien ya decidió; vacío si nunca (= encender de fábrica) */
    public static Optional<Boolean> decidido(Path carpeta) {
        Path archivo = carpeta.resolve(ARCHIVO);
        if (!Files.isRegularFile(archivo)) return Optional.empty();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(archivo)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        String v = p.getProperty(CLAVE);
        return v == null ? Optional.empty() : Optional.of(Boolean.parseBoolean(v.trim()));
    }

    public static boolean guardarDecision(Path carpeta, boolean encendido) {
        try {
            Files.createDirectories(carpeta);
            Properties p = new Properties();
            p.setProperty(CLAVE, String.valueOf(encendido));
            Path tmp = carpeta.resolve(ARCHIVO + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "Abrir Caja Libre al encender esta computadora");
            }
            Files.move(tmp, carpeta.resolve(ARCHIVO), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("No se pudo guardar 'abrir al encender': " + e.getMessage());
            return false;
        }
    }

    // ── Rutas ────────────────────────────────────────────────────────────────

    /** El lanzador del programa instalado (jpackage), si lo hay */
    public static Optional<Path> lanzador() {
        String p = System.getProperty("jpackage.app-path");
        return p == null || p.isBlank() ? Optional.empty() : Optional.of(Path.of(p));
    }

    /** En macOS, la carpeta .app que contiene al lanzador (…/X.app/Contents/MacOS/X) */
    static Optional<Path> paqueteMac(Path lanzador) {
        for (Path p = lanzador; p != null; p = p.getParent()) {
            Path nombre = p.getFileName();
            if (nombre != null && nombre.toString().endsWith(".app")) return Optional.of(p);
        }
        return Optional.empty();
    }

    static Path archivoMac(Path casa) {
        return casa.resolve("Library").resolve("LaunchAgents").resolve(ETIQUETA_MAC + ".plist");
    }

    static Path archivoLinux(Path casa, String xdgConfig) {
        Path base = xdgConfig != null && !xdgConfig.isBlank() ? Path.of(xdgConfig) : casa.resolve(".config");
        return base.resolve("autostart").resolve(ARCHIVO_LINUX);
    }

    // ── Contenido de las entradas ────────────────────────────────────────────

    static String plistMac(Path app) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n<dict>\n"
                + "  <key>Label</key>\n  <string>" + ETIQUETA_MAC + "</string>\n"
                + "  <key>ProgramArguments</key>\n  <array>\n"
                + "    <string>/usr/bin/open</string>\n    <string>-a</string>\n"
                + "    <string>" + xml(app.toString()) + "</string>\n  </array>\n"
                + "  <key>RunAtLoad</key>\n  <true/>\n"
                + "</dict>\n</plist>\n";
    }

    static String escritorioLinux(Path lanzador) {
        String exec = lanzador.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=" + Marca.NOMBRE + "\n"
                + "Comment=Abrir " + Marca.NOMBRE + " al encender la computadora\n"
                + "Exec=\"" + exec + "\"\n"
                + "Terminal=false\n"
                + "X-GNOME-Autostart-enabled=true\n";
    }

    /**
     * reg.exe add …: el valor debe guardar la ruta ENTRE comillas (el usuario puede llamarse
     * "Juan Perez"). Las comillas van escapadas ({@code \"}): Java pone comillas alrededor del
     * argumento si tiene espacios y reg.exe convierte {@code \"} en una comilla literal.
     */
    static List<String> comandoRegistrar(Path lanzador) {
        return List.of("reg", "add", CLAVE_REGISTRO, "/v", VALOR_REGISTRO, "/t", "REG_SZ",
                "/d", "\\\"" + lanzador + "\\\"", "/f");
    }

    static List<String> comandoQuitar() {
        return List.of("reg", "delete", CLAVE_REGISTRO, "/v", VALOR_REGISTRO, "/f");
    }

    static List<String> comandoConsultar() {
        return List.of("reg", "query", CLAVE_REGISTRO, "/v", VALOR_REGISTRO);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Aplicar ──────────────────────────────────────────────────────────────

    /** Contexto: sistema, carpeta del usuario, XDG_CONFIG_HOME (Linux) */
    public record Entorno(Sistema sistema, Path casa, String xdgConfig) {
        public static Entorno actual() {
            return new Entorno(Sistema.de(System.getProperty("os.name")), Path.of(System.getProperty("user.home")),
                    System.getenv("XDG_CONFIG_HOME"));
        }
    }

    /**
     * Deja la entrada de inicio como diga {@code encendido}. Nunca lanza.
     * @return null si salió bien, o el motivo en palabras llanas
     */
    public static String aplicar(boolean encendido, Path lanzador, Entorno e) {
        try {
            switch (e.sistema()) {
                case WINDOWS -> {
                    if (encendido) {
                        return correr(comandoRegistrar(lanzador)) ? null : "Windows no dejó registrar el inicio automático.";
                    }
                    // quitar un valor que no existe "falla": no importa
                    if (correr(comandoConsultar())) correr(comandoQuitar());
                    return null;
                }
                case MAC -> {
                    Path plist = archivoMac(e.casa());
                    if (!encendido) {
                        Files.deleteIfExists(plist);
                        return null;
                    }
                    Optional<Path> app = paqueteMac(lanzador);
                    if (app.isEmpty()) return "No se encontró la aplicación (.app).";
                    escribir(plist, plistMac(app.get()));
                    return null;
                }
                case LINUX -> {
                    Path desktop = archivoLinux(e.casa(), e.xdgConfig());
                    if (!encendido) {
                        Files.deleteIfExists(desktop);
                        return null;
                    }
                    escribir(desktop, escritorioLinux(lanzador));
                    return null;
                }
                default -> {
                    return "Este sistema no permite abrir el programa al encender.";
                }
            }
        } catch (IOException | RuntimeException ex) {
            return "No se pudo cambiar el inicio automático: " + ex.getMessage();
        }
    }

    /** ¿Está registrada hoy la entrada de inicio? */
    public static boolean registrado(Entorno e) {
        return switch (e.sistema()) {
            case WINDOWS -> correr(comandoConsultar());
            case MAC -> Files.isRegularFile(archivoMac(e.casa()));
            case LINUX -> Files.isRegularFile(archivoLinux(e.casa(), e.xdgConfig()));
            default -> false;
        };
    }

    /**
     * Al arrancar el programa instalado: si nadie ha decidido, se enciende (y se anota); si está
     * encendido, se reescribe la entrada con la ruta actual; si se apagó, no se toca nada.
     */
    public static void alArrancar(Path carpeta) {
        Optional<Path> l = lanzador();
        if (l.isEmpty()) return;
        Optional<Boolean> d = decidido(carpeta);
        if (d.isPresent() && !d.get()) return;
        String error = aplicar(true, l.get(), Entorno.actual());
        if (error == null && d.isEmpty()) guardarDecision(carpeta, true);
        if (error != null) System.err.println("Inicio automático: " + error);
    }

    private static void escribir(Path destino, String contenido) throws IOException {
        Files.createDirectories(destino.getParent());
        Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
        Files.writeString(tmp, contenido, StandardCharsets.UTF_8);
        Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean correr(List<String> comando) {
        try {
            Process p = new ProcessBuilder(comando).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
