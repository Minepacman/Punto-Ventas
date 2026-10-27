package me.eliassanchezfernandez.puntodeventa.config;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Motor de base de datos en uso. El programa funciona con dos:
 *
 *  - SQLITE: un archivo local (datos.db), una sola caja. Es el modo por defecto.
 *  - POSTGRESQL: un servidor en la red local al que se conectan varias cajas.
 *
 * Todo lo que es propio de un motor (pragmas, VACUUM INTO, reconstrucción de
 * tablas, tamaño del pool) pregunta aquí en lugar de suponer SQLite.
 */
public enum PerfilBaseDatos {

    SQLITE("jdbc:sqlite:", "org.sqlite.JDBC", "org.hibernate.community.dialect.SQLiteDialect"),
    POSTGRESQL("jdbc:postgresql:", "org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect");

    private final String prefijoUrl;
    private final String driver;
    private final String dialecto;

    PerfilBaseDatos(String prefijoUrl, String driver, String dialecto) {
        this.prefijoUrl = prefijoUrl;
        this.driver = driver;
        this.dialecto = dialecto;
    }

    public String prefijoUrl() { return prefijoUrl; }
    public String driver()     { return driver; }
    public String dialecto()   { return dialecto; }

    public boolean esSqlite()     { return this == SQLITE; }
    public boolean esPostgresql() { return this == POSTGRESQL; }

    /** Perfil según la URL JDBC; cualquier cosa que no sea PostgreSQL se trata como SQLite (el valor histórico) */
    public static PerfilBaseDatos deUrl(String url) {
        if (url != null && url.toLowerCase(Locale.ROOT).startsWith(POSTGRESQL.prefijoUrl)) return POSTGRESQL;
        return SQLITE;
    }

    /** Perfil según el producto que reporta la conexión abierta */
    public static PerfilBaseDatos de(Connection c) throws SQLException {
        String producto = c.getMetaData().getDatabaseProductName();
        if (producto != null && producto.toLowerCase(Locale.ROOT).contains("postgres")) return POSTGRESQL;
        return SQLITE;
    }

    /** Perfil del DataSource: por su URL si es Hikari (sin abrir conexión), si no abriendo una */
    public static PerfilBaseDatos de(DataSource ds) throws SQLException {
        if (ds instanceof HikariDataSource h && h.getJdbcUrl() != null) return deUrl(h.getJdbcUrl());
        try (Connection c = ds.getConnection()) {
            return de(c);
        }
    }
}
