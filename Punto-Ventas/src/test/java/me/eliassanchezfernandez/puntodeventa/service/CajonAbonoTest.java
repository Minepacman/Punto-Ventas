package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ModoImpresora;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El abono en efectivo de un cliente (fiado) abre el cajón con las mismas reglas que una venta en
 * efectivo (opción encendida + impresora térmica) y queda en la bitácora; con tarjeta o
 * transferencia no se abre.
 */
class CajonAbonoTest extends BaseServicioTest {

    @Autowired private ImpresionService impresionService;
    @Autowired private DispositivosService dispositivosService;
    @Autowired private CuentaClienteService cuentaService;
    @Autowired private AuditoriaService auditoriaService;

    private ImpresionServiceTest.ImpresoraFalsa impresora;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        impresora = new ImpresionServiceTest.ImpresoraFalsa();
        impresionService.setFabrica(c -> impresora);
    }

    @AfterEach
    void restaurar() {
        impresionService.setFabrica(null);
    }

    private Cliente clienteQueDebe(double monto) {
        Cliente c = cuentaService.guardar(null, "Cajón " + UUID.randomUUID().toString().substring(0, 5), null, null, 0, true, null);
        Producto p = producto("Cajón abono " + UUID.randomUUID().toString().substring(0, 4), monto, 10);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(PagoVenta.aCuenta(c.getId(), monto)), null);
        return c;
    }

    private long aperturasEnBitacora() {
        return auditoriaService.ultimas().stream()
                .filter(a -> ImpresionService.ACCION_CAJON_ABIERTO.equals(a.getAccion()) && a.getDetalle() != null
                        && a.getDetalle().contains("abono en efectivo"))
                .count();
    }

    @Test
    void elAbonoEnEfectivoAbreElCajonYQuedaEnLaBitacora() {
        dispositivosService.guardarImpresora(ModoImpresora.TERMICA, "Impresora de prueba", "CP850", false, 1, true, 80);
        Cliente c = clienteQueDebe(100);
        long antes = aperturasEnBitacora();

        var abono = cuentaService.registrarAbono(c.getId(), 40, Venta.FormaPago.EFECTIVO, null);
        ImpresionService.Resultado r = impresionService.despuesDeAbono(abono);

        assertThat(r.estado()).isEqualTo(ImpresionService.Resultado.Estado.IMPRESO);
        assertThat(impresora.aperturasDeCajon).isEqualTo(1);
        assertThat(aperturasEnBitacora()).isEqualTo(antes + 1);
    }

    @Test
    void conTarjetaOTransferenciaNoSeAbre() {
        dispositivosService.guardarImpresora(ModoImpresora.TERMICA, "Impresora de prueba", "CP850", false, 1, true, 80);
        Cliente c = clienteQueDebe(100);

        var tarjeta = cuentaService.registrarAbono(c.getId(), 30, Venta.FormaPago.TARJETA, null);
        var transferencia = cuentaService.registrarAbono(c.getId(), 30, Venta.FormaPago.TRANSFERENCIA, null);

        assertThat(impresionService.despuesDeAbono(tarjeta).estado()).isEqualTo(ImpresionService.Resultado.Estado.OMITIDO);
        assertThat(impresionService.despuesDeAbono(transferencia).estado()).isEqualTo(ImpresionService.Resultado.Estado.OMITIDO);
        assertThat(impresora.aperturasDeCajon).isZero();
    }

    @Test
    void conLaOpcionApagadaNoSeAbre() {
        dispositivosService.guardarImpresora(ModoImpresora.TERMICA, "Impresora de prueba", "CP850", false, 1, false, 80);
        Cliente c = clienteQueDebe(100);

        var abono = cuentaService.registrarAbono(c.getId(), 40, Venta.FormaPago.EFECTIVO, null);

        assertThat(impresionService.despuesDeAbono(abono).estado()).isEqualTo(ImpresionService.Resultado.Estado.OMITIDO);
        assertThat(impresora.aperturasDeCajon).isZero();
    }
}
