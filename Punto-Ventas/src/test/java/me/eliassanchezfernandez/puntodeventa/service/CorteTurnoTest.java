package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Categoria;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Tipo;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CorteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Corte por turno (CRT-09): el periodo arranca en el corte anterior de la
 * caja, el efectivo inicial es lo que ese corte dejó, las entradas y salidas
 * de efectivo suman y restan, y el reporte del día sigue acumulando todo.
 */
class CorteTurnoTest extends BaseServicioTest {

    @Autowired private CajaService cajaService;
    @Autowired private MovimientoCajaService movimientoCajaService;
    @Autowired private CorteRepository corteRepo;
    @Autowired private ImpresionService impresionService;
    @Autowired private ConfiguracionService configuracionService;

    private Venta venderEfectivo(Producto p, double pago) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.EFECTIVO, pago);
    }

    @Test
    void sinCortesNiFondoElTurnoEmpiezaConElDiaYEnCero() throws Exception {
        iniciarComoAdmin();
        CajaService.Turno t = cajaService.turnoActual();
        assertThat(t.inicio()).isEqualTo(LocalDate.now().atStartOfDay());
        assertThat(t.efectivoInicial()).isZero();
        assertThat(t.corteAnterior()).isNull();
        assertThat(t.inicialEsFondo()).isTrue();
        CajaService.EstadoCaja e = cajaService.estadoTurnoActual();
        assertThat(e.esperado()).isZero();
        assertThat(e.movimientos).isEmpty();
    }

    @Test
    void lasEntradasYSalidasSumanYRestanEnElEsperadoDelTurnoYDelCorte() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 500);
        Producto p = producto("Leche", 30, 50);
        venderEfectivo(p, 50);                                                      // +30 en efectivo
        movimientoCajaService.registrar(Tipo.ENTRADA, Categoria.CAMBIO, 200, null, null);
        movimientoCajaService.registrar(Tipo.SALIDA, Categoria.GASTO, 80, "Pago del agua", null);
        movimientoCajaService.registrar(Tipo.SALIDA, Categoria.RETIRO, 100, null, null);

        CajaService.EstadoCaja e = cajaService.estadoTurnoActual();
        assertThat(e.efectivoInicial).isEqualTo(500);
        assertThat(e.ventas.efectivo).isCloseTo(30, within(0.001));
        assertThat(e.entradas).isCloseTo(200, within(0.001));
        assertThat(e.salidas).isCloseTo(180, within(0.001));
        assertThat(e.monto(Categoria.CAMBIO)).isCloseTo(200, within(0.001));
        assertThat(e.monto(Categoria.GASTO)).isCloseTo(80, within(0.001));
        assertThat(e.monto(Categoria.RETIRO)).isCloseTo(100, within(0.001));
        assertThat(e.monto(Categoria.PAGO_PROVEEDOR)).isZero();
        assertThat(e.esperado()).isCloseTo(500 + 30 + 200 - 180, within(0.001));
        assertThat(e.movimientos).hasSize(3);

        Corte c = cajaService.hacerCorte(550, 500.0, "cuadra");
        assertThat(c.getEfectivoEsperado()).isCloseTo(550, within(0.001));
        assertThat(c.getEntradasOCero()).isCloseTo(200, within(0.001));
        assertThat(c.getSalidasOCero()).isCloseTo(180, within(0.001));
        assertThat(c.getEfectivoInicialOFondo()).isEqualTo(500);
        assertThat(c.getEfectivoDejado()).isEqualTo(500);
        assertThat(c.getRetiro()).isCloseTo(50, within(0.001));
        assertThat(c.getDiferencia()).isCloseTo(0, within(0.001));
        assertThat(c.esTurno()).isFalse();   // el primer corte del día arranca a las 00:00

        // Los movimientos ya cortados no se repiten en el turno siguiente, pero sí en el día
        CajaService.EstadoCaja siguiente = cajaService.estadoTurnoActual();
        assertThat(siguiente.entradas).isZero();
        assertThat(siguiente.salidas).isZero();
        assertThat(siguiente.efectivoInicial).isEqualTo(500);
        assertThat(siguiente.esperado()).isEqualTo(500);
        CajaService.EstadoCaja dia = cajaService.estadoDelDia();
        assertThat(dia.entradas).isCloseTo(200, within(0.001));
        assertThat(dia.salidas).isCloseTo(180, within(0.001));
        assertThat(dia.retiros).isCloseTo(50, within(0.001));
        assertThat(dia.esperado()).isCloseTo(500, within(0.001));
    }

    @Test
    void loQueSeDejaEnCajaManda() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 300);
        Producto p = producto("Pan", 100, 50);
        venderEfectivo(p, 100);

        // No se puede dejar más de lo contado ni un monto negativo
        assertThatThrownBy(() -> cajaService.hacerCorte(400, 450.0, null)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("dejar en caja");
        assertThatThrownBy(() -> cajaService.hacerCorte(400, -1.0, null)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajaService.hacerCorte(-5, null, null)).isInstanceOf(NegocioException.class);

        // La cajera deja TODO en el cajón (el dueño revisa a mediodía sin retirar)
        Corte c1 = cajaService.hacerCorte(400, 400.0, null);
        assertThat(c1.getRetiro()).isZero();
        assertThat(c1.esTurno()).isFalse();
        venderEfectivo(p, 100);
        CajaService.EstadoCaja e = cajaService.estadoTurnoActual();
        assertThat(e.efectivoInicial).isEqualTo(400);
        assertThat(e.efectivoInicialEsFondo).isFalse();
        assertThat(e.esperado()).isCloseTo(500, within(0.001));

        // Si contó menos que el fondo y no dice cuánto deja, se deja todo lo contado
        Corte c2 = cajaService.hacerCorte(250, null, "faltó dinero");
        assertThat(c2.esTurno()).isTrue();
        assertThat(c2.hayFaltante()).isTrue();
        assertThat(c2.getDiferencia()).isCloseTo(-250, within(0.001));
        assertThat(c2.getEfectivoDejado()).isEqualTo(250);
        assertThat(cajaService.turnoActual().efectivoInicial()).isEqualTo(250);
    }

    @Test
    void unTurnoQueCruzaLaMedianocheSigueDesdeElCorteDeAnoche() throws Exception {
        iniciarComoAdmin();
        // Anoche la cajera hizo su corte a las 22:00 y dejó 300; hoy nadie ha capturado fondo
        Corte anoche = new Corte();
        anoche.setFecha(LocalDate.now().minusDays(1));
        anoche.setRealizadoEn(LocalDate.now().minusDays(1).atTime(22, 0));
        anoche.setInicio(LocalDate.now().minusDays(1).atStartOfDay());
        anoche.setCajeroNombre("Cajera noche");
        anoche.setFondoInicial(200);
        anoche.setEfectivoContado(900);
        anoche.setEfectivoEsperado(900);
        anoche.setEfectivoDejado(300.0);
        anoche.setCaja(cajaService.cajaActual());
        anoche.setGeneral(false);
        corteRepo.save(anoche);
        Producto p = producto("Cigarros", 70, 50);
        venderEfectivo(p, 70);

        CajaService.Turno t = cajaService.turnoActual();
        assertThat(t.inicio()).isEqualTo(anoche.getRealizadoEn());
        assertThat(t.efectivoInicial()).isEqualTo(300);
        assertThat(t.inicialEsFondo()).isFalse();
        CajaService.EstadoCaja e = cajaService.estadoTurnoActual();
        assertThat(e.esperado()).isCloseTo(370, within(0.001));
        assertThat(e.fondo).isZero();

        Corte madrugada = cajaService.hacerCorte(370, 300.0, null);
        assertThat(madrugada.getInicio()).isEqualTo(anoche.getRealizadoEn());
        assertThat(madrugada.getFecha()).isEqualTo(LocalDate.now());
        assertThat(madrugada.esTurno()).isTrue();
        assertThat(madrugada.getDiferencia()).isCloseTo(0, within(0.001));
    }

    @Test
    void conFondoCapturadoHoyElDiaEmpiezaDeNuevoAunqueHayaCorteDeAyer() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Corte ayer = new Corte();
        ayer.setFecha(LocalDate.now().minusDays(1));
        ayer.setRealizadoEn(LocalDate.now().minusDays(1).atTime(21, 30));
        ayer.setCajeroNombre("Cajera");
        ayer.setFondoInicial(200);
        ayer.setEfectivoContado(800);
        ayer.setEfectivoEsperado(800);
        ayer.setEfectivoDejado(800.0);     // dejó todo; el dueño lo recogió en la noche
        ayer.setCaja(cajaService.cajaActual());
        ayer.setGeneral(false);
        corteRepo.save(ayer);

        cajaService.registrarFondo(admin, 250);   // el día arranca con el fondo contado esta mañana
        CajaService.Turno t = cajaService.turnoActual();
        assertThat(t.inicio()).isEqualTo(LocalDate.now().atStartOfDay());
        assertThat(t.efectivoInicial()).isEqualTo(250);
        assertThat(t.inicialEsFondo()).isTrue();
        assertThat(t.corteAnterior()).isNull();

        // Un corte de hoy posterior al fondo vuelve a mandar
        Producto p = producto("Agua", 10, 50);
        venderEfectivo(p, 10);
        Corte hoy = cajaService.hacerCorte(260, 250.0, null);
        assertThat(cajaService.turnoActual().inicio()).isEqualTo(hoy.getRealizadoEn());
        assertThat(cajaService.turnoActual().efectivoInicial()).isEqualTo(250);
    }

    @Test
    void cancelarUnaVentaDelTurnoAnteriorNoMueveElTurnoActualPeroLaDevolucionSi() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 100);
        Producto p = producto("Aceite", 40, 50);
        Venta v = venderEfectivo(p, 40);
        cajaService.hacerCorte(140, 100.0, null);

        CajaService.EstadoCaja antes = cajaService.estadoTurnoActual();
        ventaService.cancelar(v.getId(), "cliente devolvió", null);
        CajaService.EstadoCaja despues = cajaService.estadoTurnoActual();
        assertThat(despues.ventas.numCanceladas).isEqualTo(antes.ventas.numCanceladas);   // la venta era del turno anterior
        assertThat(despues.esperado()).isCloseTo(antes.esperado(), within(0.001));

        // El dinero que se devuelve hoy sale del cajón de hoy: se registra como devolución
        MovimientoCaja dev = movimientoCajaService.registrar(
                MovimientoCajaService.Solicitud.devolucion(v.getId(), 40, "Devolución venta #" + v.getId()), null);
        assertThat(dev.getVentaId()).isEqualTo(v.getId());
        assertThat(cajaService.estadoTurnoActual().esperado()).isCloseTo(antes.esperado() - 40, within(0.001));
        assertThat(cajaService.estadoTurnoActual().monto(Categoria.DEVOLUCION)).isCloseTo(40, within(0.001));
    }

    @Test
    void elEstadoDeUnCorteGuardadoReconstruyeSuPeriodoYElHistorialLoLista() throws Exception {
        Cajero admin = iniciarComoAdmin();
        cajaService.registrarFondo(admin, 100);
        Producto p = producto("Jabón", 25, 50);
        venderEfectivo(p, 25);
        movimientoCajaService.registrar(Tipo.SALIDA, Categoria.GASTO, 5, "bolsas", null);
        Corte c = cajaService.hacerCorte(120, 100.0, "obs");
        venderEfectivo(p, 25);   // ya no es del corte

        CajaService.EstadoCaja e = cajaService.estadoDelCorte(c);
        assertThat(e.inicio).isEqualTo(c.getInicioOInicioDelDia());
        assertThat(e.fin).isEqualTo(c.getRealizadoEn());
        assertThat(e.ventas.efectivo).isCloseTo(25, within(0.001));
        assertThat(e.salidas).isCloseTo(5, within(0.001));
        assertThat(e.esperado()).isCloseTo(c.getEfectivoEsperado(), within(0.001));

        assertThat(cajaService.cortesEntre(LocalDate.now(), LocalDate.now())).extracting(Corte::getId).contains(c.getId());
        assertThat(cajaService.cortesEntre(LocalDate.now().minusDays(3), LocalDate.now().minusDays(1))).isEmpty();
        assertThatThrownBy(() -> cajaService.cortesEntre(LocalDate.now(), LocalDate.now().minusDays(1)))
                .isInstanceOf(NegocioException.class);

        String impreso = impresionService.documentoDeCorte(true).aTexto(48);
        assertThat(impreso).contains("CORTE DE CAJA").contains("Turno").contains("Dejado por el corte anterior");
        String dia = impresionService.documentoDeCorte(false).aTexto(48);
        assertThat(dia).contains("Día completo").contains("Salidas de efectivo").contains("Retirado en cortes").contains("Cortes del día");
    }

    @Test
    void elCorteExigeHacerCorteYRegistraEnLaBitacora() throws Exception {
        iniciarComoCajero("sinCorteT", java.util.EnumSet.noneOf(me.eliassanchezfernandez.puntodeventa.model.Permiso.class));
        assertThatThrownBy(() -> cajaService.hacerCorte(0, 0.0, null)).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> cajaService.estadoTurnoActual()).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> cajaService.cortesEntre(LocalDate.now(), LocalDate.now())).isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void laConfiguracionDeFormasDePagoNoAfectaElEfectivoDelTurno() throws Exception {
        Cajero admin = iniciarComoAdmin();
        configuracionService.guardarFormasPago(true, true, true, true);
        cajaService.registrarFondo(admin, 0);
        Producto p = producto("Detergente", 80, 10);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(new me.eliassanchezfernandez.puntodeventa.model.PagoVenta(Venta.FormaPago.TRANSFERENCIA, 80)), null);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), Venta.FormaPago.TARJETA, 80);
        CajaService.EstadoCaja e = cajaService.estadoTurnoActual();
        assertThat(e.ventas.transferencia).isCloseTo(80, within(0.001));
        assertThat(e.ventas.tarjeta).isCloseTo(80, within(0.001));
        assertThat(e.esperado()).as("tarjeta y transferencia no están en el cajón").isZero();
        LocalDateTime antes = LocalDateTime.now();
        Corte c = cajaService.hacerCorte(0, 0.0, null);
        assertThat(c.getRealizadoEn()).isAfterOrEqualTo(antes.minusSeconds(1));
        assertThat(c.getVentasTotales()).isCloseTo(160, within(0.001));
    }
}
