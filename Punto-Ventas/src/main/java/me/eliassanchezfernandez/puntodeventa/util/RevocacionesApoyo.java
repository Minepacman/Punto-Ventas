package me.eliassanchezfernandez.puntodeventa.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lista de revocación de claves de donador, firmada con la misma llave Ed25519
 * que las claves, publicada en el repositorio ({@code claves/revocadas.pdvr})
 * y descargada a diario por el buscador de actualizaciones. Sin servidor.
 *
 * <pre>
 *   PDVR1.&lt;base64url(json)&gt;.&lt;base64url(firma)&gt;
 *   json = {"v":1,"emitido":"2026-11-12","revocadas":["ABCD2345"],"claves_retiradas":["3f9a1c2e"]}
 * </pre>
 *
 * Reglas (las aplica DonadorService): <b>fail-open</b> (sin internet se usa la
 * copia local; sin copia, nada está revocado) y anti-retroceso (una lista con
 * {@code emitido} anterior a la guardada se ignora). Revocar es cosmético para
 * el programa —nada se bloquea—; la revocación real es que el desarrollador
 * ya no atiende esa clave.
 */
public final class RevocacionesApoyo {

    public static final String PREFIJO = "PDVR1";
    public static final int VERSION = 1;
    /** Tope del archivo descargado (una lista de miles de ids cabe de sobra) */
    public static final int TAMANO_MAXIMO = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private RevocacionesApoyo() { }

    /** Contenido de una lista cuya firma ya se comprobó */
    public record Lista(LocalDate emitido, Set<String> revocadas, Set<String> clavesRetiradas) {
        public Lista {
            if (emitido == null) throw new IllegalArgumentException("La lista necesita fecha de emisión.");
            // Ordenadas (TreeSet) para que el JSON firmado sea siempre el mismo con los mismos datos
            revocadas = java.util.Collections.unmodifiableSet(new TreeSet<>(revocadas == null ? Set.of() : revocadas));
            clavesRetiradas = java.util.Collections.unmodifiableSet(new TreeSet<>(clavesRetiradas == null ? Set.of() : clavesRetiradas));
        }

        public static Lista vacia(LocalDate emitido) {
            return new Lista(emitido, Set.of(), Set.of());
        }

        public boolean contiene(String id) {
            return revocadas.contains(id);
        }
    }

    public static final class ListaInvalidaException extends RuntimeException {
        public ListaInvalidaException(String mensaje) {
            super(mensaje);
        }
    }

    // ── Verificar ─────────────────────────────────────────────────────────

    /**
     * Comprueba firma y formato del texto descargado.
     * @throws ListaInvalidaException si no es una lista firmada por el desarrollador
     */
    public static Lista verificar(String texto, ClaveDonador verificador) {
        String limpio = ClaveDonador.limpiar(texto);
        if (limpio.isEmpty()) throw new ListaInvalidaException("Lista vacía.");
        if (limpio.length() > TAMANO_MAXIMO) throw new ListaInvalidaException("La lista es demasiado grande.");
        String[] partes = limpio.split("\\.", -1);
        if (partes.length != 3 || !PREFIJO.equals(partes[0])) {
            throw new ListaInvalidaException("No es una lista de revocación (" + PREFIJO + ").");
        }
        byte[] carga, firma;
        try {
            carga = Base64.getUrlDecoder().decode(partes[1]);
            firma = Base64.getUrlDecoder().decode(partes[2]);
        } catch (IllegalArgumentException e) {
            throw new ListaInvalidaException("Lista incompleta o mal codificada.");
        }
        if (!verificador.firmaValida(carga, firma, null)) {
            throw new ListaInvalidaException("La firma de la lista de revocación no corresponde.");
        }
        return parsear(new String(carga, StandardCharsets.UTF_8));
    }

    private static Lista parsear(String json) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(json);
        } catch (IOException e) {
            throw new ListaInvalidaException("JSON inválido.");
        }
        if (raiz == null || !raiz.isObject() || raiz.path("v").asInt(0) != VERSION) {
            throw new ListaInvalidaException("Versión de lista no reconocida.");
        }
        LocalDate emitido;
        try {
            emitido = LocalDate.parse(raiz.path("emitido").asText(""));
        } catch (DateTimeParseException e) {
            throw new ListaInvalidaException("Fecha de emisión inválida.");
        }
        return new Lista(emitido, textos(raiz.path("revocadas")), textos(raiz.path("claves_retiradas")));
    }

    private static Set<String> textos(JsonNode n) {
        Set<String> s = new TreeSet<>();
        if (n != null && n.isArray()) for (JsonNode x : n) if (x.isTextual() && !x.asText().isBlank()) s.add(x.asText().trim());
        return s;
    }

    // ── Emitir (herramienta del desarrollador y pruebas) ──────────────────

    public static String emitir(Lista lista, PrivateKey privada) {
        byte[] carga = json(lista).getBytes(StandardCharsets.UTF_8);
        return ClaveDonador.armar(PREFIJO, carga, ClaveDonador.firmar(carga, privada));
    }

    static String json(Lista lista) {
        ObjectNode o = JSON.createObjectNode();
        o.put("v", VERSION);
        o.put("emitido", lista.emitido().toString());
        ArrayNode r = o.putArray("revocadas");
        lista.revocadas().forEach(r::add);
        ArrayNode k = o.putArray("claves_retiradas");
        lista.clavesRetiradas().forEach(k::add);
        return o.toString();
    }

    /** Conjunto a partir de ids sueltos (ayuda para la herramienta) */
    public static Set<String> conjunto(Collection<String> ids) {
        return ids == null ? Set.of() : new TreeSet<>(ids);
    }
}
