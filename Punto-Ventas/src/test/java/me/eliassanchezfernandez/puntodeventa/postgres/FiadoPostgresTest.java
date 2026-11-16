package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ClienteRepository;
import me.eliassanchezfernandez.puntodeventa.service.CuentaClienteService;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Fiado con varias cajas contra PostgreSQL real (aquí las transacciones SÍ corren a la vez):
 * el límite del cliente se revisa en el UPDATE condicionado, así que varias cajas que le fían
 * al mismo tiempo nunca lo rebasan, y el saldo guardado siempre es la suma del libro.
 */
class FiadoPostgresTest extends BasePostgresTest {

    @Autowired private CuentaClienteService cuentaService;
    @Autowired private ClienteRepository clienteRepo;

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
    void variasCajasFianAlMismoClienteYElLimiteNoSeRebasa() throws Exception {
        iniciarComoAdmin();
        Cajero cajero = crearCajero("fiapg" + sufijo(), EnumSet.of(Permiso.VENDER_A_CREDITO));
        Cliente c = cuentaService.guardar(null, "PG fiado " + sufijo(), null, null, 100, true, null);
        Producto p = producto("PG fiado", 30, 50);

        List<Runnable> acciones = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String ip = "10.0.1." + i;
            acciones.add(() -> sesionService.ejecutarComo(cajero, ip, () -> ventaService.cobrar(
                    List.of(ventaService.crearDetalle(p, 1)), List.of(PagoVenta.aCuenta(c.getId(), 30)), null)));
        }
        List<Throwable> errores = enParalelo(acciones);

        long exitos = errores.stream().filter(e -> e == null).count();
        assertThat(exitos).as("caben 3 ventas de 30 en un límite de 100").isEqualTo(3);
        assertThat(errores.stream().filter(e -> e != null)).allMatch(e -> e instanceof PermisoRequeridoException);
        assertThat(clienteRepo.saldoDe(c.getId())).isCloseTo(90, within(0.001));
        assertThat(cuentaService.saldoSegunLibro(c.getId())).isCloseTo(90, within(0.001));
        assertThat(stockActual(p)).isEqualTo(47);
    }

    @Test
    void cancelarYAbonarDejanElSaldoIgualAlLibro() throws Exception {
        iniciarComoAdmin();
        Cliente c = cuentaService.guardar(null, "PG libro " + sufijo(), null, null, 0, true, null);
        Producto p = producto("PG libro", 50, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), List.of(PagoVenta.aCuenta(c.getId(), 100)), null);
        cuentaService.registrarAbono(c.getId(), 25, Venta.FormaPago.TRANSFERENCIA, null);
        ventaService.cancelar(v.getId(), "Prueba PG", null);
        assertThat(clienteRepo.saldoDe(c.getId())).isCloseTo(-25, within(0.001));
        assertThat(cuentaService.saldoSegunLibro(c.getId())).isCloseTo(-25, within(0.001));
    }
}
