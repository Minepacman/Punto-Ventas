package me.eliassanchezfernandez.puntodeventa;

import me.eliassanchezfernandez.puntodeventa.config.VersionEsquema;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** El Launcher solo salta ddl-auto=update con el programa instalado y la base marcada con la misma versión */
class LauncherEsquemaTest {

    private static final String DDL = "spring.jpa.hibernate.ddl-auto";

    @BeforeEach
    @AfterEach
    void limpiar() {
        System.clearProperty(DDL);
        System.clearProperty(VersionEsquema.PROPIEDAD_OMITIR);
    }

    private static String crearBase(Path dir, String version) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("datos.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE configuracion (id bigint primary key, nombre_tienda varchar(150), version_esquema varchar(30))");
            st.execute("INSERT INTO configuracion (id, nombre_tienda, version_esquema) VALUES (1, 'x', "
                    + (version == null ? "NULL" : "'" + version + "'") + ")");
        }
        return url;
    }

    @Test
    void sinPermisoDeOmitirNoTocaLaPropiedad(@TempDir Path dir) throws Exception {
        Launcher.omitirActualizacionDeEsquemaSiAlDia(crearBase(dir, VersionApp.version()));
        assertThat(System.getProperty(DDL)).isNull();
    }

    @Test
    void conLaMismaVersionArrancaConDdlNone(@TempDir Path dir) throws Exception {
        System.setProperty(VersionEsquema.PROPIEDAD_OMITIR, "true");
        Launcher.omitirActualizacionDeEsquemaSiAlDia(crearBase(dir, VersionApp.version()));
        boolean versionConocida = !VersionApp.DESCONOCIDA.equals(VersionApp.version());
        assertThat(System.getProperty(DDL)).isEqualTo(versionConocida ? "none" : null);
    }

    @Test
    void conOtraVersionBaseNuevaOColumnaAusenteSeActualizaComoSiempre(@TempDir Path dir) throws Exception {
        System.setProperty(VersionEsquema.PROPIEDAD_OMITIR, "true");
        Launcher.omitirActualizacionDeEsquemaSiAlDia(crearBase(dir, "0.0.1"));
        assertThat(System.getProperty(DDL)).isNull();

        Launcher.omitirActualizacionDeEsquemaSiAlDia("jdbc:sqlite:" + dir.resolve("no-existe.db"));
        assertThat(System.getProperty(DDL)).isNull();

        String url = "jdbc:sqlite:" + dir.resolve("vieja.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE configuracion (id bigint primary key, nombre_tienda varchar(150))");
            st.execute("INSERT INTO configuracion (id, nombre_tienda) VALUES (1, 'x')");
        }
        Launcher.omitirActualizacionDeEsquemaSiAlDia(url);
        assertThat(System.getProperty(DDL)).isNull();
    }

    @Test
    void unValorFijadoAManoSeRespeta(@TempDir Path dir) throws Exception {
        System.setProperty(VersionEsquema.PROPIEDAD_OMITIR, "true");
        System.setProperty(DDL, "validate");
        Launcher.omitirActualizacionDeEsquemaSiAlDia(crearBase(dir, VersionApp.version()));
        assertThat(System.getProperty(DDL)).isEqualTo("validate");
    }
}
