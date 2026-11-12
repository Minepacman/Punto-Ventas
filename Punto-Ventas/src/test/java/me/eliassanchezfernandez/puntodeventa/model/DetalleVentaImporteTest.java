package me.eliassanchezfernandez.puntodeventa.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El importe de cada renglón va redondeado a centavos, así la suma de lo que
 * el cliente ve en el ticket coincide con el total (antes, con granel, el
 * total sobre importes sin redondear podía diferir un centavo).
 */
class DetalleVentaImporteTest {

    private static DetalleVenta renglon(double precio, double cantidad, double descuento) {
        DetalleVenta d = new DetalleVenta();
        d.setPrecioVenta(precio);
        d.setCantidad(cantidad);
        d.setDescuento(descuento);
        return d;
    }

    @Test
    void elImporteSeRedondeaACentavos() {
        assertThat(renglon(12.345, 1, 0).getImporte()).isEqualTo(12.35);
        assertThat(renglon(0.1, 3, 0).getImporte()).isEqualTo(0.3);
        assertThat(renglon(19.99, 1.5, 0).getImporte()).isEqualTo(29.99);   // 29.985 → 29.99
        assertThat(renglon(10, 2, 0.005).getImporte()).isEqualTo(20.0);     // 19.995 → 20.00
    }

    @Test
    void laSumaDeLosRenglonesImpresosCoincideConElTotal() {
        DetalleVenta[] granel = {renglon(12.345, 1, 0), renglon(7.115, 1, 0), renglon(3.335, 1, 0)};
        double sumaImpresa = 0;
        for (DetalleVenta d : granel) sumaImpresa += Math.round(d.getImporte() * 100.0) / 100.0;
        double total = Math.round(java.util.Arrays.stream(granel).mapToDouble(DetalleVenta::getImporte).sum() * 100.0) / 100.0;
        assertThat(Math.round(sumaImpresa * 100.0) / 100.0).isEqualTo(total);
    }
}
