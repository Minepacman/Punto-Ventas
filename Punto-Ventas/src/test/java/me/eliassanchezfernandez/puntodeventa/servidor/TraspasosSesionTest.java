package me.eliassanchezfernandez.puntodeventa.servidor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Códigos de un solo uso para pasar la sesión del teléfono a la página segura / al icono de inicio */
class TraspasosSesionTest {

    private final RelojMutable reloj = new RelojMutable(Instant.parse("2026-11-15T15:00:00Z"));
    private final TraspasosSesion t = new TraspasosSesion(reloj);
    private static final String HASH = "a".repeat(64);

    @Test
    void elCodigoSirveUnaSolaVezYDevuelveLaSesionOriginal() {
        String c = t.emitir(7L, HASH, TraspasosSesion.VIGENCIA_SEGURO);
        assertThat(c).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(TraspasosSesion.formatoValido(c)).isTrue();
        var p = t.canjear(c);
        assertThat(p).isPresent();
        assertThat(p.get().sesionId()).isEqualTo(7L);
        assertThat(p.get().tokenHash()).isEqualTo(HASH);
        assertThat(t.canjear(c)).as("segundo uso").isEmpty();
    }

    @Test
    void venceALaHoraExacta() {
        String c = t.emitir(1L, HASH, TraspasosSesion.VIGENCIA_SEGURO);
        reloj.avanzar(TraspasosSesion.VIGENCIA_SEGURO.minusSeconds(1));
        String d = t.emitir(2L, HASH, TraspasosSesion.VIGENCIA_SEGURO);
        assertThat(t.canjear(c)).isPresent();
        reloj.avanzar(TraspasosSesion.VIGENCIA_SEGURO);
        assertThat(t.canjear(d)).as("vencido").isEmpty();
        assertThat(t.pendientes()).isZero();
    }

    @Test
    void elDeIconoDeInicioDuraMasQueElDePaginaSegura() {
        assertThat(TraspasosSesion.VIGENCIA_INICIO).isGreaterThan(TraspasosSesion.VIGENCIA_SEGURO);
        assertThat(TraspasosSesion.VIGENCIA_INICIO).isLessThanOrEqualTo(Duration.ofMinutes(15));
        String c = t.emitir(1L, HASH, TraspasosSesion.VIGENCIA_INICIO);
        reloj.avanzar(Duration.ofMinutes(10));
        assertThat(t.canjear(c)).isPresent();
    }

    @Test
    void codigosInventadosOConFormatoRaroNoSirven() {
        t.emitir(1L, HASH, TraspasosSesion.VIGENCIA_SEGURO);
        assertThat(t.canjear(null)).isEmpty();
        assertThat(t.canjear("")).isEmpty();
        assertThat(t.canjear("x".repeat(43))).isEmpty();
        assertThat(t.canjear("con espacios y mas de cuarenta y tres caracteres")).isEmpty();
        assertThat(TraspasosSesion.formatoValido("a".repeat(42))).isFalse();
        assertThat(TraspasosSesion.formatoValido("a".repeat(42) + "=")).isFalse();
        assertThat(t.pendientes()).isEqualTo(1);
    }

    @Test
    void cadaSesionTieneAMuchoTresPendientesYElMasViejoSeDescarta() {
        List<String> codigos = new ArrayList<>();
        for (int i = 0; i < 5; i++) codigos.add(t.emitir(9L, HASH, TraspasosSesion.VIGENCIA_SEGURO));
        String otra = t.emitir(10L, HASH, TraspasosSesion.VIGENCIA_SEGURO);
        assertThat(t.pendientes()).isEqualTo(TraspasosSesion.MAX_POR_SESION + 1);
        assertThat(t.canjear(codigos.get(0))).isEmpty();
        assertThat(t.canjear(codigos.get(1))).isEmpty();
        assertThat(t.canjear(codigos.get(4))).isPresent();
        assertThat(t.canjear(otra)).isPresent();
    }

    @Test
    void enMemoriaNuncaHayMasDelTope() {
        for (long i = 0; i < TraspasosSesion.MAX_TOTAL + 50; i++) t.emitir(i, HASH, TraspasosSesion.VIGENCIA_SEGURO);
        assertThat(t.pendientes()).isEqualTo(TraspasosSesion.MAX_TOTAL);
    }
}
