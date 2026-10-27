package me.eliassanchezfernandez.puntodeventa.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** El pool se ajusta según el motor: pragmas y pool chico en SQLite, pool de 5 y tiempo corto de conexión en PostgreSQL. */
@SpringBootTest
class AjustesPoolPorDialectoTest {

    @Autowired private DataSource dataSource;

    @Test
    void elPoolDeSqliteLlevaLosPragmasYTresConexiones() {
        HikariDataSource ds = (HikariDataSource) dataSource;
        assertThat(ds.getMaximumPoolSize()).isEqualTo(AjustesPoolPorDialecto.POOL_SQLITE);
        assertThat(ds.getDataSourceProperties()).containsEntry("journal_mode", "WAL")
                .containsEntry("foreign_keys", "true").containsEntry("busy_timeout", "10000");
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
