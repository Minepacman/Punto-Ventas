package me.eliassanchezfernandez.puntodeventa.servidor;

import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Puerto automático y nombre local mDNS. Nunca usa multidifusión real. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServidorMovilPuertoNombreTest {

    @Autowired private ServidorMovilService servidor;
    @Autowired private AnunciadorMdns anunciador;
    @Autowired private ConfiguracionService configuracionService;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void limpiar() {
        servidor.detener();
        anunciador.usarFabrica(null);
    }

    private static int puertoLibre() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private int get200() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + servidor.getPuerto() + "/")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static void esperar(BooleanSupplier cond) throws Exception {
        for (int i = 0; i < 100 && !cond.getAsBoolean(); i++) Thread.sleep(50);
        assertThat(cond.getAsBoolean()).isTrue();
    }

    // ── Puerto automático ────────────────────────────────────────────────

    @Test
    void conElPreferidoOcupadoCaeAUnRespaldoYLoGuarda() throws Exception {
        try (ServerSocket ocupado = new ServerSocket(0)) {
            int preferido = ocupado.getLocalPort();
            int usado = servidor.iniciarAutomatico(preferido);
            assertThat(usado).isNotEqualTo(preferido).isEqualTo(servidor.getPuerto());
            assertThat(get200()).isEqualTo(200);

            configuracionService.registrarPuertoMovilEnlazado(usado);
            Configuracion c = configuracionService.obtener();
            assertThat(c.getMovilPuerto()).isEqualTo(usado);
        }
    }

    @Test
    void elPuertoGuardadoSeReutilizaYUnCambioDejaElAviso() throws Exception {
        int p = puertoLibre();
        assertThat(servidor.iniciarAutomatico(p)).isEqualTo(p);
        configuracionService.registrarPuertoMovilEnlazado(p);
        servidor.detener();

        assertThat(servidor.iniciarAutomatico(configuracionService.obtener().getMovilPuerto())).isEqualTo(p);
        assertThat(configuracionService.obtener().getMovilPuerto()).isEqualTo(p);
        servidor.detener();

        // Ahora ese puerto está ocupado: cambia y queda el aviso con el anterior
        try (ServerSocket ocupado = new ServerSocket(p)) {
            int nuevo = servidor.iniciarAutomatico(configuracionService.obtener().getMovilPuerto());
            assertThat(nuevo).isNotEqualTo(p);
            configuracionService.registrarPuertoMovilEnlazado(nuevo);
        }
        Configuracion c = configuracionService.obtener();
        assertThat(c.getMovilPuertoAnterior()).isEqualTo(p);
        assertThat(c.getMovilPuerto()).isNotEqualTo(p);
    }

    @Test
    void sinNingunPuertoLibreElSistemaAsignaUno() throws Exception {
        try (ServerSocket a = new ServerSocket(0); ServerSocket b = new ServerSocket(0)) {
            int usado = servidor.iniciarConCandidatos(List.of(a.getLocalPort(), b.getLocalPort(), 0));
            assertThat(usado).isPositive().isNotIn(a.getLocalPort(), b.getLocalPort());
            assertThat(get200()).isEqualTo(200);
        }
    }

    @Test
    void sinCandidatosUtilesDaUnErrorClaro() throws Exception {
        try (ServerSocket a = new ServerSocket(0)) {
            assertThatThrownBy(() -> servidor.iniciarConCandidatos(List.of(a.getLocalPort())))
                    .isInstanceOf(NegocioException.class);
            assertThat(servidor.estaActivo()).isFalse();
        }
    }

    @Test
    void laListaDeRespaldoTerminaEnPuertoDelSistema() {
        assertThat(ServidorMovilService.PUERTOS_RESPALDO).contains(8086, 8095, 18085).endsWith(0);
    }

    // ── Nombre local ─────────────────────────────────────────────────────

    @Test
    void validaElNombreLocal() {
        assertThat(ConfiguracionService.validarNombreLocal("mitienda")).isEqualTo("mitienda");
        assertThat(ConfiguracionService.validarNombreLocal("  MiTienda-2.local ")).isEqualTo("mitienda-2");
        assertThat(ConfiguracionService.validarNombreLocal("a")).isEqualTo("a");
        assertThat(ConfiguracionService.validarNombreLocal("  ")).isNull();
        assertThat(ConfiguracionService.validarNombreLocal(null)).isNull();
        assertThat(ConfiguracionService.validarNombreLocal("a".repeat(40))).hasSize(40);
        for (String malo : new String[]{"-tienda", "tienda-", "mi tienda", "tienda_1", "ñandú", "a.b", "a".repeat(41), "mi/tienda"}) {
            assertThatThrownBy(() -> ConfiguracionService.validarNombreLocal(malo))
                    .as(malo).isInstanceOf(NegocioException.class);
        }
    }

    @Test
    void sugiereUnNombreSinAcentos() {
        assertThat(ConfiguracionService.sugerirNombreLocal("Mi Tienda")).isEqualTo("mi-tienda");
        assertThat(ConfiguracionService.sugerirNombreLocal("Abarrotes «Peña» & Hijos, S.A.")).isEqualTo("abarrotes-pena-hijos-s-a");
        assertThat(ConfiguracionService.sugerirNombreLocal("!!!")).isEqualTo("tienda");
        assertThat(ConfiguracionService.sugerirNombreLocal(null)).isEqualTo("tienda");
        String largo = ConfiguracionService.sugerirNombreLocal("ab ".repeat(30));
        assertThat(largo.length()).isLessThanOrEqualTo(40);
        assertThat(ConfiguracionService.validarNombreLocal(largo)).isEqualTo(largo);
    }

    @Test
    void laCabeceraHostAceptaElNombreLocal() {
        ServidorMovilService s = new ServidorMovilService();
        assertThat(s.hostPermitido("mitienda.local")).isFalse();
        s.fijarNombreLocal("mitienda");
        assertThat(s.hostPermitido("mitienda.local")).isTrue();
        assertThat(s.hostPermitido("MiTienda.local:8085")).isTrue();
        assertThat(s.hostPermitido("mitienda.local:abc")).isFalse();
        assertThat(s.hostPermitido("otra.local:8085")).isFalse();
        assertThat(s.hostPermitido("mitienda.evil.com")).isFalse();
        assertThat(s.hostPermitido("evil.com")).isFalse();
        assertThat(s.hostPermitido("192.168.1.5:8085")).isTrue();
    }

    @Test
    void siMdnsFallaElServidorSigueFuncionando() throws Exception {
        AtomicBoolean intentado = new AtomicBoolean();
        anunciador.usarFabrica((nombre, puerto, ips) -> { intentado.set(true); throw new java.io.IOException("multicast bloqueado"); });
        servidor.iniciar(0);
        anunciador.iniciar("mitienda", servidor.getPuerto(), List.of(InetAddress.getLoopbackAddress()));
        esperar(() -> anunciador.estado() == AnunciadorMdns.Estado.NO_DISPONIBLE);
        assertThat(intentado).isTrue();
        assertThat(get200()).isEqualTo(200);
        assertThat(servidor.estaActivo()).isTrue();
    }

    @Test
    void mdnsActivoConflictoYCierreAlDetener() throws Exception {
        AtomicBoolean cerrado = new AtomicBoolean();
        anunciador.usarFabrica((nombre, puerto, ips) -> new AnunciadorMdns.Registro() {
            @Override public String host() { return nombre + ".local"; }
            @Override public void close() { cerrado.set(true); }
        });
        anunciador.iniciar("mitienda", 8085, List.of(InetAddress.getLoopbackAddress()));
        esperar(() -> anunciador.estado() == AnunciadorMdns.Estado.ACTIVO);
        assertThat(anunciador.hostEfectivo()).isEqualTo("mitienda.local");
        anunciador.detener();
        esperar(cerrado::get);
        assertThat(anunciador.estado()).isEqualTo(AnunciadorMdns.Estado.APAGADO);

        // Otro equipo ya usa el nombre: JmDNS renombra (mitienda-2) y se reporta conflicto
        anunciador.usarFabrica((nombre, puerto, ips) -> new AnunciadorMdns.Registro() {
            @Override public String host() { return nombre + "-2.local"; }
            @Override public void close() { }
        });
        anunciador.iniciar("mitienda", 8085, List.of(InetAddress.getLoopbackAddress()));
        esperar(() -> anunciador.estado() == AnunciadorMdns.Estado.CONFLICTO);
        assertThat(anunciador.hostEfectivo()).isEqualTo("mitienda-2.local");
    }

    @Test
    void detenerElServidorRetiraElNombreLocal() throws Exception {
        anunciador.usarFabrica((nombre, puerto, ips) -> new AnunciadorMdns.Registro() {
            @Override public String host() { return nombre + ".local"; }
            @Override public void close() { }
        });
        servidor.iniciar(0);
        anunciador.iniciar("mitienda", servidor.getPuerto(), List.of(InetAddress.getLoopbackAddress()));
        esperar(() -> anunciador.estado() == AnunciadorMdns.Estado.ACTIVO);
        servidor.detener();
        assertThat(anunciador.estado()).isEqualTo(AnunciadorMdns.Estado.APAGADO);
    }
}
