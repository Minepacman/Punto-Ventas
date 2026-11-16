package me.eliassanchezfernandez.puntodeventa.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paquetes de iconos que se pueden elegir en Configuración → Apariencia. Lucide (el de siempre) vive en
 * {@link Iconos}; los demás son un subconjunto (solo los iconos que usa el programa) en
 * {@code resources/iconos/<id>.json}, generado por {@code herramientas/iconos/generar-paquetes.mjs}
 * con las rutas normalizadas a un viewBox de 24.
 *
 * <p>Prefijos de cada ruta en esos archivos: {@code #} = rellenar (en un estilo de trazo),
 * {@code ^} = trazar (en un estilo de relleno), {@code !} = regla par-impar, {@code ~} = capa
 * translúcida (duotono). Lo que falte cae en Lucide: nunca queda un hueco.</p>
 */
public final class PaquetesIconos {

    private PaquetesIconos() { }

    /** Paquetes incluidos, en el orden en que se ofrecen */
    public static final List<String> IDS = List.of("lucide", "tabler", "phosphor", "heroicons", "material", "iconoir");

    /** Un trazo listo para dibujar */
    public record Ruta(String d, boolean rellenar, boolean trazar, boolean parImpar, boolean translucida) { }

    /** Cómo se dibuja un icono en un paquete y estilo */
    public record Dibujo(List<Ruta> rutas, double grosorBase) { }

    /** Un estilo de un paquete */
    public record Estilo(String id, String nombre, boolean deTrazo) { }

    /** Datos de un paquete (para la ventana de Apariencia y Acerca de) */
    public record Paquete(String id, String nombre, String licencia, String version, List<Estilo> estilos) {
        public Estilo estilo(String id) {
            for (Estilo e : estilos) if (e.id().equals(id)) return e;
            return estilos.get(0);
        }
    }

    private static final Map<String, Map<String, Object>> CARGADOS = new ConcurrentHashMap<>();
    private static final Map<String, Dibujo> CACHE = new ConcurrentHashMap<>();
    private static volatile ObjectMapper json;

    private static final Paquete LUCIDE = new Paquete("lucide", "Lucide (de fábrica)", "ISC", "", List.of(
            new Estilo("contorno", "Contorno", true),
            new Estilo("duotono", "Duotono", true)));

    /** Nombre visible de cada estilo */
    public static String nombreEstilo(String id) {
        return switch (id) {
            case "contorno" -> "Contorno";
            case "relleno" -> "Relleno";
            case "duotono" -> "Duotono";
            case "fino" -> "Fino";
            case "grueso" -> "Grueso";
            default -> id;
        };
    }

    /** Paquetes disponibles (los que no se puedan leer se omiten) */
    public static List<Paquete> disponibles() {
        List<Paquete> lista = new ArrayList<>();
        for (String id : IDS) {
            Paquete p = paquete(id);
            if (p != null) lista.add(p);
        }
        return lista;
    }

    public static Paquete paquete(String id) {
        if ("lucide".equals(id) || id == null) return LUCIDE;
        JsonNode raiz = leer(id);
        if (raiz == null) return null;
        List<Estilo> estilos = new ArrayList<>();
        JsonNode est = raiz.path("estilos");
        for (var it = est.fieldNames(); it.hasNext(); ) {
            String e = it.next();
            estilos.add(new Estilo(e, nombreEstilo(e), "trazo".equals(est.path(e).path("tipo").asText())));
        }
        if (estilos.isEmpty()) return null;
        return new Paquete(id, raiz.path("nombre").asText(id), raiz.path("licencia").asText(""),
                raiz.path("version").asText(""), Collections.unmodifiableList(estilos));
    }

    /** Cómo dibujar {@code nombre} con el paquete y estilo dados (cae en Lucide si no existe) */
    public static Dibujo dibujo(String paquete, String estilo, String nombre) {
        String clave = paquete + "/" + estilo + "/" + nombre;
        Dibujo d = CACHE.get(clave);
        if (d != null) return d;
        d = calcular(paquete, estilo, nombre);
        CACHE.put(clave, d);
        return d;
    }

    private static Dibujo calcular(String paquete, String estilo, String nombre) {
        if (paquete != null && !"lucide".equals(paquete)) {
            JsonNode raiz = leer(paquete);
            if (raiz != null) {
                JsonNode e = raiz.path("estilos").path(estilo);
                if (e.isMissingNode()) e = raiz.path("estilos").path("contorno");
                JsonNode trazos = e.path("iconos").path(nombre);
                if (trazos.isArray() && trazos.size() > 0) {
                    boolean deTrazo = "trazo".equals(e.path("tipo").asText());
                    double base = e.path("grosor").asDouble(2.0);
                    List<Ruta> rutas = new ArrayList<>();
                    for (JsonNode t : trazos) rutas.add(ruta(t.asText(), deTrazo));
                    return new Dibujo(List.copyOf(rutas), base);
                }
            }
        }
        return lucide(nombre, "duotono".equals(estilo));
    }

    /** Nombres de icono que trae un paquete (los del escritorio y los de la página móvil) */
    public static List<String> nombres(String paquete) {
        List<String> l = new ArrayList<>();
        if (paquete == null || "lucide".equals(paquete)) {
            l.addAll(Iconos.nombres());
            return l;
        }
        JsonNode raiz = leer(paquete);
        if (raiz == null) return l;
        var estilos = raiz.path("estilos").fields();
        if (!estilos.hasNext()) return l;
        raiz.path("estilos").path(estilos.next().getKey()).path("iconos").fieldNames().forEachRemaining(l::add);
        return l;
    }

    /** Interpreta los prefijos de una ruta del archivo */
    public static Ruta ruta(String texto, boolean estiloDeTrazo) {
        boolean translucida = false, parImpar = false, invertir = false;
        int i = 0;
        while (i < texto.length() && "~!#^".indexOf(texto.charAt(i)) >= 0) {
            char c = texto.charAt(i++);
            if (c == '~') translucida = true;
            else if (c == '!') parImpar = true;
            else invertir = true;   // '#' en trazo = rellenar; '^' en relleno = trazar
        }
        String d = texto.substring(i);
        boolean rellenar = estiloDeTrazo == invertir;
        return new Ruta(d, rellenar, !rellenar, parImpar, translucida);
    }

    /** Lucide: contorno; en duotono además se rellenan al 20 % las figuras cerradas */
    private static Dibujo lucide(String nombre, boolean duotono) {
        List<Ruta> rutas = new ArrayList<>();
        for (String d : Iconos.trazos(nombre)) {
            if (duotono && esCerrada(d)) rutas.add(new Ruta(d, true, false, false, true));
            rutas.add(new Ruta(d, false, true, false, false));
        }
        return new Dibujo(List.copyOf(rutas), 2.0);
    }

    /** ¿La ruta encierra un área? (termina en Z o es un círculo de dos arcos) */
    static boolean esCerrada(String d) {
        String t = d.trim();
        if (t.endsWith("z") || t.endsWith("Z")) return true;
        return t.matches("M[\\d.\\s-]+a[\\d.]+ [\\d.]+ 0 1 0 [\\d.]+ 0a[\\d.]+ [\\d.]+ 0 1 0-[\\d.]+ 0");
    }

    private static JsonNode leer(String id) {
        if (!id.matches("[a-z0-9-]{1,30}")) return null;
        Map<String, Object> m = CARGADOS.computeIfAbsent(id, k -> {
            Map<String, Object> r = new LinkedHashMap<>();
            try (InputStream in = PaquetesIconos.class.getResourceAsStream("/iconos/" + k + ".json")) {
                if (in != null) r.put("raiz", mapper().readTree(in));
            } catch (Exception e) {
                System.err.println("No se pudo leer el paquete de iconos " + k + ": " + e.getMessage());
            }
            return r;
        });
        return (JsonNode) m.get("raiz");
    }

    private static ObjectMapper mapper() {
        ObjectMapper m = json;
        if (m == null) json = m = new ObjectMapper();
        return m;
    }
}
