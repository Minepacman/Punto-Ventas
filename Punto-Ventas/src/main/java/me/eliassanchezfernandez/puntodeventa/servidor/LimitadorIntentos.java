package me.eliassanchezfernandez.puntodeventa.servidor;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de intentos de login FALLIDOS por IP (ventana deslizante). Se suma al
 * bloqueo por usuario de SesionService: aquel frena a quien adivina la clave
 * de UN usuario, este a quien prueba muchos usuarios desde la misma IP.
 * Un login correcto NO reinicia la cuenta (para que quien tenga una cuenta
 * válida no pueda "limpiar" sus intentos fallidos contra otras).
 */
public class LimitadorIntentos {

    private final Clock reloj;
    private final int maximo;
    private final Duration ventana;
    private final ConcurrentHashMap<String, Deque<Long>> fallos = new ConcurrentHashMap<>();

    public LimitadorIntentos(Clock reloj, int maximo, Duration ventana) {
        this.reloj = reloj;
        this.maximo = maximo;
        this.ventana = ventana;
    }

    /** Segundos que faltan para poder reintentar; 0 si la IP no está limitada */
    public long segundosDeEspera(String ip) {
        Deque<Long> q = fallos.get(ip);
        if (q == null) return 0;
        long ahora = reloj.millis();
        synchronized (q) {
            purgar(q, ahora);
            if (q.size() < maximo) return 0;
            long liberar = q.peekFirst() + ventana.toMillis();
            return Math.max(1, (liberar - ahora + 999) / 1000);
        }
    }

    public void registrarFallo(String ip) {
        long ahora = reloj.millis();
        if (fallos.size() > 5000) {                       // no crecer sin límite
            fallos.entrySet().removeIf(e -> {
                synchronized (e.getValue()) { purgar(e.getValue(), ahora); return e.getValue().isEmpty(); }
            });
        }
        Deque<Long> q = fallos.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            purgar(q, ahora);
            q.addLast(ahora);
        }
    }

    private void purgar(Deque<Long> q, long ahora) {
        long limite = ahora - ventana.toMillis();
        while (!q.isEmpty() && q.peekFirst() <= limite) q.pollFirst();
    }
}
