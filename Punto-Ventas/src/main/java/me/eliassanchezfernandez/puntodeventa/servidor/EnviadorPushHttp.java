package me.eliassanchezfernandez.puntodeventa.servidor;

import me.eliassanchezfernandez.puntodeventa.util.VersionApp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Envío real por HTTPS al servicio de push (FCM, Apple, Mozilla, Microsoft) con el mismo
 * criterio de {@code ActualizacionService}: sin redirecciones, tiempos de espera cortos, el
 * cuerpo de la respuesta se lee acotado y nunca se registra nada del mensaje ni de la
 * cabecera Authorization. El endpoint ya llegó validado (solo hosts de servicios conocidos),
 * pero aquí se vuelve a comprobar por si alguien llama con otro.
 */
public class EnviadorPushHttp implements EnviadorPush {

    static final Duration TIEMPO_ESPERA = Duration.ofSeconds(10);
    static final int MAX_RESPUESTA = 1024;

    private volatile HttpClient http;

    private HttpClient http() {
        HttpClient h = http;
        if (h == null) {
            synchronized (this) {
                h = http;
                if (h == null) {
                    http = h = HttpClient.newBuilder()
                            .connectTimeout(TIEMPO_ESPERA)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .version(HttpClient.Version.HTTP_1_1)
                            .build();
                }
            }
        }
        return h;
    }

    @Override
    public Resultado enviar(String endpoint, byte[] cuerpo, String authorization, int ttlSegundos, boolean urgente) throws IOException {
        URI uri = URI.create(endpoint);
        if (!ValidadorEndpointPush.permitido(uri)) throw new IOException("Servicio de push no reconocido.");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIEMPO_ESPERA)
                .header("User-Agent", VersionApp.agenteUsuario())
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", "aes128gcm")
                .header("TTL", String.valueOf(Math.max(0, ttlSegundos)))
                .header("Urgency", urgente ? "high" : "normal")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo))
                .build();
        try {
            HttpResponse<InputStream> resp = http().send(req, HttpResponse.BodyHandlers.ofInputStream());
            String detalle;
            try (InputStream in = resp.body()) {
                detalle = new String(in.readNBytes(MAX_RESPUESTA), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            }
            return new Resultado(resp.statusCode(), detalle.length() > 180 ? detalle.substring(0, 180) : detalle);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Envío interrumpido.");
        }
    }
}
