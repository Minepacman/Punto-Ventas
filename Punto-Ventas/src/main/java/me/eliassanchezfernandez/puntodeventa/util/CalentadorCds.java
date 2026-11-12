package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Archivo CDS de la aplicación (AppCDS): la JVM arranca un tercio más rápido
 * si las ~13 000 clases de Spring, Hibernate y JavaFX vienen ya "cocinadas"
 * en un archivo mapeado en memoria en vez de leerse y verificarse desde 49
 * jars (medido: −1.1 s de 3.3 s en un equipo rápido; en disco duro, más).
 *
 * El instalador arranca la JVM con {@code -XX:SharedArchiveFile=$APPDIR/pos.jsa}
 * (ver jvmOpciones en build.gradle). Si el archivo no existe o quedó viejo
 * (jars distintos), la JVM lo ignora en silencio ({@code -Xshare:auto}), así
 * que nunca impide arrancar. Este programa lo genera él mismo, en la carpeta
 * del instalador, un rato después del primer arranque de cada versión:
 *
 *  1. un proceso hijo (el propio lanzador del instalador, con las opciones en
 *     JAVA_TOOL_OPTIONS: el runtime de jpackage no trae bin/java) arranca
 *     Spring contra una base temporal y calienta JavaFX sin mostrar ventanas,
 *     con {@code -XX:DumpLoadedClassList} (lista de clases usadas);
 *  2. otro proceso hijo hace {@code -Xshare:dump} con esa lista y el MISMO
 *     classpath directo a pos.jsa.
 *
 * El archivo es estático (lleva también las clases del JDK), por lo que no
 * depende de que el runtime de jpackage traiga el classes.jsa del JDK
 * (jlink de Java 17 no lo incluye). Un JDK sin soporte de CDS (algunos
 * de 32 bits) solo hace que el paso 2 falle: se anota el intento y no se
 * vuelve a probar en esa versión.
 *
 * Solo corre con el programa instalado ({@code jpackage.app-path}) y con la
 * carpeta "app" escribible (instalación por usuario en Windows).
 * {@code -Dapp.cds=false} lo apaga; {@code -Dapp.cds.forzar=true} lo permite
 * fuera del instalador (pruebas manuales).
 */
public final class CalentadorCds {

    /** El proceso hijo del paso 1 arranca con esta propiedad (ver Launcher) */
    public static final String PROP_CALENTAR = "app.cds.calentar";
    public static final String PROP_ACTIVO = "app.cds";
    public static final String PROP_FORZAR = "app.cds.forzar";

    public static final String ARCHIVO = "pos.jsa";
    static final String MARCA = "pos.jsa.version";
    static final int INTENTOS_MAXIMOS = 2;
    static final long TIEMPO_MAXIMO_MIN = 6;

    private CalentadorCds() { }

    // ── ¿Toca generarlo? ──────────────────────────────────────────────────

    /** ¿Es este el proceso hijo que calienta las clases? */
    public static boolean esProcesoCalentador() {
        return Boolean.getBoolean(PROP_CALENTAR);
    }

    /** ¿Está permitido generar el archivo desde este proceso? */
    public static boolean aplica() {
        if ("false".equalsIgnoreCase(System.getProperty(PROP_ACTIVO))) return false;
        if (esProcesoCalentador()) return false;
        if (Boolean.getBoolean(PROP_FORZAR)) return true;
        // En macOS la carpeta "app" vive DENTRO del paquete .app firmado: escribir ahí rompe el sello de
        // la firma y Gatekeeper puede negarse a abrirlo ("está dañado"). Solo Windows/Linux.
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return false;
        return System.getProperty("jpackage.app-path") != null;
    }

    /** Carpeta donde viven los jars (la "app" del instalador): el padre del primer jar del classpath */
    public static Optional<Path> carpetaApp() {
        return carpetaApp(System.getProperty("java.class.path"));
    }

    static Optional<Path> carpetaApp(String classpath) {
        if (classpath == null || classpath.isBlank()) return Optional.empty();
        String primero = classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))[0];
        try {
            Path p = Path.of(primero).toAbsolutePath();
            Path carpeta = Files.isDirectory(p) ? p : p.getParent();
            return Optional.ofNullable(carpeta);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Qué dice la marca: versión con la que se generó (o se intentó) y cuántos intentos lleva */
    record Marca(String version, int intentos) {
        static Marca leer(Path archivo) {
            if (!Files.isRegularFile(archivo)) return new Marca("", 0);
            Properties p = new Properties();
            try (var in = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | RuntimeException e) {
                return new Marca("", 0);
            }
            int n;
            try {
                n = Integer.parseInt(p.getProperty("intentos", "0").trim());
            } catch (NumberFormatException e) {
                n = 0;
            }
            return new Marca(p.getProperty("version", "").trim(), n);
        }

        void escribir(Path archivo) throws IOException {
            Files.writeString(archivo, "version=" + version + "\nintentos=" + intentos + "\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * ¿Hay que (re)generar pos.jsa en esa carpeta para esta versión? Sí cuando no existe o la marca es
     * de otra versión, y no se agotaron los intentos de esta versión.
     */
    static boolean necesitaGenerar(Path carpeta, String version) {
        Marca m = Marca.leer(carpeta.resolve(MARCA));
        boolean mismaVersion = version.equals(m.version());
        if (mismaVersion && Files.isRegularFile(carpeta.resolve(ARCHIVO))) return false;
        return !mismaVersion || m.intentos() < INTENTOS_MAXIMOS;
    }

    // ── Generación ────────────────────────────────────────────────────────

    /**
     * Programa la generación en un hilo de baja prioridad, tras {@code demoraMs}
     * (para no competir con el inicio de sesión ni las primeras ventas).
     */
    public static void programar(long demoraMs) {
        if (!aplica()) return;
        Optional<Path> carpeta = carpetaApp();
        if (carpeta.isEmpty() || !Files.isWritable(carpeta.get())) return;
        String version = VersionApp.version();
        if (version == null || VersionApp.DESCONOCIDA.equals(version)) return;
        if (!necesitaGenerar(carpeta.get(), version)) return;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(demoraMs);
                boolean ok = generar(carpeta.get(), version);
                System.out.println(ok ? "ℹ️  Archivo de arranque rápido (CDS) generado en " + carpeta.get().resolve(ARCHIVO)
                                      : "ℹ️  No se pudo generar el archivo de arranque rápido (CDS); se seguirá arrancando sin él.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                System.err.println("CDS: " + e.getMessage());
            }
        }, "calentador-cds");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Genera pos.jsa en la carpeta (sincrónico). @return true si quedó el archivo */
    static synchronized boolean generar(Path carpeta, String version) {
        Marca anterior = Marca.leer(carpeta.resolve(MARCA));
        int intentos = version.equals(anterior.version()) ? anterior.intentos() + 1 : 1;
        try {
            new Marca(version, intentos).escribir(carpeta.resolve(MARCA));
        } catch (IOException e) {
            return false;       // carpeta no escribible: tampoco podríamos dejar el archivo
        }
        Path lista = carpeta.resolve("pos.lst.tmp");
        Path temporal = carpeta.resolve("pos.jsa.tmp");
        Path destino = carpeta.resolve(ARCHIVO);
        try {
            Files.deleteIfExists(lista);
            Files.deleteIfExists(temporal);
            // Paso 1: lista de clases que usa el programa (Spring + Hibernate + JavaFX)
            Invocacion paso1 = invocacion(List.of("-Xshare:off",
                    "-XX:DumpLoadedClassList=" + lista, "-D" + PROP_CALENTAR + "=true"), true);
            correr(paso1);
            if (!Files.isRegularFile(lista) || Files.size(lista) < 50_000) return false;
            // Paso 2: volcar el archivo con el MISMO classpath (la JVM lo comprueba al usarlo). Con el lanzador
            // de jpackage, su propio -XX:SharedArchiveFile=$APPDIR/pos.jsa va después y manda: el volcado cae
            // directo en pos.jsa (la JVM solo lo usa si está completo y coincide con los jars).
            long antes = System.currentTimeMillis();
            Invocacion paso2 = invocacion(List.of("-Xshare:dump",
                    "-XX:SharedClassListFile=" + lista, "-XX:SharedArchiveFile=" + temporal), false);
            int salida = correr(paso2);
            if (Files.isRegularFile(temporal) && Files.size(temporal) >= 1_000_000) {
                Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;
            }
            return salida == 0 && Files.isRegularFile(destino) && Files.size(destino) >= 1_000_000
                    && Files.getLastModifiedTime(destino).toMillis() >= antes - 1_000;
        } catch (IOException | RuntimeException e) {
            System.err.println("CDS: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            try { Files.deleteIfExists(lista); } catch (IOException ignorada) { /* temporal */ }
            try { Files.deleteIfExists(temporal); } catch (IOException ignorada) { /* temporal */ }
        }
    }

    /** Un proceso hijo: comando y variables de entorno extra */
    record Invocacion(List<String> comando, java.util.Map<String, String> entorno) { }

    /**
     * Cómo lanzar la JVM hija. Con el programa instalado el runtime de jpackage NO trae bin/java
     * (jlink quita los lanzadores), así que se vuelve a ejecutar el propio lanzador del instalador
     * ({@code jpackage.app-path}) y las opciones viajan en JAVA_TOOL_OPTIONS (la JVM las lee antes que
     * las del .cfg). Fuera del instalador (pruebas manuales con -Dapp.cds.forzar) se usa java.home/bin/java
     * con el classpath actual.
     * @param conPrograma si debe correr el Launcher (paso 1) o solo la JVM (paso 2: -Xshare:dump sale sola)
     */
    static Invocacion invocacion(List<String> opcionesJvm, boolean conPrograma) {
        String lanzador = System.getProperty("jpackage.app-path");
        List<String> comando = new ArrayList<>();
        java.util.Map<String, String> entorno = new java.util.HashMap<>();
        if (lanzador != null && Files.isRegularFile(Path.of(lanzador))) {
            comando.add(lanzador);
            StringBuilder sb = new StringBuilder();
            for (String o : opcionesJvm) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(o.indexOf(' ') >= 0 ? "\"" + o + "\"" : o);
            }
            entorno.put("JAVA_TOOL_OPTIONS", sb.toString());
            return new Invocacion(comando, entorno);
        }
        comando.addAll(comandoJava());
        comando.addAll(opcionesJvm);
        comando.add("-cp");
        comando.add(System.getProperty("java.class.path"));
        if (conPrograma) comando.add("me.eliassanchezfernandez.puntodeventa.Launcher");
        return new Invocacion(comando, entorno);
    }

    /** java del runtime en uso + opciones iguales a las del instalador (sin el archivo CDS) */
    static List<String> comandoJava() {
        List<String> c = new ArrayList<>();
        c.add(javaEjecutable().toString());
        c.add("-Xms64m");
        c.add("-Xmx384m");
        c.add("-XX:+UseSerialGC");
        c.add("-XX:TieredStopAtLevel=1");
        c.add("-XX:MaxMetaspaceSize=128m");
        c.add("-Dfile.encoding=UTF-8");
        c.add("-Djava.awt.headless=true");
        return c;
    }

    static Path javaEjecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    private static int correr(Invocacion inv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(inv.comando())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().putAll(inv.entorno());
        Process p = pb.start();
        if (!p.waitFor(TIEMPO_MAXIMO_MIN, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("el proceso tardó más de " + TIEMPO_MAXIMO_MIN + " min");
        }
        return p.exitValue();
    }
}
