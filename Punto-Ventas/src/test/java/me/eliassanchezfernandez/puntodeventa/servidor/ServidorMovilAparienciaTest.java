package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AparienciaService;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Apariencia;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Paleta;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Apariencia: guardar (toda la tienda solo el admin; "solo para mí" cualquiera, con bitácora) y lo
 * que recibe la página móvil (/api/apariencia, /api/apariencia/publica, letras y manifiesto).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilAparienciaTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private AparienciaService aparienciaService;
    @Autowired private AuditoriaRepository auditoriaRepo;

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
        sesionService.autenticar("admin", "admin123");
    }

    @AfterEach
    void detener() {
        try {
            sesionService.cerrarSesion();
            sesionService.autenticar("admin", "admin123");
            aparienciaService.guardarTienda(Apariencia.porDefecto());
            aparienciaService.guardarPropia(null);
        } catch (Exception ignorada) { /* limpieza */ }
        servidor.detener();
        sesionService.cerrarSesion();
    }

    private Cajero cajero() {
        String login = "ap" + UUID.randomUUID().toString().substring(0, 8);
        Cajero c = cajeroService.crear(login, "Apariencia " + login, CLAVE, Cajero.Rol.CAJERO, Set.of());
        c.setDebeCambiarContrasena(false);
        return cajeroRepo.save(c);
    }

    private String token(Cajero c) throws Exception {
        HttpResponse<String> r = enviar("POST", "/api/login",
                JSON.writeValueAsString(Map.of("usuario", c.getUsuario(), "contrasena", CLAVE, "recordar", false)), null);
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body()).get("token").asText();
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String cuerpo, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (cuerpo != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void soloElAdminCambiaLaDeLaTiendaYQuedaEnLaBitacora() throws Exception {
        Apariencia oscura = Apariencia.porDefecto().conTema(Apariencia.Tema.OSCURO).conAcento("#2E7D4F");
        aparienciaService.guardarTienda(oscura);
        assertThat(aparienciaService.deTienda()).isEqualTo(oscura);
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc())
                .anyMatch(x -> "APARIENCIA_MODIFICADA".equals(x.getAccion()));

        Cajero c = cajero();
        sesionService.cerrarSesion();
        sesionService.autenticar(c.getUsuario(), CLAVE);
        assertThatThrownBy(() -> aparienciaService.guardarTienda(Apariencia.porDefecto())).isInstanceOf(NegocioException.class);
        assertThat(aparienciaService.deTienda()).isEqualTo(oscura);
    }

    @Test
    void soloParaMiGanaALaDeLaTiendaYSePuedeQuitar() throws Exception {
        Apariencia tienda = Apariencia.porDefecto().conAcento("#6B4BA8");
        aparienciaService.guardarTienda(tienda);
        Cajero c = cajero();
        sesionService.cerrarSesion();
        sesionService.autenticar(c.getUsuario(), CLAVE);
        assertThat(aparienciaService.vigente()).isEqualTo(tienda);
        assertThat(aparienciaService.tienePropia()).isFalse();

        Apariencia grande = tienda.conEscala(130).conLetra(Apariencia.Letra.ATKINSON);
        aparienciaService.guardarPropia(grande);
        assertThat(aparienciaService.vigente()).isEqualTo(grande);
        assertThat(aparienciaService.tienePropia()).isTrue();
        assertThat(aparienciaService.deTienda()).as("la de la tienda no cambia").isEqualTo(tienda);
        assertThat(aparienciaService.paraUsuario(cajeroRepo.findById(c.getId()).orElseThrow())).isEqualTo(grande);
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).anyMatch(x -> "APARIENCIA_PROPIA".equals(x.getAccion()));

        aparienciaService.guardarPropia(null);
        assertThat(aparienciaService.vigente()).isEqualTo(tienda);
        assertThat(aparienciaService.tienePropia()).isFalse();
    }

    @Test
    void laPublicaNoPideSesionYNoTraeIconos() throws Exception {
        aparienciaService.guardarTienda(Apariencia.porDefecto().conTema(Apariencia.Tema.OSCURO).conAcento("#C25E12")
                .conLetra(Apariencia.Letra.INTER).conEscala(115).conVidrio(true).conPaquete("tabler", "contorno"));
        HttpResponse<String> r = enviar("GET", "/api/apariencia/publica", null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode j = JSON.readTree(r.body());
        assertThat(j.get("tema").asText()).isEqualTo("oscuro");
        assertThat(j.get("porDefecto").asBoolean()).isFalse();
        assertThat(j.get("oscuro").get("--surface").asText()).startsWith("#");
        assertThat(j.get("claro").get("--accent").asText()).isEqualTo("#C25E12");
        assertThat(j.get("altoContraste").get("--text").asText()).isEqualTo("#000000");
        assertThat(j.get("letra").get("regular").asText()).isEqualTo("/fuentes/Inter-Regular.ttf");
        assertThat(j.get("escala").asInt()).isEqualTo(115);
        assertThat(j.get("vidrio").asBoolean()).isTrue();
        assertThat(j.has("iconos")).isFalse();
        assertThat(j.get("version").asText()).hasSize(16);
    }

    @Test
    void conSesionLlegaLaDelUsuarioConSuPaqueteDeIconos() throws Exception {
        assertThat(enviar("GET", "/api/apariencia", null, null).statusCode()).isEqualTo(401);
        Cajero c = cajero();
        c.setApariencia(Apariencia.porDefecto().conPaquete("phosphor", "duotono").conGrosor(Apariencia.Grosor.GRUESO).aJson());
        cajeroRepo.save(c);
        HttpResponse<String> r = enviar("GET", "/api/apariencia", null, token(c));
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode j = JSON.readTree(r.body());
        assertThat(j.get("paqueteIconos").asText()).isEqualTo("phosphor");
        assertThat(j.get("grosorIconos").asDouble()).isEqualTo(2.5);
        JsonNode lista = j.get("iconos").get("lista");
        // Los iconos que solo usa la página móvil también vienen
        for (String n : new String[]{"ojo", "camion", "campana", "buscar", "grafica"}) {
            assertThat(lista.has(n)).as(n).isTrue();
            assertThat(lista.get(n).get(0).get("d").asText()).startsWith("M");
        }
        assertThat(lista.get("buscar").toString()).contains("\"o\":1").contains("\"r\":1");   // duotono: capa translúcida + relleno
        // Lucide: la página ya trae sus trazos
        Cajero d = cajero();
        JsonNode jl = JSON.readTree(enviar("GET", "/api/apariencia", null, token(d)).body());
        assertThat(jl.get("iconos").isNull()).isTrue();
    }

    @Test
    void lasLetrasSeSirvenDeLaListaCerradaConCache() throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base + "/fuentes/AtkinsonHyperlegible-Regular.ttf")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("font/ttf");
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("public, max-age=86400");
        String etag = r.headers().firstValue("ETag").orElseThrow();
        assertThat(r.body().length).isGreaterThan(10_000);
        HttpResponse<byte[]> r2 = http.send(HttpRequest.newBuilder(URI.create(base + "/fuentes/AtkinsonHyperlegible-Regular.ttf"))
                .header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r2.statusCode()).isEqualTo(304);
        assertThat(enviar("GET", "/fuentes/Otra.ttf", null, null).statusCode()).isEqualTo(404);
        assertThat(enviar("GET", "/fuentes/../css/app.css", null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void elManifiestoYLaPaginaSiguenALaTienda() throws Exception {
        Apariencia verde = Apariencia.porDefecto().conAcento("#2E7D4F");
        aparienciaService.guardarTienda(verde);
        JsonNode m = JSON.readTree(enviar("GET", "/manifest.webmanifest", null, null).body());
        assertThat(m.get("theme_color").asText()).isEqualTo(Paleta.colorBanda(verde));
        HttpResponse<String> pagina = enviar("GET", "/", null, null);
        assertThat(pagina.headers().firstValue("Content-Security-Policy").orElse("")).contains("font-src 'self'");
        assertThat(pagina.body()).contains("/api/apariencia/publica").contains("data-vidrio");
    }
}
