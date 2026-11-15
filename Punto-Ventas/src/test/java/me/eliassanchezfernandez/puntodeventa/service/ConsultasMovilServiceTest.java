package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consultas acotadas del móvil: páginas de ventas (orden, bordes, totales del rango completo),
 * ganancia sumada en SQL e inventario filtrado/ordenado/limitado en SQL. Las ventas van en un
 * día lejano para no mezclarse con las de otras pruebas (BD compartida).
 */
class ConsultasMovilServiceTest extends BaseServicioTest {

    private static final LocalDate DIA = LocalDate.of(2001, 3, 14);

    @Autowired private ConsultasMovilService consultas;
    @Autowired private VentaRepository ventaRepo;

    private Venta venta(Producto p, LocalDateTime fecha, double cantidad, double precio, Double costo, boolean cancelada) {
        Venta v = new Venta();
        v.setFecha(fecha);
        DetalleVenta d = new DetalleVenta();
        d.setVenta(v);
        d.setProducto(p);
        d.setCantidad(cantidad);
        d.setPrecioVenta(precio);
        d.setCostoUnitario(costo);
        v.getDetalles().add(d);
        v.setTotal(d.getImporte());
        v.setPagoCon(v.getTotal());
        if (cancelada) v.setCancelada(true);
        return ventaRepo.save(v);
    }

    @Test
    void lasVentasSePaginanDeLaMasRecienteALaMasAntiguaConTotalesDelRangoCompleto() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Pagina " + UUID.randomUUID(), 10, 0);
        Venta[] v = new Venta[7];
        for (int i = 0; i < 6; i++) v[i] = venta(p, DIA.atTime(9 + i, 0), 1 + i, 10, null, false);
        v[6] = venta(p, DIA.atTime(20, 0), 5, 10, null, true);                       // cancelada, la más reciente
        Venta empate = venta(p, DIA.atTime(9, 0), 1, 10, null, false);                // misma hora que v[0]: decide el id
        venta(p, DIA.minusDays(1).atTime(23, 59, 59), 9, 10, null, false);           // fuera del rango
        venta(p, DIA.plusDays(1).atStartOfDay(), 9, 10, null, false);                // fuera del rango (fin exclusivo)

        var pag0 = consultas.ventas(DIA, DIA, 0, 3);
        var pag1 = consultas.ventas(DIA, DIA, 1, 3);
        var pag2 = consultas.ventas(DIA, DIA, 2, 3);
        var pag3 = consultas.ventas(DIA, DIA, 3, 3);

        assertThat(pag0.ventas()).extracting(Venta::getId).containsExactly(v[6].getId(), v[5].getId(), v[4].getId());
        assertThat(pag1.ventas()).extracting(Venta::getId).containsExactly(v[3].getId(), v[2].getId(), v[1].getId());
        assertThat(pag2.ventas()).extracting(Venta::getId).containsExactly(Math.max(v[0].getId(), empate.getId()), Math.min(v[0].getId(), empate.getId()));
        assertThat(pag3.ventas()).isEmpty();
        assertThat(pag0.hayMas()).isTrue();
        assertThat(pag1.hayMas()).isTrue();
        assertThat(pag2.hayMas()).isFalse();
        assertThat(pag3.hayMas()).isFalse();
        // Página exacta: 8 ventas en 2 páginas de 4 → la segunda no dice "hay más"
        assertThat(consultas.ventas(DIA, DIA, 1, 4).hayMas()).isFalse();
        assertThat(consultas.ventas(DIA, DIA, 0, 8).hayMas()).isFalse();
        assertThat(consultas.ventas(DIA, DIA, 0, 7).hayMas()).isTrue();

        // Totales del rango completo en cualquier página: 8 ventas, 1 cancelada, importe sin ella
        for (var pag : List.of(pag0, pag1, pag2, pag3)) {
            assertThat(pag.ventasEnRango()).isEqualTo(8);
            assertThat(pag.canceladas()).isEqualTo(1);
            assertThat(pag.importe()).isEqualTo(10.0 * (1 + 2 + 3 + 4 + 5 + 6) + 10.0);
        }
        // Renglones y producto ya cargados
        assertThat(pag0.ventas().get(1).getDetalles()).hasSize(1);
        assertThat(pag0.ventas().get(1).getDetalles().get(0).getProducto().getId()).isEqualTo(p.getId());

        // Rango sin ventas
        var vacia = consultas.ventas(LocalDate.of(1999, 1, 1), LocalDate.of(1999, 1, 2), 0, 50);
        assertThat(vacia.ventas()).isEmpty();
        assertThat(vacia.ventasEnRango()).isZero();
        assertThat(vacia.importe()).isZero();
    }

    @Test
    void laPaginaDeVentasValidaLimitesYPermiso() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> consultas.ventas(DIA, DIA, -1, 10)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> consultas.ventas(DIA, DIA, 0, 0)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> consultas.ventas(DIA, DIA, 0, ConsultasMovilService.MAX_VENTAS_POR_PAGINA + 1))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> consultas.ventas(DIA, DIA.minusDays(1), 0, 10)).isInstanceOf(NegocioException.class);

        iniciarComoCajero("sinhist" + UUID.randomUUID().toString().substring(0, 5), EnumSet.noneOf(Permiso.class));
        assertThatThrownBy(() -> consultas.ventas(DIA, DIA, 0, 10)).isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void laGananciaSeSumaEnSqlSinCanceladasYConElCostoDelProductoSiFalta() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Ganancia " + UUID.randomUUID(), 10, 0);    // costo actual 5
        venta(p, DIA.atTime(10, 0), 2, 10, 7.0, false);                    // 20 − 14 = 6
        venta(p, DIA.atTime(11, 0), 3, 10, null, false);                   // 30 − 15 = 15 (costo del producto)
        venta(p, DIA.atTime(12, 0), 4, 10, 1.0, true);                     // cancelada: no cuenta
        venta(p, DIA.plusDays(1).atTime(12, 0), 4, 10, 1.0, false);        // otro día

        assertThat(consultas.ganancia(DIA, DIA)).isEqualTo(21.0);
        assertThat(consultas.ganancia(DIA, DIA.plusDays(1))).isEqualTo(21.0 + 36.0);
        assertThat(consultas.ganancia(LocalDate.of(1999, 1, 1), LocalDate.of(1999, 1, 1))).isZero();
    }

    @Test
    void elInventarioSeFiltraOrdenaYLimitaEnSql() throws Exception {
        iniciarComoAdmin();
        String marca = "zq" + UUID.randomUUID().toString().substring(0, 6);
        Producto normalB = producto(marca + " Bolillo", 5, 50);
        Producto normalA = producto(marca + " arroz", 5, 50);
        Producto bajo = producto(marca + " Zanahoria", 5, 0);                     // 0 <= mínimo 1
        Producto inactivo = producto(marca + " Inactivo", 5, 50);
        inactivo.setActivo(false);
        productoRepo.save(inactivo);
        Producto sinInventario = producto(marca + " Servicio", 5, 50);
        sinInventario.setUsaInventario(false);
        productoRepo.save(sinInventario);
        Producto conPorciento = producto(marca + " 100%_algodon", 5, 50);

        var r = consultas.inventario(marca.toUpperCase(), 10);
        assertThat(r.total()).isEqualTo(4);
        assertThat(r.productos()).extracting(Producto::getId)
                .containsExactly(bajo.getId(), conPorciento.getId(), normalA.getId(), normalB.getId());

        var limitado = consultas.inventario(marca, 2);
        assertThat(limitado.total()).isEqualTo(4);
        assertThat(limitado.productos()).hasSize(2);

        // % y _ se buscan literalmente (no son comodines)
        assertThat(consultas.inventario(marca + " 100%_", 10).productos()).extracting(Producto::getId)
                .containsExactly(conPorciento.getId());
        assertThat(consultas.inventario(marca + "%", 10).total()).isZero();
        // También por código de barras
        assertThat(consultas.inventario(bajo.getCodigoBarras(), 10).productos()).extracting(Producto::getId)
                .containsExactly(bajo.getId());
        // Sin texto: todos los activos con inventario, con tope
        assertThat(consultas.inventario("", 3).productos()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void escaparLikeNoDejaComodines() {
        assertThat(ConsultasMovilService.escaparLike("a%b_c!d")).isEqualTo("a!%b!_c!!d");
    }
}
