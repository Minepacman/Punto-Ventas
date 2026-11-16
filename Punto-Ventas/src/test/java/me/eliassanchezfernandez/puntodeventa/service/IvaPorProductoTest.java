package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.util.CalculoIva;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * IVA por producto (16 %, 0 %, exento o el de la tienda): propuesta del departamento,
 * desglose al cobrar, ticket y corte por tasa, y el texto de productos comunes en el corte impreso.
 */
class IvaPorProductoTest extends BaseServicioTest {

    @Autowired private ConfiguracionService configuracionService;
    @Autowired private TicketService        ticketService;
    @Autowired private CajaService          cajaService;

    private Producto productoConIva(String descripcion, double precio, TipoIva iva, Departamento depto) {
        Producto p = new Producto();
        p.setCodigoBarras("I-" + UUID.randomUUID().toString().substring(0, 8));
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

    private static String texto(DocumentoImpresion d) {
        return d.lineas().stream().map(DocumentoImpresion.Linea::texto).collect(Collectors.joining("\n"));
    }

    @Test
    void elDepartamentoProponeElIvaSoloAlDarDeAltaYSinPisarLoElegido() throws Exception {
        iniciarComoAdmin();
        Departamento frutas = new Departamento();
        frutas.setNombre("Frutas " + UUID.randomUUID().toString().substring(0, 4));
        frutas.setIvaPorDefecto(TipoIva.TASA_0);
        frutas = productoService.guardarDepartamento(frutas);

        Producto manzana = productoConIva("Manzana", 40, null, frutas);
        assertThat(manzana.getIva()).isEqualTo(TipoIva.TASA_0);

        // Si se eligió uno a mano (aunque sea "usar el de la tienda"), se respeta
        Producto jugo = productoConIva("Jugo", 25, TipoIva.TASA_16, frutas);
        assertThat(jugo.getIva()).isEqualTo(TipoIva.TASA_16);
        Producto otro = productoConIva("Otro", 25, TipoIva.TIENDA, frutas);
        assertThat(otro.getIva()).isEqualTo(TipoIva.TIENDA);

        // Sin departamento o departamento sin propuesta: queda en null = usar el de la tienda
        Producto suelto = productoConIva("Suelto", 10, null, null);
        assertThat(suelto.getIvaOTienda()).isEqualTo(TipoIva.TIENDA);

        // "Usar el de la tienda" en un departamento es lo mismo que no proponer nada
        Departamento d = new Departamento();
        d.setNombre("Varios " + UUID.randomUUID().toString().substring(0, 4));
        d.setIvaPorDefecto(TipoIva.TIENDA);
        assertThat(productoService.guardarDepartamento(d).getIvaPorDefecto()).isNull();

        // Editar sin mandar el IVA (importación, móvil) no lo borra
        // (objeto nuevo con el mismo id, como lo arman la pantalla y la importación)
        Producto edit = new Producto();
        edit.setId(manzana.getId());
        edit.setCodigoBarras(manzana.getCodigoBarras());
        edit.setDescripcion("Manzana roja");
        edit.setPrecioCosto(manzana.getPrecioCosto());
        edit.setPrecioVenta(manzana.getPrecioVenta());
        edit.setTipoVenta(manzana.getTipoVenta());
        edit.setDepartamento(frutas);
        edit.setUsaInventario(true);
        edit.setCantidadMinima(1);
        assertThat(productoService.guardar(edit).getIva()).isEqualTo(TipoIva.TASA_0);
        // Cambiar el departamento no cambia el IVA de un producto ya existente
        Producto cambio = productoRepo.findById(suelto.getId()).orElseThrow();
        cambio.setDepartamento(frutas);
        assertThat(productoService.guardar(cambio).getIvaOTienda()).isEqualTo(TipoIva.TIENDA);
    }

    @Test
    void ventaMixtaGuardaElIvaPorRenglonYElTicketYElCorteLoDesglosanPorTasa() throws Exception {
        iniciarComoAdmin();
        Producto refresco = productoConIva("Refresco", 18, TipoIva.TASA_16, null);
        Producto leche    = productoConIva("Leche", 28.5, TipoIva.TASA_0, null);
        Producto jabon    = productoConIva("Jabon", 25, null, null);              // el de la tienda
        Producto revista  = productoConIva("Revista", 45, TipoIva.EXENTO, null);
        CalculoIva.Grupo antes16 = grupo(ventaService.resumenDelDia().porTasaIva, 0.16, false);

        Venta v = ventaService.cobrar(List.of(
                ventaService.crearDetalle(refresco, 2),
                ventaService.crearDetalle(leche, 1),
                ventaService.crearDetalle(jabon, 2),
                ventaService.crearDetalle(revista, 1)), Venta.FormaPago.EFECTIVO, 200);

        // 36.00 (31.03 + 4.97) + 28.50 + 50.00 (43.10 + 6.90) + 45.00
        assertThat(v.getTotal()).isCloseTo(159.50, within(0.001));
        assertThat(v.getIva()).isCloseTo(11.87, within(0.001));
        assertThat(v.getSubtotal()).isCloseTo(147.63, within(0.001));
        assertThat(v.getSubtotal() + v.getIva()).isCloseTo(v.getTotal(), within(0.001));
        DetalleVenta dLeche = v.getDetalles().stream().filter(d -> d.getProducto().getId().equals(leche.getId())).findFirst().orElseThrow();
        assertThat(dLeche.getTasaIva()).isEqualTo(0.0);
        assertThat(dLeche.getIva()).isEqualTo(0.0);
        DetalleVenta dRevista = v.getDetalles().stream().filter(d -> d.getProducto().getId().equals(revista.getId())).findFirst().orElseThrow();
        assertThat(dRevista.isIvaExento()).isTrue();

        // Ticket impreso: desglose por tasa porque la venta las mezcla
        Configuracion c = configuracionService.obtener();
        String ticket = texto(ticketService.documentoVenta(v, c, false));
        assertThat(ticket).contains("Gravado 16%").contains("IVA 16%").contains("Tasa 0%").contains("Exento de IVA");
        assertThat(ticket).contains("$11.87");

        // Una venta solo al 16 % sigue imprimiendo una sola línea de IVA
        Venta simple = ventaService.cobrar(List.of(ventaService.crearDetalle(refresco, 1)), Venta.FormaPago.EFECTIVO, 18);
        String ticketSimple = texto(ticketService.documentoVenta(simple, c, false));
        assertThat(ticketSimple).contains("IVA 16%").doesNotContain("Gravado").doesNotContain("Tasa 0%");

        // Corte: IVA por tasa sumado sobre los renglones
        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        assertThat(CalculoIva.esMixto(r.porTasaIva)).isTrue();
        CalculoIva.Grupo g16 = grupo(r.porTasaIva, 0.16, false);
        assertThat(g16.iva() - (antes16 != null ? antes16.iva() : 0)).isCloseTo(11.87 + 2.48, within(0.011));
        assertThat(grupo(r.porTasaIva, 0.0, false)).isNotNull();
        assertThat(grupo(r.porTasaIva, 0.0, true)).isNotNull();
        String corte = texto(ticketService.documentoCorte(cajaService.estadoDelDia(), null, c, "admin"));
        assertThat(corte).contains("Tasa 0%").contains("Exento de IVA");
    }

    @Test
    void elCorteImpresoMencionaLosProductosComunesSoloSiSeVeLaGanancia() throws Exception {
        iniciarComoAdmin();
        ventaService.cobrar(List.of(ventaService.crearDetalleProductoComun("Hielo", 50, 2)), Venta.FormaPago.EFECTIVO, 100);
        Configuracion c = configuracionService.obtener();

        CajaService.EstadoCaja e = cajaService.estadoDelDia();
        assertThat(e.ventas.gananciaVisible).isTrue();
        String corte = texto(ticketService.documentoCorte(e, null, c, "admin")).replace("\n", " ");
        assertThat(corte).contains("Incluye $100.00 de productos comunes (ganancia estimada 20 %)");
        assertThat(TicketService.textoProductosComunes(e.ventas))
                .isEqualTo("Incluye " + me.eliassanchezfernandez.puntodeventa.util.Moneda.formato(e.ventas.productosComunes)
                        + " de productos comunes (ganancia estimada 20 %)");

        e.ventas.gananciaVisible = false;   // quien no ve ganancias tampoco ve la estimación
        assertThat(texto(ticketService.documentoCorte(e, null, c, "admin"))).doesNotContain("productos comunes");
    }

    private static CalculoIva.Grupo grupo(List<CalculoIva.Grupo> grupos, double tasa, boolean exento) {
        return grupos.stream().filter(g -> g.exento() == exento && Math.abs(g.tasa() - tasa) < 1e-9).findFirst().orElse(null);
    }
}
