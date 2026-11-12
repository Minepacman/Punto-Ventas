package me.eliassanchezfernandez.puntodeventa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.util.RevocacionesApoyo;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import me.eliassanchezfernandez.puntodeventa.util.VersionSemantica;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Busca versiones nuevas en las "releases" públicas del repositorio de GitHub
 * (sin servidor propio ni cuenta). Nunca descarga ni ejecuta instaladores:
 * solo informa y abre la página de descarga en el navegador.
 *
 * Reglas de seguridad (son las únicas salidas a internet de esta clase):
 *  - Se consulta SOLO {@link #API_RELEASES} y {@link #URL_REVOCACIONES}; sin
 *    seguir redirecciones; tiempo de espera corto; respuesta acotada en bytes.
 *  - Los enlaces que se abren en el navegador deben empezar con
 *    {@link #PREFIJO_ENLACES} (el repositorio): cualquier otro se descarta aunque
 *    venga en la respuesta de GitHub ({@link #enlacePermitido}).
 *  - Las notas de la versión se muestran como texto plano recortado (nunca HTML).
 *  - Versión actual: {@link VersionApp}; se comparan versiones semánticas.
 *  - Los donadores vigentes con bandera "beta" ven también los prelanzamientos.
 *  - La misma consulta diaria baja la lista de revocación de claves de donador
 *    (firmada; DonadorService decide si la acepta). Sin internet: nada cambia.
 *
 * El análisis del JSON es estático y puro para probarlo sin red.
 */
@Service
public class ActualizacionService {

    public static final String REPOSITORIO = "https://github.com/Minepacman/Punto-Ventas";
    /** Solo se abren en el navegador enlaces del propio repositorio */
    public static final String PREFIJO_ENLACES = REPOSITORIO + "/";
    public static final String API_RELEASES = "https://api.github.com/repos/Minepacman/Punto-Ventas/releases?per_page=10";
    public static final String PAGINA_RELEASES = REPOSITORIO + "/releases";
    public static final String URL_REVOCACIONES = "https://raw.githubusercontent.com/Minepacman/Punto-Ventas/main/claves/revocadas.pdvr";
    public static final Duration TIEMPO_ESPERA = Duration.ofSeconds(6);
    /** Tope de la respuesta de GitHub (10 releases con notas largas caben de sobra) */
    public static final int RESPUESTA_MAXIMA = 1024 * 1024;
    /** Las notas de la versión se recortan para la ventana */
    public static final int NOTAS_MAXIMO = 1500;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private DonadorService donadorService;

    /** Se crea al primer uso: construirlo inicializa SSL (decenas de ms en una PC vieja) y no hace falta al arrancar */
    private volatile HttpClient http;

    private HttpClient http() {
        HttpClient h = http;
        if (h == null) {
            synchronized (this) {
                h = http;
                if (h == null) {
                    h = HttpClient.newBuilder()
                            .connectTimeout(TIEMPO_ESPERA)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    http = h;
                }
            }
        }
        return h;
    }

    /** Una versión publicada más nueva que la instalada (todas sus URLs ya pasaron {@link #enlacePermitido}) */
    public record Actualizacion(VersionSemantica version, String etiqueta, String nombre, String notas,
                                String urlPagina, Optional<String> urlDescarga, boolean prelanzamiento) {
        /** Lo que se abre en el navegador: el instalador del sistema si lo hay, si no la página de la versión */
        public String urlParaAbrir() {
            return urlDescarga.orElse(urlPagina);
        }
    }

    public enum Estado { ACTUALIZADO, DISPONIBLE, SIN_CONEXION, VERSION_DESCONOCIDA }

    public record Resultado(Estado estado, Optional<Actualizacion> actualizacion, String detalle) {
        public static Resultado actualizado() { return new Resultado(Estado.ACTUALIZADO, Optional.empty(), ""); }
        public static Resultado disponible(Actualizacion a) { return new Resultado(Estado.DISPONIBLE, Optional.of(a), ""); }
        public static Resultado sinConexion(String motivo) { return new Resultado(Estado.SIN_CONEXION, Optional.empty(), motivo); }
        public static Resultado versionDesconocida() { return new Resultado(Estado.VERSION_DESCONOCIDA, Optional.empty(), ""); }
    }

    // ── Consulta ──────────────────────────────────────────────────────────

    /** Consulta GitHub (bloquea unos segundos a lo más): llamar fuera del hilo de JavaFX */
    public Resultado buscar() {
        Optional<VersionSemantica> actual = VersionSemantica.parsear(VersionApp.version());
        if (actual.isEmpty()) return Resultado.versionDesconocida();
        boolean prelanzamientos = donadorService.permiteBeta();
        String json;
        try {
            json = descargar(API_RELEASES, RESPUESTA_MAXIMA, "application/vnd.github+json");
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Resultado.sinConexion(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        // Hubo internet: aprovechar para refrescar la lista de revocación (si falla, no pasa nada)
        actualizarRevocaciones();
        try {
            return analizar(json, actual.get(), prelanzamientos, System.getProperty("os.name"), System.getProperty("os.arch"))
                    .map(Resultado::disponible).orElse(Resultado.actualizado());
        } catch (RuntimeException respuestaRara) {
            return Resultado.sinConexion("Respuesta inesperada de GitHub: " + respuestaRara.getMessage());
        }
    }

    /** Lo mismo en un hilo propio (demonio): el que llama decide cómo mostrarlo */
    public CompletableFuture<Resultado> buscarEnSegundoPlano() {
        CompletableFuture<Resultado> futuro = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                futuro.complete(buscar());
            } catch (Throwable e) {
                futuro.complete(Resultado.sinConexion(String.valueOf(e.getMessage())));
            }
        }, "buscar-actualizaciones");
        t.setDaemon(true);
        t.start();
        return futuro;
    }

    /** Baja la lista de revocación firmada y se la pasa a DonadorService. Silencioso: fail-open. */
    void actualizarRevocaciones() {
        try {
            String texto = descargar(URL_REVOCACIONES, RevocacionesApoyo.TAMANO_MAXIMO, "text/plain");
            donadorService.actualizarRevocaciones(texto);
        } catch (IOException | RuntimeException e) {
            // sin lista (404 mientras no exista, sin internet, firma mala): se conserva la copia local
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String descargar(String url, int maximoBytes, String accept) throws IOException, InterruptedException {
        if (!url.startsWith("https://")) throw new IOException("Solo se consulta por HTTPS.");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIEMPO_ESPERA)
                .header("Accept", accept)
                .header("User-Agent", VersionApp.agenteUsuario())
                .GET().build();
        HttpResponse<InputStream> resp = http().send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream cuerpo = resp.body()) {
            if (resp.statusCode() != 200) throw new IOException("GitHub respondió " + resp.statusCode());
            byte[] bytes = cuerpo.readNBytes(maximoBytes + 1);
            if (bytes.length > maximoBytes) throw new IOException("La respuesta es demasiado grande.");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    // ── Análisis puro ─────────────────────────────────────────────────────

    /** true solo para enlaces HTTPS dentro del repositorio (página de la versión o archivo de la release) */
    public static boolean enlacePermitido(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (!u.startsWith(PREFIJO_ENLACES)) return false;
        // Sin trucos de "https://github.com/Minepacman/Punto-Ventas/../otro" ni credenciales en la URL
        return !u.contains("..") && !u.contains("@") && !u.contains("\\") && u.indexOf('#') < 0 && !u.contains("%2e");
    }

    /**
     * La versión publicada más alta que supere a la actual (sin borradores; prelanzamientos
     * solo si se piden), con el instalador para ese sistema si existe. Las URLs que no sean
     * del repositorio se ignoran (la página cae a {@link #PAGINA_RELEASES}, el instalador a ninguno).
     */
    public static Optional<Actualizacion> analizar(String json, VersionSemantica actual, boolean incluirPrelanzamientos,
                                                   String osName, String osArch) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON inválido", e);
        }
        if (raiz == null || !raiz.isArray()) return Optional.empty();
        Actualizacion mejor = null;
        for (JsonNode r : raiz) {
            if (r.path("draft").asBoolean(false)) continue;
            boolean pre = r.path("prerelease").asBoolean(false);
            Optional<VersionSemantica> v = VersionSemantica.parsear(r.path("tag_name").asText(""));
            if (v.isEmpty()) continue;
            // Una etiqueta "1.1.0-beta.1" es prelanzamiento aunque no se haya marcado en GitHub
            pre = pre || v.get().esPrelanzamiento();
            if (pre && !incluirPrelanzamientos) continue;
            if (!v.get().esMayorQue(actual)) continue;
            if (mejor != null && !v.get().esMayorQue(mejor.version())) continue;

            List<String[]> activos = new ArrayList<>();
            for (JsonNode a : r.path("assets")) {
                String urlActivo = a.path("browser_download_url").asText("");
                if (!enlacePermitido(urlActivo)) continue;
                activos.add(new String[] {a.path("name").asText(""), urlActivo});
            }
            Optional<String> descarga = elegirActivo(activos.stream().map(x -> x[0]).toList(), osName, osArch)
                    .flatMap(nombre -> activos.stream().filter(x -> x[0].equals(nombre)).map(x -> x[1]).findFirst());
            String pagina = r.path("html_url").asText("");
            if (!enlacePermitido(pagina)) pagina = PAGINA_RELEASES;
            String nombre = r.path("name").asText("");
            if (nombre.isBlank()) nombre = r.path("tag_name").asText("");
            mejor = new Actualizacion(v.get(), r.path("tag_name").asText(""), nombre,
                    recortar(r.path("body").asText("")), pagina, descarga, pre);
        }
        return Optional.ofNullable(mejor);
    }

    /** Gestores de paquetes de Linux que decide el formato a ofrecer ({@link #elegirActivo}) */
    public enum GestorPaquetes { DPKG, RPM }

    /** Los gestores presentes en este equipo (Debian/Ubuntu tienen dpkg; Fedora/openSUSE, rpm; Ubuntu puede tener los dos) */
    public static Set<GestorPaquetes> gestoresInstalados() {
        Set<GestorPaquetes> g = EnumSet.noneOf(GestorPaquetes.class);
        if (Files.isExecutable(Path.of("/usr/bin/dpkg")) || Files.isExecutable(Path.of("/bin/dpkg"))) g.add(GestorPaquetes.DPKG);
        if (Files.isExecutable(Path.of("/usr/bin/rpm")) || Files.isExecutable(Path.of("/bin/rpm"))) g.add(GestorPaquetes.RPM);
        return g;
    }

    /** {@link #elegirActivo(List, String, String, Set)} con los gestores de paquetes detectados en este equipo (solo cuentan en Linux) */
    public static Optional<String> elegirActivo(List<String> nombres, String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        Set<GestorPaquetes> gestores = os.contains("linux") ? gestoresInstalados() : EnumSet.noneOf(GestorPaquetes.class);
        return elegirActivo(nombres, osName, osArch, gestores);
    }

    /**
     * Nombre del instalador que corresponde al sistema:
     *  - macOS: .dmg; si hay varios, el de la arquitectura (arm64/aarch64 o x64/intel);
     *  - Windows: .exe (o .msi); con JVM de 32 bits exige "x86"; de 64 bits prefiere "x64" y nunca toma uno "x86";
     *  - Linux: .deb si hay dpkg, si no .rpm si hay rpm, si no el .tar.gz portable; siempre de la arquitectura
     *    de la JVM (x64/amd64 o arm64/aarch64);
     *  - otros sistemas: ninguno (se abre la página).
     */
    public static Optional<String> elegirActivo(List<String> nombres, String osName, String osArch, Set<GestorPaquetes> gestores) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            boolean arm = arch.contains("aarch64") || arch.contains("arm");
            List<String> formatos = new ArrayList<>();
            if (gestores.contains(GestorPaquetes.DPKG)) formatos.add(".deb");
            if (gestores.contains(GestorPaquetes.RPM)) formatos.add(".rpm");
            formatos.add(".tar.gz");
            for (String formato : formatos) {
                List<String> candidatos = nombres.stream().filter(n -> n.toLowerCase(Locale.ROOT).endsWith(formato)).toList();
                Optional<String> exacto = candidatos.stream().filter(n -> arm ? esArm64(n) : esX64(n)).findFirst();
                if (exacto.isPresent()) return exacto;
                // Sin arquitectura en el nombre y es el único de ese formato: se ofrece
                if (candidatos.size() == 1 && !esArm64(candidatos.get(0)) && !esX64(candidatos.get(0))) return Optional.of(candidatos.get(0));
            }
            return Optional.empty();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            List<String> dmg = nombres.stream().filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".dmg")).toList();
            boolean arm = arch.contains("aarch64") || arch.contains("arm");
            Optional<String> exacto = dmg.stream().filter(n -> {
                String m = n.toLowerCase(Locale.ROOT);
                boolean esArm = m.contains("arm64") || m.contains("aarch64") || m.contains("apple");
                boolean esIntel = m.contains("x64") || m.contains("x86_64") || m.contains("intel") || m.contains("amd64");
                return arm ? esArm : esIntel;
            }).findFirst();
            if (exacto.isPresent()) return exacto;
            // Un solo dmg sin arquitectura en el nombre: se ofrece (en un Mac arm64 corre también el de Intel vía Rosetta)
            return dmg.size() == 1 ? Optional.of(dmg.get(0)) : Optional.empty();
        }
        if (os.contains("win")) {
            boolean treintaYDos = arch.equals("x86") || arch.equals("i386") || arch.equals("i486")
                    || arch.equals("i586") || arch.equals("i686");
            List<String> exe = nombres.stream().filter(n -> {
                String m = n.toLowerCase(Locale.ROOT);
                return m.endsWith(".exe") || m.endsWith(".msi");
            }).toList();
            if (treintaYDos) {
                return exe.stream().filter(n -> esX86(n.toLowerCase(Locale.ROOT))).findFirst();
            }
            Optional<String> x64 = exe.stream().filter(n -> {
                String m = n.toLowerCase(Locale.ROOT);
                return m.contains("x64") || m.contains("x86_64") || m.contains("amd64") || m.contains("win64");
            }).findFirst();
            if (x64.isPresent()) return x64;
            return exe.stream().filter(n -> !esX86(n.toLowerCase(Locale.ROOT))).findFirst();
        }
        return Optional.empty();
    }

    private static boolean esArm64(String nombre) {
        String m = nombre.toLowerCase(Locale.ROOT);
        return m.contains("arm64") || m.contains("aarch64");
    }

    private static boolean esX64(String nombre) {
        String m = nombre.toLowerCase(Locale.ROOT);
        return m.contains("x64") || m.contains("x86_64") || m.contains("x86-64") || m.contains("amd64");
    }

    /** "x86" como arquitectura de 32 bits (no "x86_64") */
    private static boolean esX86(String m) {
        return (m.contains("x86") && !m.contains("x86_64") && !m.contains("x86-64"))
                || m.contains("win32") || m.contains("32bit") || m.contains("32-bit") || m.contains("i686");
    }

    /** Texto plano: se normalizan saltos, se quitan caracteres de control y se recorta */
    static String recortar(String notas) {
        if (notas == null) return "";
        String limpio = notas.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").trim();
        return limpio.length() <= NOTAS_MAXIMO ? limpio : limpio.substring(0, NOTAS_MAXIMO).trim() + "\n…";
    }
}
