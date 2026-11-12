package me.eliassanchezfernandez.puntodeventa.config;

import me.eliassanchezfernandez.puntodeventa.util.VersionApp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * "¿El esquema ya está al día con esta versión del programa?"
 *
 * Al terminar un arranque completo (ddl-auto=update + migraciones) se guarda
 * la versión en {@code configuracion.version_esquema}
 * ({@link ArranqueDiferido#marcarVersionDeEsquema}). En los arranques
 * siguientes de la MISMA versión:
 *  - el Launcher arranca Hibernate con ddl-auto=none (se ahorra leer los
 *    metadatos de las 34 tablas y los ALTER que SQLite rechaza);
 *  - {@link MigracionSqlite} y {@link MigracionProveedores} no vuelven a
 *    recorrer la base.
 *
 * Solo aplica con el programa INSTALADO (jpackage fija la propiedad
 * {@code jpackage.app-path}) o con {@code -Dapp.esquema.omitir-si-al-dia=true}:
 * en desarrollo (`gradlew run`, IDE) la versión no cambia entre cambios de
 * entidades y saltarse el update dejaría columnas sin crear.
 *
 * Se lee por JDBC puro porque el Launcher lo necesita antes de Spring y
 * MigracionSqlite antes de Hibernate; si la tabla o la columna no existen
 * (base nueva o anterior a esta versión) simplemente "no está al día".
 */
public final class VersionEsquema {

    public static final String PROPIEDAD_OMITIR = "app.esquema.omitir-si-al-dia";
    public static final String PROPIEDAD_JPACKAGE = "jpackage.app-path";

    private VersionEsquema() { }

    /** ¿Está permitido saltarse el trabajo de esquema cuando la versión coincide? */
    public static boolean puedeOmitir() {
        String explicito = System.getProperty(PROPIEDAD_OMITIR);
        if (explicito != null) return Boolean.parseBoolean(explicito);
        return System.getProperty(PROPIEDAD_JPACKAGE) != null;
    }

    /** Versión anotada en la base, si la columna existe y tiene valor */
    public static Optional<String> leer(Connection c) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version_esquema FROM configuracion WHERE id = 1")) {
            if (rs.next()) {
                String v = rs.getString(1);
                if (v != null && !v.isBlank()) return Optional.of(v.trim());
            }
        } catch (SQLException sinColumna) {
            // tabla o columna inexistentes: base nueva o de una versión anterior
        }
        return Optional.empty();
    }

    /** true si se puede omitir y la base dice que ya está al día con la versión instalada */
    public static boolean alDia(Connection c) {
        if (!puedeOmitir()) return false;
        String actual = VersionApp.version();
        if (actual == null || VersionApp.DESCONOCIDA.equals(actual)) return false;
        return leer(c).map(actual::equals).orElse(false);
    }

    public static boolean alDia(DataSource ds) {
        if (!puedeOmitir()) return false;
        try (Connection c = ds.getConnection()) {
            return alDia(c);
        } catch (SQLException e) {
            return false;
        }
    }
}
