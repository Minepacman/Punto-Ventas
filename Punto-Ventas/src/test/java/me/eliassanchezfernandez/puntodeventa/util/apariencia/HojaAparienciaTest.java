package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import javafx.css.CssParser;
import javafx.css.Stylesheet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hoja de apariencia: se analiza sin errores en todas las combinaciones, escala la letra y respeta lo de fábrica */
class HojaAparienciaTest {

    private static String appCss() throws Exception {
        return Files.readString(Path.of("src/main/resources/css/app.css"));
    }

    private static List<Apariencia> combinaciones() {
        List<Apariencia> l = new ArrayList<>();
        for (Apariencia.Tema t : Apariencia.Tema.values()) {
            for (String acento : List.of(Apariencia.ACENTO_DE_FABRICA, "#FFEB3B", "#6B4BA8")) {
                for (boolean vidrio : new boolean[]{false, true}) {
                    for (int escala : Apariencia.ESCALAS) {
                        l.add(Apariencia.porDefecto().conTema(t).conAcento(acento).conVidrio(vidrio).conEscala(escala)
                                .conLetra(escala == 115 ? Apariencia.Letra.ATKINSON : Apariencia.Letra.SISTEMA));
                    }
                }
            }
        }
        return l;
    }

    @Test
    void todasLasHojasSeAnalizanSinErrores() throws Exception {
        String app = appCss();
        var errores = CssParser.errorsProperty();
        for (Apariencia a : combinaciones()) {
            for (String ambito : new String[]{null, "vista-previa"}) {
                errores.clear();
                String css = HojaApariencia.css(a, app, ambito);
                Stylesheet hoja = new CssParser().parse(css);
                assertThat(errores).as(a + " ámbito " + ambito + "\n" + css).isEmpty();
                if (!a.esPorDefecto() || ambito != null) assertThat(hoja.getRules()).as(a.toString()).isNotEmpty();
            }
        }
    }

    @Test
    void lasVariablesVanConImportantParaGanarleAAppCss() throws Exception {
        String css = HojaApariencia.css(Apariencia.porDefecto().conTema(Apariencia.Tema.OSCURO), appCss());
        Map<String, String> t = Paleta.tokens(Apariencia.porDefecto().conTema(Apariencia.Tema.OSCURO));
        assertThat(css).contains("-pos-surface: " + t.get("-pos-surface") + " !important;");
        assertThat(css).contains("-pos-text-on-accent:");
        // NordDark + ajustes de oscuro
        assertThat(css).contains("-fx-prompt-text-fill");
    }

    @Test
    void laLetraEscalaLosTamanosDeAppCssPeroNoLasCifrasGrandes() throws Exception {
        String css = HojaApariencia.css(Apariencia.porDefecto().conEscala(130), appCss());
        assertThat(css).contains("-fx-font-size: 16.9px !important;");          // 13 px × 1.3 en .root
        assertThat(css).contains(".hint { -fx-font-size: 14.3px !important; }"); // 11 px × 1.3
        assertThat(css).doesNotContain("46.8px");                                // el TOTAL (36 px) no crece
        assertThat(css).contains(".main-toolbar { -fx-font-size: 13px !important; }");
        String chica = HojaApariencia.css(Apariencia.porDefecto().conEscala(90), appCss());
        assertThat(chica).contains(".hint { -fx-font-size: 9.9px !important; }").doesNotContain(".main-toolbar { -fx-font-size: 13px");
    }

    @Test
    void laLetraIncluidaVaEnLaRaiz() throws Exception {
        String css = HojaApariencia.css(Apariencia.porDefecto().conLetra(Apariencia.Letra.INTER), appCss());
        assertThat(css).contains("-fx-font-family: \"Inter\" !important;");
        for (Apariencia.Letra l : Apariencia.Letra.values()) {
            if (l.archivo() == null) continue;
            for (String peso : List.of("Regular", "Bold")) {
                assertThat(HojaAparienciaTest.class.getResource("/fuentes/" + l.archivo() + "-" + peso + ".ttf"))
                        .as(l + " " + peso).isNotNull();
            }
        }
    }

    @Test
    void conAmbitoTodoQuedaDentroDeLaVistaPrevia() throws Exception {
        String css = HojaApariencia.css(Apariencia.porDefecto().conVidrio(true).conEscala(115), appCss(), "vista-previa");
        // De fábrica en claro igual se escriben las variables (para no heredar las de la ventana)
        assertThat(css).contains(".vista-previa {").contains("-pos-accent: #2F6CAD !important;");
        for (String linea : css.split("\n")) {
            if (linea.isBlank() || linea.startsWith("/*")) continue;
            String selectores = linea.contains("{") ? linea.substring(0, linea.indexOf('{')) : null;
            if (selectores == null || selectores.isBlank()) continue;
            for (String sel : selectores.split(",")) assertThat(sel.trim()).as(linea).startsWith(".vista-previa");
        }
    }

    @Test
    void elVidrioPintaElFondoDeManchasYPanelesTranslucidos() throws Exception {
        String css = HojaApariencia.css(Apariencia.porDefecto().conVidrio(true), appCss());
        assertThat(css).contains("radial-gradient(").contains(".config-card").contains("rgba(255, 255, 255, ");
        assertThat(HojaApariencia.css(Apariencia.porDefecto().conVidrio(false).conTema(Apariencia.Tema.OSCURO), appCss()))
                .doesNotContain("radial-gradient(");
    }

    @Test
    void comoUrlEsUnDataUriDeCss() {
        String url = HojaApariencia.comoUrl(".root { -pos-text: #000000 !important; }");
        assertThat(url).startsWith("data:text/css;base64,");
        assertThat(new String(Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1)), StandardCharsets.UTF_8))
                .contains("-pos-text");
    }

    @Test
    void lasVariablesDeAppCssSeLeen() throws Exception {
        Map<String, String> t = HojaApariencia.tokensDeAppCss(appCss());
        assertThat(t).containsEntry("-pos-accent", "#2F6CAD").containsKey("-pos-login-bg").containsKey("-pos-text-on-solid");
    }

    @Test
    void elVidrioLigeroDejaLasTablasOpacasYQuitaLaSombraDeLasTarjetas() {
        Apariencia a = Apariencia.porDefecto().conVidrio(true);
        String completo = HojaApariencia.vidrio(a, false);
        String ligero = HojaApariencia.vidrio(a, true);
        String tablaCompleta = completo.lines().filter(l -> l.startsWith(".table-view")).findFirst().orElseThrow();
        String tablaLigera = ligero.lines().filter(l -> l.startsWith(".table-view")).findFirst().orElseThrow();
        assertThat(tablaCompleta).contains("rgba(");
        assertThat(tablaLigera).doesNotContain("rgba(").contains("#");
        assertThat(completo).contains(".config-card, .login-card { -fx-effect");
        assertThat(ligero).doesNotContain(".config-card, .login-card { -fx-effect").contains(".login-card { -fx-effect");
        // Lo demás del vidrio (fondo decorativo, paneles) no cambia
        assertThat(ligero).contains("radial-gradient").contains(".card, .card-flat");
    }
}
