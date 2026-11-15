package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoComun;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desglose del IVA de precios que YA lo incluyen, renglón por renglón, con la
 * misma regla que la factura (CFDI 4.0, {@code facturacion/ConstructorCfdi}):
 * <pre>
 *   importe = round2(precio × cantidad − descuento)   lo que pagó el cliente
 *   base    = round2(importe / (1 + tasa))
 *   iva     = importe − base                           así base + iva = importe exacto
 * </pre>
 * El IVA así obtenido queda dentro del margen que acepta el SAT (±0.01 respecto a
 * base × tasa) y, sobre todo, la suma de los renglones impresos coincide siempre con
 * el total del ticket. Java puro (sin Spring): lo usan servicios, tickets y pantallas.
 */
public final class CalculoIva {

    private CalculoIva() { }

    /** Base e impuesto de un importe con IVA incluido (ambos a centavos) */
    public record Desglose(double base, double iva) { }

    /** Tasa que aplica a un renglón: fracción (0.16) y si es exento (tasa 0 pero sin "IVA 0 %") */
    public record Tasa(double tasa, boolean exento) {

        public static Tasa de(TipoIva tipo, double tasaTienda) {
            TipoIva t = TipoIva.oTienda(tipo);
            return new Tasa(t.esExento() ? 0.0 : Math.max(0, t.tasa(tasaTienda)), t.esExento());
        }

        /** "IVA 16 %", "IVA 0 %" o "Exento de IVA" */
        public String etiqueta() {
            return exento ? TipoIva.EXENTO.getEtiqueta() : String.format("IVA %s %%", porcentaje());
        }

        /** "16", "0", "8" o "10.5" (sin ceros de sobra) */
        public String porcentaje() {
            double pct = tasa * 100;
            if (Math.abs(pct - Math.rint(pct)) < 0.0001) return String.valueOf(Math.round(pct));
            return String.format("%.2f", pct).replaceAll("0+$", "");
        }
    }

    /** Un grupo de renglones con la misma tasa: lo que suman de base y de impuesto */
    public record Grupo(double tasa, boolean exento, double base, double iva) {
        public Tasa tasaIva() { return new Tasa(tasa, exento); }
        public String etiqueta() { return tasaIva().etiqueta(); }
        public boolean gravado() { return !exento && tasa > 0; }
    }

    public static double redondear(double monto) {
        return Math.round(monto * 100.0) / 100.0;
    }

    /** Desglosa un importe que ya incluye IVA a la tasa dada (0 o exento → todo es base) */
    public static Desglose desglosar(double importeConIva, double tasa) {
        double importe = redondear(importeConIva);
        if (tasa <= 0) return new Desglose(importe, 0);
        double base = redondear(importe / (1 + tasa));
        return new Desglose(base, redondear(importe - base));
    }

    /**
     * Tasa de un producto: la suya o, si dice "usar el de la tienda" (o no tiene), la de la tienda.
     * El producto común (Ctrl+P, fuera del catálogo) siempre va a la tasa de la tienda.
     */
    public static Tasa tasaDe(Producto p, double tasaTienda) {
        if (p == null || ProductoComun.es(p)) return Tasa.de(TipoIva.TIENDA, tasaTienda);
        return Tasa.de(p.getIva(), tasaTienda);
    }

    /**
     * Fija en el renglón la tasa que le toca (snapshot para ticket, corte y factura) y
     * su IVA ya desglosado; devuelve base e impuesto. Se llama al cobrar.
     */
    public static Desglose aplicar(DetalleVenta d, double tasaTienda) {
        Tasa t = tasaDe(d.getProducto(), tasaTienda);
        Desglose g = desglosar(d.getImporte(), t.tasa());
        d.setTasaIva(t.tasa());
        d.setIvaExento(t.exento() ? Boolean.TRUE : null);
        d.setIva(g.iva());
        return g;
    }

    /** Tasa guardada en el renglón; en ventas anteriores a este campo, la de respaldo (la de la venta) */
    public static Tasa tasaGuardada(DetalleVenta d, double tasaRespaldo) {
        boolean exento = Boolean.TRUE.equals(d.getIvaExento());
        double tasa = d.getTasaIva() != null ? d.getTasaIva() : Math.max(0, tasaRespaldo);
        return new Tasa(exento ? 0.0 : tasa, exento);
    }

    /** Base e IVA de un renglón ya cobrado (con lo guardado; si no hay IVA guardado, se desglosa) */
    public static Desglose desgloseGuardado(DetalleVenta d, double tasaRespaldo) {
        double importe = d.getImporte();
        if (d.getIva() != null) return new Desglose(redondear(importe - d.getIva()), redondear(d.getIva()));
        return desglosar(importe, tasaGuardada(d, tasaRespaldo).tasa());
    }

    /**
     * Agrupa los renglones de una venta por tasa (16 %, 0 %, exento) sumando base e
     * IVA de cada grupo, en orden: gravados de mayor a menor tasa, luego 0 %, luego exentos.
     * @param tasaRespaldo tasa de la venta (o de la tienda) para renglones sin tasa guardada
     */
    public static List<Grupo> agrupar(Collection<DetalleVenta> detalles, double tasaRespaldo) {
        Map<Tasa, double[]> sumas = new LinkedHashMap<>();
        for (DetalleVenta d : detalles) {
            Tasa t = tasaGuardada(d, tasaRespaldo);
            Desglose g = desgloseGuardado(d, tasaRespaldo);
            double[] s = sumas.computeIfAbsent(t, k -> new double[2]);
            s[0] += g.base();
            s[1] += g.iva();
        }
        List<Grupo> grupos = new ArrayList<>();
        sumas.forEach((t, s) -> grupos.add(new Grupo(t.tasa(), t.exento(), redondear(s[0]), redondear(s[1]))));
        ordenar(grupos);
        return grupos;
    }

    /** Gravados primero (mayor tasa antes), luego tasa 0 %, al final exentos */
    public static void ordenar(List<Grupo> grupos) {
        grupos.sort((a, b) -> {
            if (a.exento() != b.exento()) return a.exento() ? 1 : -1;
            return Double.compare(b.tasa(), a.tasa());
        });
    }

    /** true si hay más de una tasa en la venta (el ticket y el corte entonces desglosan por tasa) */
    public static boolean esMixto(List<Grupo> grupos) {
        return grupos.size() > 1;
    }
}
