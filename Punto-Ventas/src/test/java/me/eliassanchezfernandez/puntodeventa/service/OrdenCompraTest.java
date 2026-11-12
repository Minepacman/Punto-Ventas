package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra.Estado;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaOrden;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaSugerida;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.ModoSugerencia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Órdenes de compra: captura, sugerencia, recepción al inventario, permisos y exportación. */
class OrdenCompraTest extends BaseServicioTest {

    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private TicketService ticketService;
    @Autowired private ConfiguracionService configuracionService;

    private Proveedor bimbo;

    @BeforeEach
    void iniciar() throws Exception {
        iniciarComoAdmin();
        bimbo = proveedorService.guardar(null, "Bimbo", null, "55 1111", "Don Luis", Set.of(), null);
    }

    private Producto conMinimo(String nombre, double stock, double minimo) {
        Producto p = producto(nombre, 20, stock);
        p.setCantidadMinima(minimo);
        return productoRepo.save(p);
    }

    private List<MovimientoInventario> entradas(Producto p) {
        return productoService.movimientosDe(p.getId()).stream()
                .filter(m -> m.getTipo() == MovimientoInventario.Tipo.ENTRADA).toList();
    }

    // ── Captura ───────────────────────────────────────────────────────────

    @Test
    void creaUnBorradorJuntandoLosProductosRepetidosYCalculandoElTotal() {
        Producto pan = conMinimo("Pan", 2, 5);
        Producto leche = conMinimo("Leche", 3, 5);

        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(
                new LineaOrden(pan.getId(), 10, 12.5),
                new LineaOrden(leche.getId(), 6, 20),
                new LineaOrden(pan.getId(), 5, 13)), "Entregar en la mañana");

        assertThat(o.getEstado()).isEqualTo(Estado.BORRADOR);
        assertThat(o.getLineas()).hasSize(2);
        assertThat(o.getLineas().get(0).getCantidad()).isEqualTo(15);           // 10 + 5 del mismo producto
        assertThat(o.getLineas().get(0).getCostoUnitario()).isEqualTo(13);      // gana el último costo
        assertThat(o.getTotal()).isEqualTo(15 * 13 + 6 * 20);
        assertThat(o.getCreadaPor()).isNotBlank();
        assertThat(auditoriaService.ultimas()).extracting(a -> a.getAccion()).contains("ORDEN_COMPRA_CREADA");
    }

    @Test
    void validaProveedorLineasCantidadesYProductos() {
        Producto pan = conMinimo("Pan", 2, 5);
        Producto servicio = producto("Servicio", 100, 0);
        servicio.setUsaInventario(false);
        productoRepo.save(servicio);
        Producto pieza = conMinimo("Pieza", 1, 1);

        assertThatThrownBy(() -> ordenCompraService.crear(bimbo.getId(), List.of(), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("al menos un producto");
        assertThatThrownBy(() -> ordenCompraService.crear(null, List.of(new LineaOrden(pan.getId(), 1, 1)), null))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 0, 1)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("mayor a cero");
        assertThatThrownBy(() -> ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 1, -1)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("negativo");
        assertThatThrownBy(() -> ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(servicio.getId(), 1, 1)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no maneja inventario");
        assertThatThrownBy(() -> ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pieza.getId(), 1.5, 1)), null))
                .isInstanceOf(NegocioException.class);   // por pieza: sin decimales

        proveedorService.cambiarActivo(bimbo.getId(), false);
        assertThatThrownBy(() -> ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 1, 1)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("desactivado");
    }

    @Test
    void soloUnBorradorSeEditaYElFlujoDeEstadosSeRespeta() {
        Producto pan = conMinimo("Pan", 2, 5);
        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 3, 10)), null);

        OrdenCompra editada = ordenCompraService.actualizar(o.getId(), List.of(new LineaOrden(pan.getId(), 8, 11)), "nota");
        assertThat(editada.getLineas()).hasSize(1);
        assertThat(editada.getLineas().get(0).getCantidad()).isEqualTo(8);
        assertThat(editada.getNotas()).isEqualTo("nota");

        assertThat(ordenCompraService.marcarEnviada(o.getId()).getEstado()).isEqualTo(Estado.ENVIADA);
        assertThatThrownBy(() -> ordenCompraService.actualizar(o.getId(), List.of(new LineaOrden(pan.getId(), 1, 1)), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("borrador");
        assertThatThrownBy(() -> ordenCompraService.marcarEnviada(o.getId())).isInstanceOf(NegocioException.class);

        assertThat(ordenCompraService.cancelar(o.getId()).getEstado()).isEqualTo(Estado.CANCELADA);
        assertThatThrownBy(() -> ordenCompraService.cancelar(o.getId())).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> ordenCompraService.recibir(o.getId(), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("cancelada");
        assertThat(stockActual(pan)).isEqualTo(2);                               // nada entró
    }

    // ── Recibir ───────────────────────────────────────────────────────────

    @Test
    void recibirSumaTodasLasLineasConMovimientoCostoYProveedor() {
        Producto pan = conMinimo("Pan", 2, 5);
        Producto leche = conMinimo("Leche", 4, 5);
        pan.setPrecioCosto(10);
        productoRepo.save(pan);
        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(
                new LineaOrden(pan.getId(), 10, 12), new LineaOrden(leche.getId(), 6, 20)), null);
        ordenCompraService.marcarEnviada(o.getId());

        OrdenCompra r = ordenCompraService.recibir(o.getId(), null);

        assertThat(r.getEstado()).isEqualTo(Estado.RECIBIDA);
        assertThat(r.getFechaRecepcion()).isNotNull();
        assertThat(r.getRecibidaPor()).isNotBlank();
        assertThat(stockActual(pan)).isEqualTo(12);
        assertThat(stockActual(leche)).isEqualTo(10);
        assertThat(productoRepo.findById(pan.getId()).orElseThrow().getPrecioCosto()).isEqualTo(12);   // costo de esta compra
        MovimientoInventario m = entradas(pan).get(0);
        assertThat(m.getCantidad()).isEqualTo(10);
        assertThat(m.getExistenciaAntes()).isEqualTo(2);
        assertThat(m.getExistenciaDespues()).isEqualTo(12);
        assertThat(m.getProveedorId()).isEqualTo(bimbo.getId());
        assertThat(m.getProveedor()).isEqualTo("Bimbo");
        assertThat(m.getMotivo()).isEqualTo("Orden de compra #" + o.getId());
        assertThat(entradas(leche)).hasSize(1);
        assertThat(auditoriaService.ultimas()).extracting(a -> a.getAccion()).contains("ORDEN_COMPRA_RECIBIDA");
    }

    @Test
    void unaOrdenYaRecibidaNoSeRecibeOtraVez() {
        Producto pan = conMinimo("Pan", 2, 5);
        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 10, 12)), null);
        ordenCompraService.recibir(o.getId(), null);

        assertThatThrownBy(() -> ordenCompraService.recibir(o.getId(), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya fue recibida");
        assertThatThrownBy(() -> ordenCompraService.cancelar(o.getId())).isInstanceOf(NegocioException.class);
        assertThat(stockActual(pan)).isEqualTo(12);                              // no se duplicó la entrada
    }

    @Test
    void recibirPideAjustarInventarioPeroUnAdminPuedeAutorizar() throws Exception {
        Producto pan = conMinimo("Pan", 2, 5);
        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 10, 12)), null);

        iniciarComoCajero("recibidor", EnumSet.of(Permiso.GESTIONAR_PROVEEDORES));
        assertThatThrownBy(() -> ordenCompraService.recibir(o.getId(), null)).isInstanceOf(PermisoRequeridoException.class);
        assertThat(stockActual(pan)).isEqualTo(2);

        Autorizacion aut = autorizacionAdmin();
        assertThat(ordenCompraService.recibir(o.getId(), List.of(aut)).getEstado()).isEqualTo(Estado.RECIBIDA);
        assertThat(stockActual(pan)).isEqualTo(12);
    }

    @Autowired private AutorizacionService autorizacionService;

    private Autorizacion autorizacionAdmin() {
        return autorizacionService.autorizar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA, Permiso.AJUSTAR_INVENTARIO);
    }

    @Test
    void sinGestionarProveedoresNoSeVenNiSeCreanOrdenes() throws Exception {
        Producto pan = conMinimo("Pan", 2, 5);
        iniciarComoCajero("sinprov", EnumSet.of(Permiso.AJUSTAR_INVENTARIO));

        assertThatThrownBy(() -> ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 1, 1)), null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> ordenCompraService.listar()).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> ordenCompraService.sugerirPedido(bimbo.getId(), ModoSugerencia.BAJO_MINIMO))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> ordenCompraService.aCsv(1L)).isInstanceOf(PermisoRequeridoException.class);
    }

    // ── Sugerir pedido ────────────────────────────────────────────────────

    @Test
    void sugiereLosProductosBajoElMinimoParaVolverAlDobleDelMinimo() {
        Producto bajo = conMinimo("Bajo", 1, 5);          // objetivo 10 → pedir 9
        Producto enElMinimo = conMinimo("En el mínimo", 5, 5);   // objetivo 10 → pedir 5
        Producto bien = conMinimo("Bien surtido", 20, 5);
        Producto agotado = conMinimo("Agotado", 0, 0);    // objetivo 1 → pedir 1
        Producto desactivado = conMinimo("Descontinuado", 0, 5);
        productoService.cambiarActivo(desactivado.getId(), false);

        List<LineaSugerida> s = ordenCompraService.sugerirPedido(bimbo.getId(), ModoSugerencia.BAJO_MINIMO);

        assertThat(s).extracting(l -> l.producto().getDescripcion())
                .contains("Bajo", "En el mínimo", "Agotado").doesNotContain("Bien surtido", "Descontinuado");
        assertThat(cantidadDe(s, "Bajo")).isEqualTo(9);
        assertThat(cantidadDe(s, "En el mínimo")).isEqualTo(5);
        assertThat(cantidadDe(s, "Agotado")).isEqualTo(1);
        assertThat(s.stream().filter(l -> l.producto().getId().equals(bajo.getId())).findFirst().orElseThrow().costoUnitario())
                .isEqualTo(bajo.getPrecioCosto());
        assertThat(enElMinimo).isNotNull();
        assertThat(bien).isNotNull();
        assertThat(agotado).isNotNull();
    }

    private static double cantidadDe(List<LineaSugerida> s, String producto) {
        return s.stream().filter(l -> l.producto().getDescripcion().equals(producto)).findFirst().orElseThrow().cantidad();
    }

    @Test
    void laSugerenciaPuedeLimitarseOAmpliarseConLoQueElProveedorYaSurtio() {
        Producto surtidoBajo = conMinimo("Surtido y bajo", 1, 5);
        Producto surtidoBien = conMinimo("Surtido y bien", 30, 6);
        Producto ajenoBajo = conMinimo("Ajeno y bajo", 0, 5);
        Proveedor sabritas = proveedorService.guardar(null, "Sabritas", null, null, null, Set.of(), null);
        productoService.registrarEntradaDeProveedor(surtidoBajo.getId(), 1, bimbo.getId(), null, 5, null);
        productoService.registrarEntradaDeProveedor(surtidoBien.getId(), 1, bimbo.getId(), null, 5, null);
        productoService.registrarEntradaDeProveedor(ajenoBajo.getId(), 1, sabritas.getId(), null, 5, null);
        // Entrada antigua solo con texto, que coincide con el nombre de otro proveedor
        Producto viejo = conMinimo("Surtido por texto", 0, 2);
        Proveedor lala = proveedorService.guardar(null, "Lala", null, null, null, Set.of(), null);
        productoService.registrarEntrada(viejo.getId(), 1, "  LALA ", 4, null);

        List<LineaSugerida> soloDelProveedor = ordenCompraService.sugerirPedido(bimbo.getId(), ModoSugerencia.BAJO_MINIMO_DEL_PROVEEDOR);
        assertThat(soloDelProveedor).extracting(l -> l.producto().getDescripcion())
                .contains("Surtido y bajo").doesNotContain("Surtido y bien", "Ajeno y bajo");

        List<LineaSugerida> conExtras = ordenCompraService.sugerirPedido(bimbo.getId(), ModoSugerencia.BAJO_MINIMO_Y_DEL_PROVEEDOR);
        assertThat(conExtras).extracting(l -> l.producto().getDescripcion())
                .contains("Surtido y bajo", "Surtido y bien", "Ajeno y bajo");
        assertThat(cantidadDe(conExtras, "Surtido y bien")).isEqualTo(6);       // el mínimo
        assertThat(conExtras.stream().filter(l -> l.producto().getDescripcion().equals("Surtido y bien")).findFirst().orElseThrow().motivo())
                .contains("surte este proveedor");

        // Por nombre: las entradas anteriores a los proveedores cuentan
        assertThat(ordenCompraService.sugerirPedido(lala.getId(), ModoSugerencia.BAJO_MINIMO_DEL_PROVEEDOR))
                .extracting(l -> l.producto().getDescripcion()).containsExactly("Surtido por texto");

        // Un proveedor sin historial no recibe nada en el modo "solo del proveedor"
        Proveedor nuevo = proveedorService.guardar(null, "Recién llegado", null, null, null, Set.of(), null);
        assertThat(ordenCompraService.sugerirPedido(nuevo.getId(), ModoSugerencia.BAJO_MINIMO_DEL_PROVEEDOR)).isEmpty();
    }

    @Test
    void enGranelLaCantidadSugeridaPuedeLlevarDecimales() {
        Producto frijol = producto("Frijol", 30, 1, Producto.TipoVenta.GRANEL, null);
        frijol.setCantidadMinima(2.5);
        productoRepo.save(frijol);
        List<LineaSugerida> s = ordenCompraService.sugerirPedido(bimbo.getId(), ModoSugerencia.BAJO_MINIMO);
        assertThat(cantidadDe(s, "Frijol")).isEqualTo(4);        // objetivo 5 − existencia 1
    }

    // ── Exportar e imprimir ──────────────────────────────────────────────

    @Test
    void elCsvTraeLasLineasElTotalYNeutralizaFormulas() throws Exception {
        Producto raro = conMinimo("=SUMA(A1)", 1, 5);
        Producto pan = conMinimo("Pan, de caja", 1, 5);
        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(
                new LineaOrden(raro.getId(), 2, 5), new LineaOrden(pan.getId(), 3, 10)), null);

        String csv = ordenCompraService.aCsv(o.getId());

        assertThat(csv).startsWith("Código,Producto,Cantidad,Costo unitario,Importe\r\n");
        assertThat(csv).contains("'=SUMA(A1)").doesNotContain(",=SUMA(A1)");
        assertThat(csv).contains("\"Pan, de caja\",3,10.00,30.00").contains(",TOTAL,,,40.00");

        Path archivo = Files.createTempFile("orden", ".csv");
        try {
            ordenCompraService.exportarCsv(o.getId(), archivo);
            assertThat(Files.readAllBytes(archivo)).startsWith(0xEF, 0xBB, 0xBF);   // BOM para Excel
            assertThat(Files.readString(archivo)).contains("TOTAL");
        } finally {
            Files.deleteIfExists(archivo);
        }
        assertThat(auditoriaService.ultimas()).extracting(a -> a.getAccion()).contains("ORDEN_COMPRA_EXPORTADA");
    }

    @Test
    void elDocumentoParaImprimirMuestraProveedorLineasYTotalSinPreciosDeVenta() {
        Producto pan = conMinimo("Pan blanco", 1, 5);
        OrdenCompra o = ordenCompraService.crear(bimbo.getId(), List.of(new LineaOrden(pan.getId(), 4, 12.5)), "Urgente");
        OrdenCompra completa = ordenCompraService.obtener(o.getId());

        DocumentoImpresion doc = ticketService.documentoOrdenCompra(completa, configuracionService.obtener());
        String texto = doc.aTexto(48);

        assertThat(texto).contains("ORDEN DE COMPRA #" + o.getId()).contains("Proveedor: Bimbo").contains("Contacto: Don Luis")
                .contains("4 x Pan blanco").contains("$12.50").contains("$50.00").contains("TOTAL").contains("Notas: Urgente");
        assertThat(texto).doesNotContain("Ganancia");
    }
}
