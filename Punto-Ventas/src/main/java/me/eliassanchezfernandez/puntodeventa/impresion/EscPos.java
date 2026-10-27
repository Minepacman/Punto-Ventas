package me.eliassanchezfernandez.puntodeventa.impresion;

import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * Generador de bytes ESC/POS (el "idioma" de casi todas las impresoras de
 * tickets de 58 y 80 mm). No depende de ninguna librería: solo arma bytes.
 *
 * Comandos usados:
 *   ESC @        inicializar                ESC t n   tabla de caracteres (n = 0 CP437, 2 CP850)
 *   ESC a n      alineación 0/1/2           ESC E n   negrita
 *   GS ! n       tamaño (0x11 = doble ancho y alto)
 *   ESC d n      avanzar n líneas           GS V 1    corte parcial
 *   ESC p 0 25 250  pulso al cajón de dinero (pin 2, 50 ms encendido, 500 ms apagado)
 *
 * Los acentos y la ñ se envían en la tabla CP850 (o CP437); caracteres que la
 * tabla no tiene se imprimen sin acento y los que no existen como "?".
 */
public final class EscPos {

    public enum CodePage {
        CP850(2, "CP850 (Multilingüe latín 1)"), CP437(0, "CP437 (EE. UU.)");

        private final int numero;
        private final String etiqueta;

        CodePage(int numero, String etiqueta) { this.numero = numero; this.etiqueta = etiqueta; }

        /** Valor de n en ESC t n */
        public int numero() { return numero; }
        public String etiqueta() { return etiqueta; }
        @Override public String toString() { return etiqueta; }

        public static CodePage de(String nombre) {
            if (nombre != null) {
                for (CodePage c : values()) if (c.name().equalsIgnoreCase(nombre.trim())) return c;
            }
            return CP850;
        }
    }

    public static final byte ESC = 0x1B;
    public static final byte GS = 0x1D;
    public static final byte LF = 0x0A;

    /** Pulso al cajón: ESC p 0 25 250 */
    public static final byte[] ABRIR_CAJON = { ESC, 'p', 0, 25, (byte) 250 };

    private final ByteArrayOutputStream salida = new ByteArrayOutputStream();
    private final CodePage codePage;

    public EscPos(CodePage codePage) {
        this.codePage = codePage != null ? codePage : CodePage.CP850;
    }

    public EscPos inicializar() {
        salida.writeBytes(new byte[] { ESC, '@' });
        salida.writeBytes(new byte[] { ESC, 't', (byte) codePage.numero() });
        return this;
    }

    public EscPos alinear(DocumentoImpresion.Alineacion alineacion) {
        int n = switch (alineacion) { case CENTRO -> 1; case DERECHA -> 2; default -> 0; };
        salida.writeBytes(new byte[] { ESC, 'a', (byte) n });
        return this;
    }

    public EscPos negrita(boolean activa) {
        salida.writeBytes(new byte[] { ESC, 'E', (byte) (activa ? 1 : 0) });
        return this;
    }

    /** Tamaño doble de ancho y alto, o normal */
    public EscPos grande(boolean activo) {
        salida.writeBytes(new byte[] { GS, '!', (byte) (activo ? 0x11 : 0x00) });
        return this;
    }

    public EscPos texto(String texto) {
        salida.writeBytes(codificar(texto, codePage));
        return this;
    }

    public EscPos salto() {
        salida.write(LF);
        return this;
    }

    public EscPos avanzar(int lineas) {
        salida.writeBytes(new byte[] { ESC, 'd', (byte) Math.max(0, Math.min(lineas, 255)) });
        return this;
    }

    /** Avanza el papel para que el texto salga de la cuchilla y corta (parcial) */
    public EscPos cortar() {
        avanzar(4);
        salida.writeBytes(new byte[] { GS, 'V', 1 });
        return this;
    }

    public EscPos abrirCajon() {
        salida.writeBytes(ABRIR_CAJON);
        return this;
    }

    public byte[] bytes() {
        return salida.toByteArray();
    }

    // ── Documento completo ────────────────────────────────────────────────

    /** Traduce un documento: cada línea con su alineación, negrita y tamaño; al final corta (y abre el cajón si se pide) */
    public static byte[] renderizar(DocumentoImpresion doc, CodePage codePage, boolean abrirCajon, boolean cortar) {
        EscPos e = new EscPos(codePage).inicializar();
        for (DocumentoImpresion.Linea l : doc.lineas()) {
            e.alinear(l.alineacion());
            if (l.negrita()) e.negrita(true);
            if (l.grande()) e.grande(true);
            e.texto(l.texto()).salto();
            if (l.grande()) e.grande(false);
            if (l.negrita()) e.negrita(false);
        }
        e.alinear(DocumentoImpresion.Alineacion.IZQUIERDA);
        if (cortar) e.cortar();
        else e.avanzar(3);
        if (abrirCajon) e.abrirCajon();
        return e.bytes();
    }

    /** Solo inicializa y manda el pulso al cajón (apertura manual) */
    public static byte[] soloCajon(CodePage codePage) {
        return new EscPos(codePage).inicializar().abrirCajon().bytes();
    }

    // ── Codificación de caracteres ────────────────────────────────────────

    private static final Map<Character, Byte> COMUN = new HashMap<>();
    private static final Map<Character, Byte> SOLO_850 = new HashMap<>();
    private static final Map<Character, Byte> SOLO_437 = new HashMap<>();

    private static void c(Map<Character, Byte> m, char ch, int b) { m.put(ch, (byte) b); }

    static {
        // Posiciones iguales en CP437 y CP850
        Object[][] comunes = {
                {'Ç', 0x80}, {'ü', 0x81}, {'é', 0x82}, {'â', 0x83}, {'ä', 0x84}, {'à', 0x85}, {'ç', 0x87},
                {'ê', 0x88}, {'è', 0x8A}, {'î', 0x8C}, {'ì', 0x8D}, {'Ä', 0x8E}, {'É', 0x90}, {'ô', 0x93},
                {'ö', 0x94}, {'ò', 0x95}, {'û', 0x96}, {'ù', 0x97}, {'Ö', 0x99}, {'Ü', 0x9A}, {'£', 0x9C},
                {'á', 0xA0}, {'í', 0xA1}, {'ó', 0xA2}, {'ú', 0xA3}, {'ñ', 0xA4}, {'Ñ', 0xA5}, {'ª', 0xA6},
                {'º', 0xA7}, {'¿', 0xA8}, {'½', 0xAB}, {'¼', 0xAC}, {'¡', 0xAD}, {'ß', 0xE1}, {'°', 0xF8},
                {'±', 0xF1}, {'²', 0xFD}, {'«', 0xAE}, {'»', 0xAF}};
        for (Object[] p : comunes) c(COMUN, (Character) p[0], (Integer) p[1]);
        // Solo CP850
        Object[][] solo850 = {
                {'Á', 0xB5}, {'Í', 0xD6}, {'Ó', 0xE0}, {'Ú', 0xE9}, {'À', 0xB7}, {'È', 0xD4}, {'Â', 0xB6},
                {'Ê', 0xD2}, {'Î', 0xD7}, {'Ô', 0xE2}, {'Û', 0xEA}, {'×', 0x9E}, {'¢', 0xBD}, {'¥', 0xBE}};
        for (Object[] p : solo850) c(SOLO_850, (Character) p[0], (Integer) p[1]);
        // Solo CP437
        Object[][] solo437 = { {'¢', 0x9B}, {'¥', 0x9D} };
        for (Object[] p : solo437) c(SOLO_437, (Character) p[0], (Integer) p[1]);
    }

    /**
     * Texto → bytes de la tabla de caracteres. ASCII pasa igual; los acentos
     * se mapean; lo que la tabla no tiene pierde el acento (Á en CP437 → A);
     * lo demás se imprime como "?". Los saltos de línea se respetan.
     */
    public static byte[] codificar(String texto, CodePage codePage) {
        if (texto == null) return new byte[0];
        Map<Character, Byte> propio = codePage == CodePage.CP437 ? SOLO_437 : SOLO_850;
        ByteArrayOutputStream b = new ByteArrayOutputStream(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char ch = texto.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t' || (ch >= 0x20 && ch < 0x7F)) {
                b.write(ch == '\t' ? ' ' : ch);
                continue;
            }
            Byte m = COMUN.get(ch);
            if (m == null) m = propio.get(ch);
            if (m != null) { b.write(m); continue; }
            // Sin acento: descomponer (Á → A + ´) y quedarse con la letra base
            String base = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD);
            char primera = base.isEmpty() ? '?' : base.charAt(0);
            b.write(primera >= 0x20 && primera < 0x7F ? primera : '?');
        }
        return b.toByteArray();
    }
}
