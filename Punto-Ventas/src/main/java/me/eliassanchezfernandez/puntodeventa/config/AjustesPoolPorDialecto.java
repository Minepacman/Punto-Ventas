package me.eliassanchezfernandez.puntodeventa.config;

import com.zaxxer.hikari.HikariConfig;
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
    /** Propiedad de sqlite-jdbc que fija el BEGIN que abre cada transacción (DEFERRED/IMMEDIATE/EXCLUSIVE) */
    public static final String TRANSACTION_MODE = "transaction_mode";
    /** Tamaño que HikariCP pone por su cuenta (10 en HikariCP 6) */
    private static final int POOL_POR_DEFECTO_HIKARI = new HikariConfig().getMaximumPoolSize();
    /** mmap_size de SQLite: 64 MB con Java de 64 bits, 0 (lectura normal) con Java de 32 bits */
    static final String MMAP_BYTES = mmapPara(System.getProperty("sun.arch.data.model"));

    static String mmapPara(String bitsJvm) {
        return "32".equals(bitsJvm) ? "0" : "67108864";
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource ds && !ds.isRunning()) {
            ajustar(ds, PerfilBaseDatos.deUrl(ds.getJdbcUrl()));
        }
        return bean;
    }

    /** Punto de entrada reutilizable (también desde las pruebas) */
    public static void ajustar(HikariDataSource ds, PerfilBaseDatos perfil) {
        // HikariCP 5 dejaba -1 si nadie lo fijaba; HikariCP 6 (Spring Boot 3.4+) ya trae 10. Se toma como "no
        // fijado" cualquiera de los dos; un 10 puesto a mano también se cambiaría (en un POS no tiene caso).
        if (ds.getMaximumPoolSize() <= 0 || ds.getMaximumPoolSize() == POOL_POR_DEFECTO_HIKARI) {
            ds.setMaximumPoolSize(perfil.esPostgresql() ? POOL_POSTGRESQL : POOL_SQLITE);
        }
        if (ds.getMinimumIdle() < 0) ds.setMinimumIdle(1);
        if (perfil.esSqlite()) {
            propiedadSiFalta(ds, "journal_mode", "WAL");
            // FULL: cada commit se sincroniza al disco (fsync del -wal). Con NORMAL en WAL una venta ya
            // cobrada podía perderse si se iba la luz antes del siguiente checkpoint; un POS no puede.
            // Cuesta un fsync por commit (~10-20 ms en disco duro), que una venta no nota.
            propiedadSiFalta(ds, "synchronous", "FULL");
            // BEGIN IMMEDIATE: toda transacción que pueda escribir toma el bloqueo de escritura al
            // empezar (y espera busy_timeout si otro lo tiene). Con DEFERRED una transacción que leía y
            // luego escribía fallaba de inmediato con SQLITE_BUSY_SNAPSHOT si otra había escrito entre
            // medias (el busy handler no aplica ahí). Las @Transactional(readOnly = true) abren con BEGIN
            // (DEFERRED) y no esperan a nadie: lo decide ConexionSqlite por el setReadOnly de Spring.
            propiedadSiFalta(ds, TRANSACTION_MODE, "IMMEDIATE");
            propiedadSiFalta(ds, "busy_timeout", "10000");
            // El -wal no crece sin límite tras una operación grande (se recorta al cerrar la transacción)
            propiedadSiFalta(ds, "journal_size_limit", "33554432");
            propiedadSiFalta(ds, "cache_size", "-8000");
            propiedadSiFalta(ds, "temp_store", "MEMORY");
            // Lectura por mapeo de memoria (hasta 64 MB): menos llamadas al sistema en un disco duro.
            // Con Java de 32 bits no: cada conexión del pool mapea su propia ventana y el proceso entero
            // (memoria de Java, archivo de arranque rápido, JavaFX) cabe en 2 GB de direcciones.
            propiedadSiFalta(ds, "mmap_size", MMAP_BYTES);
            propiedadSiFalta(ds, "foreign_keys", "true");
            // Las conexiones físicas las crea FuenteConexionesSqlite (ConexionSqlite: BEGIN según la
            // transacción y recuperación si el BEGIN falla por BUSY)
            FuenteConexionesSqlite.instalar(ds);
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
