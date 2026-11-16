package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AparienciaService;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Apariencia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturas de la página móvil a 390 px con cada apariencia de la tienda (Chrome sin ventana).
 * <pre>APP_CAPTURAS=true APP_CAPTURAS_DIR=/ruta ./gradlew test --tests '*CapturasAparienciaWebTest'</pre>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "APP_CAPTURAS", matches = "true")
class CapturasAparienciaWebTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private AparienciaService aparienciaService;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void capturasPorApariencia() throws Exception {
        Path base = Path.of(System.getenv().getOrDefault("APP_CAPTURAS_DIR", System.getProperty("java.io.tmpdir")), "web");
        Files.createDirectories(base);
        servidor.iniciar(0);
        String url = "http://127.0.0.1:" + servidor.getPuerto();
        String chrome = System.getenv().getOrDefault("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        admin.setDebeCambiarContrasena(false);
        admin.setBloqueadoHasta(null);
        cajeroRepo.save(admin);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");
        String login = "lupita";
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(login).orElseGet(() ->
                cajeroService.crear(login, "Lupita Hernández", "clave123", Cajero.Rol.GERENTE,
                        EnumSet.of(Permiso.VER_HISTORIAL, Permiso.VER_GANANCIAS)));
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);

        Apariencia d = Apariencia.porDefecto();
        Map<String, Apariencia> variantes = new LinkedHashMap<>();
        variantes.put("1-de-fabrica", d);
        variantes.put("2-oscuro-morado", d.conTema(Apariencia.Tema.OSCURO).conAcento("#6B4BA8"));
        variantes.put("3-alto-contraste", d.conTema(Apariencia.Tema.ALTO_CONTRASTE));
        variantes.put("4-vidrio-claro-turquesa", d.conVidrio(true).conAcento("#0F7C86"));
        variantes.put("5-vidrio-oscuro-rosa", d.conTema(Apariencia.Tema.OSCURO).conVidrio(true).conAcento("#B83C7A"));
        variantes.put("6-phosphor-duotono-atkinson-115", d.conPaquete("phosphor", "duotono").conLetra(Apariencia.Letra.ATKINSON).conEscala(115).conAcento("#2E7D4F"));
        variantes.put("7-material-relleno-nunito", d.conPaquete("material", "relleno").conLetra(Apariencia.Letra.NUNITO).conAcento("#C25E12"));
        variantes.put("8-tabler-heroicons-grueso", d.conPaquete("tabler", "contorno").conGrosor(Apariencia.Grosor.GRUESO));

        try (CapturasAsistenteAvisosTest.Chrome navegador = new CapturasAsistenteAvisosTest.Chrome(chrome, false)) {
            for (Map.Entry<String, Apariencia> v : variantes.entrySet()) {
                aparienciaService.guardarTienda(v.getValue());
                Path dir = base.resolve(v.getKey());
                Files.createDirectories(dir);
                navegador.foto(url, "", CapturasAsistenteAvisosTest.UA_ANDROID, null, null, null, dir.resolve("login.png"));
                String t = token(url, login);
                navegador.foto(url, "#/estadisticas", CapturasAsistenteAvisosTest.UA_ANDROID, t, null, null, dir.resolve("estadisticas.png"));
                navegador.foto(url, "#/inventario", CapturasAsistenteAvisosTest.UA_ANDROID, t, null, null, dir.resolve("inventario.png"));
                navegador.foto(url, "#/config", CapturasAsistenteAvisosTest.UA_ANDROID, t, null, null, dir.resolve("ajustes.png"));
            }
        } finally {
            aparienciaService.guardarTienda(Apariencia.porDefecto());
            servidor.detener();
            sesionService.cerrarSesion();
        }
        System.out.println("Capturas en " + base.toAbsolutePath());
    }

    private String token(String base, String login) throws Exception {
        HttpResponse<String> r = post(base, "/api/login", JSON.writeValueAsString(Map.of("usuario", login, "contrasena", "clave123", "recordar", false)), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String t = JSON.readTree(r.body()).get("token").asText();
        post(base, "/api/tutorial/visto", "{\"visto\":true}", t);
        return t;
    }

    private HttpResponse<String> post(String base, String ruta, String json, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
