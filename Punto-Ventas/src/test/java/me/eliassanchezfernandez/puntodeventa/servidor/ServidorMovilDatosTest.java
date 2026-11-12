package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ContextoActor;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Endpoints de datos reales del acceso móvil: servidor real en un puerto libre,
 * HttpClient a 127.0.0.1 y la BD temporal de pruebas. Sin transacción envolvente
 * (el servidor trabaja en otros hilos). La sesión de ESCRITORIO queda abierta
 * como admin durante todo el test para comprobar que el móvil nunca la toca.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilDatosTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProductoService productoService;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private VentaService ventaService;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private AuditoriaRepository auditoriaRepo;

    private final HttpClient http = HttpClient.newHttpClient();
    private String base;
    private Producto producto;

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
        producto = nuevoProducto("Refresco " + UUID.randomUUID().toString().substring(0, 6), 20, 10);
    }

    @AfterEach
    void detener() {
        servidor.detener();
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private Producto nuevoProducto(String nombre, double precio, double stock) {
        Producto p = new Producto();
        p.setCodigoBarras("M-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioCosto(precio / 2);
        p.setPrecioVenta(precio);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(3);
        return productoService.guardar(p);
    }

    private String usuario(Set<Permiso> permisos) {
        String login = "m" + UUID.randomUUID().toString().substring(0, 8);
        Cajero c = cajeroService.crear(login, "Móvil " + login, CLAVE, Cajero.Rol.CAJERO, permisos);
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        return login;
    }

    private record Resp(int estado, JsonNode cuerpo) { }

    private Resp enviar(String metodo, String ruta, Object cuerpo, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (cuerpo != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cuerpo)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = null;
        try { j = JSON.readTree(r.body()); } catch (Exception ignorada) { /* no JSON */ }
        return new Resp(r.statusCode(), j);
    }

    private String token(String login) throws Exception {
        Resp r = enviar("POST", "/api/login", Map.of("usuario", login, "contrasena", CLAVE, "recordar", false), null);
        assertThat(r.estado()).isEqualTo(200);
        return r.cuerpo().get("token").asText();
    }

    private String tokenCon(Permiso... permisos) throws Exception {
        Set<Permiso> set = permisos.length == 0 ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(List.of(permisos));
        return token(usuario(set));
    }

    private Venta venderAhora(Producto p, double cantidad) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, cantidad)), Venta.FormaPago.EFECTIVO, 1000);
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void assertDesktopSigueComoAdmin() {
        assertThat(sesionService.getCajeroActual()).isNotNull();
        assertThat(sesionService.getCajeroActual().getUsuario()).isEqualTo("admin");
        assertThat(ContextoActor.actual()).isNull();
    }

    // ── Estadísticas ──────────────────────────────────────────────────────

    @Test
    void estadisticasDevuelveDatosRealesDelPeriodo() throws Exception {
        Venta v = venderAhora(producto, 2);
        String t = tokenCon(Permiso.VER_HISTORIAL);

        for (String periodo : new String[] {"dia", "semana", "mes"}) {
            Resp r = enviar("GET", "/api/estadisticas?periodo=" + periodo, null, t);
            assertThat(r.estado()).as(periodo).isEqualTo(200);
            assertThat(r.cuerpo().get("periodo").asText()).isEqualTo(periodo);
            assertThat(r.cuerpo().get("totales").get("total").asDouble()).isGreaterThanOrEqualTo(v.getTotal());
            assertThat(r.cuerpo().get("totales").get("transacciones").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(r.cuerpo().get("franjas")).hasSize(5);
            assertThat(r.cuerpo().get("top").isArray()).isTrue();
            assertThat(r.cuerpo().has("comparacion")).isTrue();
        }
        assertDesktopSigueComoAdmin();
    }

    @Test
    void estadisticasExigeSesionPermisoYPeriodoValido() throws Exception {
        assertThat(enviar("GET", "/api/estadisticas?periodo=dia", null, null).estado()).isEqualTo(401);
        assertThat(enviar("GET", "/api/estadisticas?periodo=dia", null, "token-falso").estado()).isEqualTo(401);

        String sinPermiso = tokenCon();
        Resp r = enviar("GET", "/api/estadisticas?periodo=dia", null, sinPermiso);
        assertThat(r.estado()).isEqualTo(403);
        assertThat(r.cuerpo().get("codigo").asText()).isEqualTo("SIN_PERMISO");

        String t = tokenCon(Permiso.VER_HISTORIAL);
        assertThat(enviar("GET", "/api/estadisticas?periodo=siglo", null, t).estado()).isEqualTo(400);
        assertThat(enviar("POST", "/api/estadisticas", Map.of(), t).estado()).isEqualTo(405);
    }

    @Test
    void laGananciaSoloSeMuestraConVerGanancias() throws Exception {
        venderAhora(producto, 1);
        Resp sin = enviar("GET", "/api/estadisticas?periodo=dia", null, tokenCon(Permiso.VER_HISTORIAL));
        assertThat(sin.cuerpo().get("gananciaVisible").asBoolean()).isFalse();
        assertThat(sin.cuerpo().has("ganancia")).isFalse();
        assertThat(sin.cuerpo().toString()).doesNotContain("costo");

        Resp con = enviar("GET", "/api/estadisticas?periodo=dia", null, tokenCon(Permiso.VER_HISTORIAL, Permiso.VER_GANANCIAS));
        assertThat(con.cuerpo().get("gananciaVisible").asBoolean()).isTrue();
        assertThat(con.cuerpo().get("ganancia").asDouble()).isGreaterThan(0);
    }

    // ── Ventas ────────────────────────────────────────────────────────────

    @Test
    void ventasDevuelveFolioCajeroTotalFormaDePagoYLineas() throws Exception {
        Venta v = venderAhora(producto, 3);
        String hoy = LocalDate.now().toString();
        Resp r = enviar("GET", "/api/ventas?desde=" + hoy + "&hasta=" + hoy, null, tokenCon(Permiso.VER_HISTORIAL));

        assertThat(r.estado()).isEqualTo(200);
        JsonNode venta = null;
        for (JsonNode n : r.cuerpo().get("ventas")) if (n.get("id").asLong() == v.getId()) venta = n;
        assertThat(venta).isNotNull();
        assertThat(venta.get("folio").asText()).isEqualTo(v.getFolio());
        assertThat(venta.get("cajero").asText()).isEqualTo("Administrador");
        assertThat(venta.get("total").asDouble()).isEqualTo(v.getTotal());
        assertThat(venta.get("formaPago").asText()).isEqualTo("Efectivo");
        assertThat(venta.get("cancelada").asBoolean()).isFalse();
        assertThat(venta.get("lineas")).hasSize(1);
        JsonNode l = venta.get("lineas").get(0);
        assertThat(l.get("descripcion").asText()).isEqualTo(producto.getDescripcion());
        assertThat(l.get("cantidad").asDouble()).isEqualTo(3);
        assertThat(l.has("costo")).isFalse();                // sin VER_GANANCIAS no hay costos

        Resp conCosto = enviar("GET", "/api/ventas?desde=" + hoy + "&hasta=" + hoy, null,
                tokenCon(Permiso.VER_HISTORIAL, Permiso.VER_GANANCIAS));
        assertThat(conCosto.cuerpo().get("ventas").get(0).get("lineas").get(0).has("costo")).isTrue();
        assertDesktopSigueComoAdmin();
    }

    @Test
    void ventasValidaPermisoYRango() throws Exception {
        assertThat(enviar("GET", "/api/ventas", null, null).estado()).isEqualTo(401);
        assertThat(enviar("GET", "/api/ventas", null, tokenCon()).estado()).isEqualTo(403);

        String t = tokenCon(Permiso.VER_HISTORIAL);
        LocalDate hoy = LocalDate.now();
        // 93 días exactos sí; 94 no
        assertThat(enviar("GET", "/api/ventas?desde=" + hoy.minusDays(92) + "&hasta=" + hoy, null, t).estado()).isEqualTo(200);
        Resp largo = enviar("GET", "/api/ventas?desde=" + hoy.minusDays(93) + "&hasta=" + hoy, null, t);
        assertThat(largo.estado()).isEqualTo(400);
        assertThat(largo.cuerpo().get("error").asText()).contains("93");
        assertThat(enviar("GET", "/api/ventas?desde=ayer&hasta=" + hoy, null, t).estado()).isEqualTo(400);
        assertThat(enviar("GET", "/api/ventas?desde=" + hoy + "&hasta=" + hoy.minusDays(1), null, t).estado()).isEqualTo(400);
        assertThat(enviar("GET", "/api/ventas?desde=%25ZZ", null, t).estado()).isEqualTo(400);
        assertThat(ServidorMovilService.MAX_VENTAS).isEqualTo(500);
    }

    // ── Inventario ────────────────────────────────────────────────────────

    @Test
    void inventarioLoVeCualquierUsuarioSinCostos() throws Exception {
        Producto bajo = nuevoProducto("Galleta " + UUID.randomUUID().toString().substring(0, 6), 15, 1);   // 1 <= mínimo 3
        String t = tokenCon();     // sin ningún permiso

        Resp r = enviar("GET", "/api/inventario?q=" + q(bajo.getDescripcion()), null, t);
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo().get("productos")).hasSize(1);
        JsonNode p = r.cuerpo().get("productos").get(0);
        assertThat(p.get("id").asLong()).isEqualTo(bajo.getId());
        assertThat(p.get("existencia").asDouble()).isEqualTo(1);
        assertThat(p.get("minimo").asDouble()).isEqualTo(3);
        assertThat(p.get("stockBajo").asBoolean()).isTrue();
        assertThat(p.get("unidad").asText()).isNotBlank();
        assertThat(p.has("costo")).isFalse();
        assertThat(r.cuerpo().get("puedeAjustar").asBoolean()).isFalse();

        Resp todos = enviar("GET", "/api/inventario", null, t);
        assertThat(todos.estado()).isEqualTo(200);
        assertThat(todos.cuerpo().get("productos").size()).isLessThanOrEqualTo(ServidorMovilService.MAX_PRODUCTOS);

        Resp conCosto = enviar("GET", "/api/inventario?q=" + q(bajo.getDescripcion()), null, tokenCon(Permiso.VER_GANANCIAS));
        assertThat(conCosto.cuerpo().get("productos").get(0).get("costo").asDouble()).isEqualTo(bajo.getPrecioCosto());

        assertThat(enviar("GET", "/api/inventario", null, null).estado()).isEqualTo(401);
        assertThat(enviar("GET", "/api/inventario?q=" + "x".repeat(61), null, t).estado()).isEqualTo(400);
    }

    // ── Ajuste de inventario y actor móvil ────────────────────────────────

    @Test
    void elAjusteMovilSeAtribuyeAlUsuarioMovilYNoTocaElEscritorio() throws Exception {
        String login = usuario(EnumSet.of(Permiso.AJUSTAR_INVENTARIO));
        String t = token(login);

        Resp r = enviar("POST", "/api/inventario/ajuste", Map.of("productoId", producto.getId(), "tipo", "entrada",
                "cantidad", 5, "motivo", "Reposición", "proveedor", "Proveedor X"), t);

        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cuerpo().get("producto").get("existencia").asDouble()).isEqualTo(15);
        assertThat(productoRepo.findById(producto.getId()).orElseThrow().getCantidadActual()).isEqualTo(15);

        MovimientoInventario mov = movimientoRepo.findByProductoIdOrderByFechaDesc(producto.getId()).stream()
                .filter(m -> m.getTipo() == MovimientoInventario.Tipo.ENTRADA).findFirst().orElseThrow();
        assertThat(mov.getCantidad()).isEqualTo(5);
        assertThat(mov.getUsuarioNombre()).isEqualTo("Móvil " + login);
        Cajero movil = cajeroRepo.findByUsuarioIgnoreCase(login).orElseThrow();
        assertThat(mov.getUsuarioId()).isEqualTo(movil.getId());

        Auditoria aud = auditoriaRepo.findByAccionOrderByFechaDesc("INVENTARIO_MOVIL").stream()
                .filter(a -> login.equals(a.getUsuarioLogin())).findFirst().orElseThrow();
        assertThat(aud.getDetalle()).contains("vía móvil").contains("IP 127.0.0.1").contains("Reposición");
        assertDesktopSigueComoAdmin();
    }

    @Test
    void mermaYAjusteCambianLaExistenciaYValidan() throws Exception {
        String t = tokenCon(Permiso.AJUSTAR_INVENTARIO);
        long id = producto.getId();

        Resp merma = enviar("POST", "/api/inventario/ajuste", Map.of("productoId", id, "tipo", "merma", "cantidad", 4, "motivo", "Caducó"), t);
        assertThat(merma.estado()).isEqualTo(200);
        assertThat(merma.cuerpo().get("producto").get("existencia").asDouble()).isEqualTo(6);

        Resp ajuste = enviar("POST", "/api/inventario/ajuste", Map.of("productoId", id, "tipo", "ajuste", "cantidad", 9, "motivo", "Conteo"), t);
        assertThat(ajuste.cuerpo().get("producto").get("existencia").asDouble()).isEqualTo(9);

        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", id, "tipo", "merma", "cantidad", 99, "motivo", "x"), t).estado()).isEqualTo(400);
        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", id, "tipo", "ajuste", "cantidad", -1, "motivo", "x"), t).estado()).isEqualTo(400);
        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", id, "tipo", "ajuste", "cantidad", 3), t).estado()).isEqualTo(400);   // falta motivo
        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", id, "tipo", "robo", "cantidad", 1, "motivo", "x"), t).estado()).isEqualTo(400);
        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", id, "tipo", "entrada", "cantidad", "mucho", "motivo", "x"), t).estado()).isEqualTo(400);
        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", 999999, "tipo", "entrada", "cantidad", 1, "motivo", "x"), t).estado()).isEqualTo(400);
        assertThat(productoRepo.findById(id).orElseThrow().getCantidadActual()).isEqualTo(9);
    }

    @Test
    void elAjusteSinPermisoDa403YNoCambiaNada() throws Exception {
        String t = tokenCon(Permiso.VER_HISTORIAL);
        Resp r = enviar("POST", "/api/inventario/ajuste", Map.of("productoId", producto.getId(), "tipo", "entrada",
                "cantidad", 5, "motivo", "x"), t);
        assertThat(r.estado()).isEqualTo(403);
        assertThat(productoRepo.findById(producto.getId()).orElseThrow().getCantidadActual()).isEqualTo(10);

        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", producto.getId()), null).estado()).isEqualTo(401);

        // Cambiar el costo exige además VER_GANANCIAS
        String solo = tokenCon(Permiso.AJUSTAR_INVENTARIO);
        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", producto.getId(), "tipo", "entrada",
                "cantidad", 1, "motivo", "x", "costo", 99), solo).estado()).isEqualTo(403);
        assertThat(productoRepo.findById(producto.getId()).orElseThrow().getPrecioCosto()).isEqualTo(10);
    }

    @Test
    void losPermisosSeVuelvenAComprobarEnCadaPeticion() throws Exception {
        String login = usuario(EnumSet.of(Permiso.VER_HISTORIAL, Permiso.AJUSTAR_INVENTARIO));
        String t = token(login);
        assertThat(enviar("GET", "/api/estadisticas?periodo=dia", null, t).estado()).isEqualTo(200);

        // El administrador le quita los permisos con la sesión ya abierta en el teléfono
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(login).orElseThrow();
        cajeroService.actualizar(c.getId(), c.getUsuario(), c.getNombreCompleto(), c.getRol(), true, null,
                EnumSet.noneOf(Permiso.class));

        assertThat(enviar("GET", "/api/estadisticas?periodo=dia", null, t).estado()).isEqualTo(403);
        assertThat(enviar("POST", "/api/inventario/ajuste", Map.of("productoId", producto.getId(), "tipo", "entrada",
                "cantidad", 1, "motivo", "x"), t).estado()).isEqualTo(403);
    }

    @Test
    void elAmbitoDelActorSeLimpiaAunqueHayaUnaExcepcion() {
        Cajero movil = cajeroRepo.findByUsuarioIgnoreCase(usuario(EnumSet.of(Permiso.VER_HISTORIAL))).orElseThrow();

        assertThatThrownBy(() -> sesionService.ejecutarComo(movil, "10.0.0.5", () -> {
            assertThat(sesionService.getCajeroActual().getId()).isEqualTo(movil.getId());
            assertThat(ContextoActor.actual().ip()).isEqualTo("10.0.0.5");
            throw new IllegalStateException("falla a propósito");
        })).isInstanceOf(IllegalStateException.class).hasMessage("falla a propósito");

        assertThat(ContextoActor.actual()).isNull();
        assertDesktopSigueComoAdmin();
    }

    @Test
    void dentroDelAmbitoMovilNoSePuedeTocarLaSesionDeEscritorio() {
        Cajero movil = cajeroRepo.findByUsuarioIgnoreCase(usuario(EnumSet.noneOf(Permiso.class))).orElseThrow();

        sesionService.ejecutarComo(movil, "10.0.0.5", () -> {
            assertThatThrownBy(() -> sesionService.cerrarSesion()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> sesionService.autenticar("admin", "admin123")).isInstanceOf(IllegalStateException.class);
            // y los permisos son los del usuario móvil, no los del administrador de escritorio
            assertThat(sesionService.tienePermiso(Permiso.AJUSTAR_INVENTARIO)).isFalse();
            assertThatThrownBy(() -> sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO))
                    .isInstanceOf(me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException.class);
        });

        assertDesktopSigueComoAdmin();
        assertThat(sesionService.tienePermiso(Permiso.AJUSTAR_INVENTARIO)).isTrue();
    }

    @Test
    void elAmbitoAnidadoRestauraAlActorAnterior() {
        Cajero uno = cajeroRepo.findByUsuarioIgnoreCase(usuario(EnumSet.noneOf(Permiso.class))).orElseThrow();
        Cajero dos = cajeroRepo.findByUsuarioIgnoreCase(usuario(EnumSet.noneOf(Permiso.class))).orElseThrow();
        sesionService.ejecutarComo(uno, "1.1.1.1", () -> {
            sesionService.ejecutarComo(dos, "2.2.2.2", () ->
                    assertThat(sesionService.getCajeroActual().getId()).isEqualTo(dos.getId()));
            assertThat(sesionService.getCajeroActual().getId()).isEqualTo(uno.getId());
        });
        assertThat(ContextoActor.actual()).isNull();
    }

    @Test
    void elUsuarioMovilDesactivadoPierdeElAccesoAlInstante() throws Exception {
        String login = usuario(EnumSet.of(Permiso.VER_HISTORIAL));
        String t = token(login);
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(login).orElseThrow();
        c.setActivo(false);
        cajeroRepo.save(c);
        assertThat(enviar("GET", "/api/estadisticas?periodo=dia", null, t).estado()).isEqualTo(401);
    }
}
