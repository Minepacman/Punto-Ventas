package me.eliassanchezfernandez.puntodeventa.servidor;

import jakarta.annotation.PreDestroy;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Lecturas de fotos de tickets que manda el teléfono. Leer una foto tarda (hasta un minuto en una PC
 * vieja), así que NO se hace dentro de la petición HTTP: {@code POST /api/tickets-proveedor/leer} deja la foto
 * aquí y responde de inmediato con un id; el teléfono pregunta cada segundo cómo va
 * ({@code GET /api/tickets-proveedor/lectura}) y puede cancelarla. Así no se ocupa uno de los pocos hilos del
 * servidor móvil, ni se corta la conexión por tiempo.
 *
 * Todo en memoria: una lectura se olvida {@link #VIGENCIA} después de creada (la foto ya no hace falta). Hay
 * cupo para {@link #MAX_EN_CURSO} lecturas a la vez (el OCR corre de una en una en su propia hebra de baja
 * prioridad, para no frenar la caja) y para {@link #MAX_GUARDADAS} en total.
 */
@Component
public class LecturasTicketMovil {

    static final int MAX_EN_CURSO = 2;
    static final int MAX_GUARDADAS = 8;
    static final Duration VIGENCIA = Duration.ofMinutes(30);

    public enum Estado { LEYENDO, LISTO, ERROR, CANCELADO }

    /** Una foto en lectura (o ya leída) de un usuario del teléfono */
    public static final class Lectura {
        final String id;
        final long cajeroId;
        final long creada;
        final byte[] foto;
        volatile Estado estado = Estado.LEYENDO;
        volatile TicketProveedorService.Lectura resultado;
        volatile String error;
        volatile Future<?> futuro;
        /** Tickets de esta foto que ya se registraron (una foto puede traer dos) */
        final java.util.Set<Integer> registrados = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Ya se mandó a la computadora para revisarla */
        volatile Long pendienteId;

        Lectura(String id, long cajeroId, byte[] foto, long creada) {
            this.id = id; this.cajeroId = cajeroId; this.foto = foto; this.creada = creada;
        }

        public String id() { return id; }
        public Estado estado() { return estado; }
        public TicketProveedorService.Lectura resultado() { return resultado; }
        public String error() { return error; }
        public byte[] foto() { return foto; }
        public long segundos() { return (System.currentTimeMillis() - creada) / 1000; }
    }

    /** No hay cupo: el teléfono recibe 503 OCUPADO y reintenta solo */
    public static final class SinCupo extends RuntimeException {
        SinCupo() { super(null, null, false, false); }
    }

    @Autowired private SesionService sesionService;
    @Autowired private TicketProveedorService servicio;

    private final SecureRandom azar = new SecureRandom();
    private final Map<String, Lectura> lecturas = new LinkedHashMap<>();
    private final ExecutorService hebras = Executors.newFixedThreadPool(MAX_EN_CURSO, r -> {
        Thread t = new Thread(r, "lectura-ticket-movil");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Empieza a leer la foto como {@code cajero}; devuelve la lectura (aún LEYENDO) */
    public synchronized Lectura empezar(Cajero cajero, String ip, byte[] foto) {
        purgar();
        long enCurso = lecturas.values().stream().filter(l -> l.estado == Estado.LEYENDO).count();
        if (enCurso >= MAX_EN_CURSO) throw new SinCupo();
        while (lecturas.size() >= MAX_GUARDADAS) {
            String viejo = lecturas.values().stream().filter(l -> l.estado != Estado.LEYENDO).map(l -> l.id).findFirst().orElse(null);
            if (viejo == null) throw new SinCupo();
            lecturas.remove(viejo);
        }
        byte[] b = new byte[16];
        azar.nextBytes(b);
        Lectura l = new Lectura(HexFormat.of().formatHex(b), cajero.getId(), foto, System.currentTimeMillis());
        lecturas.put(l.id, l);
        l.futuro = hebras.submit(() -> leer(l, cajero, ip));
        return l;
    }

    private void leer(Lectura l, Cajero cajero, String ip) {
        try {
            TicketProveedorService.Lectura r = sesionService.ejecutarComo(cajero, ip, () -> {
                try {
                    return servicio.leerFoto(l.foto);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            if (l.estado == Estado.CANCELADO) return;
            l.resultado = r;
            l.estado = Estado.LISTO;
        } catch (java.io.UncheckedIOException e) {
            if (l.estado == Estado.CANCELADO || e.getCause() instanceof InterruptedIOException) { l.estado = Estado.CANCELADO; return; }
            l.error = "No se pudo leer la foto: " + (e.getCause().getMessage() == null ? "error del lector" : e.getCause().getMessage());
            l.estado = Estado.ERROR;
        } catch (NegocioException e) {
            l.error = e.getMessage();
            l.estado = Estado.ERROR;
        } catch (RuntimeException e) {
            if (l.estado == Estado.CANCELADO) return;
            System.err.println("Lectura de ticket desde el teléfono falló: " + e);
            l.error = "No se pudo leer la foto.";
            l.estado = Estado.ERROR;
        }
    }

    /** La lectura {@code id} de ESTE usuario (la de otro no se ve) */
    public synchronized Optional<Lectura> de(String id, long cajeroId) {
        purgar();
        Lectura l = id == null ? null : lecturas.get(id);
        return l != null && l.cajeroId == cajeroId ? Optional.of(l) : Optional.empty();
    }

    /** Detiene la lectura (el OCR se corta en el acto) y la olvida */
    public synchronized boolean cancelar(String id, long cajeroId) {
        Lectura l = id == null ? null : lecturas.get(id);
        if (l == null || l.cajeroId != cajeroId) return false;
        if (l.estado == Estado.LEYENDO) {
            l.estado = Estado.CANCELADO;
            Future<?> f = l.futuro;
            if (f != null) f.cancel(true);
        }
        lecturas.remove(id);
        return true;
    }

    /** Lecturas en curso o guardadas (pruebas) */
    synchronized int cuantas() {
        return lecturas.size();
    }

    synchronized void olvidarTodo() {
        for (Lectura l : new ArrayList<>(lecturas.values())) cancelar(l.id, l.cajeroId);
    }

    private void purgar() {
        long limite = System.currentTimeMillis() - VIGENCIA.toMillis();
        List<String> viejos = new ArrayList<>();
        for (Iterator<Lectura> it = lecturas.values().iterator(); it.hasNext(); ) {
            Lectura l = it.next();
            if (l.creada < limite) viejos.add(l.id);
        }
        for (String id : viejos) {
            Lectura l = lecturas.remove(id);
            if (l != null && l.estado == Estado.LEYENDO && l.futuro != null) l.futuro.cancel(true);
        }
    }

    @PreDestroy
    void cerrar() {
        hebras.shutdownNow();
    }
}
