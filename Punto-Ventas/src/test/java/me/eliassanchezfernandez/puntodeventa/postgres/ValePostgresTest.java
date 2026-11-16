package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Devolucion;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ValeMovimiento;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ValeRepository;
import me.eliassanchezfernandez.puntodeventa.service.DevolucionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ValeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos (o más) cajas cobran con el MISMO vale a la vez contra PostgreSQL real: el saldo solo se
 * gasta una vez; las demás ventas se rechazan completas (sin inventario descontado).
 */
class ValePostgresTest extends BasePostgresTest {

    @Autowired private DevolucionService devolucionService;
    @Autowired private ValeService valeService;
    @Autowired private ValeRepository valeRepo;

    @Test
    void variasCajasConElMismoValeNoGastanElSaldoDosVeces() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Producto p = producto("PG vale " + sufijo(), 50, 40);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), Venta.FormaPago.EFECTIVO, 1000);
        Devolucion dev = devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(v.getDetalles().get(0).getId(), 2, true)), "Vale",
                Devolucion.FormaReembolso.VALE), null);
        String codigo = dev.getValeCodigo();
        double stockAntes = stockActual(p);

        // 4 cajas: cada una cobra una pieza de $50 con el vale de $100 → solo caben 2
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Throwable>> futuros = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String ip = "10.0.9." + i;
            futuros.add(pool.submit(() -> {
                salida.await();
                try {
                    sesionService.ejecutarComo(admin, ip, () -> ventaService.cobrar(
                            List.of(ventaService.crearDetalle(p, 1)), List.of(PagoVenta.conVale(codigo, 50)), null));
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

        assertThat(errores.stream().filter(e -> e == null).count()).as("solo dos ventas caben en el vale").isEqualTo(2);
        assertThat(errores.stream().filter(e -> e != null)).allMatch(e -> e instanceof NegocioException);
        assertThat(valeRepo.saldoDe(dev.getValeId())).isEqualTo(0.0);
        assertThat(stockActual(p)).as("las rechazadas no descontaron").isEqualTo(stockAntes - 2);
        assertThat(valeService.movimientos(dev.getValeId()).stream()
                .filter(m -> m.getTipoEnum() == ValeMovimiento.Tipo.CANJE).count()).isEqualTo(2);
    }
}
