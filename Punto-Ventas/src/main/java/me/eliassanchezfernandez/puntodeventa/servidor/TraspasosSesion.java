package me.eliassanchezfernandez.puntodeventa.servidor;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Códigos de un solo uso para «mudar» la sesión del teléfono de la página normal (http) a la
 * segura (https) —son orígenes distintos y no comparten lo guardado en el navegador— o a la
 * página agregada a la pantalla de inicio del iPhone (que tampoco comparte lo de Safari).
 *
 *  - El código son 256 bits aleatorios en Base64url; viaja en el fragmento de la URL (#...), que
 *    el navegador nunca manda al servidor ni pone en el Referer.
 *  - Solo se guarda su SHA-256, en MEMORIA (si el programa se reinicia, se pierden: basta con
 *    volver a entrar). Vence en minutos y se borra al canjearlo (un solo uso).
 *  - Lo que se canjea no es una contraseña: el servidor vuelve a validar la sesión original al
 *    canjear (si la cerraron o el usuario se desactivó, el código ya no sirve).
 */
final class TraspasosSesion {

    /** Pasar a la página segura: el usuario toca «Continuar» y la página se abre al instante */
    static final Duration VIGENCIA_SEGURO = Duration.ofMinutes(3);
    /** Abrir desde el icono de inicio (iPhone): da tiempo de agregarla y abrirla */
    static final Duration VIGENCIA_INICIO = Duration.ofMinutes(15);
    /** Códigos pendientes por sesión: al pedir otro se descarta el más viejo */
    static final int MAX_POR_SESION = 3;
    /** Tope de memoria: a lo más estos pendientes en total */
    static final int MAX_TOTAL = 500;

    /** Lo que se recuerda de un código pendiente (nunca el código en claro) */
    record Pendiente(Long sesionId, String tokenHash, Instant vence) { }

    private final Clock reloj;
    private final SecureRandom azar = new SecureRandom();
    /** hash del código → pendiente, en orden de emisión (el primero es el más viejo) */
    private final LinkedHashMap<String, Pendiente> pendientes = new LinkedHashMap<>();

    TraspasosSesion(Clock reloj) {
        this.reloj = reloj;
    }

    /** Emite un código nuevo para la sesión (identificada por su id y el hash de su token) */
    synchronized String emitir(Long sesionId, String tokenHash, Duration vigencia) {
        purgar();
        int deEsta = 0;
        for (Pendiente p : pendientes.values()) if (p.sesionId().equals(sesionId)) deEsta++;
        for (Iterator<Map.Entry<String, Pendiente>> it = pendientes.entrySet().iterator(); it.hasNext() && deEsta >= MAX_POR_SESION; ) {
            if (it.next().getValue().sesionId().equals(sesionId)) { it.remove(); deEsta--; }
        }
        while (pendientes.size() >= MAX_TOTAL) {
            Iterator<String> it = pendientes.keySet().iterator();
            it.next();
            it.remove();
        }
        byte[] b = new byte[32];
        azar.nextBytes(b);
        String codigo = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        pendientes.put(hash(codigo), new Pendiente(sesionId, tokenHash, reloj.instant().plus(vigencia)));
        return codigo;
    }

    /** Canjea (y borra) el código. Vacío si no existe, ya se usó o venció. */
    synchronized Optional<Pendiente> canjear(String codigo) {
        if (!formatoValido(codigo)) return Optional.empty();
        Pendiente p = pendientes.remove(hash(codigo));
        if (p == null || !reloj.instant().isBefore(p.vence())) return Optional.empty();
        return Optional.of(p);
    }

    /** Pendientes vigentes (para las pruebas) */
    synchronized int pendientes() {
        purgar();
        return pendientes.size();
    }

    private void purgar() {
        Instant ahora = reloj.instant();
        pendientes.values().removeIf(p -> !ahora.isBefore(p.vence()));
    }

    /** 43 caracteres Base64url (256 bits sin relleno): cualquier otra cosa se rechaza sin buscar */
    static boolean formatoValido(String codigo) {
        return codigo != null && codigo.length() == 43 && codigo.matches("[A-Za-z0-9_-]{43}");
    }

    private static String hash(String codigo) {
        return me.eliassanchezfernandez.puntodeventa.service.SesionMovilService.hash(codigo);
    }
}
