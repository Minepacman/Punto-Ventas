package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Vista previa en texto del ticket (Configuración → Ticket). Solo arma el
 * texto con los datos configurados y una venta de ejemplo para un papel de
 * 58 mm (32 columnas) u 80 mm (48 columnas); la impresión es de otra etapa.
 * Recibe la configuración (aunque no esté guardada) para actualizarse mientras
 * se escribe.
 */
@Service
public class TicketService {

    /** Columnas de texto según el ancho del papel */
    public static int columnas(int anchoMm) {
        return anchoMm == 58 ? 32 : 48;
    }

    public String vistaPrevia(Configuracion c, String folio) {
        int cols = columnas(c.getTicketAnchoMm());
        String raya = "-".repeat(cols);
        List<String> l = new ArrayList<>();

        l.addAll(centrado(c.getNombreTienda(), cols));
        if (c.getDireccion() != null) l.addAll(centrado(c.getDireccion(), cols));
        if (c.getTicketEncabezado() != null) l.addAll(centrado(c.getTicketEncabezado(), cols));
        if (c.getTicketRfc() != null) l.add(centrar("RFC: " + c.getTicketRfc(), cols));
        if (c.getTicketTelefono() != null) l.add(centrar("Tel: " + c.getTicketTelefono(), cols));
        l.add(raya);
        l.add(dosColumnas("Folio: " + folio,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), cols));
        l.add("Cajero: Ejemplo");
        l.add(raya);

        double total = 0;
        String[][] renglones = {{"2", "Refresco cola 600 ml", "36.00"}, {"1", "Pan de caja", "42.50"},
                                {"1.250", "Frijol a granel kg", "45.00"}};
        for (String[] r : renglones) {
            double importe = Double.parseDouble(r[2]);
            total += importe;
            l.add(dosColumnas(r[0] + " " + r[1], Moneda.formato(importe), cols));
        }
        l.add(raya);
        double subtotal = c.subtotalDe(total);
        l.add(dosColumnas("Subtotal", Moneda.formato(subtotal), cols));
        l.add(dosColumnas(String.format("IVA %.0f%%", c.getTasaIva() * 100), Moneda.formato(total - subtotal), cols));
        l.add(dosColumnas("TOTAL", Moneda.formato(total) + " " + c.getMonedaCodigo(), cols));
        l.add(dosColumnas("Efectivo", Moneda.formato(150), cols));
        l.add(dosColumnas("Cambio", Moneda.formato(150 - total), cols));
        l.add(raya);
        if (c.getTicketPie() != null) l.addAll(centrado(c.getTicketPie(), cols));
        l.addAll(centrado(c.getTicketAgradecimiento() != null ? c.getTicketAgradecimiento() : "¡Gracias por su compra!", cols));
        return String.join("\n", l);
    }

    // ── Documentos para imprimir (ESC/POS o impresora normal) ─────────────
    // Sin logotipo: la impresora térmica no lo lleva; el texto es el mismo de la vista previa.

    /** Ticket de una venta ya cobrada. {@code reimpresion} lo marca para que no se confunda con el original. */
    public DocumentoImpresion documentoVenta(Venta v, Configuracion c, boolean reimpresion) {
        int cols = columnas(c.getTicketAnchoMm());
        String raya = "-".repeat(cols);
        DocumentoImpresion d = new DocumentoImpresion();

        d.titulo(c.getNombreTienda());
        if (c.getDireccion() != null) ajustar(c.getDireccion(), cols).forEach(d::centrada);
        if (c.getTicketEncabezado() != null) ajustar(c.getTicketEncabezado(), cols).forEach(d::centrada);
        if (c.getTicketRfc() != null) d.centrada("RFC: " + c.getTicketRfc());
        if (c.getTicketTelefono() != null) d.centrada("Tel: " + c.getTicketTelefono());
        d.linea(raya);
        d.linea(dosColumnas("Folio: " + v.getFolioMostrado(),
                v.getFecha().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), cols));
        d.linea("Cajero: " + (v.getCajeroNombre() != null ? v.getCajeroNombre() : "—"));
        if (reimpresion) d.agregar("*** REIMPRESION ***", DocumentoImpresion.Alineacion.CENTRO, true, false);
        if (v.isCancelada()) d.agregar("*** VENTA CANCELADA ***", DocumentoImpresion.Alineacion.CENTRO, true, false);
        d.linea(raya);

        for (DetalleVenta r : v.getDetalles()) {
            String descripcion = r.getProducto() != null ? r.getProducto().getDescripcion() : "(producto)";
            double importeBruto = r.getPrecioVenta() * r.getCantidad();
            d.linea(dosColumnas(cantidad(r.getCantidad()) + " " + descripcion, Moneda.formato(importeBruto), cols));
            if (r.getDescuento() > 0.004) {
                String motivo = switch (r.getTipoDescuento() != null ? r.getTipoDescuento() : DetalleVenta.TipoDescuento.NINGUNO) {
                    case PROMOCION -> r.getPromocionNombre() != null ? r.getPromocionNombre() : "Promoción";
                    case MAYOREO -> "Mayoreo";
                    case MANUAL -> "Descuento";
                    default -> "Descuento";
                };
                d.linea(dosColumnas("  " + motivo, "-" + Moneda.formato(r.getDescuento()), cols));
            }
        }
        d.linea(raya);
        d.linea(dosColumnas("Subtotal", Moneda.formato(v.getSubtotalODefault()), cols));
        d.linea(dosColumnas(String.format("IVA %.0f%%", (v.getTasaIva() != null ? v.getTasaIva() : c.getTasaIva()) * 100),
                Moneda.formato(v.getIvaODefault()), cols));
        d.agregar(dosColumnas("TOTAL", Moneda.formato(v.getTotal()) + " " + c.getMonedaCodigo(), cols),
                DocumentoImpresion.Alineacion.IZQUIERDA, true, false);
        for (PagoVenta p : v.getPagos()) {
            d.linea(dosColumnas(p.getFormaPago().getEtiqueta(), Moneda.formato(p.getMonto()), cols));
            if (p.tieneDatosTarjeta()) ajustar("  " + p.resumenTarjeta(), cols).forEach(d::linea);
        }
        if (v.getPagos().isEmpty() && v.getFormaPago() != null) {
            d.linea(dosColumnas(v.getFormaPago().getEtiqueta(), Moneda.formato(v.getPagoCon()), cols));
        }
        d.linea(dosColumnas("Cambio", Moneda.formato(v.getCambio()), cols));
        d.linea(raya);
        if (c.getTicketPie() != null) ajustar(c.getTicketPie(), cols).forEach(d::centrada);
        ajustar(c.getTicketAgradecimiento() != null ? c.getTicketAgradecimiento() : "¡Gracias por su compra!", cols)
                .forEach(d::centrada);
        return d;
    }

    /**
     * Reporte del corte de caja del día. La ganancia solo aparece si
     * {@code r.gananciaVisible}. {@code corte} (el último guardado hoy) es opcional.
     */
    public DocumentoImpresion documentoCorte(VentaService.ResumenCorte r, double fondo, Corte corte,
                                             Configuracion c, String impresoPor) {
        int cols = columnas(c.getTicketAnchoMm());
        String raya = "-".repeat(cols);
        DocumentoImpresion d = new DocumentoImpresion();
        d.titulo(c.getNombreTienda());
        d.agregar("CORTE DE CAJA", DocumentoImpresion.Alineacion.CENTRO, true, false);
        d.linea(raya);
        d.linea(dosColumnas("Fecha", LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), cols));
        d.linea("Impreso por: " + (impresoPor != null ? impresoPor : "—"));
        d.linea(raya);
        d.linea(dosColumnas("Fondo inicial", Moneda.formato(fondo), cols));
        d.linea(dosColumnas("Ventas en efectivo", Moneda.formato(r.efectivo), cols));
        d.agregar(dosColumnas("Efectivo esperado", Moneda.formato(fondo + r.efectivo), cols),
                DocumentoImpresion.Alineacion.IZQUIERDA, true, false);
        d.linea(raya);
        d.linea(dosColumnas("Efectivo", Moneda.formato(r.efectivo), cols));
        d.linea(dosColumnas("Tarjeta", Moneda.formato(r.tarjeta), cols));
        if (r.transferencia > 0) d.linea(dosColumnas("Transferencia", Moneda.formato(r.transferencia), cols));
        if (r.vales > 0) d.linea(dosColumnas("Vales", Moneda.formato(r.vales), cols));
        for (VentaService.TarjetaTerminal t : r.tarjetaPorTerminal) {
            d.linea(dosColumnas("  " + t.terminal() + " (" + t.pagos() + ")", Moneda.formato(t.monto()), cols));
        }
        d.linea(raya);
        d.linea(dosColumnas("Subtotal", Moneda.formato(r.subtotal), cols));
        d.linea(dosColumnas("IVA", Moneda.formato(r.iva), cols));
        d.agregar(dosColumnas("VENTAS (" + r.numVentas + ")", Moneda.formato(r.ventasTotales), cols),
                DocumentoImpresion.Alineacion.IZQUIERDA, true, false);
        d.linea(dosColumnas("Canceladas (" + r.numCanceladas + ")", Moneda.formato(r.totalCanceladas), cols));
        if (r.gananciaVisible) d.linea(dosColumnas("Ganancia", Moneda.formato(r.ganancia), cols));
        if (!r.porDepartamento.isEmpty()) {
            d.linea(raya);
            d.negrita("Por departamento");
            r.porDepartamento.forEach((n, m) -> d.linea(dosColumnas(n, Moneda.formato(m), cols)));
        }
        if (!r.vouchers.isEmpty()) {
            d.linea(raya);
            d.negrita("Vouchers de tarjeta");
            for (VentaService.VoucherTarjeta v : r.vouchers) {
                d.linea(dosColumnas("#" + v.folio() + " " + v.fecha().format(DateTimeFormatter.ofPattern("HH:mm")),
                        Moneda.formato(v.monto()), cols));
                String det = (v.terminal() != null ? v.terminal() : "s/terminal")
                        + (v.ultimos4() != null ? " ****" + v.ultimos4() : "")
                        + (v.autorizacion() != null ? " aut. " + v.autorizacion() : "");
                ajustar("  " + det, cols).forEach(d::linea);
            }
        }
        if (corte != null) {
            d.linea(raya);
            d.negrita("Último corte guardado");
            d.linea(dosColumnas("Esperado", Moneda.formato(corte.getEfectivoEsperado()), cols));
            d.linea(dosColumnas("Contado", Moneda.formato(corte.getEfectivoContado()), cols));
            d.linea(dosColumnas(corte.hayFaltante() ? "FALTANTE" : corte.haySobrante() ? "SOBRANTE" : "Diferencia",
                    Moneda.formato(corte.getDiferencia()), cols));
        }
        d.linea(raya);
        return d;
    }

    /** "2", "1.25" (sin ceros de sobra) */
    static String cantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }

    // ── Helpers de formato ────────────────────────────────────────────────

    /** Texto envuelto a las columnas del papel y centrado línea por línea */
    static List<String> centrado(String texto, int cols) {
        List<String> r = new ArrayList<>();
        for (String linea : ajustar(texto, cols)) r.add(centrar(linea, cols));
        return r;
    }

    static String centrar(String texto, int cols) {
        if (texto.length() >= cols) return texto.substring(0, cols);
        return " ".repeat((cols - texto.length()) / 2) + texto;
    }

    /** Izquierda y derecha en una línea de ancho fijo (recorta la izquierda si no cabe) */
    static String dosColumnas(String izq, String der, int cols) {
        int libre = cols - der.length() - 1;
        if (izq.length() > libre) izq = izq.substring(0, Math.max(0, libre));
        return izq + " ".repeat(Math.max(1, cols - izq.length() - der.length())) + der;
    }

    /** Corte por palabras respetando saltos de línea; las palabras muy largas se parten */
    static List<String> ajustar(String texto, int cols) {
        List<String> r = new ArrayList<>();
        for (String parrafo : texto.split("\\R")) {
            StringBuilder actual = new StringBuilder();
            for (String palabra : parrafo.trim().split("\\s+")) {
                while (palabra.length() > cols) {
                    if (actual.length() > 0) { r.add(actual.toString()); actual.setLength(0); }
                    r.add(palabra.substring(0, cols));
                    palabra = palabra.substring(cols);
                }
                if (actual.length() > 0 && actual.length() + 1 + palabra.length() > cols) {
                    r.add(actual.toString());
                    actual.setLength(0);
                }
                if (actual.length() > 0) actual.append(' ');
                actual.append(palabra);
            }
            if (actual.length() > 0 || r.isEmpty()) r.add(actual.toString());
        }
        return r;
    }
}
