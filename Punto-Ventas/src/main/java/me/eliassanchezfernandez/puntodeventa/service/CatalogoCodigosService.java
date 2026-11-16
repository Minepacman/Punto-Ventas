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
 * Cadena de consulta de una ficha ({@link #consultar}), de lo más barato a lo más caro:
 *  1. la copia LOCAL (tabla {@code catalogo_codigos}, ~25 000 filas) que este
 *     servicio descarga completa y reemplaza cuando el {@code version.json}
 *     del repositorio trae otro sha256 ({@link #actualizar});
 *  2. el catálogo INCLUIDO en el programa ({@value #RECURSO_INCLUIDO}, ~0.7 MB
 *     comprimido): mientras la tabla no se haya llenado se lee en flujo; la revisión
 *     de fondo lo carga a la tabla la primera vez ({@link #cargarIncluidoSiFalta}),
 *     así una caja sin internet tiene el catálogo completo desde el primer día;
 *  3. si no está y el administrador no lo apagó ({@code Configuracion.codigosEnLinea}),
 *     un fragmento de la API estática ({@code api/v1/<3 últimos dígitos>.json},
 *     ~6-50 KB) desde jsDelivr o raw.githubusercontent;
 *  4. como último recurso la API de Open Food Facts ({@value #URL_OPEN_FOOD_FACTS}),
 *     con el código de 13 dígitos y, si empieza con 0, también como UPC de 12.
 * Lo hallado en 2-4 se guarda en la tabla local para la próxima vez. Cada petición de
 * consulta espera a lo más {@link #TIEMPO_CONSULTA} (3 s); sin red se responde
 * {@link Resultado#SIN_CONEXION} (el formulario dice "Sin internet: escribe los datos").
 *
 * Seguridad de red (igual que ActualizacionService): solo https a los hosts
 * fijos de {@link #URL_VERSION}, {@link #URL_CSV_PRIMARIA}, {@link #URL_CSV_RESPALDO},
 * {@link #URL_FRAGMENTO_PRIMARIA}/{@link #URL_FRAGMENTO_RESPALDO} y
 * {@link #URL_OPEN_FOOD_FACTS}; sin
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
    /** Se completa con el código + ".json" + {@link #CAMPOS_OPEN_FOOD_FACTS} */
    public static final String URL_OPEN_FOOD_FACTS = "https://world.openfoodfacts.org/api/v2/product/";
    static final String CAMPOS_OPEN_FOOD_FACTS = "?fields=code,product_name,product_name_es,brands,quantity,image_front_small_url,categories_tags";
    /** Catálogo que viaja en el jar (mismo CSV que publica el repositorio, comprimido) y su version.json */
    public static final String RECURSO_INCLUIDO = "/catalogo/catalogo-mx.csv.gz";
    public static final String RECURSO_VERSION_INCLUIDA = "/catalogo/version.json";

    public static final int VERSION_MAXIMO = 64 * 1024;
    /** El CSV pesa ~3.5 MB (comprimido ~0.7 MB); margen para que crezca */
    public static final int CSV_MAXIMO = 24 * 1024 * 1024;
    public static final int FRAGMENTO_MAXIMO = 512 * 1024;
    /** Una ficha de Open Food Facts con solo los campos pedidos pesa ~1 KB */
    public static final int OPEN_FOOD_FACTS_MAXIMO = 256 * 1024;
    /** Espera máxima de cada petición al consultar UN código desde el formulario (conexión y respuesta) */
    public static final Duration TIEMPO_CONSULTA = Duration.ofSeconds(3);
    public static final Duration TIEMPO_CONEXION = Duration.ofSeconds(6);
    public static final Duration TIEMPO_CORTO = Duration.ofSeconds(8);
    /** 3.5 MB (0.7 comprimido) en una conexión lenta */
    public static final Duration TIEMPO_CSV = Duration.ofSeconds(90);
    /** Se revisa si hay versión nueva a lo más cada 7 días */
    public static final Duration INTERVALO_REVISION = Duration.ofDays(7);
    static final int LOTE = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** De dónde salió la ficha: tabla local, catálogo incluido en el programa, fragmento en línea u Open Food Facts */
    public enum Origen { LOCAL, INCLUIDO, EN_LINEA, OPEN_FOOD_FACTS }

    /** Ficha encontrada y de dónde salió */
    public record Ficha(CatalogoCodigo datos, Origen origen) { }

    /** Qué pasó al consultar un código */
    public enum Resultado {
        ENCONTRADO,
        /** Ninguna fuente lo tiene (o la consulta en internet está apagada y no está en la copia local) */
        NO_ENCONTRADO,
        /** No está en la copia local y no se pudo llegar a internet */
        SIN_CONEXION,
        /** No es un código de barras comercial (código interno, dígito de control malo, vacío) */
        NO_ES_CODIGO
    }

    /** Resultado de {@link #consultar}: la ficha solo viene con {@link Resultado#ENCONTRADO} */
    public record Consulta(Resultado resultado, Ficha ficha) {
        static Consulta encontrado(CatalogoCodigo datos, Origen origen) {
            return new Consulta(Resultado.ENCONTRADO, new Ficha(datos, origen));
        }
        static Consulta de(Resultado r) {
            return new Consulta(r, null);
        }
        public Optional<Ficha> fichaOpcional() {
            return Optional.ofNullable(ficha);
        }
    }

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
    private volatile String urlOpenFoodFacts = URL_OPEN_FOOD_FACTS;
    private volatile boolean permitirHttpParaPruebas = false;
    /** Catálogo incluido: CSV comprimido y su versión (null = no hay; las pruebas lo cambian) */
    private volatile java.util.function.Supplier<InputStream> fuenteIncluida =
            () -> CatalogoCodigosService.class.getResourceAsStream(RECURSO_INCLUIDO);
    private volatile VersionIncluida versionIncluida;
    private volatile boolean versionIncluidaLeida;

    /** fecha y sha256 del CSV incluido (del version.json que viaja con él) */
    public record VersionIncluida(String fecha, String sha256) { }

    /** Códigos consultados en línea sin resultado en esta sesión: no se vuelve a pedir el mismo fragmento */
    private final Map<String, Long> sinResultadoEnLinea = new ConcurrentHashMap<>();
    private static final long SIN_RESULTADO_VIGENCIA_MS = Duration.ofHours(6).toMillis();

    private volatile HttpClient http;
    /** Cliente de las consultas sueltas: conexión de 3 s como máximo */
    private volatile HttpClient httpConsulta;
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

    private HttpClient httpConsulta() {
        HttpClient h = httpConsulta;
        if (h == null) {
            synchronized (this) {
                h = httpConsulta;
                if (h == null) {
                    h = HttpClient.newBuilder()
                            .connectTimeout(TIEMPO_CONSULTA)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    httpConsulta = h;
                }
            }
        }
        return h;
    }

    // ── Consulta ──────────────────────────────────────────────────────────

    /**
     * Ficha del código tal como lo tecleó o escaneó el usuario (vacío si no hay). Bloquea unos
     * segundos a lo más: llamar fuera del hilo de JavaFX. Nunca lanza. Ver {@link #consultar}.
     */
    public Optional<Ficha> buscar(String codigoCapturado) {
        return consultar(codigoCapturado).fichaOpcional();
    }

    /**
     * Recorre la cadena local → incluido → fragmento en línea → Open Food Facts y dice qué pasó.
     * Bloquea a lo más unos segundos (cada petición ≤ {@link #TIEMPO_CONSULTA}); llamar fuera del
     * hilo de JavaFX. Nunca lanza. Si el hilo se interrumpe (el usuario cambió el código) deja de
     * consultar y responde NO_ENCONTRADO.
     */
    public Consulta consultar(String codigoCapturado) {
        Optional<String> normalizado = CodigoBarras.normalizar(codigoCapturado);
        if (normalizado.isEmpty()) return Consulta.de(Resultado.NO_ES_CODIGO);
        String codigo = normalizado.get();
        try {
            Optional<CatalogoCodigo> local = buscarLocal(codigo);
            if (local.isPresent()) return Consulta.encontrado(local.get(), Origen.LOCAL);
        } catch (RuntimeException e) {
            // Tabla aún inexistente (instalado sin subir la versión) o base ocupada: se sigue
            System.err.println("Catálogo de códigos (local): " + e.getMessage());
        }
        // Mientras la tabla no tenga el catálogo completo se lee el que viaja en el programa
        if (!catalogoCompletoCargado()) {
            Optional<CatalogoCodigo> incluido = buscarEnIncluido(codigo);
            if (incluido.isPresent()) {
                guardarSinFallar(incluido.get());
                return Consulta.encontrado(incluido.get(), Origen.INCLUIDO);
            }
        }
        if (!isEnLinea()) return Consulta.de(Resultado.NO_ENCONTRADO);
        Long sin = sinResultadoEnLinea.get(codigo);
        if (sin != null && System.currentTimeMillis() - sin < SIN_RESULTADO_VIGENCIA_MS) return Consulta.de(Resultado.NO_ENCONTRADO);

        Remoto fragmento = buscarEnLinea(codigo);
        if (fragmento.ficha() != null) {
            guardarSinFallar(fragmento.ficha());
            return Consulta.encontrado(fragmento.ficha(), Origen.EN_LINEA);
        }
        if (Thread.currentThread().isInterrupted()) return Consulta.de(Resultado.NO_ENCONTRADO);
        // Sin red hacia GitHub/jsDelivr: no se insiste con otro host (cada intento son hasta 3 s)
        if (fragmento.sinRed() && !fragmento.respondio()) return Consulta.de(Resultado.SIN_CONEXION);

        Remoto off = buscarEnOpenFoodFacts(codigo);
        if (off.ficha() != null) {
            guardarSinFallar(off.ficha());
            return Consulta.encontrado(off.ficha(), Origen.OPEN_FOOD_FACTS);
        }
        if (Thread.currentThread().isInterrupted()) return Consulta.de(Resultado.NO_ENCONTRADO);
        if (!fragmento.respondio() && !off.respondio() && (fragmento.sinRed() || off.sinRed())) {
            return Consulta.de(Resultado.SIN_CONEXION);
        }
        // Solo se recuerda "no está" cuando las fuentes contestaron de verdad
        if (fragmento.respondio() && off.respondio()) sinResultadoEnLinea.put(codigo, System.currentTimeMillis());
        return Consulta.de(Resultado.NO_ENCONTRADO);
    }

    /** Lo mismo que {@link #buscar} en un hilo demonio propio; el que llama decide cómo mostrarlo */
    public CompletableFuture<Optional<Ficha>> buscarEnSegundoPlano(String codigoCapturado) {
        return consultarEnSegundoPlano(codigoCapturado).thenApply(Consulta::fichaOpcional);
    }

    /**
     * {@link #consultar} en un hilo demonio propio. Cancelar el futuro interrumpe la consulta
     * (deja de esperar a la red); el futuro siempre termina.
     */
    public CompletableFuture<Consulta> consultarEnSegundoPlano(String codigoCapturado) {
        CompletableFuture<Consulta> futuro = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                futuro.complete(consultar(codigoCapturado));
            } catch (Throwable e) {
                futuro.complete(Consulta.de(Resultado.NO_ENCONTRADO));
            }
        }, "catalogo-codigos-buscar");
        t.setDaemon(true);
        futuro.whenComplete((r, e) -> {
            if (futuro.isCancelled()) t.interrupt();
        });
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

    private void guardarSinFallar(CatalogoCodigo ficha) {
        try {
            guardarEncontrado(ficha);
        } catch (RuntimeException e) {
            System.err.println("Catálogo de códigos: no se pudo guardar la ficha: " + e.getMessage());
        }
    }

    /** ¿Se permite salir a internet (fragmentos y catálogo completo)? null en la configuración = sí */
    public boolean isEnLinea() {
        try {
            return configuracionService.obtener().isCodigosEnLinea();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** ¿La tabla ya tiene un catálogo completo (incluido o descargado)? Si no, se consulta el incluido */
    private boolean catalogoCompletoCargado() {
        try {
            return configuracionService.obtener().getCatalogoCodigosVersion() != null && hayFilas();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Respuesta de una fuente en línea: la ficha (o null), si algún servidor contestó y si faltó la red */
    record Remoto(CatalogoCodigo ficha, boolean respondio, boolean sinRed) { }

    /** Pide el fragmento al CDN (y al respaldo si falla) y saca el código */
    Remoto buscarEnLinea(String codigo) {
        String nombre = CodigoBarras.fragmento(codigo) + ".json";
        boolean respondio = false;
        boolean sinRed = false;
        for (String base : List.of(urlFragmentoPrimaria, urlFragmentoRespaldo)) {
            try {
                byte[] json = descargar(httpConsulta(), base + nombre, FRAGMENTO_MAXIMO, "application/json", TIEMPO_CONSULTA);
                return new Remoto(analizarFragmento(new String(json, StandardCharsets.UTF_8), codigo).orElse(null), true, sinRed);
            } catch (RespuestaHttp e) {
                respondio = true;          // el servidor contestó con un error: se prueba el otro
            } catch (IOException e) {
                sinRed = true;             // sin conexión, DNS o tiempo agotado
            } catch (RuntimeException e) {
                respondio = true;          // JSON inválido o demasiado grande
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Remoto(null, respondio, sinRed);
            }
        }
        return new Remoto(null, respondio, sinRed);
    }

    /**
     * Último recurso: la API pública de Open Food Facts. Primero el código de 13 dígitos y, si
     * empieza con 0 (UPC-A), también con 12. Un 404 o {@code status: 0} = no está.
     */
    Remoto buscarEnOpenFoodFacts(String codigo) {
        boolean respondio = false;
        List<String> variantes = codigo.length() == 13 && codigo.charAt(0) == '0'
                ? List.of(codigo, codigo.substring(1)) : List.of(codigo);
        for (String v : variantes) {
            try {
                byte[] json = descargar(httpConsulta(), urlOpenFoodFacts + v + ".json" + CAMPOS_OPEN_FOOD_FACTS,
                        OPEN_FOOD_FACTS_MAXIMO, "application/json", TIEMPO_CONSULTA);
                respondio = true;
                Optional<CatalogoCodigo> f = analizarOpenFoodFacts(new String(json, StandardCharsets.UTF_8), codigo);
                if (f.isPresent()) return new Remoto(f.get(), true, false);
            } catch (RespuestaHttp e) {
                respondio = true;          // 404 = no lo tienen
            } catch (IOException e) {
                return new Remoto(null, respondio, true);
            } catch (RuntimeException e) {
                respondio = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Remoto(null, respondio, false);
            }
        }
        return new Remoto(null, respondio, false);
    }

    /**
     * Análisis puro de la respuesta de Open Food Facts
     * ({@code {"status":1,"product":{product_name, brands, quantity, image_front_small_url, categories_tags}}}).
     * La ficha queda con el código normalizado del catálogo y fuente "off".
     */
    public static Optional<CatalogoCodigo> analizarOpenFoodFacts(String json, String codigo) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON inválido", e);
        }
        if (raiz == null || !raiz.isObject() || raiz.path("status").asInt(0) != 1) return Optional.empty();
        JsonNode p = raiz.get("product");
        if (p == null || !p.isObject()) return Optional.empty();
        String nombre = texto(p, "product_name_es");
        if (nombre.isBlank()) nombre = texto(p, "product_name");
        String marca = texto(p, "brands");
        int coma = marca.indexOf(',');
        if (coma >= 0) marca = marca.substring(0, coma);
        String contenido = texto(p, "quantity");
        String[] cantidadUnidad = cantidadYUnidad(contenido);
        String imagen = texto(p, "image_front_small_url");
        if (!imagen.startsWith("https://images.openfoodfacts.org/")) imagen = "";
        java.util.List<String> etiquetas = new ArrayList<>();
        p.path("categories_tags").forEach(n -> etiquetas.add(n.asText("")));
        return fichaDeCampos(codigo, nombre, marca, cantidadUnidad[0], cantidadUnidad[1], contenido,
                categoriaDeEtiquetas(etiquetas), imagen, "off", "");
    }

    /** "600 ml" → {"600", "ml"}; "1,5 L" → {"1.5", "l"}; "6 x 355 ml" o basura → {"", ""} */
    static String[] cantidadYUnidad(String contenido) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|mg|g|ml|cl|l|oz|lb|pz)\\.?\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(contenido == null ? "" : contenido);
        if (!m.matches()) return new String[] {"", ""};
        return new String[] {m.group(1).replace(',', '.'), m.group(2).toLowerCase(Locale.ROOT)};
    }

    /** Etiquetas de categoría de Open Food Facts → categoría del catálogo (las mismas que usa codigos-barras-mx) */
    static final List<Map.Entry<String, List<String>>> CATEGORIAS_OFF = List.of(
            Map.entry("Mascotas", List.of("en:pet-food", "en:dog-food", "en:cat-food", "en:dog-foods", "en:cat-foods")),
            Map.entry("Bebés", List.of("en:baby-foods", "en:baby-milks", "en:infant-formulas")),
            Map.entry("Congelados", List.of("en:frozen-foods", "en:ice-creams", "en:frozen-desserts")),
            Map.entry("Lácteos", List.of("en:dairies", "en:milks", "en:cheeses", "en:yogurts", "en:creams")),
            Map.entry("Carnes y embutidos", List.of("en:meats", "en:prepared-meats", "en:sausages", "en:hams")),
            Map.entry("Enlatados", List.of("en:canned-foods", "en:canned-vegetables", "en:canned-fishes")),
            Map.entry("Panadería", List.of("en:breads", "en:pastries", "en:biscuits-and-cakes", "en:biscuits", "en:cakes")),
            Map.entry("Dulces", List.of("en:confectioneries", "en:candies", "en:chocolates", "en:chewing-gum", "en:lollipops")),
            Map.entry("Botanas", List.of("en:salty-snacks", "en:crisps", "en:chips-and-fries", "en:appetizers", "en:popcorn")),
            Map.entry("Bebidas", List.of("en:beverages", "en:waters", "en:sodas", "en:juices")),
            Map.entry("Abarrotes", List.of("en:cereals-and-potatoes", "en:pastas", "en:rices", "en:legumes", "en:sauces",
                    "en:condiments", "en:fats", "en:sugars", "en:salts", "en:coffees", "en:breakfast-cereals")));

    static String categoriaDeEtiquetas(List<String> etiquetas) {
        if (etiquetas == null || etiquetas.isEmpty()) return "";
        for (Map.Entry<String, List<String>> c : CATEGORIAS_OFF) {
            for (String e : c.getValue()) {
                if (etiquetas.contains(e)) return c.getKey();
            }
        }
        return "";
    }

    // ── Catálogo incluido en el programa ──────────────────────────────────

    /** version.json del catálogo incluido (null si esta compilación no lo trae) */
    public VersionIncluida versionIncluida() {
        if (!versionIncluidaLeida) {
            VersionIncluida v = null;
            try (InputStream in = CatalogoCodigosService.class.getResourceAsStream(RECURSO_VERSION_INCLUIDA)) {
                if (in != null) {
                    JsonNode n = JSON.readTree(in);
                    String fecha = n.path("fecha").asText("").trim();
                    String sha = n.path("archivos").path("catalogo-mx.csv").path("sha256").asText("").trim().toLowerCase(Locale.ROOT);
                    if (!fecha.isEmpty() && sha.length() == 64) v = new VersionIncluida(fecha, sha);
                }
            } catch (IOException | RuntimeException e) {
                v = null;
            }
            versionIncluida = v;
            versionIncluidaLeida = true;
        }
        return versionIncluida;
    }

    /** Busca un código leyendo en flujo el CSV incluido (~0.2 s); se detiene al encontrarlo */
    Optional<CatalogoCodigo> buscarEnIncluido(String codigo) {
        java.util.function.Supplier<InputStream> fuente = fuenteIncluida;
        if (fuente == null) return Optional.empty();
        CatalogoCodigo[] hallado = new CatalogoCodigo[1];
        try (InputStream crudo = fuente.get()) {
            if (crudo == null) return Optional.empty();
            try (java.io.Reader r = new InputStreamReader(new GZIPInputStream(crudo, 64 * 1024), StandardCharsets.UTF_8)) {
                CatalogoCsv.leer(r, fila -> {
                    if (Thread.currentThread().isInterrupted()) throw new Detener();
                    if (codigo.equals(fila.getCodigo())) {
                        hallado[0] = fila;
                        throw new Detener();
                    }
                });
            }
        } catch (Detener listo) {
            // encontrado o interrumpido
        } catch (IOException | RuntimeException e) {
            System.err.println("Catálogo de códigos (incluido): " + e.getMessage());
        }
        return Optional.ofNullable(hallado[0]);
    }

    /** Corta la lectura en flujo del CSV incluido */
    private static final class Detener extends RuntimeException {
        Detener() { super(null, null, false, false); }
    }

    /**
     * Carga el catálogo incluido a la tabla si nunca se ha cargado uno o si el incluido es más
     * nuevo que el que hay (p. ej. tras actualizar el programa sin internet). Conserva la fecha
     * de la última revisión en línea, así la revisión semanal sigue su curso. Nunca lanza.
     * @return true si cargó
     */
    public boolean cargarIncluidoSiFalta() {
        try {
            VersionIncluida v = versionIncluida();
            java.util.function.Supplier<InputStream> fuente = fuenteIncluida;
            if (v == null || fuente == null) return false;
            Configuracion c = configuracionService.obtener();
            String actual = c.getCatalogoCodigosVersion();
            if (actual != null && hayFilas()
                    && (actual.compareTo(v.fecha()) >= 0 || v.sha256().equalsIgnoreCase(c.getCatalogoCodigosSha256()))) {
                return false;
            }
            byte[] csv;
            try (InputStream crudo = fuente.get()) {
                if (crudo == null) return false;
                csv = new GZIPInputStream(crudo, 64 * 1024).readAllBytes();
            }
            if (!v.sha256().equals(sha256(csv))) {
                System.err.println("Catálogo de códigos (incluido): el sha256 no coincide; no se carga.");
                return false;
            }
            reemplazarCatalogo(new String(csv, StandardCharsets.UTF_8), v.fecha(), v.sha256(),
                    LocalDateTime.now(reloj), c.getCatalogoCodigosRevisado());
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("Catálogo de códigos (incluido): " + e.getMessage());
            return false;
        }
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
        return fichaDeCampos(codigo, texto(n, "nombre"), texto(n, "marca"), texto(n, "cantidad"), texto(n, "unidad"),
                texto(n, "contenido"), texto(n, "categoria"), texto(n, "imagen"), texto(n, "fuente"), texto(n, "actualizado"));
    }

    /** Se reutiliza el mismo recorte/validación del CSV: una sola fila con el mismo encabezado */
    private static Optional<CatalogoCodigo> fichaDeCampos(String codigo, String nombre, String marca, String cantidad,
                                                          String unidad, String contenido, String categoria,
                                                          String imagen, String fuente, String actualizado) {
        String csv = "codigo,nombre,marca,cantidad,unidad,contenido,categoria,imagen,fuente,actualizado\n"
                + String.join(",", comillas(codigo), comillas(nombre), comillas(marca), comillas(cantidad),
                comillas(unidad), comillas(contenido), comillas(categoria), comillas(imagen), comillas(fuente),
                comillas(actualizado)) + "\n";
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
            cargarIncluidoSiFalta();   // primera vez (o programa más nuevo que la copia): sin internet
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
            byte[] bytes = descargar(http(), urlVersion, VERSION_MAXIMO, "application/json", TIEMPO_CORTO);
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
                csv = descargar(http(), url, CSV_MAXIMO, "text/csv, text/plain", TIEMPO_CSV);
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
        return reemplazarCatalogo(csv, version, sha256, cuando, cuando);
    }

    /** Igual, pero anotando aparte la fecha de la última revisión en línea (el catálogo incluido conserva la anterior) */
    int reemplazarCatalogo(String csv, String version, String sha256, LocalDateTime cuando, LocalDateTime revisado) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer cargadas = tx.execute(estado -> {
            em.flush();   // fichas guardadas por JPA en esta misma transacción: antes del DELETE, no después
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
                configuracionService.guardarEstadoCatalogoCodigos(version, sha256, total[0], cuando, revisado);
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

    /** El servidor contestó, pero no con 200 (404 = no está) */
    static final class RespuestaHttp extends IOException {
        final int estado;
        RespuestaHttp(int estado) {
            super("El servidor respondió " + estado);
            this.estado = estado;
        }
    }

    /** GET acotado, sin redirecciones; acepta gzip y lo descomprime (el CSV baja a ~0.7 MB) */
    private byte[] descargar(HttpClient cliente, String url, int maximoBytes, String accept, Duration espera) throws IOException, InterruptedException {
        if (!url.startsWith("https://") && !(permitirHttpParaPruebas && url.startsWith("http://127.0.0.1"))) {
            throw new IOException("Solo se consulta por HTTPS.");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(espera)
                .header("Accept", accept)
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", VersionApp.agenteUsuario())
                .GET().build();
        HttpResponse<InputStream> resp = cliente.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream crudo = resp.body()) {
            if (resp.statusCode() != 200) throw new RespuestaHttp(resp.statusCode());
            if (resp.headers().firstValueAsLong("Content-Length").orElse(0) > maximoBytes) {
                throw new RespuestaHttp(413);   // demasiado grande: cuenta como respuesta, no como falta de red
            }
            boolean gzip = resp.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase(Locale.ROOT).contains("gzip")).orElse(false);
            InputStream in = gzip ? new GZIPInputStream(crudo, 64 * 1024) : crudo;
            ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maximoBytes, 1 << 20));
            byte[] tmp = new byte[64 * 1024];
            int n;
            while ((n = in.read(tmp)) != -1) {
                if (buf.size() + n > maximoBytes) throw new RespuestaHttp(413);
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
        urlOpenFoodFacts = b == null ? URL_OPEN_FOOD_FACTS : b + "off/";
        // Con el servidor de prueba no se usa el catálogo incluido real (tiene miles de códigos reales)
        fuenteIncluida = b == null ? () -> CatalogoCodigosService.class.getResourceAsStream(RECURSO_INCLUIDO) : null;
        versionIncluidaLeida = b != null;
        versionIncluida = null;
        sinResultadoEnLinea.clear();
    }

    /** Solo pruebas: un catálogo incluido de mentira (CSV sin comprimir; null = ninguno) */
    void incluidoParaPruebas(String csv, String fecha) {
        if (csv == null) {
            fuenteIncluida = null;
            versionIncluida = null;
        } else {
            byte[] gz;
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(buf)) {
                    out.write(csv.getBytes(StandardCharsets.UTF_8));
                }
                gz = buf.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            fuenteIncluida = () -> new java.io.ByteArrayInputStream(gz);
            versionIncluida = new VersionIncluida(fecha, sha256(csv.getBytes(StandardCharsets.UTF_8)));
        }
        versionIncluidaLeida = true;
    }

    /** Solo pruebas */
    void olvidarSinResultado() {
        sinResultadoEnLinea.clear();
    }
}
