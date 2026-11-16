package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import me.eliassanchezfernandez.puntodeventa.util.PaquetesIconos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Valores, JSON tolerante y paquetes de iconos */
class AparienciaTest {

    @Test
    void jsonIdaYVuelta() {
        Apariencia a = Apariencia.porDefecto().conTema(Apariencia.Tema.ALTO_CONTRASTE).conAcento("#b5342f")
                .conPaquete("phosphor", "duotono").conGrosor(Apariencia.Grosor.GRUESO).conIconosGrandes(true)
                .conLetra(Apariencia.Letra.NUNITO).conEscala(115).conVidrio(true);
        Apariencia b = Apariencia.desdeJson(a.aJson());
        assertThat(b).isEqualTo(a);
        assertThat(b.acento()).isEqualTo("#B5342F");
    }

    @Test
    void jsonDanadoOViejoCaeEnLoDeFabrica() {
        assertThat(Apariencia.desdeJson(null)).isEqualTo(Apariencia.porDefecto());
        assertThat(Apariencia.desdeJson("no es json")).isEqualTo(Apariencia.porDefecto());
        Apariencia a = Apariencia.desdeJson("{\"tema\":\"morado\",\"acento\":\"rojo\",\"escala\":1000,\"letra\":\"comic\","
                + "\"paqueteIconos\":\"../../etc\",\"vidrio\":true}");
        assertThat(a.tema()).isEqualTo(Apariencia.Tema.CLARO);
        assertThat(a.acento()).isEqualTo(Apariencia.ACENTO_DE_FABRICA);
        assertThat(a.escala()).isEqualTo(130);
        assertThat(a.letra()).isEqualTo(Apariencia.Letra.SISTEMA);
        assertThat(a.paqueteIconos()).isEqualTo("lucide");
        assertThat(a.vidrio()).isTrue();
    }

    @Test
    void automaticoSeVeClaroEnLaComputadora() {
        assertThat(Apariencia.Tema.AUTOMATICO.enEscritorio()).isEqualTo(Apariencia.Tema.CLARO);
        assertThat(Apariencia.porDefecto().conTema(Apariencia.Tema.AUTOMATICO).coloresDeFabrica()).isTrue();
    }

    @Test
    void cadaPaqueteTieneEstilosYDibujaTodosLosIconos() {
        assertThat(PaquetesIconos.disponibles()).extracting(PaquetesIconos.Paquete::id)
                .containsExactly("lucide", "tabler", "phosphor", "heroicons", "material", "iconoir");
        for (PaquetesIconos.Paquete p : PaquetesIconos.disponibles()) {
            for (PaquetesIconos.Estilo e : p.estilos()) {
                for (String n : me.eliassanchezfernandez.puntodeventa.util.Iconos.nombres()) {
                    assertThat(PaquetesIconos.dibujo(p.id(), e.id(), n).rutas()).as(p.id() + "/" + e.id() + "/" + n).isNotEmpty();
                }
            }
        }
        // Un nombre que no existe cae en Lucide (vacío), nunca falla
        assertThat(PaquetesIconos.dibujo("tabler", "contorno", "noExiste").rutas()).isEmpty();
        assertThat(PaquetesIconos.paquete("../x")).isNull();
    }

    @Test
    void prefijosDeLasRutas() {
        var r = PaquetesIconos.ruta("~!#M0 0h1", true);
        assertThat(r.translucida()).isTrue();
        assertThat(r.parImpar()).isTrue();
        assertThat(r.rellenar()).isTrue();
        assertThat(r.d()).isEqualTo("M0 0h1");
        assertThat(PaquetesIconos.ruta("^M0 0h1", false).trazar()).isTrue();
        assertThat(PaquetesIconos.ruta("M0 0h1", false).rellenar()).isTrue();
        assertThat(PaquetesIconos.ruta("M0 0h1", true).trazar()).isTrue();
    }

    @Test
    void lucideDuotonoRellenaSoloFigurasCerradas() {
        var d = PaquetesIconos.dibujo("lucide", "duotono", "ajustes");   // engrane (Z) + círculo (dos arcos)
        assertThat(d.rutas().stream().filter(PaquetesIconos.Ruta::translucida).count()).isEqualTo(2);
        var linea = PaquetesIconos.dibujo("lucide", "duotono", "izquierda");
        assertThat(linea.rutas()).noneMatch(PaquetesIconos.Ruta::translucida);
    }
}
