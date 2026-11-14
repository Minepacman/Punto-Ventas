package me.eliassanchezfernandez.puntodeventa.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.CatalogoCodigo;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.CatalogoCodigoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catálogo de códigos de barras contra un servidor local que imita a GitHub/jsDelivr:
 * descarga con sha256, "ya al día", rechazo por firma, sin conexión, consulta local →
 * en línea → copia local, interruptor de internet, códigos inválidos y revisión semanal.
 */
public class CatalogoCodigosServiceTest extends BaseServicioTest {

    static final String ENCABEZADO = "codigo,nombre,marca,cantidad,unidad,contenido,categoria,imagen,fuente,actualizado\n";
    static final String CSV_V1 = ENCABEZADO
            + "7501055300075,Coca-Cola,Coca-Cola,355,ml,355 ml,Bebidas,https://images.openfoodfacts.org/images/products/750/105/530/0075/front_fr.25.200.jpg,off,2026-03-13\n"
            + "7501055305339,Coca Cola Light 600,Coca-Cola,600,ml,600 ml,Bebidas,,off,2026-01-01\n"
            + "7501055300076,Dígito de control malo,,,,,Otros,,off,2026-01-01\n"
            + "7501055300075,Repetido: se queda la primera,,,,,Otros,,off,2026-01-01\n";
    static final String CSV_V2 = ENCABEZADO
            + "7501055300075,Coca-Cola Nueva,Coca-Cola,355,ml,355 ml,Bebidas,,off,2026-05-01\n"
            + "96385074,Producto EAN-8,Marca,,,,Abarrotes,,manual,2026-05-01\n";
    static final String FRAGMENTO_234 = "{\"0744886534234\":{\"actualizado\":\"2026-04-02\",\"cantidad\":600,\"categoria\":\"Bebidas\","
            + "\"contenido\":\"600 ml\",\"fuente\":\"off\",\"imagen\":\"https://images.openfoodfacts.org/images/products/074/488/653/4234/front_es.6.200.jpg\","
            + "\"marca\":\"Aga\",\"nombre\":\"Caballitos Cerveza de Raíz\",\"unidad\":\"ml\"},"
            + "\"7500326303234\":{\"actualizado\":\"2024-05-31\",\"cantidad\":null,\"categoria\":\"Bebidas\",\"contenido\":\"\",\"fuente\":\"off\","
            + "\"imagen\":\"\",\"marca\":\"\",\"nombre\":\"Agua fresca con chía chía mia 360 ml\",\"unidad\":\"\"}}";

    @Autowired private CatalogoCodigosService servicio;
    @Autowired private CatalogoCodigoRepository repo;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AuditoriaRepository auditoriaRepo;

    private HttpServer servidor;
    private final Map<String, AtomicInteger> peticiones = new ConcurrentHashMap<>();
    /** Lo que responde cada ruta: cuerpo (null = 404) */
    private final Map<String, byte[]> respuestas = new ConcurrentHashMap<>();
    private final Map<String, Integer> estados = new ConcurrentHashMap<>();
    private volatile boolean gzipCsv = false;

    @BeforeEach
    void arrancarServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", this::atender);
        servidor.start();
        servicio.urlsParaPruebas("http://127.0.0.1:" + servidor.getAddress().getPort() + "/");
        publicar(CSV_V1, "2026-11-12");
        respuestas.put("/api/234.json", FRAGMENTO_234.getBytes(StandardCharsets.UTF_8));
        respuestas.put("/api/075.json", "{}".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void pararServidor() {
        servicio.urlsParaPruebas(null);
        detener();
    }

    private volatile boolean detenido;

    private void detener() {
        if (!detenido) servidor.stop(0);
        detenido = true;
    }

    private void atender(HttpExchange ex) throws IOException {
        String ruta = ex.getRequestURI().getPath();
        peticiones.computeIfAbsent(ruta, r -> new AtomicInteger()).incrementAndGet();
        Integer estado = estados.get(ruta);
        byte[] cuerpo = respuestas.get(ruta);
        if (estado != null) {
            ex.sendResponseHeaders(estado, -1);
            ex.close();
            return;
        }
        if (cuerpo == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        if (gzipCsv && ruta.endsWith(".csv")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buf)) { gz.write(cuerpo); }
            cuerpo = buf.toByteArray();
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        ex.sendResponseHeaders(200, cuerpo.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(cuerpo); }
    }

    /** version.json coherente con el CSV (sha256 real) */
    private void publicar(String csv, String fecha) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        respuestas.put("/catalogo-mx.csv", bytes);
        respuestas.put("/version.json", versionJson(fecha, CatalogoCodigosService.sha256(bytes), bytes.length).getBytes(StandardCharsets.UTF_8));
    }

    private static String versionJson(String fecha, String sha, long bytes) {
        return "{\"version\":1,\"fecha\":\"" + fecha + "\",\"productos\":24887,\"fragmentos\":1000,"
                + "\"archivos\":{\"catalogo-mx.csv\":{\"sha256\":\"" + sha + "\",\"bytes\":" + bytes + "},"
                + "\"catalogo-mx.jsonl\":{\"sha256\":\"x\",\"bytes\":1}},"
                + "\"atribucion\":\"Contiene información de Open Food Facts, disponible bajo la Open Database License (ODbL)\"}";
    }

    private int peticiones(String ruta) {
        AtomicInteger n = peticiones.get(ruta);
        return n == null ? 0 : n.get();
    }

    // ── Actualización ─────────────────────────────────────────────────────

    @Test
    void actualizarCargaElCatalogoVerificaElShaYAnotaLaVersion() {
        CatalogoCodigosService.ResultadoActualizacion r = servicio.actualizar();
        assertThat(r.estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.ACTUALIZADO);
        assertThat(r.version()).isEqualTo("2026-11-12");
        assertThat(r.productos()).as("2 válidas: la de dígito malo y la repetida se descartan").isEqualTo(2);
        assertThat(repo.count()).isEqualTo(2);
        assertThat(repo.findById("7501055300075")).map(CatalogoCodigo::getNombre).contains("Coca-Cola");

        CatalogoCodigosService.Estado e = servicio.estado();
        assertThat(e.hayCatalogo()).isTrue();
        assertThat(e.version()).isEqualTo("2026-11-12");
        assertThat(e.productos()).isEqualTo(2);
        assertThat(e.filasLocales()).isEqualTo(2);
        assertThat(e.descargado()).isNotNull();
        assertThat(e.revisado()).isNotNull();
        assertThat(servicio.tocaRevisar()).isFalse();
        assertThat(auditoriaRepo.findAll()).extracting(Auditoria::getAccion).contains("CATALOGO_CODIGOS_ACTUALIZADO");
    }

    @Test
    void siElShaNoCambioNoVuelveABajarElCsv() {
        assertThat(servicio.actualizar().estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.ACTUALIZADO);
        assertThat(peticiones("/catalogo-mx.csv")).isEqualTo(1);
        CatalogoCodigosService.ResultadoActualizacion r = servicio.actualizar();
        assertThat(r.estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.YA_AL_DIA);
        assertThat(r.version()).isEqualTo("2026-11-12");
        assertThat(peticiones("/version.json")).isEqualTo(2);
        assertThat(peticiones("/catalogo-mx.csv")).as("el CSV no se vuelve a bajar").isEqualTo(1);
    }

    @Test
    void unaVersionNuevaReemplazaLaTablaCompleta() {
        servicio.actualizar();
        publicar(CSV_V2, "2026-11-19");
        CatalogoCodigosService.ResultadoActualizacion r = servicio.actualizar();
        assertThat(r.estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.ACTUALIZADO);
        assertThat(repo.count()).isEqualTo(2);
        assertThat(repo.findById("7501055300075")).map(CatalogoCodigo::getNombre).contains("Coca-Cola Nueva");
        assertThat(repo.findById("7501055305339")).as("ya no está en la versión nueva").isEmpty();
        assertThat(repo.findById("96385074")).isPresent();
        assertThat(servicio.estado().version()).isEqualTo("2026-11-19");
    }

    @Test
    void elCsvComprimidoConGzipSeDescomprime() {
        gzipCsv = true;
        assertThat(servicio.actualizar().estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.ACTUALIZADO);
        assertThat(repo.count()).isEqualTo(2);
    }

    @Test
    void unCsvQueNoCoincideConElShaAnunciadoSeRechazaYSeConservaLoAnterior() {
        servicio.actualizar();
        // El CDN sirve una copia vieja: version.json ya anuncia V2 pero el CSV sigue siendo V1
        byte[] v2 = CSV_V2.getBytes(StandardCharsets.UTF_8);
        respuestas.put("/version.json", versionJson("2026-11-19", CatalogoCodigosService.sha256(v2), v2.length).getBytes(StandardCharsets.UTF_8));
        CatalogoCodigosService.ResultadoActualizacion r = servicio.actualizar();
        assertThat(r.estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.RECHAZADO);
        assertThat(r.detalle()).contains("sha256");
        assertThat(repo.count()).isEqualTo(2);
        assertThat(repo.findById("7501055300075")).map(CatalogoCodigo::getNombre).contains("Coca-Cola");
        assertThat(servicio.estado().version()).isEqualTo("2026-11-12");
    }

    @Test
    void siElCsvPrimarioFallaSeUsaElRespaldo() {
        estados.put("/catalogo-mx.csv", 500);
        respuestas.put("/respaldo/catalogo-mx.csv", CSV_V1.getBytes(StandardCharsets.UTF_8));
        assertThat(servicio.actualizar().estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.ACTUALIZADO);
        assertThat(peticiones("/respaldo/catalogo-mx.csv")).isEqualTo(1);
    }

    @Test
    void sinServidorNoPasaNadaYNoSeAnotaRevision() {
        detener();
        CatalogoCodigosService.ResultadoActualizacion r = servicio.actualizar();
        assertThat(r.estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.SIN_CONEXION);
        assertThat(repo.count()).isZero();
        assertThat(servicio.estado().hayCatalogo()).isFalse();
        assertThat(servicio.tocaRevisar()).as("se reintenta en la próxima oportunidad").isTrue();
    }

    @Test
    void versionJsonRaroSeRechaza() {
        respuestas.put("/version.json", "{\"fecha\":\"2026-11-12\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(servicio.actualizar().estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.RECHAZADO);
        respuestas.put("/version.json", "[1,2,3]".getBytes(StandardCharsets.UTF_8));
        assertThat(servicio.actualizar().estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.RECHAZADO);
        respuestas.put("/version.json", "no es json".getBytes(StandardCharsets.UTF_8));
        assertThat(servicio.actualizar().estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.SIN_CONEXION);
        assertThat(peticiones("/catalogo-mx.csv")).isZero();
    }

    @Test
    void actualizarAhoraExigeAdmin() throws Exception {
        iniciarComoCajero("cajero_cat", java.util.Set.of());
        assertThatThrownBy(() -> servicio.actualizarAhora()).isInstanceOf(NegocioException.class);
        assertThat(peticiones("/version.json")).isZero();
        iniciarComoAdmin();
        assertThat(servicio.actualizarAhora().estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.ACTUALIZADO);
    }

    @Test
    void laRevisionSemanalSoloCorreCuandoToca() {
        assertThat(servicio.tocaRevisar()).as("nunca se ha revisado").isTrue();
        assertThat(servicio.actualizarSiToca()).isPresent();
        assertThat(servicio.actualizarSiToca()).as("ya se revisó hoy").isEmpty();
        assertThat(peticiones("/version.json")).isEqualTo(1);

        // Hace 8 días
        configuracionService.obtenerParaEditar().setCatalogoCodigosRevisado(LocalDateTime.now().minusDays(8));
        configuracionService.invalidarCache();
        assertThat(servicio.tocaRevisar()).isTrue();
        assertThat(servicio.actualizarSiToca()).map(CatalogoCodigosService.ResultadoActualizacion::estado)
                .contains(CatalogoCodigosService.EstadoActualizacion.YA_AL_DIA);
        assertThat(servicio.tocaRevisar()).isFalse();

        // Reloj movido al futuro guardado: también toca
        configuracionService.obtenerParaEditar().setCatalogoCodigosRevisado(LocalDateTime.now().plusDays(3));
        configuracionService.invalidarCache();
        assertThat(servicio.tocaRevisar()).isTrue();
    }

    // ── Consulta ──────────────────────────────────────────────────────────

    @Test
    void buscarUsaLaCopiaLocalSinSalirAInternet() {
        servicio.actualizar();
        Optional<CatalogoCodigosService.Ficha> f = servicio.buscar("7501055300075");
        assertThat(f).isPresent();
        assertThat(f.get().origen()).isEqualTo(CatalogoCodigosService.Origen.LOCAL);
        assertThat(f.get().datos().getNombre()).isEqualTo("Coca-Cola");
        assertThat(f.get().datos().tieneImagen()).isTrue();
        // Con espacios y como UPC/GTIN-14 de relleno también
        assertThat(servicio.buscar(" 750 1055 300075 ")).isPresent();
        assertThat(servicio.buscar("07501055300075")).isPresent();
        assertThat(peticiones.keySet()).noneMatch(r -> r.startsWith("/api/"));
    }

    @Test
    void siNoEstaLocalConsultaElFragmentoYLoGuardaParaLaProxima() {
        // UPC-A de 12 dígitos tal como lo manda el lector
        Optional<CatalogoCodigosService.Ficha> f = servicio.buscar("744886534234");
        assertThat(f).isPresent();
        assertThat(f.get().origen()).isEqualTo(CatalogoCodigosService.Origen.EN_LINEA);
        CatalogoCodigo d = f.get().datos();
        assertThat(d.getCodigo()).isEqualTo("0744886534234");
        assertThat(d.getNombre()).isEqualTo("Caballitos Cerveza de Raíz");
        assertThat(d.getMarca()).isEqualTo("Aga");
        assertThat(d.getCantidad()).isEqualTo(600.0);
        assertThat(d.getUnidad()).isEqualTo("ml");
        assertThat(d.getContenido()).isEqualTo("600 ml");
        assertThat(d.getCategoria()).isEqualTo("Bebidas");
        assertThat(d.tieneImagen()).isTrue();
        assertThat(peticiones("/api/234.json")).isEqualTo(1);

        assertThat(repo.findById("0744886534234")).as("se guardó en la copia local").isPresent();
        Optional<CatalogoCodigosService.Ficha> otra = servicio.buscar("0744886534234");
        assertThat(otra).isPresent();
        assertThat(otra.get().origen()).isEqualTo(CatalogoCodigosService.Origen.LOCAL);
        assertThat(peticiones("/api/234.json")).as("la segunda vez no sale a internet").isEqualTo(1);

        // El otro código del mismo fragmento, con cantidad null y textos vacíos
        Optional<CatalogoCodigosService.Ficha> agua = servicio.buscar("7500326303234");
        assertThat(agua).isPresent();
        assertThat(agua.get().datos().getCantidad()).isNull();
        assertThat(agua.get().datos().getMarca()).isNull();
        assertThat(agua.get().datos().tieneImagen()).isFalse();
    }

    @Test
    void unCodigoQueNoEstaEnLineaSeRecuerdaYNoSeVuelveAPedir() {
        assertThat(servicio.buscar("7501055300075")).isEmpty();   // fragmento 075 = {}
        assertThat(servicio.buscar("7501055300075")).isEmpty();
        assertThat(peticiones("/api/075.json")).isEqualTo(1);
        assertThat(repo.count()).isZero();
    }

    @Test
    void siElCdnFallaSeUsaElRespaldoYSiTodoFallaNoHayError() {
        estados.put("/api/234.json", 500);
        respuestas.put("/api-respaldo/234.json", FRAGMENTO_234.getBytes(StandardCharsets.UTF_8));
        assertThat(servicio.buscar("0744886534234")).isPresent();
        assertThat(peticiones("/api-respaldo/234.json")).isEqualTo(1);

        servicio.olvidarSinResultado();
        detener();
        assertThat(servicio.buscar("7501055305339")).isEmpty();
    }

    @Test
    void conLaConsultaEnLineaApagadaSoloSeUsaLaCopiaLocal() throws Exception {
        servicio.actualizar();
        iniciarComoAdmin();
        configuracionService.guardarCodigosEnLinea(false);
        assertThat(servicio.isEnLinea()).isFalse();
        assertThat(servicio.estado().enLinea()).isFalse();

        assertThat(servicio.buscar("7501055300075")).as("local sigue funcionando").isPresent();
        assertThat(servicio.buscar("0744886534234")).as("no sale a internet").isEmpty();
        assertThat(peticiones("/api/234.json")).isZero();

        int versiones = peticiones("/version.json");
        configuracionService.obtenerParaEditar().setCatalogoCodigosRevisado(LocalDateTime.now().minusDays(30));
        configuracionService.invalidarCache();
        assertThat(servicio.actualizarSiToca()).as("la revisión semanal tampoco").isEmpty();
        assertThat(peticiones("/version.json")).isEqualTo(versiones);

        // "Actualizar ahora" es una acción explícita del administrador: sí sale
        assertThat(servicio.actualizarAhora().estado()).isEqualTo(CatalogoCodigosService.EstadoActualizacion.YA_AL_DIA);
        assertThat(auditoriaRepo.findAll()).extracting(Auditoria::getDetalle).anyMatch(d -> d.contains("en línea=false"));
    }

    @Test
    void soloElAdminApagaLaConsultaEnLinea() throws Exception {
        iniciarComoCajero("cajero_cat2", java.util.Set.of());
        assertThatThrownBy(() -> configuracionService.guardarCodigosEnLinea(false)).isInstanceOf(NegocioException.class);
    }

    @Test
    void codigosInvalidosNoConsultanNada() {
        assertThat(servicio.buscar("PRE-0001")).isEmpty();
        assertThat(servicio.buscar("7501055300076")).isEmpty();   // dígito de control malo
        assertThat(servicio.buscar("")).isEmpty();
        assertThat(servicio.buscar(null)).isEmpty();
        assertThat(peticiones.keySet()).noneMatch(r -> r.startsWith("/api"));
    }

    @Test
    void buscarEnSegundoPlanoTerminaSiempre() throws Exception {
        // Corre en otro hilo (fuera de la transacción de la prueba): se usa el fragmento vacío 075 y un código inválido
        assertThat(servicio.buscarEnSegundoPlano("7501055300075").get()).isEmpty();
        assertThat(peticiones("/api/075.json")).isEqualTo(1);
        assertThat(servicio.buscarEnSegundoPlano("PRE-1").get()).isEmpty();
    }

    @Test
    void analizarFragmentoEsPuro() {
        assertThat(CatalogoCodigosService.analizarFragmento(FRAGMENTO_234, "0744886534234")).map(CatalogoCodigo::getMarca).contains("Aga");
        assertThat(CatalogoCodigosService.analizarFragmento(FRAGMENTO_234, "7501055300075")).isEmpty();
        assertThat(CatalogoCodigosService.analizarFragmento("{}", "0744886534234")).isEmpty();
        assertThat(CatalogoCodigosService.analizarFragmento("[]", "0744886534234")).isEmpty();
        assertThatThrownBy(() -> CatalogoCodigosService.analizarFragmento("no json", "0744886534234"))
                .isInstanceOf(IllegalArgumentException.class);
        // Una ficha sin nombre no sirve
        assertThat(CatalogoCodigosService.analizarFragmento("{\"0744886534234\":{\"nombre\":\"\",\"marca\":\"X\"}}", "0744886534234")).isEmpty();
    }

    @Test
    void reemplazarCatalogoDirectoCargaYAnota() {
        int n = servicio.reemplazarCatalogo(CSV_V2, "2030-01-01", "abc", LocalDateTime.of(2030, 1, 1, 12, 0));
        assertThat(n).isEqualTo(2);
        assertThat(repo.count()).isEqualTo(2);
        CatalogoCodigosService.Estado e = servicio.estado();
        assertThat(e.version()).isEqualTo("2030-01-01");
        assertThat(e.descargado()).isEqualTo(LocalDateTime.of(2030, 1, 1, 12, 0));
    }
}
