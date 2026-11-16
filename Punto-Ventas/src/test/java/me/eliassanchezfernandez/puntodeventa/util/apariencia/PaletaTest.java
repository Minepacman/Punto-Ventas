package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Cuentas de color (contraste WCAG) y paletas derivadas de cada tema y color de acento */
class PaletaTest {

    /** Acentos sugeridos y casos extremos (amarillo claro, casi blanco, negro, verde neón) */
    private static List<String> acentos() {
        List<String> l = new ArrayList<>();
        for (Apariencia.Muestra m : Apariencia.ACENTOS) l.add(m.color());
        l.addAll(List.of("#FFEB3B", "#F5F5F5", "#000000", "#39FF14", "#7F7F7F", "#00FFFF", "#FF00FF"));
        return l;
    }

    @Test
    void contrasteWcagConocido() {
        assertThat(Rgb.BLANCO.contraste(Rgb.NEGRO)).isCloseTo(21.0, within(0.01));
        assertThat(Rgb.deHex("#777777").contraste(Rgb.BLANCO)).isCloseTo(4.48, within(0.02));
        assertThat(Rgb.deHex("#2F6CAD").contraste(Rgb.BLANCO)).isGreaterThan(4.5);
        assertThat(Rgb.deHex("#abc").hex()).isEqualTo("#AABBCC");
        assertThat(Rgb.deHex("zzz")).isNull();
    }

    @Test
    void textoSobreElAcentoEsBlancoONegroElQueSeLeaMejor() {
        assertThat(Rgb.deHex("#2F6CAD").textoEncima()).isEqualTo(Rgb.BLANCO);
        assertThat(Rgb.deHex("#FFEB3B").textoEncima()).isEqualTo(Rgb.NEGRO);
        assertThat(Rgb.deHex("#F5F5F5").textoEncima()).isEqualTo(Rgb.NEGRO);
    }

    @Test
    void hslIdaYVuelta() {
        for (String a : acentos()) {
            Rgb c = Rgb.deHex(a);
            double[] h = c.hsl();
            Rgb d = Rgb.deHsl(h[0], h[1], h[2]);
            assertThat(Math.abs(c.r() - d.r()) + Math.abs(c.g() - d.g()) + Math.abs(c.b() - d.b())).as(a).isLessThanOrEqualTo(3);
        }
    }

    @Test
    void conColoresDeFabricaNoSeCambiaNada() {
        assertThat(Paleta.tokens(Apariencia.porDefecto())).isEmpty();
        assertThat(Paleta.tokens(Apariencia.porDefecto().conTema(Apariencia.Tema.AUTOMATICO))).isEmpty();
        assertThat(Paleta.tokens(Apariencia.porDefecto().conVidrio(true).conEscala(130))).isEmpty();
    }

    @Test
    void todosLosTextosCumplenAaEnCadaTemaYAcento() {
        for (Apariencia.Tema tema : List.of(Apariencia.Tema.CLARO, Apariencia.Tema.OSCURO, Apariencia.Tema.ALTO_CONTRASTE)) {
            double min = tema == Apariencia.Tema.ALTO_CONTRASTE ? Paleta.AAA : Paleta.AA;
            for (String acento : acentos()) {
                Map<String, String> t = Paleta.tokens(Apariencia.porDefecto().conTema(tema).conAcento(acento));
                if (t.isEmpty()) continue;   // azul de fábrica en claro = app.css
                String caso = tema + " " + acento;
                Rgb sup = c(t, "-pos-surface"), alt = c(t, "-pos-surface-alt");
                assertThat(c(t, "-pos-text").contraste(sup)).as(caso + " texto").isGreaterThanOrEqualTo(min);
                assertThat(c(t, "-pos-text-muted").contraste(alt)).as(caso + " texto atenuado").isGreaterThanOrEqualTo(min);
                assertThat(c(t, "-pos-text-subtle").contraste(sup)).as(caso + " pistas").isGreaterThanOrEqualTo(min);
                assertThat(c(t, "-pos-accent-text").contraste(sup)).as(caso + " texto de acento").isGreaterThanOrEqualTo(min);
                assertThat(c(t, "-pos-text-on-accent").contraste(c(t, "-pos-accent"))).as(caso + " sobre el acento")
                        .isGreaterThanOrEqualTo(tema == Apariencia.Tema.ALTO_CONTRASTE ? Paleta.AAA : 4.0);
                for (String s : List.of("danger", "success")) {
                    assertThat(Rgb.BLANCO.contraste(c(t, "-pos-" + s))).as(caso + " blanco sobre " + s).isGreaterThanOrEqualTo(min);
                }
                for (String s : List.of("danger", "success", "warning")) {
                    assertThat(c(t, "-pos-" + s + "-text").contraste(sup)).as(caso + " texto " + s).isGreaterThanOrEqualTo(min);
                    assertThat(c(t, "-pos-" + s + "-text").contraste(c(t, "-pos-" + s + "-muted"))).as(caso + " insignia " + s)
                            .isGreaterThanOrEqualTo(min);
                }
                assertThat(Rgb.BLANCO.contraste(c(t, "-pos-band-bg-flat"))).as(caso + " banda").isGreaterThanOrEqualTo(min);
                // Bloquear / Cerrar sesión / Salir sobre la banda: relleno opaco que deja leer el texto
                assertThat(Rgb.BLANCO.contraste(c(t, "-pos-band-button-bg"))).as(caso + " botón de la banda").isGreaterThanOrEqualTo(min);
                assertThat(c(t, "-pos-band-danger-text").contraste(c(t, "-pos-band-button-bg"))).as(caso + " Salir")
                        .isGreaterThanOrEqualTo(min);
            }
        }
    }

    @Test
    void elOscuroEsOscuroYElAcentoSeDistingueDelFondo() {
        Map<String, String> t = Paleta.tokens(Apariencia.porDefecto().conTema(Apariencia.Tema.OSCURO).conAcento("#1A2433"));
        assertThat(c(t, "-pos-surface").luminancia()).isLessThan(0.05);
        assertThat(c(t, "-pos-accent").contraste(c(t, "-pos-surface"))).isGreaterThanOrEqualTo(3.0);
    }

    private static Rgb c(Map<String, String> t, String k) {
        Rgb r = Rgb.deHex(t.get(k));
        assertThat(r).as(k + " = " + t.get(k)).isNotNull();
        return r;
    }
}
