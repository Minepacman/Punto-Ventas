package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Ráfagas del lector de códigos vs. teclado humano, solo por el ritmo de las teclas. */
class RafagaLectorTest {

    private static final long MS = 1_000_000L;

    /** Escribe el texto con la separación dada y devuelve el instante de la última tecla */
    private static long teclear(RafagaLector lector, String texto, long desdeNanos, long separacionMs) {
        long t = desdeNanos;
        for (char c : texto.toCharArray()) {
            lector.caracter(c, t);
            t += separacionMs * MS;
        }
        return t - separacionMs * MS;
    }

    @Test
    void unLectorEscribeElCodigoYElEnterEnMilisegundos() {
        RafagaLector lector = new RafagaLector();
        long ultimo = teclear(lector, "7501055300006", 1_000 * MS, 3);
        assertThat(lector.enRafaga()).isTrue();
        assertThat(lector.enter(ultimo + 4 * MS)).contains("7501055300006");
        assertThat(lector.texto()).as("tras el Enter se vacía").isEmpty();
    }

    @Test
    void unaPersonaTecleandoNoEsRafaga() {
        RafagaLector lector = new RafagaLector();
        long ultimo = teclear(lector, "200", 1_000 * MS, 120);   // 120 ms entre teclas: rápido para una persona
        assertThat(lector.enRafaga()).isFalse();
        assertThat(lector.enter(ultimo + 150 * MS)).isEmpty();
    }

    @Test
    void elEnterDeUnaPersonaDespuesDeUnaRafagaNoCuenta() {
        // El lector escribió el código pero el Enter llegó medio segundo después: lo pulsó alguien
        RafagaLector lector = new RafagaLector();
        long ultimo = teclear(lector, "12345", 0, 2);
        assertThat(lector.enter(ultimo + 500 * MS)).isEmpty();
    }

    @Test
    void unaSeparacionHumanaEnMedioRompeLaRafaga() {
        RafagaLector lector = new RafagaLector();
        long t = teclear(lector, "123", 0, 2);
        t += 300 * MS;                                   // pausa humana
        long ultimo = teclear(lector, "456", t, 2);
        assertThat(lector.enRafaga()).isFalse();
        assertThat(lector.enter(ultimo + 2 * MS)).isEmpty();
    }

    @Test
    void unSoloCaracterYEnterNoEsCodigo() {
        RafagaLector lector = new RafagaLector();
        lector.caracter('5', 0);
        assertThat(lector.enRafaga()).isFalse();
        assertThat(lector.enter(5 * MS)).isEmpty();
    }

    @Test
    void elEnterHumanoVaciaLoAcumuladoYLaSiguienteRafagaEmpiezaLimpia() {
        RafagaLector lector = new RafagaLector();
        teclear(lector, "ab", 0, 200);
        assertThat(lector.enter(1_000 * MS)).isEmpty();
        long ultimo = teclear(lector, "4321", 5_000 * MS, 5);
        assertThat(lector.enter(ultimo + 5 * MS)).contains("4321");
    }

    @Test
    void losCaracteresDeControlSeIgnoran() {
        RafagaLector lector = new RafagaLector();
        lector.caracter('\t', 0);
        lector.caracter('\r', 1 * MS);
        assertThat(lector.texto()).isEmpty();
        long ultimo = teclear(lector, "99", 10 * MS, 1);
        assertThat(lector.enter(ultimo + 1 * MS)).contains("99");
    }

    @Test
    void elUmbralEsConfigurable() {
        RafagaLector estricto = new RafagaLector(10);
        long ultimo = teclear(estricto, "12345", 0, 20);          // 20 ms: lector lento, umbral 10
        assertThat(estricto.enter(ultimo + 20 * MS)).isEmpty();

        RafagaLector tolerante = new RafagaLector(30);
        ultimo = teclear(tolerante, "12345", 0, 20);
        assertThat(tolerante.enter(ultimo + 20 * MS)).contains("12345");
    }
}
