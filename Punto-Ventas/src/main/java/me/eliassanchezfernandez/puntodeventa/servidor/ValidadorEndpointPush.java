package me.eliassanchezfernandez.puntodeventa.servidor;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Lista cerrada de servicios de push a los que el programa está dispuesto a hacer POST. Un
 * endpoint lo manda el teléfono (cualquier usuario con sesión), así que sin esta lista la
 * computadora de la tienda podría usarse para pedir URLs arbitrarias (SSRF): la red local,
 * el servidor de PostgreSQL, etc. Solo https, sin usuario:clave, sin puerto raro.
 */
public final class ValidadorEndpointPush {

    private ValidadorEndpointPush() { }

    /** Hosts exactos */
    static final List<String> HOSTS = List.of(
            "fcm.googleapis.com",                       // Chrome, Edge, Brave, Samsung Internet (Android y escritorio)
            "updates.push.services.mozilla.com",        // Firefox
            "web.push.apple.com");                      // Safari / iOS
    /** Sufijos (subdominios) */
    static final List<String> SUFIJOS = List.of(
            ".push.apple.com",                          // Safari (p. ej. web.push.apple.com y regionales)
            ".notify.windows.com",                      // Edge en Windows (WNS)
            ".push.services.mozilla.com");              // Firefox (regiones)

    public static boolean permitido(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > 1000) return false;
        try {
            return permitido(URI.create(endpoint.trim()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean permitido(URI u) {
        if (u == null || !"https".equalsIgnoreCase(u.getScheme())) return false;
        if (u.getRawUserInfo() != null || u.getRawFragment() != null) return false;
        if (u.getPort() != -1 && u.getPort() != 443) return false;
        String host = u.getHost();
        if (host == null || host.isBlank()) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") || host.matches("^[0-9.]+$")) return false;    // IP literal: nunca
        if (u.getRawPath() == null || u.getRawPath().length() < 2) return false;
        if (HOSTS.contains(host)) return true;
        for (String s : SUFIJOS) if (host.endsWith(s) && host.length() > s.length()) return true;
        return false;
    }

    /** Origen (esquema://host) para la audiencia del JWT de VAPID */
    public static String origen(String endpoint) {
        URI u = URI.create(endpoint.trim());
        return "https://" + u.getHost().toLowerCase(Locale.ROOT);
    }
}
