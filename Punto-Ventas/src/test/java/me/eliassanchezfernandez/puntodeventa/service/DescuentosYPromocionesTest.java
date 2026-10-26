package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Exclusividad y precedencia de descuentos (PRO-09), promoción más reciente
 * (PRO-05), 2x1, por departamento, días/horario y "hasta agotar" (PRO-01/04/07).
 */
class DescuentosYPromocionesTest extends BaseServicioTest {

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    private Producto productoConMayoreo(double lista, double mayoreo, double costo) {
        Producto p = producto("Con mayoreo", lista, 100);
        p.setPrecioMayoreo(mayoreo);
        p.setPrecioCosto(costo);
        return productoService.guardar(p);
    }

    @Test
    void soloAplicaUnDescuentoConPrecedenciaManualMayoreoPromocion() {
        Producto p = productoConMayoreo(100, 80, 10);
        promocion(p, Promocion.Tipo.PORCENTAJE, 10);

        DetalleVenta d = ventaService.crearDetalle(p, 2);
        assertThat(d.getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.PROMOCION);
        assertThat(d.getImporte()).isCloseTo(180, within(0.001));

        ventaService.aplicarMayoreo(d, true, null);
        assertThat(d.getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.MAYOREO);
        assertThat(d.getPrecioVenta()).isEqualTo(80);
        assertThat(d.getDescuento()).isZero();                     // la promoción NO se suma al mayoreo
        assertThat(d.getImporte()).isCloseTo(160, within(0.001));

        ventaService.aplicarDescuentoManual(d, 50.0, null, null);
        assertThat(d.getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.MANUAL);
        assertThat(d.isMayoreo()).isFalse();                        // manual quita el mayoreo
        assertThat(d.getPrecioVenta()).isEqualTo(100);
        assertThat(d.getImporte()).isCloseTo(100, within(0.001));

        ventaService.aplicarMayoreo(d, true, null);                 // mayoreo quita el manual
        assertThat(d.tieneDescuentoManual()).isFalse();
        assertThat(d.getImporte()).isCloseTo(160, within(0.001));

        ventaService.aplicarDescuentoManual(d, null, 30.0, null);   // importe fijo
        assertThat(d.getImporte()).isCloseTo(170, within(0.001));

        ventaService.aplicarDescuentoManual(d, null, null, null);   // quitar → vuelve la promoción
        assertThat(d.getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.PROMOCION);

        // Al cobrar se recalcula igual y el ticket conserva el tipo
        ventaService.aplicarDescuentoManual(d, 50.0, null, null);
        Venta v = ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 100);
        assertThat(v.getTotal()).isCloseTo(100, within(0.001));
        assertThat(v.getDetalles().get(0).getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.MANUAL);
    }

    @Test
    void mayoreoYDescuentoManualExigenPermisoOAutorizacionYSeAuditan() throws Exception {
        Producto p = productoConMayoreo(100, 80, 10);
        iniciarComoCajero("cajeroA", EnumSet.noneOf(Permiso.class));
        DetalleVenta d = ventaService.crearDetalle(p, 1);

        assertThatThrownBy(() -> ventaService.aplicarMayoreo(d, true, null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> ventaService.aplicarDescuentoManual(d, 10.0, null, null))
                .isInstanceOf(PermisoRequeridoException.class);

        Autorizacion aut = new Autorizacion(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow(),
                Permiso.DESCUENTO_MANUAL, LocalDateTime.now());
        List<Autorizacion> auts = new ArrayList<>(List.of(aut));
        ventaService.aplicarDescuentoManual(d, 10.0, null, auts);
        assertThat(d.getImporte()).isCloseTo(90, within(0.001));

        // Un renglón marcado "a mano" como mayoreo sin permiso no pasa el cobro
        DetalleVenta tramposo = detalleCrudo(p, 1, 80, 0);
        tramposo.setMayoreo(true);
        assertThatThrownBy(() -> ventaService.cobrar(List.of(tramposo), List.of(
                new me.eliassanchezfernandez.puntodeventa.model.PagoVenta(Venta.FormaPago.EFECTIVO, 100)), auts))
                .isInstanceOf(PermisoRequeridoException.class);

        Venta v = ventaService.cobrar(List.of(d), List.of(
                new me.eliassanchezfernandez.puntodeventa.model.PagoVenta(Venta.FormaPago.EFECTIVO, 100)), auts);
        assertThat(v.getTotal()).isCloseTo(90, within(0.001));
        assertThat(v.getAutorizadaPorNombre()).isEqualTo("Administrador");
        assertThat(v.getDetalles().get(0).getAutorizadoPorUsuario()).isEqualTo("admin");
        assertThat(aut.isConsumida()).isTrue();   // una autorización, un ticket
    }

    @Test
    void venderBajoCostoConDescuentoRequiereCambiarPrecios() throws Exception {
        Producto p = productoConMayoreo(100, 80, 90);   // costo 90
        iniciarComoCajero("cajeroB", EnumSet.of(Permiso.DESCUENTO_MANUAL));
        DetalleVenta d = ventaService.crearDetalle(p, 1);
        ventaService.aplicarDescuentoManual(d, 50.0, null, null);   // unitario 50 < costo 90

        // Es un permiso requerido (no un simple error) para que la UI ofrezca la autorización
        assertThatThrownBy(() -> ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 100))
                .isInstanceOf(PermisoRequeridoException.class)
                .hasMessageContaining("costo")
                .extracting(e -> ((PermisoRequeridoException) e).getPermiso()).isEqualTo(Permiso.CAMBIAR_PRECIOS);

        // Con autorización del admin sí pasa
        Autorizacion aut = new Autorizacion(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow(),
                Permiso.CAMBIAR_PRECIOS, LocalDateTime.now());
        Venta autorizada = ventaService.cobrar(List.of(d), List.of(
                new me.eliassanchezfernandez.puntodeventa.model.PagoVenta(Venta.FormaPago.EFECTIVO, 100)), List.of(aut));
        assertThat(autorizada.getTotal()).isCloseTo(50, within(0.001));
        assertThat(autorizada.getDetalles().get(0).getAutorizadoPorUsuario()).isEqualTo("admin");

        iniciarComoAdmin();
        assertThat(ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 100).getTotal())
                .isCloseTo(50, within(0.001));
    }

    @Test
    void entrePromocionesGanaLaMasReciente() {
        Producto p = producto("Refresco", 20, 10);
        promocion(p, Promocion.Tipo.PORCENTAJE, 50);   // más antigua y más generosa
        Promocion reciente = promocion(p, Promocion.Tipo.PORCENTAJE, 10);

        assertThat(promocionService.promocionVigente(p).orElseThrow().getId()).isEqualTo(reciente.getId());
        assertThat(ventaService.crearDetalle(p, 1).getImporte()).isCloseTo(18, within(0.001));
    }

    @Test
    void dosPorUnoYTresPorDos() {
        Producto p = producto("Chocolate", 10, 50);
        promocionNxM(p, 2, 1);

        assertThat(ventaService.crearDetalle(p, 1).getImporte()).isCloseTo(10, within(0.001));
        assertThat(ventaService.crearDetalle(p, 2).getImporte()).isCloseTo(10, within(0.001));
        assertThat(ventaService.crearDetalle(p, 3).getImporte()).isCloseTo(20, within(0.001));
        assertThat(ventaService.crearDetalle(p, 4).getImporte()).isCloseTo(20, within(0.001));

        Producto q = producto("Yogur", 10, 50);
        promocionNxM(q, 3, 2);
        assertThat(ventaService.crearDetalle(q, 3).getImporte()).isCloseTo(20, within(0.001));
        assertThat(ventaService.crearDetalle(q, 7).getImporte()).isCloseTo(50, within(0.001));

        assertThatThrownBy(() -> promocionNxM(q, 2, 2)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> promocionNxM(q, 1, 0)).isInstanceOf(NegocioException.class);
    }

    @Test
    void promocionPorDepartamentoAplicaATodosSusProductos() {
        Departamento lacteos = departamento("Lácteos");
        Producto leche = producto("Leche", 20, 10, Producto.TipoVenta.UNIDAD, lacteos);
        Producto queso = producto("Queso", 50, 10, Producto.TipoVenta.UNIDAD, lacteos);
        Producto pan   = producto("Pan", 10, 10);

        Promocion promo = new Promocion();
        promo.setDepartamento(lacteos);
        promo.setTipo(Promocion.Tipo.PORCENTAJE);
        promo.setValor(20);
        promo.setDescripcion("Lácteos -20%");
        promocionService.guardar(promo);

        assertThat(ventaService.crearDetalle(leche, 1).getImporte()).isCloseTo(16, within(0.001));
        assertThat(ventaService.crearDetalle(queso, 1).getImporte()).isCloseTo(40, within(0.001));
        assertThat(ventaService.crearDetalle(pan, 1).getImporte()).isCloseTo(10, within(0.001));
        assertThat(ventaService.crearDetalle(leche, 1).getPromocionNombre()).isEqualTo("Lácteos -20%");

        // Precio fijo solo por producto; producto y departamento a la vez no
        Promocion invalida = new Promocion();
        invalida.setDepartamento(lacteos);
        invalida.setTipo(Promocion.Tipo.PRECIO_FIJO);
        invalida.setValor(5);
        assertThatThrownBy(() -> promocionService.guardar(invalida)).isInstanceOf(NegocioException.class);
    }

    @Test
    void diasDeLaSemanaYHorarioRestringenLaVigencia() {
        Producto p = producto("Cerveza", 30, 10);
        Promocion promo = promocion(p, Promocion.Tipo.PORCENTAJE, 10);
        promo.setDias(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        promo.setHoraInicio("18:00");
        promo.setHoraFin("22:00");
        promo.setFechaInicio(java.time.LocalDate.of(2026, 1, 1));   // fechas fijas: la prueba no depende del día de hoy
        promo.setFechaFin(null);
        promocionService.guardar(promo);

        LocalDateTime sabadoNoche = LocalDateTime.of(2026, 10, 24, 19, 0);   // sábado
        LocalDateTime sabadoManana = LocalDateTime.of(2026, 10, 24, 10, 0);
        LocalDateTime lunesNoche = LocalDateTime.of(2026, 10, 26, 19, 0);    // lunes
        assertThat(promo.estaVigente(sabadoNoche)).isTrue();
        assertThat(promo.estaVigente(sabadoManana)).isFalse();
        assertThat(promo.estaVigente(lunesNoche)).isFalse();
        assertThat(promocionService.promocionVigente(p, sabadoNoche)).isPresent();
        assertThat(promocionService.promocionVigente(p, lunesNoche)).isEmpty();

        promo.setHoraFin("17:00");
        assertThatThrownBy(() -> promocionService.guardar(promo)).isInstanceOf(NegocioException.class);
        promo.setHoraFin("25:00");
        assertThatThrownBy(() -> promocionService.guardar(promo)).isInstanceOf(NegocioException.class);
        promo.setHoraFin("22:00");
        promo.setDiasSemana("LUNES");
        assertThatThrownBy(() -> promocionService.guardar(promo)).isInstanceOf(NegocioException.class);

        // Ningún día marcado no significa "todos los días": se rechaza
        promo.setDias(EnumSet.noneOf(DayOfWeek.class));
        assertThat(promo.getDias()).isEmpty();
        assertThatThrownBy(() -> promocionService.guardar(promo))
                .isInstanceOf(NegocioException.class).hasMessageContaining("al menos un día");
    }

    @Test
    void losLimitesDeUnaPromocionSeCompartenEntreLosRenglonesDelTicket() {
        Departamento botanas = departamento("Botanas");
        Producto papas  = producto("Papas", 20, 50, Producto.TipoVenta.UNIDAD, botanas);
        Producto cacahuates = producto("Cacahuates", 10, 50, Producto.TipoVenta.UNIDAD, botanas);
        Promocion promo = new Promocion();
        promo.setDepartamento(botanas);
        promo.setTipo(Promocion.Tipo.PORCENTAJE);
        promo.setValor(50);
        promo.setLimitePorTicket(3);
        promo.setLimitePiezas(5);
        promocionService.guardar(promo);

        // Dos productos distintos en la misma promoción: 2 + 2 piezas, pero el límite por ticket es 3
        DetalleVenta d1 = ventaService.crearDetalle(papas, 2);
        DetalleVenta d2 = ventaService.crearDetalle(cacahuates, 2);
        List<DetalleVenta> ticket = new ArrayList<>(List.of(d1, d2));
        ventaService.recalcularTicket(ticket);

        assertThat(d1.getPiezasPromocion()).isEqualTo(2);
        assertThat(d1.getDescuento()).isCloseTo(20, within(0.001));      // 2 × 20 × 50 %
        assertThat(d2.getPiezasPromocion()).isEqualTo(1);                 // solo queda 1 del límite por ticket
        assertThat(d2.getDescuento()).isCloseTo(5, within(0.001));       // 1 × 10 × 50 %

        // Al cobrar se aplica el mismo reparto y se consumen 3 piezas del "hasta agotar"
        Venta v = ventaService.cobrar(ticket, Venta.FormaPago.EFECTIVO, 100);
        assertThat(v.getTotal()).isCloseTo(40 + 20 - 20 - 5, within(0.001));
        assertThat(promocionService.listarTodas().stream().filter(x -> x.getId().equals(promo.getId()))
                .findFirst().orElseThrow().getPiezasUsadasOCero()).isEqualTo(3);

        // Quedan 2 piezas "hasta agotar" para todo el ticket siguiente, aunque el límite por ticket sea 3
        DetalleVenta e1 = ventaService.crearDetalle(papas, 1);
        DetalleVenta e2 = ventaService.crearDetalle(cacahuates, 3);
        ventaService.recalcularTicket(List.of(e1, e2));
        assertThat(e1.getPiezasPromocion()).isEqualTo(1);
        assertThat(e2.getPiezasPromocion()).isEqualTo(1);
    }

    @Test
    void hastaAgotarYLimitePorTicket() {
        Producto p = producto("Detergente", 100, 50);
        Promocion promo = promocion(p, Promocion.Tipo.PORCENTAJE, 50);
        promo.setLimitePorTicket(2);
        promo.setLimitePiezas(3);
        promocionService.guardar(promo);

        // 4 piezas: solo 2 con descuento (límite por ticket)
        DetalleVenta d = ventaService.crearDetalle(p, 4);
        assertThat(d.getDescuento()).isCloseTo(100, within(0.001));
        assertThat(d.getPiezasPromocion()).isEqualTo(2);
        ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 400);

        // Quedaba 1 pieza en promoción
        DetalleVenta d2 = ventaService.crearDetalle(p, 2);
        assertThat(d2.getDescuento()).isCloseTo(50, within(0.001));
        Venta v2 = ventaService.cobrar(List.of(d2), Venta.FormaPago.EFECTIVO, 400);

        // Agotada: ya no aplica
        assertThat(promocionService.promocionVigente(p)).isEmpty();
        assertThat(ventaService.crearDetalle(p, 1).getDescuento()).isZero();

        // Cancelar devuelve el cupo
        ventaService.cancelar(v2.getId(), "Prueba", null);
        assertThat(promocionService.promocionVigente(p)).isPresent();
    }
}
