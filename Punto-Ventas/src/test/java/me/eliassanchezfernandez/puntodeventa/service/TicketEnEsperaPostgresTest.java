package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.postgres.BasePostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tickets en espera con varias cajas sobre PostgreSQL: columnas nuevas creadas
 * por ddl-auto=update, filas antiguas (en_espera NULL) que siguen siendo
 * autoguardados, aislamiento por caja y que cobrar no los borre.
 * (Está en el paquete service para poder cambiar de caja en la prueba.)
 */
class TicketEnEsperaPostgresTest extends BasePostgresTest {

    @Autowired private TicketPendienteService ticketPendienteService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void volverASerLaCajaUno() {
        identidadCaja.fijarParaPruebas(1, "Caja 1", true);
    }

    @Test
    void lasColumnasNuevasExistenConElTipoCorrecto() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'tickets_pendientes' AND column_name IN ('en_espera', 'nota')");
        assertThat(cols).hasSize(2);
        Map<String, Object> enEspera = cols.stream().filter(c -> "en_espera".equals(c.get("column_name"))).findFirst().orElseThrow();
        assertThat(enEspera.get("data_type")).isEqualTo("boolean");
        assertThat(enEspera.get("is_nullable")).isEqualTo("YES");   // las filas de antes quedan en NULL
        Map<String, Object> nota = cols.stream().filter(c -> "nota".equals(c.get("column_name"))).findFirst().orElseThrow();
        assertThat(((Number) nota.get("character_maximum_length")).intValue()).isEqualTo(60);
    }

    @Test
    void aisladosPorCajaYCobrarNoLosBorra() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Frijol PG", 30, 50);

        identidadCaja.fijarParaPruebas(1, "Caja 1", true);
        int antesUno = ticketPendienteService.contarEnEspera();
        var uno = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 2)), "Sra. Lupita");

        identidadCaja.fijarParaPruebas(2, "Caja 2", true);
        int antesDos = ticketPendienteService.contarEnEspera();
        var dos = ticketPendienteService.ponerEnEspera(List.of(ventaService.crearDetalle(p, 1)), null);
        assertThat(ticketPendienteService.listarEnEspera()).extracting(TicketPendienteService.EnEspera::id)
                .contains(dos.id()).doesNotContain(uno.id());
        assertThatThrownBy(() -> ticketPendienteService.recuperarEnEspera(uno.id())).isInstanceOf(NegocioException.class);

        // Cobrar en la caja 2 no toca ningún ticket en espera
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
        assertThat(v.getCaja()).isEqualTo(2);
        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(antesDos + 1);
        assertThat(stockActual(p)).isEqualTo(49);   // apartar no descuenta inventario

        identidadCaja.fijarParaPruebas(1, "Caja 1", true);
        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(antesUno + 1);
        assertThat(ticketPendienteService.recuperarEnEspera(uno.id())).singleElement()
                .satisfies(d -> assertThat(d.getCantidad()).isEqualTo(2));

        identidadCaja.fijarParaPruebas(2, "Caja 2", true);
        ticketPendienteService.descartarEnEspera(dos.id());
        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(antesDos);
    }

    @Test
    void unaFilaAntiguaConEnEsperaNuloSigueSiendoElAutoguardado() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Arroz PG", 25, 20);
        identidadCaja.fijarParaPruebas(1, "Caja 1", true);
        ticketPendienteService.guardar(List.of(ventaService.crearDetalle(p, 3)));
        // Como quedaría una fila creada antes de esta versión (sin las columnas nuevas)
        jdbc.update("UPDATE tickets_pendientes SET en_espera = NULL, nota = NULL WHERE cajero_id = ? AND caja = 1",
                sesionService.getCajeroActual().getId());
        int enEspera = ticketPendienteService.contarEnEspera();

        assertThat(ticketPendienteService.recuperar()).singleElement()
                .satisfies(d -> assertThat(d.getCantidad()).isEqualTo(3));
        assertThat(ticketPendienteService.listarEnEspera()).noneMatch(t -> t.renglones() == 1 && t.piezas() == 3 && t.nota() == null
                && t.detalles().get(0).getProducto().getId().equals(p.getId()));
        ticketPendienteService.descartarDelCajeroActual();
        assertThat(ticketPendienteService.hayPendiente()).isFalse();
        assertThat(ticketPendienteService.contarEnEspera()).isEqualTo(enEspera);
    }
}
