package me.eliassanchezfernandez.puntodeventa.servidor;

import me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API móvil de caducidades: fecha opcional de la entrada y preferencia de notificación */
class ServidorMovilCaducidadTest {

    @Test
    void laFechaDeCaducidadDeLaEntradaEsOpcionalYSoloAceptaAAAAMMDD() {
        assertThat(ServidorMovilService.fechaOpcional(null, "caducidad")).isNull();
        assertThat(ServidorMovilService.fechaOpcional("", "caducidad")).isNull();
        assertThat(ServidorMovilService.fechaOpcional("2026-12-25", "caducidad")).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThatThrownBy(() -> ServidorMovilService.fechaOpcional("25/12/2026", "caducidad")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ServidorMovilService.fechaOpcional(20261225, "caducidad")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ServidorMovilService.fechaOpcional("2026-12-25T00:00", "caducidad")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void lasPreferenciasDelTelefonoIncluyenCaducidad() {
        SuscripcionPush s = new SuscripcionPush();
        assertThat(ServidorMovilService.mapaSuscripcion(s)).containsEntry("caducidad", true);
        s.setCaducidad(false);
        assertThat(ServidorMovilService.mapaSuscripcion(s)).containsEntry("caducidad", false);
    }
}
