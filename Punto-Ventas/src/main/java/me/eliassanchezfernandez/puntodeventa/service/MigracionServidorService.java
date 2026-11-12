package me.eliassanchezfernandez.puntodeventa.service;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import me.eliassanchezfernandez.puntodeventa.config.IndicesSqlite;
import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.convert.EstadoOrdenCompraConverter;
import me.eliassanchezfernandez.puntodeventa.model.convert.FormaPagoConverter;
import me.eliassanchezfernandez.puntodeventa.model.convert.PermisoConverter;
import me.eliassanchezfernandez.puntodeventa.model.convert.RolConverter;
import me.eliassanchezfernandez.puntodeventa.model.convert.TipoDescuentoConverter;
import me.eliassanchezfernandez.puntodeventa.model.convert.TipoMovimientoConverter;
import me.eliassanchezfernandez.puntodeventa.model.convert.TipoPromocionConverter;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.tool.schema.spi.DelayedDropRegistry;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Asistente "Migrar datos de esta caja al servidor" (ADMIN): copia TODAS las
 * tablas del archivo SQLite local a una base PostgreSQL VACÍA y deja esta
 * caja en modo "varias cajas". Pensado para la tienda que empezó con una sola
 * caja y agrega otras.
 *
 *  1. Se rehúsa si la base del servidor no está vacía (ninguna tabla).
 *  2. Crea el esquema en el servidor con las mismas entidades y convenciones
 *     de nombres que usa el programa (Hibernate SchemaExport) y los índices.
 *  3. Copia tabla por tabla, en orden de dependencias (padres antes que hijos),
 *     UNA transacción por tabla y con avance; convierte los tipos de SQLite
 *     (fechas como enteros, booleanos 0/1) a los de PostgreSQL.
 *  4. Ajusta las secuencias de los id al máximo copiado y verifica los conteos.
 *  5. Guarda conexion.properties en modo PostgreSQL. El archivo SQLite NO se
 *     toca: queda como respaldo. El cambio aplica al reiniciar.
 *
 * Todo se hace con JDBC directo (no con JPA) para no mezclar la sesión de la
 * base en uso con la del servidor y para copiar también columnas de versiones
 * anteriores que el modelo ya no usa pero siguen en el archivo (si existen en
 * ambas tablas).
 */
@Service
public class MigracionServidorService {

    static final int LOTE = 500;

    @Autowired private SesionService        sesionService;
    @Autowired private AuditoriaService     auditoriaService;
    @Autowired private RutasApp             rutas;
    @Autowired private ConexionService      conexionService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    /** Avance para la pantalla: tabla en curso (1..total) y filas copiadas hasta ahora en esa tabla */
    public record Progreso(String tabla, int indice, int total, long filas, String mensaje) { }

    /** Qué se copió: tablas, filas totales y tablas del archivo que no existen en el modelo actual (no se copian) */
    public record Resultado(int tablas, long filas, List<String> omitidas) { }

    /**
     * Asistente completo desde la pantalla: exige ADMIN y que el programa esté
     * trabajando con SQLite; al terminar guarda el modo PostgreSQL en el archivo
     * local (hace falta reiniciar) y deja constancia en la bitácora.
     */
    public Resultado migrarYCambiarModo(ConexionLocal destino, Consumer<Progreso> progreso) {
        sesionService.verificarAdmin();
        Cajero admin = sesionService.requerirSesion();
        if (!rutas.esSqlite()) {
            throw new NegocioException("Este programa ya trabaja contra el servidor; no hay archivo local que migrar.");
        }
        Resultado r = copiar(rutas.urlBd(), destino, progreso);

        ConexionLocal nueva = destino.copia();
        nueva.setModo(PerfilBaseDatos.POSTGRESQL);
        ConexionLocal actual = conexionService.actual();
        if (nueva.getCajaNumero() <= 0) nueva.setCajaNumero(actual.getCajaNumero());
        nueva.setAdminHash(admin.getContrasenaHash());
        try {
            nueva.guardar(conexionService.carpeta());
        } catch (IOException e) {
            throw new NegocioException("Los datos se copiaron al servidor, pero no se pudo guardar la configuración local: "
                    + e.getMessage() + ". Captúrala a mano en Configuración → Base de datos.");
        }
        auditoriaService.registrar(admin, "MIGRACION_SERVIDOR", String.format("%d tablas, %d filas a %s%s",
                r.tablas(), r.filas(), nueva.descripcionServidor(),
                r.omitidas().isEmpty() ? "" : "; omitidas: " + String.join(", ", r.omitidas())), (Cajero) null);
        return r;
    }

    /**
     * Núcleo reutilizable (también desde las pruebas): copia el archivo SQLite de
     * {@code urlSqlite} a la base PostgreSQL descrita por {@code destino}, que
     * debe estar vacía. No toca el archivo ni la configuración local.
     */
    public Resultado copiar(String urlSqlite, ConexionLocal destino, Consumer<Progreso> progreso) {
        if (progreso == null) progreso = p -> { };
        try {
            destino.validarServidor();
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        if (!PerfilBaseDatos.deUrl(urlSqlite).esSqlite()) {
            throw new NegocioException("El origen de la migración debe ser un archivo SQLite.");
        }
        try (Connection pg = ConexionService.abrir(destino)) {
            exigirVacia(pg, destino);
            progreso.accept(new Progreso("esquema", 0, 0, 0, "Creando las tablas en el servidor…"));
            crearEsquema(destino);
            IndicesSqlite.crear(pg);

            try (Connection sqlite = DriverManager.getConnection(urlSqlite)) {
                Set<String> enSqlite = tablasDe(sqlite);
                List<String> orden = ordenDeCopia(pg);
                List<String> aCopiar = new ArrayList<>();
                for (String t : orden) if (enSqlite.contains(t)) aCopiar.add(t);
                List<String> omitidas = new ArrayList<>();
                for (String t : enSqlite) if (!orden.contains(t) && !t.startsWith("sqlite_")) omitidas.add(t);

                long total = 0;
                int i = 0;
                for (String tabla : aCopiar) {
                    i++;
                    total += copiarTabla(sqlite, pg, tabla, i, aCopiar.size(), progreso);
                }
                ajustarSecuencias(pg, aCopiar);
                verificarConteos(sqlite, pg, aCopiar);
                progreso.accept(new Progreso("listo", aCopiar.size(), aCopiar.size(), total, "Migración terminada."));
                return new Resultado(aCopiar.size(), total, omitidas);
            }
        } catch (SQLException e) {
            throw new NegocioException("No se pudo migrar: " + e.getMessage());
        }
    }

    // ── Pasos ─────────────────────────────────────────────────────────────

    private static void exigirVacia(Connection pg, ConexionLocal destino) throws SQLException {
        try (Statement st = pg.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()")) {
            rs.next();
            long tablas = rs.getLong(1);
            if (tablas > 0) {
                throw new NegocioException("La base de datos \"" + destino.getBaseDatos() + "\" del servidor NO está vacía ("
                        + tablas + " tablas). Para migrar hace falta una base recién creada, sin tablas; así no se mezclan "
                        + "datos de dos tiendas.");
            }
        }
    }

    /** Mismas entidades, convertidores y convención de nombres (snake_case) que usa el programa */
    private void crearEsquema(ConexionLocal destino) {
        Map<String, Object> ajustes = new HashMap<>();
        ajustes.put(AvailableSettings.JAKARTA_JDBC_URL, destino.urlPostgresql());
        ajustes.put(AvailableSettings.JAKARTA_JDBC_USER, destino.getUsuario());
        ajustes.put(AvailableSettings.JAKARTA_JDBC_PASSWORD, destino.getContrasena());
        ajustes.put(AvailableSettings.JAKARTA_JDBC_DRIVER, PerfilBaseDatos.POSTGRESQL.driver());
        ajustes.put(AvailableSettings.DIALECT, PerfilBaseDatos.POSTGRESQL.dialecto());
        try {
            generarEsquema(ajustes, clasesDelModelo());
        } catch (RuntimeException e) {
            throw new NegocioException("No se pudieron crear las tablas en el servidor: " + e.getMessage());
        }
    }

    /**
     * Crea las tablas (solo crear, sin borrar) en la base descrita por los ajustes
     * de conexión y dialecto, con las mismas convenciones de nombres que Spring
     * Boot aplica al programa. Es el mismo mecanismo de ddl-auto, pero contra
     * OTRA base de datos distinta de la que está en uso.
     */
    public static void generarEsquema(Map<String, Object> ajustesConexion, List<Class<?>> clases) {
        Map<String, Object> ajustes = new HashMap<>(ajustesConexion);
        ajustes.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY, "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        ajustes.put(AvailableSettings.IMPLICIT_NAMING_STRATEGY, "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        ajustes.put(AvailableSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "create");   // JPA "create" = solo crear
        ajustes.put(AvailableSettings.HBM2DDL_HALT_ON_ERROR, "true");
        ajustes.put(AvailableSettings.SHOW_SQL, "false");
        StandardServiceRegistry registro = new StandardServiceRegistryBuilder().applySettings(ajustes).build();
        try {
            MetadataSources fuentes = new MetadataSources(registro);
            for (Class<?> c : clases) fuentes.addAnnotatedClass(c);
            Metadata metadata = fuentes.buildMetadata();
            Map<String, Object> cfg = new HashMap<>(registro.getService(ConfigurationService.class).getSettings());
            SchemaManagementToolCoordinator.process(metadata, registro, cfg, (DelayedDropRegistry) accion -> { });
        } finally {
            StandardServiceRegistryBuilder.destroy(registro);
        }
    }

    /** Entidades registradas en el contexto actual más los convertidores de enumeraciones (no están en el metamodelo) */
    public List<Class<?>> clasesDelModelo() {
        List<Class<?>> clases = new ArrayList<>();
        for (EntityType<?> e : entityManagerFactory.getMetamodel().getEntities()) clases.add(e.getJavaType());
        clases.add(RolConverter.class);
        clases.add(PermisoConverter.class);
        clases.add(FormaPagoConverter.class);
        clases.add(TipoDescuentoConverter.class);
        clases.add(TipoMovimientoConverter.class);
        clases.add(TipoPromocionConverter.class);
        clases.add(EstadoOrdenCompraConverter.class);
        return clases;
    }

    static Set<String> tablasDe(Connection sqlite) throws SQLException {
        Set<String> tablas = new TreeSet<>();
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) tablas.add(rs.getString(1).toLowerCase(Locale.ROOT));
        }
        return tablas;
    }

    /** Tablas del servidor en orden de dependencias (primero las que no referencian a nadie) */
    static List<String> ordenDeCopia(Connection pg) throws SQLException {
        DatabaseMetaData md = pg.getMetaData();
        String esquema = pg.getSchema();
        List<String> tablas = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, esquema, "%", new String[] {"TABLE"})) {
            while (rs.next()) tablas.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        Map<String, Set<String>> dependeDe = new LinkedHashMap<>();
        for (String t : tablas) {
            Set<String> padres = new LinkedHashSet<>();
            try (ResultSet rs = md.getImportedKeys(null, esquema, t)) {
                while (rs.next()) {
                    String padre = rs.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT);
                    if (!padre.equals(t)) padres.add(padre);
                }
            }
            dependeDe.put(t, padres);
        }
        // Kahn: se van sacando las tablas cuyos padres ya salieron
        List<String> orden = new ArrayList<>();
        Deque<String> listas = new ArrayDeque<>();
        Map<String, Integer> pendientes = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : dependeDe.entrySet()) {
            pendientes.put(e.getKey(), e.getValue().size());
            if (e.getValue().isEmpty()) listas.add(e.getKey());
        }
        while (!listas.isEmpty()) {
            String t = listas.poll();
            orden.add(t);
            for (Map.Entry<String, Set<String>> e : dependeDe.entrySet()) {
                if (e.getValue().contains(t) && pendientes.merge(e.getKey(), -1, Integer::sum) == 0) listas.add(e.getKey());
            }
        }
        for (String t : tablas) if (!orden.contains(t)) orden.add(t);   // ciclos (no debería haber): al final
        return orden;
    }

    /** Columnas de la tabla en el servidor: nombre → tipo JDBC */
    private static Map<String, Integer> columnasPg(Connection pg, String tabla) throws SQLException {
        Map<String, Integer> cols = new LinkedHashMap<>();
        try (ResultSet rs = pg.getMetaData().getColumns(null, pg.getSchema(), tabla, "%")) {
            while (rs.next()) cols.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
        }
        return cols;
    }

    private static Set<String> columnasSqlite(Connection sqlite, String tabla) throws SQLException {
        Set<String> cols = new LinkedHashSet<>();
        try (Statement st = sqlite.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + tabla + "\")")) {
            while (rs.next()) cols.add(rs.getString("name").toLowerCase(Locale.ROOT));
        }
        return cols;
    }

    /** Una transacción por tabla; devuelve las filas copiadas */
    private static long copiarTabla(Connection sqlite, Connection pg, String tabla, int indice, int total,
                                    Consumer<Progreso> progreso) throws SQLException {
        Map<String, Integer> tiposPg = columnasPg(pg, tabla);
        Set<String> enSqlite = columnasSqlite(sqlite, tabla);
        List<String> columnas = new ArrayList<>();
        for (String c : tiposPg.keySet()) if (enSqlite.contains(c)) columnas.add(c);
        if (columnas.isEmpty()) return 0;

        String lista = String.join(", ", columnas);
        String marcas = String.join(", ", java.util.Collections.nCopies(columnas.size(), "?"));
        String insert = "INSERT INTO " + tabla + " (" + lista + ") VALUES (" + marcas + ")";
        progreso.accept(new Progreso(tabla, indice, total, 0, "Copiando " + tabla + "…"));

        boolean autoCommit = pg.getAutoCommit();
        pg.setAutoCommit(false);
        long filas = 0;
        try (Statement lectura = sqlite.createStatement();
             ResultSet rs = lectura.executeQuery("SELECT " + lista + " FROM \"" + tabla + "\"");
             PreparedStatement ps = pg.prepareStatement(insert)) {
            int enLote = 0;
            while (rs.next()) {
                for (int i = 0; i < columnas.size(); i++) {
                    asignar(ps, i + 1, rs.getObject(i + 1), tiposPg.get(columnas.get(i)));
                }
                ps.addBatch();
                filas++;
                if (++enLote == LOTE) {
                    ps.executeBatch();
                    enLote = 0;
                    progreso.accept(new Progreso(tabla, indice, total, filas, "Copiando " + tabla + "… " + filas + " filas"));
                }
            }
            if (enLote > 0) ps.executeBatch();
            pg.commit();
        } catch (SQLException | RuntimeException e) {
            pg.rollback();
            throw new SQLException("tabla " + tabla + ": " + e.getMessage(), e);
        } finally {
            pg.setAutoCommit(autoCommit);
        }
        progreso.accept(new Progreso(tabla, indice, total, filas, tabla + ": " + filas + " filas"));
        return filas;
    }

    /**
     * SQLite guarda las fechas como milisegundos (o texto) y los booleanos como
     * 0/1; PostgreSQL exige el tipo exacto de la columna.
     */
    static void asignar(PreparedStatement ps, int i, Object valor, int tipoPg) throws SQLException {
        if (valor == null) {
            ps.setNull(i, tipoPg == Types.OTHER ? Types.VARCHAR : tipoPg);
            return;
        }
        switch (tipoPg) {
            case Types.BOOLEAN, Types.BIT -> ps.setBoolean(i, aBooleano(valor));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ps.setTimestamp(i, aTimestamp(valor));
            case Types.DATE -> ps.setDate(i, aDate(valor));
            case Types.BIGINT -> ps.setLong(i, ((Number) aNumero(valor)).longValue());
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> ps.setInt(i, ((Number) aNumero(valor)).intValue());
            case Types.DOUBLE, Types.FLOAT, Types.REAL, Types.NUMERIC, Types.DECIMAL ->
                    ps.setDouble(i, ((Number) aNumero(valor)).doubleValue());
            case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.CLOB -> ps.setString(i, String.valueOf(valor));
            // blob de SQLite (byte[]) → bytea de PostgreSQL: imágenes de productos
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> ps.setBytes(i, aBytes(valor));
            default -> ps.setObject(i, valor);
        }
    }

    private static byte[] aBytes(Object v) {
        if (v instanceof byte[] b) return b;
        if (v instanceof java.sql.Blob blob) {
            try {
                return blob.getBytes(1, (int) blob.length());
            } catch (SQLException e) {
                throw new IllegalArgumentException("blob ilegible: " + e.getMessage(), e);
            }
        }
        return String.valueOf(v).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean aBooleano(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("t");
    }

    private static Object aNumero(Object v) {
        if (v instanceof Number) return v;
        String s = String.valueOf(v).trim();
        if (s.equalsIgnoreCase("true")) return 1;
        if (s.equalsIgnoreCase("false")) return 0;
        return Double.parseDouble(s);
    }

    private static Timestamp aTimestamp(Object v) {
        if (v instanceof Timestamp t) return t;
        if (v instanceof Number n) return new Timestamp(n.longValue());
        if (v instanceof java.util.Date d) return new Timestamp(d.getTime());
        String s = String.valueOf(v).trim().replace('T', ' ');
        if (s.length() == 10) return Timestamp.valueOf(LocalDate.parse(s).atStartOfDay());
        return Timestamp.valueOf(LocalDateTime.parse(s.replace(' ', 'T')));
    }

    private static java.sql.Date aDate(Object v) {
        if (v instanceof java.sql.Date d) return d;
        if (v instanceof Number n) return new java.sql.Date(n.longValue());
        if (v instanceof java.util.Date d) return new java.sql.Date(d.getTime());
        String s = String.valueOf(v).trim();
        return java.sql.Date.valueOf(LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s));
    }

    /** Las columnas id autogeneradas siguen desde el mayor id copiado */
    private static void ajustarSecuencias(Connection pg, List<String> tablas) throws SQLException {
        String sql = "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND (is_identity = 'YES' OR column_default LIKE 'nextval%')";
        List<String[]> identidades = new ArrayList<>();
        try (Statement st = pg.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) identidades.add(new String[] { rs.getString(1).toLowerCase(Locale.ROOT), rs.getString(2) });
        }
        try (Statement st = pg.createStatement()) {
            for (String[] id : identidades) {
                if (!tablas.contains(id[0])) continue;
                st.execute("SELECT setval(pg_get_serial_sequence('" + id[0] + "', '" + id[1] + "'), "
                        + "COALESCE((SELECT MAX(" + id[1] + ") FROM " + id[0] + "), 0) + 1, false)");
            }
        }
    }

    private static void verificarConteos(Connection sqlite, Connection pg, List<String> tablas) throws SQLException {
        for (String t : tablas) {
            long a = contar(sqlite, "\"" + t + "\""), b = contar(pg, t);
            if (a != b) {
                throw new SQLException("la tabla " + t + " tiene " + a + " filas en el archivo y " + b + " en el servidor");
            }
        }
    }

    private static long contar(Connection c, String tabla) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + tabla)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
