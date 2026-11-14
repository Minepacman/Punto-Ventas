package me.eliassanchezfernandez.puntodeventa.config;

import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.JDBC;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Fuente de conexiones físicas que Hikari usa para SQLite: crea {@link ConexionSqlite} con la URL y
 * las propiedades (pragmas) del pool, igual que haría Hikari con su DriverDataSource, pero con la
 * conexión que elige el BEGIN según la transacción. Se instala en {@link AjustesPoolPorDialecto}
 * antes de que el pool abra su primera conexión; {@code jdbcUrl} sigue puesto en el pool y lo
 * siguen leyendo {@link PerfilBaseDatos} y las pruebas.
 */
public final class FuenteConexionesSqlite implements DataSource {

    private final String url;
    /** Las del pool, en vivo: lo que se agregue después también llega a las conexiones nuevas */
    private final Properties propiedades;
    private volatile PrintWriter logWriter;
    private volatile int loginTimeout;

    FuenteConexionesSqlite(String url, Properties propiedades) {
        if (!JDBC.isValidURL(url)) throw new IllegalArgumentException("No es una URL de SQLite: " + url);
        this.url = url.trim();
        this.propiedades = propiedades;
    }

    /** Instala la fuente en el pool si no trae ya una explícita (debe llamarse antes de arrancarlo) */
    static void instalar(HikariDataSource ds) {
        if (ds.getDataSource() == null && JDBC.isValidURL(ds.getJdbcUrl())) {
            ds.setDataSource(new FuenteConexionesSqlite(ds.getJdbcUrl(), ds.getDataSourceProperties()));
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        Properties copia = new Properties();
        propiedades.forEach((k, v) -> copia.setProperty(k.toString(), v.toString()));
        return new ConexionSqlite(url, copia);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override public PrintWriter getLogWriter() { return logWriter; }
    @Override public void setLogWriter(PrintWriter out) { this.logWriter = out; }
    @Override public void setLoginTimeout(int seconds) { this.loginTimeout = seconds; }
    @Override public int getLoginTimeout() { return loginTimeout; }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("No envuelve " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
