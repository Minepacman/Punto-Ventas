package me.eliassanchezfernandez.puntodeventa.servidor;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Nombre local por mDNS (Bonjour / Zeroconf): publica "nombre.local" para cada
 * IPv4 de la red y el servicio "_http._tcp" "Punto de Venta". Solo corre
 * mientras el servidor móvil está encendido.
 *
 * Todo ocurre en un hilo propio (nunca en el de JavaFX ni en el arranque) y
 * cualquier fallo (multidifusión bloqueada, sin red...) se registra en voz baja
 * y deja el estado en NO_DISPONIBLE: el acceso por IP sigue funcionando.
 */
@Component
public class AnunciadorMdns {

    public enum Estado { APAGADO, INICIANDO, ACTIVO, CONFLICTO, NO_DISPONIBLE }

    /** Un anuncio en marcha; close() lo retira de la red */
    public interface Registro extends AutoCloseable {
        /** Nombre de host realmente obtenido ("mitienda.local"); distinto del pedido si había conflicto */
        String host();
        @Override void close();
    }

    /** Crea los anuncios; se sustituye en las pruebas (no se usa multidifusión real) */
    @FunctionalInterface
    public interface Fabrica {
        Registro publicar(String nombre, int puerto, List<InetAddress> ips) throws Exception;
    }

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AnunciadorMdns.class.getName());

    private volatile Fabrica fabrica = AnunciadorMdns::publicarConJmdns;
    private volatile Estado estado = Estado.APAGADO;
    private volatile String hostEfectivo;
    private volatile String nombrePedido;
    private ExecutorService hilo;
    private Registro registro;          // solo se toca desde el hilo propio

    public Estado estado()         { return estado; }
    /** Host con el que quedó publicado ("mitienda.local") o null */
    public String hostEfectivo()   { return hostEfectivo; }
    public String nombrePedido()   { return nombrePedido; }

    /** Pruebas: sustituye la fábrica de anuncios */
    public void usarFabrica(Fabrica f) { this.fabrica = f == null ? AnunciadorMdns::publicarConJmdns : f; }

    private synchronized ExecutorService hilo() {
        if (hilo == null) {
            hilo = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mdns");
                t.setDaemon(true);
                return t;
            });
        }
        return hilo;
    }

    /** Publica el nombre en segundo plano. Reemplaza el anuncio anterior si lo había. */
    public void iniciar(String nombre, int puerto, List<InetAddress> ips) {
        if (nombre == null || nombre.isBlank()) { detener(); return; }
        nombrePedido = nombre;
        estado = Estado.INICIANDO;
        hostEfectivo = null;
        List<InetAddress> copia = List.copyOf(ips);
        try {
            hilo().execute(() -> {
                cerrarRegistro();
                if (copia.isEmpty()) { estado = Estado.NO_DISPONIBLE; return; }
                try {
                    Registro r = fabrica.publicar(nombre, puerto, copia);
                    registro = r;
                    String host = r.host();
                    hostEfectivo = host;
                    boolean igual = host != null && host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "").equals(nombre + ".local");
                    estado = igual ? Estado.ACTIVO : Estado.CONFLICTO;
                } catch (Throwable e) {          // incluye errores de red o de enlace nativo
                    LOG.log(java.util.logging.Level.FINE, "Nombre local no disponible", e);
                    hostEfectivo = null;
                    estado = Estado.NO_DISPONIBLE;
                }
            });
        } catch (RuntimeException e) {           // hilo ya cerrado
            estado = Estado.NO_DISPONIBLE;
        }
    }

    /** Retira el anuncio (en segundo plano: cerrar JmDNS puede tardar un par de segundos) */
    public void detener() {
        nombrePedido = null;
        hostEfectivo = null;
        estado = Estado.APAGADO;
        ExecutorService h;
        synchronized (this) { h = hilo; }
        if (h == null) return;
        try {
            h.execute(this::cerrarRegistro);
        } catch (RuntimeException ignorada) { /* ya cerrado */ }
    }

    private void cerrarRegistro() {
        Registro r = registro;
        registro = null;
        if (r == null) return;
        try {
            r.close();
        } catch (Throwable e) {
            LOG.log(java.util.logging.Level.FINE, "No se pudo cerrar mDNS", e);
        }
    }

    /** Al cerrar la aplicación: retira el anuncio y espera un momento a que termine */
    @PreDestroy
    public void alCerrarContexto() {
        detener();
        ExecutorService h;
        synchronized (this) { h = hilo; hilo = null; }
        if (h == null) return;
        h.shutdown();
        try {
            if (!h.awaitTermination(4, TimeUnit.SECONDS)) h.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            h.shutdownNow();
        }
    }

    // ── JmDNS ─────────────────────────────────────────────────────────────

    private static Registro publicarConJmdns(String nombre, int puerto, List<InetAddress> ips) throws IOException {
        List<JmDNS> instancias = new ArrayList<>();
        try {
            for (InetAddress ip : ips) {
                // Una instancia por interfaz: cada una responde con SU IP para "nombre.local"
                JmDNS j = JmDNS.create(ip, nombre);
                instancias.add(j);
                j.registerService(ServiceInfo.create("_http._tcp.local.", "Punto de Venta", puerto, 0, 0, "path=/"));
            }
        } catch (IOException | RuntimeException e) {
            cerrar(instancias);
            throw e;
        }
        String host = instancias.get(0).getHostName();
        String limpio = host == null ? null : host.replaceAll("\\.$", "");
        return new Registro() {
            @Override public String host() { return limpio; }
            @Override public void close() { cerrar(instancias); }
        };
    }

    private static void cerrar(List<JmDNS> instancias) {
        for (JmDNS j : instancias) {
            try { j.close(); } catch (IOException | RuntimeException ignorada) { /* cierre en voz baja */ }
        }
    }
}
