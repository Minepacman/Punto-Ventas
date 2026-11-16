package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.CuentaClienteMovimiento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Devolucion;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ClienteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoCajaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Devoluciones de ventas a cuenta (fiado): el reembolso baja primero lo que el cliente sigue
 * debiendo de esa venta (sin dejarle saldo a favor) y solo el resto sale del cajón en efectivo.
 * El saldo siempre es la suma del libro y el efectivo esperado del corte solo baja por lo
 * entregado en efectivo.
 */
class DevolucionFiadoTest extends BaseServicioTest {

    @Autowired private DevolucionService devolucionService;
    @Autowired private CuentaClienteService cuentaService;
    @Autowired private ClienteRepository clienteRepo;
    @Autowired private MovimientoCajaRepository movimientoCajaRepo;
    @Autowired private CajaService cajaService;
    @Autowired private TicketService ticketService;
    @Autowired private ConfiguracionService configuracionService;

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    private Cliente cliente(String nombre) {
        return cuentaService.guardar(null, nombre + " " + UUID.randomUUID().toString().substring(0, 5),
                null, null, 0, true, null);
    }

    private double saldo(Cliente c) {
        return clienteRepo.saldoDe(c.getId());
    }

    private void cuadra(Cliente c) {
        assertThat(cuentaService.saldoSegunLibro(c.getId())).as("saldo = suma del libro").isCloseTo(saldo(c), within(0.001));
    }

    private static DetalleVenta renglon(Venta v) {
        return v.getDetalles().get(0);
    }

    private Devolucion devolver(Venta v, double cantidad) {
        return devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(renglon(v).getId(), cantidad, true)), "No le gustó"), null);
    }

    private List<CuentaClienteMovimiento> libro(Cliente c) {
        return cuentaService.estadoDeCuenta(c.getId(), null, null).movimientos();
    }

    @Test
    void ventaTodaACuentaSeDevuelveALaCuentaSinSacarEfectivo() {
        Cliente c = cliente("Doña Chuy");
        Producto p = producto("Fiado dev A", 50, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), List.of(PagoVenta.aCuenta(c.getId(), 100)), null);
        assertThat(saldo(c)).isCloseTo(100, within(0.001));
        assertThat(devolucionService.estadoDe(v.getId()).pendienteACuenta()).isCloseTo(100, within(0.001));
        CajaService.EstadoCaja antes = cajaService.estadoDelDia();

        Devolucion dev = devolver(v, 1);

        assertThat(dev.getTotal()).isEqualTo(50.0);
        assertThat(dev.getACuentaOCero()).isEqualTo(50.0);
        assertThat(dev.getEnEfectivo()).isEqualTo(0.0);
        assertThat(dev.getMovimientoCajaId()).as("no sale efectivo").isNull();
        assertThat(dev.getFormaTexto()).isEqualTo("A cuenta del cliente");
        assertThat(saldo(c)).isCloseTo(50, within(0.001));
        cuadra(c);
        CuentaClienteMovimiento ultimo = libro(c).get(libro(c).size() - 1);
        assertThat(ultimo.getTipoEnum()).isEqualTo(CuentaClienteMovimiento.Tipo.DEVOLUCION);
        assertThat(ultimo.getMonto()).isCloseTo(-50, within(0.001));
        assertThat(ultimo.getVentaId()).isEqualTo(v.getId());

        CajaService.EstadoCaja despues = cajaService.estadoDelDia();
        assertThat(despues.esperado() - antes.esperado()).as("el cajón no cambia").isCloseTo(0, within(0.001));
        assertThat(despues.ventas.totalDevoluciones - antes.ventas.totalDevoluciones).isCloseTo(50, within(0.001));
        assertThat(despues.ventas.devolucionesACuenta - antes.ventas.devolucionesACuenta).isCloseTo(50, within(0.001));

        String comprobante = ticketService.documentoDevolucion(devolucionService.obtener(dev.getId()),
                configuracionService.obtener()).aTexto(48);
        assertThat(comprobante).contains("A su cuenta").doesNotContain("En efectivo");

        // Lo último del renglón: la cuenta queda en cero
        Devolucion dev2 = devolver(v, 1);
        assertThat(dev2.getACuentaOCero()).isEqualTo(50.0);
        assertThat(saldo(c)).isCloseTo(0, within(0.001));
        cuadra(c);
        assertThat(cajaService.estadoDelDia().esperado() - antes.esperado()).isCloseTo(0, within(0.001));
    }

    @Test
    void siElClienteYaAbonoSeBajaLoQueDebeYElRestoSeEntregaEnEfectivo() {
        Cliente c = cliente("Don Beto");
        Producto p = producto("Fiado dev B", 50, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), List.of(PagoVenta.aCuenta(c.getId(), 100)), null);
        cuentaService.registrarAbono(c.getId(), 70, Venta.FormaPago.EFECTIVO, null);
        assertThat(saldo(c)).isCloseTo(30, within(0.001));
        assertThat(devolucionService.estadoDe(v.getId()).pendienteACuenta()).as("tope: lo que debe hoy").isCloseTo(30, within(0.001));
        CajaService.EstadoCaja antes = cajaService.estadoDelDia();

        Devolucion dev = devolver(v, 2);

        assertThat(dev.getTotal()).isEqualTo(100.0);
        assertThat(dev.getACuentaOCero()).isEqualTo(30.0);
        assertThat(dev.getEnEfectivo()).isEqualTo(70.0);
        assertThat(dev.getFormaTexto()).isEqualTo("A cuenta y efectivo");
        assertThat(saldo(c)).as("sin saldo a favor").isCloseTo(0, within(0.001));
        cuadra(c);
        MovimientoCaja m = movimientoCajaRepo.findById(dev.getMovimientoCajaId()).orElseThrow();
        assertThat(m.getCategoria()).isEqualTo(MovimientoCaja.Categoria.DEVOLUCION);
        assertThat(m.getTipo()).isEqualTo(MovimientoCaja.Tipo.SALIDA);
        assertThat(m.getMonto()).isEqualTo(70.0);

        CajaService.EstadoCaja despues = cajaService.estadoDelDia();
        assertThat(despues.esperado() - antes.esperado()).as("solo sale el efectivo").isCloseTo(-70, within(0.001));
        assertThat(despues.ventas.devolucionesACuenta - antes.ventas.devolucionesACuenta).isCloseTo(30, within(0.001));

        String comprobante = ticketService.documentoDevolucion(devolucionService.obtener(dev.getId()),
                configuracionService.obtener()).aTexto(48);
        assertThat(comprobante).contains("A su cuenta").contains("$30.00").contains("En efectivo").contains("$70.00");
    }

    @Test
    void pagoMixtoPrimeroLaParteACuentaLuegoEfectivoYYaNoSeCancela() {
        Cliente c = cliente("Doña Mari");
        Producto p = producto("Fiado dev C", 100, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)),
                List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, 120), PagoVenta.aCuenta(c.getId(), 80)), null);
        assertThat(saldo(c)).isCloseTo(80, within(0.001));
        CajaService.EstadoCaja antes = cajaService.estadoDelDia();

        Devolucion dev = devolver(v, 1);

        assertThat(dev.getTotal()).isEqualTo(100.0);
        assertThat(dev.getACuentaOCero()).isEqualTo(80.0);
        assertThat(dev.getEnEfectivo()).isEqualTo(20.0);
        assertThat(movimientoCajaRepo.findById(dev.getMovimientoCajaId()).orElseThrow().getMonto()).isEqualTo(20.0);
        assertThat(saldo(c)).isCloseTo(0, within(0.001));
        cuadra(c);
        assertThat(cajaService.estadoDelDia().esperado() - antes.esperado()).isCloseTo(-20, within(0.001));

        // Con artículos devueltos la venta ya no se cancela (primero esa regla) y la cuenta no se toca
        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "Error", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("artículos devueltos");
        assertThat(saldo(c)).isCloseTo(0, within(0.001));
        cuadra(c);

        // El otro artículo: ya no queda nada a cuenta, todo en efectivo
        Devolucion dev2 = devolver(v, 1);
        assertThat(dev2.getACuentaOCero()).isEqualTo(0.0);
        assertThat(dev2.getACuenta()).isNull();
        assertThat(dev2.getEnEfectivo()).isEqualTo(100.0);
        assertThat(dev2.getFormaTexto()).isEqualTo("Efectivo");
        assertThat(saldo(c)).isCloseTo(0, within(0.001));
        cuadra(c);
        CajaService.EstadoCaja despues = cajaService.estadoDelDia();
        assertThat(despues.esperado() - antes.esperado()).isCloseTo(-120, within(0.001));
        assertThat(despues.ventas.devolucionesACuenta - antes.ventas.devolucionesACuenta).isCloseTo(80, within(0.001));
    }

    @Test
    void cancelarUnaVentaACuentaSinDevolucionesSigueRegresandoElCargo() {
        Cliente c = cliente("Don Pepe");
        Producto p = producto("Fiado dev D", 40, 10);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(PagoVenta.aCuenta(c.getId(), 40)), null);

        ventaService.cancelar(v.getId(), "Se equivocó", null);

        assertThat(saldo(c)).isCloseTo(0, within(0.001));
        cuadra(c);
        assertThat(libro(c).get(libro(c).size() - 1).getTipoEnum()).isEqualTo(CuentaClienteMovimiento.Tipo.CANCELACION_VENTA);
        assertThat(stockActual(p)).isEqualTo(10);
    }
}
