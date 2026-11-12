package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.service.MigracionServidorService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asistente SQLite → PostgreSQL: se arma un archivo SQLite con el esquema del
 * programa (el mismo generador que usa el asistente, con dialecto SQLite) y
 * datos de muestra, se copia a una base vacía del servidor embebido y se
 * verifica fila por fila; con la base ya ocupada se rehúsa y el archivo queda intacto.
 */
class MigracionPostgresTest extends BasePostgresTest {

    @Autowired private MigracionServidorService migracion;

    @TempDir Path temporal;

    /** Archivo SQLite con el esquema actual y unas filas (fechas como las guarda el programa) */
    private Path archivoSqliteDeMuestra() throws Exception {
        Path bd = temporal.resolve("datos.db");
        Map<String, Object> ajustes = new HashMap<>();
        ajustes.put(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:sqlite:" + bd);
        ajustes.put(AvailableSettings.JAKARTA_JDBC_DRIVER, PerfilBaseDatos.SQLITE.driver());
        ajustes.put(AvailableSettings.DIALECT, PerfilBaseDatos.SQLITE.dialecto());
        MigracionServidorService.generarEsquema(ajustes, migracion.clasesDelModelo());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + bd); Statement st = c.createStatement()) {
            st.execute("INSERT INTO configuracion (id, nombre_tienda, tasa_iva, plazo_cancelacion_horas, folio_prefijo, respaldo_auto) "
                    + "VALUES (1, 'Tienda migrada', 0.16, 48, 'A-', 1)");
            st.execute("INSERT INTO cajeros (id, usuario, nombre_completo, contrasena_hash, rol, activo, debe_cambiar_contrasena) "
                    + "VALUES (1, 'admin', 'Administrador', '$2a$10$abcdefghijklmnopqrstuv', 'ADMIN', 1, 0)");
            st.execute("INSERT INTO cajero_permisos (cajero_id, permiso) VALUES (1, 'VER_GANANCIAS')");
            st.execute("INSERT INTO departamentos (id, nombre) VALUES (1, 'Abarrotes')");
            st.execute("INSERT INTO productos (id, codigo_barras, descripcion, tipo_venta, precio_costo, precio_venta, precio_mayoreo, "
                    + "departamento_id, activo, usa_inventario, cantidad_actual, cantidad_minima) "
                    + "VALUES (1, '7501', 'Refresco', 'UNIDAD', 10, 18, 16, 1, 1, 1, 24.5, 6)");
            st.execute("INSERT INTO productos (id, codigo_barras, descripcion, tipo_venta, precio_costo, precio_venta, precio_mayoreo, "
                    + "activo, usa_inventario, cantidad_actual, cantidad_minima) "
                    + "VALUES (2, '7502', 'Jamón', 'GRANEL', 80, 120, 0, 1, 1, 3.25, 1)");
            // fechas como las guarda sqlite-jdbc (milisegundos) y también en texto, por si el archivo viene de otra versión
            long ahora = System.currentTimeMillis();
            st.execute("INSERT INTO ventas (id, fecha, forma_pago, folio, total, pago_con, cambio, cajero_id, cajero_nombre, cancelada) "
                    + "VALUES (1, " + ahora + ", 'EFECTIVO', 'A-1', 36, 50, 14, 1, 'Administrador', 0)");
            st.execute("INSERT INTO ventas (id, fecha, forma_pago, folio, total, pago_con, cambio, cajero_id, cajero_nombre, cancelada) "
                    + "VALUES (2, '2026-01-15 10:30:00', 'TARJETA', 'A-2', 120, 120, 0, 1, 'Administrador', 1)");
            st.execute("INSERT INTO detalle_ventas (id, venta_id, producto_id, cantidad, precio_venta, descuento, mayoreo, tipo_descuento) "
                    + "VALUES (1, 1, 1, 2, 18, 0, 0, 'NINGUNO')");
            st.execute("INSERT INTO pagos_venta (id, venta_id, forma_pago, monto) VALUES (1, 1, 'EFECTIVO', 50)");
            st.execute("INSERT INTO fondos_caja (id, fecha, cajero_id, cajero_nombre, monto, registrado_en) "
                    + "VALUES (1, " + ahora + ", 1, 'Administrador', 500, " + ahora + ")");
            st.execute("INSERT INTO auditoria (id, fecha, usuario_login, accion, detalle) VALUES (1, " + ahora + ", 'admin', 'PRUEBA', 'migración')");
            // foto de producto: blob de SQLite → bytea de PostgreSQL
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO producto_imagen (producto_id, imagen, miniatura, mime, sha256, ancho, alto, actualizado) "
                    + "VALUES (1, ?, ?, 'image/jpeg', 'abc', 2, 1, " + ahora + ")")) {
                ps.setBytes(1, IMAGEN_MUESTRA);
                ps.setBytes(2, new byte[] {9, 8, 7});
                ps.executeUpdate();
            }
        }
        return bd;
    }

    /** Bytes "raros" (0x00, 0xFF, 0x27) para comprobar que el blob viaja intacto */
    private static final byte[] IMAGEN_MUESTRA = {(byte) 0xFF, (byte) 0xD8, 0, 0x27, (byte) 0x80, 0x7F, 0, (byte) 0xD9};

    private static long contar(String url, String usuario, String clave, String tabla) throws Exception {
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + tabla)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void copiaTodasLasTablasAUnaBaseVaciaYSeRehusaSiNoLoEsta() throws Exception {
        Path bd = archivoSqliteDeMuestra();
        byte[] original = Files.readAllBytes(bd);
        String base = "migracion_" + sufijo();
        PostgresEmbebido.crearBase(base);
        ConexionLocal destino = PostgresEmbebido.conexion(base);
        List<MigracionServidorService.Progreso> avances = new ArrayList<>();

        MigracionServidorService.Resultado r = migracion.copiar("jdbc:sqlite:" + bd, destino, avances::add);

        assertThat(r.tablas()).isGreaterThanOrEqualTo(20);
        assertThat(r.filas()).isEqualTo(13);
        assertThat(r.omitidas()).isEmpty();
        assertThat(avances).isNotEmpty();
        assertThat(avances.get(avances.size() - 1).mensaje()).contains("terminada");
        assertThat(avances).anyMatch(p -> p.tabla().equals("ventas") && p.filas() == 2);

        String url = PostgresEmbebido.url(base);
        assertThat(contar(url, PostgresEmbebido.USUARIO, PostgresEmbebido.CONTRASENA, "ventas")).isEqualTo(2);
        assertThat(contar(url, PostgresEmbebido.USUARIO, PostgresEmbebido.CONTRASENA, "productos")).isEqualTo(2);
        assertThat(contar(url, PostgresEmbebido.USUARIO, PostgresEmbebido.CONTRASENA, "cajero_permisos")).isEqualTo(1);
        try (Connection c = DriverManager.getConnection(url, PostgresEmbebido.USUARIO, PostgresEmbebido.CONTRASENA);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT cancelada, folio, total, fecha::date = current_date FROM ventas WHERE id = 1")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse();
                assertThat(rs.getString(2)).isEqualTo("A-1");
                assertThat(rs.getDouble(3)).isEqualTo(36);
                assertThat(rs.getBoolean(4)).isTrue();
            }
            try (ResultSet rs = st.executeQuery("SELECT cancelada, fecha FROM ventas WHERE id = 2")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getTimestamp(2).toLocalDateTime().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 1, 15));
            }
            try (ResultSet rs = st.executeQuery("SELECT cantidad_actual, activo, usa_inventario FROM productos WHERE id = 2")) {
                rs.next();
                assertThat(rs.getDouble(1)).isEqualTo(3.25);
                assertThat(rs.getBoolean(2)).isTrue();
                assertThat(rs.getBoolean(3)).isTrue();
            }
            try (ResultSet rs = st.executeQuery("SELECT fecha = current_date FROM fondos_caja WHERE id = 1")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
            try (ResultSet rs = st.executeQuery("SELECT imagen, miniatura, mime FROM producto_imagen WHERE producto_id = 1")) {
                rs.next();
                assertThat(rs.getBytes(1)).isEqualTo(IMAGEN_MUESTRA);
                assertThat(rs.getBytes(2)).isEqualTo(new byte[] {9, 8, 7});
                assertThat(rs.getString(3)).isEqualTo("image/jpeg");
            }
            // Las secuencias siguen después del mayor id copiado: la próxima venta no choca con las migradas
            st.execute("INSERT INTO ventas (fecha, forma_pago, total, pago_con, cambio) VALUES (now(), 'EFECTIVO', 1, 1, 0)");
            try (ResultSet rs = st.executeQuery("SELECT max(id) FROM ventas")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(3);
            }
        }
        // El archivo SQLite no se tocó
        assertThat(Files.readAllBytes(bd)).isEqualTo(original);

        // Ya no está vacía: se rehúsa
        assertThatThrownBy(() -> migracion.copiar("jdbc:sqlite:" + bd, destino, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("NO está vacía");
        assertThat(contar(url, PostgresEmbebido.USUARIO, PostgresEmbebido.CONTRASENA, "ventas")).isEqualTo(3);
    }

    @Test
    void conDatosDelServidorIncorrectosAvisaSinTocarNada() throws Exception {
        Path bd = archivoSqliteDeMuestra();
        ConexionLocal destino = PostgresEmbebido.conexion("base_que_no_existe_" + sufijo());
        assertThatThrownBy(() -> migracion.copiar("jdbc:sqlite:" + bd, destino, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("No se pudo migrar");
        destino.setContrasena("mala");
        destino.setBaseDatos(PostgresEmbebido.BASE_APP);
        assertThatThrownBy(() -> migracion.copiar("jdbc:sqlite:" + bd, destino, null))
                .isInstanceOf(NegocioException.class);
        // El asistente completo exige ADMIN y que el programa trabaje con SQLite (aquí corre contra el servidor)
        iniciarComoAdmin();
        assertThatThrownBy(() -> migracion.migrarYCambiarModo(destino, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya trabaja contra el servidor");
    }
}
