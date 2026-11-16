package me.eliassanchezfernandez.puntodeventa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * «Detectar mi ubicación»: estima la ciudad del negocio a partir de la conexión a
 * internet (geolocalización por IP), para que el administrador no tenga que
 * teclear coordenadas. Ni Windows ni macOS exponen a Java una API sencilla de
 * ubicación del equipo, y una computadora de mostrador no trae GPS, así que la IP
 * es la mejor pista disponible: da la ciudad o la zona (la precisión es la del
 * proveedor de internet), que es todo lo que necesita el clima.
 *
 * Privacidad: SOLO se consulta cuando el administrador pulsa el botón (nunca al
 * arrancar ni en segundo plano); la petición no lleva ningún dato de la tienda,
 * solo la IP pública que cualquier servidor ve de todos modos. Lo que se guarda
 * son la ciudad y las coordenadas que el administrador confirma, por la misma vía
 * que la captura manual ({@code ConfiguracionService.guardarUbicacionClima}).
 *
 * Proveedores (todos gratuitos, por HTTPS, sin llave ni registro), en orden; si
 * uno falla o está saturado se prueba el siguiente:
 *  1. ipwho.is — ~10 000 consultas al mes gratis, nombres en español ({@code lang=es}).
 *  2. GeoJS (get.geojs.io) — proyecto abierto sin límite publicado; nombres en inglés.
 *  3. ipapi.co — 1 000 consultas al día gratis; respaldo final.
 * Seguridad al estilo de ActualizacionService: solo https, sin redirecciones,
 * tiempos de espera cortos y respuesta acotada a 64 KB.
 */
@Service
public class GeolocalizacionIpService {

    /** Un servicio de geolocalización: nombre para mostrar y URL de consulta */
    public record Proveedor(String nombre, URI uri) { }

    public static final List<Proveedor> PROVEEDORES = List.of(
            new Proveedor("ipwho.is", URI.create("https://ipwho.is/?lang=es&fields=success,message,city,region,country,latitude,longitude")),
            new Proveedor("GeoJS", URI.create("https://get.geojs.io/v1/ip/geo.json")),
            new Proveedor("ipapi.co", URI.create("https://ipapi.co/json/")));

    static final int MAX_RESPUESTA_BYTES = 64 * 1024;
    static final Duration TIEMPO_CONEXION = Duration.ofSeconds(4);
    static final Duration TIEMPO_RESPUESTA = Duration.ofSeconds(6);

    /** Solo las pruebas: permite http:// a un servidor local en lugar de https:// a internet */
    static volatile boolean permitirRedLocalParaPruebas = false;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIEMPO_CONEXION)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private volatile List<Proveedor> proveedores = PROVEEDORES;

    /** Ubicación estimada: ciudad y región para mostrar, coordenadas para el clima */
    public record Ubicacion(String ciudad, String region, String pais, double latitud, double longitud, String proveedor) {

        /** "Puebla, Puebla, México" (omite las partes vacías o repetidas) */
        public String etiqueta() {
            StringBuilder sb = new StringBuilder();
            for (String parte : new String[] { ciudad, region, pais }) {
                if (parte == null || parte.isBlank()) continue;
                if (sb.length() > 0 && sb.toString().equalsIgnoreCase(parte)) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(parte.trim());
            }
            return sb.length() == 0 ? "una zona sin nombre" : sb.toString();
        }

        /** Lo que conviene guardar como "ciudad" en la configuración (ciudad o, si falta, la región) */
        public String nombreParaGuardar() {
            if (ciudad != null && !ciudad.isBlank()) return ciudad.trim();
            if (region != null && !region.isBlank()) return region.trim();
            return pais == null ? "" : pais.trim();
        }
    }

    /** Las pruebas apuntan los proveedores a un servidor local */
    void setProveedores(List<Proveedor> lista) {
        this.proveedores = List.copyOf(lista);
    }

    /**
     * Interpreta la respuesta de cualquiera de los proveedores: todos devuelven
     * {@code latitude}/{@code longitude} (número o texto) y {@code city}; la región
     * viene como {@code region} (ipwho.is, GeoJS, ipapi.co) y el país como
     * {@code country} (ipwho.is, GeoJS) o {@code country_name} (ipapi.co). Un fallo
     * del proveedor llega como {@code "success": false} (ipwho.is) o
     * {@code "error": true} (ipapi.co). Vacío si faltan las coordenadas o están
     * fuera de rango.
     */
    public static Optional<Ubicacion> parsear(String proveedor, String json) throws IOException {
        JsonNode raiz = JSON.readTree(json);
        if (raiz == null || !raiz.isObject()) return Optional.empty();
        if (raiz.path("success").isBoolean() && !raiz.path("success").asBoolean()) return Optional.empty();
        if (raiz.path("error").isBoolean() && raiz.path("error").asBoolean()) return Optional.empty();
        Double lat = numero(raiz, "latitude", "lat");
        Double lon = numero(raiz, "longitude", "lon", "lng");
        if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) return Optional.empty();
        if (lat == 0 && lon == 0) return Optional.empty();   // "sin datos" disfrazado de coordenadas
        String ciudad = texto(raiz, "city");
        String region = texto(raiz, "region", "region_name", "state");
        String pais = texto(raiz, "country_name", "country");
        return Optional.of(new Ubicacion(ciudad, region, pais, lat, lon, proveedor));
    }

    private static Double numero(JsonNode raiz, String... claves) {
        for (String clave : claves) {
            JsonNode n = raiz.get(clave);
            if (n == null || n.isNull()) continue;
            if (n.isNumber()) return n.asDouble();
            if (n.isTextual()) {
                try {
                    return Double.parseDouble(n.asText().trim());
                } catch (NumberFormatException ignorada) { /* siguiente clave */ }
            }
        }
        return null;
    }

    private static String texto(JsonNode raiz, String... claves) {
        for (String clave : claves) {
            JsonNode n = raiz.get(clave);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                String t = n.asText().trim();
                return t.length() > 100 ? t.substring(0, 100) : t;
            }
        }
        return null;
    }

    /**
     * Consulta los proveedores en orden y devuelve la primera estimación válida.
     * @throws IOException si ninguno respondió (sin internet o todos saturados); el
     *         mensaje resume qué pasó con cada uno
     */
    public Ubicacion estimar() throws IOException {
        List<String> fallos = new ArrayList<>();
        for (Proveedor p : proveedores) {
            try {
                Optional<Ubicacion> u = consultar(p);
                if (u.isPresent()) return u.get();
                fallos.add(p.nombre() + ": sin datos de ubicación");
            } catch (IOException e) {
                fallos.add(p.nombre() + ": " + e.getMessage());
            }
        }
        throw new IOException("No se pudo estimar la ubicación (" + String.join("; ", fallos) + ").");
    }

    private Optional<Ubicacion> consultar(Proveedor p) throws IOException {
        validarUri(p.uri());
        HttpRequest req = HttpRequest.newBuilder(p.uri())
                .timeout(TIEMPO_RESPUESTA)
                .header("Accept", "application/json")
                .header("User-Agent", VersionApp.agenteUsuario())
                .GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream cuerpo = resp.body()) {
                if (resp.statusCode() != 200) {
                    throw new IOException("respondió " + resp.statusCode());
                }
                byte[] bytes = cuerpo.readNBytes(MAX_RESPUESTA_BYTES + 1);
                if (bytes.length > MAX_RESPUESTA_BYTES) {
                    throw new IOException("respuesta demasiado grande");
                }
                return parsear(p.nombre(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("consulta interrumpida", e);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    static void validarUri(URI uri) throws IOException {
        String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean ok = esquema.equals("https") || (permitirRedLocalParaPruebas && esquema.equals("http"));
        if (!ok || uri.getHost() == null) {
            throw new IOException("solo se consultan servicios por https");
        }
    }
}
