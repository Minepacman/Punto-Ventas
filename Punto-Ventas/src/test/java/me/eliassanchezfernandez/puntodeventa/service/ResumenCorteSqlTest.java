package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * El corte se suma en SQL; aquí se compara contra la suma "a la antigua"
 * (recorriendo cada ticket y renglón) con un día variado: pago dividido con
 * cambio, tarjeta, cancelada, descuento y productos de distintos departamentos.
 */
class ResumenCorteSqlTest extends BaseServicioTest {

    @BeforeEach
    void entrar() throws Exception {
        iniciarComoAdmin();
    }

    @Test
    void elResumenEnSqlCoincideConLaSumaTicketPorTicket() {
        Departamento bebidas = departamento("Bebidas");
        Departamento botanas = departamento("Botanas");
        Producto cola = producto("Cola", 20, 100, Producto.TipoVenta.UNIDAD, bebidas);
        Producto papas = producto("Papas", 35, 100, Producto.TipoVenta.UNIDAD, botanas);
        Producto suelto = producto("Sin depto", 12, 100);

        VentaService.ResumenCorte antes = ventaService.resumenDelDia();

        ventaService.cobrar(List.of(ventaService.crearDetalle(cola, 3), ventaService.crearDetalle(papas, 2)),
                Venta.FormaPago.EFECTIVO, 200);
        ventaService.cobrar(List.of(ventaService.crearDetalle(papas, 1), ventaService.crearDetalle(suelto, 4)), List.of(
                new PagoVenta(Venta.FormaPago.EFECTIVO, 50),
                new PagoVenta(Venta.FormaPago.TARJETA, 40)), null);
        Venta cancelar = ventaService.cobrar(List.of(ventaService.crearDetalle(cola, 5)), Venta.FormaPago.TARJETA, 100);
        ventaService.cancelar(cancelar.getId(), "prueba", null);

        VentaService.ResumenCorte sql = ventaService.resumenDelDia();
        VentaService.ResumenCorte clasico = clasico(LocalDate.now());

        assertThat(sql.numVentas).isEqualTo(clasico.numVentas);
        assertThat(sql.numCanceladas).isEqualTo(clasico.numCanceladas);
        assertThat(sql.ventasTotales).isCloseTo(clasico.ventasTotales, within(0.005));
        assertThat(sql.subtotal).isCloseTo(clasico.subtotal, within(0.005));
        assertThat(sql.iva).isCloseTo(clasico.iva, within(0.005));
        assertThat(sql.efectivo).isCloseTo(clasico.efectivo, within(0.005));
        assertThat(sql.tarjeta).isCloseTo(clasico.tarjeta, within(0.005));
        assertThat(sql.totalCanceladas).isCloseTo(clasico.totalCanceladas, within(0.005));
        assertThat(sql.ganancia).isCloseTo(clasico.ganancia, within(0.005));
        assertThat(sql.porDepartamento.keySet()).containsExactlyInAnyOrderElementsOf(clasico.porDepartamento.keySet());
        clasico.porDepartamento.forEach((k, v) -> assertThat(sql.porDepartamento.get(k)).as(k).isCloseTo(v, within(0.005)));

        // y el aumento respecto de antes es el de estas ventas: 130 de la primera; 50 − 7 de cambio y 40 de tarjeta en la segunda
        assertThat(sql.numVentas - antes.numVentas).isEqualTo(2);
        assertThat(sql.efectivo - antes.efectivo).isCloseTo(130 + 50 - 7, within(0.01));
        assertThat(sql.tarjeta - antes.tarjeta).isCloseTo(40, within(0.01));
    }

    /** Lo que hacía el corte antes: cargar tickets y recorrer renglones */
    private VentaService.ResumenCorte clasico(LocalDate dia) {
        VentaService.ResumenCorte r = new VentaService.ResumenCorte();
        Map<String, Double> deptos = new LinkedHashMap<>();
        for (Venta v : ventaService.ventasEntre(dia, dia)) {
            if (v.isCancelada()) {
                r.numCanceladas++;
                r.totalCanceladas += v.getTotal();
                continue;
            }
            r.numVentas++;
            r.ventasTotales += v.getTotal();
            r.subtotal += v.getSubtotalODefault();
            r.iva += v.getIvaODefault();
            if (v.getPagos().isEmpty()) {
                if (v.getFormaPago() == Venta.FormaPago.TARJETA) r.tarjeta += v.getTotal(); else r.efectivo += v.getTotal();
            } else {
                for (PagoVenta p : v.getPagos()) {
                    double monto = p.getMonto();
                    if (p.getFormaPago() == Venta.FormaPago.EFECTIVO) monto -= v.getCambio();
                    if (p.getFormaPago() == Venta.FormaPago.TARJETA) r.tarjeta += monto; else if (p.getFormaPago() == Venta.FormaPago.EFECTIVO) r.efectivo += monto;
                }
            }
            for (DetalleVenta d : v.getDetalles()) {
                r.ganancia += d.getImporte() - d.getCostoUnitarioODefault() * d.getCantidad();
                Departamento dep = d.getProducto().getDepartamento();
                deptos.merge(dep != null ? dep.getNombre() : "Sin departamento", d.getImporte(), Double::sum);
            }
        }
        r.porDepartamento.putAll(deptos);
        return r;
    }
}
