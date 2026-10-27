package me.eliassanchezfernandez.puntodeventa.bascula;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Interpretación de las tramas de la báscula (sin puerto serie). */
class PesoParserTest {

    private static final Pattern NUMERO = Pattern.compile("(\\d+(?:[.,]\\d+)?)");

    @Test
    void tramaTipoTorrey() {
        assertThat(PesoParser.extraer("  0.500 kg\r\n", NUMERO)).contains(0.5);
        assertThat(PesoParser.extraer(" 12.345 kg", NUMERO)).contains(12.345);
    }

    @Test
    void tramaTipoCasConPrefijoDeEstado() {
        Pattern cas = Pattern.compile("GS,\\s*(\\d+\\.\\d+)");
        assertThat(PesoParser.extraer("ST,GS,   1.250kg", cas)).contains(1.25);
        // Con la expresión por defecto el "ST," no estorba: el primer número es el peso
        assertThat(PesoParser.extraer("ST,GS,   1.250kg", NUMERO)).contains(1.25);
    }

    @Test
    void comaDecimalYMilesSeEntiendenComoLasBasculasLosMandan() {
        assertThat(PesoParser.extraer("W: 2,50", NUMERO)).contains(2.5);
        assertThat(PesoParser.extraer("1,250", NUMERO)).contains(1.25);          // una sola coma = decimal
        assertThat(PesoParser.numero("1.250,5")).contains(1250.5);
        assertThat(PesoParser.numero("1,250.5")).contains(1250.5);
    }

    @Test
    void sinNumeroONegativoONoEsPeso() {
        assertThat(PesoParser.extraer("ERROR", NUMERO)).isEmpty();
        assertThat(PesoParser.extraer("", NUMERO)).isEmpty();
        assertThat(PesoParser.extraer(null, NUMERO)).isEmpty();
        assertThat(PesoParser.numero("-1.5")).isEmpty();
        assertThat(PesoParser.numero("99999")).isEmpty();                           // absurdo (> 10 000 kg)
        assertThat(PesoParser.numero("1.2.3.x")).isEmpty();
    }

    @Test
    void usaElGrupoSiLaExpresionLoTieneYSiNoToda_laCoincidencia() {
        assertThat(PesoParser.extraer("P=3.5", Pattern.compile("P=(\\d+\\.\\d)"))).contains(3.5);
        assertThat(PesoParser.extraer("3.5 kg", Pattern.compile("\\d+\\.\\d"))).contains(3.5);
    }

    @Test
    void enUnFlujoContinuoTomaLaUltimaTramaCompleta() {
        String bloque = "  0.480 kg\r\n  0.495 kg\r\n  0.500 kg\r\n";
        assertThat(PesoParser.ultimoPeso(bloque, NUMERO)).contains(0.5);
    }

    @Test
    void ignoraLaUltimaTramaSiLlegoCortada() {
        // "0.50" es la mitad de "0.500": no debe tomarse
        assertThat(PesoParser.ultimoPeso("  0.480 kg\r\n  0.50", NUMERO)).contains(0.48);
        // Una sola trama sin terminador sí se acepta (algunas básculas no mandan salto)
        assertThat(PesoParser.ultimoPeso("  0.500 kg", NUMERO)).contains(0.5);
    }

    @Test
    void tramasConStxYEtx() {
        assertThat(PesoParser.ultimoPeso("\u0002  1.250\u0003\u0002  1.255\u0003", NUMERO)).contains(1.255);
        assertThat(PesoParser.hayTramaCompleta("\u0002  1.250\u0003", NUMERO)).isTrue();
    }

    @Test
    void hayTramaCompletaSoloConTerminadorYPeso() {
        assertThat(PesoParser.hayTramaCompleta("  0.500", NUMERO)).isFalse();
        assertThat(PesoParser.hayTramaCompleta("  0.500 kg\r\n", NUMERO)).isTrue();
        assertThat(PesoParser.hayTramaCompleta("basura\r\n", NUMERO)).isFalse();
        assertThat(PesoParser.hayTramaCompleta("", NUMERO)).isFalse();
    }
}
