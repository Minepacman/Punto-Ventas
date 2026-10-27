package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.PromocionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.PromocionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos cajas venden el mismo producto al mismo tiempo: con el UPDATE
 * condicionado nunca se vende más de lo que hay, y el cupo "hasta agotar" de
 * una promoción tampoco se rebasa. Cada hilo actúa como su propio cajero
 * (ejecutarComo) para no depender de la sesión de escritorio compartida.
 */
class ConcurrenciaPostgresTest extends BasePostgresTest {

    @Autowired private PromocionService promocionService;
    @Autowired private PromocionRepository promocionRepo;
    @Autowired private VentaRepository ventaRepo;

    /** Lanza N hilos que arrancan al mismo tiempo; devuelve los errores (null = la venta se cobró) */
    private List<Throwable> enParalelo(List<Runnable> acciones) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(acciones.size());
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Throwable>> futuros = new ArrayList<>();
        for (Runnable r : acciones) {
            futuros.add(pool.submit(() -> {
                salida.await();
                try {
                    r.run();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        salida.countDown();
        List<Throwable> errores = new ArrayList<>();
        for (Future<Throwable> f : futuros) errores.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return errores;
    }

    @Test
    void dosCajasNoPuedenVenderMasDeLoQueHay() throws Exception {
        iniciarComoAdmin();
        Cajero c1 = crearCajero("caja1-" + sufijo(), EnumSet.noneOf(Permiso.class));
        Cajero c2 = crearCajero("caja2-" + sufijo(), EnumSet.noneOf(Permiso.class));
        Producto p = producto("Disputado", 10, 10);   // hay 10; cada caja quiere 7

        List<Throwable> errores = enParalelo(List.of(
                () -> sesionService.ejecutarComo(c1, "10.0.0.1", () -> cobrar(p, 7)),
                () -> sesionService.ejecutarComo(c2, "10.0.0.2", () -> cobrar(p, 7))));

        long exitosas = errores.stream().filter(e -> e == null).count();
        assertThat(exitosas).as("solo una caja alcanza existencia").isEqualTo(1);
        Throwable fallo = errores.stream().filter(e -> e != null).findFirst().orElseThrow();
        assertThat(fallo).isInstanceOf(PermisoRequeridoException.class);
        assertThat(((PermisoRequeridoException) fallo).getPermiso()).isEqualTo(Permiso.VENDER_SIN_STOCK);
        assertThat(stockActual(p)).isEqualTo(3);   // 10 − 7, nunca −4
        assertThat(productoService.movimientosDe(p.getId()).stream()
                .filter(m -> m.getTipo() == me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario.Tipo.VENTA).count()).isEqualTo(1);
    }

    @Test
    void muchasVentasSimultaneasDelMismoProductoCuadranAlCentavo() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Masivo", 5, 100);
        List<Cajero> cajeros = new ArrayList<>();
        for (int i = 0; i < 6; i++) cajeros.add(crearCajero("masivo" + i + "-" + sufijo(), EnumSet.noneOf(Permiso.class)));
        List<Runnable> acciones = new ArrayList<>();
        for (Cajero c : cajeros) {
            for (int j = 0; j < 3; j++) acciones.add(() -> sesionService.ejecutarComo(c, "10.0.0.9", () -> cobrar(p, 4)));
        }
        List<Throwable> errores = enParalelo(acciones);   // 18 ventas de 4 = 72 ≤ 100: todas deben cobrarse
        assertThat(errores).allMatch(e -> e == null);
        assertThat(stockActual(p)).isEqualTo(28);
    }

    @Test
    void elCupoHastaAgotarNoSeRebasaEntreCajas() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Promo compartida", 10, 100);
        Promocion promo = new Promocion();
        promo.setProducto(p);
        promo.setTipo(Promocion.Tipo.PORCENTAJE);
        promo.setValor(50);
        promo.setLimitePiezas(5);
        promo.setFechaInicio(LocalDate.now().minusDays(1));
        promo = promocionService.guardar(promo);
        Cajero c1 = crearCajero("promo1-" + sufijo(), EnumSet.noneOf(Permiso.class));
        Cajero c2 = crearCajero("promo2-" + sufijo(), EnumSet.noneOf(Permiso.class));

        // Las dos cajas arman el ticket con 4 piezas en promoción (en pantalla caben) y cobran a la vez
        DetalleVenta d1 = ventaService.crearDetalle(p, 4);
        DetalleVenta d2 = ventaService.crearDetalle(p, 4);
        assertThat(d1.getPiezasPromocion()).isEqualTo(4);
        List<Throwable> errores = enParalelo(List.of(
                () -> sesionService.ejecutarComo(c1, "10.0.0.1", () -> ventaService.cobrar(List.of(d1), Venta.FormaPago.EFECTIVO, 100)),
                () -> sesionService.ejecutarComo(c2, "10.0.0.2", () -> ventaService.cobrar(List.of(d2), Venta.FormaPago.EFECTIVO, 100))));

        long exitosas = errores.stream().filter(e -> e == null).count();
        assertThat(exitosas).isEqualTo(1);
        Throwable fallo = errores.stream().filter(e -> e != null).findFirst().orElseThrow();
        assertThat(fallo).isInstanceOf(NegocioException.class).hasMessageContaining("se agotó");
        double usadas = promocionRepo.findById(promo.getId()).orElseThrow().getPiezasUsadasOCero();
        assertThat(usadas).isEqualTo(4);                       // 4, no 8
        assertThat(stockActual(p)).isEqualTo(96);              // la venta rechazada se revirtió completa (ni stock ni venta)
        assertThat(ventaRepo.count()).isGreaterThan(0);
    }

    private Venta cobrar(Producto p, double cantidad) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, cantidad, true)), Venta.FormaPago.EFECTIVO, 1000);
    }
}
