package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.CajaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Cortes por caja y corte general, y folios únicos entre cajas, contra PostgreSQL (sin transacción envolvente). */
class CortesYFoliosPostgresTest extends BasePostgresTest {

    @Autowired private CajaService cajaService;
    @Autowired private ConfiguracionService configuracionService;

    @AfterEach
    void volverASerLaCajaUno() throws Exception {
        java.lang.reflect.Method m = identidadCaja.getClass().getDeclaredMethod("fijarParaPruebas", int.class, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(identidadCaja, 1, "Caja 1", true);
    }

    private void serCaja(int n) throws Exception {
        java.lang.reflect.Method m = identidadCaja.getClass().getDeclaredMethod("fijarParaPruebas", int.class, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(identidadCaja, n, "Caja " + n, true);
    }

    @Test
    void elCorteEsPorCajaYElGeneralSumaTodasLasCajas() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Producto p = producto("Pan PG", 100, 500);
        // Los totales del día pueden traer ventas de otras pruebas: se miden diferencias
        VentaService.ResumenCorte antes1 = ventaService.resumenCompletoDe(LocalDate.now(), 1);
        VentaService.ResumenCorte antes2 = ventaService.resumenCompletoDe(LocalDate.now(), 2);
        VentaService.ResumenCorte antesG = ventaService.resumenCompletoDe(LocalDate.now(), null);

        serCaja(1);
        if (cajaService.fondoDelDia(LocalDate.now(), 1).isEmpty()) cajaService.registrarFondo(admin, 200);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
        serCaja(2);
        if (cajaService.fondoDelDia(LocalDate.now(), 2).isEmpty()) cajaService.registrarFondo(admin, 50);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), Venta.FormaPago.EFECTIVO, 200);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.TARJETA, 100);

        VentaService.ResumenCorte r2 = ventaService.resumenCompletoDe(LocalDate.now(), 2);
        assertThat(r2.numVentas - antes2.numVentas).isEqualTo(2);
        assertThat(r2.efectivo - antes2.efectivo).isCloseTo(200, within(0.001));
        assertThat(r2.tarjeta - antes2.tarjeta).isCloseTo(100, within(0.001));
        Corte c2 = cajaService.hacerCorte(r2.efectivo + cajaService.fondoVigenteDelDia(LocalDate.now(), 2), null);
        assertThat(c2.getCajaONumero()).isEqualTo(2);
        assertThat(c2.getDiferencia()).isCloseTo(0, within(0.001));

        serCaja(1);
        VentaService.ResumenCorte r1 = ventaService.resumenCompletoDe(LocalDate.now(), 1);
        assertThat(r1.numVentas - antes1.numVentas).isEqualTo(1);
        assertThat(r1.efectivo - antes1.efectivo).isCloseTo(100, within(0.001));

        VentaService.ResumenCorte g = ventaService.resumenGeneralDe(LocalDate.now());
        assertThat(g.numVentas - antesG.numVentas).isEqualTo(3);
        assertThat(g.porCaja).extracting(VentaService.VentasCaja::caja).contains(1, 2);
        Corte cg = cajaService.hacerCorteGeneral(g.efectivo + cajaService.fondoTotalDelDia(LocalDate.now()), "general PG");
        assertThat(cg.isGeneral()).isTrue();
        assertThat(cg.getFondoInicial()).isCloseTo(cajaService.fondoTotalDelDia(LocalDate.now()), within(0.001));
        assertThat(cg.getDiferencia()).isCloseTo(0, within(0.001));
        assertThat(cajaService.cortesDeHoyDeEstaCaja()).noneMatch(Corte::isGeneral);
        assertThat(cajaService.cortesDeHoy()).extracting(Corte::getId).contains(c2.getId(), cg.getId());
    }

    @Test
    void losFoliosSonUnicosAunqueVariasCajasCobrenALaVez() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Folio PG", 5, 1000);
        List<Cajero> cajeros = new ArrayList<>();
        for (int i = 0; i < 4; i++) cajeros.add(crearCajero("folio" + i + "-" + sufijo(), EnumSet.noneOf(Permiso.class)));
        String prefijo = configuracionService.obtener().getFolioPrefijo();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Venta>> futuros = new ArrayList<>();
        for (Cajero c : cajeros) {
            for (int j = 0; j < 5; j++) {
                futuros.add(pool.submit(() -> {
                    salida.await();
                    return sesionService.ejecutarComo(c, "10.0.0.5", () ->
                            ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1, true)), Venta.FormaPago.EFECTIVO, 10));
                }));
            }
        }
        salida.countDown();
        Set<String> folios = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        for (Future<Venta> f : futuros) {
            Venta v = f.get(60, TimeUnit.SECONDS);
            assertThat(v.getFolio()).startsWith(prefijo);
            folios.add(v.getFolio());
            ids.add(v.getId());
        }
        pool.shutdown();
        assertThat(folios).hasSize(20);   // 20 ventas, 20 folios distintos: salen de la secuencia del servidor
        assertThat(ids).hasSize(20);
    }
}
