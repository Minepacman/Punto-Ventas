package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.impresion.EscPos;
import me.eliassanchezfernandez.puntodeventa.model.Devolucion;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Vale;
import me.eliassanchezfernandez.puntodeventa.model.ValeMovimiento;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ValeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Vales de compra: se entregan en una devolución («Darle un vale para otra compra»), se usan como
 * forma de pago (completos o en partes), vencen si la tienda lo configura, regresan saldo al
 * cancelar la venta y el corte los muestra sin tocar el efectivo esperado.
 */
class ValeTest extends BaseServicioTest {

    @Autowired private DevolucionService devolucionService;
    @Autowired private ValeService valeService;
    @Autowired private ValeRepository valeRepo;
    @Autowired private CajaService cajaService;
    @Autowired private TicketService ticketService;
    @Autowired private ConfiguracionService configuracionService;

    @BeforeEach
    void iniciarSesion() throws Exception {
        iniciarComoAdmin();
    }

    private Venta vender(Producto p, double cantidad) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, cantidad)),
                List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, 1000)), null);
    }

    private Devolucion devolverEnVale(Venta v, double cantidad) {
        return devolucionService.devolver(new DevolucionService.Solicitud(v.getId(),
                List.of(new DevolucionService.Linea(v.getDetalles().get(0).getId(), cantidad, true)), "No le quedó",
                Devolucion.FormaReembolso.VALE), null);
    }

    private Vale valeDe(Devolucion d) {
        return valeRepo.findById(d.getValeId()).orElseThrow();
    }

    @Test
    void laDevolucionEnValeNoSacaEfectivoYElCorteLoMuestraAparte() {
        Producto p = producto("Vale A", 50, 10);
        Venta v = vender(p, 3);
        CajaService.EstadoCaja antes = cajaService.estadoDelDia();

        Devolucion dev = devolverEnVale(v, 2);

        assertThat(dev.getTotal()).isEqualTo(100.0);
        assertThat(dev.getEnValeOCero()).isEqualTo(100.0);
        assertThat(dev.getEnEfectivo()).isEqualTo(0.0);
        assertThat(dev.getMovimientoCajaId()).as("no sale efectivo").isNull();
        assertThat(dev.getFormaReembolso()).isEqualTo(Devolucion.FormaReembolso.VALE);
        assertThat(dev.getFormaTexto()).isEqualTo("Vale para otra compra");
        Vale vale = valeDe(dev);
        assertThat(vale.getMonto()).isEqualTo(100.0);
        assertThat(vale.getSaldo()).isEqualTo(100.0);
        assertThat(vale.getCodigo()).hasSize(8);
        assertThat(ValeService.pareceCodigo(vale.getCodigo())).isTrue();
        assertThat(vale.getVence()).as("por defecto no vence").isNull();
        assertThat(vale.getVentaId()).isEqualTo(v.getId());
        assertThat(vale.getDevolucionId()).isEqualTo(dev.getId());
        assertThat(valeService.movimientos(vale.getId())).extracting(ValeMovimiento::getTipoEnum)
                .containsExactly(ValeMovimiento.Tipo.EMISION);

        CajaService.EstadoCaja despues = cajaService.estadoDelDia();
        assertThat(despues.esperado() - antes.esperado()).as("el cajón no cambia").isCloseTo(0, within(0.001));
        assertThat(despues.ventas.devolucionesEnVale - antes.ventas.devolucionesEnVale).isCloseTo(100, within(0.001));
        assertThat(despues.ventas.numValesEmitidos - antes.ventas.numValesEmitidos).isEqualTo(1);
        assertThat(despues.ventas.valesEmitidos - antes.ventas.valesEmitidos).isCloseTo(100, within(0.001));

        String comprobante = ticketService.documentoDevolucion(devolucionService.obtener(dev.getId()),
                configuracionService.obtener()).aTexto(48);
        assertThat(comprobante).contains("En vale").contains(vale.getCodigoMostrado()).doesNotContain("En efectivo");
        String impreso = ticketService.documentoVale(vale, configuracionService.obtener()).aTexto(48);
        assertThat(impreso).contains("VALE DE COMPRA").contains("$100.00").contains(vale.getCodigoMostrado())
                .contains("Preséntalo en caja").contains("No vence").contains("*" + vale.getCodigo() + "*");
    }

    @Test
    void seUsaEnPartesYElTicketDiceCuantoLeQueda() {
        Producto p = producto("Vale B", 50, 20);
        Vale vale = valeDe(devolverEnVale(vender(p, 4), 3));   // vale de $150
        CajaService.EstadoCaja antes = cajaService.estadoDelDia();

        // Compra de $100: todo con el vale, le quedan $50 (el código se acepta en minúsculas y con guion)
        String tecleado = vale.getCodigoMostrado().toLowerCase();
        Venta compra = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)),
                List.of(PagoVenta.conVale(tecleado, 100)), null);
        assertThat(valeRepo.saldoDe(vale.getId())).isCloseTo(50, within(0.001));
        PagoVenta pago = compra.getPagos().get(0);
        assertThat(pago.getValeCodigo()).isEqualTo(vale.getCodigo());
        assertThat(pago.getValeSaldo()).isEqualTo(50.0);
        assertThat(compra.getCambio()).isEqualTo(0.0);
        String ticket = ticketService.documentoVenta(compra, configuracionService.obtener(), false).aTexto(48);
        assertThat(ticket).contains("Vale de la tienda").contains(vale.getCodigoMostrado())
                .contains("Le queda al vale").contains("$50.00");

        // Compra de $150: $50 del vale (lo que le queda) + $100 en efectivo (con cambio de un billete de $200)
        Venta otra = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 3)),
                List.of(PagoVenta.conVale(vale.getCodigo(), 50), new PagoVenta(Venta.FormaPago.EFECTIVO, 200)), null);
        assertThat(otra.getCambio()).isEqualTo(100.0);
        assertThat(valeRepo.saldoDe(vale.getId())).isCloseTo(0, within(0.001));
        assertThat(valeService.consultar(vale.getCodigo()).sePuedeUsar()).isFalse();
        assertThat(valeService.consultar(vale.getCodigo()).problema()).contains("ya se usó completo");

        // Corte: lo pagado con vale no es efectivo; el esperado solo sube por los $100 en efectivo
        CajaService.EstadoCaja despues = cajaService.estadoDelDia();
        assertThat(despues.ventas.valesTienda - antes.ventas.valesTienda).isCloseTo(150, within(0.001));
        assertThat(despues.esperado() - antes.esperado()).isCloseTo(100, within(0.001));
        String corte = ticketService.documentoCorte(despues, null, configuracionService.obtener(), "prueba").aTexto(48);
        assertThat(corte).contains("Vales de la tienda").contains("Usados para pagar");

        // Ya sin saldo: no se acepta
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(PagoVenta.conVale(vale.getCodigo(), 50)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya se usó completo");
    }

    @Test
    void noSeUsaMasDeLoQueTieneNiUnCodigoQueNoExisteNiDaCambio() {
        Producto p = producto("Vale C", 50, 20);
        Vale vale = valeDe(devolverEnVale(vender(p, 1), 1));   // $50
        double stock = stockActual(p);

        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)),
                List.of(PagoVenta.conVale(vale.getCodigo(), 100)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("solo le quedan");
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(PagoVenta.conVale("ZZZZ-ZZZZ", 50)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("No existe un vale");
        // Un vale no da cambio: lo que no es efectivo no puede pasar del total
        Producto barato = producto("Vale C barato", 30, 5);
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(barato, 1)),
                List.of(PagoVenta.conVale(vale.getCodigo(), 50)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no puede ser mayor que el total");
        assertThat(valeRepo.saldoDe(vale.getId())).isCloseTo(50, within(0.001));
        assertThat(stockActual(p)).as("nada se descontó").isEqualTo(stock);
    }

    @Test
    void unValeVencidoNoSeAcepta() {
        configuracionService.guardarVigenciaVales(30);
        Producto p = producto("Vale D", 50, 20);
        Vale vale = valeDe(devolverEnVale(vender(p, 1), 1));
        assertThat(vale.getVence()).isEqualTo(LocalDate.now().plusDays(30));
        assertThat(valeService.consultar(vale.getCodigo()).sePuedeUsar()).isTrue();

        vale.setVence(LocalDate.now().minusDays(1));
        valeRepo.saveAndFlush(vale);
        assertThat(valeService.consultar(vale.getCodigo()).problema()).contains("venció");
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(PagoVenta.conVale(vale.getCodigo(), 50)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("venció");
        configuracionService.guardarVigenciaVales(0);
    }

    @Test
    void cancelarUnaVentaPagadaConValeLeRegresaElSaldo() {
        Producto p = producto("Vale E", 50, 20);
        Vale vale = valeDe(devolverEnVale(vender(p, 2), 2));   // $100
        Venta compra = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)),
                List.of(PagoVenta.conVale(vale.getCodigo(), 50)), null);
        assertThat(valeRepo.saldoDe(vale.getId())).isCloseTo(50, within(0.001));

        ventaService.cancelar(compra.getId(), "Se equivocó", null);

        assertThat(valeRepo.saldoDe(vale.getId())).isCloseTo(100, within(0.001));
        assertThat(valeService.movimientos(vale.getId())).extracting(ValeMovimiento::getTipoEnum).containsExactly(
                ValeMovimiento.Tipo.EMISION, ValeMovimiento.Tipo.CANJE, ValeMovimiento.Tipo.CANCELACION_VENTA);
    }

    @Test
    void loPagadoConValeRegresaComoValeAunqueSePidaEfectivo() {
        Producto p = producto("Vale F", 50, 20);
        Vale vale = valeDe(devolverEnVale(vender(p, 1), 1));   // $50
        Venta compra = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)),
                List.of(PagoVenta.conVale(vale.getCodigo(), 50), new PagoVenta(Venta.FormaPago.EFECTIVO, 50)), null);
        assertThat(devolucionService.estadoDe(compra.getId()).pendienteEnVale()).isCloseTo(50, within(0.001));

        Devolucion dev = devolucionService.devolver(new DevolucionService.Solicitud(compra.getId(),
                List.of(new DevolucionService.Linea(compra.getDetalles().get(0).getId(), 2, true)), "No lo quiso"), null);

        assertThat(dev.getTotal()).isEqualTo(100.0);
        assertThat(dev.getEnValeOCero()).as("lo pagado con vale no se vuelve dinero").isEqualTo(50.0);
        assertThat(dev.getEnEfectivo()).isEqualTo(50.0);
        assertThat(dev.getFormaTexto()).isEqualTo("Vale y efectivo");
        assertThat(valeDe(dev).getSaldo()).isEqualTo(50.0);
    }

    @Test
    void repartoDeLaVentanaDeDevolucion() {
        DevolucionService.Estado e = new DevolucionService.Estado(null, List.of(), 20, 30);
        assertThat(e.reparto(100, Devolucion.FormaReembolso.EFECTIVO)).containsExactly(20, 30, 50);
        assertThat(e.reparto(100, Devolucion.FormaReembolso.VALE)).containsExactly(20, 80, 0);
        assertThat(e.reparto(100)).containsExactly(20, 80);
    }

    @Test
    void codigosYCodigoDeBarras() {
        assertThat(ValeService.normalizar(" k7m2-9qxa ")).isEqualTo("K7M29QXA");
        assertThat(ValeService.pareceCodigo("K7M2-9QXA")).isTrue();
        assertThat(ValeService.pareceCodigo("12345678")).as("solo números: es un monto o un producto").isFalse();
        assertThat(ValeService.pareceCodigo("K7M2")).isFalse();
        for (int i = 0; i < 50; i++) assertThat(ValeService.pareceCodigo(ValeService.generarCodigo())).isTrue();

        DocumentoImpresion d = new DocumentoImpresion().codigoBarras("K7M29QXA");
        byte[] bytes = EscPos.renderizar(d, EscPos.CodePage.CP850, false, false);
        byte[] esperado = { 0x1D, 'k', 73, 10, '{', 'B', 'K', '7', 'M', '2', '9', 'Q', 'X', 'A' };
        assertThat(indice(bytes, esperado)).as("GS k CODE128").isGreaterThanOrEqualTo(0);
        assertThat(d.aTexto(32)).contains("*K7M29QXA*");
    }

    private static int indice(byte[] datos, byte[] buscado) {
        for (int i = 0; i + buscado.length <= datos.length; i++) {
            boolean igual = true;
            for (int j = 0; j < buscado.length && igual; j++) igual = datos[i + j] == buscado[j];
            if (igual) return i;
        }
        return -1;
    }
}
