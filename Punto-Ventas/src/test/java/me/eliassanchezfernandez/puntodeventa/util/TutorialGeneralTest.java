package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contenido del recorrido general: hay un paso por cada pestaña real de
 * main.fxml, los atajos salen del catálogo Atajo y el filtro por permisos
 * respeta el orden.
 */
class TutorialGeneralTest {

    private static final Path MAIN_FXML = Path.of("src/main/resources/fxml/main.fxml");
    private static final Pattern TAB_ID = Pattern.compile("<Tab\\s+fx:id=\"(\\w+)\"");

    @Test
    void hayUnPasoPorCadaPestanaDeMainFxmlYNingunoDeMas() throws IOException {
        Set<String> enFxml = new HashSet<>();
        Matcher m = TAB_ID.matcher(Files.readString(MAIN_FXML));
        while (m.find()) enFxml.add(m.group(1));
        assertThat(enFxml).isNotEmpty();

        Set<String> enTutorial = new HashSet<>();
        for (TutorialGeneral.Paso p : TutorialGeneral.pasos()) {
            if (p.tabId() != null) enTutorial.add(p.tabId());
        }
        assertThat(enTutorial).containsExactlyInAnyOrderElementsOf(enFxml);
    }

    @Test
    void empiezaConBienvenidaYTerminaConCierreSinPestana() {
        List<TutorialGeneral.Paso> pasos = TutorialGeneral.pasos();
        assertThat(pasos.get(0).tabId()).isNull();
        assertThat(pasos.get(pasos.size() - 1).tabId()).isNull();
        assertThat(pasos).allSatisfy(p -> {
            assertThat(p.titulo()).isNotBlank();
            assertThat(p.texto()).isNotBlank();
            assertThat(p.atajos()).isNotNull();
        });
    }

    @Test
    void losAtajosSalenDelCatalogoYNoSeEscribenAMano() {
        TutorialGeneral.Paso ventas = TutorialGeneral.pasos().stream()
                .filter(p -> "tabVentas".equals(p.tabId())).findFirst().orElseThrow();
        assertThat(ventas.atajos()).contains(Atajo.IR_VENTAS, Atajo.COBRAR, Atajo.BUSCAR_PRODUCTO);

        String windows = ventas.textoAtajos(false);
        assertThat(windows).contains(Atajo.COBRAR.etiquetaTeclas(false))
                .contains(Atajo.COBRAR.getDescripcion())
                .contains("Ctrl+");
        String mac = ventas.textoAtajos(true);
        assertThat(mac).contains("Cmd+").doesNotContain("Ctrl+");

        TutorialGeneral.Paso bienvenida = TutorialGeneral.pasos().get(0);
        assertThat(bienvenida.textoAtajos(false)).isEmpty();
    }

    @Test
    void elFiltroPorPestanasVisiblesConservaElOrdenYLosPasosGenerales() {
        List<TutorialGeneral.Paso> cajero = TutorialGeneral.pasosPara(List.of("tabVentas", "tabProductos", "tabInventario"));

        assertThat(cajero).extracting(TutorialGeneral.Paso::tabId)
                .containsExactly(null, "tabVentas", "tabProductos", "tabInventario", null, null);
        assertThat(cajero).extracting(TutorialGeneral.Paso::titulo)
                .doesNotContain("Corte", "Configuración", "Estadísticas");

        List<TutorialGeneral.Paso> admin = TutorialGeneral.pasosPara(
                List.of("tabVentas", "tabProductos", "tabInventario", "tabEstadisticas", "tabCorte", "tabConfig"));
        assertThat(admin).hasSameSizeAs(TutorialGeneral.pasos());
    }
}
