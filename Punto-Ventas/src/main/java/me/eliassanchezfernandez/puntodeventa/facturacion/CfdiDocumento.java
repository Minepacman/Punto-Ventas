package me.eliassanchezfernandez.puntodeventa.facturacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Comprobante (CFDI 4.0 de ingreso) listo para enviarse al PAC, independiente
 * del proveedor. Lo arma {@link ConstructorCfdi} con BigDecimal y cada
 * proveedor ({@link ProveedorCfdi}) lo traduce a su propio JSON.
 *
 * Importes: ValorUnitario hasta 6 decimales; Importe, Descuento, Base,
 * Importe del impuesto y totales a 2 decimales (HALF_UP).
 */
public record CfdiDocumento(
        String serie,
        String folio,
        String lugarExpedicion,
        /** c_FormaPago: 01 efectivo, 03 transferencia, 04 tarjeta de crédito, 28 débito, 08 vales */
        String formaPago,
        /** c_MetodoPago: siempre PUE (pago en una sola exhibición) */
        String metodoPago,
        Receptor receptor,
        List<Concepto> conceptos,
        /** Solo en la factura global (público en general); null en las demás */
        InformacionGlobal informacionGlobal,
        /** Referencia interna (folio de la venta o del periodo): sirve para no timbrar dos veces */
        String numeroOrden,
        String observaciones) {

    public static final String MONEDA = "MXN";
    public static final String EXPORTACION_NO_APLICA = "01";
    public static final String METODO_PUE = "PUE";
    public static final String TIPO_INGRESO = "I";

    public record Receptor(String rfc, String nombre, String regimenFiscal, String codigoPostal, String usoCfdi) { }

    public record Impuesto(String nombre, BigDecimal base, BigDecimal tasa, BigDecimal importe) {
        public static final String IVA = "IVA";
    }

    public record Concepto(
            String claveProdServ,
            String noIdentificacion,
            String descripcion,
            String claveUnidad,
            String unidad,
            BigDecimal cantidad,
            BigDecimal valorUnitario,
            /** cantidad × valorUnitario (antes de descuento, sin IVA) */
            BigDecimal importe,
            /** Descuento sin IVA; null si no hay */
            BigDecimal descuento,
            String objetoImp,
            List<Impuesto> impuestos,
            /** importe − descuento + impuestos (lo que paga el cliente por el renglón) */
            BigDecimal total) {

        public BigDecimal descuentoONada() {
            return descuento == null ? BigDecimal.ZERO : descuento;
        }

        public BigDecimal totalImpuestos() {
            return impuestos == null ? BigDecimal.ZERO
                    : impuestos.stream().map(Impuesto::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal base() {
            return importe.subtract(descuentoONada());
        }
    }

    public record InformacionGlobal(String periodicidad, String meses, String anio) { }

    public BigDecimal subtotal() {
        return conceptos.stream().map(Concepto::importe).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal descuento() {
        return conceptos.stream().map(Concepto::descuentoONada).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal totalImpuestos() {
        return conceptos.stream().map(Concepto::totalImpuestos).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal total() {
        return conceptos.stream().map(Concepto::total).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    public boolean esGlobal() {
        return informacionGlobal != null;
    }
}
