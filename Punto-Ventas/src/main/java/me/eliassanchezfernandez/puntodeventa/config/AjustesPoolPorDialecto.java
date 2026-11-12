package me.eliassanchezfernandez.puntodeventa.config;

import com.zaxxer.hikari.HikariDataSource;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Ajusta el pool de conexiones según el motor, ANTES de que abra su primera
 * conexión. Antes estos valores vivían en application.properties como
 * spring.datasource.hikari.*, pero eran de SQLite (pragmas) y no tienen
 * sentido contra PostgreSQL.
 *
 *  - SQLite: un solo escritor → pool de 3; los pragmas se aplican a CADA
 *    conexión (WAL, busy_timeout, claves foráneas...).
 *  - PostgreSQL: pool de 5 (varias pantallas y el servidor móvil pueden
 *    consultar a la vez sin que el servidor reciba decenas de conexiones por
 *    caja), 5 s para conectar (si el servidor no responde, la pantalla de
 *    error aparece pronto) y nombre de aplicación con el número de caja, para
 *    verlo en pg_stat_activity.
 *
 * Lo que venga explícito en las propiedades (p. ej. maximum-pool-size) se respeta.
 */
@Component
public class AjustesPoolPorDialecto implements BeanPostProcessor {

    public static final int POOL_SQLITE = 3;
    public static final int POOL_POSTGRESQL = 5;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource ds && !ds.isRunning()) {
            ajustar(ds, PerfilBaseDatos.deUrl(ds.getJdbcUrl()));
        }
        return bean;
    }

    /** Punto de entrada reutilizable (también desde las pruebas) */
    public static void ajustar(HikariDataSource ds, PerfilBaseDatos perfil) {
        if (ds.getMaximumPoolSize() <= 0) {
            ds.setMaximumPoolSize(perfil.esPostgresql() ? POOL_POSTGRESQL : POOL_SQLITE);
        }
        if (ds.getMinimumIdle() < 0) ds.setMinimumIdle(1);
        if (perfil.esSqlite()) {
            propiedadSiFalta(ds, "journal_mode", "WAL");
            propiedadSiFalta(ds, "synchronous", "NORMAL");
            propiedadSiFalta(ds, "busy_timeout", "10000");
            propiedadSiFalta(ds, "cache_size", "-8000");
            propiedadSiFalta(ds, "temp_store", "MEMORY");
            // Lectura por mapeo de memoria (hasta 64 MB): menos llamadas al sistema en un disco duro
            propiedadSiFalta(ds, "mmap_size", "67108864");
            propiedadSiFalta(ds, "foreign_keys", "true");
        } else {
            if (ds.getConnectionTimeout() == 30_000L) ds.setConnectionTimeout(5_000L);
            propiedadSiFalta(ds, "connectTimeout", "5");
            propiedadSiFalta(ds, "reWriteBatchedInserts", "true");
            propiedadSiFalta(ds, "ApplicationName", "PuntoDeVenta caja "
                    + System.getProperty(ConexionLocal.PROP_CAJA_NUMERO, "1"));
        }
    }

    private static void propiedadSiFalta(HikariDataSource ds, String clave, String valor) {
        if (!ds.getDataSourceProperties().containsKey(clave)) ds.addDataSourceProperty(clave, valor);
    }
}
