package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.ParProductos;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoClima;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ParProductosRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.util.CalculoIva;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Ningún reporte cuenta como vendido lo devuelto (reglas-negocio CRT-12):
 *  - corte (caja): resta el día de la DEVOLUCIÓN, con desglose por tasa y departamento netos;
 *  - análisis de ventas (Estadísticas, celular, recomendaciones): resta en el día de la VENTA.
 * Siempre por diferencia (antes/después): la base de pruebas puede traer ventas de otras clases.
 */
class DevolucionesEnReportesTest extends BaseServicioTest {

    @Autowired private DevolucionService devolucionService;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private CajaService cajaService;
    @Autowired private TicketService ticketService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AgregadosService agregadosService;
    @Autowired private EstadisticasService estadisticasService;
    @Autowired private ConsultasMovilService consultasMovilService;
    @Autowired private ParProductosRepository paresRepo;

    private Departamento bebidas, lacteos;
    private Producto refresco, leche;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        bebidas = departamento("Bebidas netas");
        lacteos = departamento("Lacteos netos");
        refresco = productoConIva("Refresco neto", 18, TipoIva.TASA_16, bebidas);   // costo 9
        leche    = productoConIva("Leche neta", 28.5, TipoIva.TASA_0, lacteos);     // costo 14.25
    }

    private Producto productoConIva(String descripcion, double precio, TipoIva iva, Departamento depto) {
        Producto p = new Producto();
        p.setCodigoBarras("N-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(descripcion);
        p.setPrecioCosto(precio / 2);
        p.setPrecioVenta(precio);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        p.setDepartamento(depto);
        p.setIva(iva);
        p.setUsaInventario(true);
        p.setCantidadActual(50);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    /** 2 refrescos ($36, IVA 16 % $4.97) + 2 leches ($57, tasa 0 %) = $93 */
    private Venta venderMixta() {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(refresco, 2), ventaService.crearDetalle(leche, 2)),
                Venta.FormaPago.EFECTIVO, 100);
    }

    private static DetalleVenta renglon(Venta v, Producto p) {
        return v.getDetalles().stream().filter(d -> d.getProducto().getId().equals(p.getId())).findFirst().orElseThrow();
    }

    /** Devuelve 1 refresco ($18, IVA $2.48) y 1 leche ($28.50): $46.50 */
    private void devolverUnoDeCada(Venta v) {
        devolucionService.devolver(new DevolucionService.Solicitud(v.getId(), List.of(
                new DevolucionService.Linea(renglon(v, refresco).getId(), 1, true),
                new DevolucionService.Linea(renglon(v, leche).getId(), 1, true)), "No le gustó"), null);
    }

    private static CalculoIva.Grupo grupo(List<CalculoIva.Grupo> grupos, double tasa) {
        return grupos.stream().filter(g -> !g.exento() && Math.abs(g.tasa() - tasa) < 0.0001).findFirst()
                .orElse(new CalculoIva.Grupo(tasa, false, 0, 0));
    }

    private static String texto(DocumentoImpresion d) {
        return d.lineas().stream().map(DocumentoImpresion.Linea::texto).collect(Collectors.joining("\n"));
    }

    // ── Corte: la devolución resta el día en que se hizo ─────────────────

    @Test
    void elCorteDaIvaPorTasaYDepartamentosNetosConLaNotaDeLoDevuelto() {
        VentaService.ResumenCorte antes = ventaService.resumenDelDia();
        Venta v = venderMixta();
        devolverUnoDeCada(v);
        VentaService.ResumenCorte r = ventaService.resumenDelDia();

        // Departamentos: neto y, aparte, lo devuelto (para la nota)
        assertThat(r.porDepartamento.get(bebidas.getNombre())).isCloseTo(18.0, within(0.001));
        assertThat(r.porDepartamento.get(lacteos.getNombre())).isCloseTo(28.5, within(0.001));
        assertThat(r.devueltoDeDepartamento(bebidas.getNombre())).isCloseTo(18.0, within(0.001));
        assertThat(r.devueltoDeDepartamento(lacteos.getNombre())).isCloseTo(28.5, within(0.001));

        // IVA por tasa neto: 16 % → base 31.03 − 15.52, IVA 4.97 − 2.48; tasa 0 % → 57 − 28.50
        CalculoIva.Grupo g16 = grupo(r.porTasaIva, 0.16), g16Antes = grupo(antes.porTasaIva, 0.16);
        assertThat(g16.iva() - g16Antes.iva()).isCloseTo(2.49, within(0.001));
        assertThat(g16.base() - g16Antes.base()).isCloseTo(15.51, within(0.001));
        CalculoIva.Grupo g0 = grupo(r.porTasaIva, 0.0), g0Antes = grupo(antes.porTasaIva, 0.0);
        assertThat(g0.base() - g0Antes.base()).isCloseTo(28.5, within(0.001));
        assertThat(g0.iva()).isCloseTo(0.0, within(0.001));
        assertThat(grupo(r.devolucionesPorTasaIva, 0.16).iva() - grupo(antes.devolucionesPorTasaIva, 0.16).iva())
                .isCloseTo(2.48, within(0.001));
        assertThat(r.devueltoDeTasa(g0) - antes.devueltoDeTasa(g0Antes)).isCloseTo(28.5, within(0.001));
        assertThat(CalculoIva.esMixto(r.porTasaIva)).isTrue();

        // Subtotal e IVA del corte, netos: suman las ventas netas
        assertThat(r.iva - antes.iva).isCloseTo(2.49, within(0.001));
        assertThat(r.ivaDevoluciones - antes.ivaDevoluciones).isCloseTo(2.48, within(0.001));
        assertThat((r.subtotal + r.iva) - (antes.subtotal + antes.iva))
                .isCloseTo(r.getVentasNetas() - antes.getVentasNetas(), within(0.001));
        assertThat(r.getVentasNetas() - antes.getVentasNetas()).isCloseTo(46.5, within(0.001));
        assertThat(r.ventasTotales - antes.ventasTotales).isCloseTo(93.0, within(0.001));   // cobradas, brutas

        // La nota de la pantalla y el corte impreso dicen lo mismo
        assertThat(VentaService.ResumenCorte.notaDevoluciones(18)).isEqualTo("  (incluye −$18.00 de devoluciones)");
        assertThat(VentaService.ResumenCorte.notaDevoluciones(0)).isEmpty();
        String impreso = texto(ticketService.documentoCorte(cajaService.estadoDelDia(), null, configuracionService.obtener(), "x"));
        List<String> lineas = List.of(impreso.split("\n"));
        int iBebidas = indiceQueEmpieza(lineas, bebidas.getNombre());
        assertThat(lineas.get(iBebidas)).endsWith("$18.00");
        assertThat(lineas.get(iBebidas + 1)).contains("incluye devoluciones").endsWith("-$18.00");
        int iLacteos = indiceQueEmpieza(lineas, lacteos.getNombre());
        assertThat(lineas.get(iLacteos + 1)).contains("incluye devoluciones").endsWith("-$28.50");
        assertThat(impreso).contains("Tasa 0%").contains("VENTAS NETAS");
    }

    private static int indiceQueEmpieza(List<String> lineas, String inicio) {
        for (int i = 0; i < lineas.size(); i++) if (lineas.get(i).startsWith(inicio)) return i;
        throw new AssertionError("No aparece «" + inicio + "» en el corte impreso");
    }

    @Test
    void unaDevolucionDeUnaVentaDeAyerRestaEnElCorteDeHoyYEnLasEstadisticasDeAyer() {
        LocalDate hoy = LocalDate.now(), ayer = hoy.minusDays(1);
        double vendidoHoyAntes = ventaService.totalVentasHoy();
        Venta v = venderMixta();
        v.setFecha(ayer.atTime(10, 0));           // la venta fue ayer
        ventaRepo.saveAndFlush(v);
        devolverUnoDeCada(v);                     // la devolución, hoy

        // Corte de ayer: intacto (la devolución no se hizo ese día)
        VentaService.ResumenCorte deAyer = ventaService.resumenCompletoDe(ayer);
        assertThat(deAyer.porDepartamento.get(bebidas.getNombre())).isCloseTo(36.0, within(0.001));
        assertThat(deAyer.devolucionesPorDepartamento).doesNotContainKey(bebidas.getNombre());

        // Corte de hoy: el departamento sin ventas hoy sale en negativo, con su nota
        VentaService.ResumenCorte deHoy = ventaService.resumenDelDia();
        assertThat(deHoy.porDepartamento.get(bebidas.getNombre())).isCloseTo(-18.0, within(0.001));
        assertThat(deHoy.porDepartamento.get(lacteos.getNombre())).isCloseTo(-28.5, within(0.001));
        assertThat(deHoy.devueltoDeDepartamento(bebidas.getNombre())).isCloseTo(18.0, within(0.001));
        // "Vendido hoy" en Ventas: regla de caja, lo reembolsado hoy resta hoy
        assertThat(ventaService.totalVentasHoy() - vendidoHoyAntes).isCloseTo(-46.5, within(0.001));

        // Estadísticas: resta en el día de la venta (ayer), nada hoy
        ProductoDiario refrescoAyer = productoDelDia(ayer, refresco);
        assertThat(refrescoAyer.getPiezas()).isCloseTo(1.0, within(0.001));
        assertThat(refrescoAyer.getImporte()).isCloseTo(18.0, within(0.001));
        assertThat(agregadosService.calcularDia(hoy).productos())
                .noneMatch(pd -> pd.getProducto().getId().equals(refresco.getId()));
    }

    private ProductoDiario productoDelDia(LocalDate dia, Producto p) {
        return agregadosService.calcularDia(dia).productos().stream()
                .filter(pd -> pd.getProducto().getId().equals(p.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Sin fila de " + p.getDescripcion() + " el " + dia));
    }

    // ── Análisis de ventas: la devolución resta en el día de la venta ────

    @Test
    void estadisticasPorCajeroHoraCajaClimaHoyYCelularCuentanLoNeto() {
        LocalDate hoy = LocalDate.now();
        EstadisticasService.Periodo dia = EstadisticasService.Periodo.dia(hoy);
        Producto arroz = producto("Arroz neto", 10, 20);      // costo 5
        Producto chicle = producto("Chicle neto", 7, 20);     // costo 3.5

        double cajeroAntes = totalCajero(dia), cajaAntes = totalCajas(dia), climaAntes = totalClima(dia);
        int ventasCajeroAntes = ventasCajero(dia);
        double hoyAntes = estadisticasService.resumenHoy(LocalDateTime.now()).cifras().total();
        double movilAntes = consultasMovilService.ventas(hoy, hoy, 0, 1).importe();
        double gananciaAntes = consultasMovilService.ganancia(hoy, hoy);

        // $50 + $7 = $57; se devuelven 2 arroces ($20) y el chicle completo ($7) → neto $30
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(arroz, 5), ventaService.crearDetalle(chicle, 1)),
                Venta.FormaPago.EFECTIVO, 100);
        // La hora de la venta antes de devolver: ya la incluye ($57); tras devolver queda en $30
        double horaConVenta = totalHora(dia, v.getFecha().getHour());
        devolucionService.devolver(new DevolucionService.Solicitud(v.getId(), List.of(
                new DevolucionService.Linea(renglon(v, arroz).getId(), 2, true),
                new DevolucionService.Linea(renglon(v, chicle).getId(), 1, true)), "Cambio de opinión"), null);

        assertThat(totalCajero(dia) - cajeroAntes).isCloseTo(30.0, within(0.001));
        assertThat(ventasCajero(dia) - ventasCajeroAntes).isEqualTo(1);           // el ticket sigue contando
        assertThat(totalHora(dia, v.getFecha().getHour()) - horaConVenta).isCloseTo(-27.0, within(0.001));
        assertThat(totalCajas(dia) - cajaAntes).isCloseTo(30.0, within(0.001));
        assertThat(totalClima(dia) - climaAntes).isCloseTo(30.0, within(0.001));
        assertThat(estadisticasService.resumenHoy(LocalDateTime.now()).cifras().total() - hoyAntes).isCloseTo(30.0, within(0.001));

        // Celular: importe de la lista de ventas y ganancia (50 − 20 − 5×3 = 15; el chicle, 0)
        assertThat(consultasMovilService.ventas(hoy, hoy, 0, 1).importe() - movilAntes).isCloseTo(30.0, within(0.001));
        assertThat(consultasMovilService.ganancia(hoy, hoy) - gananciaAntes).isCloseTo(15.0, within(0.001));

        // Productos del día (más vendidos): 3 arroces por $30; el chicle devuelto completo no aparece
        ProductoDiario arrozHoy = productoDelDia(hoy, arroz);
        assertThat(arrozHoy.getPiezas()).isCloseTo(3.0, within(0.001));
        assertThat(arrozHoy.getImporte()).isCloseTo(30.0, within(0.001));
        assertThat(agregadosService.calcularDia(hoy).productos())
                .noneMatch(pd -> pd.getProducto().getId().equals(chicle.getId()));
    }

    private double totalCajero(EstadisticasService.Periodo p) {
        return estadisticasService.ventasPorCajero(p).stream().filter(c -> c.cajero().equals("Administrador"))
                .mapToDouble(EstadisticasService.VentasCajero::total).sum();
    }

    private int ventasCajero(EstadisticasService.Periodo p) {
        return estadisticasService.ventasPorCajero(p).stream().filter(c -> c.cajero().equals("Administrador"))
                .mapToInt(EstadisticasService.VentasCajero::ventas).sum();
    }

    private double totalHora(EstadisticasService.Periodo p, int hora) {
        return estadisticasService.ventasPorHora(p).stream().filter(h -> h.hora() == hora)
                .mapToDouble(EstadisticasService.VentasHora::total).sum();
    }

    private double totalCajas(EstadisticasService.Periodo p) {
        return estadisticasService.ventasPorCaja(p).stream().mapToDouble(EstadisticasService.VentasPorCaja::total).sum();
    }

    private double totalClima(EstadisticasService.Periodo p) {
        return estadisticasService.ventasPorClima(p).stream().mapToDouble(EstadisticasService.VentasClima::importe).sum();
    }

    // ── Recomendaciones: canasta y clima sin lo devuelto ─────────────────

    @Test
    void laCanastaSeRecalculaSinLosRenglonesDevueltosCompletos() {
        Producto pan = producto("Pan neto", 12, 20);
        Producto cafe = producto("Cafe neto", 40, 20);
        Venta v1 = ventaService.cobrar(List.of(ventaService.crearDetalle(pan, 1), ventaService.crearDetalle(cafe, 1)),
                Venta.FormaPago.EFECTIVO, 100);
        ventaService.cobrar(List.of(ventaService.crearDetalle(pan, 2), ventaService.crearDetalle(cafe, 1)),
                Venta.FormaPago.EFECTIVO, 100);
        agregadosService.recalcularVentana();
        assertThat(hayPar(pan, cafe)).isTrue();                  // dos tickets juntos: se sugiere

        // El café del primer ticket se devolvió completo: ya solo van juntos en un ticket (mínimo 2)
        devolucionService.devolver(new DevolucionService.Solicitud(v1.getId(), List.of(
                new DevolucionService.Linea(renglon(v1, cafe).getId(), 1, true)), "Caducado"), null);
        agregadosService.recalcularVentana();
        assertThat(hayPar(pan, cafe)).isFalse();
        assertThat(hayPar(cafe, pan)).isFalse();
    }

    private boolean hayPar(Producto a, Producto b) {
        for (ParProductos p : paresRepo.findTodosOrdenados()) {
            if (p.getProductoA().getId().equals(a.getId()) && p.getProductoB().getId().equals(b.getId())) return true;
        }
        return false;
    }

    @Test
    void elClimaCuentaSoloLasPiezasQueElClienteSeQuedo() {
        LocalDateTime lluvia = LocalDate.now().minusDays(2).atTime(16, 0);
        LocalDateTime sol = LocalDate.now().minusDays(2).atTime(11, 0);
        List<ClimaRegistro> clima = List.of(
                new ClimaRegistro(lluvia, 12, 61, ClimaService.LLUVIA, ClimaService.FRIO),
                new ClimaRegistro(sol, 30, 0, ClimaService.DESPEJADO, ClimaService.CALOR));
        // Con lluvia: 4 de leche (devolvieron 2) y 3 refrescos (devueltos los 3); con sol: 4 refrescos
        List<Venta> ventas = List.of(
                ventaEnMemoria(lluvia.plusMinutes(5), leche, 4, 2, refresco, 3, 3),
                ventaEnMemoria(sol.plusMinutes(5), refresco, 4, 0));

        List<ProductoClima> filas = AgregadosService.calcularProductoClima(ventas, clima);

        // Piezas netas con clima: leche 2 + refresco 4 = 6; con lluvia solo las 2 leches
        ProductoClima lecheLluvia = filas.stream().filter(f -> f.getTipo() == ProductoClima.Tipo.CONDICION
                && f.getClave().equals(ClimaService.LLUVIA) && f.getProducto().getId().equals(leche.getId())).findFirst().orElseThrow();
        assertThat(lecheLluvia.getPiezas()).isCloseTo(2.0, within(0.001));
        assertThat(lecheLluvia.getIndice()).isCloseTo((2.0 / 2) / (2.0 / 6), within(0.001));
        assertThat(filas).noneMatch(f -> f.getClave().equals(ClimaService.LLUVIA) && f.getProducto().getId().equals(refresco.getId()));

        // La canasta en memoria tampoco junta lo devuelto completo
        assertThat(AgregadosService.calcularPares(List.of(
                ventaEnMemoria(lluvia, leche, 1, 0, refresco, 1, 1),
                ventaEnMemoria(sol, leche, 1, 0, refresco, 1, 1)))).isEmpty();
        assertThat(AgregadosService.calcularPares(List.of(
                ventaEnMemoria(lluvia, leche, 1, 0, refresco, 2, 1),
                ventaEnMemoria(sol, leche, 1, 0, refresco, 1, 0)))).isNotEmpty();
    }

    /** Venta sin guardar: (producto, cantidad, devuelta)… */
    private static Venta ventaEnMemoria(LocalDateTime fecha, Object... productoCantidadDevuelta) {
        Venta v = new Venta();
        v.setFecha(fecha);
        double total = 0;
        for (int i = 0; i < productoCantidadDevuelta.length; i += 3) {
            Producto p = (Producto) productoCantidadDevuelta[i];
            DetalleVenta d = new DetalleVenta();
            d.setVenta(v);
            d.setProducto(p);
            d.setCantidad(((Number) productoCantidadDevuelta[i + 1]).doubleValue());
            double devuelta = ((Number) productoCantidadDevuelta[i + 2]).doubleValue();
            if (devuelta > 0) {
                d.setCantidadDevuelta(devuelta);
                d.setImporteDevuelto(devuelta * p.getPrecioVenta());
            }
            d.setPrecioVenta(p.getPrecioVenta());
            v.getDetalles().add(d);
            total += p.getPrecioVenta() * d.getCantidad();
        }
        v.setTotal(total);
        return v;
    }
}
