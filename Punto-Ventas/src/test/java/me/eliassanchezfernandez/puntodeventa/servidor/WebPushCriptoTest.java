package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vectores del Apéndice A del RFC 8291 y formato del JWT de VAPID (RFC 8292) */
class WebPushCriptoTest {

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static byte[] b(String s) { return B64.decode(s); }
    private static String e(byte[] b) { return WebPushCripto.B64URL.encodeToString(b); }

    // Apéndice A, RFC 8291
    static final String TEXTO = "When I grow up, I want to be a watermelon";
    static final String AS_PUBLICA = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String AS_PRIVADA = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String UA_PUBLICA = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String UA_PRIVADA = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    static final String SAL = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String ECDH = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs";
    static final String PRK_CLAVE = "Snr3JMxaHVDXHWJn5wdC52WjpCtd2EIEGBykDcZW32k";
    static final String IKM = "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg";
    static final String PRK = "09_eUZGrsvxChDCGRCdkLiDXrReGOEVeSCdCcPBSJSc";
    static final String CEK = "oIhVW04MRdy2XN9CiKLxTg";
    static final String NONCE = "4h_95klXJ5E_qnoN";
    static final String FINAL = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private static KeyPair parServidor() throws Exception {
        ECPrivateKey priv = WebPushCripto.privadaDeCruda(b(AS_PRIVADA));
        ECPublicKey pub = WebPushCripto.publicaDeCruda(b(AS_PUBLICA));
        return new KeyPair(pub, priv);
    }

    @Test
    void losValoresIntermediosCoincidenConElRfc8291() throws Exception {
        KeyPair as = parServidor();
        assertThat(e(WebPushCripto.publicaCruda(as.getPublic()))).isEqualTo(AS_PUBLICA);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(WebPushCripto.publicaDeCruda(b(UA_PUBLICA)), true);
        byte[] ecdh = ka.generateSecret();
        assertThat(e(ecdh)).isEqualTo(ECDH);
        byte[] prkClave = WebPushCripto.hkdfExtraer(b(AUTH), ecdh);
        assertThat(e(prkClave)).isEqualTo(PRK_CLAVE);
        byte[] info = WebPushCripto.bytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), b(UA_PUBLICA), b(AS_PUBLICA));
        byte[] ikm = WebPushCripto.hkdfExpandir(prkClave, info, 32);
        assertThat(e(ikm)).isEqualTo(IKM);
        byte[] prk = WebPushCripto.hkdfExtraer(b(SAL), ikm);
        assertThat(e(prk)).isEqualTo(PRK);
        assertThat(e(WebPushCripto.hkdfExpandir(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16))).isEqualTo(CEK);
        assertThat(e(WebPushCripto.hkdfExpandir(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12))).isEqualTo(NONCE);
    }

    @Test
    void elMensajeCifradoEsExactamenteElDelRfc8291() throws Exception {
        byte[] cuerpo = WebPushCripto.cifrar(b(UA_PUBLICA), b(AUTH), TEXTO.getBytes(StandardCharsets.UTF_8), parServidor(), b(SAL));
        assertThat(e(cuerpo)).isEqualTo(FINAL);
        // Cabecera aes128gcm: sal, rs=4096, idlen=65, clave pública del servidor
        assertThat(Arrays.copyOfRange(cuerpo, 0, 16)).isEqualTo(b(SAL));
        assertThat(Arrays.copyOfRange(cuerpo, 16, 21)).isEqualTo(new byte[] { 0, 0, 0x10, 0, 65 });
        assertThat(Arrays.copyOfRange(cuerpo, 21, 86)).isEqualTo(b(AS_PUBLICA));
    }

    @Test
    void elTelefonoPuedeDescifrarUnMensajeConClaveEfimeraNueva() throws Exception {
        byte[] mensaje = "{\"titulo\":\"Hoy entrega Bimbo\"}".getBytes(StandardCharsets.UTF_8);
        byte[] cuerpo = WebPushCripto.cifrar(b(UA_PUBLICA), b(AUTH), mensaje);
        // Descifrado del lado del teléfono con su clave privada (RFC 8291 §3)
        byte[] sal = Arrays.copyOfRange(cuerpo, 0, 16);
        byte[] asPublica = Arrays.copyOfRange(cuerpo, 21, 86);
        byte[] cifrado = Arrays.copyOfRange(cuerpo, 86, cuerpo.length);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(WebPushCripto.privadaDeCruda(b(UA_PRIVADA)));
        ka.doPhase(WebPushCripto.publicaDeCruda(asPublica), true);
        byte[] prkClave = WebPushCripto.hkdfExtraer(b(AUTH), ka.generateSecret());
        byte[] ikm = WebPushCripto.hkdfExpandir(prkClave, WebPushCripto.bytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), b(UA_PUBLICA), asPublica), 32);
        byte[] prk = WebPushCripto.hkdfExtraer(sal, ikm);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(WebPushCripto.hkdfExpandir(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16), "AES"),
                new GCMParameterSpec(128, WebPushCripto.hkdfExpandir(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12)));
        byte[] registro = aes.doFinal(cifrado);
        assertThat(registro[registro.length - 1]).isEqualTo((byte) 2);
        assertThat(Arrays.copyOf(registro, registro.length - 1)).isEqualTo(mensaje);
    }

    @Test
    void rechazaClavesYSecretosInvalidos() {
        byte[] texto = "x".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> WebPushCripto.cifrar(new byte[65], b(AUTH), texto)).hasMessageContaining("0x04");
        byte[] fueraDeCurva = b(UA_PUBLICA).clone();
        fueraDeCurva[64] ^= 1;
        assertThatThrownBy(() -> WebPushCripto.cifrar(fueraDeCurva, b(AUTH), texto)).hasMessageContaining("curva");
        assertThatThrownBy(() -> WebPushCripto.cifrar(b(UA_PUBLICA), new byte[15], texto)).hasMessageContaining("16 bytes");
        assertThatThrownBy(() -> WebPushCripto.cifrar(b(UA_PUBLICA), b(AUTH), new byte[WebPushCripto.MAX_MENSAJE + 1])).hasMessageContaining("largo");
    }

    @Test
    void elJwtDeVapidTieneElFormatoDelRfc8292YSeVerificaConLaClavePublica() throws Exception {
        KeyPair par = WebPushCripto.nuevoPar();
        long exp = 1_800_000_000L;
        String jwt = WebPushCripto.jwtVapid(par.getPrivate(), "https://fcm.googleapis.com", exp, "https://github.com/Minepacman/Punto-Ventas");
        String[] partes = jwt.split("\\.");
        assertThat(partes).hasSize(3);
        ObjectMapper json = new ObjectMapper();
        JsonNode cab = json.readTree(B64.decode(partes[0]));
        assertThat(cab.get("typ").asText()).isEqualTo("JWT");
        assertThat(cab.get("alg").asText()).isEqualTo("ES256");
        JsonNode carga = json.readTree(B64.decode(partes[1]));
        assertThat(carga.get("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(carga.get("exp").asLong()).isEqualTo(exp);
        assertThat(carga.get("sub").asText()).isEqualTo("https://github.com/Minepacman/Punto-Ventas");
        assertThat(B64.decode(partes[2])).as("firma JWS cruda r||s").hasSize(64);
        assertThat(jwt).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(WebPushCripto.verificarJwt(jwt, par.getPublic())).isTrue();
        assertThat(WebPushCripto.verificarJwt(jwt, WebPushCripto.nuevoPar().getPublic())).isFalse();
        assertThat(WebPushCripto.verificarJwt(jwt.substring(0, jwt.length() - 2) + "AA", par.getPublic())).isFalse();

        String auth = WebPushCripto.cabeceraAuthorization(jwt, par.getPublic());
        assertThat(auth).startsWith("vapid t=" + jwt + ", k=");
        String k = auth.substring(auth.indexOf("k=") + 2);
        assertThat(B64.decode(k)).hasSize(65).startsWith((byte) 4);
    }

    @Test
    void laConversionDerCrudoEsReversibleYQuitaLosCerosDeSigno() throws Exception {
        for (int i = 0; i < 20; i++) {
            KeyPair par = WebPushCripto.nuevoPar();
            java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
            s.initSign(par.getPrivate());
            s.update(new byte[] { (byte) i });
            byte[] der = s.sign();
            byte[] crudo = WebPushCripto.derACrudo(der);
            assertThat(crudo).hasSize(64);
            java.security.Signature v = java.security.Signature.getInstance("SHA256withECDSA");
            v.initVerify(par.getPublic());
            v.update(new byte[] { (byte) i });
            assertThat(v.verify(WebPushCripto.crudoADer(crudo))).isTrue();
        }
    }
}
