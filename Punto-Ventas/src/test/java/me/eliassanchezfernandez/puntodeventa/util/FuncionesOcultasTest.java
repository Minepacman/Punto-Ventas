package me.eliassanchezfernandez.puntodeventa.util;

import javafx.scene.input.KeyCode;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Fiado y Clientes ocultos con {@link FuncionesOcultas#FIADO_HABILITADO} = false: ni atajo, ni paso del
 * tutorial, ni permisos en el editor. (La pestaña la quita MainController y la API móvil responde 404:
 * ver {@code ServidorMovilFiadoTest}.) Y la pestaña Promociones ocupa F2, con las teclas F en el orden
 * de las pestañas.
 */
class FuncionesOcultasTest {

    @Test
    void conElFiadoOcultoNoHayAtajoNiPasoDelTutorialNiPermisosVisibles() {
        assumeFalse(FuncionesOcultas.FIADO_HABILITADO);

        assertThat(Atajo.disponibles()).doesNotContain(Atajo.IR_CLIENTES).contains(Atajo.IR_PROMOCIONES);
        assertThat(Atajo.de(Atajo.Ambito.GLOBAL)).doesNotContain(Atajo.IR_CLIENTES);
        assertThat(Atajo.resolver(Atajo.Ambito.GLOBAL, KeyCode.F2, false, true, false)).isEmpty();
        assertThat(Atajo.resolver(Atajo.Ambito.GLOBAL, KeyCode.DIGIT6, true, false, false)).isEmpty();
        for (Atajo a : Atajo.disponibles()) {
            assertThat(a.getDescripcion() + " " + a.getNota()).as(a.name()).doesNotContainIgnoringCase("fiado");
        }

        for (TutorialGeneral.Paso p : TutorialGeneral.pasos()) {
            assertThat(p.tabId()).isNotEqualTo("tabClientes");
            assertThat(p.titulo() + " " + p.texto()).as(p.titulo())
                    .doesNotContainIgnoringCase("fiado").doesNotContain("A cuenta");
        }
        assertThat(TutorialGeneral.pasosPara(List.of("tabVentas", "tabClientes", "tabProductos")))
                .extracting(TutorialGeneral.Paso::tabId).doesNotContain("tabClientes");

        assertThat(FuncionesOcultas.permisoVisible(Permiso.VENDER_A_CREDITO)).isFalse();
        assertThat(FuncionesOcultas.permisoVisible(Permiso.AJUSTAR_CREDITO)).isFalse();
        assertThat(FuncionesOcultas.permisoVisible(Permiso.DESCUENTO_MANUAL)).isTrue();
    }

    @Test
    void promocionesEsLaPestanaF2YLasTeclasFSiguenElOrdenDeLasPestanas() throws Exception {
        String main;
        try (InputStream in = FuncionesOcultasTest.class.getResourceAsStream("/fxml/main.fxml")) {
            main = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Texto de las pestañas en el orden del FXML
        List<String> pestanas = new ArrayList<>();
        Matcher m = Pattern.compile("<Tab fx:id=\"(\\w+)\" text=\"([^\"]*)\"").matcher(main);
        while (m.find()) pestanas.add(m.group(1) + "=" + m.group(2));
        assertThat(pestanas).startsWith("tabVentas=F1 · Ventas", "tabPromociones=F2 · Promociones",
                "tabProductos=F3 · Productos", "tabInventario=F4 · Inventario");
        assertThat(Atajo.IR_PROMOCIONES.etiquetaTeclas(false)).isEqualTo("F2  ·  Ctrl+2");
        assertThat(Atajo.IR_PROMOCIONES.getDescripcion()).isEqualTo("Ir a Promociones");
        // La pestaña Clientes no lleva tecla F en su texto (F2 es de Promociones)
        assertThat(pestanas).anyMatch(p -> p.equals("tabClientes=Clientes"));
        // Productos ya no abre Promociones: hay un solo lugar
        try (InputStream in = FuncionesOcultasTest.class.getResourceAsStream("/fxml/productos.fxml")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).doesNotContain("onPromociones");
        }
    }
}
