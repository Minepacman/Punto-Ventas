package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoComun;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** IVA por producto: desglose renglón por renglón de precios que ya incluyen el impuesto. */
class CalculoIvaTest {

    private static final double TIENDA = 0.16;

    private static Producto producto(TipoIva iva) {
        Producto p = new Producto();
        p.setCodigoBarras("P-" + (iva == null ? "null" : iva.name()));
        p.setDescripcion("Prueba");
        p.setIva(iva);
        return p;
    }

    private static DetalleVenta renglon(TipoIva iva, double precio, double cantidad, double descuento) {
        DetalleVenta d = new DetalleVenta();
        d.setProducto(producto(iva));
        d.setPrecioVenta(precio);
        d.setCantidad(cantidad);
        d.setDescuento(descuento);
        return d;
    }

    @Test
    void desglosaCadaTasaDelPrecioQueYaIncluyeElIva() {
        assertThat(CalculoIva.desglosar(116, 0.16)).isEqualTo(new CalculoIva.Desglose(100, 16));
        assertThat(CalculoIva.desglosar(36, 0.16)).isEqualTo(new CalculoIva.Desglose(31.03, 4.97));
        assertThat(CalculoIva.desglosar(20, 0.0)).isEqualTo(new CalculoIva.Desglose(20, 0));
    }

    @Test
    void laTasaSaleDelProductoYUsarElDeLaTiendaTomaLaConfigurada() {
        assertThat(CalculoIva.tasaDe(producto(TipoIva.TASA_16), 0.08)).isEqualTo(new CalculoIva.Tasa(0.16, false));
        assertThat(CalculoIva.tasaDe(producto(TipoIva.TASA_0), TIENDA)).isEqualTo(new CalculoIva.Tasa(0.0, false));
        assertThat(CalculoIva.tasaDe(producto(TipoIva.EXENTO), TIENDA)).isEqualTo(new CalculoIva.Tasa(0.0, true));
        assertThat(CalculoIva.tasaDe(producto(TipoIva.TIENDA), 0.08)).isEqualTo(new CalculoIva.Tasa(0.08, false));
        // Productos anteriores al campo (null) = usar el de la tienda: nada cambia hasta editarlos
        assertThat(CalculoIva.tasaDe(producto(null), TIENDA)).isEqualTo(new CalculoIva.Tasa(0.16, false));
        // Producto común: siempre la tasa de la tienda, aunque el producto del sistema dijera otra cosa
        Producto comun = producto(TipoIva.TASA_0);
        comun.setCodigoBarras(ProductoComun.CODIGO);
        assertThat(CalculoIva.tasaDe(comun, TIENDA)).isEqualTo(new CalculoIva.Tasa(0.16, false));
    }

    @Test
    void ticketMixtoConDescuentoGuardaTasaEIvaPorRenglonYCuadraConElTotal() {
        DetalleVenta refresco = renglon(TipoIva.TASA_16, 18, 2, 0);       // 36.00 → 31.03 + 4.97
        DetalleVenta leche    = renglon(TipoIva.TASA_0, 28.5, 1, 0);      // 28.50 → 28.50 + 0
        DetalleVenta jabon    = renglon(TipoIva.TIENDA, 25, 3, 15);       // 60.00 → 51.72 + 8.28
        DetalleVenta revista  = renglon(TipoIva.EXENTO, 45, 1, 0);        // 45.00 exento
        List<DetalleVenta> ticket = List.of(refresco, leche, jabon, revista);

        double base = 0, iva = 0, total = 0;
        for (DetalleVenta d : ticket) {
            CalculoIva.Desglose g = CalculoIva.aplicar(d, TIENDA);
            base += g.base();
            iva += g.iva();
            total += d.getImporte();
        }
        assertThat(refresco.getTasaIva()).isEqualTo(0.16);
        assertThat(refresco.getIva()).isCloseTo(4.97, within(0.001));
        assertThat(leche.getTasaIva()).isEqualTo(0.0);
        assertThat(leche.getIva()).isEqualTo(0.0);
        assertThat(leche.isIvaExento()).isFalse();
        assertThat(jabon.getIva()).isCloseTo(8.28, within(0.001));
        assertThat(revista.isIvaExento()).isTrue();
        assertThat(revista.getIva()).isEqualTo(0.0);
        assertThat(CalculoIva.redondear(base + iva)).isCloseTo(total, within(0.001));
        assertThat(CalculoIva.redondear(iva)).isCloseTo(13.25, within(0.001));

        List<CalculoIva.Grupo> grupos = CalculoIva.agrupar(ticket, TIENDA);
        assertThat(CalculoIva.esMixto(grupos)).isTrue();
        assertThat(grupos).hasSize(3);
        // Gravados primero, luego tasa 0, al final exentos
        assertThat(grupos.get(0)).isEqualTo(new CalculoIva.Grupo(0.16, false, 82.75, 13.25));
        assertThat(grupos.get(1)).isEqualTo(new CalculoIva.Grupo(0.0, false, 28.5, 0));
        assertThat(grupos.get(2)).isEqualTo(new CalculoIva.Grupo(0.0, true, 45, 0));
        assertThat(grupos.get(0).etiqueta()).isEqualTo("IVA 16 %");
        assertThat(grupos.get(1).etiqueta()).isEqualTo("IVA 0 %");
        assertThat(grupos.get(2).etiqueta()).isEqualTo("Exento de IVA");
    }

    @Test
    void renglonesViejosSinTasaGuardadaUsanLaDeLaVenta() {
        DetalleVenta viejo = renglon(null, 116, 1, 0);   // sin tasa ni IVA guardados
        List<CalculoIva.Grupo> grupos = CalculoIva.agrupar(List.of(viejo), 0.16);
        assertThat(grupos).containsExactly(new CalculoIva.Grupo(0.16, false, 100, 16));
        assertThat(CalculoIva.esMixto(grupos)).isFalse();
    }

    @Test
    void conMuchosRenglonesYGranelLaSumaDeBasesEIvasEsElTotal() {
        Random r = new Random(7);
        TipoIva[] tipos = TipoIva.values();
        for (int vuelta = 0; vuelta < 200; vuelta++) {
            List<DetalleVenta> ticket = new ArrayList<>();
            int n = 1 + r.nextInt(25);
            for (int i = 0; i < n; i++) {
                double precio = Math.round((1 + r.nextDouble() * 300) * 100) / 100.0;
                double cantidad = r.nextBoolean() ? 1 + r.nextInt(5) : Math.round(r.nextDouble() * 3000) / 1000.0 + 0.001;
                double descuento = r.nextInt(4) == 0 ? Math.round(precio * cantidad * 0.1 * 100) / 100.0 : 0;
                ticket.add(renglon(tipos[r.nextInt(tipos.length)], precio, cantidad, descuento));
            }
            double base = 0, iva = 0, total = 0;
            for (DetalleVenta d : ticket) {
                CalculoIva.Desglose g = CalculoIva.aplicar(d, TIENDA);
                base += g.base();
                iva += g.iva();
                total += d.getImporte();
                // Margen del SAT por renglón: |iva − base × tasa| ≤ 0.01
                assertThat(Math.abs(g.iva() - g.base() * d.getTasaIva())).isLessThanOrEqualTo(0.01 + 1e-9);
            }
            assertThat(CalculoIva.redondear(base) + CalculoIva.redondear(iva))
                    .isCloseTo(CalculoIva.redondear(total), within(0.001));
        }
    }
}
