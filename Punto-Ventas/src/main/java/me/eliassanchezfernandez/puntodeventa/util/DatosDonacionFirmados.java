package me.eliassanchezfernandez.puntodeventa.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;


/**
 * Datos de donación del autor (CLABE, banco, beneficiario, contacto) firmados con
 * la misma llave Ed25519 de las claves de donador, publicados en el repositorio
 * ({@code claves/donacion.pdvd}) y descargados a diario junto con la lista de
 * revocación. El programa los compara con el {@code donacion.properties} que
 * lleva incrustado: si coinciden, la ventana de donación dice «Datos verificados»;
 * si no, avisa que esos datos NO son los del autor (una copia modificada con otra
 * CLABE que conserve esta comprobación se delata sola; si la quita, ya no puede
 * decir «verificados» con la firma del autor). El sitio oficial lee el mismo archivo.
 *
 * <pre>
 *   PDVD1.&lt;base64url(json)&gt;.&lt;base64url(firma)&gt;
 *   json = {"v":1,"emitido":"2026-11-20","pendiente":false,"clabe":"0123…","banco":"BBVA",
 *           "beneficiario":"Elias Oziel Sánchez Fernández","whatsapp":"52…","correo":"…",
 *           "sitio":"https://minepacman.github.io/Punto-Ventas/"}
 * </pre>
 *
 * Mientras el autor no publique su CLABE, el archivo firmado dice {@code "pendiente":true}
 * sin CLABE, y el programa muestra «Datos de donación aún no publicados». Reglas
 * (las aplica DonadorService): fail-open (sin internet, copia local; sin copia,
 * «sin verificar») y anti-retroceso por {@code emitido}.
 */
public final class DatosDonacionFirmados {

    public static final String PREFIJO = "PDVD1";
    public static final int VERSION = 1;
    /** Tope del archivo descargado (son unos cientos de bytes) */
    public static final int TAMANO_MAXIMO = 8 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private DatosDonacionFirmados() { }

    /** Contenido de un archivo cuya firma ya se comprobó. Campos vacíos = "" (nunca null). */
    public record Datos(LocalDate emitido, boolean pendiente, String clabe, String banco, String beneficiario,
                        String whatsapp, String correo, String sitio) {
        public Datos {
            if (emitido == null) throw new IllegalArgumentException("Los datos necesitan fecha de emisión.");
            clabe = Donacion.limpiarClabe(clabe);
            banco = limpio(banco);
            beneficiario = limpio(beneficiario);
            whatsapp = limpio(whatsapp);
            correo = limpio(correo);
            sitio = limpio(sitio);
            if (!pendiente && !Donacion.clabeValida(clabe)) {
                throw new IllegalArgumentException("Los datos publicados deben llevar una CLABE válida o marcarse como pendientes.");
            }
            if (pendiente) clabe = "";
        }

        private static String limpio(String s) {
            return s == null ? "" : s.trim();
        }

        /** Los datos incrustados en el programa, tal como se publican (pendiente si la CLABE no está configurada) */
        public static Datos de(Donacion.Datos d, LocalDate emitido) {
            boolean pendiente = !d.clabeConfigurada();
            return new Datos(emitido, pendiente, pendiente ? "" : d.clabe(), sinPendiente(d.banco()),
                    sinPendiente(d.beneficiario()), d.whatsappConfigurado() ? d.soporteWhatsapp() : "",
                    d.correoConfigurado() ? d.soporteCorreo() : "", Marca.SITIO_OFICIAL);
        }

        private static String sinPendiente(String v) {
            return v == null || Donacion.PENDIENTE.equalsIgnoreCase(v.trim()) ? "" : v.trim();
        }

        /**
         * ¿Los datos incrustados en el programa son los que publicó el autor? Se comparan la CLABE
         * (sin espacios), el beneficiario y el banco (sin importar mayúsculas ni espacios de más).
         * Con datos publicados pendientes, coinciden solo si el programa tampoco tiene CLABE configurada.
         */
        public boolean coincideCon(Donacion.Datos d) {
            if (pendiente) return !d.clabeConfigurada();
            if (!d.clabeConfigurada()) return false;
            return clabe.equals(Donacion.limpiarClabe(d.clabe()))
                    && normal(beneficiario).equals(normal(d.beneficiario()))
                    && normal(banco).equals(normal(d.banco()));
        }

        private static String normal(String s) {
            return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final class DatosInvalidosException extends RuntimeException {
        public DatosInvalidosException(String mensaje) {
            super(mensaje);
        }
    }

    // ── Verificar ─────────────────────────────────────────────────────────

    /**
     * Comprueba firma y formato del texto descargado.
     * @throws DatosInvalidosException si no son datos firmados por el desarrollador
     */
    public static Datos verificar(String texto, ClaveDonador verificador) {
        String limpio = ClaveDonador.limpiar(texto);
        if (limpio.isEmpty()) throw new DatosInvalidosException("Archivo vacío.");
        if (limpio.length() > TAMANO_MAXIMO) throw new DatosInvalidosException("El archivo es demasiado grande.");
        String[] partes = limpio.split("\\.", -1);
        if (partes.length != 3 || !PREFIJO.equals(partes[0])) {
            throw new DatosInvalidosException("No son datos de donación firmados (" + PREFIJO + ").");
        }
        byte[] carga, firma;
        try {
            carga = Base64.getUrlDecoder().decode(partes[1]);
            firma = Base64.getUrlDecoder().decode(partes[2]);
        } catch (IllegalArgumentException e) {
            throw new DatosInvalidosException("Archivo incompleto o mal codificado.");
        }
        if (!verificador.firmaValida(carga, firma, null)) {
            throw new DatosInvalidosException("La firma de los datos de donación no corresponde al autor.");
        }
        return parsear(new String(carga, StandardCharsets.UTF_8));
    }

    private static Datos parsear(String json) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(json);
        } catch (IOException e) {
            throw new DatosInvalidosException("JSON inválido.");
        }
        if (raiz == null || !raiz.isObject() || raiz.path("v").asInt(0) != VERSION) {
            throw new DatosInvalidosException("Versión de datos no reconocida.");
        }
        LocalDate emitido;
        try {
            emitido = LocalDate.parse(raiz.path("emitido").asText(""));
        } catch (DateTimeParseException e) {
            throw new DatosInvalidosException("Fecha de emisión inválida.");
        }
        try {
            return new Datos(emitido, raiz.path("pendiente").asBoolean(false), raiz.path("clabe").asText(""),
                    raiz.path("banco").asText(""), raiz.path("beneficiario").asText(""), raiz.path("whatsapp").asText(""),
                    raiz.path("correo").asText(""), raiz.path("sitio").asText(""));
        } catch (IllegalArgumentException e) {
            throw new DatosInvalidosException(e.getMessage());
        }
    }

    // ── Emitir (herramienta del desarrollador y pruebas) ──────────────────

    public static String emitir(Datos datos, PrivateKey privada) {
        byte[] carga = json(datos).getBytes(StandardCharsets.UTF_8);
        return ClaveDonador.armar(PREFIJO, carga, ClaveDonador.firmar(carga, privada));
    }

    static String json(Datos d) {
        ObjectNode o = JSON.createObjectNode();
        o.put("v", VERSION);
        o.put("emitido", d.emitido().toString());
        o.put("pendiente", d.pendiente());
        o.put("clabe", d.clabe());
        o.put("banco", d.banco());
        o.put("beneficiario", d.beneficiario());
        o.put("whatsapp", d.whatsapp());
        o.put("correo", d.correo());
        o.put("sitio", d.sitio());
        return o.toString();
    }

    /** Huella SHA-256 (hex) de la llave pública oficial: el ancla de confianza que se publica en el sitio y el README */
    public static String huellaLlaveOficial() {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(ClaveDonador.clavePublicaDesde(ClaveDonador.CLAVE_PUBLICA_OFICIAL).getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }
}
