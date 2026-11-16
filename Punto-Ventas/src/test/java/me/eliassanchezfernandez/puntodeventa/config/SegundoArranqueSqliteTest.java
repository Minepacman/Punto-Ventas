package me.eliassanchezfernandez.puntodeventa.config;

import me.eliassanchezfernandez.puntodeventa.PuntoDeVentaApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arranca el programa DOS veces sobre la misma base SQLite con ddl-auto=update, como pasa en una tienda: el primer
 * arranque crea las tablas y el segundo las revisa. Con Hibernate 6.6 la revisión de un esquema de más de 500
 * columnas fallaba ("too many terms in compound SELECT") y el programa no abría. Las demás pruebas usan create-drop
 * y nunca pasan por ese camino.
 */
class SegundoArranqueSqliteTest {

    @TempDir Path dir;

    private ConfigurableApplicationContext arrancar(Path base) {
        return new SpringApplicationBuilder(PuntoDeVentaApp.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                // como argumentos: tienen prioridad sobre el application.properties de pruebas (create-drop)
                .run("--spring.datasource.url=jdbc:sqlite:" + base, "--spring.jpa.hibernate.ddl-auto=update");
    }

    @Test
    void elSegundoArranqueSobreUnaBaseExistenteNoFalla() throws Exception {
        Path base = dir.resolve("datos.db");
        try (ConfigurableApplicationContext primero = arrancar(base)) {
            assertThat(columnas(primero.getBean(DataSource.class)))
                    .as("el esquema ya pasa del límite de 500 términos de SQLite: esta prueba cubre el caso real")
                    .isGreaterThan(500);
        }
        try (ConfigurableApplicationContext segundo = arrancar(base)) {
            assertThat(segundo.isActive()).isTrue();
        }
    }

    private static int columnas(DataSource ds) throws Exception {
        int total = 0;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet tablas = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            java.util.List<String> nombres = new java.util.ArrayList<>();
            while (tablas.next()) nombres.add(tablas.getString(1));
            for (String t : nombres) {
                try (Statement s2 = c.createStatement();
                     ResultSet rs = s2.executeQuery("SELECT count(*) FROM pragma_table_info('" + t.replace("'", "''") + "')")) {
                    total += rs.getInt(1);
                }
            }
        }
        return total;
    }
}
