package me.eliassanchezfernandez.puntodeventa.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Búsqueda de ciudades (asistente inicial): lectura del JSON del
 * geocodificador de Open-Meteo desde un texto fijo (NUNCA se llama a
 * internet en las pruebas), armado de la URL y validación del texto.
 */
class UbicacionServiceTest {

    private static final String RESPUESTA = """
            {"results":[
              {"id":3521081,"name":"Puebla","latitude":19.03793,"longitude":-98.20346,"country":"México","admin1":"Puebla"},
              {"id":2512989,"name":"Puebla de Sanabria","latitude":42.05,"longitude":-6.63,"country":"España","admin1":"Castilla y León"},
              {"id":1,"name":"Sin coordenadas"},
              {"id":2,"name":"Fuera de rango","latitude":95.0,"longitude":0.0}
            ],"generationtime_ms":0.6}
            """;

    @Test
    void leeLosLugaresConSusCoordenadasYDescartaLosIncompletos() throws IOException {
        List<UbicacionService.Lugar> lugares = UbicacionService.parsear(RESPUESTA);

        assertThat(lugares).hasSize(2);
        UbicacionService.Lugar puebla = lugares.get(0);
        assertThat(puebla.nombre()).isEqualTo("Puebla");
        assertThat(puebla.latitud()).isCloseTo(19.0379, org.assertj.core.data.Offset.offset(0.001));
        assertThat(puebla.longitud()).isCloseTo(-98.2035, org.assertj.core.data.Offset.offset(0.001));
        assertThat(puebla.etiqueta()).isEqualTo("Puebla, México");            // región igual al nombre: no se repite
        assertThat(lugares.get(1).etiqueta()).isEqualTo("Puebla de Sanabria, Castilla y León, España");
    }

    @Test
    void sinResultadosRegresaListaVacia() throws IOException {
        assertThat(UbicacionService.parsear("{\"generationtime_ms\":0.3}")).isEmpty();
        assertThat(UbicacionService.parsear("{\"results\":[]}")).isEmpty();
    }

    @Test
    void unJsonRotoLanzaIOException() {
        assertThatThrownBy(() -> UbicacionService.parsear("esto no es json")).isInstanceOf(IOException.class);
    }

    @Test
    void laUrlCodificaElTextoYPideEspanol() {
        URI uri = UbicacionService.uriPara("  San Luis Potosí ");
        assertThat(uri.toString())
                .startsWith(UbicacionService.URL_BASE + "?name=San+Luis+Potos%C3%AD")
                .contains("&count=" + UbicacionService.MAX_RESULTADOS)
                .contains("&language=es");
    }

    @Test
    void elTextoDebeTenerAlMenosDosLetras() {
        assertThatThrownBy(() -> UbicacionService.uriPara(" a ")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> UbicacionService.uriPara(null)).isInstanceOf(NegocioException.class);
        assertThat(UbicacionService.validarTexto("x".repeat(200))).hasSize(80);
    }
}
