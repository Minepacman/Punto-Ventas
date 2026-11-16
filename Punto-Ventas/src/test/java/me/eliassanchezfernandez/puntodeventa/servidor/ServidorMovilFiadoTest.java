package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.CuentaClienteService;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import me.eliassanchezfernandez.puntodeventa.util.FuncionesOcultas;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceso móvil: "Quién me debe" (/api/clientes/saldos), solo lectura y solo con VENDER_A_CREDITO */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilFiadoTest {

    private static final String CLAVE = "clave123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private CuentaClienteService cuentaService;

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
        servidor.detener();
        sesionService.cerrarSesion();
    }

    private String token(Permiso... permisos) throws Exception {
        String login = "mf" + UUID.randomUUID().toString().substring(0, 8);
        Set<Permiso> set = permisos.length == 0 ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(List.of(permisos));
        Cajero c = cajeroService.crear(login, "Móvil " + login, CLAVE, Cajero.Rol.CAJERO, set);
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        HttpResponse<String> resp = enviar("POST", "/api/login",
                JSON.writeValueAsString(Map.of("usuario", login, "contrasena", CLAVE, "recordar", false)), null);
        assertThat(resp.statusCode()).isEqualTo(200);
        return JSON.readTree(resp.body()).get("token").asText();
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String cuerpoJson, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (cuerpoJson != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpoJson == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpoJson));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void conElFiadoOcultoLaSeccionNoSeOfreceYLaRutaNoExiste() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(FuncionesOcultas.FIADO_HABILITADO);
        String conPermiso = token(Permiso.VENDER_A_CREDITO);
        assertThat(JSON.readTree(enviar("GET", "/api/sesion", null, conPermiso).body()).get("secciones").get("clientes").asBoolean()).isFalse();
        HttpResponse<String> r = enviar("GET", "/api/clientes/saldos", null, conPermiso);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).doesNotContain("clientes");
        assertThat(enviar("GET", "/api/clientes/saldos", null, token()).statusCode()).isEqualTo(404);
    }

    @Test
    void quienMeDebeSoloConPermisoYSoloLosQueDeben() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(FuncionesOcultas.FIADO_HABILITADO, "fiado oculto: FuncionesOcultas");
        Cliente debe = cuentaService.guardar(null, "Debe móvil " + UUID.randomUUID().toString().substring(0, 5),
                "2821410000", null, 500, true, null);
        cuentaService.ajustar(debe.getId(), 120, "Deuda anterior", null);
        Cliente nada = cuentaService.guardar(null, "No debe móvil " + UUID.randomUUID().toString().substring(0, 5),
                null, null, 0, true, null);

        String sinPermiso = token();
        assertThat(enviar("GET", "/api/clientes/saldos", null, sinPermiso).statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(enviar("GET", "/api/sesion", null, sinPermiso).body()).get("secciones").get("clientes").asBoolean()).isFalse();

        String conPermiso = token(Permiso.VENDER_A_CREDITO);
        assertThat(JSON.readTree(enviar("GET", "/api/sesion", null, conPermiso).body()).get("secciones").get("clientes").asBoolean()).isTrue();
        HttpResponse<String> r = enviar("GET", "/api/clientes/saldos", null, conPermiso);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode json = JSON.readTree(r.body());
        assertThat(json.get("total").asDouble()).isGreaterThanOrEqualTo(120);
        boolean vieneElQueDebe = false, vieneElQueNo = false;
        for (JsonNode c : json.get("clientes")) {
            if (c.get("id").asLong() == debe.getId()) {
                vieneElQueDebe = true;
                assertThat(c.get("saldo").asDouble()).isEqualTo(120);
                assertThat(c.get("limite").asDouble()).isEqualTo(500);
            }
            if (c.get("id").asLong() == nada.getId()) vieneElQueNo = true;
        }
        assertThat(vieneElQueDebe).isTrue();
        assertThat(vieneElQueNo).isFalse();
        // Solo lectura: no hay forma de escribir desde el móvil
        assertThat(enviar("POST", "/api/clientes/saldos", "{}", conPermiso).statusCode()).isIn(404, 405);
    }
}
