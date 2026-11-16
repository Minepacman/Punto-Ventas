package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.ConsultasMovilService;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService;
import me.eliassanchezfernandez.puntodeventa.service.SesionMovilService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Las consultas JPQL del móvil (páginas, agregados, LIKE con ESCAPE, ORDER BY CASE) también corren en PostgreSQL */
class ConsultasMovilPostgresTest extends BasePostgresTest {

    @Autowired private ConsultasMovilService consultas;
    @Autowired private SesionMovilService sesionMovilService;

    @Test
    void ventasInventarioEstadisticasYValidacionEnPostgres() throws Exception {
        Cajero admin = iniciarComoAdmin();
        String marca = "pgm" + sufijo();
        Producto p = new Producto();
        p.setCodigoBarras(marca);
        p.setDescripcion(marca + " 50%_off");
        p.setPrecioCosto(4);
        p.setPrecioVenta(10);
        p.setUsaInventario(true);
        p.setCantidadActual(100);
        p.setCantidadMinima(1);
        p = productoService.guardar(p);
        Venta v1 = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), Venta.FormaPago.EFECTIVO, 100);
        Venta v2 = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
        LocalDate hoy = LocalDate.now();

        var pag = consultas.ventas(hoy, hoy, 0, 1);
        assertThat(pag.ventas()).extracting(Venta::getId).containsExactly(v2.getId());
        assertThat(pag.hayMas()).isTrue();
        assertThat(pag.ventasEnRango()).isGreaterThanOrEqualTo(2);
        assertThat(pag.importe()).isGreaterThanOrEqualTo(v1.getTotal() + v2.getTotal());
        assertThat(consultas.ventas(hoy, hoy, 1, 1).ventas()).extracting(Venta::getId).doesNotContain(v2.getId());

        var inv = consultas.inventario(marca.toUpperCase() + " 50%_", 10);
        assertThat(inv.total()).isEqualTo(1);
        assertThat(inv.productos()).extracting(Producto::getId).containsExactly(p.getId());
        assertThat(consultas.inventario(marca + "%", 10).total()).isZero();

        var est = consultas.estadisticas(EstadisticasService.Periodo.dia(hoy), "Hoy", true);
        assertThat(est.totales().transacciones()).isGreaterThanOrEqualTo(2);
        assertThat(est.comparacion()).isPresent();
        assertThat(est.ganancia()).isGreaterThanOrEqualTo(18.0);      // (10 − 4) × 3

        var emitida = sesionMovilService.emitir(admin, false, "127.0.0.1", "prueba");
        assertThat(sesionMovilService.validar(emitida.token())).isPresent();
        assertThat(sesionMovilService.validar("no-existe")).isEmpty();
    }
}
