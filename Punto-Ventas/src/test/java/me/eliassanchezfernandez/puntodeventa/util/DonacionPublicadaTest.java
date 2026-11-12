package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El archivo publicado en el repositorio ({@code claves/donacion.pdvd}) debe estar
 * firmado con la llave oficial y decir LO MISMO que {@code donacion.properties}:
 * si el dueño cambia la CLABE y olvida correr {@code ./gradlew firmarDatosDonacion},
 * la versión publicada diría «estos datos NO coinciden» en todas las instalaciones.
 */
class DonacionPublicadaTest {

    @Test
    void losDatosPublicadosEstanFirmadosYCoincidenConDonacionProperties() throws Exception {
        Path archivo = Path.of("..", "claves", "donacion.pdvd");
        assertThat(archivo).as("claves/donacion.pdvd en la raíz del repositorio (./gradlew firmarDatosDonacion)").exists();
        DatosDonacionFirmados.Datos publicados = DatosDonacionFirmados.verificar(Files.readString(archivo), ClaveDonador.oficial());
        assertThat(publicados.emitido()).isAfter(java.time.LocalDate.of(2026, 11, 1));
        assertThat(publicados.sitio()).isEqualTo(Marca.SITIO_OFICIAL);
        Donacion.Datos incrustados = Donacion.cargar();
        assertThat(publicados.coincideCon(incrustados))
                .as("claves/donacion.pdvd desfasado respecto a donacion.properties: corre ./gradlew firmarDatosDonacion")
                .isTrue();
        assertThat(publicados.pendiente()).isEqualTo(!incrustados.clabeConfigurada());
        if (!publicados.pendiente()) {
            assertThat(publicados.beneficiario()).isNotBlank();
            assertThat(publicados.banco()).isNotBlank();
        }
    }
}
