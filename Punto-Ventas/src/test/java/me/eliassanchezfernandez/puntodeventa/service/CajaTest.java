package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Fondo de caja persistido por día y cajero (CRT-02) y corte guardado (CRT-06). */
class CajaTest extends BaseServicioTest {

    @Autowired private CajaService cajaService;

    @Test
    void elFondoSeCapturaUnaVezAlDiaYSoloElAdminLoCorrige() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Cajero cajera = crearUsuario("cajeraF", Cajero.Rol.CAJERO, null);
        assertThat(cajaService.requiereFondo(admin)).isTrue();
        assertThat(cajaService.requiereFondo(cajera)).isTrue();

        // El primer inicio del día (quien sea) captura el fondo
        iniciarComo("cajeraF", CLAVE_PRUEBA);
        cajaService.registrarFondo(sesionService.getCajeroActual(), 500);

        // A nadie más se le vuelve a pedir ese día y no se puede capturar otro
        assertThat(cajaService.requiereFondo(cajera)).isFalse();
        assertThat(cajaService.requiereFondo(admin)).isFalse();
        assertThat(cajaService.fondoDelCajeroActual()).isEqualTo(500);
        assertThat(cajaService.fondoVigenteDelDia(LocalDate.now())).isEqualTo(500);
        assertThatThrownBy(() -> cajaService.registrarFondo(admin, 300))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya fue registrado");
        assertThatThrownBy(() -> cajaService.registrarFondo(cajera, -1))
                .isInstanceOf(NegocioException.class);

        // Un cajero no corrige; el admin sí, con motivo, y la corrección es la que vale (no se suma)
        assertThatThrownBy(() -> cajaService.corregirFondo(450, "x"))
                .isInstanceOf(NegocioException.class);
        iniciarComoAdmin();
        assertThatThrownBy(() -> cajaService.corregirFondo(450, "  "))
                .isInstanceOf(NegocioException.class).hasMessageContaining("motivo");
        cajaService.corregirFondo(450, "Se contó mal");
        assertThat(cajaService.fondoVigenteDelDia(LocalDate.now())).isEqualTo(450);
        assertThat(cajaService.fondoDelDia(LocalDate.now()).orElseThrow().getCajeroNombre()).isEqualTo("Administrador");
    }

    @Test
    void elCorteGuardaEsperadoContadoYDiferencia() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 300);
        Producto p = producto("Pan", 100, 10);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 150);   // cambio 50
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.TARJETA, 100);
        double efectivoEsperado = 300 + ventaService.resumenDelDia().efectivo;   // un solo fondo, no suma por cajero

        Corte c = cajaService.hacerCorte(efectivoEsperado - 20, "Faltó un billete");

        assertThat(c.getId()).isNotNull();
        assertThat(c.getEfectivoEsperado()).isCloseTo(efectivoEsperado, within(0.001));
        assertThat(c.getDiferencia()).isCloseTo(-20, within(0.001));
        assertThat(c.hayFaltante()).isTrue();
        assertThat(c.getCajeroNombre()).isEqualTo("Administrador");
        assertThat(c.getFondoInicial()).isEqualTo(300);
        assertThat(c.getVentasTarjeta()).isGreaterThanOrEqualTo(100);
        assertThat(c.getGanancia()).isGreaterThan(0);
        assertThat(cajaService.cortesDeHoy()).extracting(Corte::getId).contains(c.getId());

        // Se puede seguir vendiendo y hacer otro corte
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
        Corte c2 = cajaService.hacerCorte(efectivoEsperado + 100, null);
        assertThat(c2.getDiferencia()).isCloseTo(0, within(0.001));
        assertThat(cajaService.cortesDeHoy()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void hacerCorteYVerGananciasSonPermisosDistintos() throws Exception {
        iniciarComoCajero("corteSinGanancia", EnumSet.of(Permiso.HACER_CORTE));
        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        assertThat(r.gananciaVisible).isFalse();
        assertThat(r.ganancia).isZero();
        assertThat(cajaService.hacerCorte(0, null).getId()).isNotNull();

        iniciarComoCajero("sinCorte", EnumSet.noneOf(Permiso.class));
        assertThatThrownBy(() -> cajaService.hacerCorte(0, null)).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> ventaService.resumenDelDia()).isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void elResumenIncluyeLosProductosBajos() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Casi agotado", 10, 1);
        assertThat(ventaService.resumenDelDia().productosBajos).extracting(Producto::getId).contains(p.getId());
    }
}
