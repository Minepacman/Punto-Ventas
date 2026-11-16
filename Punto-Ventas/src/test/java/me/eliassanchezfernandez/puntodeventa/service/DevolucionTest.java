package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Devolucion;
import me.eliassanchezfernandez.puntodeventa.model.DevolucionDetalle;
import me.eliassanchezfernandez.puntodeventa.model.Factura;
import me.eliassanchezfernandez.puntodeventa.model.FacturaVenta;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.FacturaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoCajaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Devoluciones parciales: cantidades, reembolso con descuentos e IVA, inventario/merma, corte, permisos y bloqueos. */
class DevolucionTest extends BaseServicioTest {

    @Autowired private DevolucionService devolucionService;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private MovimientoCajaRepository movimientoCajaRepo;
    @Autowired private FacturaRepository facturaRepo;
    @Autowired private CajaService cajaService;
    @Autowired private AgregadosService agregadosService;
    @Autowired private TicketService ticketService;
    @Autowired private ConfiguracionService configuracionService;

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    private Venta vender(Producto p, double cantidad) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, cantidad)), Venta.FormaPago.EFECTIVO, 10_000);
    }

    private static DetalleVenta renglon(Venta v, Producto p) {
        return v.getDetalles().stream().filter(d -> d.getProducto().getId().equals(p.getId())).findFirst().orElseThrow();
    }

    private Devolucion devolver(Venta v, DetalleVenta d, double cantidad, boolean regresa) {
        return devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(d.getId(), cantidad, regresa)), "Cliente se arrepintió"), null);
    }

    private long movimientos(Producto p, MovimientoInventario.Tipo tipo) {
        return productoService.movimientosDe(p.getId()).stream().filter(m -> m.getTipo() == tipo).count();
    }

    @Test
    void devolverParteDeUnRenglonRegresaStockReembolsaEnEfectivoYQuedaEnLaVenta() {
        Producto p = producto("Arroz dev", 50, 10);
        Venta v = vender(p, 3);
        assertThat(stockActual(p)).isEqualTo(7);

        Devolucion dev = devolver(v, renglon(v, p), 1, true);

        assertThat(dev.getTotal()).isEqualTo(50.0);
        assertThat(dev.getIva()).isCloseTo(6.90, within(0.001));        // 50 − round(50/1.16)
        assertThat(dev.getCajeroNombre()).isEqualTo("Administrador");
        assertThat(dev.getAutorizadoPor()).isNull();
        assertThat(dev.getFolio()).isEqualTo("D-" + dev.getId());
        assertThat(stockActual(p)).isEqualTo(8);
        assertThat(movimientos(p, MovimientoInventario.Tipo.DEVOLUCION)).isEqualTo(1);

        Venta despues = ventaRepo.findConDetallesById(v.getId()).orElseThrow();
        assertThat(despues.isCancelada()).isFalse();
        assertThat(despues.getTotalDevueltoOCero()).isEqualTo(50.0);
        assertThat(despues.getTotalNeto()).isEqualTo(100.0);
        assertThat(despues.getFechaUltimaDevolucion()).isNotNull();
        assertThat(renglon(despues, p).getCantidadDevueltaOCero()).isEqualTo(1.0);
        assertThat(renglon(despues, p).getCantidadPorDevolver()).isEqualTo(2.0);
        assertThat(despues.tieneDevoluciones()).isTrue();
        assertThat(despues.estaDevueltaPorCompleto()).isFalse();

        MovimientoCaja m = movimientoCajaRepo.findById(dev.getMovimientoCajaId()).orElseThrow();
        assertThat(m.getCategoria()).isEqualTo(MovimientoCaja.Categoria.DEVOLUCION);
        assertThat(m.getTipo()).isEqualTo(MovimientoCaja.Tipo.SALIDA);
        assertThat(m.getMonto()).isEqualTo(50.0);
        assertThat(m.getVentaId()).isEqualTo(v.getId());

        assertThat(devolucionService.estadoDe(v.getId()).devoluciones()).extracting(Devolucion::getId).containsExactly(dev.getId());
    }

    @Test
    void noSeDevuelveMasDeLoVendidoNiDecimalesEnPiezas() {
        Producto p = producto("Jabón dev", 20, 10);
        Venta v = vender(p, 3);
        DetalleVenta d = renglon(v, p);

        assertThatThrownBy(() -> devolver(v, d, 4, true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("solo quedan 3");
        assertThatThrownBy(() -> devolver(v, d, 1.5, true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("número entero");
        assertThatThrownBy(() -> devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(d.getId(), 1, true)), "  "), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("motivo");
        assertThatThrownBy(() -> devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(d.getId(), 0, true)), "x"), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("qué artículos");

        devolver(v, d, 2, true);
        assertThatThrownBy(() -> devolver(v, d, 2, true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("solo quedan 1");
        devolver(v, d, 1, true);
        assertThatThrownBy(() -> devolver(v, d, 1, true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya se devolvió completo");

        Venta despues = ventaRepo.findConDetallesById(v.getId()).orElseThrow();
        assertThat(despues.estaDevueltaPorCompleto()).isTrue();
        assertThat(despues.getTotalDevueltoOCero()).isEqualTo(60.0);
        assertThat(stockActual(p)).isEqualTo(10);
        assertThat(devolucionService.motivoParaNoDevolver(despues)).contains("todos los artículos");
    }

    @Test
    void granelAdmiteDecimales() {
        Producto frijol = producto("Frijol granel dev", 30, 10, Producto.TipoVenta.GRANEL, null);
        Venta v = vender(frijol, 1.5);       // $45
        Devolucion dev = devolver(v, renglon(v, frijol), 0.5, true);
        assertThat(dev.getTotal()).isEqualTo(15.0);
        assertThat(stockActual(frijol)).isCloseTo(9.0, within(0.0001));
    }

    @Test
    void elReembolsoEsLoCobradoDeVerdadConPromocionYDescuentoYCuadraAlDevolverTodo() {
        // 2x1: 2 piezas de $10 se cobran $10 → cada pieza devuelta reembolsa $5, no $10
        Producto refresco = producto("Refresco 2x1 dev", 10, 10);
        promocionNxM(refresco, 2, 1);
        Venta v = vender(refresco, 2);
        DetalleVenta d = renglon(v, refresco);
        assertThat(d.getImporte()).isEqualTo(10.0);
        assertThat(devolver(v, d, 1, true).getTotal()).isEqualTo(5.0);
        assertThat(devolver(v, d, 1, true).getTotal()).isEqualTo(5.0);

        // Descuento manual del 10 % sobre 3 × $33.33 = $89.99: 1 pieza → $30.00; las 2 últimas → lo que falta ($59.99)
        Producto queso = producto("Queso dev", 33.33, 10);
        DetalleVenta conDescuento = ventaService.crearDetalle(queso, 3);
        ventaService.aplicarDescuentoManual(conDescuento, 10.0, null, null);
        Venta v2 = ventaService.cobrar(List.of(conDescuento), Venta.FormaPago.EFECTIVO, 1000);
        DetalleVenta dq = renglon(v2, queso);
        assertThat(dq.getImporte()).isEqualTo(89.99);
        double primera = devolver(v2, dq, 1, true).getTotal();
        double resto = devolver(v2, dq, 2, true).getTotal();
        assertThat(primera).isEqualTo(30.0);
        assertThat(resto).isEqualTo(59.99);
        assertThat(primera + resto).isCloseTo(dq.getImporte(), within(0.001));
        assertThat(ventaRepo.findById(v2.getId()).orElseThrow().getTotalDevueltoOCero()).isCloseTo(89.99, within(0.001));
    }

    @Test
    void elIvaDelReembolsoVaALaTasaDeCadaRenglon() {
        Producto tortilla = producto("Tortilla dev", 23.20, 10);
        tortilla.setIva(TipoIva.TASA_0);
        tortilla = productoService.guardar(tortilla);
        Producto refresco = producto("Refresco dev", 23.20, 10);
        refresco.setIva(TipoIva.TASA_16);
        refresco = productoService.guardar(refresco);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(tortilla, 1), ventaService.crearDetalle(refresco, 1)),
                Venta.FormaPago.EFECTIVO, 100);

        Devolucion dev = devolucionService.devolver(new DevolucionService.Solicitud(v.getId(), List.of(
                new DevolucionService.Linea(renglon(v, tortilla).getId(), 1, true),
                new DevolucionService.Linea(renglon(v, refresco).getId(), 1, true)), "Producto equivocado"), null);

        assertThat(dev.getTotal()).isEqualTo(46.40);
        assertThat(dev.getIva()).isCloseTo(3.20, within(0.001));        // solo el refresco: 23.20 − 20.00
        assertThat(dev.getDetalles()).extracting(DevolucionDetalle::getTasaIva).containsExactlyInAnyOrder(0.0, 0.16);
    }

    @Test
    void danadoOCaducadoNoRegresaAlInventarioYQuedaComoMerma() {
        Producto leche = producto("Leche dev", 25, 10);
        Venta v = vender(leche, 2);
        assertThat(stockActual(leche)).isEqualTo(8);

        Devolucion dev = devolver(v, renglon(v, leche), 1, false);

        assertThat(dev.getTotal()).isEqualTo(25.0);
        assertThat(dev.getDetalles().get(0).isRegresaAInventario()).isFalse();
        assertThat(stockActual(leche)).isEqualTo(8);                    // no vuelve al anaquel
        List<MovimientoInventario> movs = productoService.movimientosDe(leche.getId());
        assertThat(movs).anyMatch(m -> m.getTipo() == MovimientoInventario.Tipo.DEVOLUCION && m.getCantidad() == 1);
        assertThat(movs).anyMatch(m -> m.getTipo() == MovimientoInventario.Tipo.AJUSTE && m.getCantidad() == -1
                && m.getMotivo().startsWith("Merma"));
    }

    @Test
    void elProductoComunSeReembolsaSinTocarInventario() {
        DetalleVenta comun = ventaService.crearDetalleProductoComun("Bolsa de hielo", 35, 2);
        Venta v = ventaService.cobrar(List.of(comun), Venta.FormaPago.EFECTIVO, 100);
        DetalleVenta d = v.getDetalles().get(0);

        Devolucion dev = devolver(v, d, 1, true);

        assertThat(dev.getTotal()).isEqualTo(35.0);
        assertThat(dev.getDetalles().get(0).isRegresaAInventario()).isFalse();
        assertThat(dev.getDetalles().get(0).getDescripcion()).isEqualTo("Bolsa de hielo");
        assertThat(movimientos(d.getProducto(), MovimientoInventario.Tipo.DEVOLUCION)).isZero();
    }

    @Test
    void elCorteMuestraLasDevolucionesYRestaVentasNetasEfectivoYGanancia() {
        Producto p = producto("Aceite dev", 40, 10);   // costo 20
        VentaService.ResumenCorte antes = ventaService.resumenDelDia();
        double esperadoAntes = cajaService.estadoTurnoActual().esperado();
        Venta v = vender(p, 3);                         // $120, ganancia $60
        VentaService.ResumenCorte conVenta = ventaService.resumenDelDia();

        devolver(v, renglon(v, p), 1, true);            // −$40 de venta; regresa costo $20 → ganancia −$20

        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        assertThat(r.numDevoluciones).isEqualTo(antes.numDevoluciones + 1);
        assertThat(r.totalDevoluciones).isCloseTo(antes.totalDevoluciones + 40, within(0.001));
        assertThat(r.ventasTotales).isCloseTo(conVenta.ventasTotales, within(0.001));     // la venta sigue contando
        assertThat(r.getVentasNetas()).isCloseTo(conVenta.ventasTotales - r.totalDevoluciones, within(0.001));
        assertThat(r.ganancia).isCloseTo(conVenta.ganancia - 20, within(0.001));

        CajaService.EstadoCaja e = cajaService.estadoTurnoActual();
        assertThat(e.esperado()).isCloseTo(esperadoAntes + 120 - 40, within(0.001));
        assertThat(e.monto(MovimientoCaja.Categoria.DEVOLUCION)).isGreaterThanOrEqualTo(40);

        // Merma: no se recupera el costo → ganancia −$40 completa
        devolver(v, renglon(v, p), 1, false);
        assertThat(ventaService.resumenDelDia().ganancia).isCloseTo(conVenta.ganancia - 20 - 40, within(0.001));

        // El corte guardado e impreso lo anota
        cajaService.hacerCorte(e.esperado() - 40, null);
        String impreso = ticketService.documentoCorte(cajaService.estadoDelDia(), null, configuracionService.obtener(), "x")
                .lineas().stream().map(DocumentoImpresion.Linea::texto).collect(Collectors.joining("\n"));
        assertThat(impreso).contains("Devoluciones (").contains("VENTAS NETAS");
        assertThat(cajaService.cortesDeHoyDeEstaCaja().get(0).getDevolucionesOCero()).isGreaterThanOrEqualTo(80);
    }

    @Test
    void lasEstadisticasDelDiaDescuentanLoDevuelto() {
        Producto p = producto("Galletas dev", 15, 10);
        double antes = agregadosService.calcularDia(LocalDate.now()).resumen().getTotal();
        Venta v = vender(p, 4);
        devolver(v, renglon(v, p), 1, true);
        double despues = agregadosService.calcularDia(LocalDate.now()).resumen().getTotal();
        assertThat(despues - antes).isCloseTo(45, within(0.001));
    }

    @Test
    void unCajeroNecesitaElPermisoOLaAutorizacionDeUnAdministrador() throws Exception {
        Producto p = producto("Atún dev", 18, 10);
        iniciarComoCajero("cajeraDev", null);
        Venta v = vender(p, 2);
        DetalleVenta d = renglon(v, p);

        assertThatThrownBy(() -> devolver(v, d, 1, true)).isInstanceOf(PermisoRequeridoException.class);
        assertThat(stockActual(p)).isEqualTo(8);

        Autorizacion aut = new Autorizacion(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow(),
                Permiso.DEVOLVER_VENTAS, LocalDateTime.now());
        Devolucion dev = devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(d.getId(), 1, true)), "Caducado"), List.of(aut));
        assertThat(dev.getAutorizadoPor()).isEqualTo("Administrador");
        assertThat(dev.getCajeroNombre()).isEqualTo("Usuario cajeraDev");
        assertThat(aut.isConsumida()).isTrue();
        assertThat(movimientoCajaRepo.findById(dev.getMovimientoCajaId()).orElseThrow().getAutorizadoPor()).isEqualTo("Administrador");

        // La autorización ya se usó
        assertThatThrownBy(() -> devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(d.getId(), 1, true)), "Otra"), List.of(aut)))
                .isInstanceOf(PermisoRequeridoException.class);

        // Con el permiso concedido no hace falta autorización
        iniciarComoAdmin();
        crearUsuario("gerenteDev", me.eliassanchezfernandez.puntodeventa.model.Cajero.Rol.CAJERO,
                java.util.EnumSet.of(Permiso.DEVOLVER_VENTAS));
        iniciarComo("gerenteDev", CLAVE_PRUEBA);
        assertThat(devolver(v, d, 1, true).getAutorizadoPor()).isNull();
    }

    @Test
    void noSeDevuelveFueraDePlazoNiDeVentasCanceladasNiFacturadas() {
        Producto p = producto("Café dev", 60, 20);
        Venta vieja = vender(p, 1);
        vieja.setFecha(LocalDateTime.now().minusHours(49));
        ventaRepo.save(vieja);
        assertThatThrownBy(() -> devolver(vieja, renglon(vieja, p), 1, true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("plazo");

        Venta cancelada = vender(p, 1);
        ventaService.cancelar(cancelada.getId(), "Error", null);
        assertThatThrownBy(() -> devolver(cancelada, renglon(cancelada, p), 1, true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("cancelada");

        Venta facturada = vender(p, 2);
        Factura f = new Factura();
        f.setEstado(Factura.Estado.VIGENTE.name());
        f.setVenta(facturada);
        f.setSerie("A");
        f.setFolio("77");
        f.getVentas().add(new FacturaVenta(f, facturada));
        facturaRepo.save(f);
        assertThatThrownBy(() -> devolver(facturada, renglon(facturada, p), 1, true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("está facturada")
                .hasMessageContaining("nota de crédito");
        assertThat(devolucionService.motivoParaNoDevolver(facturada)).contains("está facturada");
    }

    @Test
    void unaVentaConDevolucionesYaNoSeCancelaEntera() {
        Producto p = producto("Pan dev", 12, 10);
        Venta v = vender(p, 3);
        devolver(v, renglon(v, p), 1, true);
        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "Todo", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("artículos devueltos");
        assertThat(stockActual(p)).isEqualTo(8);
        assertThat(ventaRepo.findById(v.getId()).orElseThrow().isCancelada()).isFalse();
    }

    @Test
    void elComprobanteImpresoTraeLoDevueltoYElReembolso() {
        Producto p = producto("Cereal dev", 55, 10);
        Venta v = vender(p, 2);
        Devolucion dev = devolver(v, renglon(v, p), 1, false);
        String texto = ticketService.documentoDevolucion(devolucionService.obtener(dev.getId()), configuracionService.obtener())
                .lineas().stream().map(DocumentoImpresion.Linea::texto).collect(Collectors.joining("\n"));
        assertThat(texto).contains("COMPROBANTE DE DEVOLUCION").contains(dev.getFolio()).contains("Cereal dev")
                .contains("REEMBOLSO").contains("no regresa a inventario").contains("Cliente se arrepintió");
    }
}
