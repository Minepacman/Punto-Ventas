package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.repository.ClimaRegistroRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Clima: mapeo WMO → categoría, bandas de temperatura, lectura del JSON de
 * Open-Meteo desde un texto fijo (NUNCA se llama a internet en las pruebas),
 * registro por hora, y la configuración de ubicación (solo ADMIN, auditada).
 */
class ClimaServiceTest extends BaseServicioTest {

    @Autowired private ClimaService            climaService;
    @Autowired private ClimaRegistroRepository climaRepo;
    @Autowired private ConfiguracionService    configuracionService;
    @Autowired private AuditoriaService        auditoriaService;

    private static final String RESPUESTA = """
            {"latitude":19.4375,"longitude":-99.125,"timezone":"America/Mexico_City",
             "current_units":{"time":"iso8601","temperature_2m":"°C","weather_code":"wmo code","precipitation":"mm"},
             "current":{"time":"2026-10-26T15:00","interval":900,"temperature_2m":22.4,"weather_code":61,"precipitation":0.8}}
            """;

    @Test
    void elCodigoWmoSeTraduceACategoriasEnEspanol() {
        assertThat(ClimaService.categoriaDe(0)).isEqualTo("Despejado");
        assertThat(ClimaService.categoriaDe(1)).isEqualTo("Despejado");
        assertThat(ClimaService.categoriaDe(2)).isEqualTo("Nublado");
        assertThat(ClimaService.categoriaDe(3)).isEqualTo("Nublado");
        assertThat(ClimaService.categoriaDe(45)).isEqualTo("Niebla");
        assertThat(ClimaService.categoriaDe(48)).isEqualTo("Niebla");
        assertThat(ClimaService.categoriaDe(51)).isEqualTo("Lluvia");
        assertThat(ClimaService.categoriaDe(63)).isEqualTo("Lluvia");
        assertThat(ClimaService.categoriaDe(67)).isEqualTo("Lluvia");
        assertThat(ClimaService.categoriaDe(71)).isEqualTo("Nieve");
        assertThat(ClimaService.categoriaDe(77)).isEqualTo("Nieve");
        assertThat(ClimaService.categoriaDe(80)).isEqualTo("Lluvia");
        assertThat(ClimaService.categoriaDe(82)).isEqualTo("Lluvia");
        assertThat(ClimaService.categoriaDe(85)).isEqualTo("Nieve");
        assertThat(ClimaService.categoriaDe(95)).isEqualTo("Tormenta");
        assertThat(ClimaService.categoriaDe(99)).isEqualTo("Tormenta");
        assertThat(ClimaService.categoriaDe(1234)).isEqualTo("Nublado");   // desconocido: neutro
        assertThat(ClimaService.esLluvioso("Tormenta")).isTrue();
        assertThat(ClimaService.esLluvioso("Despejado")).isFalse();
        assertThat(ClimaService.esLluvioso(null)).isFalse();
    }

    @Test
    void lasBandasDeTemperaturaRespetanLosLimites() {
        assertThat(ClimaService.bandaDe(-2)).isEqualTo("Frío");
        assertThat(ClimaService.bandaDe(14.9)).isEqualTo("Frío");
        assertThat(ClimaService.bandaDe(15)).isEqualTo("Templado");
        assertThat(ClimaService.bandaDe(25)).isEqualTo("Templado");
        assertThat(ClimaService.bandaDe(25.1)).isEqualTo("Calor");
        assertThat(ClimaService.bandaDe(38)).isEqualTo("Calor");
    }

    @Test
    void elJsonDeOpenMeteoSeInterpretaDesdeUnTextoFijo() throws Exception {
        ClimaService.Lectura l = ClimaService.parsear(RESPUESTA);
        assertThat(l.temperatura()).isCloseTo(22.4, within(0.001));
        assertThat(l.codigo()).isEqualTo(61);
        assertThat(l.precipitacion()).isCloseTo(0.8, within(0.001));
        assertThat(l.hora()).isEqualTo(LocalDateTime.of(2026, 10, 26, 15, 0));
        assertThat(l.categoria()).isEqualTo("Lluvia");
        assertThat(l.banda()).isEqualTo("Templado");

        assertThatThrownBy(() -> ClimaService.parsear("{\"current\":{}}")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ClimaService.parsear("esto no es json")).isInstanceOf(IOException.class);
        // La URL solo lleva coordenadas y los campos pedidos: nada de ventas
        String uri = ClimaService.uriPara(19.4326, -99.1332).toString();
        assertThat(uri).startsWith(ClimaService.URL_BASE).contains("latitude=19.4326").contains("longitude=-99.1332")
                .contains("current=temperature_2m,weather_code,precipitation");
    }

    @Test
    void elRegistroSeGuardaPorHoraYSirveComoClimaActual() throws Exception {
        // Minuto fijo para que "+20 minutos" caiga en la misma hora sin importar cuándo corra la prueba
        LocalDateTime ahora = LocalDateTime.now().withMinute(5).withSecond(0).withNano(0);
        ClimaService.Lectura l = ClimaService.parsear(RESPUESTA);

        ClimaRegistro r = climaService.guardar(l, ahora);
        assertThat(r.getFechaHora()).isEqualTo(ahora.withMinute(0).withSecond(0).withNano(0));
        assertThat(r.getCategoria()).isEqualTo("Lluvia");
        assertThat(r.getBanda()).isEqualTo("Templado");
        assertThat(r.getDescripcion()).isEqualTo("Lluvia, 22 °C");

        // Segunda lectura de la misma hora: reemplaza, no duplica
        ClimaRegistro r2 = climaService.guardar(new ClimaService.Lectura(null, 28, 0, 0), ahora.plusMinutes(20));
        assertThat(r2.getId()).isEqualTo(r.getId());
        assertThat(climaRepo.findByFechaHora(r.getFechaHora()).orElseThrow().getBanda()).isEqualTo("Calor");

        assertThat(climaService.actual()).isPresent();
        assertThat(climaService.climaDe(ahora)).isPresent();
        assertThat(climaService.climaDe(ahora.minusHours(5))).isEmpty();

        // Un registro viejo ya no es "actual"
        climaRepo.deleteAll();
        climaService.guardar(l, ahora.minusHours(ClimaService.VIGENCIA_HORAS + 1));
        assertThat(climaService.actual()).isEmpty();
    }

    @Test
    void sinClimaActivoNoSeConsultaNadaYLaUbicacionSoloLaCambiaElAdmin() throws Exception {
        // Por defecto apagado y sin coordenadas: registrarAhora no hace nada (y no toca la red)
        Configuracion c = configuracionService.obtener();
        assertThat(c.isClimaActivo()).isFalse();
        assertThat(c.tieneUbicacion()).isFalse();
        assertThat(climaService.registrarAhora()).isEmpty();
        assertThat(climaService.estaActivo()).isFalse();

        iniciarComoAdmin();
        assertThatThrownBy(() -> configuracionService.guardarUbicacionClima(null, null, "CDMX", true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("coordenadas");
        assertThatThrownBy(() -> configuracionService.guardarUbicacionClima(95.0, -99.0, null, false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("latitud");
        assertThatThrownBy(() -> configuracionService.guardarUbicacionClima(19.0, -200.0, null, false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("longitud");
        assertThatThrownBy(() -> configuracionService.guardarUbicacionClima(19.0, null, null, false))
                .isInstanceOf(NegocioException.class);

        Configuracion g = configuracionService.guardarUbicacionClima(19.4326, -99.1332, " Ciudad de México ", true);
        assertThat(g.getLatitud()).isEqualTo(19.4326);
        assertThat(g.getCiudad()).isEqualTo("Ciudad de México");
        assertThat(g.isClimaActivo()).isTrue();
        List<Auditoria> bitacora = auditoriaService.ultimas();
        assertThat(bitacora).anyMatch(a -> "CONFIGURACION_MODIFICADA".equals(a.getAccion())
                && a.getDetalle().contains("[Ubicación y clima]") && a.getDetalle().contains("clima=true"));

        // Quitar las coordenadas apaga el clima
        assertThat(configuracionService.guardarUbicacionClima(null, null, null, false).isClimaActivo()).isFalse();

        iniciarComoCajero("cajeroClima", null);
        assertThatThrownBy(() -> configuracionService.guardarUbicacionClima(19.0, -99.0, null, true))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void consultarRechazaCoordenadasInvalidasSinSalirALaRed() {
        double[][] malas = {{Double.NaN, 0}, {0, Double.POSITIVE_INFINITY}, {91, 0}, {-90.5, 0}, {0, 180.1}, {0, -181}};
        for (double[] c : malas) {
            assertThatThrownBy(() -> climaService.consultar(c[0], c[1]))
                    .isInstanceOf(IOException.class).hasMessageContaining("debe estar entre");
        }
        assertThatThrownBy(() -> ClimaService.uriPara(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ClimaService.uriPara(90, -180).toString()).startsWith("https://api.open-meteo.com/");
        assertThat(climaService.ultimoError()).isPresent();
    }
}
