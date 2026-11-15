package me.eliassanchezfernandez.puntodeventa.servidor;

import me.eliassanchezfernandez.puntodeventa.util.Der;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Criptografía de Web Push con el JDK, sin bibliotecas:
 *
 *  - Cifrado del mensaje (RFC 8291 + RFC 8188, "aes128gcm"): ECDH P-256 con la clave del
 *    teléfono, HKDF-SHA256 con el secreto de autenticación y AES-128-GCM en un solo registro.
 *    Probado contra el ejemplo del Apéndice A del RFC 8291.
 *  - Identificación del servidor (RFC 8292, VAPID): JWT firmado con ES256. La firma ECDSA del
 *    JDK viene en DER (SEQUENCE de dos INTEGER) y JWS exige los 64 bytes crudos r||s: se convierte.
 *
 * Todo son funciones puras: las claves efímeras y la sal se pueden fijar desde las pruebas.
 */
public final class WebPushCripto {

    private WebPushCripto() { }

    /** Tamaño de registro que anunciamos (RFC 8188); el mensaje cabe en uno solo */
    static final int TAMANO_REGISTRO = 4096;
    /** Máximo del mensaje en claro: los servicios de push aceptan 4 KB cifrados */
    public static final int MAX_MENSAJE = 3800;

    private static final byte[] INFO_CLAVE = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INFO_CEK = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INFO_NONCE = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    public static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    public static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    // ── Claves P-256 ──────────────────────────────────────────────────────

    public static KeyPair nuevoPar() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        return p.getParameterSpec(ECParameterSpec.class);
    }

    /** Clave pública P-256 sin comprimir: 0x04 || X (32) || Y (32) */
    public static byte[] publicaCruda(PublicKey k) {
        ECPoint w = ((ECPublicKey) k).getW();
        byte[] r = new byte[65];
        r[0] = 4;
        copiar32(w.getAffineX(), r, 1);
        copiar32(w.getAffineY(), r, 33);
        return r;
    }

    private static void copiar32(BigInteger v, byte[] destino, int desde) {
        byte[] b = v.toByteArray();
        int inicio = b.length > 32 ? b.length - 32 : 0;          // quita el 0 de signo
        int n = b.length - inicio;
        System.arraycopy(b, inicio, destino, desde + (32 - n), n);
    }

    /** Clave pública desde los 65 bytes sin comprimir; falla si no es un punto válido de la curva */
    public static ECPublicKey publicaDeCruda(byte[] b) throws GeneralSecurityException {
        if (b == null || b.length != 65 || b[0] != 4) throw new GeneralSecurityException("La clave pública debe tener 65 bytes y empezar por 0x04.");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(b, 1, 33)), y = new BigInteger(1, Arrays.copyOfRange(b, 33, 65));
        ECParameterSpec curva = p256();
        // Comprobación de que el punto está en la curva: y² = x³ + ax + b (mod p)
        BigInteger p = ((java.security.spec.ECFieldFp) curva.getCurve().getField()).getP();
        BigInteger izq = y.modPow(BigInteger.TWO, p);
        BigInteger der = x.modPow(BigInteger.valueOf(3), p).add(curva.getCurve().getA().multiply(x)).add(curva.getCurve().getB()).mod(p);
        if (!izq.equals(der) || x.signum() == 0 && y.signum() == 0) throw new GeneralSecurityException("La clave pública no es un punto de la curva P-256.");
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curva));
    }

    /** Clave privada desde su escalar de 32 bytes (pruebas con los vectores del RFC) */
    static ECPrivateKey privadaDeCruda(byte[] d) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), p256()));
    }

    // ── Cifrado aes128gcm (RFC 8291) ──────────────────────────────────────

    /** Cifra con clave efímera y sal nuevas (uso normal) */
    public static byte[] cifrar(byte[] publicaTelefono, byte[] secretoAuth, byte[] mensaje) throws GeneralSecurityException {
        byte[] sal = new byte[16];
        new SecureRandom().nextBytes(sal);
        return cifrar(publicaTelefono, secretoAuth, mensaje, nuevoPar(), sal);
    }

    /**
     * Cifrado determinista (pruebas): el cuerpo HTTP completo con cabecera aes128gcm:
     * sal(16) || rs(4) || idlen(1)=65 || clave pública del servidor(65) || texto cifrado.
     */
    public static byte[] cifrar(byte[] publicaTelefono, byte[] secretoAuth, byte[] mensaje, KeyPair efimero, byte[] sal)
            throws GeneralSecurityException {
        if (mensaje.length > MAX_MENSAJE) throw new GeneralSecurityException("Mensaje demasiado largo para una notificación.");
        if (secretoAuth == null || secretoAuth.length != 16) throw new GeneralSecurityException("El secreto auth debe tener 16 bytes.");
        if (sal.length != 16) throw new GeneralSecurityException("La sal debe tener 16 bytes.");
        ECPublicKey ua = publicaDeCruda(publicaTelefono);
        byte[] asPublica = publicaCruda(efimero.getPublic());

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(efimero.getPrivate());
        ka.doPhase(ua, true);
        byte[] ecdh = ka.generateSecret();

        byte[] prkClave = hkdfExtraer(secretoAuth, ecdh);
        byte[] ikm = hkdfExpandir(prkClave, Der.concatenar(INFO_CLAVE, publicaTelefono, asPublica), 32);
        byte[] prk = hkdfExtraer(sal, ikm);
        byte[] cek = hkdfExpandir(prk, INFO_CEK, 16);
        byte[] nonce = hkdfExpandir(prk, INFO_NONCE, 12);

        byte[] registro = Der.concatenar(mensaje, new byte[] { 2 });   // 0x02 = último registro, sin relleno
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] cifrado = aes.doFinal(registro);

        ByteBuffer cuerpo = ByteBuffer.allocate(16 + 4 + 1 + 65 + cifrado.length);
        cuerpo.put(sal).putInt(TAMANO_REGISTRO).put((byte) 65).put(asPublica).put(cifrado);
        return cuerpo.array();
    }

    static byte[] hkdfExtraer(byte[] sal, byte[] ikm) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sal, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    /** HKDF-Expand para largos de hasta 32 bytes (un solo bloque) */
    static byte[] hkdfExpandir(byte[] prk, byte[] info, int largo) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), largo);
    }

    // ── VAPID (RFC 8292) ──────────────────────────────────────────────────

    /**
     * JWT ES256: cabecera {"typ":"JWT","alg":"ES256"}, carga {aud, exp, sub}.
     * @param audiencia origen del servicio de push ("https://fcm.googleapis.com")
     * @param expiraEpoch segundos UNIX (máximo 24 h desde ahora)
     * @param contacto "mailto:..." o URL https del responsable
     */
    public static String jwtVapid(PrivateKey clave, String audiencia, long expiraEpoch, String contacto) throws GeneralSecurityException {
        String cabecera = B64URL.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String carga = B64URL.encodeToString(("{\"aud\":\"" + json(audiencia) + "\",\"exp\":" + expiraEpoch + ",\"sub\":\"" + json(contacto) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String firmado = cabecera + "." + carga;
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(clave);
        s.update(firmado.getBytes(StandardCharsets.US_ASCII));
        return firmado + "." + B64URL.encodeToString(derACrudo(s.sign()));
    }

    /** Valor de la cabecera Authorization: {@code vapid t=<jwt>, k=<clave pública>} */
    public static String cabeceraAuthorization(String jwt, PublicKey publica) {
        return "vapid t=" + jwt + ", k=" + B64URL.encodeToString(publicaCruda(publica));
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** ECDSA-Sig-Value DER (SEQUENCE { r INTEGER, s INTEGER }) → r||s de 64 bytes (JWS) */
    static byte[] derACrudo(byte[] der) throws GeneralSecurityException {
        if (der.length < 8 || der[0] != 0x30) throw new GeneralSecurityException("Firma DER inválida.");
        int i = 2;
        if ((der[1] & 0x80) != 0) i += der[1] & 0x7F;                   // longitud larga (no ocurre en P-256, por si acaso)
        byte[] r = new byte[64];
        for (int parte = 0; parte < 2; parte++) {
            if (der[i++] != 0x02) throw new GeneralSecurityException("Firma DER inválida.");
            int largo = der[i++] & 0xFF;
            int inicio = i;
            while (largo > 32 && der[inicio] == 0) { inicio++; largo--; }  // quita ceros de signo
            if (largo > 32) throw new GeneralSecurityException("Firma DER inválida.");
            System.arraycopy(der, inicio, r, parte * 32 + (32 - largo), largo);
            i = inicio + largo;
        }
        return r;
    }

    /** r||s (64 bytes) → DER, para verificar un JWT con Signature del JDK (pruebas) */
    static byte[] crudoADer(byte[] crudo) {
        if (crudo.length != 64) throw new IllegalArgumentException("La firma cruda debe tener 64 bytes.");
        return Der.secuencia(Der.entero(new BigInteger(1, Arrays.copyOfRange(crudo, 0, 32))),
                             Der.entero(new BigInteger(1, Arrays.copyOfRange(crudo, 32, 64))));
    }

    /** Verifica un JWT ES256 (pruebas y comprobación propia) */
    public static boolean verificarJwt(String jwt, PublicKey publica) {
        try {
            String[] partes = jwt.split("\\.");
            if (partes.length != 3) return false;
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(publica);
            s.update((partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII));
            return s.verify(crudoADer(B64URL_DEC.decode(partes[2])));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Concatena en un flujo (ayuda para pruebas) */
    static byte[] bytes(byte[]... partes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : partes) out.writeBytes(p);
        return out.toByteArray();
    }
}
