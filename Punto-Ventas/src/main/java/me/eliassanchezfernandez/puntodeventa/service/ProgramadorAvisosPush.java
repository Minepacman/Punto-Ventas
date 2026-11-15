package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService.Aviso;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService.HoraPico;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decide QUÉ aviso de {@link AvisosService} se manda a QUÉ teléfono y CUÁNDO, y lo entrega con
 * {@link NotificacionesPushService}. Corre cada minuto en segundo plano (nunca al arrancar: el
 * primer tic espera dos minutos) y solo si el acceso móvil está encendido.
 *
 * Horarios (hora local de la computadora):
 *  - Proveedores que entregan o pasan a preventa HOY: desde las {@link #HORA_PROVEEDORES_HOY}.
 *  - Proveedores de MAÑANA (entrega o preventa): desde las {@link #HORA_PROVEEDORES_MANANA}.
 *  - "Se está acabando": como máximo dos veces al día, en el primer tic después de las
 *    {@link #HORAS_STOCK} horas; en cada corte solo salen los productos que no se avisaron hoy.
 *  - Hora pico: en la media hora anterior a la franja.
 *
 * Por suscripción se respetan: interruptor general, tipo de aviso, horas de silencio (21:00 a
 * 7:00 por defecto) y "nunca el mismo aviso el mismo día" (ids guardados en la suscripción).
 * Los importes (nota media del proveedor) los decide AvisosService según los permisos del
 * usuario, porque se calcula EJECUTANDO COMO ese usuario.
 */
@Service
public class ProgramadorAvisosPush {

    public static final int HORA_PROVEEDORES_HOY = 8;
    public static final int HORA_PROVEEDORES_MANANA = 19;
    public static final int[] HORAS_STOCK = { 9, 17 };
    public static final int MINUTOS_ANTES_PICO = 30;

    @Autowired private NotificacionesPushService pushService;
    @Autowired private AvisosService avisosService;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private Clock reloj;

    /** false en pruebas: se llama a {@link #evaluar(LocalDateTime)} a mano */
    @Value("${app.movil.push.programador:true}")
    private boolean programadorActivo;

    private final Object candado = new Object();

    /** Cada minuto; el primero a los dos minutos de arrancar (nada pesado al inicio) */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void tic() {
        if (!programadorActivo) return;
        try {
            if (!configuracionService.obtener().isMovilActivo()) return;
            evaluar(LocalDateTime.now(reloj));
        } catch (RuntimeException e) {
            System.err.println("Notificaciones push: " + e.getMessage());
        }
    }

    /** Resumen de un tic (para pruebas y bitácora) */
    public record Resumen(int suscripcionesRevisadas, int enviados, int fallidos) { }

    /**
     * Revisa todas las suscripciones activas y manda lo que toque a esa hora. Público para que
     * las pruebas lo invoquen con una hora concreta.
     */
    public Resumen evaluar(LocalDateTime ahora) {
        synchronized (candado) {
            List<SuscripcionPush> todas = pushService.activas();
            if (todas.isEmpty()) return new Resumen(0, 0, 0);
            Map<Long, List<SuscripcionPush>> porUsuario = new LinkedHashMap<>();
            for (SuscripcionPush s : todas) porUsuario.computeIfAbsent(s.getCajeroId(), k -> new ArrayList<>()).add(s);
            int enviados = 0, fallidos = 0;
            for (Map.Entry<Long, List<SuscripcionPush>> e : porUsuario.entrySet()) {
                Cajero cajero = cajeroRepo.findById(e.getKey()).orElse(null);
                if (cajero == null || !cajero.isActivo()) {
                    pushService.quitarDeCajero(e.getKey());          // usuario eliminado o desactivado: sus teléfonos ya no reciben nada
                    continue;
                }
                List<Pendiente> pendientes;
                try {
                    pendientes = sesionService.ejecutarComo(cajero, "push", () -> pendientesDe(ahora));
                } catch (RuntimeException ex) {
                    System.err.println("Notificaciones push (" + cajero.getUsuario() + "): " + ex.getMessage());
                    continue;
                }
                for (SuscripcionPush s : e.getValue()) {
                    if (!s.isActiva() || s.enSilencio(ahora.getHour())) continue;
                    for (Pendiente p : seleccionar(s, pendientes, ahora.toLocalDate())) {
                        NotificacionesPushService.Envio r = pushService.enviar(s, mensaje(p.aviso()), p.aviso().prioridad() == 1);
                        if (r.ok()) enviados++; else fallidos++;
                    }
                }
            }
            return new Resumen(todas.size(), enviados, fallidos);
        }
    }

    /** Aviso con la hora a partir de la cual se manda y, para stock, el corte al que pertenece */
    record Pendiente(Aviso aviso, boolean due, String marcaCorte) { }

    /** Avisos del usuario del ámbito actual, ya decidido cuáles están "en hora" */
    List<Pendiente> pendientesDe(LocalDateTime ahora) {
        List<Pendiente> r = new ArrayList<>();
        AvisosService.Avisos a = avisosService.avisos(ahora);
        HoraPico pico = avisosService.horaPicoHoy(ahora);
        for (Aviso av : a.avisos()) {
            switch (av.tipo()) {
                case PROVEEDOR -> r.add(new Pendiente(av, av.id().endsWith("-hoy") ? ahora.getHour() >= HORA_PROVEEDORES_HOY
                                                                                 : ahora.getHour() >= HORA_PROVEEDORES_MANANA, null));
                case STOCK -> {
                    String corte = corteStock(ahora.getHour());
                    r.add(new Pendiente(av, corte != null, corte));
                }
                case HORA_PICO -> {
                    boolean due = pico != null && !ahora.isBefore(ahora.toLocalDate().atTime(pico.desde(), 0).minusMinutes(MINUTOS_ANTES_PICO))
                            && ahora.isBefore(ahora.toLocalDate().atTime(pico.desde(), 0));
                    r.add(new Pendiente(av, due, null));
                }
            }
        }
        return r;
    }

    /** "stock@9" / "stock@17": el último corte de stock ya alcanzado a esa hora; null antes del primero */
    public static String corteStock(int hora) {
        String r = null;
        for (int h : HORAS_STOCK) if (hora >= h) r = "stock@" + h;
        return r;
    }

    /**
     * Qué avisos salen para esta suscripción: los en hora, de un tipo que quiere y no mandados hoy.
     * Los de stock solo en el primer tic de cada corte (la marca del corte se guarda como enviada).
     * Anota los ids en la suscripción y la guarda.
     */
    List<Pendiente> seleccionar(SuscripcionPush s, List<Pendiente> pendientes, LocalDate hoy) {
        Set<String> enviados = s.enviados(hoy);
        List<Pendiente> salen = new ArrayList<>();
        boolean cambio = false;
        for (Pendiente p : pendientes) {
            if (!p.due() || !s.quiere(p.aviso().tipo().name()) || enviados.contains(p.aviso().id())) continue;
            if (p.marcaCorte() != null && enviados.contains(p.marcaCorte())) continue;   // ya pasó el corte de stock de esta hora
            salen.add(p);
        }
        for (Pendiente p : salen) {
            s.marcarEnviado(hoy, p.aviso().id());
            cambio = true;
        }
        // Las marcas de corte se anotan aunque no haya salido nada: el siguiente corte traerá solo lo nuevo
        for (Pendiente p : pendientes) {
            if (p.marcaCorte() != null && p.due() && !enviados.contains(p.marcaCorte())) {
                s.marcarEnviado(hoy, p.marcaCorte());
                enviados.add(p.marcaCorte());
                cambio = true;
            }
        }
        if (cambio) pushService.guardar(s);
        return salen;
    }

    /** Título, cuerpo y a dónde abre la página al tocar la notificación */
    public static NotificacionesPushService.Mensaje mensaje(Aviso a) {
        String url = "/";
        if (a.accion() != null && a.accion().seccion() != null) {
            StringBuilder sb = new StringBuilder("/#/").append(a.accion().seccion());
            List<String> q = new ArrayList<>();
            if (a.accion().proveedorId() != null) q.add("proveedorId=" + a.accion().proveedorId());
            if (a.accion().filtro() != null) q.add("filtro=" + URLEncoder.encode(a.accion().filtro(), StandardCharsets.UTF_8));
            if (a.accion().buscar() != null) q.add("buscar=" + URLEncoder.encode(a.accion().buscar(), StandardCharsets.UTF_8));
            if (!q.isEmpty()) sb.append('?').append(String.join("&", q));
            url = sb.toString();
        }
        return new NotificacionesPushService.Mensaje(a.titulo(), a.detalle(), a.id(), url);
    }
}
