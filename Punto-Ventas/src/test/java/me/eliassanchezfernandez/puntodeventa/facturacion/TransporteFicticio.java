package me.eliassanchezfernandez.puntodeventa.facturacion;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Transporte sin red para las pruebas: responde con archivos JSON de
 * src/test/resources/facturama según la petición, y guarda lo enviado.
 */
public class TransporteFicticio implements TransporteHttp {

    public final List<Solicitud> enviadas = new ArrayList<>();
    private final Function<Solicitud, Respuesta> respondedor;
    private boolean fallarConTimeout;
    private IOException fallarCon;

    public TransporteFicticio(Function<Solicitud, Respuesta> respondedor) {
        this.respondedor = respondedor;
    }

    public TransporteFicticio queTardeDemasiado() { fallarConTimeout = true; return this; }
    public TransporteFicticio queFalleCon(IOException e) { fallarCon = e; return this; }

    @Override
    public Respuesta enviar(Solicitud s, int maxBytes) throws IOException {
        enviadas.add(s);
        if (fallarConTimeout) throw new HttpTimeoutException("request timed out");
        if (fallarCon != null) throw fallarCon;
        Respuesta r = respondedor.apply(s);
        if (r.cuerpo() != null && r.cuerpo().length > maxBytes) throw new IOException("demasiado grande");
        return r;
    }

    public Solicitud ultima() {
        return enviadas.get(enviadas.size() - 1);
    }

    public static Respuesta recurso(int estado, String nombre) {
        try (InputStream in = TransporteFicticio.class.getResourceAsStream("/facturama/" + nombre)) {
            if (in == null) throw new IllegalStateException("Falta el recurso de prueba " + nombre);
            return new Respuesta(estado, in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Respuesta texto(int estado, String cuerpo) {
        return new Respuesta(estado, cuerpo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
