package me.eliassanchezfernandez.puntodeventa.util;

import javafx.scene.input.KeyCode;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Catálogo de atajos: sin choques, resolución por tecla y textos para Windows/Mac. Sin toolkit de JavaFX. */
class AtajoTest {

    @Test
    void ningunaTeclaEstaRepetidaEntreAtajosQueConviven() {
        // Globales y de Ventas se registran en la misma escena: ninguna combinación puede repetirse
        Map<Atajo.Tecla, Atajo> vistas = new HashMap<>();
        List<String> choques = new ArrayList<>();
        for (Atajo a : Atajo.values()) {
            for (Atajo.Tecla t : a.teclas()) {
                Atajo previo = vistas.put(t, a);
                if (previo != null) choques.add(t.etiqueta(false) + ": " + previo + " y " + a);
            }
        }
        assertThat(choques).isEmpty();
    }

    @Test
    void resuelveTeclasFyCombinacionesConCtrlOCmd() {
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.F12, false, false, false)).contains(Atajo.COBRAR);
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.ENTER, true, false, false)).contains(Atajo.COBRAR);
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.F11, false, false, false)).contains(Atajo.PONER_EN_ESPERA);
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.F11, false, true, false)).contains(Atajo.RECUPERAR_ESPERA);
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.B, true, false, false)).contains(Atajo.BUSCAR_PRODUCTO);
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.C, true, true, false)).contains(Atajo.CANCELAR_TICKET);
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.DELETE, true, false, false)).contains(Atajo.CANCELAR_TICKET);
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.DELETE, false, false, false)).contains(Atajo.QUITAR_RENGLON);
        assertThat(Atajo.resolver(Atajo.Ambito.GLOBAL, KeyCode.F1, false, false, false)).contains(Atajo.IR_VENTAS);
        assertThat(Atajo.resolver(Atajo.Ambito.GLOBAL, KeyCode.DIGIT3, true, false, false)).contains(Atajo.IR_PRODUCTOS);
        assertThat(Atajo.resolver(Atajo.Ambito.GLOBAL, KeyCode.F10, false, false, false)).contains(Atajo.AYUDA_ATAJOS);
    }

    @Test
    void noSeDisparaConModificadoresDeMasNiEnOtroAmbito() {
        // Enter solo (sin Ctrl) no es Cobrar: es el Enter normal de los campos
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.ENTER, false, false, false)).isEmpty();
        // F12 con Shift o Alt no es Cobrar
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.F12, false, true, false)).isEmpty();
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.F12, false, false, true)).isEmpty();
        // Ctrl+C (copiar) no es Cancelar ticket: hace falta Shift
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.C, true, false, false)).isEmpty();
        // Las teclas de Ventas no existen en el ámbito global y viceversa
        assertThat(Atajo.resolver(Atajo.Ambito.GLOBAL, KeyCode.F12, false, false, false)).isEmpty();
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, KeyCode.F1, false, false, false)).isEmpty();
        assertThat(Atajo.resolver(Atajo.Ambito.VENTAS, null, false, false, false)).isEqualTo(Optional.empty());
    }

    @Test
    void lasTeclasQueCompitenConLaEscrituraSonSoloSuprYMasMenos() {
        assertThat(Atajo.values()).filteredOn(Atajo::compiteConEscritura)
                .containsExactlyInAnyOrder(Atajo.QUITAR_RENGLON, Atajo.MAS_UNO, Atajo.MENOS_UNO);
        // Y ninguna de ellas lleva Ctrl/Cmd (si lo llevaran serían aceleradores normales)
        for (Atajo a : Atajo.values()) {
            if (a.compiteConEscritura()) assertThat(a.teclas()).allMatch(t -> !t.atajo());
        }
    }

    @Test
    void etiquetasParaWindowsYMac() {
        assertThat(Atajo.COBRAR.etiquetaCorta(false)).isEqualTo("F12");
        assertThat(Atajo.COBRAR.etiquetaTeclas(false)).isEqualTo("F12  ·  Ctrl+Enter");
        assertThat(Atajo.COBRAR.etiquetaTeclas(true)).isEqualTo("F12  ·  Cmd+Enter");
        assertThat(Atajo.RECUPERAR_ESPERA.etiquetaCorta(false)).isEqualTo("Mayús+F11");
        assertThat(Atajo.RECUPERAR_ESPERA.etiquetaCorta(true)).isEqualTo("Shift+F11");
        assertThat(Atajo.CANCELAR_TICKET.etiquetaTeclas(false)).isEqualTo("Ctrl+Mayús+C  ·  Ctrl+Supr");
        assertThat(Atajo.QUITAR_RENGLON.etiquetaTeclas(false)).isEqualTo("Supr  ·  Retroceso");
        assertThat(Atajo.MAS_UNO.etiquetaCorta(false)).isEqualTo("+");
        assertThat(Atajo.IR_PRODUCTOS.etiquetaTeclas(true)).isEqualTo("F3  ·  Cmd+3");
    }

    @Test
    void losAtajosConPermisoCoincidenConLoQueExigeElServicio() {
        assertThat(Atajo.DESCUENTO_MANUAL.getPermiso()).isEqualTo(Permiso.DESCUENTO_MANUAL);
        assertThat(Atajo.MAYOREO.getPermiso()).isEqualTo(Permiso.APLICAR_MAYOREO);
        assertThat(Atajo.ABRIR_CAJON.getPermiso()).isEqualTo(Permiso.ABRIR_CAJON);
        assertThat(Atajo.IR_ESTADISTICAS.getPermiso()).isEqualTo(Permiso.VER_HISTORIAL);
        assertThat(Atajo.COBRAR.getPermiso()).isNull();
        assertThat(Atajo.BUSCAR_PRODUCTO.getPermiso()).isNull();
    }

    @Test
    void lasCombinacionesParaLaEscenaConservanLosModificadores() {
        assertThat(Atajo.COBRAR.getAlternativa().aCombinacion().getName()).isEqualTo("Shortcut+Enter");
        assertThat(Atajo.RECUPERAR_ESPERA.getPrincipal().aCombinacion().getName()).isEqualTo("Shift+F11");
        assertThat(Atajo.CANCELAR_TICKET.getPrincipal().aCombinacion().getName()).isEqualTo("Shift+Shortcut+C");
        assertThat(Atajo.IR_VENTAS.getPrincipal().aCombinacion().getName()).isEqualTo("F1");
        assertThat(Atajo.de(Atajo.Ambito.GLOBAL)).contains(Atajo.IR_VENTAS, Atajo.AYUDA_ATAJOS).doesNotContain(Atajo.COBRAR);
    }
}
