package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Se busca a lo más una vez al día por computadora; archivo dañado o reloj atrasado = volver a buscar. */
class PreferenciasActualizacionTest {

    @TempDir Path carpeta;

    @Test
    void laPrimeraVezTocaYDespuesSoloCadaVeinticuatroHoras() {
        Instant ahora = Instant.parse("2026-11-12T10:00:00Z");
        Path sub = carpeta.resolve("nueva");
        assertThat(PreferenciasActualizacion.tocaRevisar(sub, ahora)).isTrue();

        PreferenciasActualizacion.anotarRevision(sub, ahora);
        assertThat(PreferenciasActualizacion.ultimaRevision(sub)).contains(ahora);
        assertThat(PreferenciasActualizacion.tocaRevisar(sub, ahora.plus(Duration.ofHours(23)))).isFalse();
        assertThat(PreferenciasActualizacion.tocaRevisar(sub, ahora.plus(Duration.ofHours(24)))).isTrue();
        // Reloj movido hacia atrás: la fecha guardada es "del futuro" → se vuelve a buscar
        assertThat(PreferenciasActualizacion.tocaRevisar(sub, ahora.minus(Duration.ofDays(3)))).isTrue();
    }

    @Test
    void unArchivoDanadoNoRompeNada() throws Exception {
        Files.writeString(PreferenciasActualizacion.archivo(carpeta), "ultima.revision=ayer");
        assertThat(PreferenciasActualizacion.ultimaRevision(carpeta)).isEmpty();
        assertThat(PreferenciasActualizacion.tocaRevisar(carpeta, Instant.now())).isTrue();
        PreferenciasActualizacion.anotarRevision(carpeta, Instant.now());
        assertThat(PreferenciasActualizacion.ultimaRevision(carpeta)).isPresent();
    }
}
