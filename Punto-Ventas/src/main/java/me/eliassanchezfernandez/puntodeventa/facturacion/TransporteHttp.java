package me.eliassanchezfernandez.puntodeventa.facturacion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Capa mínima sobre java.net.http para poder probar {@link FacturamaProveedor}
 * sin red (las pruebas entregan respuestas de archivos JSON).
 */
public interface TransporteHttp {

    record Solicitud(String metodo, URI uri, Map<String, String> cabeceras, byte[] cuerpo, Duration timeout) { }

    record Respuesta(int estado, byte[] cuerpo) {
        public String texto() {
            return cuerpo == null ? "" : new String(cuerpo, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** @param maxBytes tope del cuerpo de la respuesta; más grande = IOException */
    Respuesta enviar(Solicitud solicitud, int maxBytes) throws IOException, InterruptedException;

    /** Implementación real con java.net.http.HttpClient */
    final class Real implements TransporteHttp {

        private final HttpClient http;

        public Real(Duration timeoutConexion) {
            this.http = HttpClient.newBuilder()
                    .connectTimeout(timeoutConexion)
                    .followRedirects(HttpClient.Redirect.NEVER)   // nunca mandar la autorización a otro host
                    .build();
        }

        @Override
        public Respuesta enviar(Solicitud s, int maxBytes) throws IOException, InterruptedException {
            HttpRequest.Builder b = HttpRequest.newBuilder(s.uri()).timeout(s.timeout());
            s.cabeceras().forEach(b::header);
            HttpRequest.BodyPublisher cuerpo = s.cuerpo() == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(s.cuerpo());
            b.method(s.metodo(), cuerpo);
            HttpResponse<InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                byte[] datos = in.readNBytes(maxBytes + 1);
                if (datos.length > maxBytes) {
                    throw new IOException("La respuesta del PAC es demasiado grande (más de " + (maxBytes / 1024) + " KB).");
                }
                return new Respuesta(r.statusCode(), datos);
            }
        }
    }
}
