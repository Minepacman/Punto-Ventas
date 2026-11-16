package me.eliassanchezfernandez.puntodeventa.servidor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CA local y certificado del servidor generados solo con el JDK: forma, vigencia, SAN, cadena y renovación */
class CertificadosLocalesTest {

    @TempDir Path carpeta;

    private static Clock relojFijo() {
        return Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void laCaEsUnaRaizVigenteDiezAnosYElServidorEncadenaConElla() throws Exception {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-11-14T12:00:00Z"));
        CertificadosLocales c = new CertificadosLocales(carpeta.resolve("claves"), reloj);
        X509Certificate ca = c.ca();
        assertThat(ca.getBasicConstraints()).as("CA:TRUE").isGreaterThanOrEqualTo(0);
        assertThat(ca.getCriticalExtensionOIDs()).contains("2.5.29.19", "2.5.29.15");
        assertThat(ca.getKeyUsage()[5]).as("keyCertSign").isTrue();
        assertThat(ca.getKeyUsage()[6]).as("cRLSign").isTrue();
        assertThat(ca.getSigAlgName()).containsIgnoringCase("SHA256").containsIgnoringCase("ECDSA");
        assertThat(ca.getPublicKey().getAlgorithm()).isEqualTo("EC");
        assertThat(ca.getSubjectX500Principal().getName()).contains("CN=Caja Libre CA local").contains("O=Caja Libre");
        assertThat(ca.getIssuerX500Principal()).isEqualTo(ca.getSubjectX500Principal());
        assertThat(ca.getVersion()).isEqualTo(3);
        assertThat(ca.getSerialNumber().signum()).isPositive();
        assertThat(Duration.between(ca.getNotBefore().toInstant(), ca.getNotAfter().toInstant()))
                .isEqualTo(CertificadosLocales.VIGENCIA_CA.plus(CertificadosLocales.MARGEN_ATRAS));
        ca.checkValidity(java.util.Date.from(reloj.instant()));
        ca.verify(ca.getPublicKey());
        assertThat(ca.getExtensionValue("2.5.29.14")).as("SubjectKeyIdentifier").isNotNull();

        CertificadosLocales.Servidor s = c.servidor(List.of("localhost", "mi-pc.local", "MiTienda.local"), List.of("192.168.1.20", "127.0.0.1"));
        X509Certificate cert = s.certificado();
        assertThat(cert.getBasicConstraints()).as("no es CA").isEqualTo(-1);
        assertThat(cert.getKeyUsage()[0]).as("digitalSignature").isTrue();
        assertThat(cert.getExtendedKeyUsage()).containsExactly("1.3.6.1.5.5.7.3.1");
        assertThat(cert.getIssuerX500Principal()).isEqualTo(ca.getSubjectX500Principal());
        assertThat(Duration.between(cert.getNotBefore().toInstant(), cert.getNotAfter().toInstant()))
                .isLessThanOrEqualTo(Duration.ofDays(825));       // límite de iOS para hojas TLS
        cert.verify(ca.getPublicKey());
        cert.checkValidity(java.util.Date.from(reloj.instant()));
        List<String> sans = new ArrayList<>();
        for (List<?> san : cert.getSubjectAlternativeNames()) sans.add(san.get(0) + ":" + san.get(1));
        assertThat(sans).containsExactlyInAnyOrder("2:localhost", "2:mi-pc.local", "2:mitienda.local", "7:192.168.1.20", "7:127.0.0.1");
        assertThat(cert.getExtensionValue("2.5.29.35")).as("AuthorityKeyIdentifier").isNotNull();
        assertThat(s.ca()).isSameAs(ca);

        // Cadena validada con PKIX, como haría un navegador que confía en la CA
        CertPathValidator v = CertPathValidator.getInstance("PKIX");
        PKIXParameters p = new PKIXParameters(Set.of(new TrustAnchor(ca, null)));
        p.setRevocationEnabled(false);
        p.setDate(java.util.Date.from(reloj.instant()));
        v.validate(CertificateFactory.getInstance("X.509").generateCertPath(List.of(cert)), p);

        // Un TrustManager del JDK que confía solo en la CA acepta la cadena
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        ((javax.net.ssl.X509TrustManager) tmf.getTrustManagers()[0]).checkServerTrusted(new X509Certificate[] { cert, ca }, "ECDHE_ECDSA");
        SSLContext tls = CertificadosLocales.contextoTls(s);
        assertThat(tls).isNotNull();
    }

    @Test
    void reutilizaElCertificadoMientrasSirvaYLoRenuevaSinCambiarLaCaCuandoCambianLasDirecciones() throws Exception {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-11-14T12:00:00Z"));
        CertificadosLocales c = new CertificadosLocales(carpeta, reloj);
        X509Certificate ca = c.ca();
        X509Certificate s1 = c.servidor(List.of("localhost"), List.of("192.168.1.20")).certificado();
        assertThat(c.servidor(List.of("LOCALHOST"), List.of("192.168.1.20")).certificado()).as("mismos nombres: mismo certificado").isEqualTo(s1);
        assertThat(c.servidorVigentePara(List.of("localhost"), List.of("192.168.1.20"))).isTrue();
        assertThat(c.servidorVigentePara(List.of("localhost"), List.of("192.168.1.21"))).isFalse();

        // Otra instancia (reinicio del programa) lee los mismos archivos
        CertificadosLocales c2 = new CertificadosLocales(carpeta, reloj);
        assertThat(c2.ca()).isEqualTo(ca);
        assertThat(c2.servidor(List.of("localhost"), List.of("192.168.1.20")).certificado()).isEqualTo(s1);

        // Cambió la IP: certificado nuevo, misma CA
        X509Certificate s2 = c2.servidor(List.of("localhost"), List.of("10.0.0.5")).certificado();
        assertThat(s2).isNotEqualTo(s1);
        assertThat(c2.ca()).isEqualTo(ca);
        s2.verify(ca.getPublicKey());

        // Le queda menos de un mes: se renueva solo
        reloj.avanzar(CertificadosLocales.VIGENCIA_SERVIDOR.minus(Duration.ofDays(20)));
        X509Certificate s3 = c2.servidor(List.of("localhost"), List.of("10.0.0.5")).certificado();
        assertThat(s3).isNotEqualTo(s2);
        assertThat(c2.ca()).isEqualTo(ca);

        // Regenerar la CA: todo cambia y la huella también
        String huellaAntes = c2.huellaCa();
        c2.regenerarCa();
        assertThat(c2.ca()).isNotEqualTo(ca);
        assertThat(c2.huellaCa()).isNotEqualTo(huellaAntes).hasSize(64).matches("[0-9A-F]+");
        X509Certificate s4 = c2.servidor(List.of("localhost"), List.of("10.0.0.5")).certificado();
        s4.verify(c2.ca().getPublicKey());
        assertThat(CertificadosLocales.huellaCorta("A1B2C3D4E5F67890FFFF")).isEqualTo("A1B2 C3D4 E5F6 7890");
        assertThat(CertificadosLocales.huellaConDosPuntos("A1B2C3")).isEqualTo("A1:B2:C3");
    }

    @Test
    void lasClavesQuedanEnLaCarpetaConPermisosDeSoloElUsuarioYNoSeEntreganNunca() throws Exception {
        CertificadosLocales c = new CertificadosLocales(carpeta.resolve("claves"), relojFijo());
        c.servidor(List.of("localhost"), List.of());
        Path dir = carpeta.resolve("claves");
        assertThat(dir.resolve("movil-ca.key")).exists();
        assertThat(dir.resolve("movil-ca.crt")).exists();
        assertThat(dir.resolve("movil-servidor.key")).exists();
        assertThat(dir.resolve("movil-servidor.crt")).exists();
        assertThat(Files.list(dir).filter(p -> p.toString().endsWith(".tmp")).count()).isZero();
        try {
            assertThat(Files.getPosixFilePermissions(dir.resolve("movil-ca.key"))).containsExactlyInAnyOrder(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(dir)).doesNotContain(
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ, java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
        } catch (UnsupportedOperationException windows) { /* sin POSIX: hereda los permisos del perfil */ }
        // El DER público es lo único que sale: no contiene la clave privada
        byte[] der = c.caDer();
        assertThat(der).isEqualTo(c.ca().getEncoded());
        assertThat(new String(der, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(
                new String(Files.readAllBytes(dir.resolve("movil-ca.key")), java.nio.charset.StandardCharsets.ISO_8859_1).substring(30, 60));
    }

    /**
     * La CA queda limitada a la red local (nameConstraints crítico + pathLen 0): con su clave privada robada
     * no se puede falsificar un sitio de internet ante los teléfonos que la instalaron.
     */
    @Test
    void laCaSoloPuedeFirmarNombresYDireccionesDeLaRedLocal() throws Exception {
        CertificadosLocales c = new CertificadosLocales(carpeta.resolve("claves"), relojFijo());
        X509Certificate ca = c.ca();
        assertThat(ca.getCriticalExtensionOIDs()).contains(CertificadosLocales.OID_NAME_CONSTRAINTS);
        assertThat(ca.getBasicConstraints()).as("pathLen 0: no puede crear CA intermedias").isZero();

        // Lo permitido valida; un sitio de internet (por nombre o por IP pública) no
        assertThat(valida(ca, hoja(c, ca, Set.of("localhost", "tienda.local"), Set.of("192.168.1.20", "10.1.2.3", "172.20.0.1", "127.0.0.1")))).isTrue();
        assertThat(valida(ca, hoja(c, ca, Set.of("www.banco.com.mx"), Set.of()))).as("dominio de internet").isFalse();
        assertThat(valida(ca, hoja(c, ca, Set.of("local.evil.com"), Set.of()))).as("parecido a .local").isFalse();
        assertThat(valida(ca, hoja(c, ca, Set.of(), Set.of("8.8.8.8")))).as("IP pública").isFalse();
        assertThat(valida(ca, hoja(c, ca, Set.of("localhost", "accounts.google.com"), Set.of("192.168.1.20")))).as("un nombre ajeno tumba toda la hoja").isFalse();

        // El servidor nunca pide nombres fuera de las restricciones: los descarta en vez de romper la cadena
        X509Certificate s = c.servidor(List.of("localhost", "mi-pc", "mi-pc.lan", "tienda.local"), List.of("192.168.1.20", "8.8.8.8", "fe80::1")).certificado();
        List<String> sans = new ArrayList<>();
        for (List<?> san : s.getSubjectAlternativeNames()) sans.add(san.get(0) + ":" + san.get(1));
        assertThat(sans).containsExactlyInAnyOrder("2:localhost", "2:tienda.local", "7:192.168.1.20");
        assertThat(valida(ca, s)).isTrue();
        assertThat(c.servidorVigentePara(List.of("localhost", "mi-pc", "tienda.local"), List.of("192.168.1.20", "8.8.8.8"))).isTrue();
    }

    private X509Certificate hoja(CertificadosLocales c, X509Certificate ca, Set<String> dns, Set<String> ips) throws Exception {
        java.security.PrivateKey claveCa = java.security.KeyFactory.getInstance("EC").generatePrivate(
                new java.security.spec.PKCS8EncodedKeySpec(Files.readAllBytes(c.carpeta().resolve(CertificadosLocales.ARCHIVO_CA_CLAVE))));
        return CertificadosLocales.generarServidor(CertificadosLocales.nuevoPar().getPublic(), claveCa, ca, dns, ips,
                java.time.ZonedDateTime.ofInstant(relojFijo().instant(), ZoneOffset.UTC), Duration.ofDays(30), new java.security.SecureRandom());
    }

    /**
     * Valida como un teléfono: Chrome/Android e iOS aplican las nameConstraints de la raíz instalada. El PKIX del
     * JDK no las aplica al certificado ancla, así que la cadena (firma, fechas) se valida con PKIX y las
     * restricciones con X509CertSelector.setPathToNames, que comprueba si las nameConstraints de la CA permiten
     * cada nombre de la hoja (también comprueba que el DER de la extensión sea válido).
     */
    private static boolean valida(X509Certificate ca, X509Certificate hoja) throws Exception {
        PKIXParameters p = new PKIXParameters(Set.of(new TrustAnchor(ca, null)));
        p.setRevocationEnabled(false);
        p.setDate(java.util.Date.from(relojFijo().instant()));
        CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(List.of(hoja)), p);
        java.security.cert.X509CertSelector sel = new java.security.cert.X509CertSelector();
        for (List<?> san : hoja.getSubjectAlternativeNames()) sel.addPathToName((Integer) san.get(0), (String) san.get(1));
        return sel.match(ca);
    }

    @Test
    void elPerfilParaIphoneLlevaLaCaEnBase64YUuidsEstables() {
        CertificadosLocales c = new CertificadosLocales(carpeta, relojFijo());
        String m = c.mobileconfig("Abarrotes \"La Esquina\" <centro>");
        assertThat(m).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .contains("<string>com.apple.security.root</string>")
                .contains("<string>Configuration</string>")
                .contains("<key>PayloadRemovalDisallowed</key>")
                .contains("Abarrotes &quot;La Esquina&quot; &lt;centro&gt;")
                .contains(c.huellaCorta())
                .doesNotContain("<centro>");
        String b64 = java.util.Base64.getEncoder().encodeToString(c.caDer());
        assertThat(m.replace("\n", "").replace(" ", "")).contains(b64);
        assertThat(c.mobileconfig("x")).as("mismos UUID al descargar dos veces").isEqualTo(c.mobileconfig("x"));
        java.util.regex.Matcher uuids = java.util.regex.Pattern.compile("<key>PayloadUUID</key>\\s*<string>([0-9A-F-]{36})</string>").matcher(m);
        int n = 0;
        while (uuids.find()) n++;
        assertThat(n).isEqualTo(2);
    }
}
