package me.eliassanchezfernandez.puntodeventa.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La migración reconstruye las tablas de una BD creada por la versión
 * anterior (restricciones CHECK de enums y NOT NULL) conservando las filas.
 * Usa un archivo SQLite temporal independiente.
 */
class MigracionSqliteTest {

    private Path archivo;

    @AfterEach
    void limpiar() throws Exception {
        if (archivo == null) return;
        File dir = archivo.getParent().toFile();
        File[] restos = dir.listFiles((d, n) -> n.startsWith(archivo.getFileName().toString()));
        if (restos != null) for (File f : restos) Files.deleteIfExists(f.toPath());
    }

    @Test
    void reconstruyeLasTablasAntiguasSinPerderDatos() throws Exception {
        archivo = Files.createTempFile("pos-legacy-", ".db");
        Files.deleteIfExists(archivo);
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + archivo);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE cajeros (id integer, activo boolean not null, contrasena_hash varchar(255) not null, "
                    + "nombre_completo varchar(150) not null, rol varchar(255) not null check (rol in ('ADMIN','CAJERO')), "
                    + "usuario varchar(60) not null unique, primary key (id))");
            st.execute("INSERT INTO cajeros VALUES (1, 1, 'hash', 'Administrador', 'ADMIN', 'admin')");
            st.execute("CREATE TABLE promociones (id integer, activa boolean not null, descripcion varchar(150), fecha_fin date, "
                    + "fecha_inicio date not null, tipo varchar(255) not null check (tipo in ('PORCENTAJE','PRECIO_FIJO')), "
                    + "valor float not null, producto_id bigint not null, primary key (id))");
            st.execute("INSERT INTO promociones VALUES (7, 1, 'Vieja', NULL, '2026-01-01', 'PORCENTAJE', 10, 3)");
            st.execute("CREATE TABLE ventas (id integer, cajero_nombre varchar(100), cambio float not null, fecha timestamp not null, "
                    + "forma_pago varchar(255) check (forma_pago in ('EFECTIVO','TARJETA','CREDITO')), pago_con float not null, "
                    + "total float not null, cajero_id bigint, primary key (id))");
            st.execute("INSERT INTO ventas VALUES (1, 'Administrador', 0, '2026-10-01 10:00:00', 'EFECTIVO', 50, 50, 1)");
            st.execute("CREATE TABLE detalle_ventas (id integer, cantidad integer not null, descuento float not null, "
                    + "precio_venta float, producto_id bigint not null, venta_id bigint not null, primary key (id))");
            st.execute("INSERT INTO detalle_ventas VALUES (1, 2, 0, 10, 3, 1)");
        }

        List<String> migradas = MigracionSqlite.migrar(ds);

        assertThat(migradas).containsExactlyInAnyOrder("cajeros", "promociones", "ventas", "detalle_ventas");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(MigracionSqlite.sqlDeTabla(c, "cajeros")).doesNotContainIgnoringCase("check");
            assertThat(MigracionSqlite.sqlDeTabla(c, "cajeros")).containsIgnoringCase("unique");
            assertThat(MigracionSqlite.sqlDeTabla(c, "promociones")).doesNotContainIgnoringCase("check")
                    .doesNotContainIgnoringCase("producto_id bigint not null");
            assertThat(MigracionSqlite.sqlDeTabla(c, "detalle_ventas")).containsIgnoringCase("cantidad float");
            assertThat(MigracionSqlite.sqlDeTabla(c, "ventas")).doesNotContainIgnoringCase("check");
            try (ResultSet rs = st.executeQuery("SELECT total, forma_pago FROM ventas WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble(1)).isEqualTo(50);
                assertThat(rs.getString(2)).isEqualTo("EFECTIVO");
            }
            st.execute("INSERT INTO ventas VALUES (2, 'Administrador', 0, '2026-10-02 10:00:00', 'TRANSFERENCIA', 10, 10, 1)");

            // Filas intactas
            try (ResultSet rs = st.executeQuery("SELECT usuario, rol FROM cajeros")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("admin");
                assertThat(rs.getString(2)).isEqualTo("ADMIN");
            }
            try (ResultSet rs = st.executeQuery("SELECT descripcion, producto_id FROM promociones WHERE id = 7")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Vieja");
                assertThat(rs.getLong(2)).isEqualTo(3);
            }
            // Lo que antes fallaba ahora entra
            st.execute("INSERT INTO cajeros VALUES (2, 1, 'h', 'Gerente', 'GERENTE', 'gerente')");
            st.execute("INSERT INTO promociones (id, activa, fecha_inicio, tipo, valor, producto_id) VALUES (8, 1, '2026-01-01', 'NXM', 0, NULL)");
            st.execute("INSERT INTO detalle_ventas VALUES (2, 1.5, 0, 10, 3, 1)");
            try (ResultSet rs = st.executeQuery("SELECT cantidad FROM detalle_ventas WHERE id = 2")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble(1)).isEqualTo(1.5);
            }
        }

        // Respaldo creado junto al archivo
        File[] respaldos = archivo.getParent().toFile().listFiles((d, n) ->
                n.startsWith(archivo.getFileName().toString() + ".respaldo-migracion-"));
        assertThat(respaldos).isNotEmpty();

        // Segunda corrida: nada que hacer
        assertThat(MigracionSqlite.migrar(ds)).isEmpty();
    }

    @Test
    void enUnaBaseNuevaNoHaceNada() throws Exception {
        archivo = Files.createTempFile("pos-nueva-", ".db");
        Files.deleteIfExists(archivo);
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + archivo);
        assertThat(MigracionSqlite.migrar(ds)).isEmpty();
    }
}
