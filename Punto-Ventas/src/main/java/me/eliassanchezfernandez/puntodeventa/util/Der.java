package me.eliassanchezfernandez.puntodeventa.util;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Codificador ASN.1 DER mínimo (solo lo que hace falta para escribir un certificado X.509 v3
 * con las APIs públicas del JDK, sin BouncyCastle ni clases internas sun.security.x509).
 *
 * Cada método devuelve el TLV completo (etiqueta, longitud, contenido). No hay decodificador:
 * para leer certificados se usa {@code CertificateFactory}.
 */
public final class Der {

    private Der() { }

    // ── Etiquetas universales ─────────────────────────────────────────────
    static final int BOOLEAN = 0x01, INTEGER = 0x02, BIT_STRING = 0x03, OCTET_STRING = 0x04, NULL = 0x05,
            OID = 0x06, UTF8_STRING = 0x0C, PRINTABLE_STRING = 0x13, IA5_STRING = 0x16,
            UTC_TIME = 0x17, GENERALIZED_TIME = 0x18, SEQUENCE = 0x30, SET = 0x31;

    /** TLV con cualquier etiqueta y contenido ya codificado */
    public static byte[] tlv(int etiqueta, byte[] contenido) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(contenido.length + 6);
        out.write(etiqueta);
        escribirLongitud(out, contenido.length);
        out.writeBytes(contenido);
        return out.toByteArray();
    }

    private static void escribirLongitud(ByteArrayOutputStream out, int n) {
        if (n < 0x80) { out.write(n); return; }
        int bytes = n <= 0xFF ? 1 : n <= 0xFFFF ? 2 : n <= 0xFFFFFF ? 3 : 4;
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) out.write((n >>> (8 * i)) & 0xFF);
    }

    public static byte[] concatenar(byte[]... partes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : partes) out.writeBytes(p);
        return out.toByteArray();
    }

    public static byte[] secuencia(byte[]... elementos) { return tlv(SEQUENCE, concatenar(elementos)); }

    public static byte[] conjunto(byte[]... elementos) { return tlv(SET, concatenar(elementos)); }

    /** Etiqueta de contexto [n] EXPLICIT (constructed) */
    public static byte[] explicito(int n, byte[] contenido) { return tlv(0xA0 | n, contenido); }

    /** Etiqueta de contexto [n] IMPLICIT primitiva: sustituye la etiqueta del valor por la de contexto */
    public static byte[] implicito(int n, byte[] contenidoSinEtiqueta) { return tlv(0x80 | n, contenidoSinEtiqueta); }

    public static byte[] booleano(boolean v) { return tlv(BOOLEAN, new byte[] { (byte) (v ? 0xFF : 0x00) }); }

    public static byte[] entero(BigInteger v) { return tlv(INTEGER, v.toByteArray()); }

    public static byte[] entero(long v) { return entero(BigInteger.valueOf(v)); }

    public static byte[] nulo() { return tlv(NULL, new byte[0]); }

    public static byte[] octetos(byte[] v) { return tlv(OCTET_STRING, v); }

    /** BIT STRING con 0 bits sin usar (firmas, claves públicas) */
    public static byte[] bits(byte[] v) { return tlv(BIT_STRING, concatenar(new byte[] { 0 }, v)); }

    /**
     * BIT STRING de banderas (KeyUsage): el bit 0 es el más significativo del primer byte.
     * Se recorta a los bytes necesarios y se declaran los bits sobrantes, como exige DER.
     */
    public static byte[] bitsBanderas(int... bitsActivos) {
        int max = -1;
        for (int b : bitsActivos) max = Math.max(max, b);
        if (max < 0) return tlv(BIT_STRING, new byte[] { 0 });
        byte[] datos = new byte[max / 8 + 1];
        for (int b : bitsActivos) datos[b / 8] |= (byte) (0x80 >>> (b % 8));
        int sinUsar = 7 - (max % 8);
        return tlv(BIT_STRING, concatenar(new byte[] { (byte) sinUsar }, datos));
    }

    public static byte[] utf8(String s) { return tlv(UTF8_STRING, s.getBytes(StandardCharsets.UTF_8)); }

    public static byte[] printable(String s) { return tlv(PRINTABLE_STRING, s.getBytes(StandardCharsets.US_ASCII)); }

    public static byte[] ia5(String s) { return tlv(IA5_STRING, s.getBytes(StandardCharsets.US_ASCII)); }

    /** OBJECT IDENTIFIER a partir de su forma con puntos ("1.2.840.10045.4.3.2") */
    public static byte[] oid(String puntos) {
        String[] p = puntos.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        escribirBase128(out, Long.parseLong(p[0]) * 40 + Long.parseLong(p[1]));
        for (int i = 2; i < p.length; i++) escribirBase128(out, Long.parseLong(p[i]));
        return tlv(OID, out.toByteArray());
    }

    private static void escribirBase128(ByteArrayOutputStream out, long v) {
        int n = 1;
        while ((v >>> (7 * n)) != 0) n++;
        for (int i = n - 1; i >= 0; i--) {
            int b = (int) ((v >>> (7 * i)) & 0x7F);
            out.write(i == 0 ? b : (b | 0x80));
        }
    }

    /** Fecha X.509: UTCTime hasta 2049 y GeneralizedTime desde 2050 (RFC 5280 §4.1.2.5) */
    public static byte[] fecha(ZonedDateTime t) {
        ZonedDateTime utc = t.withZoneSameInstant(ZoneOffset.UTC).withNano(0);
        if (utc.getYear() < 2050) {
            return tlv(UTC_TIME, utc.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).getBytes(StandardCharsets.US_ASCII));
        }
        return tlv(GENERALIZED_TIME, utc.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).getBytes(StandardCharsets.US_ASCII));
    }
}
