package me.eliassanchezfernandez.puntodeventa.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Los índices de consulta existen tras el arranque y los pragmas de SQLite están aplicados. */
@SpringBootTest
class IndicesSqliteTest {

    @Autowired private DataSource dataSource;

    @Test
    void existenTodosLosIndicesEsperados() throws Exception {
        Set<String> existentes = new HashSet<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'")) {
            while (rs.next()) existentes.add(rs.getString(1));
        }
        for (String sql : IndicesSqlite.INDICES) {
            String nombre = sql.replaceAll("(?s).*EXISTS\\s+(\\w+)\\s+ON.*", "$1");
            assertThat(existentes).as(nombre).contains(nombre);
        }
        assertThat(IndicesSqlite.INDICES).hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    void lasConexionesUsanWalBusyTimeoutYClavesForaneas() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(pragma(st, "journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(pragma(st, "synchronous")).isEqualTo("1");        // NORMAL
            assertThat(pragma(st, "busy_timeout")).isEqualTo("10000");
            assertThat(pragma(st, "foreign_keys")).isEqualTo("1");
            assertThat(pragma(st, "temp_store")).isEqualTo("2");         // MEMORY
            assertThat(pragma(st, "cache_size")).isEqualTo("-8000");
        }
    }

    private static String pragma(Statement st, String nombre) throws Exception {
        try (ResultSet rs = st.executeQuery("PRAGMA " + nombre)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
