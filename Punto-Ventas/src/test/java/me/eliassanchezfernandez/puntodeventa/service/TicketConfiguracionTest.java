package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Datos del ticket (se guardan) y su vista previa de texto para 58/80 mm. No imprime. */
class TicketConfiguracionTest extends BaseServicioTest {

    @Autowired private ConfiguracionService configuracionService;
    @Autowired private TicketService        ticketService;

    @Test
    void guardaLosDatosDelTicketSoloElAdmin() throws Exception {
        iniciarComoAdmin();
        configuracionService.guardarTicket("Sucursal Centro", "Sin ticket no hay cambios",
                "xaxx010101000", "55 1234 5678", "¡Vuelva pronto!", 58);

        Configuracion c = configuracionService.obtener();
        assertThat(c.getTicketEncabezado()).isEqualTo("Sucursal Centro");
        assertThat(c.getTicketRfc()).isEqualTo("XAXX010101000");   // se guarda en mayúsculas
        assertThat(c.getTicketTelefono()).isEqualTo("55 1234 5678");
        assertThat(c.getTicketAgradecimiento()).isEqualTo("¡Vuelva pronto!");
        assertThat(c.getTicketAnchoMm()).isEqualTo(58);

        iniciarComoCajero("cajeroTicket", null);
        assertThatThrownBy(() -> configuracionService.guardarTicket(null, null, null, null, null, 80))
                .isInstanceOf(NegocioException.class);
        assertThat(configuracionService.obtener().getTicketAnchoMm()).isEqualTo(58);
    }

    @Test
    void validaAnchoYRfc() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> configuracionService.guardarTicket(null, null, null, null, null, 70))
                .isInstanceOf(NegocioException.class).hasMessageContaining("58 u 80");
        assertThatThrownBy(() -> configuracionService.guardarTicket(null, null, "ABC", null, null, 80))
                .isInstanceOf(NegocioException.class).hasMessageContaining("RFC");
        assertThatThrownBy(() -> configuracionService.guardarTicket("x".repeat(251), null, null, null, null, 80))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void laVistaPreviaRespetaElAnchoDelPapelYMuestraLosDatos() {
        Configuracion c = new Configuracion();
        c.setNombreTienda("Abarrotes Don Pepe con un nombre bastante largo para envolver");
        c.setDireccion("Av. Siempre Viva 742, Col. Centro");
        c.setTicketRfc("XAXX010101000");
        c.setTicketTelefono("55 1234 5678");
        c.setTicketPie("Gracias por preferirnos, conserve su ticket");
        c.setTicketAgradecimiento("¡Vuelva pronto!");

        for (int ancho : new int[] {58, 80}) {
            c.setTicketAnchoMm(ancho);
            String texto = ticketService.vistaPrevia(c, "A-15");
            int columnas = TicketService.columnas(ancho);
            assertThat(texto.split("\n")).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(columnas));
            assertThat(texto).contains("RFC: XAXX010101000", "Tel: 55 1234 5678", "Folio: A-15",
                    "¡Vuelva pronto!", "TOTAL", "Gracias por preferirnos");
        }
        assertThat(TicketService.columnas(58)).isLessThan(TicketService.columnas(80));
    }

    @Test
    void laVistaPreviaUsaLaMonedaConfigurada() throws Exception {
        iniciarComoAdmin();
        configuracionService.guardarMoneda("US$", "USD");
        String texto = ticketService.vistaPrevia(configuracionService.obtener(), "1");
        assertThat(texto).contains("US$").contains("USD");
    }
}
