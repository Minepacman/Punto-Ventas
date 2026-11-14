package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.service.AuditoriaService;
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
 * Acceso móvil: agenda de proveedores (/api/proveedores) para todos los usuarios, nota media solo
 * para quien ve costos, y alta/edición de días de entrega y preventa (/api/proveedores/guardar)
 * solo con GESTIONAR_PROVEEDORES.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilProveedoresTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProveedorService proveedorService;
    @Autowired private ProveedorRepository proveedorRepo;
    @Autowired private ProductoService productoService;
    @Autowired private AuditoriaService auditoriaService;

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
    }

    @AfterEach
    void detener() {
        servidor.detener();
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private record Usuario(String login, String token) { }

    private Usuario usuario(Permiso... permisos) throws Exception {
        String login = "mp" + UUID.randomUUID().toString().substring(0, 8);
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

    private JsonNode agenda(String token) throws Exception {
        HttpResponse<String> r = enviar("GET", "/api/proveedores", null, token);
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static JsonNode proveedorEn(JsonNode agenda, long id) {
        for (JsonNode p : agenda.get("proveedores")) if (p.get("id").asLong() == id) return p;
        throw new AssertionError("El proveedor " + id + " no está en la agenda");
    }

    private static List<Integer> enteros(JsonNode lista) {
        List<Integer> r = new ArrayList<>();
        for (JsonNode n : lista) r.add(n.asInt());
        return r;
    }

    private Producto producto(String nombre) {
        Producto p = new Producto();
        p.setCodigoBarras("MP-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioCosto(5);
        p.setPrecioVenta(10);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        p.setUsaInventario(true);
        p.setCantidadActual(0);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    private JsonNode secciones(String token) throws Exception {
        HttpResponse<String> r = enviar("GET", "/api/sesion", null, token);
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body()).get("secciones");
    }

    // ── Consulta ──────────────────────────────────────────────────────────

    @Test
    void todosVenLaAgendaYSoloQuienVeCostosRecibeLaNotaMedia() throws Exception {
        LocalDate hoy = LocalDate.now();
        DayOfWeek diaHoy = hoy.getDayOfWeek();
        String nombre = "Bimbo móvil " + UUID.randomUUID().toString().substring(0, 6);
        Proveedor bimbo = proveedorService.guardar(null, nombre, null, "55 1234 5678", "Don Luis",
                EnumSet.of(diaHoy, hoy.plusDays(3).getDayOfWeek()), EnumSet.of(hoy.plusDays(1).getDayOfWeek()), "Pedidos antes de las 5");
        Producto pan = producto("Pan móvil");
        productoService.registrarEntradaDeProveedor(pan.getId(), 10, bimbo.getId(), null, 12.5, null);   // nota de 125

        assertThat(enviar("GET", "/api/proveedores", null, null).statusCode()).isEqualTo(401);
        assertThat(enviar("GET", "/api/proveedores", null, "token-falso").statusCode()).isEqualTo(401);

        // Cajero sin permisos: ve días, teléfono y próxima visita; nada de costos
        Usuario cajero = usuario();
        JsonNode sec = secciones(cajero.token());
        assertThat(sec.get("proveedores").asBoolean()).isTrue();
        assertThat(sec.get("proveedoresEditar").asBoolean()).isFalse();
        assertThat(sec.get("proveedoresCostos").asBoolean()).isFalse();

        JsonNode a = agenda(cajero.token());
        assertThat(a.get("hoy").asText()).isEqualTo(hoy.toString());
        assertThat(a.get("diaSemanaHoy").asInt()).isEqualTo(diaHoy.getValue());
        assertThat(a.get("costosVisibles").asBoolean()).isFalse();
        assertThat(a.get("puedeEditar").asBoolean()).isFalse();
        JsonNode p = proveedorEn(a, bimbo.getId());
        assertThat(p.get("nombre").asText()).isEqualTo(nombre);
        assertThat(p.get("telefono").asText()).isEqualTo("55 1234 5678");
        assertThat(p.get("contacto").asText()).isEqualTo("Don Luis");
        assertThat(enteros(p.get("diasEntrega"))).containsExactlyInAnyOrder(diaHoy.getValue(), hoy.plusDays(3).getDayOfWeek().getValue());
        assertThat(enteros(p.get("diasPreventa"))).containsExactly(hoy.plusDays(1).getDayOfWeek().getValue());
        assertThat(p.get("proximaEntrega").asText()).isEqualTo(hoy.toString());
        assertThat(p.get("proximaPreventa").asText()).isEqualTo(hoy.plusDays(1).toString());
        assertThat(p.has("notaMedia")).as("sin permiso ni siquiera aparece el campo").isFalse();
        assertThat(a.toString()).doesNotContain("125.0");

        // Con VER_GANANCIAS ve la nota media, pero no edita
        Usuario ganancias = usuario(Permiso.VER_GANANCIAS);
        JsonNode g = agenda(ganancias.token());
        assertThat(g.get("costosVisibles").asBoolean()).isTrue();
        assertThat(g.get("puedeEditar").asBoolean()).isFalse();
        JsonNode nota = proveedorEn(g, bimbo.getId()).get("notaMedia");
        assertThat(nota.get("promedio").asDouble()).isEqualTo(125.0);
        assertThat(nota.get("notas").asInt()).isEqualTo(1);
        assertThat(nota.get("ultima").asText()).isEqualTo(hoy.toString());
        assertThat(secciones(ganancias.token()).get("proveedoresCostos").asBoolean()).isTrue();

        // Con GESTIONAR_PROVEEDORES ve costos y edita; un proveedor sin compras trae notaMedia null ("sin datos aún")
        Proveedor nuevo = proveedorService.guardar(null, "Sin compras móvil " + UUID.randomUUID().toString().substring(0, 6),
                null, null, null, EnumSet.of(DayOfWeek.MONDAY), Set.of(), null);
        Usuario gestor = usuario(Permiso.GESTIONAR_PROVEEDORES);
        JsonNode ge = agenda(gestor.token());
        assertThat(ge.get("costosVisibles").asBoolean()).isTrue();
        assertThat(ge.get("puedeEditar").asBoolean()).isTrue();
        JsonNode sinCompras = proveedorEn(ge, nuevo.getId());
        assertThat(sinCompras.has("notaMedia")).isTrue();
        assertThat(sinCompras.get("notaMedia").isNull()).isTrue();
        assertThat(sinCompras.get("proximaPreventa").isNull()).isTrue();
        assertThat(secciones(gestor.token()).get("proveedoresEditar").asBoolean()).isTrue();

        // Un proveedor desactivado no sale en la agenda
        proveedorService.cambiarActivo(nuevo.getId(), false);
        JsonNode despues = agenda(gestor.token());
        for (JsonNode x : despues.get("proveedores")) assertThat(x.get("id").asLong()).isNotEqualTo(nuevo.getId());
    }

    // ── Alta y edición ────────────────────────────────────────────────────

    @Test
    void soloConGestionarProveedoresSeCreanYEditanDesdeElMovil() throws Exception {
        String nombre = "Sabritas móvil " + UUID.randomUUID().toString().substring(0, 6);
        String cuerpo = JSON.writeValueAsString(Map.of("nombre", nombre, "telefono", "282 141 6799", "contacto", "Preventista Paco",
                "diasEntrega", List.of(4, 1), "diasPreventa", List.of(2), "notas", "Levanta pedido martes"));

        assertThat(enviar("POST", "/api/proveedores/guardar", cuerpo, null).statusCode()).isEqualTo(401);
        Usuario cajero = usuario();
        HttpResponse<String> prohibido = enviar("POST", "/api/proveedores/guardar", cuerpo, cajero.token());
        assertThat(prohibido.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(prohibido.body()).get("codigo").asText()).isEqualTo("SIN_PERMISO");
        assertThat(proveedorRepo.findByNombreIgnoreCase(nombre)).as("no se creó nada").isEmpty();

        Usuario gestor = usuario(Permiso.GESTIONAR_PROVEEDORES);
        assertThat(enviar("GET", "/api/proveedores/guardar", null, gestor.token()).statusCode()).isEqualTo(405);

        HttpResponse<String> creado = enviar("POST", "/api/proveedores/guardar", cuerpo, gestor.token());
        assertThat(creado.statusCode()).isEqualTo(200);
        JsonNode rc = JSON.readTree(creado.body());
        assertThat(rc.get("ok").asBoolean()).isTrue();
        assertThat(rc.get("creado").asBoolean()).isTrue();
        assertThat(rc.get("proveedor").get("nombre").asText()).isEqualTo(nombre);
        assertThat(enteros(rc.get("proveedor").get("diasEntrega"))).as("ordenados lunes → domingo").containsExactly(1, 4);
        assertThat(enteros(rc.get("proveedor").get("diasPreventa"))).containsExactly(2);
        assertThat(rc.get("proveedor").get("etiquetaEntrega").asText()).isEqualTo("Lun, Jue");
        assertThat(rc.get("proveedor").get("etiquetaPreventa").asText()).isEqualTo("Mar");
        long id = rc.get("proveedor").get("id").asLong();

        Proveedor enBd = proveedorRepo.findById(id).orElseThrow();
        assertThat(enBd.getDias()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
        assertThat(enBd.getPreventa()).containsExactly(DayOfWeek.TUESDAY);
        assertThat(enBd.getTelefono()).isEqualTo("282 141 6799");
        assertThat(enBd.getNotas()).isEqualTo("Levanta pedido martes");
        assertThat(auditoriaService.ultimas()).anySatisfy(x -> {
            assertThat(x.getAccion()).isEqualTo("PROVEEDOR_CREADO");
            assertThat(x.getUsuarioLogin()).as("auditado como el usuario MÓVIL, no como el admin del escritorio").isEqualTo(gestor.login());
            assertThat(x.getDetalle()).contains(nombre).contains("preventa=Mar");
        });

        // Editar: cambia días, conserva el RFC capturado en el escritorio (el móvil no lo maneja)
        proveedorService.guardar(id, nombre, "SAB840101AB1", "282 141 6799", "Preventista Paco", EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                EnumSet.of(DayOfWeek.TUESDAY), "Levanta pedido martes");
        HttpResponse<String> editado = enviar("POST", "/api/proveedores/guardar", JSON.writeValueAsString(Map.of(
                "id", id, "nombre", nombre, "telefono", "", "contacto", "", "diasEntrega", List.of(5), "diasPreventa", List.of(), "notas", "")), gestor.token());
        assertThat(editado.statusCode()).isEqualTo(200);
        JsonNode re = JSON.readTree(editado.body());
        assertThat(re.get("creado").asBoolean()).isFalse();
        assertThat(enteros(re.get("proveedor").get("diasEntrega"))).containsExactly(5);
        assertThat(enteros(re.get("proveedor").get("diasPreventa"))).isEmpty();
        Proveedor tras = proveedorRepo.findById(id).orElseThrow();
        assertThat(tras.getDias()).containsExactly(DayOfWeek.FRIDAY);
        assertThat(tras.getPreventa()).isEmpty();
        assertThat(tras.getRfc()).as("el RFC no se pierde al editar desde el móvil").isEqualTo("SAB840101AB1");
        assertThat(tras.getTelefono()).isNull();
        assertThat(auditoriaService.ultimas()).anySatisfy(x -> {
            assertThat(x.getAccion()).isEqualTo("PROVEEDOR_EDITADO");
            assertThat(x.getUsuarioLogin()).isEqualTo(gestor.login());
        });

        // El cajero sigue viendo lo que cambió el gestor
        JsonNode visto = proveedorEn(agenda(cajero.token()), id);
        assertThat(enteros(visto.get("diasEntrega"))).containsExactly(5);
    }

    @Test
    void elGuardadoMovilValidaElCuerpo() throws Exception {
        Usuario gestor = usuario(Permiso.GESTIONAR_PROVEEDORES);
        long antes = proveedorRepo.count();

        record Caso(String cuerpo, int estado, String codigo) { }
        List<Caso> casos = List.of(
                new Caso("{\"nombre\":\"   \",\"diasEntrega\":[1]}", 400, "NEGOCIO"),                       // nombre obligatorio
                new Caso("{\"nombre\":123}", 400, "SOLICITUD_INVALIDA"),                                   // tipo incorrecto
                new Caso("{\"nombre\":\"X\",\"diasEntrega\":[8]}", 400, "SOLICITUD_INVALIDA"),              // día fuera de 1..7
                new Caso("{\"nombre\":\"X\",\"diasEntrega\":[0]}", 400, "SOLICITUD_INVALIDA"),
                new Caso("{\"nombre\":\"X\",\"diasEntrega\":\"lunes\"}", 400, "SOLICITUD_INVALIDA"),        // no es lista
                new Caso("{\"nombre\":\"X\",\"diasPreventa\":[1.5]}", 400, "SOLICITUD_INVALIDA"),           // no entero
                new Caso("{\"nombre\":\"X\",\"diasPreventa\":[1,2,3,4,5,6,7,1]}", 400, "SOLICITUD_INVALIDA"),
                new Caso("{\"id\":0,\"nombre\":\"X\"}", 400, "SOLICITUD_INVALIDA"),                          // id inválido
                new Caso("{\"id\":\"abc\",\"nombre\":\"X\"}", 400, "SOLICITUD_INVALIDA"),
                new Caso("{\"id\":987654321,\"nombre\":\"X\"}", 400, "NEGOCIO"),                             // no existe
                new Caso("{\"nombre\":\"X\",\"telefono\":\"abc<script>\"}", 400, "NEGOCIO"),                // teléfono inválido
                new Caso("{\"nombre\":\"" + "n".repeat(151) + "\"}", 400, "SOLICITUD_INVALIDA"),           // largo acotado aquí
                new Caso("no es json", 400, "SOLICITUD_INVALIDA"));
        for (Caso c : casos) {
            HttpResponse<String> r = enviar("POST", "/api/proveedores/guardar", c.cuerpo(), gestor.token());
            assertThat(r.statusCode()).as(c.cuerpo()).isEqualTo(c.estado());
            assertThat(JSON.readTree(r.body()).get("codigo").asText()).as(c.cuerpo()).isEqualTo(c.codigo());
        }
        assertThat(proveedorRepo.count()).as("ningún caso inválido creó un proveedor").isEqualTo(antes);

        // Duplicado (sin importar mayúsculas ni acentos) también se rechaza
        String nombre = "Lala móvil " + UUID.randomUUID().toString().substring(0, 6);
        assertThat(enviar("POST", "/api/proveedores/guardar", JSON.writeValueAsString(Map.of("nombre", nombre)), gestor.token()).statusCode()).isEqualTo(200);
        HttpResponse<String> dup = enviar("POST", "/api/proveedores/guardar", JSON.writeValueAsString(Map.of("nombre", nombre.toUpperCase())), gestor.token());
        assertThat(dup.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(dup.body()).get("error").asText()).contains("Ya existe");
    }

    @Test
    void laPaginaTieneLaSeccionDeProveedoresConCalendarioYSinEmojis() throws Exception {
        HttpResponse<String> r = enviar("GET", "/", null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        String html = r.body();
        assertThat(html).contains("id: 'proveedores'").contains("/api/proveedores").contains("/api/proveedores/guardar")
                .contains("vistaProveedores").contains("Esta semana").contains("Preventa").contains("Nota media")
                .contains("camion:").contains("pedido:").contains("telefono:");
        assertThat(html.codePoints().anyMatch(cp -> cp >= 0x1F300 && cp <= 0x1FAFF)).as("sin emojis").isFalse();
    }
}
