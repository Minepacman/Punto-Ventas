package me.eliassanchezfernandez.puntodeventa.impresion;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion.Alineacion;
import me.eliassanchezfernandez.puntodeventa.impresion.EscPos.CodePage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bytes ESC/POS: inicio, alineación, negrita, tamaño, corte, cajón y acentos en la tabla de caracteres. */
class EscPosTest {

    private static byte[] b(int... valores) {
        byte[] r = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) r[i] = (byte) valores[i];
        return r;
    }

    /** Posición de la subsecuencia dentro del arreglo (-1 si no está) */
    private static int indice(byte[] datos, byte[] buscado) {
        for (int i = 0; i + buscado.length <= datos.length; i++) {
            boolean igual = true;
            for (int j = 0; j < buscado.length && igual; j++) igual = datos[i + j] == buscado[j];
            if (igual) return i;
        }
        return -1;
    }

    @Test
    void inicializaLaImpresoraYElegiLaTablaDeCaracteres() {
        byte[] cp850 = new EscPos(CodePage.CP850).inicializar().bytes();
        byte[] cp437 = new EscPos(CodePage.CP437).inicializar().bytes();
        assertThat(cp850).containsExactly(b(0x1B, '@', 0x1B, 't', 2));
        assertThat(cp437).containsExactly(b(0x1B, '@', 0x1B, 't', 0));
    }

    @Test
    void alineaIzquierdaCentroYDerecha() {
        assertThat(new EscPos(null).alinear(Alineacion.IZQUIERDA).bytes()).containsExactly(b(0x1B, 'a', 0));
        assertThat(new EscPos(null).alinear(Alineacion.CENTRO).bytes()).containsExactly(b(0x1B, 'a', 1));
        assertThat(new EscPos(null).alinear(Alineacion.DERECHA).bytes()).containsExactly(b(0x1B, 'a', 2));
    }

    @Test
    void negritaYTamanoDoble() {
        assertThat(new EscPos(null).negrita(true).bytes()).containsExactly(b(0x1B, 'E', 1));
        assertThat(new EscPos(null).negrita(false).bytes()).containsExactly(b(0x1B, 'E', 0));
        assertThat(new EscPos(null).grande(true).bytes()).containsExactly(b(0x1D, '!', 0x11));
        assertThat(new EscPos(null).grande(false).bytes()).containsExactly(b(0x1D, '!', 0));
    }

    @Test
    void elCorteAvanzaElPapelYCortaAlFinal() {
        byte[] corte = new EscPos(null).cortar().bytes();
        assertThat(corte).containsExactly(b(0x1B, 'd', 4, 0x1D, 'V', 1));
    }

    @Test
    void elPulsoDelCajonEsEscP0_25_250() {
        assertThat(new EscPos(null).abrirCajon().bytes()).containsExactly(b(0x1B, 'p', 0, 25, 250));
        assertThat(EscPos.ABRIR_CAJON).containsExactly(b(0x1B, 'p', 0, 25, 250));
    }

    @Test
    void renderizarUnDocumentoAplicaFormatoPorLineaYTerminaCortando() {
        DocumentoImpresion doc = new DocumentoImpresion()
                .titulo("Mi Tienda")
                .centrada("Gracias")
                .linea("Total 10");
        byte[] datos = EscPos.renderizar(doc, CodePage.CP850, false, true);

        assertThat(datos).startsWith(b(0x1B, '@', 0x1B, 't', 2));
        assertThat(indice(datos, b(0x1B, 'a', 1))).isPositive();                 // centrado
        assertThat(indice(datos, b(0x1D, '!', 0x11))).isPositive();              // título doble
        assertThat(indice(datos, b(0x1B, 'E', 1))).isPositive();                 // negrita
        assertThat(indice(datos, "Mi Tienda\n".getBytes())).isPositive();
        assertThat(indice(datos, b(0x1B, 'a', 0))).isPositive();                 // vuelve a la izquierda
        // Termina con el avance y el corte; sin pedirlo, no hay pulso al cajón
        assertThat(datos).endsWith(b(0x1B, 'd', 4, 0x1D, 'V', 1));
        assertThat(indice(datos, EscPos.ABRIR_CAJON)).isEqualTo(-1);
    }

    @Test
    void conCajonElPulsoVaDespuesDelCorte() {
        DocumentoImpresion doc = new DocumentoImpresion().linea("x");
        byte[] datos = EscPos.renderizar(doc, CodePage.CP850, true, true);
        assertThat(datos).endsWith(b(0x1D, 'V', 1, 0x1B, 'p', 0, 25, 250));
        assertThat(EscPos.soloCajon(CodePage.CP850)).endsWith(EscPos.ABRIR_CAJON);
    }

    @Test
    void losAcentosYLaEnieSalenEnLaTablaCP850() {
        assertThat(EscPos.codificar("áéíóú ñÑ ü ¿¡", CodePage.CP850))
                .containsExactly(b(0xA0, 0x82, 0xA1, 0xA2, 0xA3, ' ', 0xA4, 0xA5, ' ', 0x81, ' ', 0xA8, 0xAD));
        assertThat(EscPos.codificar("ÁÉÍÓÚ", CodePage.CP850)).containsExactly(b(0xB5, 0x90, 0xD6, 0xE0, 0xE9));
    }

    @Test
    void enCP437LasMayusculasAcentuadasPierdenElAcentoPeroLaEnieSigue() {
        assertThat(EscPos.codificar("ÁÍÓÚ", CodePage.CP437)).containsExactly('A', 'I', 'O', 'U');
        assertThat(EscPos.codificar("É ñ Ñ", CodePage.CP437)).containsExactly(b(0x90, ' ', 0xA4, ' ', 0xA5));
    }

    @Test
    void loQueNoExisteEnLaTablaSeImprimeComoSignoDeInterrogacion() {
        assertThat(EscPos.codificar("a€b", CodePage.CP850)).containsExactly('a', '?', 'b');
        assertThat(EscPos.codificar(null, CodePage.CP850)).isEmpty();
    }

    @Test
    void elDocumentoEnTextoPlanoAlineaPorColumnas() {
        DocumentoImpresion doc = new DocumentoImpresion().centrada("HOLA").agregar("1", Alineacion.DERECHA, false, false);
        assertThat(doc.aTexto(10).split("\n")).containsExactly("   HOLA", "         1");
    }

    // ── Impresora ESC/POS con canal falso ────────────────────────────────

    @Test
    void laImpresoraEnviaUnTrabajoPorCopiaYAbreElCajonSoloAlFinal() throws Exception {
        List<byte[]> enviados = new ArrayList<>();
        ImpresoraEscPos impresora = new ImpresoraEscPos(enviados::add, CodePage.CP850, "Prueba");

        impresora.imprimir(new DocumentoImpresion().linea("ticket"), 2, true);

        assertThat(enviados).hasSize(2);
        assertThat(indice(enviados.get(0), EscPos.ABRIR_CAJON)).isEqualTo(-1);
        assertThat(enviados.get(1)).endsWith(EscPos.ABRIR_CAJON);
    }

    @Test
    void siElCanalFallaLaImpresoraLanzaImpresionException() {
        ImpresoraEscPos impresora = new ImpresoraEscPos(datos -> { throw new ImpresionException("sin papel"); },
                CodePage.CP850, "Prueba");
        assertThatThrownBy(() -> impresora.imprimir(new DocumentoImpresion().linea("x"), 1, false))
                .isInstanceOf(ImpresionException.class).hasMessageContaining("sin papel");
        assertThatThrownBy(impresora::abrirCajon).isInstanceOf(ImpresionException.class);
    }

    @Test
    void sinImpresoraConfiguradaImprimirAvisa() {
        ImpresoraNinguna ninguna = new ImpresoraNinguna();
        assertThatThrownBy(() -> ninguna.imprimir(new DocumentoImpresion(), 1, false))
                .isInstanceOf(ImpresionException.class).hasMessageContaining("No hay una impresora");
        assertThat(ninguna.soportaCajon()).isFalse();
    }
}
