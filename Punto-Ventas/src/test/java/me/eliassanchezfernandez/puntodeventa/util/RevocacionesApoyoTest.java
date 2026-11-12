package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lista de revocación firmada (PDVR1): ida y vuelta, firma ajena, JSON raro. */
class RevocacionesApoyoTest {

    private static KeyPair par;
    private static KeyPair otroPar;
    private static ClaveDonador verificador;

    @BeforeAll
    static void llaves() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance(ClaveDonador.ALGORITMO);
        par = g.generateKeyPair();
        otroPar = g.generateKeyPair();
        verificador = new ClaveDonador(par.getPublic());
    }

    @Test
    void emiteYVerificaIdaYVuelta() {
        RevocacionesApoyo.Lista lista = new RevocacionesApoyo.Lista(LocalDate.of(2026, 11, 12), Set.of("QRST6789", "ABCD2345"), Set.of("deadbeef"));
        String texto = RevocacionesApoyo.emitir(lista, par.getPrivate());
        assertThat(texto).startsWith("PDVR1.").doesNotContain(" ", "\n");
        RevocacionesApoyo.Lista leida = RevocacionesApoyo.verificar(" " + texto + "\n", verificador);
        assertThat(leida).isEqualTo(lista);
        assertThat(leida.contiene("ABCD2345")).isTrue();
        assertThat(leida.contiene("XXXX2345")).isFalse();
        assertThat(RevocacionesApoyo.json(lista)).contains("\"v\":1", "\"emitido\":\"2026-11-12\"", "\"revocadas\":[\"ABCD2345\",\"QRST6789\"]");
    }

    @Test
    void unaListaVaciaTambienSeFirma() {
        String texto = RevocacionesApoyo.emitir(RevocacionesApoyo.Lista.vacia(LocalDate.of(2026, 11, 12)), par.getPrivate());
        RevocacionesApoyo.Lista l = RevocacionesApoyo.verificar(texto, verificador);
        assertThat(l.revocadas()).isEmpty();
        assertThat(l.clavesRetiradas()).isEmpty();
    }

    @Test
    void rechazaFirmasAjenasYTextosRaros() {
        RevocacionesApoyo.Lista lista = new RevocacionesApoyo.Lista(LocalDate.of(2026, 11, 12), Set.of("QRST6789"), Set.of());
        String ajena = RevocacionesApoyo.emitir(lista, otroPar.getPrivate());
        assertThatThrownBy(() -> RevocacionesApoyo.verificar(ajena, verificador))
                .isInstanceOf(RevocacionesApoyo.ListaInvalidaException.class).hasMessageContaining("firma");
        for (String t : new String[] {"", "hola", "PDVR1.abc", "PDV2.a.b", null, "PDVR1.%%.##"}) {
            assertThatThrownBy(() -> RevocacionesApoyo.verificar(t, verificador)).as(String.valueOf(t))
                    .isInstanceOf(RevocacionesApoyo.ListaInvalidaException.class);
        }
        // JSON firmado pero con otra versión: no se acepta
        byte[] carga = "{\"v\":2,\"emitido\":\"2026-11-12\",\"revocadas\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String otraVersion = ClaveDonador.armar(RevocacionesApoyo.PREFIJO, carga, ClaveDonador.firmar(carga, par.getPrivate()));
        assertThatThrownBy(() -> RevocacionesApoyo.verificar(otraVersion, verificador))
                .isInstanceOf(RevocacionesApoyo.ListaInvalidaException.class);
        // Demasiado grande
        assertThatThrownBy(() -> RevocacionesApoyo.verificar("PDVR1." + "A".repeat(RevocacionesApoyo.TAMANO_MAXIMO) + ".B", verificador))
                .isInstanceOf(RevocacionesApoyo.ListaInvalidaException.class);
    }
}
