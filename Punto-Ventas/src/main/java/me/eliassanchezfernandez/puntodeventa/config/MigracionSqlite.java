package me.eliassanchezfernandez.puntodeventa.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Migración mínima para bases de datos SQLite creadas por versiones
 * anteriores, ejecutada ANTES de que Hibernate aplique ddl-auto=update.
 *
 * Problema: Hibernate generaba restricciones CHECK para los enums
 * (ej. rol in ('ADMIN','CAJERO')) y columnas NOT NULL que SQLite no permite
 * modificar con ALTER TABLE. Para admitir el rol GERENTE, nuevos tipos de
 * promoción y promociones por departamento hay que RECONSTRUIR esas tablas:
 * crear la tabla nueva sin la restricción, copiar las filas, borrar la vieja
 * y renombrar. Todo dentro de una transacción.
 *
 * Es idempotente: si la tabla ya no tiene la restricción no hace nada, y en
 * una base nueva (sin tablas) tampoco.
 */
@Component("migracionSqlite")
public class MigracionSqlite implements InitializingBean {

    /** Reemplazos de texto sobre el CREATE TABLE original, por tabla */
    private static final Map<String, Map<Pattern, String>> REEMPLAZOS = new LinkedHashMap<>();

    static {
        REEMPLAZOS.put("cajeros", Map.of(
                Pattern.compile("\\s*check \\(rol in \\([^)]*\\)\\)", Pattern.CASE_INSENSITIVE), ""));
        REEMPLAZOS.put("promociones", Map.of(
                Pattern.compile("\\s*check \\(tipo in \\([^)]*\\)\\)", Pattern.CASE_INSENSITIVE), "",
                Pattern.compile("producto_id bigint not null", Pattern.CASE_INSENSITIVE), "producto_id bigint"));
        REEMPLAZOS.put("ventas", Map.of(
                Pattern.compile("\\s*check \\(forma_pago in \\([^)]*\\)\\)", Pattern.CASE_INSENSITIVE), ""));
        REEMPLAZOS.put("detalle_ventas", Map.of(
                Pattern.compile("cantidad integer not null", Pattern.CASE_INSENSITIVE), "cantidad float not null"));
    }

    @Autowired private DataSource dataSource;

    @Override
    public void afterPropertiesSet() throws Exception {
        migrar(dataSource);
    }

    /** Punto de entrada reutilizable (también desde las pruebas) */
    public static List<String> migrar(DataSource dataSource) throws SQLException {
        List<String> tablasMigradas = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            if (!esSqlite(c)) return tablasMigradas;

            // Antes de tocar nada: ¿hay algo que migrar? Si sí, respaldar el archivo
            boolean hayCambios = false;
            for (Map.Entry<String, Map<Pattern, String>> e : REEMPLAZOS.entrySet()) {
                if (requiereCambios(c, e.getKey(), e.getValue())) { hayCambios = true; break; }
            }
            if (hayCambios) respaldarArchivo(c);

            for (Map.Entry<String, Map<Pattern, String>> e : REEMPLAZOS.entrySet()) {
                if (reconstruirTabla(c, e.getKey(), e.getValue())) {
                    tablasMigradas.add(e.getKey());
                    System.out.println("ℹ️  Migración SQLite: tabla '" + e.getKey() + "' reconstruida.");
                }
            }
        }
        return tablasMigradas;
    }

    private static boolean requiereCambios(Connection c, String tabla, Map<Pattern, String> reemplazos)
            throws SQLException {
        String sql = sqlDeTabla(c, tabla);
        if (sql == null) return false;
        String nuevo = sql;
        for (Map.Entry<Pattern, String> r : reemplazos.entrySet()) {
            nuevo = r.getKey().matcher(nuevo).replaceAll(r.getValue());
        }
        return !nuevo.equals(sql);
    }

    /**
     * Copia completa de la BD (VACUUM INTO) junto al archivo original,
     * p. ej. datos.db.respaldo-migracion-20261025-1930, antes de reconstruir.
     * Si la BD es en memoria no hay nada que respaldar.
     */
    static void respaldarArchivo(Connection c) throws SQLException {
        String url = c.getMetaData().getURL();           // jdbc:sqlite:datos.db
        if (url == null || !url.startsWith("jdbc:sqlite:")) return;
        String ruta = url.substring("jdbc:sqlite:".length());
        int q = ruta.indexOf('?');
        if (q >= 0) ruta = ruta.substring(0, q);
        if (ruta.isBlank() || ruta.contains(":memory:") || url.contains("mode=memory")) return;

        String destino = ruta + ".respaldo-migracion-"
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        try (Statement st = c.createStatement()) {
            st.execute("VACUUM INTO '" + destino.replace("'", "''") + "'");
        }
        System.out.println("ℹ️  Migración SQLite: respaldo creado en " + destino);
    }

    private static boolean esSqlite(Connection c) throws SQLException {
        String producto = c.getMetaData().getDatabaseProductName();
        return producto != null && producto.toLowerCase().contains("sqlite");
    }

    /**
     * @return true si la tabla existía con alguna restricción a quitar y se reconstruyó
     */
    static boolean reconstruirTabla(Connection c, String tabla, Map<Pattern, String> reemplazos)
            throws SQLException {

        String sqlOriginal = sqlDeTabla(c, tabla);
        if (sqlOriginal == null) return false;              // la tabla no existe (BD nueva)

        String sqlNuevo = sqlOriginal;
        for (Map.Entry<Pattern, String> r : reemplazos.entrySet()) {
            sqlNuevo = r.getKey().matcher(sqlNuevo).replaceAll(r.getValue());
        }
        if (sqlNuevo.equals(sqlOriginal)) return false;     // nada que cambiar

        String temporal = tabla + "__nuevo";
        sqlNuevo = sqlNuevo.replaceFirst("(?i)CREATE TABLE\\s+\"?" + tabla + "\"?", "CREATE TABLE " + temporal);

        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + temporal);
            st.execute(sqlNuevo);
            st.execute("INSERT INTO " + temporal + " SELECT * FROM " + tabla);
            st.execute("DROP TABLE " + tabla);
            st.execute("ALTER TABLE " + temporal + " RENAME TO " + tabla);
            c.commit();
            return true;
        } catch (SQLException ex) {
            c.rollback();
            throw new SQLException("No se pudo migrar la tabla " + tabla + ": " + ex.getMessage(), ex);
        } finally {
            c.setAutoCommit(autoCommit);
        }
    }

    static String sqlDeTabla(Connection c, String tabla) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, tabla);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Obliga a que el EntityManagerFactory (y por tanto ddl-auto) espere a la migración */
    @Configuration
    static class Orden {
        @Bean
        static EntityManagerFactoryDependsOnPostProcessor migracionAntesDeJpa() {
            return new EntityManagerFactoryDependsOnPostProcessor("migracionSqlite");
        }
    }
}
