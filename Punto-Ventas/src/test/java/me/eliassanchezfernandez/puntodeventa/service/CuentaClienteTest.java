package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.CuentaClienteMovimiento;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ClienteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoCajaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Clientes con crédito (fiado): venta a cuenta sola o en pago dividido, límite con
 * permiso o autorización, abonos (el efectivo cuenta en el corte), cancelación que
 * regresa el cargo, ajustes y permisos. El saldo guardado siempre coincide con la
 * suma del libro.
 */
class CuentaClienteTest extends BaseServicioTest {

    @Autowired private CuentaClienteService cuentaService;
    @Autowired private ClienteRepository clienteRepo;
    @Autowired private CajaService cajaService;
    @Autowired private MovimientoCajaService movimientoCajaService;
    @Autowired private MovimientoCajaRepository movimientoCajaRepo;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private TicketService ticketService;
    @Autowired private ConfiguracionService configuracionService;

    private Cliente cliente(String nombre, double limite) {
        return cuentaService.guardar(null, nombre + " " + UUID.randomUUID().toString().substring(0, 5),
                "282 141 0000", null, limite, true, null);
    }

    private double saldo(Cliente c) {
        return clienteRepo.saldoDe(c.getId());
    }

    private void cuadra(Cliente c) {
        assertThat(cuentaService.saldoSegunLibro(c.getId())).as("saldo = suma del libro").isCloseTo(saldo(c), within(0.001));
    }

    private Venta fiar(Producto p, double cantidad, Cliente c, double monto, List<Autorizacion> auts) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, cantidad)), List.of(PagoVenta.aCuenta(c.getId(), monto)), auts);
    }

    private boolean hayAuditoria(String accion) {
        return auditoriaService.ultimas().stream().anyMatch(a -> accion.equals(a.getAccion()));
    }

    @Test
    void ventaACuentaCargaAlClienteYElTicketLoDice() throws Exception {
        iniciarComoAdmin();
        Cliente c = cliente("Doña Lupe", 0);
        Producto p = producto("Fiado A", 100, 10);

        Venta v = fiar(p, 1, c, 100, null);

        assertThat(v.getClienteId()).isEqualTo(c.getId());
        assertThat(v.getClienteNombre()).isEqualTo(c.getNombre());
        assertThat(v.getSaldoCliente()).isCloseTo(100, within(0.001));
        assertThat(v.getCambio()).isCloseTo(0, within(0.001));
        assertThat(saldo(c)).isCloseTo(100, within(0.001));
        assertThat(stockActual(p)).isEqualTo(9);
        cuadra(c);
        List<CuentaClienteMovimiento> libro = cuentaService.estadoDeCuenta(c.getId(), null, null).movimientos();
        assertThat(libro).hasSize(1);
        assertThat(libro.get(0).getTipoEnum()).isEqualTo(CuentaClienteMovimiento.Tipo.CARGO_VENTA);
        assertThat(libro.get(0).getVentaId()).isEqualTo(v.getId());

        String ticket = ticketService.documentoVenta(v, configuracionService.obtener(), false).aTexto(48);
        assertThat(ticket).contains("A cuenta de " + c.getNombre()).contains("Saldo: $100.00");
    }

    @Test
    void pagoDivididoEfectivoYFiadoSoloElEfectivoEntraAlCajon() throws Exception {
        iniciarComoAdmin();
        Cliente c = cliente("Don Toño", 0);
        Producto p = producto("Fiado B", 100, 10);
        CajaService.EstadoCaja antes = cajaService.estadoDelDia();

        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, 100), PagoVenta.aCuenta(c.getId(), 30)), null);

        assertThat(v.getCambio()).isCloseTo(30, within(0.001));   // pagó 100 en efectivo + 30 fiado de un total de 100
        assertThat(saldo(c)).isCloseTo(30, within(0.001));
        CajaService.EstadoCaja despues = cajaService.estadoDelDia();
        assertThat(despues.ventas.credito - antes.ventas.credito).isCloseTo(30, within(0.001));
        assertThat(despues.ventas.efectivo - antes.ventas.efectivo).isCloseTo(70, within(0.001));
        assertThat(despues.esperado() - antes.esperado()).as("al cajón solo entra lo cobrado en efectivo").isCloseTo(70, within(0.001));
        cuadra(c);
    }

    @Test
    void sinClienteOConDosCuentasNoSeCobra() throws Exception {
        iniciarComoAdmin();
        Cliente a = cliente("Uno", 0);
        Cliente b = cliente("Dos", 0);
        Producto p = producto("Fiado C", 100, 10);
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(PagoVenta.aCuenta(null, 100)), null)).hasMessageContaining("cliente");
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(PagoVenta.aCuenta(a.getId(), 50), PagoVenta.aCuenta(b.getId(), 50)), null))
                .hasMessageContaining("un cliente");
        // El fiado tampoco puede pasar del total (el cambio solo sale del efectivo)
        assertThatThrownBy(() -> fiar(p, 1, a, 150, null)).isInstanceOf(NegocioException.class);
        assertThat(stockActual(p)).isEqualTo(10);
        assertThat(saldo(a)).isZero();
    }

    @Test
    void rebasarElLimiteExigePermisoOAutorizacionDeUnSoloUso() throws Exception {
        iniciarComoAdmin();
        Cajero admin = sesionService.getCajeroActual();
        Cliente c = cliente("Con límite", 100);
        Producto p = producto("Fiado D", 40, 20);
        crearUsuario("cajeroFiado", Cajero.Rol.CAJERO, EnumSet.of(Permiso.VENDER_A_CREDITO));
        iniciarComo("cajeroFiado", CLAVE_PRUEBA);

        fiar(p, 2, c, 80, null);                       // 80 de 100: cabe
        assertThat(saldo(c)).isCloseTo(80, within(0.001));

        assertThatThrownBy(() -> fiar(p, 1, c, 40, null))   // 120 > 100
                .isInstanceOf(PermisoRequeridoException.class)
                .hasMessageContaining("límite")
                .satisfies(e -> assertThat(((PermisoRequeridoException) e).getPermiso()).isEqualTo(Permiso.AJUSTAR_CREDITO));
        assertThat(saldo(c)).as("nada se cargó").isCloseTo(80, within(0.001));
        // (que tampoco se descuente inventario lo prueba FiadoEntreCajasTest, con transacciones reales)

        Autorizacion aut = new Autorizacion(admin, Permiso.AJUSTAR_CREDITO, LocalDateTime.now());
        Venta v = fiar(p, 1, c, 40, List.of(aut));
        assertThat(aut.isConsumida()).isTrue();
        assertThat(saldo(c)).isCloseTo(120, within(0.001));
        assertThat(v.getAutorizadaPorNombre()).contains(admin.getNombreCompleto());
        assertThat(hayAuditoria("CREDITO_LIMITE_EXCEDIDO")).isTrue();
        CuentaClienteMovimiento cargo = cuentaService.estadoDeCuenta(c.getId(), null, null).movimientos().get(1);
        assertThat(cargo.getAutorizadoPor()).isEqualTo(admin.getNombreCompleto());
        // La autorización ya se usó: otra venta que rebase vuelve a pedirla
        assertThatThrownBy(() -> fiar(p, 1, c, 40, List.of(aut))).isInstanceOf(PermisoRequeridoException.class);
        cuadra(c);
    }

    @Test
    void permisosParaFiarYDarDeAlta() throws Exception {
        iniciarComoAdmin();
        Cajero admin = sesionService.getCajeroActual();
        Cliente c = cliente("Permisos", 0);
        Producto p = producto("Fiado E", 50, 10);
        assertThat(Permiso.porDefecto(Cajero.Rol.GERENTE)).contains(Permiso.VENDER_A_CREDITO).doesNotContain(Permiso.AJUSTAR_CREDITO);
        assertThat(Permiso.porDefecto(Cajero.Rol.CAJERO)).doesNotContain(Permiso.VENDER_A_CREDITO);

        crearUsuario("cajeroSinFiado", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        iniciarComo("cajeroSinFiado", CLAVE_PRUEBA);
        assertThatThrownBy(() -> fiar(p, 1, c, 50, null))
                .isInstanceOf(PermisoRequeridoException.class)
                .satisfies(e -> assertThat(((PermisoRequeridoException) e).getPermiso()).isEqualTo(Permiso.VENDER_A_CREDITO));
        assertThatThrownBy(() -> cuentaService.crearRapido("Nuevo sin permiso", null)).isInstanceOf(PermisoRequeridoException.class);
        assertThat(stockActual(p)).isEqualTo(10);

        Autorizacion aut = new Autorizacion(admin, Permiso.VENDER_A_CREDITO, LocalDateTime.now());
        Cliente nuevo = cuentaService.crearRapido("Cliente al vuelo " + UUID.randomUUID().toString().substring(0, 4), List.of(aut));
        assertThat(aut.isConsumida()).as("el alta no consume: la consume el cobro").isFalse();
        fiar(p, 1, nuevo, 50, List.of(aut));
        assertThat(aut.isConsumida()).isTrue();
        assertThat(saldo(nuevo)).isCloseTo(50, within(0.001));
        // Abonar no exige permiso (es trabajo normal de la caja)
        cuentaService.registrarAbono(nuevo.getId(), 20, Venta.FormaPago.EFECTIVO, null);
        assertThat(saldo(nuevo)).isCloseTo(30, within(0.001));
        // Ajustar saldo sí: AJUSTAR_CREDITO
        assertThatThrownBy(() -> cuentaService.ajustar(nuevo.getId(), -30, "Perdonado", null))
                .isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void cambiarElLimiteDeUnClienteExigeAjustarCredito() throws Exception {
        iniciarComoAdmin();
        Cliente c = cliente("Límite", 200);
        crearUsuario("gerenteFiado", Cajero.Rol.GERENTE, EnumSet.copyOf(Permiso.porDefecto(Cajero.Rol.GERENTE)));
        iniciarComo("gerenteFiado", CLAVE_PRUEBA);
        // El gerente edita el teléfono sin problema…
        cuentaService.guardar(c.getId(), c.getNombre(), "55 1234 5678", "Paga los viernes", 200, true, null);
        // …pero subir el límite pide autorización
        assertThatThrownBy(() -> cuentaService.guardar(c.getId(), c.getNombre(), null, null, 5000, true, null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThat(clienteRepo.findById(c.getId()).orElseThrow().getLimiteCredito()).isEqualTo(200);
        // Y no se aceptan dos clientes con el mismo nombre
        assertThatThrownBy(() -> cuentaService.guardar(null, c.getNombre().toUpperCase(), null, null, 0, true, null))
                .hasMessageContaining("Ya hay un cliente");
    }

    @Test
    void abonosBajanLaDeudaYElEfectivoCuentaEnElCorte() throws Exception {
        iniciarComoAdmin();
        Cliente c = cliente("Abonos", 0);
        Producto p = producto("Fiado F", 200, 10);
        fiar(p, 1, c, 200, null);
        CajaService.EstadoCaja antes = cajaService.estadoDelDia();
        CuentaClienteService.AbonosPeriodo abAntes = cuentaService.abonosEntre(LocalDate.now().atStartOfDay(),
                LocalDate.now().plusDays(1).atStartOfDay(), null);

        CuentaClienteService.Abono a1 = cuentaService.registrarAbono(c.getId(), 50, Venta.FormaPago.EFECTIVO, "primer pago");
        assertThat(a1.saldoDespues()).isCloseTo(150, within(0.001));
        assertThat(a1.movimientoCaja()).isNotNull();
        MovimientoCaja mc = movimientoCajaRepo.findById(a1.movimientoCaja().getId()).orElseThrow();
        assertThat(mc.getCategoria()).isEqualTo(MovimientoCaja.Categoria.ABONO_CLIENTE);
        assertThat(mc.esEntrada()).isTrue();
        CuentaClienteService.Abono a2 = cuentaService.registrarAbono(c.getId(), 30, Venta.FormaPago.TRANSFERENCIA, null);
        assertThat(a2.movimientoCaja()).as("la transferencia no entra al cajón").isNull();
        assertThat(saldo(c)).isCloseTo(120, within(0.001));

        CajaService.EstadoCaja despues = cajaService.estadoDelDia();
        assertThat(despues.monto(MovimientoCaja.Categoria.ABONO_CLIENTE) - antes.monto(MovimientoCaja.Categoria.ABONO_CLIENTE))
                .isCloseTo(50, within(0.001));
        assertThat(despues.esperado() - antes.esperado()).as("efectivo esperado: solo el abono en efectivo").isCloseTo(50, within(0.001));
        CuentaClienteService.AbonosPeriodo ab = cuentaService.abonosEntre(LocalDate.now().atStartOfDay(),
                LocalDate.now().plusDays(1).atStartOfDay(), null);
        assertThat(ab.efectivo() - abAntes.efectivo()).isCloseTo(50, within(0.001));
        assertThat(ab.otros() - abAntes.otros()).isCloseTo(30, within(0.001));

        // Más de lo que debe: no
        assertThatThrownBy(() -> cuentaService.registrarAbono(c.getId(), 500, Venta.FormaPago.EFECTIVO, null))
                .hasMessageContaining("mayor que lo que debe");
        // Vales no es forma de abono
        assertThatThrownBy(() -> cuentaService.registrarAbono(c.getId(), 10, Venta.FormaPago.VALES, null))
                .isInstanceOf(NegocioException.class);
        // El abono no se captura a mano como entrada de efectivo
        assertThatThrownBy(() -> movimientoCajaService.registrar(MovimientoCaja.Tipo.ENTRADA, MovimientoCaja.Categoria.ABONO_CLIENTE, 10, "x", null))
                .hasMessageContaining("Clientes");
        assertThat(MovimientoCaja.Categoria.de(MovimientoCaja.Tipo.ENTRADA)).doesNotContain(MovimientoCaja.Categoria.ABONO_CLIENTE);
        assertThat(hayAuditoria("CLIENTE_ABONO")).isTrue();

        // Comprobante del abono con el saldo nuevo
        String comprobante = ticketService.documentoAbono(a1.movimiento(), c.getNombre(), configuracionService.obtener()).aTexto(48);
        assertThat(comprobante).contains("ABONO A CUENTA").contains("$150.00");

        // Estado de cuenta: cargos, abonos y saldo
        CuentaClienteService.EstadoCuenta ec = cuentaService.estadoDeCuenta(c.getId(), LocalDate.now(), LocalDate.now());
        assertThat(ec.cargos()).isCloseTo(200, within(0.001));
        assertThat(ec.abonos()).isCloseTo(80, within(0.001));
        assertThat(ec.saldoFinal()).isCloseTo(120, within(0.001));
        assertThat(ticketService.documentoEstadoCuenta(ec, configuracionService.obtener()).aTexto(48)).contains("ESTADO DE CUENTA").contains("$120.00");
        cuadra(c);
    }

    @Test
    void cancelarUnaVentaACuentaRegresaElCargo() throws Exception {
        iniciarComoAdmin();
        Cliente c = cliente("Cancelada", 0);
        Producto p = producto("Fiado G", 100, 10);
        Venta v = fiar(p, 1, c, 100, null);
        cuentaService.registrarAbono(c.getId(), 40, Venta.FormaPago.EFECTIVO, null);
        assertThat(saldo(c)).isCloseTo(60, within(0.001));

        ventaService.cancelar(v.getId(), "Se equivocó de producto", null);

        assertThat(saldo(c)).as("ya había abonado 40: le quedan a favor").isCloseTo(-40, within(0.001));
        List<CuentaClienteMovimiento> libro = cuentaService.estadoDeCuenta(c.getId(), null, null).movimientos();
        assertThat(libro).extracting(CuentaClienteMovimiento::getTipoEnum).containsExactly(
                CuentaClienteMovimiento.Tipo.CARGO_VENTA, CuentaClienteMovimiento.Tipo.ABONO, CuentaClienteMovimiento.Tipo.CANCELACION_VENTA);
        assertThat(libro.get(2).getMonto()).isCloseTo(-100, within(0.001));
        cuadra(c);
        // Una venta sin fiado se cancela igual que siempre
        Venta contado = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, 100);
        ventaService.cancelar(contado.getId(), "Prueba", null);
        assertThat(saldo(c)).isCloseTo(-40, within(0.001));
    }

    @Test
    void devolucionParcialBajaLaDeudaSoloHastaLoPendienteDeEsaVenta() throws Exception {
        iniciarComoAdmin();
        Cliente c = cliente("Devolución", 0);
        Producto p = producto("Fiado H", 50, 10);
        Venta v = fiar(p, 2, c, 100, null);
        assertThat(cuentaService.abonarPorDevolucion(v.getId(), v.getFolio(), 30, null)).isCloseTo(30, within(0.001));
        assertThat(saldo(c)).isCloseTo(70, within(0.001));
        // Pedir más de lo que queda de esa venta: solo se aplica lo pendiente (el resto se devuelve en efectivo)
        assertThat(cuentaService.abonarPorDevolucion(v.getId(), v.getFolio(), 500, null)).isCloseTo(70, within(0.001));
        assertThat(saldo(c)).isCloseTo(0, within(0.001));
        cuadra(c);
    }

    @Test
    void ajusteConMotivoQuedaEnElLibroYLaBitacora() throws Exception {
        iniciarComoAdmin();
        Cliente c = cliente("Libreta", 0);
        assertThatThrownBy(() -> cuentaService.ajustar(c.getId(), 350, " ", null)).hasMessageContaining("motivo");
        CuentaClienteMovimiento m = cuentaService.ajustar(c.getId(), 350, "Deuda anterior de la libreta", null);
        assertThat(m.getSaldoDespues()).isCloseTo(350, within(0.001));
        cuentaService.ajustar(c.getId(), -50, "Descuento por pronto pago", null);
        assertThat(saldo(c)).isCloseTo(300, within(0.001));
        assertThat(hayAuditoria("CLIENTE_AJUSTE")).isTrue();
        assertThat(cuentaService.conSaldo()).extracting(Cliente::getId).contains(c.getId());
        cuadra(c);
    }

    @Test
    void clienteDesactivadoNoCompraACuentaPeroPuedeAbonar() throws Exception {
        iniciarComoAdmin();
        Cliente c = cliente("Moroso", 0);
        Producto p = producto("Fiado I", 50, 10);
        fiar(p, 1, c, 50, null);
        cuentaService.guardar(c.getId(), c.getNombre(), null, null, 0, false, null);
        assertThatThrownBy(() -> fiar(p, 1, c, 50, null)).hasMessageContaining("desactivado");
        cuentaService.registrarAbono(c.getId(), 50, Venta.FormaPago.EFECTIVO, null);
        assertThat(saldo(c)).isCloseTo(0, within(0.001));
        assertThat(cuentaService.buscar(c.getNombre(), false)).isEmpty();
        assertThat(cuentaService.buscar(c.getNombre(), true)).hasSize(1);
    }

    @Test
    void laBusquedaIgnoraAcentosYEncuentraPorTelefono() {
        Cliente c = new Cliente();
        c.setNombre("José Martínez");
        c.setTelefono("282-141-6799");
        assertThat(CuentaClienteService.coincide(c, "jose mart")).isTrue();
        assertThat(CuentaClienteService.coincide(c, "1416")).isTrue();
        assertThat(CuentaClienteService.coincide(c, "lupe")).isFalse();
    }
}
