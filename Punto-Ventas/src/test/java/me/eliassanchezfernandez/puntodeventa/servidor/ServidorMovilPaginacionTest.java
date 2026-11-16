package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /api/ventas por páginas (orden, bordes, totales del rango, compatibilidad) y el tope de
 * consultas pesadas simultáneas (503 OCUPADO). Servidor real, BD de pruebas; las ventas van
 * en un día lejano y se borran al terminar.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilPaginacionTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DIA = LocalDate.of(2002, 7, 9);

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProductoService productoService;
    @Autowired private VentaRepository ventaRepo;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Venta> sembradas = new ArrayList<>();
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
        sesionService.autenticar("admin", "admin123");
    }

    @AfterEach
    void detener() {
        servidor.detener();
        ventaRepo.deleteAll(sembradas);
        sembradas.clear();
        sesionService.cerrarSesion();
    }

    private String token(Permiso... permisos) throws Exception {
        String login = "p" + UUID.randomUUID().toString().substring(0, 8);
        Set<Permiso> set = permisos.length == 0 ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(List.of(permisos));
        Cajero c = cajeroService.crear(login, "Móvil " + login, CLAVE, Cajero.Rol.CAJERO, set);
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("usuario", login, "contrasena", CLAVE, "recordar", false)))).build(),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(r.body()).get("token").asText();
    }

    private record Resp(int estado, JsonNode cuerpo, HttpResponse<String> crudo) { }

    private Resp get(String ruta, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = null;
        try { j = JSON.readTree(r.body()); } catch (Exception ignorada) { /* no JSON */ }
        return new Resp(r.statusCode(), j, r);
    }

    private void sembrar(int cuantas, int canceladaCada) {
        Producto p = new Producto();
        p.setCodigoBarras("PG-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion("Paginación " + p.getCodigoBarras());
        p.setPrecioVenta(10);
        p.setPrecioCosto(4);
        p.setUsaInventario(false);
        p = productoService.guardar(p);
        for (int i = 0; i < cuantas; i++) {
            Venta v = new Venta();
            v.setFecha(DIA.atTime(8, 0).plusMinutes(i));
            v.setFolio("PG" + i);
            DetalleVenta d = new DetalleVenta();
            d.setVenta(v);
            d.setProducto(p);
            d.setCantidad(1);
            d.setPrecioVenta(10);
            v.getDetalles().add(d);
            v.setTotal(10);
            v.setPagoCon(10);
            if (canceladaCada > 0 && i % canceladaCada == 0) v.setCancelada(true);
            sembradas.add(ventaRepo.save(v));
        }
    }

    @Test
    void lasVentasLleganPorPaginasSinRepetirNiSaltarseNinguna() throws Exception {
        sembrar(123, 10);                              // 13 canceladas (0, 10, …, 120)
        String t = token(Permiso.VER_HISTORIAL);
        String rango = "/api/ventas?desde=" + DIA + "&hasta=" + DIA;

        // Sin pagina/limite: primera página de 50 (lo que pedía la página anterior sigue sirviendo)
        Resp primera = get(rango, t);
        assertThat(primera.estado()).isEqualTo(200);
        assertThat(primera.cuerpo().get("ventas")).hasSize(ServidorMovilService.VENTAS_POR_PAGINA);
        assertThat(primera.cuerpo().get("hayMas").asBoolean()).isTrue();
        assertThat(primera.cuerpo().get("truncado").asBoolean()).isTrue();
        assertThat(primera.cuerpo().get("total").asLong()).isEqualTo(123);
        JsonNode tot = primera.cuerpo().get("totales");
        assertThat(tot.get("ventas").asLong()).isEqualTo(123);
        assertThat(tot.get("canceladas").asLong()).isEqualTo(13);
        assertThat(tot.get("importe").asDouble()).isEqualTo(1100.0);
        assertThat(primera.cuerpo().get("ventas").get(0).get("folio").asText()).isEqualTo("PG122");   // la más reciente

        List<String> folios = new ArrayList<>();
        int pagina = 0;
        boolean hayMas = true;
        while (hayMas) {
            Resp r = get(rango + "&pagina=" + pagina + "&limite=40", t);
            assertThat(r.estado()).isEqualTo(200);
            assertThat(r.cuerpo().get("pagina").asInt()).isEqualTo(pagina);
            assertThat(r.cuerpo().get("totales").get("ventas").asLong()).isEqualTo(123);
            r.cuerpo().get("ventas").forEach(v -> folios.add(v.get("folio").asText()));
            hayMas = r.cuerpo().get("hayMas").asBoolean();
            pagina++;
        }
        assertThat(pagina).isEqualTo(4);                 // 40 + 40 + 40 + 3
        assertThat(folios).hasSize(123);
        assertThat(new HashSet<>(folios)).hasSize(123);
        for (int i = 0; i < 123; i++) assertThat(folios.get(i)).isEqualTo("PG" + (122 - i));

        // Después de la última: vacía y sin "hay más"
        Resp despues = get(rango + "&pagina=10&limite=40", t);
        assertThat(despues.cuerpo().get("ventas")).isEmpty();
        assertThat(despues.cuerpo().get("hayMas").asBoolean()).isFalse();
    }

    @Test
    void parametrosDePaginaInvalidosDan400() throws Exception {
        String t = token(Permiso.VER_HISTORIAL);
        String rango = "/api/ventas?desde=" + DIA + "&hasta=" + DIA;
        assertThat(get(rango + "&limite=0", t).estado()).isEqualTo(400);
        assertThat(get(rango + "&limite=" + (ServidorMovilService.MAX_VENTAS + 1), t).estado()).isEqualTo(400);
        assertThat(get(rango + "&limite=diez", t).estado()).isEqualTo(400);
        assertThat(get(rango + "&pagina=-1", t).estado()).isEqualTo(400);
        assertThat(get(rango + "&pagina=99999999999", t).estado()).isEqualTo(400);
        assertThat(get(rango + "&limite=" + ServidorMovilService.MAX_VENTAS, t).estado()).isEqualTo(200);
    }

    @Test
    void conLasConsultasPesadasOcupadasLaSiguienteRecibe503YLuegoFunciona() throws Exception {
        String t = token(Permiso.VER_HISTORIAL);
        String rango = "/api/ventas?desde=" + DIA + "&hasta=" + DIA;
        assertThat(servidor.consultasPesadas.tryAcquire(ServidorMovilService.MAX_CONSULTAS_PESADAS)).isTrue();
        try {
            for (String ruta : List.of(rango, "/api/estadisticas?periodo=dia", "/api/inventario")) {
                Resp r = get(ruta, t);
                assertThat(r.estado()).as(ruta).isEqualTo(503);
                assertThat(r.cuerpo().get("codigo").asText()).isEqualTo("OCUPADO");
                assertThat(r.cuerpo().get("error").asText()).isEqualTo("Intenta de nuevo en un momento.");
                assertThat(r.crudo().headers().firstValue("Retry-After")).contains("1");
            }
            // Las ligeras no esperan; sin sesión sigue siendo 401 (no ocupa ni consulta el semáforo)
            assertThat(get("/api/sesion", t).estado()).isEqualTo(200);
            assertThat(get(rango, null).estado()).isEqualTo(401);
        } finally {
            servidor.consultasPesadas.release(ServidorMovilService.MAX_CONSULTAS_PESADAS);
        }
        assertThat(get(rango, t).estado()).isEqualTo(200);

        // Con un solo lugar libre sí pasa, y el lugar se devuelve al terminar (también tras un 403)
        assertThat(servidor.consultasPesadas.tryAcquire()).isTrue();
        try {
            assertThat(get(rango, t).estado()).isEqualTo(200);
            assertThat(get(rango, t).estado()).isEqualTo(200);
        } finally {
            servidor.consultasPesadas.release();
        }
        assertThat(get(rango, token()).estado()).isEqualTo(403);
        assertThat(servidor.consultasPesadas.availablePermits()).isEqualTo(ServidorMovilService.MAX_CONSULTAS_PESADAS);
    }
}
