package me.eliassanchezfernandez.puntodeventa.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig.TransactionMode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El pool se ajusta según el motor: pragmas y pool chico en SQLite, pool de 5 y tiempo corto de conexión en PostgreSQL. */
@SpringBootTest
class AjustesPoolPorDialectoTest {

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    @Test
    void elPoolDeSqliteLlevaLosPragmasYTresConexiones() throws Exception {
        HikariDataSource ds = (HikariDataSource) dataSource;
        assertThat(ds.getMaximumPoolSize()).isEqualTo(AjustesPoolPorDialecto.POOL_SQLITE);
        assertThat(ds.getDataSourceProperties()).containsEntry("journal_mode", "WAL")
                .containsEntry("foreign_keys", "true").containsEntry("busy_timeout", "10000")
                .containsEntry("synchronous", "FULL")
                .containsEntry(AjustesPoolPorDialecto.TRANSACTION_MODE, "IMMEDIATE");
        // las conexiones físicas las crea nuestra fuente (BEGIN según la transacción)
        assertThat(ds.getDataSource()).isInstanceOf(FuenteConexionesSqlite.class);
        // ...y las conexiones de verdad los traen puestos (synchronous 2 = FULL)
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(c.unwrap(ConexionSqlite.class).modoDeTransaccion()).isEqualTo(TransactionMode.IMMEDIATE);
            assertThat(c.isReadOnly()).isFalse();
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

    /** Modo con el que se abrió la transacción de Spring en curso, visto desde la conexión física */
    private TransactionMode modoActual() {
        return em.unwrap(Session.class).doReturningWork(c -> c.unwrap(ConexionSqlite.class).getCurrentTransactionMode());
    }

    /**
     * Spring pone setReadOnly(true) antes de abrir una @Transactional(readOnly = true): esa abre con BEGIN
     * (DEFERRED); las demás con BEGIN IMMEDIATE. Y la conexión vuelve al pool sin la bandera.
     */
    @Test
    void unaTransaccionDeSpringQueEscribeAbreImmediateYUnaDeSoloLecturaDeferred() {
        TransactionTemplate escritura = new TransactionTemplate(transactionManager);
        TransactionTemplate lectura = new TransactionTemplate(transactionManager);
        lectura.setReadOnly(true);

        assertThat(escritura.<TransactionMode>execute(s -> modoActual())).isEqualTo(TransactionMode.IMMEDIATE);
        assertThat(lectura.<TransactionMode>execute(s -> modoActual())).isEqualTo(TransactionMode.DEFERRED);
        assertThat(escritura.<TransactionMode>execute(s -> modoActual())).isEqualTo(TransactionMode.IMMEDIATE);
    }

    /** Lo mismo con JDBC a mano: la bandera decide el BEGIN siguiente y se puede quitar */
    @Test
    void setReadOnlyEligeElBeginDeLaSiguienteTransaccion() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ConexionSqlite fisica = c.unwrap(ConexionSqlite.class);
            c.setReadOnly(true);
            assertThat(c.isReadOnly()).isTrue();
            c.setAutoCommit(false);
            assertThat(fisica.getCurrentTransactionMode()).isEqualTo(TransactionMode.DEFERRED);
            c.rollback();
            c.setAutoCommit(true);
            c.setReadOnly(false);
            c.setAutoCommit(false);
            assertThat(fisica.getCurrentTransactionMode()).isEqualTo(TransactionMode.IMMEDIATE);
            c.rollback();
            c.setAutoCommit(true);
        }
    }

    /**
     * Con una transacción de escritura abierta (y con cambios sin confirmar), una lectura de otro hilo
     * en @Transactional(readOnly = true) termina de inmediato; otra transacción de escritura sí espera
     * a que la primera cierre (busy_timeout) y entonces continúa.
     */
    @Test
    void unaLecturaEnOtroHiloNoEsperaAUnaTransaccionDeEscrituraAbierta() throws Exception {
        TransactionTemplate escritura = new TransactionTemplate(transactionManager);
        TransactionTemplate lectura = new TransactionTemplate(transactionManager);
        lectura.setReadOnly(true);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        @SuppressWarnings("unchecked") Future<TransactionMode>[] segundoEscritor = new Future[1];
        try {
            escritura.executeWithoutResult(s -> {
                em.createNativeQuery("UPDATE configuracion SET nombre_tienda = nombre_tienda").executeUpdate();
                Future<Object> leer = hilos.submit(() -> lectura.<Object>execute(t -> {
                    assertThat(modoActual()).isEqualTo(TransactionMode.DEFERRED);
                    return em.createNativeQuery("SELECT count(*) FROM configuracion").getSingleResult();
                }));
                try {
                    assertThat(leer.get(3, SECONDS)).isNotNull();   // leyó con la escritura abierta
                } catch (Exception e) {
                    throw new AssertionError("La lectura no terminó con la escritura abierta", e);
                }
                segundoEscritor[0] = hilos.submit(() -> escritura.<TransactionMode>execute(t -> modoActual()));
                assertThatThrownBy(() -> segundoEscritor[0].get(500, MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);     // el otro escritor espera en el BEGIN
            });
            assertThat(segundoEscritor[0].get(10, SECONDS)).isEqualTo(TransactionMode.IMMEDIATE);   // y sigue al cerrar
        } finally {
            hilos.shutdownNow();
        }
    }

    /**
     * Si el BEGIN IMMEDIATE falla por BUSY, sqlite-jdbc ya había anotado autoCommit=false sin
     * transacción; la conexión debe quedar coherente (autoCommit=true), seguir abriendo transacciones
     * y volver al pool sin «cannot commit - no transaction is active».
     */
    @Test
    void unaConexionCuyoBeginFallaPorBusyQuedaSanaYVuelveAlPool() throws Exception {
        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);   // BEGIN IMMEDIATE: tiene el bloqueo
            try (Statement sb = b.createStatement()) {
                sb.execute("PRAGMA busy_timeout = 100");
                assertThatThrownBy(() -> b.setAutoCommit(false))
                        .isInstanceOf(SQLException.class).hasMessageContaining("BUSY");
                assertThat(b.getAutoCommit()).isTrue();   // la anotación se deshizo
                a.rollback();
                a.setAutoCommit(true);
                // la misma conexión abre, escribe y confirma con normalidad
                b.setAutoCommit(false);
                assertThat(b.unwrap(ConexionSqlite.class).getCurrentTransactionMode()).isEqualTo(TransactionMode.IMMEDIATE);
                sb.executeUpdate("UPDATE configuracion SET nombre_tienda = nombre_tienda");
                b.commit();
                b.setAutoCommit(true);
                sb.execute("PRAGMA busy_timeout = 10000");
            }
        }   // close() sin excepción
        // y las transacciones de Spring siguen funcionando en el pool
        assertThat(new TransactionTemplate(transactionManager).<TransactionMode>execute(s -> modoActual())).isEqualTo(TransactionMode.IMMEDIATE);
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
