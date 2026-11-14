package me.eliassanchezfernandez.puntodeventa.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El pool se ajusta según el motor: pragmas y pool chico en SQLite, pool de 5 y tiempo corto de conexión en PostgreSQL. */
@SpringBootTest
class AjustesPoolPorDialectoTest {

    @Autowired private DataSource dataSource;

    @Test
    void elPoolDeSqliteLlevaLosPragmasYTresConexiones() throws Exception {
        HikariDataSource ds = (HikariDataSource) dataSource;
        assertThat(ds.getMaximumPoolSize()).isEqualTo(AjustesPoolPorDialecto.POOL_SQLITE);
        assertThat(ds.getDataSourceProperties()).containsEntry("journal_mode", "WAL")
                .containsEntry("foreign_keys", "true").containsEntry("busy_timeout", "10000")
                .containsEntry("synchronous", "FULL")
                .containsEntry(AjustesPoolPorDialecto.TRANSACTION_MODE, "IMMEDIATE");
        // ...y las conexiones de verdad los traen puestos (synchronous 2 = FULL)
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
            }
            try (ResultSet rs = st.executeQuery("PRAGMA synchronous")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    /**
     * Con BEGIN IMMEDIATE la transacción toma el bloqueo de escritura al abrirse, aunque todavía no
     * haya ejecutado nada: otra conexión que quiera escribir espera (busy_timeout) en vez de leer
     * y luego fallar con SQLITE_BUSY_SNAPSHOT. Aquí la segunda conexión espera solo 100 ms para que
     * la prueba sea rápida.
     */
    @Test
    void unaTransaccionAbiertaTomaElBloqueoDeEscrituraDesdeElBegin() throws Exception {
        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);   // sqlite-jdbc ejecuta aquí el BEGIN IMMEDIATE
            try (Statement sb = b.createStatement()) {
                sb.execute("PRAGMA busy_timeout = 100");
                long inicio = System.nanoTime();
                assertThatThrownBy(() -> sb.executeUpdate("UPDATE configuracion SET nombre_tienda = nombre_tienda"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("BUSY");
                assertThat((System.nanoTime() - inicio) / 1_000_000).isGreaterThanOrEqualTo(80L);   // sí esperó
                sb.execute("PRAGMA busy_timeout = 10000");   // la conexión vuelve al pool como estaba
            } finally {
                a.rollback();
                a.setAutoCommit(true);
            }
        }
    }

    @Test
    void unPoolDePostgresqlNoLlevaPragmasDeSqlite() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://localhost:5432/pos");
        AjustesPoolPorDialecto.ajustar(ds, PerfilBaseDatos.deUrl(ds.getJdbcUrl()));
        assertThat(ds.getMaximumPoolSize()).isEqualTo(AjustesPoolPorDialecto.POOL_POSTGRESQL);
        assertThat(ds.getConnectionTimeout()).isEqualTo(5_000L);
        assertThat(ds.getDataSourceProperties()).doesNotContainKey("journal_mode")
                .containsEntry("connectTimeout", "5").containsKey("ApplicationName");

        // lo que venga explícito se respeta
        HikariDataSource explicito = new HikariDataSource();
        explicito.setJdbcUrl("jdbc:postgresql://localhost:5432/pos");
        explicito.setMaximumPoolSize(8);
        AjustesPoolPorDialecto.ajustar(explicito, PerfilBaseDatos.POSTGRESQL);
        assertThat(explicito.getMaximumPoolSize()).isEqualTo(8);
    }
}
