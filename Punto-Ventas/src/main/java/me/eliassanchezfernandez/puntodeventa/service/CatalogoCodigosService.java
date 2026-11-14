package me.eliassanchezfernandez.puntodeventa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.CatalogoCodigo;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.repository.CatalogoCodigoRepository;
import me.eliassanchezfernandez.puntodeventa.util.CatalogoCsv;
import me.eliassanchezfernandez.puntodeventa.util.CodigoBarras;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Catálogo público de códigos de barras de productos de México
 * ({@code github.com/Minepacman/codigos-barras-mx}: Open Food Facts y hermanos,
 * ODbL; mostrar siempre {@link #ATRIBUCION} donde se usen sus datos).
 *
 * Dos caminos para una ficha ({@link #buscar}):
 *  1. la copia LOCAL (tabla {@code catalogo_codigos}, ~25 000 filas) que este
 *     servicio descarga completa y reemplaza cuando el {@code version.json}
 *     del repositorio trae otro sha256 ({@link #actualizar});
 *  2. si no está y el administrador no lo apagó ({@code Configuracion.codigosEnLinea}),
 *     un fragmento de la API estática ({@code api/v1/<3 últimos dígitos>.json},
 *     ~6-50 KB) desde jsDelivr o raw.githubusercontent; lo que se encuentra se
 *     guarda en la tabla local para la próxima vez.
 *
 * Seguridad de red (igual que ActualizacionService): solo https a los hosts
 * fijos de {@link #URL_VERSION}, {@link #URL_CSV_PRIMARIA}, {@link #URL_CSV_RESPALDO}
 * y {@link #URL_FRAGMENTO_PRIMARIA}/{@link #URL_FRAGMENTO_RESPALDO}; sin
 * redirecciones; tiempos de espera cortos; respuestas acotadas en bytes; el CSV
 * se acepta solo si su SHA-256 coincide con el anunciado en version.json. La
 * imagen del producto NO la baja este servicio: la URL se entrega al formulario,
 * que usa {@link ProductoImagenService#descargarDesdeUrl} (guardia anti-SSRF).
 *
 * Rendimiento (PCs viejas): nada corre al arrancar. La revisión semanal la
 * programa la ventana principal un par de minutos después de entrar
 * ({@link #programarRevisionSemanal}), en un hilo demonio de prioridad mínima;
 * la carga son INSERT por lotes de {@value #LOTE} en una sola transacción JDBC
 * (en SQLite ~1-2 s para 25 000 filas) y mientras tanto la tabla anterior
 * sigue sirviendo (DELETE + INSERT dentro de la misma transacción).
 *
 * Esquema: tabla nueva y columnas nuevas en {@code configuracion}; con el
 * programa instalado solo se crean cuando cambia la versión (config/VersionEsquema):
 * la versión debe subir al publicar. Mientras la tabla no exista, buscar y
 * actualizar fallan en silencio (el formulario sigue funcionando a mano).
 */
@Service
public class CatalogoCodigosService {

    public static final String REPOSITORIO = "https://github.com/Minepacman/codigos-barras-mx";
    public static final String ATRIBUCION = "Datos de Open Food Facts (ODbL)";
    public static final String URL_VERSION = "https://raw.githubusercontent.com/Minepacman/codigos-barras-mx/main/api/v1/version.json";
    public static final String URL_CSV_PRIMARIA = "https://raw.githubusercontent.com/Minepacman/codigos-barras-mx/main/datos/catalogo-mx.csv";
    public static final String URL_CSV_RESPALDO = "https://cdn.jsdelivr.net/gh/Minepacman/codigos-barras-mx@main/datos/catalogo-mx.csv";
    /** Se completa con el fragmento ("075") + ".json" */
    public static final String URL_FRAGMENTO_PRIMARIA = "https://cdn.jsdelivr.net/gh/Minepacman/codigos-barras-mx@main/api/v1/";
    public static final String URL_FRAGMENTO_RESPALDO = "https://raw.githubusercontent.com/Minepacman/codigos-barras-mx/main/api/v1/";

    public static final int VERSION_MAXIMO = 64 * 1024;
    /** El CSV pesa ~3.5 MB (comprimido ~0.7 MB); margen para que crezca */
    public static final int CSV_MAXIMO = 24 * 1024 * 1024;
    public static final int FRAGMENTO_MAXIMO = 512 * 1024;
    public static final Duration TIEMPO_CONEXION = Duration.ofSeconds(6);
    public static final Duration TIEMPO_CORTO = Duration.ofSeconds(8);
    /** 3.5 MB (0.7 comprimido) en una conexión lenta */
    public static final Duration TIEMPO_CSV = Duration.ofSeconds(90);
    /** Se revisa si hay versión nueva a lo más cada 7 días */
    public static final Duration INTERVALO_REVISION = Duration.ofDays(7);
    static final int LOTE = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Origen { LOCAL, EN_LINEA }

    /** Ficha encontrada y de dónde salió */
    public record Ficha(CatalogoCodigo datos, Origen origen) { }

    public enum EstadoActualizacion { ACTUALIZADO, YA_AL_DIA, SIN_CONEXION, RECHAZADO }

    public record ResultadoActualizacion(EstadoActualizacion estado, String version, int productos, String detalle) {
        static ResultadoActualizacion actualizado(String version, int productos) {
            return new ResultadoActualizacion(EstadoActualizacion.ACTUALIZADO, version, productos, "");
        }
        static ResultadoActualizacion alDia(String version, int productos) {
            return new ResultadoActualizacion(EstadoActualizacion.YA_AL_DIA, version, productos, "");
        }
        static ResultadoActualizacion sinConexion(String detalle) {
            return new ResultadoActualizacion(EstadoActualizacion.SIN_CONEXION, null, 0, detalle);
        }
        static ResultadoActualizacion rechazado(String detalle) {
            return new ResultadoActualizacion(EstadoActualizacion.RECHAZADO, null, 0, detalle);
        }
    }

    /** Lo que se muestra en Configuración */
    public record Estado(boolean enLinea, String version, Integer productos, LocalDateTime descargado,
                         LocalDateTime revisado, long filasLocales) {
        public boolean hayCatalogo() { return version != null && filasLocales > 0; }
    }

    @Autowired private CatalogoCodigoRepository repo;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private DataSource dataSource;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private Clock reloj;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    /** Minutos de espera tras entrar antes de revisar el catálogo (pruebas: 0) */
    @Value("${app.catalogo.espera-ms:120000}")
    private long esperaRevisionMs;

    /** Pruebas: apuntan a un servidor local http://127.0.0.1 (en producción quedan los https fijos) */
    private volatile String urlVersion = URL_VERSION;
    private volatile String urlCsvPrimaria = URL_CSV_PRIMARIA;
    private volatile String urlCsvRespaldo = URL_CSV_RESPALDO;
    private volatile String urlFragmentoPrimaria = URL_FRAGMENTO_PRIMARIA;
    private volatile String urlFragmentoRespaldo = URL_FRAGMENTO_RESPALDO;
    private volatile boolean permitirHttpParaPruebas = false;

    /** Códigos consultados en línea sin resultado en esta sesión: no se vuelve a pedir el mismo fragmento */
    private final Map<String, Long> sinResultadoEnLinea = new ConcurrentHashMap<>();
    private static final long SIN_RESULTADO_VIGENCIA_MS = Duration.ofHours(6).toMillis();

    private volatile HttpClient http;
    private volatile Thread hiloRevision;

    private HttpClient http() {
        HttpClient h = http;
        if (h == null) {
            synchronized (this) {
                h = http;
                if (h == null) {
                    h = HttpClient.newBuilder()
                            .connectTimeout(TIEMPO_CONEXION)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    http = h;
                }
            }
        }
        return h;
    }

    // ── Consulta ──────────────────────────────────────────────────────────

    /**
     * Ficha del código tal como lo tecleó o escaneó el usuario: primero la tabla local y, si
     * no está y la consulta en línea está permitida, un fragmento de la API (bloquea unos
     * segundos a lo más: llamar fuera del hilo de JavaFX). Vacío si el código no es un GTIN
     * válido, no está en ninguna parte o no hay internet. Nunca lanza.
     */
    public Optional<Ficha> buscar(String codigoCapturado) {
        Optional<String> normalizado = CodigoBarras.normalizar(codigoCapturado);
        if (normalizado.isEmpty()) return Optional.empty();
        String codigo = normalizado.get();
        try {
            Optional<CatalogoCodigo> local = buscarLocal(codigo);
            if (local.isPresent()) return Optional.of(new Ficha(local.get(), Origen.LOCAL));
        } catch (RuntimeException e) {
            // Tabla aún inexistente (instalado sin subir la versión) o base ocupada: se sigue en línea
            System.err.println("Catálogo de códigos (local): " + e.getMessage());
        }
        if (!isEnLinea()) return Optional.empty();
        Long sin = sinResultadoEnLinea.get(codigo);
        if (sin != null && System.currentTimeMillis() - sin < SIN_RESULTADO_VIGENCIA_MS) return Optional.empty();
        Optional<CatalogoCodigo> remoto = buscarEnLinea(codigo);
        if (remoto.isEmpty()) {
            sinResultadoEnLinea.put(codigo, System.currentTimeMillis());
            return Optional.empty();
        }
        try {
            guardarEncontrado(remoto.get());
        } catch (RuntimeException e) {
            System.err.println("Catálogo de códigos: no se pudo guardar la ficha en línea: " + e.getMessage());
        }
        return Optional.of(new Ficha(remoto.get(), Origen.EN_LINEA));
    }

    /** Lo mismo en un hilo demonio propio; el que llama decide cómo mostrarlo */
    public CompletableFuture<Optional<Ficha>> buscarEnSegundoPlano(String codigoCapturado) {
        CompletableFuture<Optional<Ficha>> futuro = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                futuro.complete(buscar(codigoCapturado));
            } catch (Throwable e) {
                futuro.complete(Optional.empty());
            }
        }, "catalogo-codigos-buscar");
        t.setDaemon(true);
        t.start();
        return futuro;
    }

    @Transactional(readOnly = true)
    public Optional<CatalogoCodigo> buscarLocal(String codigoNormalizado) {
        return repo.findById(codigoNormalizado);
    }

    /** La ficha hallada en línea se guarda en la tabla local (REQUIRED: en SQLite una transacción nueva chocaría con la abierta) */
    @Transactional
    void guardarEncontrado(CatalogoCodigo ficha) {
        repo.save(ficha);
    }

    /** ¿Se permite salir a internet (fragmentos y catálogo completo)? null en la configuración = sí */
    public boolean isEnLinea() {
        try {
            return configuracionService.obtener().isCodigosEnLinea();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Pide el fragmento al CDN (y al respaldo si falla) y saca el código; vacío si no está o no hay red */
    Optional<CatalogoCodigo> buscarEnLinea(String codigo) {
        String nombre = CodigoBarras.fragmento(codigo) + ".json";
        for (String base : List.of(urlFragmentoPrimaria, urlFragmentoRespaldo)) {
            try {
                byte[] json = descargar(base + nombre, FRAGMENTO_MAXIMO, "application/json", TIEMPO_CORTO);
                return analizarFragmento(new String(json, StandardCharsets.UTF_8), codigo);
            } catch (IOException | RuntimeException e) {
                // siguiente host
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Análisis puro del fragmento {@code {"<codigo>": {nombre, marca, ...}, ...}} */
    public static Optional<CatalogoCodigo> analizarFragmento(String json, String codigo) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON inválido", e);
        }
        if (raiz == null || !raiz.isObject()) return Optional.empty();
        JsonNode n = raiz.get(codigo);
        if (n == null || !n.isObject()) return Optional.empty();
        // Se reutiliza el mismo recorte/validación del CSV: una sola fila con el mismo encabezado
        String csv = "codigo,nombre,marca,cantidad,unidad,contenido,categoria,imagen,fuente,actualizado\n"
                + String.join(",", comillas(codigo), comillas(texto(n, "nombre")), comillas(texto(n, "marca")),
                comillas(texto(n, "cantidad")), comillas(texto(n, "unidad")), comillas(texto(n, "contenido")),
                comillas(texto(n, "categoria")), comillas(texto(n, "imagen")), comillas(texto(n, "fuente")),
                comillas(texto(n, "actualizado"))) + "\n";
        try {
            List<CatalogoCodigo> filas = CatalogoCsv.leer(new StringReader(csv));
            return filas.isEmpty() ? Optional.empty() : Optional.of(filas.get(0));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        if (v == null || v.isNull()) return "";
        return v.isNumber() ? v.numberValue().toString() : v.asText("");
    }

    private static String comillas(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ── Actualización del catálogo completo ───────────────────────────────

    /** Estado para la pantalla de Configuración (nunca lanza) */
    public Estado estado() {
        Configuracion c = configuracionService.obtener();
        long filas;
        try {
            filas = repo.count();
        } catch (RuntimeException e) {
            filas = 0;
        }
        return new Estado(c.isCodigosEnLinea(), c.getCatalogoCodigosVersion(), c.getCatalogoCodigosProductos(),
                c.getCatalogoCodigosDescargado(), c.getCatalogoCodigosRevisado(), filas);
    }

    /** ¿Ya pasaron 7 días desde la última revisión (o nunca se revisó, o la fecha es del futuro)? */
    public boolean tocaRevisar() {
        LocalDateTime ultima = configuracionService.obtener().getCatalogoCodigosRevisado();
        if (ultima == null) return true;
        LocalDateTime ahora = LocalDateTime.now(reloj);
        return ultima.isAfter(ahora) || Duration.between(ultima, ahora).compareTo(INTERVALO_REVISION) >= 0;
    }

    /**
     * Revisión semanal automática: solo si la consulta en línea está permitida y toca.
     * Pensada para el hilo de fondo; devuelve el resultado por si alguien lo quiere mostrar.
     */
    public Optional<ResultadoActualizacion> actualizarSiToca() {
        try {
            if (!isEnLinea() || !tocaRevisar()) return Optional.empty();
            return Optional.of(actualizar());
        } catch (RuntimeException e) {
            System.err.println("Catálogo de códigos: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Después de entrar: espera {@code app.catalogo.espera-ms} (2 min) y revisa una vez, en un
     * hilo demonio de prioridad mínima. Si ya hay uno esperando no arranca otro.
     */
    public synchronized void programarRevisionSemanal() {
        if (hiloRevision != null && hiloRevision.isAlive()) return;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(Math.max(0, esperaRevisionMs));
            } catch (InterruptedException e) {
                return;
            }
            actualizarSiToca();
        }, "catalogo-codigos-semanal");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        hiloRevision = t;
        t.start();
    }

    /** "Actualizar ahora" desde Configuración (solo ADMIN); bloquea: llamar fuera del hilo de JavaFX */
    public ResultadoActualizacion actualizarAhora() {
        sesionService.verificarAdmin();
        return actualizar();
    }

    public CompletableFuture<ResultadoActualizacion> actualizarAhoraEnSegundoPlano() {
        sesionService.verificarAdmin();
        CompletableFuture<ResultadoActualizacion> futuro = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                futuro.complete(actualizar());
            } catch (Throwable e) {
                futuro.complete(ResultadoActualizacion.sinConexion(String.valueOf(e.getMessage())));
            }
        }, "catalogo-codigos-actualizar");
        t.setDaemon(true);
        t.start();
        return futuro;
    }

    /**
     * Baja version.json; si el sha256 del CSV ya es el que tenemos, solo anota la revisión. Si no,
     * baja el CSV, verifica el sha256, lo carga por lotes reemplazando la tabla y anota versión,
     * conteo y fechas. Nunca deja la tabla a medias (una transacción). Nunca lanza.
     */
    public ResultadoActualizacion actualizar() {
        LocalDateTime ahora = LocalDateTime.now(reloj);
        JsonNode version;
        try {
            byte[] bytes = descargar(urlVersion, VERSION_MAXIMO, "application/json", TIEMPO_CORTO);
            version = JSON.readTree(bytes);
        } catch (IOException | RuntimeException e) {
            return ResultadoActualizacion.sinConexion(mensaje(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultadoActualizacion.sinConexion("interrumpido");
        }
        if (version == null || !version.isObject()) return ResultadoActualizacion.rechazado("version.json inesperado");
        String fecha = version.path("fecha").asText("").trim();
        String sha = version.path("archivos").path("catalogo-mx.csv").path("sha256").asText("").trim().toLowerCase(Locale.ROOT);
        long bytesAnunciados = version.path("archivos").path("catalogo-mx.csv").path("bytes").asLong(0);
        if (fecha.isEmpty() || sha.length() != 64) return ResultadoActualizacion.rechazado("version.json sin fecha o sin sha256");
        if (bytesAnunciados > CSV_MAXIMO) return ResultadoActualizacion.rechazado("El catálogo anunciado pesa demasiado.");

        Configuracion c = configuracionService.obtener();
        boolean mismaVersion = sha.equalsIgnoreCase(c.getCatalogoCodigosSha256());
        if (mismaVersion && hayFilas()) {
            configuracionService.marcarCatalogoCodigosRevisado(ahora);
            return ResultadoActualizacion.alDia(c.getCatalogoCodigosVersion(), c.getCatalogoCodigosProductos() == null ? 0 : c.getCatalogoCodigosProductos());
        }

        byte[] csv = null;
        String ultimoError = "";
        for (String url : List.of(urlCsvPrimaria, urlCsvRespaldo)) {
            try {
                csv = descargar(url, CSV_MAXIMO, "text/csv, text/plain", TIEMPO_CSV);
                break;
            } catch (IOException | RuntimeException e) {
                ultimoError = mensaje(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ResultadoActualizacion.sinConexion("interrumpido");
            }
        }
        if (csv == null) return ResultadoActualizacion.sinConexion(ultimoError);
        if (!sha.equals(sha256(csv))) {
            // CDN con una copia vieja o descarga corrupta: se conserva lo que había; se reintenta en la próxima revisión
            return ResultadoActualizacion.rechazado("El catálogo descargado no coincide con la firma anunciada (sha256).");
        }
        int cargadas;
        try {
            cargadas = reemplazarCatalogo(new String(csv, StandardCharsets.UTF_8), fecha, sha, ahora);
        } catch (RuntimeException e) {
            return ResultadoActualizacion.rechazado("No se pudo cargar el catálogo: " + mensaje(e));
        }
        try {
            auditoriaService.registrar(sesionService.getCajeroActual(), "CATALOGO_CODIGOS_ACTUALIZADO",
                    "versión " + fecha + " · " + cargadas + " productos · " + ATRIBUCION, (Cajero) null);
        } catch (RuntimeException sinBitacora) {
            // la bitácora no debe tumbar la actualización
        }
        return ResultadoActualizacion.actualizado(fecha, cargadas);
    }

    private boolean hayFilas() {
        try {
            return repo.count() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Carga el CSV completo en la tabla (DELETE + INSERT por lotes) y anota la versión, todo en
     * UNA transacción JDBC: si algo falla, la tabla anterior queda intacta. Público para las
     * pruebas y para una futura importación desde archivo. Devuelve las filas cargadas.
     */
    public int reemplazarCatalogo(String csv, String version, String sha256, LocalDateTime cuando) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer cargadas = tx.execute(estado -> {
            Connection con = DataSourceUtils.getConnection(dataSource);
            try {
                try (Statement st = con.createStatement()) {
                    st.executeUpdate("DELETE FROM catalogo_codigos");
                }
                int[] total = {0};
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO catalogo_codigos (codigo, nombre, marca, cantidad, unidad, contenido, categoria, imagen, fuente, actualizado) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    List<CatalogoCodigo> lote = new ArrayList<>(LOTE);
                    java.util.Set<String> vistos = new java.util.HashSet<>(32_768);
                    CatalogoCsv.leer(new StringReader(csv), fila -> {
                        if (!vistos.add(fila.getCodigo())) return;   // código repetido: se queda la primera
                        lote.add(fila);
                        if (lote.size() >= LOTE) {
                            total[0] += insertar(ps, lote);
                            lote.clear();
                        }
                    });
                    if (!lote.isEmpty()) total[0] += insertar(ps, lote);
                }
                em.flush();
                em.clear();   // por si alguna ficha vieja quedó en la sesión de JPA
                configuracionService.guardarEstadoCatalogoCodigos(version, sha256, total[0], cuando);
                return total[0];
            } catch (SQLException | IOException e) {
                throw new NegocioException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                DataSourceUtils.releaseConnection(con, dataSource);
            }
        });
        return cargadas == null ? 0 : cargadas;
    }

    private static int insertar(PreparedStatement ps, List<CatalogoCodigo> lote) {
        try {
            for (CatalogoCodigo f : lote) {
                ps.setString(1, f.getCodigo());
                ps.setString(2, f.getNombre());
                ps.setString(3, f.getMarca());
                if (f.getCantidad() == null) ps.setNull(4, Types.DOUBLE); else ps.setDouble(4, f.getCantidad());
                ps.setString(5, f.getUnidad());
                ps.setString(6, f.getContenido());
                ps.setString(7, f.getCategoria());
                ps.setString(8, f.getImagen());
                ps.setString(9, f.getFuente());
                ps.setString(10, f.getActualizado());
                ps.addBatch();
            }
            int[] r = ps.executeBatch();
            return r.length;
        } catch (SQLException e) {
            throw new NegocioException("Error al insertar el catálogo: " + e.getMessage());
        }
    }

    // ── Red ───────────────────────────────────────────────────────────────

    /** GET acotado, sin redirecciones; acepta gzip y lo descomprime (el CSV baja a ~0.7 MB) */
    private byte[] descargar(String url, int maximoBytes, String accept, Duration espera) throws IOException, InterruptedException {
        if (!url.startsWith("https://") && !(permitirHttpParaPruebas && url.startsWith("http://127.0.0.1"))) {
            throw new IOException("Solo se consulta por HTTPS.");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(espera)
                .header("Accept", accept)
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", VersionApp.agenteUsuario())
                .GET().build();
        HttpResponse<InputStream> resp = http().send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream crudo = resp.body()) {
            if (resp.statusCode() != 200) throw new IOException("El servidor respondió " + resp.statusCode());
            resp.headers().firstValueAsLong("Content-Length").ifPresent(n -> {
                if (n > maximoBytes) throw new IllegalStateException("La respuesta es demasiado grande.");
            });
            boolean gzip = resp.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase(Locale.ROOT).contains("gzip")).orElse(false);
            InputStream in = gzip ? new GZIPInputStream(crudo, 64 * 1024) : crudo;
            ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maximoBytes, 1 << 20));
            byte[] tmp = new byte[64 * 1024];
            int n;
            while ((n = in.read(tmp)) != -1) {
                if (buf.size() + n > maximoBytes) throw new IOException("La respuesta es demasiado grande.");
                buf.write(tmp, 0, n);
            }
            return buf.toByteArray();
        }
    }

    static String sha256(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mensaje(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ── Pruebas ───────────────────────────────────────────────────────────

    /** Solo pruebas: apunta todas las URL a un servidor local (http://127.0.0.1:puerto/...) */
    void urlsParaPruebas(String base) {
        permitirHttpParaPruebas = base != null;
        String b = base == null ? null : (base.endsWith("/") ? base : base + "/");
        urlVersion = b == null ? URL_VERSION : b + "version.json";
        urlCsvPrimaria = b == null ? URL_CSV_PRIMARIA : b + "catalogo-mx.csv";
        urlCsvRespaldo = b == null ? URL_CSV_RESPALDO : b + "respaldo/catalogo-mx.csv";
        urlFragmentoPrimaria = b == null ? URL_FRAGMENTO_PRIMARIA : b + "api/";
        urlFragmentoRespaldo = b == null ? URL_FRAGMENTO_RESPALDO : b + "api-respaldo/";
        sinResultadoEnLinea.clear();
    }

    /** Solo pruebas */
    void olvidarSinResultado() {
        sinResultadoEnLinea.clear();
    }
}
