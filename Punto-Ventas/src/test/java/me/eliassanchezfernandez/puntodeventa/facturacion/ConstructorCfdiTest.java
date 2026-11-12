package me.eliassanchezfernandez.puntodeventa.facturacion;

import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Concepto;
import me.eliassanchezfernandez.puntodeventa.facturacion.ConstructorCfdi.Linea;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Importes del CFDI a partir de precios CON IVA: base e IVA por renglón,
 * descuentos como atributo Descuento, redondeo consistente con muchos
 * renglones, forma de pago y factura global.
 */
class ConstructorCfdiTest {

    private static final BigDecimal TASA = new BigDecimal("0.16");

    private static Linea linea(String desc, String cantidad, String bruto, String descuento) {
        return new Linea("50000000", "C1", desc, "H87", "Pieza", "02", new BigDecimal(cantidad), new BigDecimal(bruto), new BigDecimal(descuento));
    }

    @Test
    void desglosaElIvaDeUnPrecioQueLoIncluye() {
        List<Concepto> c = ConstructorCfdi.conceptos(List.of(linea("Refresco", "2", "36.00", "0")), TASA, new BigDecimal("36.00"));
        Concepto r = c.get(0);
        assertThat(r.importe()).isEqualByComparingTo("31.03");          // 36 / 1.16
        assertThat(r.valorUnitario()).isEqualByComparingTo("15.515000");
        assertThat(r.descuento()).isNull();
        assertThat(r.impuestos()).hasSize(1);
        assertThat(r.impuestos().get(0).base()).isEqualByComparingTo("31.03");
        assertThat(r.impuestos().get(0).tasa()).isEqualByComparingTo("0.160000");
        assertThat(r.impuestos().get(0).importe()).isEqualByComparingTo("4.97");
        assertThat(r.total()).isEqualByComparingTo("36.00");
        // IVA dentro del margen del SAT: |iva − base×tasa| < 0.01
        assertThat(r.impuestos().get(0).importe().subtract(r.base().multiply(TASA)).abs()).isLessThan(new BigDecimal("0.01"));
    }

    @Test
    void elDescuentoDelTicketVaComoAtributoDescuentoSinIva() {
        // 3 piezas a $25 = $75 con $15 de promoción → cliente paga $60
        List<Concepto> c = ConstructorCfdi.conceptos(List.of(linea("Jabón", "3", "75.00", "15.00")), TASA, new BigDecimal("60.00"));
        Concepto r = c.get(0);
        assertThat(r.importe()).isEqualByComparingTo("64.66");           // 75 / 1.16
        assertThat(r.descuento()).isEqualByComparingTo("12.94");         // 64.66 − 51.72 (60 / 1.16)
        assertThat(r.base()).isEqualByComparingTo("51.72");
        assertThat(r.impuestos().get(0).base()).isEqualByComparingTo("51.72");
        assertThat(r.impuestos().get(0).importe()).isEqualByComparingTo("8.28");
        assertThat(r.total()).isEqualByComparingTo("60.00");
        assertThat(r.importe().subtract(r.descuento()).add(r.totalImpuestos())).isEqualByComparingTo(r.total());
        // Mayoreo: el precio ya viene bajado, sin descuento
        Concepto m = ConstructorCfdi.conceptos(List.of(linea("Arroz mayoreo", "10", "200.00", "0")), TASA, null).get(0);
        assertThat(m.descuento()).isNull();
        assertThat(m.total()).isEqualByComparingTo("200.00");
    }

    @Test
    void granelConTresDecimalesYAjusteDeCentavosAlTotalDeLaVenta() {
        // 0.333 kg × 45.90 = 15.2847 y 0.667 × 45.90 = 30.6153: suman 45.90 pero redondeados 15.28 + 30.62 = 45.90 (ok)
        // caso con desfase: 0.335 × 45.90 = 15.3765 (15.38) y 0.665 × 45.90 = 30.5235 (30.52) = 45.90; forzamos 45.89 esperado
        List<Linea> lineas = List.of(
                new Linea("50221000", "A", "Arroz", "KGM", "Kilogramo", "02", new BigDecimal("0.335"), new BigDecimal("15.3765"), BigDecimal.ZERO),
                new Linea("50221000", "A", "Arroz", "KGM", "Kilogramo", "02", new BigDecimal("0.665"), new BigDecimal("30.5235"), BigDecimal.ZERO));
        List<Concepto> c = ConstructorCfdi.conceptos(lineas, TASA, new BigDecimal("45.89"));
        BigDecimal suma = c.stream().map(Concepto::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(suma).isEqualByComparingTo("45.89");
        assertThat(c.get(1).total()).isEqualByComparingTo("30.51");      // el centavo se ajustó en el mayor
        assertThat(c.get(0).cantidad()).isEqualByComparingTo("0.335");
        assertThat(c.get(0).valorUnitario().scale()).isEqualTo(6);

        // Una diferencia mayor a 1 centavo por renglón no se ajusta en silencio
        assertThatThrownBy(() -> ConstructorCfdi.conceptos(lineas, TASA, new BigDecimal("40.00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no se puede facturar");
    }

    @Test
    void conMuchosRenglonesLasSumasCuadranYElIvaEstaEnMargen() {
        Random azar = new Random(7);
        for (int ronda = 0; ronda < 200; ronda++) {
            List<Linea> lineas = new ArrayList<>();
            BigDecimal totalVenta = BigDecimal.ZERO;
            int n = 1 + azar.nextInt(40);
            for (int i = 0; i < n; i++) {
                boolean granel = azar.nextInt(4) == 0;
                BigDecimal cantidad = granel ? BigDecimal.valueOf(1 + azar.nextInt(5000), 3) : BigDecimal.valueOf(1 + azar.nextInt(12));
                BigDecimal precio = BigDecimal.valueOf(50 + azar.nextInt(20000), 2);
                BigDecimal bruto = precio.multiply(cantidad);
                BigDecimal descuento = azar.nextInt(3) == 0 ? bruto.multiply(new BigDecimal("0.1")).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                lineas.add(new Linea("50000000", "C" + i, "Prod " + i, granel ? "KGM" : "H87", "u", "02", cantidad, bruto, descuento));
                totalVenta = totalVenta.add(bruto.subtract(descuento));
            }
            totalVenta = totalVenta.setScale(2, RoundingMode.HALF_UP);          // como lo guarda VentaService
            List<Concepto> c = ConstructorCfdi.conceptos(lineas, TASA, totalVenta);
            CfdiDocumento d = new CfdiDocumento("A", "1", "78240", "01", "PUE",
                    new CfdiDocumento.Receptor("XAXX010101000", "P", "616", "78240", "S01"), c, null, "x", null);

            assertThat(d.total()).as("ronda " + ronda).isEqualByComparingTo(totalVenta);
            assertThat(d.subtotal().subtract(d.descuento()).add(d.totalImpuestos())).isEqualByComparingTo(d.total());
            for (Concepto r : c) {
                assertThat(r.importe().scale()).isLessThanOrEqualTo(2);
                assertThat(r.base()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(r.descuentoONada()).isLessThanOrEqualTo(r.importe());
                BigDecimal iva = r.impuestos().get(0).importe();
                BigDecimal exacto = r.base().multiply(TASA);
                // Regla del SAT: el importe del impuesto está entre el piso y el techo de base×tasa a 2 decimales
                assertThat(iva).isBetween(exacto.setScale(2, RoundingMode.FLOOR), exacto.setScale(2, RoundingMode.CEILING));
                // Importe dentro de la tolerancia de cantidad × valor unitario
                BigDecimal calculado = r.cantidad().multiply(r.valorUnitario());
                assertThat(r.importe().subtract(calculado).abs()).isLessThan(new BigDecimal("0.01"));
                assertThat(r.base().add(iva)).isEqualByComparingTo(r.total());
            }
        }
    }

    @Test
    void productoNoObjetoDeImpuestoNoDesglosaIva() {
        Linea l = new Linea("51000000", "M", "Medicina", "H87", "Pieza", "01", BigDecimal.ONE, new BigDecimal("100.00"), BigDecimal.ZERO);
        Concepto r = ConstructorCfdi.conceptos(List.of(l), TASA, null).get(0);
        assertThat(r.impuestos()).isEmpty();
        assertThat(r.importe()).isEqualByComparingTo("100.00");
        assertThat(r.total()).isEqualByComparingTo("100.00");
        Linea cero = new Linea("50000000", "T", "Tortilla", "KGM", "Kilogramo", "02", BigDecimal.ONE, new BigDecimal("20.00"), BigDecimal.ZERO);
        Concepto t = ConstructorCfdi.conceptos(List.of(cero), BigDecimal.ZERO, null).get(0);
        assertThat(t.impuestos().get(0).importe()).isEqualByComparingTo("0");
        assertThat(t.base()).isEqualByComparingTo("20.00");
    }

    @Test
    void formaDePagoDelSatSegunElPagoMayor() {
        Venta v = new Venta();
        v.setFormaPago(Venta.FormaPago.EFECTIVO);
        assertThat(ConstructorCfdi.formaPagoSat(v)).isEqualTo("01");
        v.getPagos().add(new PagoVenta(Venta.FormaPago.EFECTIVO, 40));
        v.getPagos().add(new PagoVenta(Venta.FormaPago.TARJETA, 60));
        assertThat(ConstructorCfdi.formaPagoSat(v)).isEqualTo("04");       // pago dividido: el mayor
        v.getPagos().add(new PagoVenta(Venta.FormaPago.TRANSFERENCIA, 100));
        assertThat(ConstructorCfdi.formaPagoSat(v)).isEqualTo("03");
        assertThat(ConstructorCfdi.formaPagoSat(Venta.FormaPago.VALES)).isEqualTo("08");
        assertThat(ConstructorCfdi.formaPagoSat((Venta.FormaPago) null)).isEqualTo("01");
    }

    @Test
    void armaElComprobanteDeUnaVentaConLasClavesDelProducto() {
        Departamento limpieza = new Departamento();
        limpieza.setNombre("Limpieza");
        limpieza.setClaveProdServ("47131800");
        Producto cloro = new Producto();
        cloro.setCodigoBarras("750100");
        cloro.setDescripcion("Cloro 1 L");
        cloro.setPrecioVenta(25);
        cloro.setDepartamento(limpieza);
        cloro.setUnidad(new Unidad("Litro", "L", true));
        Producto pan = new Producto();
        pan.setCodigoBarras("750200");
        pan.setDescripcion("Pan");
        pan.setPrecioVenta(10);
        pan.setClaveProdServ("50181900");
        pan.setClaveUnidad("XPK");

        Venta v = new Venta();
        v.setId(42L);
        v.setFolio("A-42");
        v.setTotal(45.00);
        v.setTasaIva(0.16);
        v.setFormaPago(Venta.FormaPago.TARJETA);
        DetalleVenta d1 = new DetalleVenta(); d1.setProducto(cloro); d1.setCantidad(1); d1.setPrecioVenta(25); d1.setDescuento(0);
        DetalleVenta d2 = new DetalleVenta(); d2.setProducto(pan); d2.setCantidad(2); d2.setPrecioVenta(10); d2.setDescuento(0);
        v.getDetalles().add(d1);
        v.getDetalles().add(d2);

        ConfiguracionFiscal cfg = new ConfiguracionFiscal();
        cfg.setEmisorCodigoPostal("78240");
        cfg.setSerie("FAC");
        ClienteFiscal cli = new ClienteFiscal();
        cli.setRfc("GODE561231GR8"); cli.setNombre("JUAN PEREZ"); cli.setRegimenFiscal("612"); cli.setCodigoPostal("06600"); cli.setUsoCfdi("G01");

        CfdiDocumento doc = ConstructorCfdi.paraVenta(v, cfg, cli, "G03", null, "12", TASA);
        assertThat(doc.serie()).isEqualTo("FAC");
        assertThat(doc.folio()).isEqualTo("12");
        assertThat(doc.formaPago()).isEqualTo("04");
        assertThat(doc.metodoPago()).isEqualTo("PUE");
        assertThat(doc.lugarExpedicion()).isEqualTo("78240");
        assertThat(doc.numeroOrden()).isEqualTo("V-42");
        assertThat(doc.receptor().usoCfdi()).isEqualTo("G03");
        assertThat(doc.receptor().codigoPostal()).isEqualTo("06600");
        assertThat(doc.conceptos()).hasSize(2);
        assertThat(doc.conceptos().get(0).claveProdServ()).isEqualTo("47131800");
        assertThat(doc.conceptos().get(0).claveUnidad()).isEqualTo("LTR");
        assertThat(doc.conceptos().get(0).unidad()).isEqualTo("Litro");
        assertThat(doc.conceptos().get(0).noIdentificacion()).isEqualTo("750100");
        assertThat(doc.conceptos().get(1).claveProdServ()).isEqualTo("50181900");
        assertThat(doc.conceptos().get(1).claveUnidad()).isEqualTo("XPK");
        assertThat(doc.conceptos().get(1).cantidad()).isEqualByComparingTo("2");
        assertThat(doc.total()).isEqualByComparingTo("45.00");
        assertThat(doc.esGlobal()).isFalse();

        // Forma de pago elegida por el usuario (débito) y receptor genérico: CP del emisor
        ClienteFiscal gen = new ClienteFiscal();
        gen.setRfc(Rfc.PUBLICO_EN_GENERAL); gen.setNombre("PUBLICO EN GENERAL"); gen.setRegimenFiscal("616"); gen.setCodigoPostal("00000"); gen.setUsoCfdi("S01");
        CfdiDocumento doc2 = ConstructorCfdi.paraVenta(v, cfg, gen, null, "28", "13", TASA);
        assertThat(doc2.formaPago()).isEqualTo("28");
        assertThat(doc2.receptor().codigoPostal()).isEqualTo("78240");
        assertThat(doc2.receptor().usoCfdi()).isEqualTo("S01");
    }

    @Test
    void facturaGlobalUnConceptoPorTicket() {
        Venta a = new Venta(); a.setId(1L); a.setFolio("A-1"); a.setTotal(116.00); a.setTasaIva(0.16);
        Venta b = new Venta(); b.setId(2L); b.setTotal(58.00); b.setTasaIva(0.16);
        Venta c = new Venta(); c.setId(3L); c.setTotal(10.00); c.setTasaIva(0.0);      // venta con IVA 0 %
        ConfiguracionFiscal cfg = new ConfiguracionFiscal();
        cfg.setEmisorCodigoPostal("78240");

        CfdiDocumento g = ConstructorCfdi.global(List.of(a, b, c), cfg, "01", "10", "2026", "5", TASA);
        assertThat(g.esGlobal()).isTrue();
        assertThat(g.informacionGlobal().periodicidad()).isEqualTo("01");
        assertThat(g.informacionGlobal().meses()).isEqualTo("10");
        assertThat(g.informacionGlobal().anio()).isEqualTo("2026");
        assertThat(g.receptor().rfc()).isEqualTo("XAXX010101000");
        assertThat(g.receptor().nombre()).isEqualTo("PUBLICO EN GENERAL");
        assertThat(g.receptor().regimenFiscal()).isEqualTo("616");
        assertThat(g.receptor().usoCfdi()).isEqualTo("S01");
        assertThat(g.receptor().codigoPostal()).isEqualTo("78240");
        assertThat(g.formaPago()).isEqualTo("01");
        assertThat(g.conceptos()).hasSize(3);
        Concepto c1 = g.conceptos().get(0);
        assertThat(c1.claveProdServ()).isEqualTo("01010101");
        assertThat(c1.claveUnidad()).isEqualTo("ACT");
        assertThat(c1.cantidad()).isEqualByComparingTo("1");
        assertThat(c1.noIdentificacion()).isEqualTo("A-1");
        assertThat(c1.importe()).isEqualByComparingTo("100.00");
        assertThat(c1.impuestos().get(0).importe()).isEqualByComparingTo("16.00");
        assertThat(c1.total()).isEqualByComparingTo("116.00");
        assertThat(g.conceptos().get(1).noIdentificacion()).isEqualTo("2");          // sin folio: el id
        assertThat(g.conceptos().get(2).impuestos().get(0).importe()).isEqualByComparingTo("0");
        assertThat(g.total()).isEqualByComparingTo("184.00");

        assertThat(ConstructorCfdi.mesesDe(LocalDate.of(2026, 3, 7))).isEqualTo("03");
        assertThat(ConstructorCfdi.anioDe(LocalDate.of(2026, 3, 7))).isEqualTo("2026");
        assertThatThrownBy(() -> ConstructorCfdi.global(List.of(), cfg, "01", "10", "2026", "5", TASA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
