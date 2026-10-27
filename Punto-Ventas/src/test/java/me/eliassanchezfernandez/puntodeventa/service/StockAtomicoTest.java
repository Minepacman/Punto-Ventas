package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.PromocionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * La existencia y el cupo "hasta agotar" se descuentan EN la base de datos con
 * UPDATE condicionados (seguros con varias cajas). Aquí se prueba la semántica
 * en un solo hilo: la carrera real entre dos cajas se prueba en el perfil
 * PostgreSQL (ConcurrenciaPostgresTest).
 */
class StockAtomicoTest extends BaseServicioTest {

    @Autowired private PromocionRepository promocionRepo;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    /** Los UPDATE en bloque no pasan por las entidades en memoria: se vuelve a leer la fila */
    private double existencia(Producto p) {
        em.clear();
        return stockActual(p);
    }

    @Test
    void elDescuentoCondicionadoNoRestaSiNoAlcanza() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Atómico", 10, 5);
        assertThat(productoRepo.descontarSiAlcanza(p.getId(), 6)).isZero();
        assertThat(existencia(p)).isEqualTo(5);
        assertThat(productoRepo.descontarSiAlcanza(p.getId(), 5)).isEqualTo(1);
        assertThat(existencia(p)).isEqualTo(0);
        assertThat(productoRepo.descontarSiAlcanza(p.getId(), 0.001)).isZero();
        assertThat(productoRepo.sumar(p.getId(), 2.5)).isEqualTo(1);
        assertThat(existencia(p)).isEqualTo(2.5);
        // comparar-e-intercambiar: solo ajusta si nadie cambió la fila
        assertThat(productoRepo.fijarSiNoCambio(p.getId(), 99, 3)).isZero();
        assertThat(productoRepo.fijarSiNoCambio(p.getId(), 2.5, 3)).isEqualTo(1);
        assertThat(existencia(p)).isEqualTo(3);
    }

    @Test
    void siOtraCajaVendioAntesLaVentaPideAutorizacionYNoDescuentaNada() throws Exception {
        iniciarComoCajero("cajeroStock", EnumSet.noneOf(Permiso.class));
        Producto p = producto2("Compartido", 10, 3);
        DetalleVenta d = ventaService.crearDetalle(p, 3);           // en pantalla había 3
        productoRepo.descontarSiAlcanza(p.getId(), 2);              // ...pero otra caja vendió 2 mientras tanto

        assertThatThrownBy(() -> ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 100))
                .isInstanceOf(PermisoRequeridoException.class)
                .hasMessageContaining("solo hay 1");
        assertThat(stockActual(p)).isEqualTo(1);                    // no se descontó nada de esta venta

        // Con autorización sí se vende y queda negativo, con bitácora de antes/después reales
        iniciarComoAdmin();
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 3, true)), Venta.FormaPago.EFECTIVO, 100);
        assertThat(stockActual(p)).isEqualTo(-2);
        MovimientoInventario m = productoService.movimientosDe(p.getId()).get(0);
        assertThat(m.getTipo()).isEqualTo(MovimientoInventario.Tipo.VENTA);
        assertThat(m.getExistenciaAntes()).isEqualTo(1);
        assertThat(m.getExistenciaDespues()).isEqualTo(-2);
        assertThat(m.getVentaId()).isEqualTo(v.getId());
    }

    @Test
    void lasCantidadesAGranelQuedanSinResiduosDePuntoFlotante() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Jamón", 100, 10, Producto.TipoVenta.GRANEL, null);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1.1)), Venta.FormaPago.EFECTIVO, 500);
        ventaService.cobrar(List.of(ventaService.crearDetalle(p, 0.2)), Venta.FormaPago.EFECTIVO, 500);
        assertThat(stockActual(p)).isEqualTo(8.7);                  // exacto, no 8.699999…
        productoService.registrarEntrada(p.getId(), 0.3, "Surtidor", 0, null);
        assertThat(stockActual(p)).isEqualTo(9.0);
    }

    @Test
    void elCupoHastaAgotarNoSeRebasaAunqueOtraCajaLoConsumaAntes() throws Exception {
        iniciarComoAdmin();
        Producto p = producto("Promo", 10, 100);
        Promocion promo = new Promocion();
        promo.setProducto(p);
        promo.setTipo(Promocion.Tipo.PORCENTAJE);
        promo.setValor(50);
        promo.setLimitePiezas(5);
        promo.setFechaInicio(java.time.LocalDate.now().minusDays(1));
        promo = promocionService.guardar(promo);

        DetalleVenta d = ventaService.crearDetalle(p, 4);           // 4 piezas con promoción, caben
        assertThat(d.getPiezasPromocion()).isEqualTo(4);
        assertThat(promocionRepo.consumirCupo(promo.getId(), 3)).isEqualTo(1);   // otra caja se llevó 3 antes

        assertThatThrownBy(() -> ventaService.cobrar(List.of(d), Venta.FormaPago.EFECTIVO, 100))
                .isInstanceOf(NegocioException.class).hasMessageContaining("se agotó");
        // (que la venta se revierte completa se comprueba sin transacción envolvente en el perfil PostgreSQL)
        em.clear();
        assertThat(promocionRepo.findById(promo.getId()).orElseThrow().getPiezasUsadasOCero()).isEqualTo(3);

        // Con el ticket recalculado solo caben 2 con promoción y se cobra bien
        DetalleVenta d2 = ventaService.crearDetalle(p, 4);
        assertThat(d2.getPiezasPromocion()).isEqualTo(2);
        Venta v = ventaService.cobrar(List.of(d2), Venta.FormaPago.EFECTIVO, 100);
        assertThat(v.getTotal()).isCloseTo(30, within(0.001));      // 2 a 5 + 2 a 10
        em.clear();
        assertThat(promocionRepo.findById(promo.getId()).orElseThrow().getPiezasUsadasOCero()).isEqualTo(5);
        assertThat(promocionRepo.consumirCupo(promo.getId(), 1)).isZero();
        promocionRepo.devolverCupo(promo.getId(), 2);
        em.clear();
        assertThat(promocionRepo.findById(promo.getId()).orElseThrow().getPiezasUsadasOCero()).isEqualTo(3);
        promocionRepo.devolverCupo(promo.getId(), 99);
        em.clear();
        assertThat(promocionRepo.findById(promo.getId()).orElseThrow().getPiezasUsadasOCero()).isZero();
    }

    /** Producto creado por el admin para un cajero sin permisos (la sesión vuelve al cajero) */
    private Producto producto2(String descripcion, double precio, double stock) throws Exception {
        String usuario = sesionService.getCajeroActual().getUsuario();
        iniciarComoAdmin();
        Producto p = producto(descripcion, precio, stock);
        iniciarComo(usuario, CLAVE_PRUEBA);
        return p;
    }
}
