package me.eliassanchezfernandez.puntodeventa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Búsqueda de ciudades por nombre para obtener las coordenadas del negocio
 * (asistente inicial). Usa el geocodificador gratuito de Open-Meteo, el mismo
 * proveedor del clima, sin llave ni registro. Solo se envía el texto que el
 * administrador escribe; si no hay internet se avisa y se pueden capturar las
 * coordenadas a mano u omitir el paso. Nada se guarda aquí: las coordenadas
 * elegidas las guarda ConfiguracionService.guardarUbicacionClima.
 */
@Service
public class UbicacionService {

    public static final String URL_BASE = "https://geocoding-api.open-meteo.com/v1/search";
    public static final int MAX_RESULTADOS = 8;
    static final int MAX_RESPUESTA_BYTES = 256 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Un lugar encontrado: "Puebla, Puebla, México" con sus coordenadas */
    public record Lugar(String nombre, String region, String pais, double latitud, double longitud) {
        public String etiqueta() {
            StringBuilder sb = new StringBuilder(nombre);
            if (region != null && !region.isBlank() && !region.equalsIgnoreCase(nombre)) sb.append(", ").append(region);
            if (pais != null && !pais.isBlank()) sb.append(", ").append(pais);
            return sb.toString();
        }

        @Override public String toString() { return etiqueta(); }
    }

    /** Texto de búsqueda válido: al menos 2 caracteres sin contar espacios */
    static String validarTexto(String texto) {
        String t = texto == null ? "" : texto.trim();
        if (t.length() < 2) throw new NegocioException("Escribe al menos dos letras de la ciudad.");
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    static URI uriPara(String texto) {
        String t = validarTexto(texto);
        return URI.create(URL_BASE + "?name=" + URLEncoder.encode(t, StandardCharsets.UTF_8)
                + "&count=" + MAX_RESULTADOS + "&language=es&format=json");
    }

    /**
     * Interpreta la respuesta: {"results":[{"name":"Puebla","latitude":19.04,"longitude":-98.21,
     * "country":"México","admin1":"Puebla"}, ...]}. Sin "results" = sin coincidencias.
     */
    public static List<Lugar> parsear(String json) throws IOException {
        JsonNode raiz = JSON.readTree(json);
        List<Lugar> lugares = new ArrayList<>();
        for (JsonNode n : raiz.path("results")) {
            if (!n.has("name") || !n.has("latitude") || !n.has("longitude")) continue;
            double lat = n.get("latitude").asDouble();
            double lon = n.get("longitude").asDouble();
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
            lugares.add(new Lugar(n.get("name").asText(), n.path("admin1").asText(null),
                    n.path("country").asText(null), lat, lon));
            if (lugares.size() >= MAX_RESULTADOS) break;
        }
        return lugares;
    }

    /** Consulta en línea; lanza IOException si no hay conexión (el llamador decide qué mostrar) */
    public List<Lugar> buscar(String texto) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(uriPara(texto))
                .timeout(Duration.ofSeconds(6))
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream cuerpo = resp.body()) {
                if (resp.statusCode() != 200) {
                    throw new IOException("El servicio de ubicación respondió " + resp.statusCode() + ".");
                }
                byte[] bytes = cuerpo.readNBytes(MAX_RESPUESTA_BYTES + 1);
                if (bytes.length > MAX_RESPUESTA_BYTES) {
                    throw new IOException("La respuesta del servicio de ubicación es demasiado grande.");
                }
                return parsear(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Búsqueda interrumpida.", e);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
