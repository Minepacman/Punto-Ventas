package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.facturacion.ConstructorCfdi;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoComun;
import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * "Producto común" (Ctrl/Cmd+P en Ventas): un artículo fuera del catálogo con nombre, precio y
 * cantidad tecleados. Apunta a un producto del sistema oculto; el renglón guarda el nombre y el
 * precio; el costo se estima en el 80 % de lo cobrado (20 % de ganancia en el corte); no toca
 * inventario, no entra a promociones ni mayoreo, sí admite descuento manual y se cancela como
 * cualquier venta.
 */
class ProductoComunTest extends BaseServicioTest {

    @Autowired private VentaRepository        ventaRepo;
    @Autowired private ProductoComunService   productoComunService;
    @Autowired private TicketPendienteService ticketPendienteService;
    @Autowired private AgregadosLote          agregadosLote;
    @Autowired private ConfiguracionService   configuracionService;

    @BeforeEach
    void entrar() throws Exception {
        iniciarComoAdmin();
    }

    @Test
    void laVentaGuardaNombrePrecioYCantidadConCostoDelOchentaPorCientoYSinTocarInventario() {
        DetalleVenta d = ventaService.crearDetalleProductoComun("Bolsa de hielo", 25, 2);
        assertThat(d.esProductoComun()).isTrue();
        assertThat(d.getDescripcionMostrada()).isEqualTo("Bolsa de hielo");
        assertThat(d.getCodigoMostrado()).isEqualTo("—");
        assertThat(d.getPrecioVenta()).isEqualTo(25);
        assertThat(d.getImporte()).isCloseTo(50, within(0.001));
        assertThat(d.getCostoUnitario()).isCloseTo(20, within(0.0001));
        assertThat(d.getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.NINGUNO);

        Venta venta = ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 100);
        assertThat(venta.getTotal()).isCloseTo(50, within(0.001));
        assertThat(venta.getCambio()).isCloseTo(50, within(0.001));

        Venta guardada = ventaRepo.findConDetallesById(venta.getId()).orElseThrow();
        DetalleVenta r = guardada.getDetalles().get(0);
        assertThat(r.getDescripcion()).isEqualTo("Bolsa de hielo");
        assertThat(r.getDescripcionMostrada()).isEqualTo("Bolsa de hielo");
        assertThat(r.getPrecioVenta()).isEqualTo(25);
        assertThat(r.getCantidad()).isEqualTo(2);
        assertThat(r.getCostoUnitario()).isCloseTo(20, within(0.0001));
        assertThat(ProductoComun.es(r.getProducto())).isTrue();

        // El producto del sistema no lleva inventario: ni existencia ni movimientos
        Producto comun = productoRepo.findByCodigoBarras(ProductoComun.CODIGO).orElseThrow();
        assertThat(comun.isUsaInventario()).isFalse();
        assertThat(comun.getCantidadActual()).isEqualTo(0);
        assertThat(productoService.movimientosDe(comun.getId())).isEmpty();
        assertThat(venta.getAlertasStockBajo()).isEmpty();
    }

    @Test
    void elCorteSumaElVeintePorCientoDeGananciaYLoMuestraAparte() {
        VentaService.ResumenCorte antes = ventaService.resumenDelDia();

        ventaService.cobrar(List.of(ventaService.crearDetalleProductoComun("Veladora", 100, 1)), Venta.FormaPago.EFECTIVO, 100);

        VentaService.ResumenCorte despues = ventaService.resumenDelDia();
        assertThat(despues.ventasTotales - antes.ventasTotales).isCloseTo(100, within(0.005));
        assertThat(despues.ganancia - antes.ganancia).isCloseTo(20, within(0.005));
        assertThat(despues.productosComunes - antes.productosComunes).isCloseTo(100, within(0.005));
        assertThat(despues.gananciaProductosComunes - antes.gananciaProductosComunes).isCloseTo(20, within(0.005));
        assertThat(despues.porDepartamento).containsKey(VentaService.ResumenCorte.DEPARTAMENTO_PRODUCTOS_COMUNES);
        assertThat(despues.porDepartamento.get(VentaService.ResumenCorte.DEPARTAMENTO_PRODUCTOS_COMUNES))
                .isGreaterThanOrEqualTo(100 - 0.005);
        // Los departamentos siguen sumando el total vendido (los comunes salieron de "Sin departamento", no se duplican)
        double sumaDeptos = despues.porDepartamento.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sumaDeptos).isCloseTo(despues.ventasTotales, within(0.05));
    }

    @Test
    void nombreVacioUsaElPorDefectoYPrecioOCantidadInvalidosSeRechazan() {
        assertThat(ventaService.crearDetalleProductoComun("   ", 10, 1).getDescripcionMostrada())
                .isEqualTo(ProductoComun.NOMBRE_POR_DEFECTO);
        assertThat(ventaService.crearDetalleProductoComun("  Pan   dulce  ", 10, 1).getDescripcion()).isEqualTo("Pan dulce");

        assertThatThrownBy(() -> ventaService.crearDetalleProductoComun("X", 0, 1))
                .isInstanceOf(NegocioException.class).hasMessageContaining("precio");
        assertThatThrownBy(() -> ventaService.crearDetalleProductoComun("X", -5, 1))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> ventaService.crearDetalleProductoComun("X", 10, 0))
                .isInstanceOf(NegocioException.class).hasMessageContaining("cantidad");

        // Decimales (kilos) aunque la tienda no venda a granel
        var c = configuracionService.obtener();
        configuracionService.guardarOpciones(c.isPedirFondoCaja(), c.isAvisoStockBajo(), false, c.isConfirmarCobro(),
                c.isMostrarSugerencias());
        assertThat(configuracionService.isPermitirGranel()).isFalse();
        DetalleVenta kilo = ventaService.crearDetalleProductoComun("Tomate", 30, 1.5);
        assertThat(kilo.getImporte()).isCloseTo(45, within(0.001));
        // (la transacción de la prueba se revierte: la configuración queda como estaba)
    }

    @Test
    void lasPromocionesNoAplicanElMayoreoSeRechazaYElDescuentoManualSi() {
        Producto comun = productoComunService.obtenerOCrear();
        // Aunque alguien lograra colgarle una promoción al producto del sistema, no aplica
        Promocion promo = new Promocion();
        promo.setProducto(comun);
        promo.setTipo(Promocion.Tipo.PORCENTAJE);
        promo.setValor(50);
        promo.setFechaInicio(LocalDate.now().minusDays(1));
        promo.setActiva(true);
        promocionService.guardar(promo);

        DetalleVenta d = ventaService.crearDetalleProductoComun("Pastel", 100, 2);
        assertThat(d.getDescuento()).isZero();
        assertThat(d.getPromocionId()).isNull();

        assertThatThrownBy(() -> ventaService.aplicarMayoreo(d, true, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("mayoreo");
        assertThat(d.isMayoreo()).isFalse();

        ventaService.aplicarDescuentoManual(d, 10.0, null, null);
        assertThat(d.getTipoDescuento()).isEqualTo(DetalleVenta.TipoDescuento.MANUAL);
        assertThat(d.getDescuento()).isCloseTo(20, within(0.001));
        assertThat(d.getImporte()).isCloseTo(180, within(0.001));
        // El costo estimado sigue a lo que de verdad se cobra: 80 % de 180 entre 2 piezas
        assertThat(d.getCostoUnitario()).isCloseTo(72, within(0.0001));

        // Un descuento por importe mayor al renglón se rechaza con el precio tecleado como base
        assertThatThrownBy(() -> ventaService.aplicarDescuentoManual(d, null, 250.0, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("mayor");

        VentaService.ResumenCorte antes = ventaService.resumenDelDia();
        Venta venta = ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 200);
        assertThat(venta.getTotal()).isCloseTo(180, within(0.001));
        assertThat(venta.getDetalles().get(0).getCostoUnitario()).isCloseTo(72, within(0.0001));
        VentaService.ResumenCorte despues = ventaService.resumenDelDia();
        assertThat(despues.ganancia - antes.ganancia).isCloseTo(36, within(0.005));
    }

    @Test
    void cancelarUnaVentaConProductoComunFuncionaYQuitaSuGanancia() {
        VentaService.ResumenCorte antes = ventaService.resumenDelDia();
        Venta venta = ventaService.cobrar(List.of(
                ventaService.crearDetalleProductoComun("Hielo", 25, 2),
                ventaService.crearDetalle(producto("Cola", 20, 10), 1)), Venta.FormaPago.EFECTIVO, 100);

        Venta cancelada = ventaService.cancelar(venta.getId(), "se arrepintió", null);

        assertThat(cancelada.isCancelada()).isTrue();
        VentaService.ResumenCorte despues = ventaService.resumenDelDia();
        assertThat(despues.ganancia).isCloseTo(antes.ganancia, within(0.005));
        assertThat(despues.productosComunes).isCloseTo(antes.productosComunes, within(0.005));
        assertThat(despues.numCanceladas - antes.numCanceladas).isEqualTo(1);
        Producto comun = productoRepo.findByCodigoBarras(ProductoComun.CODIGO).orElseThrow();
        assertThat(comun.getCantidadActual()).isEqualTo(0);
        assertThat(productoService.movimientosDe(comun.getId())).isEmpty();
    }

    @Test
    void elProductoDelSistemaNoApareceEnElCatalogoNiSeVendePorCodigo() {
        Producto comun = productoComunService.obtenerOCrear();
        assertThat(comun.getId()).isNotNull();
        assertThat(productoComunService.obtenerOCrear().getId()).isEqualTo(comun.getId());   // uno solo

        assertThat(productoService.listarTodos()).noneMatch(ProductoComun::es);
        assertThat(productoService.buscar("")).noneMatch(ProductoComun::es);
        assertThat(productoService.buscar("común")).noneMatch(ProductoComun::es);
        assertThat(productoService.buscar(ProductoComun.CODIGO)).noneMatch(ProductoComun::es);
        assertThat(productoService.buscarParaVenta("Producto")).noneMatch(ProductoComun::es);
        assertThat(productoService.buscarPorNombre("Producto común")).noneMatch(ProductoComun::es);
        assertThat(productoService.productosConInventario()).noneMatch(ProductoComun::es);
        assertThat(ventaService.buscarPorCodigo(ProductoComun.CODIGO)).isEmpty();
        assertThat(ventaService.buscarPorNombre("Producto común")).noneMatch(ProductoComun::es);
        assertThat(productoRepo.contarCatalogo()).isEqualTo(productoRepo.count() - 1);

        // Por el camino normal (código / F6) no se vende: hace falta nombre y precio
        assertThatThrownBy(() -> ventaService.crearDetalle(comun, 1))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Ctrl/Cmd+P");
    }

    @Test
    void elTicketEnEsperaConservaNombreYPrecio() {
        DetalleVenta d = ventaService.crearDetalleProductoComun("Veladora grande", 18.5, 3);
        TicketPendienteService.EnEspera e = ticketPendienteService.ponerEnEspera(List.of(d), null);

        List<DetalleVenta> recuperados = ticketPendienteService.recuperarEnEspera(e.id());
        ventaService.recalcularTicket(recuperados);

        assertThat(recuperados).hasSize(1);
        DetalleVenta r = recuperados.get(0);
        assertThat(r.esProductoComun()).isTrue();
        assertThat(r.getDescripcionMostrada()).isEqualTo("Veladora grande");
        assertThat(r.getPrecioVenta()).isEqualTo(18.5);
        assertThat(r.getCantidad()).isEqualTo(3);
        assertThat(r.getImporte()).isCloseTo(55.5, within(0.001));
    }

    @Test
    void laFacturaUsaLaClaveGenericaDelSatYElNombreTecleado() {
        Venta venta = ventaService.cobrar(List.of(
                ventaService.crearDetalleProductoComun("Hielo en bolsa", 25, 2),
                ventaService.crearDetalleProductoComun("Tomate", 30, 1.5)), Venta.FormaPago.EFECTIVO, 100);

        List<ConstructorCfdi.Linea> lineas = ConstructorCfdi.lineasDe(venta);

        assertThat(lineas).hasSize(2);
        ConstructorCfdi.Linea pieza = lineas.get(0);
        assertThat(pieza.claveProdServ()).isEqualTo(CatalogoSat.PROD_SERV_COMODIN);
        assertThat(pieza.noIdentificacion()).isNull();
        assertThat(pieza.descripcion()).isEqualTo("Hielo en bolsa");
        assertThat(pieza.claveUnidad()).isEqualTo(CatalogoSat.UNIDAD_PIEZA);
        assertThat(pieza.objetoImp()).isEqualTo(CatalogoSat.OBJETO_IMP_SI);
        assertThat(pieza.brutoConIva()).isEqualByComparingTo("50.0");

        ConstructorCfdi.Linea kilos = lineas.get(1);
        assertThat(kilos.claveUnidad()).isEqualTo("KGM");
        assertThat(kilos.descripcion()).isEqualTo("Tomate");
        assertThat(kilos.cantidad()).isEqualByComparingTo("1.5");

        // Y los conceptos cuadran con el total de la venta
        var conceptos = ConstructorCfdi.conceptos(lineas, new java.math.BigDecimal("0.16"),
                java.math.BigDecimal.valueOf(venta.getTotal()));
        assertThat(conceptos).hasSize(2);
    }

    @Test
    void lasEstadisticasLoCuentanComoProductoComunPeroNoLoEmparejanConOtros() {
        Producto cola = producto("Cola", 20, 10);
        Venta venta = ventaService.cobrar(List.of(
                ventaService.crearDetalle(cola, 1),
                ventaService.crearDetalleProductoComun("Hielo", 25, 1)), Venta.FormaPago.EFECTIVO, 100);

        // Canasta: el común no forma pares (no es un producto real que se pueda sugerir)
        assertThat(AgregadosService.calcularPares(List.of(venta))).isEmpty();

        // Ranking por producto: sí cuenta su venta, bajo el nombre del producto del sistema
        var dias = agregadosLote.calcularDias(LocalDate.now(), LocalDate.now());
        List<ProductoDiario> productos = dias.get(LocalDate.now()).productos();
        assertThat(productos).anyMatch(pd -> pd.getProducto().getId().equals(cola.getId()));
        assertThat(productos).anyMatch(pd -> ProductoComun.es(pd.getProducto())
                && pd.getProducto().getDescripcion().equals(ProductoComun.NOMBRE_POR_DEFECTO) && pd.getImporte() >= 25);
    }

    @Test
    void elCostoEstimadoEsElOchentaPorCientoDeLoCobrado() {
        assertThat(ProductoComun.costoUnitarioEstimado(100, 1)).isCloseTo(80, within(0.0001));
        assertThat(ProductoComun.costoUnitarioEstimado(180, 2)).isCloseTo(72, within(0.0001));
        assertThat(ProductoComun.costoUnitarioEstimado(0, 1)).isZero();
        assertThat(ProductoComun.costoUnitarioEstimado(50, 0)).isZero();
        assertThat(ProductoComun.MARGEN_GANANCIA).isEqualTo(0.20);
        assertThat(ProductoComun.nombreNormalizado(null)).isEqualTo(ProductoComun.NOMBRE_POR_DEFECTO);
        assertThat(ProductoComun.nombreNormalizado("x".repeat(500))).hasSize(ProductoComun.MAX_LARGO_NOMBRE);
    }
}
