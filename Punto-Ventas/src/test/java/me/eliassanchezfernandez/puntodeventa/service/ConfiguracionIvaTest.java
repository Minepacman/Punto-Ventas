package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Configuración del negocio en BD (CFG-01) e IVA incluido en el precio (PRD-07, VEN-04). */
class ConfiguracionIvaTest extends BaseServicioTest {

    @Autowired private ConfiguracionService configuracionService;

    @Test
    void laConfiguracionSeCreaConValoresPorDefecto() {
        Configuracion c = configuracionService.obtener();
        assertThat(c.getId()).isEqualTo(Configuracion.ID_UNICO);
        assertThat(c.getTasaIva()).isEqualTo(0.16);
        assertThat(c.getPlazoCancelacionHoras()).isEqualTo(48);
        assertThat(c.getNombreTienda()).isNotBlank();
        assertThat(configuracionService.obtener().getId()).isEqualTo(c.getId());   // una sola fila
    }

    @Test
    void elIvaSeDesglosaDelTotalQueYaLoIncluye() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Con IVA", 116, 10);

        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 116);

        assertThat(v.getTotal()).isCloseTo(116, within(0.001));
        assertThat(v.getSubtotal()).isCloseTo(100, within(0.01));
        assertThat(v.getIva()).isCloseTo(16, within(0.01));
        assertThat(v.getTasaIva()).isEqualTo(0.16);
        assertThat(ventaService.resumenDelDia().iva).isGreaterThanOrEqualTo(15.99);
    }

    @Test
    void soloElAdminCambiaLaConfiguracionYElPlazoNoEsRetroactivo() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Antes", 108, 10);
        Venta anterior = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 108);

        configuracionService.guardar("Abarrotes La Esquina", "Av. Siempre Viva 742", 8, 24);

        Configuracion c = configuracionService.obtener();
        assertThat(c.getNombreTienda()).isEqualTo("Abarrotes La Esquina");
        assertThat(c.getDireccion()).isEqualTo("Av. Siempre Viva 742");
        assertThat(c.getTasaIva()).isCloseTo(0.08, within(0.00001));
        assertThat(c.getPlazoCancelacionHoras()).isEqualTo(24);

        Venta nueva = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 108);
        assertThat(nueva.getIva()).isCloseTo(8, within(0.01));
        assertThat(nueva.getPlazoCancelacionHoras()).isEqualTo(24);
        assertThat(anterior.getPlazoCancelacionHoras()).isEqualTo(48);   // VEN-19

        assertThatThrownBy(() -> configuracionService.guardar("", null, 16, 48)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardar("X", null, 101, 48)).isInstanceOf(NegocioException.class);

        iniciarComoCajero("cajeroCfg", null);
        assertThatThrownBy(() -> configuracionService.guardar("Otra", null, 16, 48))
                .isInstanceOf(NegocioException.class);
        assertThat(configuracionService.getNombreTienda()).isEqualTo("Abarrotes La Esquina");
    }

    @Test
    void losTextosDeConfiguracionTienenLargoMaximoYSinCaracteresDeControl() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> configuracionService.guardar("x".repeat(151), null, 16, 48))
                .isInstanceOf(NegocioException.class).hasMessageContaining("150");
        assertThatThrownBy(() -> configuracionService.guardar("Tienda", "d".repeat(251), 16, 48))
                .isInstanceOf(NegocioException.class).hasMessageContaining("250");
        assertThatThrownBy(() -> configuracionService.guardar("Tien\u0000da", null, 16, 48))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no permitidos");
        assertThatThrownBy(() -> configuracionService.guardar("Tienda\nDos", null, 16, 48))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardarMoneda("$\u0007$", "MXN"))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardarRespaldoAutomatico(false, "r".repeat(501), 5))
                .isInstanceOf(NegocioException.class).hasMessageContaining("500");
        // En el ticket sí se permiten saltos de línea, pero no otros caracteres de control
        configuracionService.guardarTicket("Línea 1\nLínea 2", null, null, null, null, 80);
        assertThatThrownBy(() -> configuracionService.guardarTicket("a\u001Bb", null, null, null, null, 80))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no permitidos");
        configuracionService.guardar("Mi Tienda", "Calle 1", 16, 48);
    }

    @Test
    void laConfiguracionSeLeeDeMemoriaYGuardarLaInvalida() throws Exception {
        iniciarComoAdmin();
        Configuracion a = configuracionService.obtener();
        assertThat(configuracionService.obtener()).as("segunda lectura: misma copia, sin consulta").isSameAs(a);

        configuracionService.guardar("Tienda nueva", "Calle 9", 8, 24);

        Configuracion b = configuracionService.obtener();
        assertThat(b.getNombreTienda()).isEqualTo("Tienda nueva");
        assertThat(b.getTasaIva()).isEqualTo(0.08);
        assertThat(configuracionService.getPlazoCancelacionHoras()).isEqualTo(24);
    }
}
