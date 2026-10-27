package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opciones habilitadas (CFG-05): solo ADMIN, con bitácora, y cada interruptor realmente se aplica. */
class ConfiguracionOpcionesTest extends BaseServicioTest {

    @Autowired private ConfiguracionService configuracionService;
    @Autowired private CajaService          cajaService;
    @Autowired private AuditoriaService     auditoriaService;

    @Test
    void porDefectoTodoComoAntesDeLaConfiguracion() {
        Configuracion c = configuracionService.obtener();
        assertThat(c.isPedirFondoCaja()).isTrue();
        assertThat(c.isAvisoStockBajo()).isTrue();
        assertThat(c.isPermitirGranel()).isTrue();
        assertThat(c.isConfirmarCobro()).isFalse();
        assertThat(c.isMostrarSugerencias()).isTrue();
    }

    @Test
    void elInterruptorDeSugerenciasEnVentaSoloLoCambiaElAdmin() throws Exception {
        iniciarComoAdmin();
        assertThat(configuracionService.isMostrarSugerencias()).isTrue();

        configuracionService.guardarOpciones(true, true, true, false, false);
        assertThat(configuracionService.isMostrarSugerencias()).isFalse();
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "CONFIGURACION_MODIFICADA".equals(a.getAccion())
                && a.getDetalle().contains("sugerencias=false"));

        // La versión de cuatro opciones conserva el valor del interruptor
        configuracionService.guardarOpciones(true, true, true, true);
        assertThat(configuracionService.isMostrarSugerencias()).isFalse();
        assertThat(configuracionService.isConfirmarCobro()).isTrue();

        iniciarComoCajero("cajeroSug", null);
        assertThatThrownBy(() -> configuracionService.guardarOpciones(true, true, true, false, true))
                .isInstanceOf(NegocioException.class);
        assertThat(configuracionService.isMostrarSugerencias()).isFalse();
    }

    @Test
    void soloElAdminCambiaLasOpcionesYQuedaEnLaBitacora() throws Exception {
        iniciarComoAdmin();
        configuracionService.guardarOpciones(false, false, true, true);

        assertThat(configuracionService.isPedirFondoCaja()).isFalse();
        assertThat(configuracionService.isAvisoStockBajo()).isFalse();
        assertThat(configuracionService.isConfirmarCobro()).isTrue();
        List<Auditoria> bitacora = auditoriaService.ultimas();
        assertThat(bitacora).anyMatch(a -> "CONFIGURACION_MODIFICADA".equals(a.getAccion())
                && a.getDetalle().contains("[Opciones]") && a.getDetalle().contains("fondoCaja=false"));

        iniciarComoCajero("cajeroOpc", null);
        assertThatThrownBy(() -> configuracionService.guardarOpciones(true, true, true, false))
                .isInstanceOf(NegocioException.class);
        assertThat(configuracionService.isPedirFondoCaja()).isFalse();   // sin cambios
    }

    @Test
    void sinFondoDeCajaElLoginNoLoPide() throws Exception {
        var admin = iniciarComoAdmin();
        configuracionService.guardarOpciones(false, true, true, false);
        assertThat(cajaService.requiereFondo(admin)).isFalse();

        configuracionService.guardarOpciones(true, true, true, false);
        // con la opción encendida vuelve a pedirse mientras no haya fondo registrado hoy
        assertThat(cajaService.requiereFondo(admin)).isEqualTo(cajaService.fondoDelDia(java.time.LocalDate.now()).isEmpty());
    }

    @Test
    void sinVentaAGranelNoSeCreanNiSeVendenProductosAGranel() throws Exception {
        iniciarComoAdmin();
        Producto existente = producto("Frijol granel", 30, 50, Producto.TipoVenta.GRANEL, null);
        assertThat(ventaService.crearDetalle(existente, 1.5).getCantidad()).isEqualTo(1.5);

        configuracionService.guardarOpciones(true, true, false, false);

        assertThatThrownBy(() -> producto("Arroz granel", 20, 10, Producto.TipoVenta.GRANEL, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("granel");
        assertThatThrownBy(() -> ventaService.crearDetalle(existente, 1.5))
                .isInstanceOf(NegocioException.class).hasMessageContaining("granel");
        // un producto por pieza no se afecta
        Producto pieza = producto("Pieza normal", 10, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(pieza, 1)), Venta.FormaPago.EFECTIVO, 10);
        assertThat(v.getTotal()).isEqualTo(10);
        // y editar uno que ya era a granel (p. ej. su precio) sigue permitido
        existente.setDescripcion("Frijol granel (editado)");
        assertThat(productoService.guardar(existente).getDescripcion()).contains("editado");
    }
}
