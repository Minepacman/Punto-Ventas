package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deriva TODOS los colores de la interfaz (las variables {@code -pos-*} de app.css) a partir del
 * tema y del color de acento. Las variantes (pasar el ratón, presionado, tintes, texto sobre el
 * acento) se calculan, y cada texto se ajusta para cumplir el contraste WCAG AA (4.5:1; 7:1 en alto
 * contraste). La misma paleta la usa la página móvil ({@code /api/apariencia}).
 *
 * <p>Con el tema claro y el azul de fábrica no se calcula nada: valen los colores de app.css.</p>
 */
public final class Paleta {

    private Paleta() { }

    /** Contraste mínimo de texto normal (WCAG AA) */
    public static final double AA = 4.5;
    /** Contraste mínimo de texto en alto contraste (WCAG AAA) */
    public static final double AAA = 7.0;

    /** Colores neutros de cada tema */
    private record Base(Rgb bgApp, Rgb surface, Rgb surfaceAlt, Rgb surfaceInset,
                        Rgb border, Rgb borderSubtle, Rgb borderStrong,
                        Rgb text, Rgb textMuted, Rgb textSubtle,
                        Rgb rowHover, Rgb rowAlt, Rgb tooltip,
                        Rgb danger, Rgb success, Rgb warning) { }

    private static Rgb c(String hex) { return Rgb.deHex(hex); }

    private static final Base CLARO = new Base(
            c("#E6EAF0"), c("#FFFFFF"), c("#F2F4F7"), c("#E9EDF2"),
            c("#C9D1DB"), c("#E0E5EB"), c("#A6B0BD"),
            c("#1B232E"), c("#525C6A"), c("#6B7582"),
            c("#F3F6FA"), c("#F8FAFC"), c("#2B323C"),
            c("#B23A43"), c("#2E7A55"), c("#B0731C"));

    private static final Base OSCURO = new Base(
            c("#14181E"), c("#1D232B"), c("#252C35"), c("#11151A"),
            c("#3A4350"), c("#2B323C"), c("#5A6573"),
            c("#E7EBF0"), c("#B3BCC7"), c("#98A2AE"),
            c("#262E38"), c("#20262E"), c("#0B0E12"),
            c("#E0626B"), c("#3FA273"), c("#D69A3C"));

    private static final Base ALTO_CONTRASTE = new Base(
            c("#FFFFFF"), c("#FFFFFF"), c("#F0F0F0"), c("#E2E2E2"),
            c("#000000"), c("#4D4D4D"), c("#000000"),
            c("#000000"), c("#1A1A1A"), c("#2E2E2E"),
            c("#E8E8E8"), c("#F6F6F6"), c("#000000"),
            c("#A1000F"), c("#00592E"), c("#7A4A00"));

    /** Variables -pos-* a escribir sobre las de app.css (vacío = las de fábrica) */
    public static Map<String, String> tokens(Apariencia a) {
        Map<String, String> t = new LinkedHashMap<>();
        if (a.coloresDeFabrica()) return t;
        Apariencia.Tema tema = a.tema().enEscritorio();
        Base b = switch (tema) {
            case OSCURO -> OSCURO;
            case ALTO_CONTRASTE -> ALTO_CONTRASTE;
            default -> CLARO;
        };
        boolean oscuro = tema == Apariencia.Tema.OSCURO;
        boolean alto = tema == Apariencia.Tema.ALTO_CONTRASTE;
        double minTexto = alto ? AAA : AA;

        t.put("-pos-bg-app", b.bgApp.hex());
        t.put("-pos-surface", b.surface.hex());
        t.put("-pos-surface-alt", b.surfaceAlt.hex());
        t.put("-pos-surface-inset", b.surfaceInset.hex());
        t.put("-pos-border", b.border.hex());
        t.put("-pos-border-subtle", b.borderSubtle.hex());
        t.put("-pos-border-strong", b.borderStrong.hex());
        t.put("-pos-text", b.text.hex());
        t.put("-pos-text-muted", b.textMuted.conContrasteMinimo(b.surfaceAlt, minTexto).hex());
        t.put("-pos-text-subtle", b.textSubtle.conContrasteMinimo(b.surface, minTexto).hex());
        t.put("-pos-row-hover", b.rowHover.hex());
        t.put("-pos-row-alt", b.rowAlt.hex());
        t.put("-pos-tooltip-bg", b.tooltip.hex());
        t.put("-pos-shadow-1", oscuro ? "rgba(0, 0, 0, 0.30)" : alto ? "rgba(0, 0, 0, 0.00)" : "rgba(27, 35, 46, 0.07)");
        t.put("-pos-shadow-2", oscuro ? "rgba(0, 0, 0, 0.45)" : alto ? "rgba(0, 0, 0, 0.00)" : "rgba(27, 35, 46, 0.12)");

        // ── Acento ──
        Rgb acento = a.colorAcento();
        if (oscuro) {
            // En fondo oscuro un acento muy oscuro se pierde: se aclara hasta distinguirse (3:1, componente de UI)
            acento = acento.conContrasteMinimo(b.surface, 3.0);
        } else if (alto) {
            acento = acento.conContrasteMinimo(Rgb.BLANCO, AAA);
        }
        Rgb sobreAcento = alto ? (acento.contraste(Rgb.BLANCO) >= AAA ? Rgb.BLANCO : Rgb.NEGRO) : acento.textoEncima();
        boolean textoBlanco = sobreAcento.equals(Rgb.BLANCO);
        Rgb hover = textoBlanco ? acento.oscurecer(0.12) : acento.aclarar(0.12);
        Rgb presionado = textoBlanco ? acento.oscurecer(0.25) : acento.aclarar(0.25);
        Rgb arriba = textoBlanco ? acento.aclarar(0.08) : acento.aclarar(0.04);
        t.put("-pos-accent", acento.hex());
        t.put("-pos-accent-hover", hover.hex());
        t.put("-pos-accent-pressed", presionado.hex());
        t.put("-pos-accent-text", acento.conContrasteMinimo(b.surface, minTexto).hex());
        t.put("-pos-accent-muted", acento.mezclar(b.surface, oscuro ? 0.62 : alto ? 0.70 : 0.76).hex());
        t.put("-pos-accent-subtle", acento.mezclar(b.surface, oscuro ? 0.80 : alto ? 0.88 : 0.90).hex());
        t.put("-pos-accent-gradient", "linear-gradient(to bottom, " + arriba.hex() + " 0%, " + acento.hex() + " 100%)");
        t.put("-pos-accent-gradient-hover", "linear-gradient(to bottom, " + hover.aclarar(0.05).hex() + " 0%, " + hover.hex() + " 100%)");
        t.put("-pos-focus-ring", alto ? acento.rgba(0.95) : acento.rgba(oscuro ? 0.60 : 0.42));
        t.put("-pos-text-on-accent", sobreAcento.hex());

        // ── Banda oscura (cabecera, caja del total, pie del corte): tono del acento, poca saturación ──
        double[] hsl = a.colorAcento().hsl();
        Rgb bandaPlana, bandaArriba, bandaBorde;
        if (alto) {
            bandaPlana = Rgb.NEGRO;
            bandaArriba = Rgb.NEGRO;
            bandaBorde = Rgb.NEGRO;
        } else {
            double s = Math.min(hsl[1] * 0.8, 0.5);
            double l = oscuro ? 0.15 : 0.24;
            bandaPlana = Rgb.deHsl(hsl[0], s, l);
            bandaArriba = Rgb.deHsl(hsl[0], s * 0.92, l + 0.04);
            bandaBorde = Rgb.deHsl(hsl[0], s, l - (oscuro ? 0.07 : 0.06));
        }
        t.put("-pos-band-bg", "linear-gradient(to bottom, " + bandaArriba.hex() + " 0%, " + bandaPlana.hex() + " 100%)");
        t.put("-pos-band-bg-flat", bandaPlana.hex());
        t.put("-pos-band-border", bandaBorde.hex());
        t.put("-pos-band-text", "#FFFFFF");
        t.put("-pos-band-text-muted", alto ? "#FFFFFF" : "rgba(255, 255, 255, 0.78)");
        t.put("-pos-band-line", alto ? "rgba(255, 255, 255, 0.60)" : "rgba(255, 255, 255, 0.16)");
        t.put("-pos-band-fill", alto ? "rgba(255, 255, 255, 0.16)" : "rgba(255, 255, 255, 0.10)");
        t.put("-pos-band-fill-hover", alto ? "rgba(255, 255, 255, 0.30)" : "rgba(255, 255, 255, 0.18)");
        Rgb loginArriba = alto ? Rgb.NEGRO : Rgb.deHsl(hsl[0], Math.min(hsl[1] * 0.75, 0.5), oscuro ? 0.18 : 0.28);
        Rgb loginAbajo = alto ? Rgb.NEGRO : Rgb.deHsl(hsl[0], Math.min(hsl[1] * 0.75, 0.5), oscuro ? 0.09 : 0.20);
        t.put("-pos-login-bg", "linear-gradient(to bottom right, " + loginArriba.hex() + " 0%, " + loginAbajo.hex() + " 100%)");

        // ── Semánticos ──
        semantico(t, "danger", b.danger, b.surface, oscuro, alto, minTexto, true);
        semantico(t, "success", b.success, b.surface, oscuro, alto, minTexto, true);
        semantico(t, "warning", b.warning, b.surface, oscuro, alto, minTexto, false);
        return t;
    }

    private static void semantico(Map<String, String> t, String nombre, Rgb color, Rgb superficie,
                                  boolean oscuro, boolean alto, double minTexto, boolean completo) {
        // Relleno sólido (botón Cobrar, Eliminar): lleva texto blanco, así que debe llegar a AA contra blanco
        Rgb solido = completo ? color.conContrasteMinimo(Rgb.BLANCO, alto ? AAA : AA) : color;
        Rgb hover = solido.oscurecer(0.12);
        Rgb presionado = solido.oscurecer(0.25);
        Rgb muted = color.mezclar(superficie, oscuro ? 0.65 : alto ? 0.72 : 0.75);
        Rgb subtle = color.mezclar(superficie, oscuro ? 0.82 : 0.92);
        // El texto semántico se lee sobre su propio tinte (badges, avisos): se asegura contra el "muted"
        Rgb texto = color.conContrasteMinimo(superficie, minTexto).conContrasteMinimo(muted, minTexto);
        t.put("-pos-" + nombre, solido.hex());
        t.put("-pos-" + nombre + "-text", texto.hex());
        t.put("-pos-" + nombre + "-muted", muted.hex());
        t.put("-pos-" + nombre + "-subtle", subtle.hex());
        if (!completo) return;
        t.put("-pos-" + nombre + "-hover", hover.hex());
        t.put("-pos-" + nombre + "-pressed", presionado.hex());
        t.put("-pos-" + nombre + "-ring", color.rgba(0.40));
        if (nombre.equals("success")) {
            t.put("-pos-success-gradient", "linear-gradient(to bottom, " + solido.aclarar(0.07).hex() + " 0%, " + solido.hex() + " 100%)");
            t.put("-pos-success-gradient-hover", "linear-gradient(to bottom, " + hover.aclarar(0.05).hex() + " 0%, " + hover.hex() + " 100%)");
        }
    }

    /** Color para la barra del navegador del teléfono y el manifiesto (la banda oscura) */
    public static String colorBanda(Apariencia a) {
        Map<String, String> t = tokens(a);
        return t.getOrDefault("-pos-band-bg-flat", "#213A58");
    }
}
