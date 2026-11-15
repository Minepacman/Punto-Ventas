package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceso móvil: avisos de la campana (/api/avisos) para cualquier usuario con sesión;
 * los importes (nota media del proveedor) solo para quien ve costos; la página trae la
 * campana, su hoja y el paso del recorrido.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilAvisosTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProveedorService proveedorService;
    @Autowired private ProductoService productoService;
    @Autowired private AvisosService avisosService;

    private final HttpClient http = HttpClient.newHttpClient();
    private String base;

    @BeforeEach
    void arrancar() throws Exception {
        servidor.iniciar(0);
        base = "http://127.0.0.1:" + servidor.getPuerto();
        Cajero a = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        a.setDebeCambiarContrasena(false);
        a.setBloqueadoHasta(null);
        a.setIntentosFallidos(0);
        cajeroRepo.save(a);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");     // el escritorio es el admin: crea datos de prueba
        avisosService.invalidarCache();
    }

    @AfterEach
    void detener() {
        servidor.detener();
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private record Usuario(String login, String token) { }

    private Usuario usuario(Permiso... permisos) throws Exception {
        String login = "ma" + UUID.randomUUID().toString().substring(0, 8);
        Set<Permiso> set = permisos.length == 0 ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(List.of(permisos));
        Cajero c = cajeroService.crear(login, "Móvil " + login, CLAVE, Cajero.Rol.CAJERO, set);
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        HttpResponse<String> resp = enviar("POST", "/api/login",
                JSON.writeValueAsString(Map.of("usuario", login, "contrasena", CLAVE, "recordar", false)), null);
        assertThat(resp.statusCode()).isEqualTo(200);
        return new Usuario(login, JSON.readTree(resp.body()).get("token").asText());
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String cuerpoJson, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (cuerpoJson != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpoJson == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpoJson));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode avisos(String token) throws Exception {
        HttpResponse<String> r = enviar("GET", "/api/avisos", null, token);
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static List<JsonNode> lista(JsonNode avisos) {
        List<JsonNode> r = new ArrayList<>();
        for (JsonNode a : avisos.get("avisos")) r.add(a);
        return r;
    }

    private static JsonNode conTitulo(JsonNode avisos, String titulo) {
        for (JsonNode a : avisos.get("avisos")) if (a.get("titulo").asText().equals(titulo)) return a;
        throw new AssertionError("No está el aviso \"" + titulo + "\" en " + avisos);
    }

    private Producto producto(String nombre, double stock, double minimo) {
        Producto p = new Producto();
        p.setCodigoBarras("MA-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioCosto(5);
        p.setPrecioVenta(10);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(minimo);
        return productoService.guardar(p);
    }

    // ── Endpoint ──────────────────────────────────────────────────────────

    @Test
    void todosRecibenSusAvisosYSoloQuienVeCostosRecibeImportes() throws Exception {
        LocalDate hoy = LocalDate.now();
        DayOfWeek dHoy = hoy.getDayOfWeek(), dManana = hoy.plusDays(1).getDayOfWeek();
        String s = UUID.randomUUID().toString().substring(0, 6);
        Proveedor bimbo = proveedorService.guardar(null, "Bimbo avisos " + s, null, "55 1234 5678", "Don Luis", EnumSet.of(dHoy), Set.of(), null);
        Proveedor sabritas = proveedorService.guardar(null, "Sabritas avisos " + s, null, null, null, EnumSet.of(dManana), EnumSet.of(dHoy), null);
        Producto papas = producto("Papas avisos " + s, 0, 1);
        productoService.registrarEntradaDeProveedor(papas.getId(), 10, sabritas.getId(), null, 12.5, null);   // nota de $125
        Producto jabon = producto("Aceite avisos " + s, 0, 5);   // agotado con mínimo: encabeza los que no tienen ritmo de venta
        avisosService.invalidarCache();

        assertThat(enviar("GET", "/api/avisos", null, null).statusCode()).isEqualTo(401);
        assertThat(enviar("GET", "/api/avisos", null, "token-falso").statusCode()).isEqualTo(401);

        // Cajero sin permisos: proveedores, productos y nada de dinero
        Usuario cajero = usuario();
        assertThat(enviar("POST", "/api/avisos", "{}", cajero.token()).statusCode()).isEqualTo(405);
        JsonNode a = avisos(cajero.token());
        assertThat(a.get("hoy").asText()).isEqualTo(hoy.toString());
        assertThat(a.get("costosVisibles").asBoolean()).isFalse();
        assertThat(a.get("total").asInt()).isEqualTo(lista(a).size()).isGreaterThanOrEqualTo(3);
        assertThat(a.get("antiguedadSegundos").asLong()).isBetween(0L, 60L);

        JsonNode entrega = conTitulo(a, "Hoy entrega " + bimbo.getNombre());
        assertThat(entrega.get("tipo").asText()).isEqualTo("PROVEEDOR");
        assertThat(entrega.get("tipoEtiqueta").asText()).isEqualTo("Proveedores");
        assertThat(entrega.get("prioridad").asInt()).isEqualTo(1);
        assertThat(entrega.get("icono").asText()).isEqualTo("camion");
        assertThat(entrega.get("id").asText()).isEqualTo("prov-e-" + bimbo.getId() + "-hoy");
        assertThat(entrega.get("detalle").asText()).contains("Contacto: Don Luis");
        assertThat(entrega.get("accion").get("seccion").asText()).isEqualTo("proveedores");
        assertThat(entrega.get("accion").get("proveedorId").asLong()).isEqualTo(bimbo.getId());

        JsonNode preventa = conTitulo(a, "Hoy pasa el preventa de " + sabritas.getNombre());
        assertThat(preventa.get("icono").asText()).isEqualTo("pedido");
        assertThat(preventa.get("detalle").asText()).contains("Prepara el pedido").doesNotContain("$");
        conTitulo(a, "Mañana viene " + sabritas.getNombre());

        JsonNode stock = conTitulo(a, jabon.getDescripcion());
        assertThat(stock.get("tipo").asText()).isEqualTo("STOCK");
        assertThat(stock.get("tipoEtiqueta").asText()).isEqualTo("Se está acabando");
        assertThat(stock.get("detalle").asText()).isEqualTo("Agotado.");
        assertThat(stock.get("prioridad").asInt()).isEqualTo(1);
        assertThat(stock.get("accion").get("seccion").asText()).isEqualTo("inventario");
        assertThat(stock.get("accion").get("buscar").asText()).isEqualTo(jabon.getDescripcion());
        assertThat(stock.get("accion").has("proveedorId")).isFalse();
        assertThat(a.toString()).doesNotContain("125.0").doesNotContain("$");

        // Con GESTIONAR_PROVEEDORES aparece la nota media en el aviso de preventa
        Usuario gestor = usuario(Permiso.GESTIONAR_PROVEEDORES);
        JsonNode g = avisos(gestor.token());
        assertThat(g.get("costosVisibles").asBoolean()).isTrue();
        assertThat(conTitulo(g, "Hoy pasa el preventa de " + sabritas.getNombre()).get("detalle").asText()).contains("$125.00");
        // Mismo cálculo de fondo para todos (caché): los mismos ids
        assertThat(lista(g).stream().map(x -> x.get("id").asText()).toList())
                .containsExactlyElementsOf(lista(a).stream().map(x -> x.get("id").asText()).toList());
    }

    @Test
    void laPaginaTieneLaCampanaLaHojaDeAvisosYElPasoDelRecorridoSinEmojis() throws Exception {
        HttpResponse<String> r = enviar("GET", "/", null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        String html = r.body();
        assertThat(html).contains("campana:").contains("reloj:").contains("/api/avisos").contains("id: 'avisos'")
                .contains("class: 'globo").contains("'Avisos'").contains("Descartar todos").contains("pv.avisos.")
                .contains("visibilitychange").contains("Avisos del día").contains("Sin avisos por ahora");
        assertThat(html).as("sin notificaciones del navegador: no hay HTTPS").doesNotContain("Notification.").doesNotContain("serviceWorker");
        assertThat(html.codePoints().anyMatch(cp -> cp >= 0x1F300 && cp <= 0x1FAFF)).as("sin emojis").isFalse();
    }
}
