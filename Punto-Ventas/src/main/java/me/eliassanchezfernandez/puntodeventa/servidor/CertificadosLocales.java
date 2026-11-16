package me.eliassanchezfernandez.puntodeventa.servidor;

import me.eliassanchezfernandez.puntodeventa.util.Der;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Autoridad certificadora (CA) LOCAL de esta instalación y el certificado del servidor HTTPS
 * del acceso móvil. Todo con el JDK (claves EC P-256, SHA256withECDSA, DER escrito con
 * {@link Der}); no hay dependencias nuevas.
 *
 * Por qué una CA propia: los teléfonos solo aceptan notificaciones (service worker + Web
 * Push) desde una página HTTPS, y en una red local no se puede obtener un certificado de
 * una autoridad pública. El dueño instala UNA vez el certificado de la CA en cada teléfono
 * (página /certificado) y a partir de ahí el servidor puede renovar su certificado cuando
 * cambie la IP de la computadora sin que nadie tenga que volver a instalar nada.
 *
 * Dónde viven las claves: carpeta {@code claves-privadas/} junto a la base de datos (o en la carpeta de
 * datos de la aplicación en modo servidor), ver {@link RutasApp#carpetaClaves()}. Fuera de los
 * respaldos (que copian solo datos.db), fuera del repositorio y con permisos de solo el usuario
 * en macOS/Linux; en Windows heredan los permisos de la carpeta del perfil. Un respaldo
 * restaurado en otra computadora genera su propia CA: los teléfonos tendrán que instalar la nueva.
 *
 *  - CA: 10 años, basicConstraints CA:TRUE pathLen 0 (crítico), keyUsage keyCertSign+cRLSign y
 *    nameConstraints (crítico) que SOLO permiten localhost, nombres .local y direcciones de red local
 *    (10/8, 172.16/12, 192.168/16, 127/8). El teléfono confía en esta CA como raíz: sin las restricciones,
 *    quien copiara la clave privada de la computadora podría falsificar cualquier sitio (banco, correo)
 *    ante esos teléfonos. Con ellas, una hoja para otro nombre es rechazada por el propio teléfono.
 *    Una CA guardada sin restricciones (versiones de prueba anteriores) se reemplaza al arrancar.
 *  - Servidor: 2 años (iOS rechaza hojas de más de 825 días), SAN con todas las IP de la red
 *    local, localhost, el nombre del equipo y el nombre .local configurado; extKeyUsage serverAuth.
 *    Se vuelve a emitir solo cuando cambian las direcciones, cuando le queda menos de un mes o
 *    cuando lo firmó otra CA. La CA NO cambia.
 */
@Component
public class CertificadosLocales {

    public static final Duration VIGENCIA_CA = Duration.ofDays(3652);          // 10 años
    public static final Duration VIGENCIA_SERVIDOR = Duration.ofDays(730);     // 2 años (< 825 días que exige iOS)
    /** Se renueva el certificado del servidor cuando le queda menos de esto */
    public static final Duration RENOVAR_ANTES = Duration.ofDays(30);
    /** Margen hacia atrás de notBefore (relojes de teléfonos atrasados) */
    static final Duration MARGEN_ATRAS = Duration.ofDays(1);

    static final String ARCHIVO_CA_CLAVE = "movil-ca.key";
    static final String ARCHIVO_CA_CERT = "movil-ca.crt";
    static final String ARCHIVO_SERVIDOR_CLAVE = "movil-servidor.key";
    static final String ARCHIVO_SERVIDOR_CERT = "movil-servidor.crt";

    // OIDs
    private static final String OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2";
    private static final String OID_CN = "2.5.4.3";
    private static final String OID_O = "2.5.4.10";
    private static final String OID_SKI = "2.5.29.14";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_SAN = "2.5.29.17";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_AKI = "2.5.29.35";
    private static final String OID_EKU = "2.5.29.37";
    static final String OID_NAME_CONSTRAINTS = "2.5.29.30";

    /** Nombres DNS permitidos por la CA (y sus subdominios): localhost y la zona .local (mDNS) */
    static final List<String> DNS_PERMITIDOS = List.of("localhost", "local");
    /** Redes permitidas por la CA: dirección y máscara (privadas y loopback) */
    static final List<int[]> REDES_PERMITIDAS = List.of(
            new int[] { 10, 0, 0, 0, 8 },
            new int[] { 172, 16, 0, 0, 12 },
            new int[] { 192, 168, 0, 0, 16 },
            new int[] { 127, 0, 0, 0, 8 });
    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    /** Clave privada y certificado listos para el servidor TLS */
    public record Servidor(PrivateKey clave, X509Certificate certificado, X509Certificate ca) { }

    private final Path carpeta;
    private final Clock reloj;
    private final SecureRandom azar = new SecureRandom();

    private PrivateKey claveCa;
    private X509Certificate certCa;
    private Servidor servidor;

    @Autowired
    public CertificadosLocales(RutasApp rutas, Clock reloj) {
        this(rutas.carpetaClaves(), reloj);
    }

    /** Pruebas: carpeta propia (temporal) */
    public CertificadosLocales(Path carpeta, Clock reloj) {
        this.carpeta = carpeta;
        this.reloj = reloj;
    }

    public Path carpeta() { return carpeta; }

    // ── CA ────────────────────────────────────────────────────────────────

    /** Certificado de la CA (se crea la primera vez). Nunca entrega la clave privada. */
    public synchronized X509Certificate ca() {
        cargarOCrearCa();
        return certCa;
    }

    public byte[] caDer() {
        try {
            return ca().getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 del certificado de la CA en hexadecimal (64 caracteres, mayúsculas) */
    public String huellaCa() {
        return huella(caDer());
    }

    /** Forma corta para comparar a ojo: los primeros 16 caracteres en grupos de 4 ("A1B2 C3D4 E5F6 7890") */
    public String huellaCorta() {
        return huellaCorta(huellaCa());
    }

    public static String huellaCorta(String huellaHex) {
        String h = huellaHex.replace(" ", "").replace(":", "").toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(16, h.length()); i += 4) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(h, i, Math.min(i + 4, h.length()));
        }
        return sb.toString();
    }

    /** Huella completa en grupos de 2 separados por ":" (como la muestran Android e iOS) */
    public static String huellaConDosPuntos(String huellaHex) {
        String h = huellaHex.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length(); i += 2) {
            if (sb.length() > 0) sb.append(':');
            sb.append(h, i, Math.min(i + 2, h.length()));
        }
        return sb.toString();
    }

    static String huella(byte[] der) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Borra la CA y el certificado del servidor y crea otros. Los teléfonos que instalaron la CA
     * anterior tendrán que instalar la nueva (y sus suscripciones a notificaciones dejan de servir
     * porque la página ya no será "segura" para ellos hasta entonces).
     */
    public synchronized void regenerarCa() {
        try {
            Files.deleteIfExists(carpeta.resolve(ARCHIVO_CA_CLAVE));
            Files.deleteIfExists(carpeta.resolve(ARCHIVO_CA_CERT));
            Files.deleteIfExists(carpeta.resolve(ARCHIVO_SERVIDOR_CLAVE));
            Files.deleteIfExists(carpeta.resolve(ARCHIVO_SERVIDOR_CERT));
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron borrar los certificados anteriores: " + e.getMessage(), e);
        }
        claveCa = null;
        certCa = null;
        servidor = null;
        cargarOCrearCa();
    }

    private void cargarOCrearCa() {
        if (certCa != null && claveCa != null) return;
        Path pk = carpeta.resolve(ARCHIVO_CA_CLAVE), pc = carpeta.resolve(ARCHIVO_CA_CERT);
        try {
            if (Files.isRegularFile(pk) && Files.isRegularFile(pc)) {
                PrivateKey clave = leerClave(pk);
                X509Certificate cert = leerCertificado(Files.readAllBytes(pc));
                ZonedDateTime ahora = ahora();
                boolean vigente = cert.getNotAfter().toInstant().isAfter(ahora.plus(RENOVAR_ANTES).toInstant());
                boolean restringida = cert.getExtensionValue(OID_NAME_CONSTRAINTS) != null;
                if (vigente && restringida && mismaClave(clave, cert.getPublicKey())) {
                    claveCa = clave;
                    certCa = cert;
                    return;
                }
                System.err.println(restringida
                        ? "⚠ El certificado de la CA local venció o no corresponde a su clave: se crea otro."
                        : "⚠ La CA local no limita los nombres que puede firmar: se crea otra (hay que instalarla de nuevo en los teléfonos).");
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            System.err.println("⚠ No se pudo leer la CA local (" + e.getMessage() + "): se crea otra.");
        }
        try {
            KeyPair par = nuevoPar();
            String id = HexFormat.of().formatHex(sha256(par.getPublic().getEncoded())).substring(0, 6);
            X509Certificate cert = generarCa(par, "Caja Libre CA local " + id, ahora(), VIGENCIA_CA, azar);
            guardarClave(pk, par.getPrivate());
            guardar(pc, cert.getEncoded());
            claveCa = par.getPrivate();
            certCa = cert;
            servidor = null;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo crear la CA local en " + carpeta + ": " + e.getMessage(), e);
        }
    }

    // ── Certificado del servidor ──────────────────────────────────────────

    /**
     * Certificado del servidor válido para esos nombres y direcciones (SAN). Reutiliza el
     * guardado si lo firmó la CA actual, le queda más de un mes y cubre exactamente los mismos
     * nombres; si no, emite uno nuevo con la misma CA.
     */
    public synchronized Servidor servidor(Collection<String> nombresDns, Collection<String> ips) {
        cargarOCrearCa();
        Set<String> dns = dnsPermitidos(nombresDns), direcciones = ipsPermitidas(ips);
        Servidor actual = servidor != null ? servidor : leerServidorGuardado();
        if (actual != null && sirve(actual, dns, direcciones)) {
            servidor = actual;
            return actual;
        }
        try {
            KeyPair par = nuevoPar();
            X509Certificate cert = generarServidor(par.getPublic(), claveCa, certCa, dns, direcciones, ahora(), VIGENCIA_SERVIDOR, azar);
            guardarClave(carpeta.resolve(ARCHIVO_SERVIDOR_CLAVE), par.getPrivate());
            guardar(carpeta.resolve(ARCHIVO_SERVIDOR_CERT), cert.getEncoded());
            servidor = new Servidor(par.getPrivate(), cert, certCa);
            return servidor;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo emitir el certificado del servidor: " + e.getMessage(), e);
        }
    }

    /** ¿El certificado guardado sigue sirviendo para estos nombres? (sin emitir nada) */
    public synchronized boolean servidorVigentePara(Collection<String> nombresDns, Collection<String> ips) {
        cargarOCrearCa();
        Servidor actual = servidor != null ? servidor : leerServidorGuardado();
        return actual != null && sirve(actual, dnsPermitidos(nombresDns), ipsPermitidas(ips));
    }

    private Servidor leerServidorGuardado() {
        Path pk = carpeta.resolve(ARCHIVO_SERVIDOR_CLAVE), pc = carpeta.resolve(ARCHIVO_SERVIDOR_CERT);
        if (!Files.isRegularFile(pk) || !Files.isRegularFile(pc)) return null;
        try {
            PrivateKey clave = leerClave(pk);
            X509Certificate cert = leerCertificado(Files.readAllBytes(pc));
            if (!mismaClave(clave, cert.getPublicKey())) return null;
            return new Servidor(clave, cert, certCa);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    private boolean sirve(Servidor s, Set<String> dns, Set<String> ips) {
        try {
            s.certificado().verify(certCa.getPublicKey());
        } catch (GeneralSecurityException e) {
            return false;                                             // lo firmó otra CA
        }
        ZonedDateTime ahora = ahora();
        if (!s.certificado().getNotAfter().toInstant().isAfter(ahora.plus(RENOVAR_ANTES).toInstant())) return false;
        if (s.certificado().getNotBefore().toInstant().isAfter(ahora.toInstant())) return false;
        Set<String> dnsCert = new TreeSet<>(), ipsCert = new TreeSet<>();
        try {
            Collection<List<?>> sans = s.certificado().getSubjectAlternativeNames();
            if (sans != null) for (List<?> san : sans) {
                int tipo = (Integer) san.get(0);
                if (tipo == 2) dnsCert.add(((String) san.get(1)).toLowerCase(Locale.ROOT));
                if (tipo == 7) ipsCert.add((String) san.get(1));
            }
        } catch (GeneralSecurityException e) {
            return false;
        }
        return dnsCert.equals(dns) && ipsCert.equals(ips);
    }

    /**
     * Solo los nombres que la CA puede firmar (localhost y *.local): un nombre fuera de las restricciones
     * haría que el teléfono rechazara el certificado completo, así que ni se incluye.
     */
    static Set<String> dnsPermitidos(Collection<String> c) {
        Set<String> r = new TreeSet<>();
        for (String d : normalizar(c)) {
            for (String base : DNS_PERMITIDOS) {
                if (d.equals(base) || (d.endsWith("." + base) && d.length() > base.length() + 1)) { r.add(d); break; }
            }
        }
        return r;
    }

    /** Solo IPv4 dentro de {@link #REDES_PERMITIDAS} */
    static Set<String> ipsPermitidas(Collection<String> c) {
        Set<String> r = new TreeSet<>();
        for (String ip : normalizar(c)) if (ipPermitida(ip)) r.add(ip);
        return r;
    }

    static boolean ipPermitida(String ip) {
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] p = ip.split("\\.");
        long valor = 0;
        for (String x : p) {
            int n = Integer.parseInt(x);
            if (n > 255) return false;
            valor = (valor << 8) | n;
        }
        for (int[] red : REDES_PERMITIDAS) {
            long base = ((long) red[0] << 24) | (red[1] << 16) | (red[2] << 8) | red[3];
            long mascara = (0xFFFFFFFFL << (32 - red[4])) & 0xFFFFFFFFL;
            if ((valor & mascara) == base) return true;
        }
        return false;
    }

    /** nameConstraints: permittedSubtrees [0] con los nombres DNS y las redes permitidas */
    static byte[] restriccionesDeNombre() {
        List<byte[]> subarboles = new ArrayList<>();
        for (String d : DNS_PERMITIDOS) subarboles.add(Der.secuencia(Der.implicito(2, d.getBytes(StandardCharsets.US_ASCII))));
        for (int[] red : REDES_PERMITIDAS) {
            long mascara = (0xFFFFFFFFL << (32 - red[4])) & 0xFFFFFFFFL;
            byte[] valor = new byte[] { (byte) red[0], (byte) red[1], (byte) red[2], (byte) red[3],
                    (byte) (mascara >>> 24), (byte) (mascara >>> 16), (byte) (mascara >>> 8), (byte) mascara };
            subarboles.add(Der.secuencia(Der.implicito(7, valor)));
        }
        return Der.secuencia(Der.explicito(0, Der.concatenar(subarboles.toArray(new byte[0][]))));
    }

    private static Set<String> normalizar(Collection<String> c) {
        Set<String> r = new TreeSet<>();
        if (c != null) for (String s : c) if (s != null && !s.isBlank()) r.add(s.trim().toLowerCase(Locale.ROOT));
        return r;
    }

    // ── TLS ───────────────────────────────────────────────────────────────

    /** SSLContext con la cadena [servidor, CA] para HttpsServer */
    public static SSLContext contextoTls(Servidor s) {
        try {
            char[] clave = new char[0];
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("movil", s.clave(), clave, new Certificate[] { s.certificado(), s.ca() });
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, clave);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar TLS: " + e.getMessage(), e);
        }
    }

    // ── Perfil para iPhone (.mobileconfig) ────────────────────────────────

    /**
     * Perfil de configuración de Apple (plist XML, sin firmar) que instala la CA como certificado
     * raíz. iOS lo muestra como "No verificado" porque no está firmado por un desarrollador de
     * Apple; es normal. Los UUID se derivan de la huella para que descargarlo dos veces sea el
     * mismo perfil (iOS lo trata como actualización, no como duplicado).
     */
    public String mobileconfig(String nombreNegocio) {
        byte[] der = caDer();
        String huella = huella(der);
        String uuidPerfil = UUID.nameUUIDFromBytes(("perfil:" + huella).getBytes(StandardCharsets.UTF_8)).toString().toUpperCase(Locale.ROOT);
        String uuidCarga = UUID.nameUUIDFromBytes(("cert:" + huella).getBytes(StandardCharsets.UTF_8)).toString().toUpperCase(Locale.ROOT);
        String negocio = nombreNegocio == null || nombreNegocio.isBlank() ? "la tienda" : nombreNegocio.trim();
        String id = "me.eliassanchezfernandez.puntodeventa.ca." + huella.substring(0, 12).toLowerCase(Locale.ROOT);
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>PayloadContent</key>
                  <array>
                    <dict>
                      <key>PayloadCertificateFileName</key>
                      <string>caja-libre.crt</string>
                      <key>PayloadContent</key>
                      <data>
                %s
                      </data>
                      <key>PayloadDescription</key>
                      <string>Certificado de la computadora de %s para abrir la página segura de Caja Libre en el Wi-Fi del negocio.</string>
                      <key>PayloadDisplayName</key>
                      <string>Caja Libre - certificado de %s</string>
                      <key>PayloadIdentifier</key>
                      <string>%s.cert</string>
                      <key>PayloadType</key>
                      <string>com.apple.security.root</string>
                      <key>PayloadUUID</key>
                      <string>%s</string>
                      <key>PayloadVersion</key>
                      <integer>1</integer>
                    </dict>
                  </array>
                  <key>PayloadDescription</key>
                  <string>Instala el certificado local de Caja Libre. Después activa la confianza en Ajustes, General, Información, Ajustes de confianza de certificados. Huella SHA-256: %s</string>
                  <key>PayloadDisplayName</key>
                  <string>Caja Libre (%s)</string>
                  <key>PayloadIdentifier</key>
                  <string>%s</string>
                  <key>PayloadRemovalDisallowed</key>
                  <false/>
                  <key>PayloadType</key>
                  <string>Configuration</string>
                  <key>PayloadUUID</key>
                  <string>%s</string>
                  <key>PayloadVersion</key>
                  <integer>1</integer>
                </dict>
                </plist>
                """.formatted(base64, xml(negocio), xml(negocio), id, uuidCarga, huellaCorta(huella), xml(negocio), id, uuidPerfil);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ── Generación X.509 (funciones puras, probadas aparte) ───────────────

    static KeyPair nuevoPar() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    /** CA autofirmada */
    static X509Certificate generarCa(KeyPair par, String nombreComun, ZonedDateTime ahora, Duration vigencia, SecureRandom azar)
            throws GeneralSecurityException {
        byte[] nombre = nombre(nombreComun);
        byte[] ski = Der.octetos(sha1(bitsDeClave(par.getPublic())));
        byte[] extensiones = Der.secuencia(
                extension(OID_BASIC_CONSTRAINTS, true, Der.secuencia(Der.booleano(true), Der.entero(0))),   // CA, pathLen 0
                extension(OID_KEY_USAGE, true, Der.bitsBanderas(5, 6)),               // keyCertSign, cRLSign
                extension(OID_NAME_CONSTRAINTS, true, restriccionesDeNombre()),      // solo red local
                extension(OID_SKI, false, ski));
        return firmar(par.getPublic(), par.getPrivate(), nombre, nombre, ahora.minus(MARGEN_ATRAS), ahora.plus(vigencia), extensiones, azar);
    }

    /** Certificado de servidor firmado por la CA */
    static X509Certificate generarServidor(PublicKey publica, PrivateKey claveCa, X509Certificate ca, Set<String> dns, Set<String> ips,
                                           ZonedDateTime ahora, Duration vigencia, SecureRandom azar) throws GeneralSecurityException {
        List<byte[]> nombres = new ArrayList<>();
        for (String d : dns) nombres.add(Der.implicito(2, d.getBytes(StandardCharsets.US_ASCII)));                  // dNSName
        for (String ip : ips) {
            try {
                nombres.add(Der.implicito(7, InetAddress.getByName(ip).getAddress()));                               // iPAddress
            } catch (IOException e) {
                throw new GeneralSecurityException("Dirección IP inválida: " + ip);
            }
        }
        byte[] san = Der.secuencia(nombres.toArray(new byte[0][]));
        byte[] akiValor = Der.secuencia(Der.implicito(0, sha1(bitsDeClave(ca.getPublicKey()))));
        byte[] extensiones = Der.secuencia(
                extension(OID_KEY_USAGE, true, Der.bitsBanderas(0)),                                        // digitalSignature
                extension(OID_EKU, false, Der.secuencia(Der.oid(OID_SERVER_AUTH))),
                extension(OID_SAN, false, san),
                extension(OID_SKI, false, Der.octetos(sha1(bitsDeClave(publica)))),
                extension(OID_AKI, false, akiValor));
        String cn = dns.isEmpty() ? (ips.isEmpty() ? "Caja Libre acceso móvil" : ips.iterator().next()) : "Caja Libre acceso móvil";
        byte[] issuer = ca.getSubjectX500Principal().getEncoded();
        return firmar(publica, claveCa, issuer, nombre(cn), ahora.minus(MARGEN_ATRAS), ahora.plus(vigencia), extensiones, azar);
    }

    private static X509Certificate firmar(PublicKey publica, PrivateKey firmante, byte[] issuer, byte[] subject,
                                          ZonedDateTime desde, ZonedDateTime hasta, byte[] extensiones, SecureRandom azar)
            throws GeneralSecurityException {
        byte[] serie = new byte[16];
        azar.nextBytes(serie);
        serie[0] &= 0x7F;                                              // positivo
        byte[] algoritmo = Der.secuencia(Der.oid(OID_ECDSA_SHA256));
        byte[] tbs = Der.secuencia(
                Der.explicito(0, Der.entero(2)),                       // v3
                Der.entero(new BigInteger(1, serie)),
                algoritmo,
                issuer,
                Der.secuencia(Der.fecha(desde), Der.fecha(hasta)),
                subject,
                publica.getEncoded(),                                  // SubjectPublicKeyInfo ya en DER
                Der.explicito(3, extensiones));
        Signature firma = Signature.getInstance("SHA256withECDSA");
        firma.initSign(firmante);
        firma.update(tbs);
        byte[] cert = Der.secuencia(tbs, algoritmo, Der.bits(firma.sign()));
        return leerCertificado(cert);
    }

    private static byte[] extension(String oid, boolean critica, byte[] valor) {
        return critica ? Der.secuencia(Der.oid(oid), Der.booleano(true), Der.octetos(valor))
                       : Der.secuencia(Der.oid(oid), Der.octetos(valor));
    }

    /** Name: CN=..., O=Caja Libre */
    private static byte[] nombre(String cn) {
        return Der.secuencia(
                Der.conjunto(Der.secuencia(Der.oid(OID_O), Der.utf8("Caja Libre"))),
                Der.conjunto(Der.secuencia(Der.oid(OID_CN), Der.utf8(cn))));
    }

    /** Los bits de la clave pública (sin el AlgorithmIdentifier) para SKI/AKI: 65 bytes en P-256 */
    private static byte[] bitsDeClave(PublicKey k) {
        byte[] spki = k.getEncoded();
        // SPKI = SEQUENCE { AlgorithmIdentifier, BIT STRING }; la clave P-256 sin comprimir ocupa los últimos 65 bytes
        return java.util.Arrays.copyOfRange(spki, spki.length - 65, spki.length);
    }

    private static byte[] sha1(byte[] d) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-1").digest(d);
    }

    private static byte[] sha256(byte[] d) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(d);
    }

    static X509Certificate leerCertificado(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static boolean mismaClave(PrivateKey privada, PublicKey publica) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA256withECDSA");
        byte[] prueba = "caja-libre".getBytes(StandardCharsets.UTF_8);
        s.initSign(privada);
        s.update(prueba);
        byte[] firma = s.sign();
        s.initVerify(publica);
        s.update(prueba);
        return s.verify(firma);
    }

    // ── Archivos ──────────────────────────────────────────────────────────

    private static PrivateKey leerClave(Path p) throws IOException, GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(p)));
    }

    private void guardarClave(Path p, PrivateKey clave) throws IOException {
        guardar(p, clave.getEncoded());
    }

    /** Escribe a un temporal y lo mueve; en POSIX deja permisos de solo el usuario */
    private void guardar(Path p, byte[] datos) throws IOException {
        Files.createDirectories(carpeta);
        restringir(carpeta);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, datos);
        restringir(tmp);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void restringir(Path p) {
        try {
            Files.setPosixFilePermissions(p, Files.isDirectory(p)
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignorada) {
            // Windows: hereda los permisos de la carpeta del usuario
        }
    }

    private ZonedDateTime ahora() {
        return ZonedDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC);
    }
}
