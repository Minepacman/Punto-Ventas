package me.eliassanchezfernandez.puntodeventa.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * «Detectar mi ubicación» por IP: lectura del JSON de los tres proveedores desde
 * textos fijos (NUNCA se llama a internet en las pruebas), el salto al siguiente
 * proveedor cuando uno falla (con un servidor HTTP local) y la regla "solo https".
 */
class GeolocalizacionIpServiceTest {

    private static final String IPWHOIS = """
            {"success":true,"city":"Puebla","region":"Puebla","country":"México","latitude":19.0414,"longitude":-98.2063}
            """;
    private static final String GEOJS = """
            {"country":"Mexico","country_code":"MX","city":"Toluca","region":"México","latitude":"19.2926","longitude":"-99.6569",
             "timezone":"America/Mexico_City","ip":"203.0.113.9","organization":"AS8151 Uninet"}
            """;
    private static final String IPAPI = """
            {"ip":"203.0.113.9","city":"Mérida","region":"Yucatán","region_code":"YUC","country":"MX","country_name":"Mexico",
             "latitude":20.9754,"longitude":-89.6169,"timezone":"America/Merida","org":"Telmex"}
            """;

    private HttpServer servidor;

    @AfterEach
    void apagar() {
        if (servidor != null) servidor.stop(0);
        GeolocalizacionIpService.permitirRedLocalParaPruebas = false;
    }

    @Test
    void leeLaRespuestaDeCadaProveedor() throws IOException {
        GeolocalizacionIpService.Ubicacion a = GeolocalizacionIpService.parsear("ipwho.is", IPWHOIS).orElseThrow();
        assertThat(a.ciudad()).isEqualTo("Puebla");
        assertThat(a.region()).isEqualTo("Puebla");
        assertThat(a.pais()).isEqualTo("México");
        assertThat(a.latitud()).isCloseTo(19.0414, offset(0.0001));
        assertThat(a.longitud()).isCloseTo(-98.2063, offset(0.0001));
        assertThat(a.etiqueta()).isEqualTo("Puebla, México");          // región igual a la ciudad: no se repite
        assertThat(a.nombreParaGuardar()).isEqualTo("Puebla");
        assertThat(a.proveedor()).isEqualTo("ipwho.is");

        // GeoJS manda las coordenadas como texto
        GeolocalizacionIpService.Ubicacion b = GeolocalizacionIpService.parsear("GeoJS", GEOJS).orElseThrow();
        assertThat(b.ciudad()).isEqualTo("Toluca");
        assertThat(b.latitud()).isCloseTo(19.2926, offset(0.0001));
        assertThat(b.longitud()).isCloseTo(-99.6569, offset(0.0001));
        assertThat(b.etiqueta()).isEqualTo("Toluca, México, Mexico");

        // ipapi.co: el país legible viene en country_name
        GeolocalizacionIpService.Ubicacion c = GeolocalizacionIpService.parsear("ipapi.co", IPAPI).orElseThrow();
        assertThat(c.ciudad()).isEqualTo("Mérida");
        assertThat(c.region()).isEqualTo("Yucatán");
        assertThat(c.pais()).isEqualTo("Mexico");
        assertThat(c.latitud()).isCloseTo(20.9754, offset(0.0001));
    }

    @Test
    void unFalloDelProveedorOCoordenadasInvalidasDanVacio() throws IOException {
        assertThat(GeolocalizacionIpService.parsear("x", "{\"success\":false,\"message\":\"Reserved range\"}")).isEmpty();
        assertThat(GeolocalizacionIpService.parsear("x", "{\"error\":true,\"reason\":\"RateLimited\"}")).isEmpty();
        assertThat(GeolocalizacionIpService.parsear("x", "{\"city\":\"Sin coordenadas\"}")).isEmpty();
        assertThat(GeolocalizacionIpService.parsear("x", "{\"latitude\":95,\"longitude\":0}")).isEmpty();
        assertThat(GeolocalizacionIpService.parsear("x", "{\"latitude\":0,\"longitude\":0}")).isEmpty();   // "sin datos"
        assertThat(GeolocalizacionIpService.parsear("x", "{\"latitude\":\"abc\",\"longitude\":\"-99\"}")).isEmpty();
        assertThat(GeolocalizacionIpService.parsear("x", "[1,2,3]")).isEmpty();
        assertThatThrownBy(() -> GeolocalizacionIpService.parsear("x", "esto no es json")).isInstanceOf(IOException.class);

        // Sin ciudad se conserva la región como nombre a guardar
        Optional<GeolocalizacionIpService.Ubicacion> soloRegion =
                GeolocalizacionIpService.parsear("x", "{\"region\":\"Oaxaca\",\"country\":\"México\",\"latitude\":17.06,\"longitude\":-96.72}");
        assertThat(soloRegion).isPresent();
        assertThat(soloRegion.get().nombreParaGuardar()).isEqualTo("Oaxaca");
        assertThat(soloRegion.get().etiqueta()).isEqualTo("Oaxaca, México");
    }

    @Test
    void siElPrimerProveedorFallaSePruebaElSiguiente() throws IOException {
        AtomicInteger llamadasPrimero = new AtomicInteger();
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/saturado", ex -> {           // 429: límite del plan gratuito
            llamadasPrimero.incrementAndGet();
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        servidor.createContext("/sin-exito", ex -> responder(ex, 200, "{\"success\":false,\"message\":\"no\"}"));
        servidor.createContext("/bien", ex -> responder(ex, 200, IPWHOIS));
        servidor.createContext("/redirige", ex -> {           // una redirección NO se sigue
            ex.getResponseHeaders().add("Location", "/bien");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        servidor.createContext("/enorme", ex -> {
            byte[] relleno = new byte[GeolocalizacionIpService.MAX_RESPUESTA_BYTES + 10];
            java.util.Arrays.fill(relleno, (byte) ' ');
            ex.sendResponseHeaders(200, relleno.length);
            ex.getResponseBody().write(relleno);
            ex.close();
        });
        servidor.start();
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        GeolocalizacionIpService.permitirRedLocalParaPruebas = true;

        GeolocalizacionIpService servicio = new GeolocalizacionIpService();
        servicio.setProveedores(List.of(
                new GeolocalizacionIpService.Proveedor("A", URI.create(base + "/saturado")),
                new GeolocalizacionIpService.Proveedor("B", URI.create(base + "/sin-exito")),
                new GeolocalizacionIpService.Proveedor("C", URI.create(base + "/redirige")),
                new GeolocalizacionIpService.Proveedor("D", URI.create(base + "/enorme")),
                new GeolocalizacionIpService.Proveedor("E", URI.create(base + "/bien"))));
        GeolocalizacionIpService.Ubicacion u = servicio.estimar();
        assertThat(u.ciudad()).isEqualTo("Puebla");
        assertThat(u.proveedor()).isEqualTo("E");
        assertThat(llamadasPrimero.get()).isEqualTo(1);

        // Si todos fallan, un solo IOException que resume qué pasó con cada uno
        servicio.setProveedores(List.of(
                new GeolocalizacionIpService.Proveedor("A", URI.create(base + "/saturado")),
                new GeolocalizacionIpService.Proveedor("B", URI.create(base + "/sin-exito"))));
        assertThatThrownBy(servicio::estimar).isInstanceOf(IOException.class)
                .hasMessageContaining("A: respondió 429").hasMessageContaining("B: sin datos");
    }

    @Test
    void soloSeConsultaPorHttpsYLosProveedoresRealesCumplen() {
        assertThat(GeolocalizacionIpService.permitirRedLocalParaPruebas).isFalse();
        assertThatThrownBy(() -> GeolocalizacionIpService.validarUri(URI.create("http://ipwho.is/")))
                .isInstanceOf(IOException.class).hasMessageContaining("https");
        for (GeolocalizacionIpService.Proveedor p : GeolocalizacionIpService.PROVEEDORES) {
            assertThat(p.uri().getScheme()).as(p.nombre()).isEqualTo("https");
        }
        assertThat(GeolocalizacionIpService.PROVEEDORES).extracting(GeolocalizacionIpService.Proveedor::nombre)
                .containsExactly("ipwho.is", "GeoJS", "ipapi.co");

        // Sin permiso de pruebas, un proveedor http:// no se consulta aunque esté en la lista
        GeolocalizacionIpService servicio = new GeolocalizacionIpService();
        servicio.setProveedores(List.of(new GeolocalizacionIpService.Proveedor("local", URI.create("http://127.0.0.1:9/"))));
        assertThatThrownBy(servicio::estimar).isInstanceOf(IOException.class).hasMessageContaining("https");
    }

    private static void responder(com.sun.net.httpserver.HttpExchange ex, int codigo, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(codigo, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
