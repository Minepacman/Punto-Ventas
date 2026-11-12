package me.eliassanchezfernandez.puntodeventa.postgres;

import com.zaxxer.hikari.HikariDataSource;
import me.eliassanchezfernandez.puntodeventa.config.AjustesPoolPorDialecto;
import me.eliassanchezfernandez.puntodeventa.config.IndicesSqlite;
import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.BaseDatosService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El contexto arranca contra PostgreSQL: esquema, índices, pool, una venta completa y los respaldos deshabilitados. */
class EsquemaYVentaPostgresTest extends BasePostgresTest {

    @Autowired private DataSource dataSource;
    @Autowired private RutasApp rutas;
    @Autowired private BaseDatosService baseDatosService;

    @Test
    void elEsquemaSeCreaEnPostgresqlConLosIndices() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(PerfilBaseDatos.de(c)).isEqualTo(PerfilBaseDatos.POSTGRESQL);
            Set<String> tablas = new TreeSet<>();
            try (ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()")) {
                while (rs.next()) tablas.add(rs.getString(1));
            }
            assertThat(tablas).contains("ventas", "detalle_ventas", "productos", "cajeros", "cortes", "fondos_caja",
                    "tickets_pendientes", "movimientos_inventario", "configuracion");
            Set<String> columnas = new TreeSet<>();
            try (ResultSet rs = st.executeQuery("SELECT table_name || '.' || column_name FROM information_schema.columns "
                    + "WHERE column_name = 'caja' AND table_schema = current_schema()")) {
                while (rs.next()) columnas.add(rs.getString(1));
            }
            assertThat(columnas).contains("ventas.caja", "cortes.caja", "fondos_caja.caja", "tickets_pendientes.caja",
                    "movimientos_inventario.caja");
            Set<String> indices = new TreeSet<>();
            try (ResultSet rs = st.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()")) {
                while (rs.next()) indices.add(rs.getString(1));
            }
            for (String sql : IndicesSqlite.INDICES) {
                String nombre = sql.split(" ")[5];
                assertThat(indices).as(nombre).contains(nombre);
            }
        }
        HikariDataSource ds = (HikariDataSource) dataSource;
        assertThat(ds.getMaximumPoolSize()).isEqualTo(AjustesPoolPorDialecto.POOL_POSTGRESQL);
        assertThat(ds.getDataSourceProperties()).doesNotContainKey("journal_mode").containsKey("ApplicationName");
        assertThat(rutas.esSqlite()).isFalse();
        assertThat(identidadCaja.esMultiCaja()).isTrue();
    }

    @Test
    void unaVentaCompletaFuncionaYLlevaCajaYFolio() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Refresco PG", 18, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 3)), Venta.FormaPago.EFECTIVO, 100);
        assertThat(v.getId()).isNotNull();
        assertThat(v.getFolio()).isNotBlank();
        assertThat(v.getCaja()).isEqualTo(1);
        assertThat(v.getTotal()).isEqualTo(54);
        assertThat(stockActual(p)).isEqualTo(7);
        assertThat(productoService.movimientosDe(p.getId())).hasSize(2);   // alta inicial + venta
        assertThat(ventaService.resumenDelDia().numVentas).isGreaterThanOrEqualTo(1);

        Venta cancelada = ventaService.cancelar(v.getId(), "Prueba PG", null);
        assertThat(cancelada.isCancelada()).isTrue();
        assertThat(stockActual(p)).isEqualTo(10);
    }

    @Test
    void enModoServidorElRespaldoEsDelServidor() throws Exception {
        iniciarComoAdmin();
        BaseDatosService.Informacion info = baseDatosService.informacion();
        assertThat(info.esSqlite()).isFalse();
        assertThat(info.servidor()).startsWith("localhost:").contains(PostgresEmbebido.BASE_APP);
        assertThat(info.bytes()).isGreaterThan(0);
        assertThat(info.registros()).containsKeys("Productos", "Ventas");
        assertThatThrownBy(() -> baseDatosService.respaldar(Path.of("x.db")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("pg_dump");
        assertThat(baseDatosService.respaldoAutomaticoAlCerrar()).isEmpty();
        assertThat(baseDatosService.hayRestauracionPendiente()).isFalse();
        assertThat(baseDatosService.instruccionesRespaldoServidor()).contains("pg_dump -h localhost -p " + PostgresEmbebido.puerto());
    }
}
