package me.eliassanchezfernandez.puntodeventa.config;

import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.jdbc4.JDBC4Connection;

import java.sql.SQLException;
import java.util.Properties;

/**
 * Conexión SQLite del pool que elige el BEGIN según el tipo de transacción de Spring.
 *
 * sqlite-jdbc abre cada transacción en {@code setAutoCommit(false)} con el BEGIN que dicte
 * {@code transaction_mode} (aquí IMMEDIATE, ver {@link AjustesPoolPorDialecto}). Con IMMEDIATE
 * también las lecturas en transacción tomaban el bloqueo de escritura y una consulta de la pantalla
 * esperaba (hasta busy_timeout) a que terminara cualquier otra transacción, aunque solo leyera.
 *
 * Spring llama {@code setReadOnly(true)} ANTES de {@code setAutoCommit(false)} en las transacciones
 * {@code @Transactional(readOnly = true)} ({@code HibernateJpaDialect.beginTransaction} →
 * {@code DataSourceUtils.prepareConnectionForTransaction}) y {@code setReadOnly(false)} al terminar.
 * Esta clase recuerda esa bandera (sqlite-jdbc la rechaza una vez abierta la conexión) y:
 *  - solo lectura → {@code BEGIN} (DEFERRED): en WAL los lectores no se bloquean entre sí ni con el escritor;
 *  - lo demás      → {@code BEGIN IMMEDIATE}: el bloqueo de escritura se toma al abrir y nunca hay
 *                    SQLITE_BUSY_SNAPSHOT al pasar de leer a escribir.
 * El JDBC directo (migraciones, respaldos, pruebas) no llama setReadOnly y sigue en IMMEDIATE.
 *
 * Además, si el BEGIN falla (BUSY tras busy_timeout) deja la conexión coherente: sqlite-jdbc ya había
 * anotado autoCommit=false sin transacción abierta y la conexión volvía al pool rota (el siguiente
 * {@code setAutoCommit(false)} no hacía nada y el commit fallaba con
 * «cannot commit - no transaction is active»). Hikari no la desechaba porque SQLiteException no trae SQLState.
 */
public class ConexionSqlite extends JDBC4Connection {

    /** El modo de las propiedades (IMMEDIATE), para las transacciones que pueden escribir */
    private final SQLiteConfig.TransactionMode modoEscritura;
    private volatile boolean soloLectura;

    public ConexionSqlite(String url, Properties propiedades) throws SQLException {
        super(url, url.substring(JDBC.PREFIX.length()), propiedades);
        this.modoEscritura = getConnectionConfig().getTransactionMode();
    }

    /** Solo se anota: decide el BEGIN de la siguiente transacción (sqlite-jdbc no admite cambiarla tras abrir) */
    @Override
    public void setReadOnly(boolean soloLectura) throws SQLException {
        checkOpen();
        this.soloLectura = soloLectura;
    }

    @Override
    public boolean isReadOnly() {
        return super.isReadOnly() || soloLectura;
    }

    /** Modo con el que abriría ahora una transacción */
    public SQLiteConfig.TransactionMode modoDeTransaccion() {
        return soloLectura ? SQLiteConfig.TransactionMode.DEFERRED : modoEscritura;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        if (getAutoCommit() == autoCommit) return;
        if (!autoCommit) getConnectionConfig().setTransactionMode(modoDeTransaccion());
        try {
            super.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            if (!autoCommit) {
                // El BEGIN no se ejecutó: deshacer la anotación para que la conexión siga sana en el pool
                getConnectionConfig().setAutoCommit(true);
                setCurrentTransactionMode(null);
            }
            throw e;
        }
    }
}
