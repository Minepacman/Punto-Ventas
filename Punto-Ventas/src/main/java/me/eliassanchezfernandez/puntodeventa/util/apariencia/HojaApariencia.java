package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hoja de estilos que se pone ENCIMA de app.css para aplicar una {@link Apariencia} en la computadora.
 *
 * <p>Va en la lista de hojas de cada escena (no en la del nodo raíz como app.css) y por eso todas sus
 * declaraciones llevan {@code !important}: en JavaFX una declaración importante gana a cualquier otra
 * no importante, venga de la hoja que venga. Contiene:</p>
 * <ol>
 *   <li>las variables {@code -pos-*} del tema y del acento ({@link Paleta});</li>
 *   <li>la letra: familia en {@code .root} y, si la escala no es 100 %, cada tamaño en px de app.css
 *       multiplicado (app.css usa px, no em, para que los tamaños no se acumulen al anidar);</li>
 *   <li>ajustes propios del tema oscuro / alto contraste que no se resuelven con variables;</li>
 *   <li>el efecto vidrio, si está encendido.</li>
 * </ol>
 * Sin JavaFX: es texto puro (se prueba solo).
 */
public final class HojaApariencia {

    private HojaApariencia() { }

    /** Tamaño base de la letra en app.css (.root) */
    public static final double LETRA_BASE_PX = 13;

    private static final Pattern REGLA = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
    private static final Pattern TAMANO_PX = Pattern.compile("(-fx-(?:tick-label-)?font-size)\\s*:\\s*([0-9.]+)px");
    private static final Pattern COMENTARIO = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** Texto CSS completo para la apariencia; {@code appCss} es el contenido de app.css (para escalar la letra) */
    public static String css(Apariencia a, String appCss) {
        return css(a, appCss, null);
    }

    /**
     * Igual, pero limitada a un nodo con la clase {@code ambito} (la vista previa de la ventana de
     * Apariencia): sus variables van en ese nodo y cada selector queda dentro de él. En ese caso se
     * escriben TODAS las variables (también las de fábrica), para no heredar las de la ventana.
     */
    public static String css(Apariencia a, String appCss, String ambito) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("/* Apariencia generada (util/apariencia/HojaApariencia) */\n");
        Map<String, String> tokens = Paleta.tokens(a);
        if (ambito != null && tokens.isEmpty() && appCss != null) tokens = tokensDeAppCss(appCss);
        if (ambito == null) {
            sb.append(cuerpo(a, appCss, tokens));
            return sb.toString();
        }
        sb.append(acotar(cuerpo(a, appCss, tokens), ambito));
        return sb.toString();
    }

    private static String cuerpo(Apariencia a, String appCss, Map<String, String> tokens) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(".root {\n");
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            sb.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append(" !important;\n");
        }
        if (a.escala() != 100) {
            sb.append("    -fx-font-size: ").append(px(LETRA_BASE_PX * a.factorEscala())).append(" !important;\n");
        }
        if (a.letra().familia() != null) {
            sb.append("    -fx-font-family: \"").append(a.letra().familia()).append("\" !important;\n");
        }
        sb.append("}\n");

        if (a.escala() != 100 && appCss != null) sb.append(letraEscalada(appCss, a.factorEscala()));
        if (a.escala() > 100) sb.append(COMPACTO);

        Apariencia.Tema tema = a.tema().enEscritorio();
        if (tema == Apariencia.Tema.OSCURO) sb.append(AJUSTES_OSCURO);
        if (tema == Apariencia.Tema.ALTO_CONTRASTE) sb.append(AJUSTES_ALTO_CONTRASTE);
        if (a.vidrio()) sb.append(vidrio(a));
        return sb.toString();
    }

    /** Variables -pos-* tal como las define app.css (su primer bloque .root) */
    static Map<String, String> tokensDeAppCss(String appCss) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        String limpio = COMENTARIO.matcher(appCss).replaceAll(" ");
        Matcher r = REGLA.matcher(limpio);
        while (r.find()) {
            if (!r.group(1).trim().equals(".root")) continue;
            Matcher v = Pattern.compile("(-pos-[a-z0-9-]+)\\s*:\\s*([^;]+);").matcher(r.group(2));
            while (v.find()) m.put(v.group(1), v.group(2).trim());
            break;
        }
        return m;
    }

    /** Mete cada regla dentro de {@code .ambito}: ".root" pasa a ser el propio nodo */
    static String acotar(String css, String ambito) {
        String limpio = COMENTARIO.matcher(css).replaceAll(" ");
        StringBuilder sb = new StringBuilder(limpio.length() + 1024);
        Matcher r = REGLA.matcher(limpio);
        while (r.find()) {
            List<String> partes = new ArrayList<>();
            for (String sel : r.group(1).split(",")) {
                String t = sel.trim().replaceAll("\\s+", " ");
                if (t.isEmpty()) continue;
                if (t.equals(".root")) partes.add("." + ambito);
                else if (t.startsWith(".root ")) partes.add("." + ambito + t.substring(5));
                else if (t.startsWith(".root.")) partes.add("." + ambito + t.substring(5));
                else partes.add("." + ambito + " " + t);
            }
            sb.append(String.join(", ", partes)).append(" {").append(r.group(2)).append("}\n");
        }
        return sb.toString();
    }

    /** La hoja como URL data: (JavaFX 17 las acepta en getStylesheets()) */
    public static String comoUrl(String css) {
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    /** Cada regla de app.css con tamaño en px, repetida con el tamaño multiplicado */
    static String letraEscalada(String appCss, double factor) {
        String limpio = COMENTARIO.matcher(appCss).replaceAll(" ");
        StringBuilder sb = new StringBuilder();
        Matcher r = REGLA.matcher(limpio);
        while (r.find()) {
            String selector = r.group(1).trim().replaceAll("\\s+", " ");
            if (selector.startsWith("@")) continue;
            // El papel del ticket (vista previa) imita la impresión: su letra no cambia con la escala
            if (selector.contains(".ticket-papel")) continue;
            List<String> decl = new ArrayList<>();
            Matcher t = TAMANO_PX.matcher(r.group(2));
            while (t.find()) {
                double tam = Double.parseDouble(t.group(2));
                // Las cifras grandes (total, cambio, reloj) ya se leen bien: no crecen y así cabe el botón Cobrar
                double f = tam >= TAMANO_SIN_ESCALAR ? Math.min(factor, 1.0) : factor;
                decl.add(t.group(1) + ": " + px(tam * f) + " !important;");
            }
            if (decl.isEmpty()) continue;
            sb.append(selector).append(" { ").append(String.join(" ", decl)).append(" }\n");
        }
        return sb.toString();
    }

    /** Desde este tamaño (px) la letra no crece con la escala */
    static final double TAMANO_SIN_ESCALAR = 18;

    /**
     * Con letra grande en 1024×768: la cabecera conserva su tamaño (si no, se esconden Cerrar sesión y
     * Salir), las pestañas crecen poco, los encabezados de las tablas se quedan en 13 px (las cifras de las
     * celdas sí crecen; así caben las 8 columnas del ticket sin «Descu…») y el panel del total se compacta
     * para que Cobrar siga a la vista.
     */
    private static final String COMPACTO = """
            .main-toolbar { -fx-font-size: 13px !important; }
            .main-toolbar .button { -fx-font-size: 13px !important; }
            .main-tabs > .tab-header-area > .headers-region > .tab > .tab-container > .tab-label { -fx-font-size: 14px !important; }
            .button-sm { -fx-padding: 3 7 3 7 !important; -fx-graphic-text-gap: 4 !important; }
            .totales-panel .text-field { -fx-padding: 4 10 4 10 !important; }
            .total-box { -fx-padding: 8 14 8 14 !important; }
            .btn-cobrar { -fx-padding: 8 12 8 12 !important; }
            .table-view > .column-header-background .column-header { -fx-font-size: 13px !important; }
            .totales-panel > .separator { -fx-padding: 0 !important; }
            """;

    private static String px(double v) {
        return String.format(Locale.ROOT, "%.1fpx", v);
    }

    /** Lo que las variables no alcanzan en oscuro: sombras más profundas y marcadores de texto */
    private static final String AJUSTES_OSCURO = """
            .text-input { -fx-prompt-text-fill: -pos-text-subtle !important; }
            .login-card, .context-menu, .dialog-pane { -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.55), 18, 0.2, 0, 6) !important; }
            .scroll-bar > .thumb { -fx-background-color: -pos-border-strong !important; }
            .chart-legend { -fx-background-color: transparent !important; }
            .chart-legend-item { -fx-text-fill: -pos-text-muted !important; }
            .axis-label, .chart-title { -fx-text-fill: -pos-text-muted !important; }
            .check-box > .box, .radio-button > .radio { -fx-background-color: -pos-surface-inset !important; -fx-border-color: -pos-border-strong !important; }
            .radio-button > .radio { -fx-background-radius: 1em !important; -fx-border-radius: 1em !important; }
            .radio-button:selected > .radio > .dot { -fx-background-color: -pos-accent !important; }
            .check-box:selected > .box { -fx-background-color: -pos-accent !important; -fx-border-color: -pos-accent !important; }
            .check-box:selected > .box > .mark { -fx-background-color: -pos-text-on-accent !important; }
            """;

    /** Alto contraste: bordes y foco más gruesos, sin sombras ni degradados suaves */
    private static final String AJUSTES_ALTO_CONTRASTE = """
            .button, .toggle-button, .text-input, .combo-box-base, .choice-box { -fx-border-color: -pos-text !important; -fx-border-width: 1.5 !important; -fx-border-radius: 6 !important; }
            .button:focused, .text-input:focused, .combo-box-base:focused, .toggle-button:focused { -fx-border-color: -pos-accent !important; -fx-border-width: 3 !important; }
            .table-view, .list-view { -fx-border-color: -pos-text !important; -fx-border-width: 1.5 !important; }
            .table-view .table-row-cell:selected, .list-view .list-cell:selected { -fx-background-color: -pos-accent !important; }
            .table-view .table-row-cell:selected > .table-cell, .list-view .list-cell:selected { -fx-text-fill: -pos-text-on-accent !important; }
            .text-input { -fx-prompt-text-fill: #595959 !important; }
            .hyperlink { -fx-underline: true !important; }
            .login-card, .context-menu, .dialog-pane, .card { -fx-effect: null !important; }
            .main-tabs > .tab-header-area > .headers-region > .tab:selected { -fx-border-color: -pos-accent !important; -fx-border-width: 0 0 4 0 !important; }
            .main-toolbar .button { -fx-border-color: -pos-band-button-border !important; }
            .main-toolbar .button-danger { -fx-border-color: -pos-band-danger-border !important; }
            """;

    /**
     * Efecto vidrio ("liquid glass"): fondo decorativo (manchas suaves del color de acento) y paneles
     * translúcidos con borde de luz y esquinas redondeadas. JavaFX no tiene desenfoque del fondo
     * (backdrop-filter): el "esmerilado" se imita con un velo claro casi opaco, que además mantiene el
     * texto legible. Barato: solo fondos y bordes pintados; la única sombra (-fx-effect) es la de las
     * tarjetas de Configuración y del login, que casi no se repintan. Nunca en tablas ni campos.
     */
    static String vidrio(Apariencia a) {
        Apariencia.Tema tema = a.tema().enEscritorio();
        boolean oscuro = tema == Apariencia.Tema.OSCURO;
        boolean alto = tema == Apariencia.Tema.ALTO_CONTRASTE;
        Rgb acento = a.colorAcento();
        double[] hsl = acento.hsl();
        Rgb mancha1 = Rgb.deHsl(hsl[0], Math.max(0.60, hsl[1]), oscuro ? 0.40 : 0.66);
        Rgb mancha2 = Rgb.deHsl(hsl[0] + 0.16, Math.max(0.55, hsl[1]), oscuro ? 0.36 : 0.72);
        Rgb mancha3 = Rgb.deHsl(hsl[0] - 0.12, Math.max(0.50, hsl[1]), oscuro ? 0.32 : 0.74);
        Map<String, String> t = Paleta.tokens(a);
        Rgb lienzo = Rgb.deHex(t.getOrDefault("-pos-bg-app", "#E6EAF0"));
        Rgb superficie = Rgb.deHex(t.getOrDefault("-pos-surface", "#FFFFFF"));
        Rgb lienzo2 = oscuro ? lienzo.oscurecer(0.25) : lienzo.mezclar(Rgb.deHsl(hsl[0], 0.35, 0.86), 0.5);
        double velo = alto ? 0.96 : oscuro ? 0.62 : 0.64;
        double veloFuerte = alto ? 0.98 : oscuro ? 0.80 : 0.82;
        String luzArriba = oscuro ? "rgba(255, 255, 255, 0.22)" : "rgba(255, 255, 255, 0.95)";
        String luzAbajo = oscuro ? "rgba(255, 255, 255, 0.05)" : "rgba(255, 255, 255, 0.35)";
        String bordeVidrio = "linear-gradient(to bottom, " + luzArriba + " 0%, " + luzAbajo + " 100%)";
        String brillo = "linear-gradient(to bottom, " + (oscuro ? "rgba(255, 255, 255, 0.07)" : "rgba(255, 255, 255, 0.55)")
                + " 0%, rgba(255, 255, 255, 0.0) 45%)";
        String panel = superficie.rgba(velo);
        String panelFuerte = superficie.rgba(veloFuerte);
        String sombra = oscuro ? "rgba(0, 0, 0, 0.45)" : "rgba(27, 35, 46, 0.16)";
        Rgb banda = Rgb.deHex(t.getOrDefault("-pos-band-bg-flat", "#213A58"));

        StringBuilder sb = new StringBuilder("/* Vidrio */\n");
        // En JavaFX la primera capa es la de abajo: lienzo, luego las manchas
        sb.append(".root { -fx-background-color: ")
                .append("linear-gradient(to bottom right, ").append(lienzo.hex()).append(" 0%, ").append(lienzo2.hex()).append(" 100%), ")
                .append("radial-gradient(center 12% 8%, radius 55%, ").append(mancha1.rgba(oscuro ? 0.75 : 0.95)).append(" 0%, ").append(mancha1.rgba(0)).append(" 100%), ")
                .append("radial-gradient(center 92% 18%, radius 45%, ").append(mancha2.rgba(oscuro ? 0.65 : 0.85)).append(" 0%, ").append(mancha2.rgba(0)).append(" 100%), ")
                .append("radial-gradient(center 70% 100%, radius 60%, ").append(mancha3.rgba(oscuro ? 0.60 : 0.80)).append(" 0%, ").append(mancha3.rgba(0)).append(" 100%) !important; }\n");
        // Lienzos transparentes: dejan ver el fondo decorativo
        sb.append(".tab-pane > .tab-content-area, .scroll-pane, .scroll-pane > .viewport, .config-page, .page, .split-pane, .form-scroll, .login-background ")
                .append("{ -fx-background-color: transparent !important; }\n");
        sb.append(".login-root { -fx-background-color: -pos-login-bg !important; }\n");
        // Paneles de vidrio: velo + brillo superior; borde de luz; esquinas redondeadas
        String paneles = ".card, .card-flat, .config-card, .form-panel, .section-toolbar, .totales-panel, .sugerencias-panel, .callout";
        sb.append(paneles).append(" { -fx-background-color: ").append(panel).append(", ").append(brillo)
                .append(" !important; -fx-background-insets: 0 !important; -fx-background-radius: 14 !important; ")
                .append("-fx-border-color: ").append(bordeVidrio).append(" !important; -fx-border-width: 1 !important; -fx-border-radius: 14 !important; }\n");
        sb.append(".config-card, .login-card { -fx-effect: dropshadow(three-pass-box, ").append(sombra).append(", 18, 0, 0, 6) !important; }\n");
        sb.append(".login-card { -fx-background-color: ").append(panelFuerte).append(", ").append(brillo)
                .append(" !important; -fx-background-radius: 18 !important; -fx-border-color: ").append(bordeVidrio)
                .append(" !important; -fx-border-radius: 18 !important; }\n");
        // Botones de Configuración y pestañas: también de vidrio
        sb.append(".boton-opcion { -color-button-bg: ").append(superficie.rgba(oscuro ? 0.35 : 0.55))
                .append(" !important; -color-button-bg-hover: ").append(superficie.rgba(oscuro ? 0.55 : 0.85)).append(" !important; }\n");
        sb.append(".tab-pane > .tab-header-area > .tab-header-background { -fx-background-color: ").append(superficie.rgba(oscuro ? 0.35 : 0.45)).append(" !important; }\n");
        sb.append(".status-bar { -fx-background-color: ").append(superficie.rgba(oscuro ? 0.45 : 0.55)).append(" !important; }\n");
        // Tablas y listas: casi opacas (se leen igual), solo esquinas y borde de luz
        sb.append(".table-view, .list-view, .tree-table-view { -fx-background-color: ").append(panelFuerte)
                .append(" !important; -fx-background-radius: 10 !important; -fx-border-color: ").append(bordeVidrio)
                .append(" !important; -fx-border-radius: 10 !important; }\n");
        // Banda: el color de la banda un poco translúcido, con línea de luz arriba
        sb.append(".main-toolbar, .total-box, .corte-footer { -fx-background-color: ").append(banda.rgba(alto ? 1.0 : 0.86))
                .append(", linear-gradient(to bottom, rgba(255, 255, 255, 0.16) 0%, rgba(255, 255, 255, 0.0) 60%) !important; }\n");
        sb.append(".total-box { -fx-background-radius: 14 !important; -fx-border-color: rgba(255, 255, 255, 0.30) !important; -fx-border-radius: 14 !important; }\n");
        return sb.toString();
    }
}
