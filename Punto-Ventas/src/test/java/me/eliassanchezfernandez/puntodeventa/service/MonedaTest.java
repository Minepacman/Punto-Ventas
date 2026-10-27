package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Moneda (CFG-02): símbolo y código guardados, y un solo formato de dinero para toda la app. */
class MonedaTest extends BaseServicioTest {

    @Autowired private ConfiguracionService configuracionService;

    @AfterEach
    void restaurarFormato() {
        Moneda.configurar("$", "MXN");   // el formato es estático: no debe contaminar otras pruebas
    }

    @Test
    void pordefectoPesosMexicanos() {
        assertThat(configuracionService.obtener().getMonedaSimbolo()).isEqualTo("$");
        assertThat(configuracionService.obtener().getMonedaCodigo()).isEqualTo("MXN");
        assertThat(Moneda.formato(1234.5)).isEqualTo("$1234.50");
        assertThat(Moneda.formato(-5)).isEqualTo("-$5.00");
        assertThat(Moneda.formato(0)).isEqualTo("$0.00");
    }

    @Test
    void elAdminCambiaLaMonedaYElFormatoLaUsa() throws Exception {
        iniciarComoAdmin();
        configuracionService.guardarMoneda("US$", "usd");

        assertThat(configuracionService.obtener().getMonedaCodigo()).isEqualTo("USD");
        assertThat(Moneda.formato(10)).isEqualTo("US$10.00");
        // los mensajes de los servicios también salen con la moneda configurada
        var p = producto("Moneda", 50, 5);
        assertThatThrownBy(() -> ventaService.cobrar(java.util.List.of(ventaService.crearDetalle(p, 1)),
                me.eliassanchezfernandez.puntodeventa.model.Venta.FormaPago.EFECTIVO, 10))
                .hasMessageContaining("US$50.00");
    }

    @Test
    void validaSimboloYCodigoYSoloAdmin() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> configuracionService.guardarMoneda("", "MXN")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardarMoneda("$$$$", "MXN")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardarMoneda("$", "PESOS")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardarMoneda("$", "M1N")).isInstanceOf(NegocioException.class);

        iniciarComoCajero("cajeroMoneda", null);
        assertThatThrownBy(() -> configuracionService.guardarMoneda("€", "EUR")).isInstanceOf(NegocioException.class);
        assertThat(configuracionService.obtener().getMonedaCodigo()).isEqualTo("MXN");
    }
}
