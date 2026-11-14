package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Categoria;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Tipo;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Entradas y salidas de efectivo (CRT-08): permiso MOVIMIENTOS_CAJA o
 * autorización de administrador (de un solo uso), validaciones, rastro
 * (cajero, caja, hora, cuánto había y cuánto queda, bitácora) y comprobante.
 */
class MovimientoCajaTest extends BaseServicioTest {

    @Autowired private MovimientoCajaService movimientoCajaService;
    @Autowired private CajaService cajaService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private TicketService ticketService;
    @Autowired private ConfiguracionService configuracionService;

    @Test
    void elCajeroSinPermisoNecesitaAutorizacionYSeConsume() throws Exception {
        iniciarComoAdmin();
        Cajero admin = sesionService.getCajeroActual();
        crearUsuario("cajeroEf", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        iniciarComo("cajeroEf", CLAVE_PRUEBA);

        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, 100, null, null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThat(movimientoCajaService.efectivoEsperadoSiPuedeVerlo()).isEmpty();
        assertThatThrownBy(() -> movimientoCajaService.movimientosDeHoy()).isInstanceOf(PermisoRequeridoException.class);

        Autorizacion aut = new Autorizacion(admin, Permiso.MOVIMIENTOS_CAJA, LocalDateTime.now());
        MovimientoCaja m = movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, 100, null, List.of(aut));
        assertThat(m.getId()).isNotNull();
        assertThat(m.getAutorizadoPor()).isEqualTo(admin.getNombreCompleto());
        assertThat(m.getAutorizadoPorId()).isEqualTo(admin.getId());
        assertThat(m.getCajeroNombre()).isEqualTo("Usuario cajeroEf");
        assertThat(aut.isConsumida()).as("la autorización es de un solo uso").isTrue();
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, 1, null, List.of(aut)))
                .isInstanceOf(PermisoRequeridoException.class);

        Auditoria a = auditoriaService.ultimas().stream().filter(x -> "EFECTIVO_SALIDA".equals(x.getAccion())).findFirst().orElseThrow();
        assertThat(a.getDetalle()).contains("Retiro de efectivo").contains("100");
        assertThat(a.getAutorizadoPor()).isEqualTo(admin.getNombreCompleto());
    }

    @Test
    void elGerenteLoTienePorDefectoYElMovimientoGuardaCuantoHabiaYCuantoQueda() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 500);
        crearUsuario("gerenteEf", Cajero.Rol.GERENTE, EnumSet.copyOf(Permiso.porDefecto(Cajero.Rol.GERENTE)));
        iniciarComo("gerenteEf", CLAVE_PRUEBA);
        assertThat(sesionService.tienePermiso(Permiso.MOVIMIENTOS_CAJA)).isTrue();

        MovimientoCaja entrada = movimientoCajaService.registrar(Tipo.ENTRADA, Categoria.CAMBIO, 150.50, "  ", null);
        assertThat(entrada.getConcepto()).isEqualTo("Entrada de cambio (morralla)");   // concepto por defecto
        assertThat(entrada.getEfectivoAntes()).isCloseTo(500, within(0.001));
        assertThat(entrada.getEfectivoDespues()).isCloseTo(650.5, within(0.001));
        assertThat(entrada.getCaja()).isEqualTo(cajaService.cajaActual());
        assertThat(entrada.getAutorizadoPor()).isNull();
        assertThat(entrada.getMontoConSigno()).isCloseTo(150.5, within(0.001));

        MovimientoCaja salida = movimientoCajaService.registrar(Tipo.SALIDA, Categoria.GASTO, 50, "Pago de luz", null);
        assertThat(salida.getEfectivoAntes()).isCloseTo(650.5, within(0.001));
        assertThat(salida.getEfectivoDespues()).isCloseTo(600.5, within(0.001));
        assertThat(salida.getMontoConSigno()).isCloseTo(-50, within(0.001));

        // El gerente puede ver los movimientos y el efectivo esperado aunque no haga cortes
        assertThat(movimientoCajaService.movimientosDeHoy()).extracting(MovimientoCaja::getId).contains(entrada.getId(), salida.getId());
        assertThat(movimientoCajaService.efectivoEsperadoSiPuedeVerlo()).hasValueSatisfying(v -> assertThat(v).isCloseTo(600.5, within(0.001)));
        assertThat(cajeroService.tieneHistorial(sesionService.getCajeroActual().getId())).isTrue();
    }

    @Test
    void validaMontoCategoriaYMotivo() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, 0, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("mayor que cero");
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, -10, null, null))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, Double.NaN, null, null))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, 5_000_000, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no puede pasar");
        // Una categoría de salida no es una entrada
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.ENTRADA, Categoria.RETIRO, 10, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no es una entrada");
        // Gastos y "otros" piden motivo; cambio y retiro no
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.SALIDA, Categoria.GASTO, 10, "", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("motivo");
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.ENTRADA, Categoria.OTRA_ENTRADA, 10, null, null))
                .isInstanceOf(NegocioException.class);
        assertThat(movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, 10, null, null).getConcepto())
                .isEqualTo("Retiro de efectivo");
        // Pago a proveedor: proveedor o motivo
        assertThatThrownBy(() -> movimientoCajaService.registrar(Tipo.SALIDA, Categoria.PAGO_PROVEEDOR, 10, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("proveedor");
        assertThatThrownBy(() -> movimientoCajaService.registrar(
                MovimientoCajaService.Solicitud.pagoProveedor(99_999L, null, 10, null), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya no existe");
        assertThat(Categoria.de(Tipo.ENTRADA)).containsExactly(Categoria.CAMBIO, Categoria.OTRA_ENTRADA);
        assertThat(Categoria.de(Tipo.SALIDA)).allMatch(c -> c.getTipo() == Tipo.SALIDA);
    }

    @Test
    void elPagoAProveedorGuardaProveedorYOrdenYSaleEnElCorte() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 1000);
        Proveedor prov = proveedorService.guardar(null, "Bimbo " + System.nanoTime(), null, null, null, null, null);
        MovimientoCaja pago = movimientoCajaService.registrar(
                MovimientoCajaService.Solicitud.pagoProveedor(prov.getId(), 77L, 320, ""), null);
        assertThat(pago.getProveedorId()).isEqualTo(prov.getId());
        assertThat(pago.getProveedorNombre()).isEqualTo(prov.getNombre());
        assertThat(pago.getOrdenCompraId()).isEqualTo(77L);
        assertThat(pago.getConcepto()).isEqualTo("Pago a " + prov.getNombre());

        CajaService.EstadoCaja e = cajaService.estadoTurnoActual();
        assertThat(e.monto(Categoria.PAGO_PROVEEDOR)).isCloseTo(320, within(0.001));
        assertThat(e.esperado()).isCloseTo(680, within(0.001));

        String comprobante = ticketService.documentoMovimientoCaja(pago, configuracionService.obtener()).aTexto(48);
        assertThat(comprobante).contains("SALIDA DE EFECTIVO").contains("Pago a proveedor").contains(prov.getNombre())
                .contains("Orden de compra #77").contains("SALE").contains("320.00");
        String bitacora = auditoriaService.ultimas().stream().filter(x -> "EFECTIVO_SALIDA".equals(x.getAccion()))
                .findFirst().orElseThrow().getDetalle();
        assertThat(bitacora).contains(prov.getNombre()).contains("1000.00").contains("680.00");
    }

    @Test
    void elMovimientoEsDeLaCajaDondeSeRegistraYNoCuentaEnOtra() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 100);
        movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, 60, null, null);
        assertThat(cajaService.estadoDe(cajaService.cajaActual(), LocalDate.now(), true, false).salidas).isCloseTo(60, within(0.001));
        assertThat(cajaService.estadoDe(7, LocalDate.now(), true, false).salidas).isZero();
        // El general sí lo ve (todas las cajas)
        CajaService.EstadoCaja g = cajaService.estadoGeneralDelDia();
        assertThat(g.salidas).isCloseTo(60, within(0.001));
        assertThat(g.esperado()).isCloseTo(40, within(0.001));
    }

    @Test
    void movimientosEntreFechasYComprobanteDeEntrada() throws Exception {
        iniciarComoAdmin();
        MovimientoCaja m = movimientoCajaService.registrar(Tipo.ENTRADA, Categoria.OTRA_ENTRADA, 12.5, "Préstamo del dueño", null);
        assertThat(movimientoCajaService.movimientosEntre(LocalDate.now().minusDays(1), LocalDate.now()))
                .extracting(MovimientoCaja::getId).contains(m.getId());
        assertThat(movimientoCajaService.movimientosEntre(LocalDate.now().minusDays(5), LocalDate.now().minusDays(1))).isEmpty();
        assertThatThrownBy(() -> movimientoCajaService.movimientosEntre(LocalDate.now(), LocalDate.now().minusDays(1)))
                .isInstanceOf(NegocioException.class);
        assertThat(movimientoCajaService.obtener(m.getId()).getConcepto()).isEqualTo("Préstamo del dueño");
        assertThatThrownBy(() -> movimientoCajaService.obtener(123_456L)).isInstanceOf(NegocioException.class);
        String texto = ticketService.documentoMovimientoCaja(m, configuracionService.obtener()).aTexto(32);
        assertThat(texto).contains("ENTRADA DE EFECTIVO").contains("ENTRA").contains("12.50").contains("Recibió");
        // Una venta normal no es un movimiento de caja
        assertThat(Venta.FormaPago.EFECTIVO).isNotNull();
    }
}
