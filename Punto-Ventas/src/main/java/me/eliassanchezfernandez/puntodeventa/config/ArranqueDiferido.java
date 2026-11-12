package me.eliassanchezfernandez.puntodeventa.config;

import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Trabajo de arranque que NO debe retrasar la primera ventana: acceso móvil
 * (abrir el puerto, enumerar interfaces de red, mDNS), migración de
 * proveedores, registro del clima... Antes corrían de forma síncrona en
 * {@code ApplicationReadyEvent}, es decir, antes de que
 * {@code SpringApplication.run} devolviera y por tanto antes del login.
 *
 * Ahora cada listener entrega su tarea a {@link #ejecutar}; un único hilo de
 * baja prioridad las corre en orden cuando la ventana ya se dibujó
 * ({@link #senalarVentanaLista}, la llama PuntoDeVentaApp) o, como máximo,
 * tras {@code app.arranque.diferido-espera-ms} (3 s) si nadie avisa (p. ej.
 * una herramienta sin interfaz). Al terminar todas, anota en la
 * configuración la versión del esquema ({@link VersionEsquema}).
 *
 * {@code app.arranque.diferido=false} (pruebas) ejecuta cada tarea en el acto,
 * en el mismo hilo, como antes.
 */
@Component
public class ArranqueDiferido {

    private static final CountDownLatch VENTANA_LISTA = new CountDownLatch(1);

    @Value("${app.arranque.diferido:true}")
    private boolean diferido;

    @Value("${app.arranque.diferido-espera-ms:3000}")
    private long esperaMaximaMs;

    @Autowired private ConfiguracionService configuracionService;

    private final Deque<Runnable> pendientes = new ArrayDeque<>();
    private Thread hilo;
    /** true cuando ya se encoló la última tarea del arranque: el hilo termina al vaciar la cola */
    private boolean arranqueCompleto;

    /** Lo llama la interfaz cuando la primera ventana ya está dibujada */
    public static void senalarVentanaLista() {
        VENTANA_LISTA.countDown();
    }

    /** Encola una tarea (o la corre ya, en modo no diferido). Nunca lanza. */
    public void ejecutar(String nombre, Runnable tarea) {
        if (!diferido) {
            correr(nombre, tarea);
            return;
        }
        synchronized (this) {
            pendientes.addLast(() -> correr(nombre, tarea));
            if (hilo == null || !hilo.isAlive()) {
                hilo = new Thread(this::bucle, "arranque-diferido");
                hilo.setDaemon(true);
                hilo.setPriority(Thread.MIN_PRIORITY);
                hilo.start();
            }
            notifyAll();
        }
    }

    /** Última tarea del arranque: dejar constancia de que el esquema quedó al día con esta versión */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void marcarVersionDeEsquema() {
        ejecutar("versión del esquema", () -> configuracionService.marcarVersionEsquema(VersionApp.version()));
        synchronized (this) {
            arranqueCompleto = true;
            notifyAll();
        }
    }

    /** Pruebas / cierre: espera a que el hilo termine lo encolado (true si terminó a tiempo) */
    public boolean esperar(long ms) throws InterruptedException {
        Thread h;
        synchronized (this) { h = hilo; }
        if (h == null) return true;
        h.join(ms);
        return !h.isAlive();
    }

    /** ¿Quedan tareas por correr? (pruebas) */
    public synchronized boolean hayPendientes() {
        return !pendientes.isEmpty();
    }

    private void bucle() {
        try {
            VENTANA_LISTA.await(Math.max(0, esperaMaximaMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        while (true) {
            Runnable r;
            synchronized (this) {
                while (pendientes.isEmpty()) {
                    if (arranqueCompleto) return;
                    try {
                        wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                r = pendientes.pollFirst();
            }
            r.run();
        }
    }

    private static void correr(String nombre, Runnable tarea) {
        try {
            tarea.run();
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Arranque (" + nombre + "): " + e.getMessage());
        }
    }
}
