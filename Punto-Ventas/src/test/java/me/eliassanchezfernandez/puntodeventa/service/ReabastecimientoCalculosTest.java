package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.NivelDatos;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.MadurezDatos;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.LineaSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.Urgencia;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Sin Spring: las fórmulas de surtido y de rezago son funciones puras. */
class ReabastecimientoCalculosTest {

    private static Producto producto(double existencia, double minimo, double costo, Producto.TipoVenta tipo) {
        Producto p = new Producto();
        p.setDescripcion("Prueba");
        p.setCantidadActual(existencia);
        p.setCantidadMinima(minimo);
        p.setPrecioCosto(costo);
        p.setPrecioVenta(costo * 2);
        p.setTipoVenta(tipo);
        p.setUsaInventario(true);
        return p;
    }

    @Test
    void agotadoPideHastaElObjetivoYCalculaFrecuencia() {
        // 30 piezas en 30 días = 1 al día; mínimo 5; horizonte 7 → objetivo = max(10, 5 + 7) = 12
        LineaSurtido l = ReabastecimientoService.calcularLinea(producto(0, 5, 10, Producto.TipoVenta.UNIDAD), 30, 30, 7);
        assertThat(l.ventaDiaria()).isCloseTo(1.0, within(0.001));
        assertThat(l.urgencia()).isEqualTo(Urgencia.AGOTADO);
        assertThat(l.pedido()).isEqualTo(12);
        assertThat(l.frecuenciaDias()).isEqualTo(7);          // (12 − 5) ÷ 1
        assertThat(l.diasCobertura()).isCloseTo(0, within(0.001));
        assertThat(l.costoPedido()).isCloseTo(120, within(0.001));
        assertThat(l.motivo()).contains("Agotado");
    }

    @Test
    void bajoElMinimoSinVentasPideElDobleDelMinimo() {
        LineaSurtido l = ReabastecimientoService.calcularLinea(producto(3, 5, 10, Producto.TipoVenta.UNIDAD), 0, 30, 7);
        assertThat(l.urgencia()).isEqualTo(Urgencia.BAJO_MINIMO);
        assertThat(l.ventaDiaria()).isZero();
        assertThat(l.diasCobertura()).isNull();
        assertThat(l.frecuenciaDias()).isNull();
        assertThat(l.pedido()).isEqualTo(7);                 // 10 − 3
        assertThat(l.motivo()).contains("Existencia 3").contains("mínimo 5");
    }

    @Test
    void seAcabaAntesDelProximoSurtidoCuandoLaCoberturaNoLlegaAlHorizonte() {
        // 2 al día, existencia 6 (alcanza 3 días), mínimo 5, próximo surtido en 4 días → objetivo = max(10, 5 + 8) = 13
        LineaSurtido l = ReabastecimientoService.calcularLinea(producto(6, 5, 20, Producto.TipoVenta.UNIDAD), 20, 10, 4);
        assertThat(l.ventaDiaria()).isCloseTo(2.0, within(0.001));
        assertThat(l.diasCobertura()).isCloseTo(3.0, within(0.001));
        assertThat(l.urgencia()).isEqualTo(Urgencia.PRONTO);
        assertThat(l.pedido()).isEqualTo(7);                 // 13 − 6
        assertThat(l.frecuenciaDias()).isEqualTo(4);         // (13 − 5) ÷ 2
        assertThat(l.hayQuePedir()).isTrue();
    }

    @Test
    void conExistenciaSuficienteNoSePideNada() {
        // 1 al día, existencia 50 → alcanza 50 días; mínimo 5; horizonte 7 → objetivo 12, surtir cada 7
        LineaSurtido l = ReabastecimientoService.calcularLinea(producto(50, 5, 10, Producto.TipoVenta.UNIDAD), 30, 30, 7);
        assertThat(l.urgencia()).isEqualTo(Urgencia.OK);
        assertThat(l.pedido()).isZero();
        assertThat(l.hayQuePedir()).isFalse();
        assertThat(l.diasCobertura()).isCloseTo(50, within(0.001));
        assertThat(l.frecuenciaDias()).isEqualTo(7);
        assertThat(l.motivo()).contains("Alcanza para 50");
    }

    @Test
    void sinMinimoNiVentasNoHayNadaQuePlanear() {
        LineaSurtido l = ReabastecimientoService.calcularLinea(producto(0, 0, 10, Producto.TipoVenta.UNIDAD), 0, 30, 7);
        assertThat(l.urgencia()).isEqualTo(Urgencia.OK);
        assertThat(l.pedido()).isZero();
    }

    @Test
    void elPedidoRespetaLosDecimalesDelProducto() {
        // Unidad: 6.4 piezas → 7 enteras. Objetivo = max(2·3, 3 + 0.34·10 = 6.4): existencia 0
        LineaSurtido unidad = ReabastecimientoService.calcularLinea(producto(0, 3, 1, Producto.TipoVenta.UNIDAD), 10.2, 30, 10);
        assertThat(unidad.pedido()).isEqualTo(7);
        // Granel: se conservan milésimas (venta diaria redondeada a 0.333). Objetivo = max(4, 2 + 0.333·7 = 4.331) → 4.331 − 1.5
        LineaSurtido granel = ReabastecimientoService.calcularLinea(producto(1.5, 2, 1, Producto.TipoVenta.GRANEL), 10, 30, 7);
        assertThat(granel.urgencia()).isEqualTo(Urgencia.BAJO_MINIMO);
        assertThat(granel.pedido()).isCloseTo(2.831, within(0.001));
    }

    @Test
    void laVentaDiariaNuncaDivideEntreCeroDias() {
        LineaSurtido l = ReabastecimientoService.calcularLinea(producto(0, 1, 1, Producto.TipoVenta.UNIDAD), 5, 0, 0);
        assertThat(l.ventaDiaria()).isCloseTo(5, within(0.001));
        assertThat(l.frecuenciaDias()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void elHorizonteSaleDeLosDiasDeVisitaDelProveedor() {
        assertThat(ReabastecimientoService.horizonteDe(null)).isEqualTo(ReabastecimientoService.HORIZONTE_DIAS);
        Proveedor sinDias = new Proveedor();
        assertThat(ReabastecimientoService.horizonteDe(sinDias)).isEqualTo(7);
        Proveedor dosVisitas = new Proveedor();
        dosVisitas.setDias(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY));
        assertThat(ReabastecimientoService.horizonteDe(dosVisitas)).isEqualTo(4);
        Proveedor diario = new Proveedor();
        diario.setDias(EnumSet.allOf(DayOfWeek.class));
        assertThat(ReabastecimientoService.horizonteDe(diario)).isEqualTo(1);
        Proveedor tres = new Proveedor();
        tres.setDias(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY));
        assertThat(ReabastecimientoService.horizonteDe(tres)).isEqualTo(3);
    }

    @Test
    void elMotivoDeRezagoDistingueSinVentaYRotacionBaja() {
        LocalDate hace40 = LocalDate.now().minusDays(40);
        assertThat(ReabastecimientoService.motivoRezago(hace40, 40, 30, 5.0, true)).contains("hace 40 días");
        assertThat(ReabastecimientoService.motivoRezago(null, 45, 30, null, true)).contains("Sin ventas registradas en 45");
        assertThat(ReabastecimientoService.motivoRezago(LocalDate.now(), 0, 30, 120.0, true)).contains("Rotación baja").contains("120");
        assertThat(ReabastecimientoService.motivoRezago(LocalDate.now(), 0, 30, 89.0, true)).isNull();
        assertThat(ReabastecimientoService.motivoRezago(LocalDate.now().minusDays(10), 10, 30, null, true)).isNull();
        assertThat(ReabastecimientoService.motivoRezago(null, 3, 30, null, true)).isNull();   // nunca vendido pero la tienda es nueva
        assertThat(ReabastecimientoService.motivoRezago(null, 100, 30, null, false)).isNull(); // sin ventas en la tienda: nada que juzgar
    }

    @Test
    void laVentanaEfectivaNoPasaDeLosDiasQueRealmenteHay() {
        assertThat(ReabastecimientoService.diasVentanaEfectiva(MadurezDatos.SIN_DATOS)).isEqualTo(30);
        assertThat(ReabastecimientoService.diasVentanaEfectiva(new MadurezDatos(0, 1, NivelDatos.ENTRENAMIENTO))).isEqualTo(1);
        assertThat(ReabastecimientoService.diasVentanaEfectiva(new MadurezDatos(9, 8, NivelDatos.PARCIAL))).isEqualTo(10);
        assertThat(ReabastecimientoService.diasVentanaEfectiva(new MadurezDatos(100, 90, NivelDatos.SUFICIENTE))).isEqualTo(30);
    }
}
