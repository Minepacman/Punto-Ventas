package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Folios: prefijo y número mostrados; el id (llave primaria) nunca se toca y las ventas viejas conservan su folio. */
class FoliosTest extends BaseServicioTest {

    @Autowired private ConfiguracionService configuracionService;

    private Venta vender(Producto p) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
    }

    @Test
    void elSiguienteFolioEsElSiguienteIdYSeAsignaAlCobrar() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Folio", 10, 50);
        long siguiente = configuracionService.siguienteNumeroFolio();

        Venta v = vender(p);

        assertThat(v.getId()).isEqualTo(siguiente);
        assertThat(v.getFolio()).isEqualTo(String.valueOf(siguiente));
        assertThat(configuracionService.siguienteNumeroFolio()).isEqualTo(siguiente + 1);
    }

    @Test
    void elPrefijoYElNumeroAplicanSoloALasVentasNuevas() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Folio 2", 10, 50);
        Venta anterior = vender(p);
        long idSiguiente = anterior.getId() + 1;

        configuracionService.guardarFolios("A-", idSiguiente + 99);
        assertThat(configuracionService.siguienteFolio()).isEqualTo("A-" + (idSiguiente + 99));

        Venta nueva = vender(p);
        assertThat(nueva.getId()).isEqualTo(idSiguiente);                          // la llave no cambia
        assertThat(nueva.getFolio()).isEqualTo("A-" + (idSiguiente + 99));
        assertThat(nueva.getFolioMostrado()).startsWith("A-");
        assertThat(anterior.getFolioMostrado()).isEqualTo(String.valueOf(anterior.getId()));   // sin renumerar

        // las siguientes siguen la secuencia
        assertThat(vender(p).getFolio()).isEqualTo("A-" + (idSiguiente + 100));
    }

    @Test
    void unaVentaSinFolioGuardadoMuestraSuId() {
        Venta vieja = new Venta();
        vieja.setId(42L);
        assertThat(vieja.getFolioMostrado()).isEqualTo("42");
    }

    @Test
    void validaPrefijoNoRepetirFoliosYPermisos() throws Exception {
        iniciarComoAdmin();
        long actual = configuracionService.siguienteNumeroFolio();
        assertThatThrownBy(() -> configuracionService.guardarFolios("A-", actual - 1))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no puede ser menor");
        assertThatThrownBy(() -> configuracionService.guardarFolios("muy largo!", actual))
                .isInstanceOf(NegocioException.class).hasMessageContaining("prefijo");
        assertThatThrownBy(() -> configuracionService.guardarFolios("A", 1_000_000_000L))
                .isInstanceOf(NegocioException.class);
        configuracionService.guardarFolios("", actual);   // quitar el prefijo es válido
        assertThat(configuracionService.siguienteFolio()).isEqualTo(String.valueOf(actual));

        iniciarComoCajero("cajeroFolio", null);
        assertThatThrownBy(() -> configuracionService.guardarFolios("B-", actual + 5))
                .isInstanceOf(NegocioException.class);
    }
}
