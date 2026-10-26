package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Cancelación de ventas (VEN-14, VEN-19): permiso/autorización, plazo, stock y totales. */
class CancelacionTest extends BaseServicioTest {

    @Autowired private VentaRepository ventaRepo;

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    @Test
    void cancelarRegresaElStockYExcluyeLaVentaDeLosTotales() {
        Producto p = producto("Arroz", 50, 10);
        double totalAntes = ventaService.totalVentasHoy();
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 3)), Venta.FormaPago.EFECTIVO, 200);
        assertThat(stockActual(p)).isEqualTo(7);
        assertThat(ventaService.totalVentasHoy()).isCloseTo(totalAntes + 150, within(0.001));

        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "  ", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("motivo");

        Venta cancelada = ventaService.cancelar(v.getId(), "Cliente se arrepintió", null);

        assertThat(cancelada.isCancelada()).isTrue();
        assertThat(cancelada.getMotivoCancelacion()).isEqualTo("Cliente se arrepintió");
        assertThat(cancelada.getCanceladaPorNombre()).isEqualTo("Administrador");
        assertThat(cancelada.getCanceladaPorId()).isNotNull();
        assertThat(cancelada.getCancelacionAutorizadaPor()).isNull();   // el admin no necesitó autorización
        assertThat(stockActual(p)).isEqualTo(10);
        assertThat(ventaService.totalVentasHoy()).isCloseTo(totalAntes, within(0.001));
        assertThat(ventaRepo.findById(v.getId())).isPresent();           // nunca se borra

        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        assertThat(r.numCanceladas).isGreaterThanOrEqualTo(1);
        assertThat(r.totalCanceladas).isGreaterThanOrEqualTo(150);
        assertThat(ventaService.ventasEntre(LocalDate.now(), LocalDate.now()))
                .filteredOn(x -> x.getId().equals(v.getId())).first()
                .extracting(Venta::isCancelada).isEqualTo(true);

        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "otra vez", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya estaba cancelada");
    }

    @Test
    void unCajeroNecesitaAutorizacionDeAdminYQuedaRegistrado() throws Exception {
        Producto p = producto("Frijol", 30, 10);
        iniciarComoCajero("cajeraC", null);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), Venta.FormaPago.EFECTIVO, 100);

        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "Error", null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThat(stockActual(p)).isEqualTo(8);

        Autorizacion aut = new Autorizacion(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow(),
                Permiso.CANCELAR_VENTA, LocalDateTime.now());
        Venta cancelada = ventaService.cancelar(v.getId(), "Error de captura", List.of(aut));

        assertThat(cancelada.getCanceladaPorNombre()).isEqualTo("Usuario cajeraC");
        assertThat(cancelada.getCancelacionAutorizadaPor()).isEqualTo("Administrador");
        assertThat(cancelada.getCancelacionAutorizadaPorId()).isNotNull();
        assertThat(aut.isConsumida()).isTrue();
        assertThat(stockActual(p)).isEqualTo(10);

        // La misma autorización no sirve para otra cancelación
        Venta v2 = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
        assertThatThrownBy(() -> ventaService.cancelar(v2.getId(), "Otra", List.of(aut)))
                .isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void noSePuedeCancelarFueraDelPlazoVigenteAlMomentoDeLaVenta() {
        Producto p = producto("Jugo", 20, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 20);
        assertThat(v.getPlazoCancelacionHoras()).isEqualTo(48);

        v.setFecha(LocalDateTime.now().minusHours(49));
        ventaRepo.save(v);

        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "Tarde", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("plazo");
        assertThat(stockActual(p)).isEqualTo(9);

        // Dentro del plazo sí
        v.setFecha(LocalDateTime.now().minusHours(47));
        ventaRepo.save(v);
        assertThat(ventaService.cancelar(v.getId(), "A tiempo", null).isCancelada()).isTrue();
    }
}
