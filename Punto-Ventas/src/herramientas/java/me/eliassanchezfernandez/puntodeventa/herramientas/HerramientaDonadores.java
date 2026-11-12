package me.eliassanchezfernandez.puntodeventa.herramientas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.eliassanchezfernandez.puntodeventa.util.ClaveDonador;
import me.eliassanchezfernandez.puntodeventa.util.RevocacionesApoyo;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Herramienta del DESARROLLADOR para las claves de donador (constancias de
 * apoyo). No se incluye en el programa instalado (vive en el source set
 * "herramientas"). Tareas Gradle del grupo "donadores" (ver docs/donadores.md):
 *
 * <pre>
 *   ./gradlew crearLlavesDonador
 *   ./gradlew emitirClaveDonador   -Pdonador="Abarrotes Lupita" [-Pnivel=basico] [-Pmeses=12] [-Pmonto=300]
 *                                  [-PfechaPago=2026-11-12] [-Prastreo=…] [-Pcontacto=+52…]
 *   ./gradlew renovarClaveDonador  -Pid=ABCD2345 [-Pmeses=12] [-Pmonto=…] [-PfechaPago=…]
 *   ./gradlew revocarClaveDonador  -Pid=ABCD2345 -Pmotivo="clave publicada en un foro"
 *   ./gradlew publicarRevocaciones            → escribe claves/revocadas.pdvr (firmado) para hacer commit
 *   ./gradlew buscarDonador        -Ptexto="Lupita" | -Pid=ABCD2345 | -Pinstalacion=7F3A-91C2
 *   ./gradlew verificarClaveDonador -Pclave="PDV2.…"  o  -Ptexto="<mensaje de soporte pegado>"
 *   ./gradlew registrarSoporte     -Pid=ABCD2345 -Pinstalacion=7F3A-91C2 -Pversion=1.0.3 -Pnota="…"
 * </pre>
 *
 * Todo vive FUERA del repositorio, en ~/.puntodeventa-desarrollador/ (o la carpeta
 * de la propiedad del sistema {@code puntodeventa.desarrollador.carpeta}):
 *   donadores.key   privada (PKCS#8 en Base64): respaldarla, nunca publicarla
 *   donadores.pub   pública (X.509 en Base64): la que va en ClaveDonador.CLAVE_PUBLICA_OFICIAL
 *   donadores.jsonl libro: una línea JSON por evento (emitida, renovada, revocada, soporte), solo se agrega
 *
 * El libro guarda datos personales (nombre, contacto, claves de rastreo): es el
 * desarrollador quien responde por ellos (LFPDPPP); mantener lo mínimo y cifrar
 * la carpeta o el respaldo.
 */
public final class HerramientaDonadores {

    public static final String PROPIEDAD_CARPETA = "puntodeventa.desarrollador.carpeta";
    public static final int MESES_POR_DEFECTO = 12;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("\\b([A-HJ-NP-Z2-9]{8})\\b");
    private static final Pattern HUELLA = Pattern.compile("firma\\s+([A-Za-z0-9_-]{6," + ClaveDonador.LARGO_HUELLA + "})");
    private static final Pattern INSTALACION = Pattern.compile("Instalaci[oó]n:\\s*([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})");
    private static final Pattern VERSION = Pattern.compile("Versi[oó]n:\\s*v?([0-9][0-9A-Za-z.-]*)");
    private static final Pattern CLAVE = Pattern.compile("(PDV[12]\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");

    private final Path carpeta;
    private final PrintStream out;

    public HerramientaDonadores(Path carpeta, PrintStream out) {
        this.carpeta = carpeta;
        this.out = out;
    }

    public static Path carpetaPorDefecto() {
        String p = System.getProperty(PROPIEDAD_CARPETA);
        return p != null && !p.isBlank() ? Path.of(p) : Path.of(System.getProperty("user.home"), ".puntodeventa-desarrollador");
    }

    public Path privada()  { return carpeta.resolve("donadores.key"); }
    public Path publica()  { return carpeta.resolve("donadores.pub"); }
    public Path libro()    { return carpeta.resolve("donadores.jsonl"); }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            uso();
            System.exit(2);
        }
        try {
            new HerramientaDonadores(carpetaPorDefecto(), System.out).ejecutar(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void uso() {
        System.err.println("Uso: crear-llaves | emitir | renovar | revocar | publicar-revocaciones | buscar | verificar | registrar-soporte");
        System.err.println("     (cada comando toma pares --nombre valor; ver docs/donadores.md)");
    }

    /** Punto de entrada probable desde pruebas: mismo comportamiento que main sin System.exit */
    public void ejecutar(String... args) throws IOException, GeneralSecurityException {
        Map<String, String> a = argumentos(args);
        switch (args[0]) {
            case "crear-llaves" -> crearLlaves();
            case "emitir" -> emitir(a.get("donador"), a.get("nivel"), meses(a.get("meses")), a.get("monto"),
                    a.get("fecha-pago"), a.get("rastreo"), a.get("contacto"));
            case "renovar" -> renovar(a.get("id"), meses(a.get("meses")), a.get("nivel"), a.get("monto"),
                    a.get("fecha-pago"), a.get("rastreo"));
            case "revocar" -> revocar(a.get("id"), a.get("motivo"));
            case "publicar-revocaciones" -> publicarRevocaciones(a.get("salida") == null ? null : Path.of(a.get("salida")));
            case "buscar" -> buscar(a.get("texto"), a.get("id"), a.get("instalacion"));
            case "verificar" -> verificar(a.get("clave"), a.get("texto"));
            case "registrar-soporte" -> registrarSoporte(a.get("id"), a.get("instalacion"), a.get("version"), a.get("nota"));
            default -> throw new IllegalArgumentException("Comando desconocido: " + args[0]);
        }
    }

    /** "--nombre valor" → mapa; un "--nombre" sin valor (o con valor vacío) no cuenta */
    static Map<String, String> argumentos(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i + 1 < args.length; i++) {
            if (args[i].startsWith("--") && !args[i + 1].startsWith("--")) {
                String v = args[i + 1].trim();
                if (!v.isEmpty()) m.put(args[i].substring(2), v);
                i++;
            }
        }
        return m;
    }

    private static int meses(String texto) {
        if (texto == null || texto.isBlank()) return MESES_POR_DEFECTO;
        try {
            int m = Integer.parseInt(texto.trim());
            if (m < 0 || m > 120) throw new IllegalArgumentException("Los meses deben estar entre 0 (sin vencimiento) y 120.");
            return m;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Meses inválidos: " + texto);
        }
    }

    // ── Par de llaves ─────────────────────────────────────────────────────

    public void crearLlaves() throws IOException, GeneralSecurityException {
        if (Files.exists(privada())) {
            throw new IllegalStateException("Ya existe " + privada() + ". Si de verdad quieres otro par, muévela primero a un lugar seguro: "
                    + "las claves emitidas con la llave anterior solo seguirán valiendo si su pública se conserva en "
                    + "ClaveDonador.CLAVES_PUBLICAS_OFICIALES.");
        }
        Files.createDirectories(carpeta);
        KeyPair par = KeyPairGenerator.getInstance(ClaveDonador.ALGORITMO).generateKeyPair();
        String priv = java.util.Base64.getEncoder().encodeToString(par.getPrivate().getEncoded());
        String pub = java.util.Base64.getEncoder().encodeToString(par.getPublic().getEncoded());
        Files.writeString(privada(), priv + System.lineSeparator(), StandardCharsets.US_ASCII);
        Files.writeString(publica(), pub + System.lineSeparator(), StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(privada(), PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(carpeta, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException windows) {
            // en Windows la carpeta del usuario ya es privada
        }
        out.println("Par de llaves Ed25519 creado en " + carpeta);
        out.println("  Privada (RESPÁLDALA, nunca la publiques): " + privada());
        out.println("  Pública: " + publica() + "  (kid " + ClaveDonador.kidDe(par.getPublic()) + ")");
        out.println();
        out.println("Agrega esta clave pública a ClaveDonador.CLAVES_PUBLICAS_OFICIALES:");
        out.println(pub);
    }

    PrivateKey leerPrivada() throws IOException, GeneralSecurityException {
        if (!Files.exists(privada())) {
            throw new IllegalStateException("No existe " + privada() + ". Córrelo primero: ./gradlew crearLlavesDonador");
        }
        byte[] pkcs8 = java.util.Base64.getDecoder().decode(Files.readString(privada()).trim());
        return KeyFactory.getInstance(ClaveDonador.ALGORITMO).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    PublicKey leerPublica() throws IOException {
        if (!Files.exists(publica())) throw new IllegalStateException("No existe " + publica());
        return ClaveDonador.clavePublicaDesde(Files.readString(publica()).trim());
    }

    private void avisarSiNoEsLaOficial(PublicKey publica) {
        boolean oficial = ClaveDonador.CLAVES_PUBLICAS_OFICIALES.stream()
                .anyMatch(b -> ClaveDonador.clavePublicaDesde(b).equals(publica));
        if (!oficial) {
            out.println("AVISO: la llave pública de " + publica() + " NO está en ClaveDonador.CLAVES_PUBLICAS_OFICIALES. "
                    + "Lo que se emita no lo aceptará la versión publicada del programa.");
        }
    }

    // ── Emitir / renovar ──────────────────────────────────────────────────

    public ClaveDonador.Datos emitir(String nombre, String nivel, int meses, String monto, String fechaPago,
                                     String rastreo, String contacto) throws IOException, GeneralSecurityException {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Falta el nombre: ./gradlew emitirClaveDonador -Pdonador=\"Abarrotes Lupita\"");
        }
        String nivelClave = nivelValido(nivel);
        PrivateKey priv = leerPrivada();
        PublicKey pub = leerPublica();
        avisarSiNoEsLaOficial(pub);
        LocalDate hoy = LocalDate.now();
        ClaveDonador.Datos datos = new ClaveDonador.Datos(ClaveDonador.kidDe(pub), idNuevoLibre(), nombre, hoy,
                meses == 0 ? null : hoy.plusMonths(meses), nivelClave, List.of());
        String clave = ClaveDonador.emitir(datos, priv);
        new ClaveDonador(pub).validar(clave);    // ida y vuelta antes de entregarla

        ObjectNode e = evento("emitida", datos.id());
        e.put("kid", datos.kid());
        e.put("nombre", datos.nombre());
        e.put("nivel", datos.nivel());
        e.put("emision", datos.emision().toString());
        e.put("vence", datos.vence() == null ? "" : datos.vence().toString());
        if (monto != null) e.put("monto", monto);
        if (fechaPago != null) e.put("fecha_pago", fechaPago);
        if (rastreo != null) e.put("rastreo", rastreo);
        if (contacto != null) e.put("contacto", contacto);
        e.put("clave", clave);
        anotar(e);
        imprimirClave("Clave de donador para", datos, clave);
        return datos;
    }

    public ClaveDonador.Datos renovar(String id, int meses, String nivel, String monto, String fechaPago, String rastreo)
            throws IOException, GeneralSecurityException {
        Registro r = registro(id).orElseThrow(() -> new IllegalArgumentException("No hay ninguna clave con id " + id + " en el libro."));
        if (r.revocada()) throw new IllegalStateException("La clave " + id + " está revocada; emite una nueva en vez de renovarla.");
        PrivateKey priv = leerPrivada();
        PublicKey pub = leerPublica();
        avisarSiNoEsLaOficial(pub);
        LocalDate hoy = LocalDate.now();
        LocalDate desde = r.vence() != null && r.vence().isAfter(hoy) ? r.vence() : hoy;   // renovar antes de tiempo no pierde días
        ClaveDonador.Datos datos = new ClaveDonador.Datos(ClaveDonador.kidDe(pub), r.id(), r.nombre(), hoy,
                meses == 0 ? null : desde.plusMonths(meses), nivel == null ? r.nivel() : nivelValido(nivel), List.of());
        String clave = ClaveDonador.emitir(datos, priv);
        new ClaveDonador(pub).validar(clave);

        ObjectNode e = evento("renovada", datos.id());
        e.put("kid", datos.kid());
        e.put("nombre", datos.nombre());
        e.put("nivel", datos.nivel());
        e.put("emision", datos.emision().toString());
        e.put("vence", datos.vence() == null ? "" : datos.vence().toString());
        if (monto != null) e.put("monto", monto);
        if (fechaPago != null) e.put("fecha_pago", fechaPago);
        if (rastreo != null) e.put("rastreo", rastreo);
        e.put("clave", clave);
        anotar(e);
        imprimirClave("Clave RENOVADA para", datos, clave);
        return datos;
    }

    private static String nivelValido(String nivel) {
        if (nivel == null || nivel.isBlank()) return ClaveDonador.Nivel.BASICO.clave();
        if (!ClaveDonador.Nivel.esValido(nivel)) {
            throw new IllegalArgumentException("Nivel inválido: " + nivel + " (usa basico, plus o patrocinador)");
        }
        return nivel.trim().toLowerCase(Locale.ROOT);
    }

    private String idNuevoLibre() throws IOException {
        Set<String> usados = new java.util.HashSet<>();
        for (JsonNode n : leerLibro()) usados.add(n.path("id").asText(""));
        String id;
        do { id = ClaveDonador.nuevoId(); } while (usados.contains(id));
        return id;
    }

    private void imprimirClave(String titulo, ClaveDonador.Datos d, String clave) {
        out.println();
        out.println(titulo + ": " + d.nombre() + " · nivel " + d.nivelApoyo().etiqueta() + " · id " + d.id()
                + " · emitida " + d.emision() + " · " + d.vigenciaTexto() + " · firma " + ClaveDonador.huella(clave));
        out.println("Envíasela tal cual (una sola línea). Quedó anotada en " + libro());
        out.println();
        out.println(clave);
        out.println();
    }

    // ── Revocar y publicar ────────────────────────────────────────────────

    public void revocar(String id, String motivo) throws IOException {
        Registro r = registro(id).orElseThrow(() -> new IllegalArgumentException("No hay ninguna clave con id " + id + " en el libro."));
        if (r.revocada()) {
            out.println("La clave " + id + " ya estaba revocada.");
            return;
        }
        ObjectNode e = evento("revocada", r.id());
        e.put("nombre", r.nombre());
        e.put("motivo", motivo == null ? "" : motivo);
        anotar(e);
        out.println("Clave " + id + " (" + r.nombre() + ") revocada en el libro. Para que los programas lo sepan: "
                + "./gradlew publicarRevocaciones y haz commit del archivo.");
    }

    /** Lista firmada con todas las claves revocadas del libro; la escribe en {@code salida} (o solo la imprime) */
    public String publicarRevocaciones(Path salida) throws IOException, GeneralSecurityException {
        Set<String> revocadas = new TreeSet<>();
        Set<String> retiradas = new TreeSet<>();
        for (JsonNode n : leerLibro()) {
            if ("revocada".equals(n.path("ev").asText())) revocadas.add(n.path("id").asText());
            if ("llave_retirada".equals(n.path("ev").asText())) retiradas.add(n.path("kid").asText());
        }
        RevocacionesApoyo.Lista lista = new RevocacionesApoyo.Lista(LocalDate.now(), revocadas, retiradas);
        String texto = RevocacionesApoyo.emitir(lista, leerPrivada());
        RevocacionesApoyo.verificar(texto, new ClaveDonador(leerPublica()));
        if (salida != null) {
            Files.createDirectories(salida.toAbsolutePath().getParent());
            Files.writeString(salida, texto + System.lineSeparator(), StandardCharsets.US_ASCII);
            out.println("Lista de revocación firmada (" + revocadas.size() + " clave(s), emitida " + lista.emitido()
                    + ") escrita en " + salida + ". Haz commit y push para publicarla.");
        } else {
            out.println(texto);
        }
        return texto;
    }

    // ── Libro ─────────────────────────────────────────────────────────────

    /** Lo que el libro sabe de un id (la última emisión o renovación, y si fue revocada) */
    public record Registro(String id, String nombre, String nivel, LocalDate emision, LocalDate vence, String clave,
                           boolean revocada, String motivoRevocacion, Set<String> instalaciones, int soportes) {
        public ClaveDonador.Estado estado(LocalDate hoy) {
            if (revocada) return ClaveDonador.Estado.REVOCADA;
            return vence == null || !hoy.isAfter(vence) ? ClaveDonador.Estado.VIGENTE : ClaveDonador.Estado.VENCIDA;
        }
    }

    public Optional<Registro> registro(String id) throws IOException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Falta el id de la clave (-Pid=ABCD2345).");
        String buscado = id.trim().toUpperCase(Locale.ROOT);
        return registros().stream().filter(r -> r.id().equals(buscado)).findFirst();
    }

    public List<Registro> registros() throws IOException {
        Map<String, Object[]> porId = new LinkedHashMap<>();
        for (JsonNode n : leerLibro()) {
            String id = n.path("id").asText("");
            if (id.isEmpty()) continue;
            Object[] acc = porId.computeIfAbsent(id, k -> new Object[] {null, false, "", new LinkedHashSet<String>(), 0});
            switch (n.path("ev").asText()) {
                case "emitida", "renovada" -> acc[0] = n;
                case "revocada" -> { acc[1] = true; acc[2] = n.path("motivo").asText(""); }
                case "soporte" -> {
                    @SuppressWarnings("unchecked") Set<String> inst = (Set<String>) acc[3];
                    String i = n.path("instalacion").asText("");
                    if (!i.isEmpty()) inst.add(i);
                    acc[4] = (Integer) acc[4] + 1;
                }
                default -> { }
            }
        }
        List<Registro> lista = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : porId.entrySet()) {
            JsonNode u = (JsonNode) e.getValue()[0];
            if (u == null) continue;
            @SuppressWarnings("unchecked") Set<String> inst = (Set<String>) e.getValue()[3];
            String vence = u.path("vence").asText("");
            lista.add(new Registro(e.getKey(), u.path("nombre").asText(""), u.path("nivel").asText(""),
                    LocalDate.parse(u.path("emision").asText()), vence.isBlank() ? null : LocalDate.parse(vence),
                    u.path("clave").asText(""), (Boolean) e.getValue()[1], (String) e.getValue()[2],
                    Set.copyOf(inst), (Integer) e.getValue()[4]));
        }
        return lista;
    }

    public void buscar(String texto, String id, String instalacion) throws IOException {
        List<Registro> todos = registros();
        List<Registro> encontrados = new ArrayList<>();
        for (Registro r : todos) {
            boolean coincide = id != null && r.id().equalsIgnoreCase(id.trim())
                    || texto != null && (r.nombre().toLowerCase(Locale.ROOT).contains(texto.toLowerCase(Locale.ROOT))
                        || r.id().equalsIgnoreCase(texto.trim()))
                    || instalacion != null && r.instalaciones().stream().anyMatch(i -> i.equalsIgnoreCase(instalacion.trim()));
            if (coincide) encontrados.add(r);
        }
        if (id == null && texto == null && instalacion == null) encontrados = todos;
        if (encontrados.isEmpty()) {
            out.println("Nada en el libro (" + todos.size() + " clave(s) en total).");
            return;
        }
        for (Registro r : encontrados) out.println(describir(r));
    }

    private static String describir(Registro r) {
        LocalDate hoy = LocalDate.now();
        return r.id() + "  " + r.nombre() + "  · nivel " + ClaveDonador.Nivel.desde(r.nivel()).etiqueta()
                + " · emitida " + r.emision() + " · " + (r.vence() == null ? "sin vencimiento" : "vence " + r.vence())
                + " · " + r.estado(hoy).name().toLowerCase(Locale.ROOT)
                + (r.revocada() && !r.motivoRevocacion().isBlank() ? " (" + r.motivoRevocacion() + ")" : "")
                + " · firma " + ClaveDonador.huella(r.clave())
                + " · soportes " + r.soportes() + " desde " + r.instalaciones().size() + " instalación(es)"
                + (r.instalaciones().isEmpty() ? "" : " " + r.instalaciones());
    }

    /**
     * Coteja una clave completa o un mensaje de soporte pegado (id, huella de la
     * firma, instalación, versión) contra el libro. Imprime el veredicto.
     * @return el registro si la clave existe en el libro
     */
    public Optional<Registro> verificar(String clave, String texto) throws IOException {
        String fuente = clave != null ? clave : texto;
        if (fuente == null || fuente.isBlank()) throw new IllegalArgumentException("Pasa -Pclave=\"PDV2.…\" o -Ptexto=\"<mensaje de soporte>\".");
        Optional<String> claveCompleta = primero(CLAVE, fuente);
        String id = null;
        String huella = null;
        if (claveCompleta.isPresent()) {
            try {
                ClaveDonador.Datos d = new ClaveDonador(leerPublica()).validar(claveCompleta.get());
                id = d.id();
                huella = ClaveDonador.huella(claveCompleta.get());
                out.println("Firma válida: " + d.nombre() + " · id " + d.id() + " · " + d.vigenciaTexto()
                        + " · nivel " + d.nivelApoyo().etiqueta() + (d.esPdv2() ? " · kid " + d.kid() : " · formato PDV1"));
            } catch (ClaveDonador.ClaveInvalidaException e) {
                out.println("FIRMA INVÁLIDA: " + e.getMessage() + " → no es una clave emitida con esta llave (¿fork con otra pública?).");
                return Optional.empty();
            }
        } else {
            Matcher m = Pattern.compile("Clave de apoyo:\\s*([A-HJ-NP-Z2-9]{8})").matcher(fuente);
            id = m.find() ? m.group(1) : primero(ID, fuente).orElse(null);
            huella = primero(HUELLA, fuente).orElse(null);
        }
        if (id == null) {
            out.println("El texto no trae un id de clave (8 símbolos). Sin clave no hay nada que verificar: "
                    + "el programa es libre y gratis; el soporte personal es para quien apoya.");
            return Optional.empty();
        }
        Optional<Registro> r = registro(id);
        if (r.isEmpty()) {
            out.println("El id " + id + " NO está en el libro: clave no emitida por ti (o libro de otra computadora). No atender como donador.");
            return Optional.empty();
        }
        Registro reg = r.get();
        out.println("Libro: " + describir(reg));
        if (huella != null) {
            String propia = ClaveDonador.huella(reg.clave());
            out.println(propia.startsWith(huella) || huella.startsWith(propia)
                    ? "La huella de la firma coincide con la clave emitida."
                    : "OJO: la huella " + huella + " NO coincide con la emitida (" + propia + "): clave vieja, renovada o alterada.");
        }
        primero(INSTALACION, fuente).ifPresent(i -> {
            if (!reg.instalaciones().isEmpty() && !reg.instalaciones().contains(i.toUpperCase(Locale.ROOT))) {
                out.println("Instalación " + i + " nueva para este id (antes: " + reg.instalaciones() + ").");
            }
        });
        out.println("Veredicto: " + switch (reg.estado(LocalDate.now())) {
            case VIGENTE -> "ATENDER (apoyo vigente).";
            case VENCIDA -> "apoyo VENCIDO el " + reg.vence() + ": ofrecer renovación.";
            case REVOCADA -> "clave REVOCADA: no atender como donador.";
        });
        return r;
    }

    public void registrarSoporte(String id, String instalacion, String version, String nota) throws IOException {
        Registro r = registro(id).orElseThrow(() -> new IllegalArgumentException("No hay ninguna clave con id " + id + " en el libro."));
        ObjectNode e = evento("soporte", r.id());
        if (instalacion != null) e.put("instalacion", instalacion.trim().toUpperCase(Locale.ROOT));
        if (version != null) e.put("version", version.trim());
        e.put("nota", nota == null ? "" : nota);
        anotar(e);
        out.println("Soporte anotado para " + r.id() + " (" + r.nombre() + ").");
    }

    /** Extrae id, instalación y versión de un mensaje de soporte pegado (ayuda para registrarSoporte) */
    public static Map<String, String> datosDeMensaje(String texto) {
        Map<String, String> m = new LinkedHashMap<>();
        if (texto == null) return m;
        Matcher id = Pattern.compile("Clave de apoyo:\\s*([A-HJ-NP-Z2-9]{8})").matcher(texto);
        if (id.find()) m.put("id", id.group(1));
        primero(INSTALACION, texto).ifPresent(v -> m.put("instalacion", v.toUpperCase(Locale.ROOT)));
        primero(VERSION, texto).ifPresent(v -> m.put("version", v));
        primero(HUELLA, texto).ifPresent(v -> m.put("huella", v));
        return m;
    }

    private static Optional<String> primero(Pattern p, String texto) {
        Matcher m = p.matcher(texto);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    // ── JSONL ─────────────────────────────────────────────────────────────

    private ObjectNode evento(String ev, String id) {
        ObjectNode n = JSON.createObjectNode();
        n.put("t", LocalDateTime.now().withNano(0).toString());
        n.put("ev", ev);
        n.put("id", id);
        return n;
    }

    private void anotar(ObjectNode n) throws IOException {
        Files.createDirectories(carpeta);
        Files.writeString(libro(), n.toString() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            Files.setPosixFilePermissions(libro(), PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException windows) {
            // en Windows la carpeta del usuario ya es privada
        }
    }

    public List<JsonNode> leerLibro() throws IOException {
        List<JsonNode> lista = new ArrayList<>();
        if (!Files.exists(libro())) return lista;
        for (String linea : Files.readAllLines(libro(), StandardCharsets.UTF_8)) {
            if (linea.isBlank()) continue;
            try {
                lista.add(JSON.readTree(linea));
            } catch (IOException e) {
                out.println("Línea ilegible en el libro (se ignora): " + linea);
            }
        }
        return lista;
    }
}
