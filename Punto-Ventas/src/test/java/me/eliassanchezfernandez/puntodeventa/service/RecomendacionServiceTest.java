package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.NivelDatos;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.RecomendacionService.Pesos;
import me.eliassanchezfernandez.puntodeventa.service.RecomendacionService.Sugerencia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Motor de recomendaciones: orden, exclusiones (carrito, inactivos, sin
 * existencia), motivos y la ponderación por nivel de datos (arranque en frío).
 */
class RecomendacionServiceTest extends BaseServicioTest {

    @Autowired private RecomendacionService recomendacionService;
    @Autowired private AgregadosService     agregadosService;
    @Autowired private VentaRepository      ventaRepo;

    private Producto coca, sabritas, cafe, pan, lecheInactiva, hieloSinStock;
    /** Un viernes a las 10:00 posterior a las ventas de prueba */
    private LocalDateTime viernes10;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        coca          = producto("Coca Cola 600 ml", 20, 100);
        sabritas      = producto("Sabritas original", 18, 100);
        cafe          = producto("Café soluble", 60, 100);
        pan           = producto("Pan blanco", 40, 100);
        lecheInactiva = producto("Leche entera", 25, 100);
        hieloSinStock = producto("Hielo bolsa", 30, 0);
        productoService.cambiarActivo(lecheInactiva.getId(), false);

        // Tres viernes recientes a las 10:00 (dentro de la ventana móvil):
        // 4 × (coca + sabritas), 2 × coca, 3 × (café + pan), 1 × pan, 2 × leche
        LocalDate ultimoViernes = LocalDate.now().minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
        LocalDate[] viernes = { ultimoViernes, ultimoViernes.minusWeeks(1), ultimoViernes.minusWeeks(2) };
        int i = 0;
        for (int k = 0; k < 4; k++) venta(viernes[i++ % 3].atTime(10, 15), coca, sabritas);
        for (int k = 0; k < 2; k++) venta(viernes[i++ % 3].atTime(10, 30), coca);
        for (int k = 0; k < 3; k++) venta(viernes[i++ % 3].atTime(10, 45), cafe, pan);
        venta(viernes[i++ % 3].atTime(10, 50), pan);
        for (int k = 0; k < 2; k++) venta(viernes[i++ % 3].atTime(10, 55), lecheInactiva, hieloSinStock);
        agregadosService.recalcularTodo();
        viernes10 = ultimoViernes.plusWeeks(1).atTime(10, 0);
    }

    private Venta venta(LocalDateTime fecha, Producto... productos) {
        Venta v = new Venta();
        v.setFecha(fecha);
        double total = 0;
        for (Producto p : productos) {
            DetalleVenta d = new DetalleVenta();
            d.setVenta(v);
            d.setProducto(p);
            d.setCantidad(1);
            d.setPrecioVenta(p.getPrecioVenta());
            v.getDetalles().add(d);
            total += p.getPrecioVenta();
        }
        v.setTotal(total);
        v.setPagoCon(total);
        return ventaRepo.save(v);
    }

    @Test
    void conHistorialSuficienteLaCanastaMandaYSeExcluyeLoQueNoSePuedeVender() {
        List<Sugerencia> s = recomendacionService.sugerir(List.of(coca.getId()), viernes10, null, 5, NivelDatos.SUFICIENTE);

        assertThat(s).isNotEmpty();
        assertThat(s.get(0).producto().getId()).isEqualTo(sabritas.getId());
        assertThat(s.get(0).motivo()).isEqualTo("Se compra junto con Coca Cola 600 ml");
        assertThat(s.get(0).confianza()).isGreaterThan(0.3);
        assertThat(s).extracting(x -> x.producto().getId())
                .doesNotContain(coca.getId(), lecheInactiva.getId(), hieloSinStock.getId());
        assertThat(s).hasSizeLessThanOrEqualTo(5);
        // Determinista
        assertThat(recomendacionService.sugerir(List.of(coca.getId()), viernes10, null, 5, NivelDatos.SUFICIENTE))
                .usingRecursiveComparison().isEqualTo(s);
    }

    @Test
    void enEntrenamientoMandanLasReglasGenericasYLaConfianzaEsDiscreta() {
        ClimaRegistro lluviaFria = new ClimaRegistro(viernes10, 12, 61, ClimaService.LLUVIA, ClimaService.FRIO);

        // Entrenamiento: el café (regla "día lluvioso") va antes que las sabritas (canasta)
        List<Sugerencia> entrenamiento = recomendacionService.sugerir(List.of(coca.getId()), viernes10, lluviaFria, 5, NivelDatos.ENTRENAMIENTO);
        assertThat(entrenamiento.get(0).producto().getId()).isEqualTo(cafe.getId());
        assertThat(entrenamiento.get(0).motivo()).contains("lluvioso");
        assertThat(entrenamiento).allMatch(x -> x.confianza() <= 0.5);
        assertThat(entrenamiento).noneMatch(x -> x.nivelConfianza().equals("Alta"));

        // Con datos suficientes, la canasta aprendida pesa más que la regla
        List<Sugerencia> suficiente = recomendacionService.sugerir(List.of(coca.getId()), viernes10, lluviaFria, 5, NivelDatos.SUFICIENTE);
        assertThat(suficiente.get(0).producto().getId()).isEqualTo(sabritas.getId());
        assertThat(suficiente).anyMatch(x -> x.producto().getId().equals(cafe.getId()));
    }

    @Test
    void sinCarritoSugiereLoDelDiaYHoraYElPanDeLaManana() {
        List<Sugerencia> s = recomendacionService.sugerir(List.of(), viernes10, null, 5, NivelDatos.PARCIAL);
        assertThat(s).isNotEmpty();
        assertThat(s).extracting(x -> x.producto().getId()).contains(coca.getId(), pan.getId());
        Sugerencia deCoca = s.stream().filter(x -> x.producto().getId().equals(coca.getId())).findFirst().orElseThrow();
        assertThat(deCoca.motivo()).isEqualTo("Se vende mucho los viernes " + Franja.MANANA.getFrase());
        assertThat(s).extracting(x -> x.producto().getId()).doesNotContain(lecheInactiva.getId(), hieloSinStock.getId());
        // El máximo se respeta y 0 regresa vacío
        assertThat(recomendacionService.sugerir(List.of(), viernes10, null, 2, NivelDatos.PARCIAL)).hasSize(2);
        assertThat(recomendacionService.sugerir(List.of(), viernes10, null, 0, NivelDatos.PARCIAL)).isEmpty();
    }

    @Test
    void losPesosDependenDelNivelYSinClimaSuPesoPasaADiaYHora() {
        Pesos e = Pesos.para(NivelDatos.ENTRENAMIENTO, true);
        Pesos s = Pesos.para(NivelDatos.SUFICIENTE, true);
        assertThat(e.generico()).isGreaterThan(s.generico());
        assertThat(s.canasta()).isGreaterThan(e.canasta());
        assertThat(e.tope()).isCloseTo(0.5, within(0.001));
        assertThat(s.tope()).isCloseTo(1.0, within(0.001));
        for (NivelDatos n : NivelDatos.values()) {
            Pesos p = Pesos.para(n, true);
            assertThat(p.generico() + p.canasta() + p.diaHora() + p.clima()).isCloseTo(1.0, within(0.0001));
        }
        Pesos sinClima = Pesos.para(NivelDatos.SUFICIENTE, false);
        assertThat(sinClima.clima()).isZero();
        assertThat(sinClima.diaHora()).isCloseTo(s.diaHora() + s.clima(), within(0.0001));
        assertThat(Pesos.para(NivelDatos.SIN_DATOS, false).generico()).isCloseTo(1.0, within(0.0001));
        assertThat(RecomendacionService.nombreDia(DayOfWeek.FRIDAY)).isEqualTo("viernes");
        assertThat(RecomendacionService.nombreDia(DayOfWeek.SATURDAY)).isEqualTo("sábados");
    }

    @Test
    void lasReglasGenericasCoincidenPorPalabrasClaveSinAcentos() {
        assertThat(ReglasGenericas.evaluar(cafe, DayOfWeek.MONDAY, Franja.TARDE, ClimaService.LLUVIA, ClimaService.TEMPLADO))
                .extracting(ReglasGenericas.Coincidencia::motivo).anyMatch(m -> m.contains("lluvioso"));
        assertThat(ReglasGenericas.evaluar(coca, DayOfWeek.MONDAY, Franja.MEDIODIA, ClimaService.DESPEJADO, ClimaService.CALOR))
                .extracting(ReglasGenericas.Coincidencia::motivo).anyMatch(m -> m.contains("calor"));
        assertThat(ReglasGenericas.evaluar(pan, DayOfWeek.MONDAY, Franja.MANANA, null, null))
                .extracting(ReglasGenericas.Coincidencia::motivo).containsExactly("Se suele vender por la mañana");
        assertThat(ReglasGenericas.evaluar(hieloSinStock, DayOfWeek.SATURDAY, Franja.NOCHE, null, null))
                .extracting(ReglasGenericas.Coincidencia::motivo).anyMatch(m -> m.contains("Fin de semana"));
        assertThat(ReglasGenericas.evaluar(pan, DayOfWeek.MONDAY, Franja.MEDIODIA, null, null)).isEmpty();
        assertThat(ReglasGenericas.normalizar("Café  Ólé")).isEqualTo(" cafe ole ");
    }
}
