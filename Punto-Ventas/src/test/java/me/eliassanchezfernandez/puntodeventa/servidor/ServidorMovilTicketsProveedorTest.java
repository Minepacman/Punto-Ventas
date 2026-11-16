package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProveedorCodigo;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.TicketPorRevisar;
import me.eliassanchezfernandez.puntodeventa.ocr.MotorOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.Renglon;
import me.eliassanchezfernandez.puntodeventa.ocr.ResultadoOcr;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorCodigoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.TicketPorRevisarRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketsPorRevisarService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceso móvil: «Leer ticket de proveedor» de punta a punta por HTTP con un motor OCR falso que «lee»
 * la muestra t14 (Lala, anonimizada): foto → lectura en segundo plano → revisión → registrar (existencia,
 * costo, lo aprendido) → precios; permisos, tamaño, cupo de lecturas, la cola «por revisar» y la
 * computadora sin lector. La foto que se sube es el ticket sintético {@code ocr/ticket-muestra.jpg}.
 */
@SpringBootTest
@Import(ServidorMovilTicketsProveedorTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilTicketsProveedorTest {

    /** Motor de prueba: devuelve el texto de una muestra; se puede apagar o hacer esperar */
    static volatile boolean disponible = true;
    static volatile CountDownLatch espera = null;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MotorOcr motorFalso() {
            return new MotorOcr() {
                @Override public ResultadoOcr leer(byte[] imagen) throws IOException {
                    CountDownLatch e = espera;
                    if (e != null) {
                        try {
                            if (!e.await(20, TimeUnit.SECONDS)) throw new IOException("tardó");
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new java.io.InterruptedIOException("cancelada");
                        }
                    }
                    String texto = recurso("/tickets-proveedor/t14.txt");
                    List<Renglon> rs = new ArrayList<>();
                    String[] ls = texto.split("\n");
                    for (int i = 0; i < ls.length; i++) rs.add(new Renglon(ls[i], 0.9, 40 + i * 22));
                    return new ResultadoOcr(rs, texto);
                }
                @Override public String nombre() { return "Motor de prueba"; }
                @Override public boolean disponible() { return disponible; }
            };
        }
    }

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private LecturasTicketMovil lecturas;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProveedorService proveedorService;
    @Autowired private ProductoService productoService;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private ProveedorCodigoRepository codigoRepo;
    @Autowired private TicketPorRevisarRepository porRevisarRepo;
    @Autowired private TicketsPorRevisarService porRevisar;

    private final HttpClient http = HttpClient.newHttpClient();
    private String base;
    private static byte[] foto;

    @BeforeEach
    void arrancar() throws Exception {
        disponible = true;
        espera = null;
        if (foto == null) {
            try (InputStream in = getClass().getResourceAsStream("/ocr/ticket-muestra.jpg")) { foto = in.readAllBytes(); }
        }
        servidor.iniciar(0);
        base = "http://127.0.0.1:" + servidor.getPuerto();
        Cajero a = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        a.setDebeCambiarContrasena(false);
        a.setBloqueadoHasta(null);
        a.setIntentosFallidos(0);
        cajeroRepo.save(a);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");
    }

    @AfterEach
    void detener() {
        CountDownLatch e = espera;
        if (e != null) e.countDown();
        espera = null;
        lecturas.olvidarTodo();
        servidor.detener();
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    static String recurso(String ruta) throws IOException {
        try (InputStream in = ServidorMovilTicketsProveedorTest.class.getResourceAsStream(ruta)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String usuario(Permiso... permisos) throws Exception {
        String login = "tk" + UUID.randomUUID().toString().substring(0, 8);
        Set<Permiso> set = permisos.length == 0 ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(List.of(permisos));
        Cajero c = cajeroService.crear(login, "Móvil " + login, CLAVE, Cajero.Rol.CAJERO, set);
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        HttpResponse<String> r = json("POST", "/api/login", Map.of("usuario", login, "contrasena", CLAVE, "recordar", false), null);
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body()).get("token").asText();
    }

    private HttpResponse<String> json(String metodo, String ruta, Object cuerpo, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (cuerpo != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cuerpo)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> imagen(String ruta, byte[] datos, String tipo, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("Content-Type", tipo);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(datos)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String ruta, String token) throws Exception {
        HttpResponse<String> r = json("GET", ruta, null, token);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private String empezarLectura(String token) throws Exception {
        HttpResponse<String> r = imagen("/api/tickets-proveedor/leer", foto, "image/jpeg", token);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        return JSON.readTree(r.body()).get("id").asText();
    }

    private JsonNode esperarLectura(String id, String token, String extra) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode d = get("/api/tickets-proveedor/lectura?id=" + id + extra, token);
            if (!"LEYENDO".equals(d.get("estado").asText())) return d;
            Thread.sleep(50);
        }
        throw new AssertionError("La lectura no terminó");
    }

    private Producto producto(String nombre, double costo, double venta) {
        Producto p = new Producto();
        p.setCodigoBarras("TK-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioCosto(costo);
        p.setPrecioVenta(venta);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        p.setUsaInventario(true);
        p.setCantidadActual(0);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    private static JsonNode linea(JsonNode d, String inicio) {
        for (JsonNode l : d.get("lineas")) if (l.get("descripcion").asText().startsWith(inicio)) return l;
        throw new AssertionError("No está el renglón " + inicio);
    }

    // ── Pruebas ───────────────────────────────────────────────────────────

    @Test
    void leerRevisarYRegistrarDePuntaAPunta() throws Exception {
        Proveedor lala = proveedorService.guardar(null, "Lala", null, null, null, Set.of(), null);
        Producto mango = producto("Yog Lala Beb Mango 220 gr", 13.00, 18.00);
        String token = usuario(Permiso.AJUSTAR_INVENTARIO, Permiso.EDITAR_PRODUCTOS, Permiso.CAMBIAR_PRECIOS, Permiso.VER_GANANCIAS);

        JsonNode est = get("/api/tickets-proveedor/estado", token);
        assertThat(est.get("puedeLeerFotos").asBoolean()).isTrue();
        assertThat(est.get("puedeRegistrar").asBoolean()).isTrue();
        assertThat(est.get("puedeCambiarPrecios").asBoolean()).isTrue();
        assertThat(est.get("proveedores").toString()).contains("Lala");
        assertThat(get("/api/sesion", token).get("secciones").get("ticketsProveedor").asBoolean()).isTrue();

        String id = empezarLectura(token);
        JsonNode d = esperarLectura(id, token, "");
        assertThat(d.get("estado").asText()).isEqualTo("LISTO");
        assertThat(d.get("motor").asText()).isEqualTo("Motor de prueba");
        assertThat(d.get("proveedorId").asLong()).isEqualTo(lala.getId());
        assertThat(d.get("folio").asText()).contains("2794109");
        assertThat(d.get("lineas")).hasSize(8);
        assertThat(d.get("cuadre").get("cuadra").asBoolean()).isTrue();
        JsonNode lm = linea(d, "Yog Lala Beb Mango");
        assertThat(lm.get("producto").get("id").asLong()).isEqualTo(mango.getId());
        assertThat(lm.get("costoPorPieza").asDouble()).isEqualTo(14.40);
        JsonNode lv = linea(d, "UHT Lala 100 Vainilla");

        // El teléfono manda un total inventado: no se usa (el total y el reparto salen de la lectura guardada)
        Map<String, Object> cuerpo = Map.of("id", id, "ticket", 0, "proveedorId", lala.getId(), "folio", "4229-2794109",
                "fecha", "2026-09-11", "total", 1.0,
                "renglones", List.of(
                        Map.of("indice", lm.get("indice").asInt(), "productoId", mango.getId(), "cantidad", 3, "piezasPorUnidad", 1, "importe", 43.20),
                        Map.of("indice", lv.get("indice").asInt(), "nuevo", Map.of("descripcion", "UHT Lala Vainilla 330 ml", "precioVenta", 30.0),
                                "cantidad", 1, "piezasPorUnidad", 1, "importe", 23.70)));
        HttpResponse<String> r = json("POST", "/api/tickets-proveedor/registrar", cuerpo, token);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode res = JSON.readTree(r.body());
        assertThat(res.get("renglones").asInt()).isEqualTo(2);
        assertThat(res.get("productosNuevos").asInt()).isEqualTo(1);
        assertThat(res.get("subieron")).hasSize(1);
        JsonNode sube = res.get("subieron").get(0);
        assertThat(sube.get("productoId").asLong()).isEqualTo(mango.getId());
        assertThat(sube.get("costoAhora").asDouble()).isEqualTo(14.40);
        assertThat(sube.get("precioSugerido").asDouble()).isEqualTo(20.00);   // 14.40 × 18/13 = 19.94 → 20.00

        Producto m2 = productoRepo.findById(mango.getId()).orElseThrow();
        assertThat(m2.getCantidadActual()).isEqualTo(3.0);
        assertThat(m2.getPrecioCosto()).isEqualTo(14.40);
        Producto nuevo = productoRepo.findAllByOrderByDescripcionAsc().stream()
                .filter(p -> p.getDescripcion().equals("UHT Lala Vainilla 330 ml")).findFirst().orElseThrow();
        assertThat(nuevo.getCantidadActual()).isEqualTo(1.0);
        assertThat(nuevo.getPrecioCosto()).isEqualTo(23.70);
        ProveedorCodigo aprendido = codigoRepo.findByProveedorIdAndClave(lala.getId(),
                ProveedorCodigo.claveDe(lm.get("codigoProveedor").asText(null), lm.get("descripcion").asText())).orElseThrow();
        assertThat(aprendido.getProducto().getId()).isEqualTo(mango.getId());

        // El mismo ticket no se registra dos veces desde la misma lectura
        assertThat(json("POST", "/api/tickets-proveedor/registrar", cuerpo, token).statusCode()).isEqualTo(400);

        // Precios: el sugerido
        HttpResponse<String> p = json("POST", "/api/precios/actualizar",
                Map.of("precios", List.of(Map.of("productoId", mango.getId(), "precioVenta", 20.0))), token);
        assertThat(p.statusCode()).as(p.body()).isEqualTo(200);
        assertThat(JSON.readTree(p.body()).get("actualizados").asInt()).isEqualTo(1);
        assertThat(productoRepo.findById(mango.getId()).orElseThrow().getPrecioVenta()).isEqualTo(20.0);

        // La próxima lectura ya conoce el renglón (y avisa que el folio ya se registró)
        String id2 = empezarLectura(token);
        JsonNode d2 = esperarLectura(id2, token, "");
        assertThat(linea(d2, "Yog Lala Beb Mango").get("producto").get("origen").asText()).isEqualTo("APRENDIDO");
        assertThat(d2.get("avisoRepetido").asText()).contains("ya se registró");

        // Buscador de productos
        JsonNode b = get("/api/productos/buscar?q=mango", token);
        assertThat(b.get("productos").toString()).contains("Yog Lala Beb Mango");

        // Un ticket mandado a la computadora queda REGISTRADO al darle entrada allá (una sola vez)
        long pid = JSON.readTree(json("POST", "/api/tickets-proveedor/pendiente", Map.of("id", id2), token).body()).get("pendienteId").asLong();
        assertThat(porRevisar.marcarRegistrado(pid, res.get("registroId").asLong())).isTrue();
        assertThat(porRevisar.marcarRegistrado(pid, res.get("registroId").asLong())).isFalse();
        TicketPorRevisar cerrado = porRevisarRepo.findById(pid).orElseThrow();
        assertThat(cerrado.getEstado()).isEqualTo(TicketPorRevisar.Estado.REGISTRADO);
        assertThat(cerrado.getRegistroId()).isEqualTo(res.get("registroId").asLong());
    }

    /** Dos «Registrar entrada» a la vez (doble toque, red lenta) meten la mercancía UNA vez; un intento fallido no aparta el ticket */
    @Test
    void registrarDosVecesALaVezSoloDaEntradaUnaVez() throws Exception {
        Proveedor lala = proveedorService.listarActivos().stream().filter(p -> p.getNombre().equals("Lala")).findFirst()
                .orElseGet(() -> proveedorService.guardar(null, "Lala", null, null, null, Set.of(), null));
        Producto mango = producto("Yog Lala Beb Mango 220 gr", 13.00, 18.00);
        String token = usuario(Permiso.AJUSTAR_INVENTARIO, Permiso.VER_GANANCIAS);
        String id = empezarLectura(token);
        JsonNode d = esperarLectura(id, token, "");
        JsonNode lm = linea(d, "Yog Lala Beb Mango");
        List<Map<String, Object>> renglones = List.of(
                Map.of("indice", lm.get("indice").asInt(), "productoId", mango.getId(), "cantidad", 3, "piezasPorUnidad", 1, "importe", 43.20));

        // Sin proveedor falla en el servicio: el ticket NO queda apartado y se puede volver a intentar
        HttpResponse<String> mal = json("POST", "/api/tickets-proveedor/registrar",
                Map.of("id", id, "ticket", 0, "renglones", renglones), token);
        assertThat(mal.statusCode()).as(mal.body()).isEqualTo(400);

        Map<String, Object> cuerpo = Map.of("id", id, "ticket", 0, "proveedorId", lala.getId(), "folio", "",
                "registrarAunqueSeRepita", true, "renglones", renglones);
        java.util.concurrent.ExecutorService hilos = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch salida = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> f = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                f.add(hilos.submit(() -> {
                    salida.await();
                    return json("POST", "/api/tickets-proveedor/registrar", cuerpo, token).statusCode();
                }));
            }
            salida.countDown();
            List<Integer> estados = new java.util.ArrayList<>();
            for (var x : f) estados.add(x.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(estados).as("uno entra; el otro: ya registrado (400) u ocupado (503)").containsOnlyOnce(200);
        } finally {
            hilos.shutdownNow();
        }
        assertThat(productoRepo.findById(mango.getId()).orElseThrow().getCantidadActual()).isEqualTo(3.0);
    }

    @Test
    void permisosDelLectorYDeLosPrecios() throws Exception {
        String cajero = usuario();
        String otro = usuario(Permiso.AJUSTAR_INVENTARIO);
        assertThat(imagen("/api/tickets-proveedor/leer", foto, "image/jpeg", null).statusCode()).isEqualTo(401);
        assertThat(json("GET", "/api/tickets-proveedor/estado", null, null).statusCode()).isEqualTo(401);

        JsonNode est = get("/api/tickets-proveedor/estado", cajero);
        assertThat(est.get("puedeRegistrar").asBoolean()).isFalse();
        assertThat(est.get("puedeCambiarPrecios").asBoolean()).isFalse();
        assertThat(est.get("costosVisibles").asBoolean()).isFalse();

        // Leer sí puede cualquiera; registrar no
        String id = empezarLectura(cajero);
        JsonNode d = esperarLectura(id, cajero, "");
        assertThat(d.get("lineas").get(0).has("costoPorPieza")).as("sin permiso no ve costos").isFalse();
        HttpResponse<String> r = json("POST", "/api/tickets-proveedor/registrar", Map.of("id", id, "renglones", List.of()), cajero);
        assertThat(r.statusCode()).isEqualTo(403);
        // La lectura de otro usuario no se ve ni se registra
        HttpResponse<String> ajena = json("GET", "/api/tickets-proveedor/lectura?id=" + id, null, otro);
        assertThat(ajena.statusCode()).isEqualTo(400);
        HttpResponse<String> reg = json("POST", "/api/tickets-proveedor/registrar",
                Map.of("id", id, "renglones", List.of(Map.of("indice", 0, "productoId", 1, "cantidad", 1, "piezasPorUnidad", 1, "importe", 1))), otro);
        assertThat(reg.statusCode()).isEqualTo(400);
        // Precios: hace falta EDITAR_PRODUCTOS y CAMBIAR_PRECIOS
        assertThat(json("POST", "/api/precios/actualizar", Map.of("precios", List.of()), otro).statusCode()).isEqualTo(403);
        String soloEditar = usuario(Permiso.EDITAR_PRODUCTOS);
        assertThat(json("POST", "/api/precios/actualizar", Map.of("precios", List.of()), soloEditar).statusCode()).isEqualTo(403);
    }

    @Test
    void tamanoYTipoDeLaFoto() throws Exception {
        String token = usuario();
        byte[] grande = new byte[TicketsPorRevisarService.MAX_FOTO_BYTES + 1];
        System.arraycopy(foto, 0, grande, 0, foto.length);
        assertThat(imagen("/api/tickets-proveedor/leer", grande, "image/jpeg", token).statusCode()).isEqualTo(413);
        assertThat(imagen("/api/tickets-proveedor/leer", "no soy una foto, soy texto".getBytes(), "image/jpeg", token).statusCode()).isEqualTo(415);
        assertThat(imagen("/api/tickets-proveedor/leer", foto, "text/plain", token).statusCode()).isEqualTo(415);
        assertThat(imagen("/api/tickets-proveedor/pendiente", "texto".getBytes(), "image/png", token).statusCode()).isEqualTo(415);
    }

    @Test
    void cupoDeLecturasYCancelar() throws Exception {
        espera = new CountDownLatch(1);
        String token = usuario();
        String a = empezarLectura(token);
        String b = empezarLectura(token);
        HttpResponse<String> tercera = imagen("/api/tickets-proveedor/leer", foto, "image/jpeg", token);
        assertThat(tercera.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(tercera.body()).get("codigo").asText()).isEqualTo("OCUPADO");
        assertThat(tercera.headers().firstValue("Retry-After")).isPresent();
        assertThat(get("/api/tickets-proveedor/lectura?id=" + a, token).get("estado").asText()).isEqualTo("LEYENDO");

        // Cancelar libera el lugar en el acto
        assertThat(JSON.readTree(json("POST", "/api/tickets-proveedor/cancelar", Map.of("id", a), token).body()).get("ok").asBoolean()).isTrue();
        assertThat(json("GET", "/api/tickets-proveedor/lectura?id=" + a, null, token).statusCode()).isEqualTo(400);
        String c = empezarLectura(token);
        espera.countDown();
        assertThat(esperarLectura(b, token, "").get("estado").asText()).isEqualTo("LISTO");
        assertThat(esperarLectura(c, token, "").get("estado").asText()).isEqualTo("LISTO");
    }

    @Test
    void mandarALaComputadoraYRevisarAlla() throws Exception {
        long antes = porRevisar.cuantosPendientes();
        String cajero = usuario();
        String id = empezarLectura(cajero);
        esperarLectura(id, cajero, "");
        HttpResponse<String> r = json("POST", "/api/tickets-proveedor/pendiente", Map.of("id", id, "nota", "Lo de Lala"), cajero);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        long pendienteId = JSON.readTree(r.body()).get("pendienteId").asLong();
        // Mandarlo dos veces no lo duplica
        assertThat(JSON.readTree(json("POST", "/api/tickets-proveedor/pendiente", Map.of("id", id), cajero).body())
                .get("pendienteId").asLong()).isEqualTo(pendienteId);
        assertThat(porRevisar.cuantosPendientes()).isEqualTo(antes + 1);
        assertThat(get("/api/tickets-proveedor/estado", cajero).get("porRevisar").asLong()).isEqualTo(antes + 1);
        assertThat(get("/api/avisos", cajero).toString()).contains("tickets-por-revisar");

        // En la computadora: la foto y lo leído, interpretado otra vez
        var p = porRevisar.obtener(pendienteId);
        assertThat(p.foto()).isEqualTo(foto);
        assertThat(p.nota()).isEqualTo("Lo de Lala");
        assertThat(TicketsPorRevisarService.interpretar(p).get(0).getLineas()).hasSize(8);
        assertThat(porRevisar.pendientes()).extracting(TicketPorRevisarRepository.Resumen::id).contains(pendienteId);

        // Descartar (el admin de escritorio tiene AJUSTAR_INVENTARIO)
        assertThat(porRevisar.descartar(pendienteId)).isTrue();
        assertThat(porRevisar.descartar(pendienteId)).as("ya estaba cerrado").isFalse();
        assertThat(porRevisarRepo.findById(pendienteId).orElseThrow().getEstado()).isEqualTo(TicketPorRevisar.Estado.DESCARTADO);
        assertThat(porRevisar.cuantosPendientes()).isEqualTo(antes);
    }

    @Test
    void sinLectorSeOfreceMandarLaFoto() throws Exception {
        disponible = false;
        String token = usuario();
        JsonNode est = get("/api/tickets-proveedor/estado", token);
        assertThat(est.get("puedeLeerFotos").asBoolean()).isFalse();
        assertThat(est.get("motivoSinLector").asText()).contains("todavía no puede leer fotos");
        HttpResponse<String> r = imagen("/api/tickets-proveedor/leer", foto, "image/jpeg", token);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(r.body()).get("codigo").asText()).isEqualTo("SIN_LECTOR");

        HttpResponse<String> q = imagen("/api/tickets-proveedor/pendiente?nota=sin%20leer", foto, "image/jpeg", token);
        assertThat(q.statusCode()).as(q.body()).isEqualTo(200);
        long pid = JSON.readTree(q.body()).get("pendienteId").asLong();
        var p = porRevisar.obtener(pid);
        assertThat(p.lectura()).isNull();
        assertThat(TicketsPorRevisarService.interpretar(p)).isEmpty();
        // Al registrar la entrada en la computadora queda REGISTRADO (aquí: se marca con un registro existente)
        assertThat(porRevisar.descartar(pid)).isTrue();
    }

    // ── Proveedor nuevo desde el ticket ───────────────────────────────────

    @Test
    void daDeAltaAlProveedorDelTicketYLoEligeSinDuplicar() throws Exception {
        String token = usuario(Permiso.AJUSTAR_INVENTARIO, Permiso.GESTIONAR_PROVEEDORES);
        assertThat(get("/api/tickets-proveedor/estado", token).get("puedeCrearProveedores").asBoolean()).isTrue();
        String nombre = "Lácteos Nuevos " + UUID.randomUUID().toString().substring(0, 6);

        HttpResponse<String> r = json("POST", "/api/tickets-proveedor/proveedor",
                Map.of("nombre", nombre, "rfc", "lan 010101 ab1", "telefono", "282 141 6799"), token);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode c = JSON.readTree(r.body());
        assertThat(c.get("creado").asBoolean()).isTrue();
        long id = c.get("proveedor").get("id").asLong();
        Proveedor guardado = proveedorService.obtener(id);
        assertThat(guardado.getRfc()).isEqualTo("LAN010101AB1");
        assertThat(guardado.getTelefono()).isEqualTo("282 141 6799");

        // Mismo nombre con otras mayúsculas y acentos, o mismo RFC: no se crea otro, se ofrece el que ya hay
        for (Map<String, Object> cuerpo : List.of(
                Map.<String, Object>of("nombre", nombre.toUpperCase().replace("Á", "A")),
                Map.<String, Object>of("nombre", "Otro nombre distinto", "rfc", "LAN010101AB1"))) {
            JsonNode d = JSON.readTree(json("POST", "/api/tickets-proveedor/proveedor", cuerpo, token).body());
            assertThat(d.get("creado").asBoolean()).isFalse();
            assertThat(d.get("proveedor").get("id").asLong()).isEqualTo(id);
        }
        assertThat(proveedorService.listarTodos().stream().filter(p -> p.getNombre().equals("Otro nombre distinto"))).isEmpty();

        // Aparece en la lista del ticket y se puede elegir en la revisión
        assertThat(get("/api/tickets-proveedor/estado", token).get("proveedores").toString()).contains(nombre);
        JsonNode d = esperarLectura(empezarLectura(token), token, "&proveedorId=" + id);
        assertThat(d.get("proveedorId").asLong()).isEqualTo(id);
        assertThat(d.get("motivoProveedor").asText()).contains("elegido");
    }

    @Test
    void elAltaDeProveedorDelTicketValidaYPideElPermiso() throws Exception {
        String sin = usuario(Permiso.AJUSTAR_INVENTARIO);
        assertThat(get("/api/tickets-proveedor/estado", sin).get("puedeCrearProveedores").asBoolean()).isFalse();
        HttpResponse<String> r = json("POST", "/api/tickets-proveedor/proveedor", Map.of("nombre", "Sin Permiso SA"), sin);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(proveedorService.listarTodos().stream().filter(p -> p.getNombre().equals("Sin Permiso SA"))).isEmpty();
        assertThat(json("POST", "/api/tickets-proveedor/proveedor", Map.of("nombre", "X"), null).statusCode()).isEqualTo(401);

        String con = usuario(Permiso.GESTIONAR_PROVEEDORES);
        assertThat(json("POST", "/api/tickets-proveedor/proveedor", Map.of("nombre", "   "), con).statusCode()).isEqualTo(400);
        assertThat(json("POST", "/api/tickets-proveedor/proveedor", Map.of("nombre", "Bueno", "rfc", "123"), con).statusCode()).isEqualTo(400);
        assertThat(json("POST", "/api/tickets-proveedor/proveedor", Map.of("nombre", "Bueno", "telefono", "abc<script>"), con).statusCode()).isEqualTo(400);
        assertThat(json("POST", "/api/tickets-proveedor/proveedor", Map.of("nombre", "x".repeat(200)), con).statusCode()).isEqualTo(400);
        assertThat(json("GET", "/api/tickets-proveedor/proveedor", null, con).statusCode()).isEqualTo(405);
    }

    @Test
    void laRevisionSugiereElRfcDelTicketCuandoElProveedorNoExiste() {
        var t = new me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketLeido();
        t.setEncabezado(new ArrayList<>(List.of("DISTRIBUIDORA LA ESPIGA")));
        t.setRfcs(new ArrayList<>(List.of("DES 010203 AB4")));
        var s = me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService.sugerenciaProveedorNuevo(t);
        assertThat(s.nombre()).isEqualTo("DISTRIBUIDORA LA ESPIGA");
        assertThat(s.rfc()).isEqualTo("DES010203AB4");
    }
}
