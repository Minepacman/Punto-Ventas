package me.eliassanchezfernandez.puntodeventa.servidor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LimitadorIntentosTest {

    @Test
    void bloqueaAlLlegarAlMaximoYLiberaAlPasarLaVentana() {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-01-01T10:00:00Z"));
        LimitadorIntentos l = new LimitadorIntentos(reloj, 3, Duration.ofMinutes(5));

        for (int i = 0; i < 2; i++) l.registrarFallo("1.1.1.1");
        assertThat(l.segundosDeEspera("1.1.1.1")).isZero();

        l.registrarFallo("1.1.1.1");
        assertThat(l.segundosDeEspera("1.1.1.1")).isEqualTo(300);
        assertThat(l.segundosDeEspera("2.2.2.2")).as("otra IP no se ve afectada").isZero();

        reloj.avanzar(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(l.segundosDeEspera("1.1.1.1")).isEqualTo(1);
        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(l.segundosDeEspera("1.1.1.1")).isZero();
    }
}
