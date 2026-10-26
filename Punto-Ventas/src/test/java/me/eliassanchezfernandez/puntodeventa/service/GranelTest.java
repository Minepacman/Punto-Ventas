package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Productos a granel con cantidades decimales (PRD-08) en carrito, venta, stock y movimientos. */
class GranelTest extends BaseServicioTest {

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    @Test
    void unProductoAGranelSeVendeConDecimalesYDescuentaDecimales() {
        Producto jamon = producto("Jamón", 120, 10, Producto.TipoVenta.GRANEL, null);   // $120/kg

        DetalleVenta d = ventaService.crearDetalle(jamon, 0.75);
        assertThat(d.getImporte()).isCloseTo(90, within(0.001));

        Venta v = ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 100);
        assertThat(v.getTotal()).isCloseTo(90, within(0.001));
        assertThat(v.getDetalles().get(0).getCantidad()).isEqualTo(0.75);
        assertThat(stockActual(jamon)).isCloseTo(9.25, within(0.0001));

        assertThat(productoService.movimientosDe(jamon.getId()).get(0).getCantidad()).isCloseTo(-0.75, within(0.0001));

        productoService.registrarEntrada(jamon.getId(), 2.5, "Carnes del Valle", 80, null);
        assertThat(stockActual(jamon)).isCloseTo(11.75, within(0.0001));
        productoService.ajustarStock(jamon.getId(), 11.2, "Conteo", null);
        assertThat(stockActual(jamon)).isCloseTo(11.2, within(0.0001));
    }

    @Test
    void unProductoPorPiezaNoAceptaDecimales() {
        Producto refresco = producto("Refresco", 18, 10);

        assertThatThrownBy(() -> ventaService.crearDetalle(refresco, 1.5))
                .isInstanceOf(NegocioException.class).hasMessageContaining("entero");
        assertThatThrownBy(() -> ventaService.cobrar(List.of(detalleCrudo(refresco, 2.5, 18, 0)),
                Venta.FormaPago.EFECTIVO, 100))
                .isInstanceOf(NegocioException.class).hasMessageContaining("entero");
        assertThatThrownBy(() -> productoService.registrarEntrada(refresco.getId(), 1.5, "Prov", 10, null))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void lasPromocionesTambienAplicanACantidadesDecimales() {
        Producto queso = producto("Queso", 200, 10, Producto.TipoVenta.GRANEL, null);
        promocion(queso, Promocion.Tipo.PORCENTAJE, 10);

        DetalleVenta d = ventaService.crearDetalle(queso, 1.5);
        assertThat(d.getDescuento()).isCloseTo(30, within(0.001));
        assertThat(d.getImporte()).isCloseTo(270, within(0.001));

        // Stock insuficiente también se valida con decimales
        assertThatThrownBy(() -> ventaService.crearDetalle(queso, 10.001))
                .isInstanceOf(StockInsuficienteException.class);
    }
}
