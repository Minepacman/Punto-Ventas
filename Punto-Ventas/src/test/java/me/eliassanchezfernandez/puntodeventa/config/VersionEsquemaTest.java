package me.eliassanchezfernandez.puntodeventa.config;

import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/** La marca de versión del esquema se guarda en configuracion y solo "está al día" con el programa instalado */
@SpringBootTest
class VersionEsquemaTest {

    @Autowired private DataSource dataSource;
    @Autowired private ConfiguracionService configuracionService;

    @AfterEach
    void limpiar() {
        System.clearProperty(VersionEsquema.PROPIEDAD_OMITIR);
        configuracionService.marcarVersionEsquema("0.0.0-prueba");
    }

    @Test
    void laMarcaSeLeePorJdbcYSinPermisoDeOmitirNuncaEstaAlDia() throws Exception {
        configuracionService.marcarVersionEsquema("9.9.9");
        try (Connection c = dataSource.getConnection()) {
            assertThat(VersionEsquema.leer(c)).contains("9.9.9");
            assertThat(VersionEsquema.alDia(c)).isFalse();        // en desarrollo nunca se omite
        }
        assertThat(VersionEsquema.puedeOmitir()).isFalse();
    }

    @Test
    void conPermisoDeOmitirEstaAlDiaSoloSiCoincideLaVersionInstalada() throws Exception {
        System.setProperty(VersionEsquema.PROPIEDAD_OMITIR, "true");
        assertThat(VersionEsquema.puedeOmitir()).isTrue();
        configuracionService.marcarVersionEsquema("otra");
        assertThat(VersionEsquema.alDia(dataSource)).isFalse();

        configuracionService.marcarVersionEsquema(VersionApp.version());
        boolean versionConocida = !VersionApp.DESCONOCIDA.equals(VersionApp.version());
        assertThat(VersionEsquema.alDia(dataSource)).isEqualTo(versionConocida);
    }

    @Test
    void marcarLaMismaVersionNoVuelveAEscribir() {
        configuracionService.marcarVersionEsquema("1.2.3");
        configuracionService.marcarVersionEsquema("1.2.3");
        assertThat(configuracionService.obtenerParaEditar().getVersionEsquema()).isEqualTo("1.2.3");
        configuracionService.marcarVersionEsquema(" ");
        assertThat(configuracionService.obtenerParaEditar().getVersionEsquema()).isEqualTo("1.2.3");
    }
}
