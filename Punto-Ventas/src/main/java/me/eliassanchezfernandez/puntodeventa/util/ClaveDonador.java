package me.eliassanchezfernandez.puntodeventa.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Clave de donador (constancia de apoyo): un texto firmado por el desarrollador
 * que el programa verifica SIN conexión con la clave pública que lleva dentro
 * (Ed25519, incluido en Java desde la versión 15). No hay servidor de licencias
 * ni activación en línea; una clave falsa o alterada simplemente no pasa la firma.
 *
 * La clave es una CONSTANCIA, no un candado: ninguna función de venta, corte,
 * inventario, impresión, respaldo ni facturación depende de ella (el programa
 * es software libre, GPLv3). Lo que da es soporte personal, prioridad y acceso
 * anticipado; al vencer, esos extras terminan y nada más.
 *
 * Formatos aceptados (una sola línea, copiable y pegable):
 * <pre>
 *   PDV2.&lt;carga en Base64URL&gt;.&lt;firma en Base64URL&gt;
 *   carga = "PDV2|kid|id|nombre|emision|vence|nivel|flags"
 *     kid    = id de la llave que firmó (8 hex de SHA-256 de la pública)
 *     id     = 8 símbolos sin ambigüedad (identificador en el libro del desarrollador)
 *     vence  = aaaa-mm-dd (vacío = sin vencimiento)
 *     nivel  = basico | plus | patrocinador
 *     flags  = lista separada por comas: "beta,soporte" (vacío = las dos)
 *
 *   PDV1.&lt;carga&gt;.&lt;firma&gt;   (formato anterior, se sigue aceptando)
 *   carga = "PDV1|id|nombre|emision|nivel"   → sin kid, sin vencimiento, nivel libre
 * </pre>
 * La firma cubre la carga completa, prefijo incluido. Los espacios y saltos de
 * línea que meta el correo o WhatsApp se ignoran al validar.
 *
 * La clave PRIVADA nunca está en el repositorio: vive en la computadora del
 * desarrollador (ver docs/donadores.md y las tareas Gradle del grupo "donadores").
 */
public final class ClaveDonador {

    public static final String PREFIJO = "PDV2";
    public static final String PREFIJO_V1 = "PDV1";
    public static final String ALGORITMO = "Ed25519";
    public static final int NOMBRE_MAXIMO = 80;
    public static final int NIVEL_MAXIMO = 20;
    public static final int LARGO_ID = 8;
    public static final int LARGO_KID = 8;
    /** Caracteres de la firma que se citan en los mensajes de soporte (para cotejar con el libro) */
    public static final int LARGO_HUELLA = 12;

    public static final String FLAG_BETA = "beta";
    public static final String FLAG_SOPORTE = "soporte";

    /**
     * Clave pública OFICIAL del desarrollador (X.509/SPKI en Base64). Solo las
     * claves firmadas con su pareja privada son válidas. Si algún día se pierde
     * la privada hay que generar otro par y AGREGAR su pública a
     * {@link #CLAVES_PUBLICAS_OFICIALES} (las viejas siguen valiendo mientras
     * no se retiren en la lista de revocación).
     */
    public static final String CLAVE_PUBLICA_OFICIAL = "MCowBQYDK2VwAyEAt8n1iOJpkwZQDteDtvp7WNUvSpUopLbVrZG7PtzEABM=";

    /** Todas las públicas que el programa reconoce (Base64); el kid se calcula de cada una */
    public static final List<String> CLAVES_PUBLICAS_OFICIALES = List.of(CLAVE_PUBLICA_OFICIAL);

    private static final String SEPARADOR = ".";
    private static final char SEPARADOR_CARGA = '|';
    private static final String ALFABETO_ID = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // sin 0/O ni 1/I

    /** Nivel de apoyo (cerrado para que el programa pueda razonar con él) */
    public enum Nivel {
        BASICO("basico", "Básico"), PLUS("plus", "Plus"), PATROCINADOR("patrocinador", "Patrocinador");

        private final String clave;
        private final String etiqueta;

        Nivel(String clave, String etiqueta) {
            this.clave = clave;
            this.etiqueta = etiqueta;
        }

        public String clave()    { return clave; }
        public String etiqueta() { return etiqueta; }

        /** Texto de la clave → nivel; lo que no se reconoce (p. ej. "Oro" de una PDV1) cuenta como básico */
        public static Nivel desde(String texto) {
            if (texto == null) return BASICO;
            String t = texto.trim().toLowerCase(Locale.ROOT);
            for (Nivel n : values()) if (n.clave.equals(t)) return n;
            return BASICO;
        }

        public static boolean esValido(String texto) {
            if (texto == null || texto.isBlank()) return false;
            String t = texto.trim().toLowerCase(Locale.ROOT);
            for (Nivel n : values()) if (n.clave.equals(t)) return true;
            return false;
        }
    }

    /** Lo que el programa dice de una clave válida en una fecha dada */
    public enum Estado { VIGENTE, VENCIDA, REVOCADA }

    /**
     * Lo que dice una clave válida.
     * @param kid    id de la llave firmante (null en una PDV1)
     * @param vence  último día de vigencia (null = sin vencimiento)
     * @param flags  banderas ("beta", "soporte"); vacía = las dos por defecto
     */
    public record Datos(String kid, String id, String nombre, LocalDate emision, LocalDate vence, String nivel, List<String> flags) {
        public Datos {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("La clave necesita un id.");
            if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("Escribe el nombre del donador o del negocio.");
            if (emision == null) throw new IllegalArgumentException("La clave necesita fecha de emisión.");
            if (vence != null && vence.isBefore(emision)) throw new IllegalArgumentException("El vencimiento no puede ser anterior a la emisión.");
            kid = kid == null || kid.isBlank() ? null : kid.trim().toLowerCase(Locale.ROOT);
            if (kid != null && !kid.matches("[0-9a-f]{" + LARGO_KID + "}")) {
                throw new IllegalArgumentException("El id de la llave firmante debe tener " + LARGO_KID + " caracteres hexadecimales.");
            }
            id = id.trim();
            nombre = nombre.trim();
            nivel = nivel == null ? "" : nivel.trim();
            flags = flags == null ? List.of() : List.copyOf(flags.stream().map(f -> f.trim().toLowerCase(Locale.ROOT))
                    .filter(f -> !f.isEmpty()).toList());
            if (nombre.length() > NOMBRE_MAXIMO) throw new IllegalArgumentException("El nombre no puede pasar de " + NOMBRE_MAXIMO + " caracteres.");
            if (nivel.length() > NIVEL_MAXIMO) throw new IllegalArgumentException("El nivel no puede pasar de " + NIVEL_MAXIMO + " caracteres.");
            List<String> campos = new ArrayList<>(List.of(id, nombre, nivel));
            campos.addAll(flags);
            for (String campo : campos) {
                if (campo.indexOf(SEPARADOR_CARGA) >= 0 || campo.indexOf('\n') >= 0 || campo.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("Los datos de la clave no pueden contener '|' ni saltos de línea.");
                }
            }
            for (String f : flags) {
                if (!f.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Bandera inválida: " + f);
            }
        }

        /** Formato anterior (PDV1): sin llave firmante, sin vencimiento, nivel libre */
        public Datos(String id, String nombre, LocalDate emision, String nivel) {
            this(null, id, nombre, emision, null, nivel, List.of());
        }

        public boolean esPdv2()     { return kid != null; }
        public boolean tieneNivel() { return nivel != null && !nivel.isBlank(); }
        public Nivel nivelApoyo()   { return Nivel.desde(nivel); }

        /** true mientras no haya vencido (sin fecha de vencimiento = siempre) */
        public boolean vigente(LocalDate hoy) {
            return vence == null || !hoy.isAfter(vence);
        }

        /** Revocación: por id de la clave o por retiro de la llave que la firmó */
        public boolean revocada(Collection<String> idsRevocados, Collection<String> kidsRetirados) {
            if (idsRevocados != null && idsRevocados.contains(id)) return true;
            return kid != null && kidsRetirados != null && kidsRetirados.contains(kid);
        }

        public Estado estado(LocalDate hoy, Collection<String> idsRevocados, Collection<String> kidsRetirados) {
            if (revocada(idsRevocados, kidsRetirados)) return Estado.REVOCADA;
            return vigente(hoy) ? Estado.VIGENTE : Estado.VENCIDA;
        }

        /** Sin banderas = todas; con banderas, solo las listadas */
        public boolean permite(String flag) {
            return flags.isEmpty() || flags.contains(flag);
        }

        public boolean permiteBeta()    { return permite(FLAG_BETA); }
        public boolean permiteSoporte() { return permite(FLAG_SOPORTE); }

        /** "Abarrotes Lupita (desde 12/11/2026)" */
        public String descripcion() {
            return nombre + " (desde " + fecha(emision) + ")";
        }

        /** "vigente hasta 12/11/2027" o "sin vencimiento" */
        public String vigenciaTexto() {
            return vence == null ? "sin vencimiento" : "vigente hasta " + fecha(vence);
        }

        public static String fecha(LocalDate f) {
            return f.getDayOfMonth() + "/" + f.getMonthValue() + "/" + f.getYear();
        }
    }

    /** Clave que no pasa la validación; el mensaje es apto para mostrarse al usuario */
    public static final class ClaveInvalidaException extends RuntimeException {
        public ClaveInvalidaException(String mensaje) {
            super(mensaje);
        }
    }

    /** kid → llave pública */
    private final Map<String, PublicKey> clavesPublicas;

    public ClaveDonador(PublicKey clavePublica) {
        this(List.of(clavePublica));
    }

    public ClaveDonador(Collection<PublicKey> publicas) {
        Map<String, PublicKey> m = new LinkedHashMap<>();
        for (PublicKey p : publicas) m.put(kidDe(p), p);
        this.clavesPublicas = Collections.unmodifiableMap(m);
    }

    /** Verificador con las claves públicas oficiales incrustadas */
    public static ClaveDonador oficial() {
        return new ClaveDonador(CLAVES_PUBLICAS_OFICIALES.stream().map(ClaveDonador::clavePublicaDesde).toList());
    }

    public Set<String> kids() {
        return clavesPublicas.keySet();
    }

    public static PublicKey clavePublicaDesde(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITMO).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Clave pública de donadores inválida: " + e.getMessage(), e);
        }
    }

    /** Id de una llave: primeros 8 hex de SHA-256 del DER (X.509) de la pública */
    public static String kidDe(PublicKey publica) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(publica.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < LARGO_KID / 2; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Validar ───────────────────────────────────────────────────────────

    /**
     * Valida el texto pegado por el usuario (tolera espacios y saltos de línea).
     * @throws ClaveInvalidaException si está incompleta, alterada o no la firmó el desarrollador
     */
    public Datos validar(String texto) {
        String limpio = limpiar(texto);
        if (limpio.isEmpty()) throw new ClaveInvalidaException("Pega la clave de donador completa.");
        String[] partes = limpio.split("\\" + SEPARADOR, -1);
        if (partes.length != 3 || !(PREFIJO.equals(partes[0]) || PREFIJO_V1.equals(partes[0]))) {
            throw new ClaveInvalidaException("Eso no parece una clave de donador (debe empezar con " + PREFIJO + ". o " + PREFIJO_V1 + ".).");
        }
        byte[] carga, firma;
        try {
            carga = Base64.getUrlDecoder().decode(partes[1]);
            firma = Base64.getUrlDecoder().decode(partes[2]);
        } catch (IllegalArgumentException e) {
            throw new ClaveInvalidaException("La clave está incompleta o mal copiada.");
        }
        String textoCarga = new String(carga, StandardCharsets.UTF_8);
        String[] c = textoCarga.split("\\" + SEPARADOR_CARGA, -1);
        if (c.length == 0 || !partes[0].equals(c[0])) {
            throw new ClaveInvalidaException("La clave tiene un formato que esta versión del programa no reconoce.");
        }
        PublicKey publica;
        if (PREFIJO.equals(c[0])) {
            if (c.length != 8) throw new ClaveInvalidaException("La clave tiene un formato que esta versión del programa no reconoce.");
            publica = clavesPublicas.get(c[1].trim().toLowerCase(Locale.ROOT));
            if (publica == null) {
                throw new ClaveInvalidaException("La clave fue firmada con una llave que esta versión del programa no conoce; "
                        + "actualiza el programa o pide al desarrollador que la reemita.");
            }
        } else {
            if (c.length != 5) throw new ClaveInvalidaException("La clave tiene un formato que esta versión del programa no reconoce.");
            publica = null;   // PDV1 no dice con qué llave se firmó: se prueban todas
        }
        if (!firmaValida(carga, firma, publica)) {
            throw new ClaveInvalidaException("La clave no es válida: la firma no corresponde (¿se copió completa?).");
        }
        return datosDe(c);
    }

    /** Huella corta de la firma de una clave (para citarla en soporte); "" si el texto no tiene la forma esperada */
    public static String huella(String texto) {
        String[] partes = limpiar(texto).split("\\" + SEPARADOR, -1);
        if (partes.length != 3) return "";
        return partes[2].length() <= LARGO_HUELLA ? partes[2] : partes[2].substring(0, LARGO_HUELLA);
    }

    /** Comprueba una firma Ed25519 con una llave concreta, o con cualquiera de las oficiales si {@code publica} es null */
    public boolean firmaValida(byte[] carga, byte[] firma, PublicKey publica) {
        if (publica != null) return verificar(carga, firma, publica);
        for (PublicKey p : clavesPublicas.values()) if (verificar(carga, firma, p)) return true;
        return false;
    }

    private static boolean verificar(byte[] carga, byte[] firma, PublicKey publica) {
        try {
            Signature s = Signature.getInstance(ALGORITMO);
            s.initVerify(publica);
            s.update(carga);
            return s.verify(firma);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Solo se llama con una carga cuya firma ya se comprobó */
    private static Datos datosDe(String[] c) {
        try {
            if (PREFIJO.equals(c[0])) {
                LocalDate vence = c[5].isBlank() ? null : LocalDate.parse(c[5].trim());
                List<String> flags = c[7].isBlank() ? List.of() : List.of(c[7].split(","));
                return new Datos(c[1], c[2], c[3], LocalDate.parse(c[4].trim()), vence, c[6], flags);
            }
            return new Datos(c[1], c[2], LocalDate.parse(c[3].trim()), c[4]);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new ClaveInvalidaException("La clave tiene datos inválidos: " + e.getMessage());
        }
    }

    /** Quita espacios, saltos de línea y comillas que meten los mensajeros al copiar */
    public static String limpiar(String texto) {
        if (texto == null) return "";
        return texto.replaceAll("[\\s\"'«»]", "");
    }

    // ── Emitir (herramienta del desarrollador y pruebas) ──────────────────

    /**
     * Firma los datos con la clave privada y regresa el texto que se le entrega
     * al donador: PDV2 si los datos traen kid; PDV1 (formato anterior) si no.
     */
    public static String emitir(Datos datos, PrivateKey clavePrivada) {
        String prefijo = datos.esPdv2() ? PREFIJO : PREFIJO_V1;
        byte[] carga = carga(datos).getBytes(StandardCharsets.UTF_8);
        return armar(prefijo, carga, firmar(carga, clavePrivada));
    }

    /** Firma Ed25519 de cualquier carga (claves y lista de revocación) */
    public static byte[] firmar(byte[] carga, PrivateKey clavePrivada) {
        try {
            Signature s = Signature.getInstance(ALGORITMO);
            s.initSign(clavePrivada);
            s.update(carga);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo firmar: " + e.getMessage(), e);
        }
    }

    /** "PREFIJO.base64url(carga).base64url(firma)" */
    public static String armar(String prefijo, byte[] carga, byte[] firma) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return prefijo + SEPARADOR + b64.encodeToString(carga) + SEPARADOR + b64.encodeToString(firma);
    }

    static String carga(Datos d) {
        if (d.esPdv2()) {
            return PREFIJO + SEPARADOR_CARGA + d.kid() + SEPARADOR_CARGA + d.id() + SEPARADOR_CARGA + d.nombre()
                    + SEPARADOR_CARGA + d.emision() + SEPARADOR_CARGA + (d.vence() == null ? "" : d.vence())
                    + SEPARADOR_CARGA + d.nivel() + SEPARADOR_CARGA + String.join(",", d.flags());
        }
        return PREFIJO_V1 + SEPARADOR_CARGA + d.id() + SEPARADOR_CARGA + d.nombre() + SEPARADOR_CARGA
                + d.emision() + SEPARADOR_CARGA + d.nivel();
    }

    /** Id corto legible (8 letras/dígitos sin ambigüedad) para identificar la clave en la bitácora y el libro del desarrollador */
    public static String nuevoId() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(LARGO_ID);
        for (int i = 0; i < LARGO_ID; i++) sb.append(ALFABETO_ID.charAt(r.nextInt(ALFABETO_ID.length())));
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    /** Un id de clave tiene exactamente la forma de {@link #nuevoId()} */
    public static boolean esId(String texto) {
        return texto != null && texto.matches("[A-HJ-NP-Z2-9]{" + LARGO_ID + "}");
    }
}
