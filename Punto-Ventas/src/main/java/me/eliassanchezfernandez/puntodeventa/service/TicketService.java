package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompraLinea;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.ProductoComun;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.util.CalculoIva;
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

    /** Con varias cajas el ticket y el corte dicen de qué caja son; en una sola caja no hace falta */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IdentidadCaja identidadCaja;

    private boolean multiCaja() {
        return identidadCaja != null && identidadCaja.esMultiCaja();
    }

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
        if (multiCaja() || (v.getCaja() != null && v.getCaja() > 1)) d.linea(IdentidadCaja.etiqueta(v.getCaja()));
        if (reimpresion) d.agregar("*** REIMPRESION ***", DocumentoImpresion.Alineacion.CENTRO, true, false);
        if (v.isCancelada()) d.agregar("*** VENTA CANCELADA ***", DocumentoImpresion.Alineacion.CENTRO, true, false);
        d.linea(raya);

        for (DetalleVenta r : v.getDetalles()) {
            String descripcion = r.getDescripcionMostrada();   // en un producto común, el nombre tecleado
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
        lineasIva(v, c, cols).forEach(d::linea);
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
     * Renglones de IVA del ticket. Con una sola tasa en la venta, una línea como siempre
     * ("IVA 16%", "IVA 0%" o "Exento de IVA"); con tasas mezcladas (básicos al 0 % junto
     * con refrescos al 16 %), el desglose por tasa: lo gravado y su IVA, lo de tasa 0 y lo exento.
     */
    static List<String> lineasIva(Venta v, Configuracion c, int cols) {
        double tasaRespaldo = v.getTasaIva() != null ? v.getTasaIva() : c.getTasaIva();
        List<CalculoIva.Grupo> grupos = CalculoIva.agrupar(v.getDetalles(), tasaRespaldo);
        List<String> l = new ArrayList<>();
        if (grupos.isEmpty()) {
            l.add(dosColumnas(new CalculoIva.Tasa(tasaRespaldo, false).etiqueta().replace(" %", "%"),
                    Moneda.formato(v.getIvaODefault()), cols));
            return l;
        }
        if (!CalculoIva.esMixto(grupos)) {
            CalculoIva.Grupo g = grupos.get(0);
            l.add(dosColumnas(g.etiqueta().replace(" %", "%"), Moneda.formato(g.exento() ? 0 : g.iva()), cols));
            return l;
        }
        l.addAll(desglosePorTasa(grupos, cols));
        l.add(dosColumnas("IVA", Moneda.formato(v.getIvaODefault()), cols));
        return l;
    }

    /** Líneas "Gravado 16% / IVA 16% / Tasa 0% / Exento" con sus importes (ticket y corte) */
    static List<String> desglosePorTasa(List<CalculoIva.Grupo> grupos, int cols) {
        List<String> l = new ArrayList<>();
        for (CalculoIva.Grupo g : grupos) {
            if (g.gravado()) {
                l.add(dosColumnas("  Gravado " + g.tasaIva().porcentaje() + "%", Moneda.formato(g.base()), cols));
                l.add(dosColumnas("  IVA " + g.tasaIva().porcentaje() + "%", Moneda.formato(g.iva()), cols));
            } else if (g.exento()) {
                l.add(dosColumnas("  Exento de IVA", Moneda.formato(g.base()), cols));
            } else {
                l.add(dosColumnas("  Tasa 0%", Moneda.formato(g.base()), cols));
            }
        }
        return l;
    }

    /**
     * Reporte del corte de caja: el turno en curso, el día completo o el periodo
     * de un corte guardado ({@code e}). La ganancia solo aparece si
     * {@code e.ventas.gananciaVisible}. {@code corte} (el guardado para ese
     * periodo) es opcional.
     */
    public DocumentoImpresion documentoCorte(CajaService.EstadoCaja e, Corte corte, Configuracion c, String impresoPor) {
        VentaService.ResumenCorte r = e.ventas;
        int cols = columnas(c.getTicketAnchoMm());
        String raya = "-".repeat(cols);
        DateTimeFormatter fmtHora = DateTimeFormatter.ofPattern("HH:mm");
        DateTimeFormatter fmtFecha = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        DocumentoImpresion d = new DocumentoImpresion();
        d.titulo(c.getNombreTienda());
        String titulo = r.esGeneral() ? "CORTE GENERAL (TODAS LAS CAJAS)"
                : multiCaja() ? "CORTE DE " + IdentidadCaja.etiqueta(r.caja).toUpperCase() : "CORTE DE CAJA";
        d.agregar(titulo, DocumentoImpresion.Alineacion.CENTRO, true, false);
        d.centrada(e.turno ? "Turno" : "Día completo");
        d.linea(raya);
        if (corte != null) d.linea(dosColumnas("Corte #" + corte.getId(), "por " + corte.getCajeroNombre(), cols));
        d.linea(dosColumnas("Desde", e.inicio.format(e.inicio.toLocalDate().equals(e.fin.toLocalDate()) ? fmtHora : fmtFecha), cols));
        d.linea(dosColumnas("Hasta", e.fin.format(fmtFecha), cols));
        d.linea("Impreso por: " + (impresoPor != null ? impresoPor : "—"));
        d.linea(raya);
        d.negrita("Efectivo en caja");
        d.linea(dosColumnas(e.efectivoInicialEsFondo ? "Fondo inicial" : "Dejado por el corte anterior", Moneda.formato(e.efectivoInicial), cols));
        d.linea(dosColumnas("+ Ventas en efectivo", Moneda.formato(r.efectivo), cols));
        if (e.entradas > 0) d.linea(dosColumnas("+ Entradas de efectivo", Moneda.formato(e.entradas), cols));
        if (e.salidas > 0)  d.linea(dosColumnas("- Salidas de efectivo", Moneda.formato(e.salidas), cols));
        if (e.retiros != 0) d.linea(dosColumnas("- Retirado en cortes", Moneda.formato(e.retiros), cols));
        d.agregar(dosColumnas("Efectivo esperado", Moneda.formato(e.esperado()), cols),
                DocumentoImpresion.Alineacion.IZQUIERDA, true, false);
        if (corte != null) {
            d.linea(dosColumnas("Efectivo contado", Moneda.formato(corte.getEfectivoContado()), cols));
            d.agregar(dosColumnas(corte.hayFaltante() ? "FALTANTE" : corte.haySobrante() ? "SOBRANTE" : "Diferencia",
                    Moneda.formato(corte.getDiferencia()), cols), DocumentoImpresion.Alineacion.IZQUIERDA, true, false);
            if (corte.getEfectivoDejado() != null) {
                d.linea(dosColumnas("Queda en caja", Moneda.formato(corte.getEfectivoDejado()), cols));
                d.linea(dosColumnas("Retiro", Moneda.formato(corte.getRetiro()), cols));
            }
            if (corte.getObservaciones() != null) ajustar("Obs.: " + corte.getObservaciones(), cols).forEach(d::linea);
        }
        if (!e.movimientos.isEmpty()) {
            d.linea(raya);
            d.negrita("Entradas y salidas");
            for (MovimientoCaja m : e.movimientos) {
                String signo = m.esEntrada() ? "+" : "-";
                d.linea(dosColumnas(m.getFecha().format(fmtHora) + " " + recortar(m.getConcepto(), Math.max(8, cols - 18)),
                        signo + Moneda.formato(m.getMonto()), cols));
            }
        }
        d.linea(raya);
        d.negrita("Ventas por forma de pago");
        d.linea(dosColumnas("Efectivo", Moneda.formato(r.efectivo), cols));
        d.linea(dosColumnas("Tarjeta", Moneda.formato(r.tarjeta), cols));
        if (r.transferencia > 0) d.linea(dosColumnas("Transferencia", Moneda.formato(r.transferencia), cols));
        if (r.vales > 0) d.linea(dosColumnas("Vales", Moneda.formato(r.vales), cols));
        for (VentaService.TarjetaTerminal t : r.tarjetaPorTerminal) {
            d.linea(dosColumnas("  " + t.terminal() + " (" + t.pagos() + ")", Moneda.formato(t.monto()), cols));
        }
        d.linea(raya);
        d.linea(dosColumnas("Subtotal", Moneda.formato(r.subtotal), cols));
        if (CalculoIva.esMixto(r.porTasaIva)) desglosePorTasa(r.porTasaIva, cols).forEach(d::linea);
        d.linea(dosColumnas("IVA", Moneda.formato(r.iva), cols));
        d.agregar(dosColumnas("VENTAS (" + r.numVentas + ")", Moneda.formato(r.ventasTotales), cols),
                DocumentoImpresion.Alineacion.IZQUIERDA, true, false);
        d.linea(dosColumnas("Canceladas (" + r.numCanceladas + ")", Moneda.formato(r.totalCanceladas), cols));
        if (r.gananciaVisible) {
            d.linea(dosColumnas("Ganancia", Moneda.formato(r.ganancia), cols));
            // Productos comunes (Ctrl+P, fuera del catálogo): su costo no es real sino una estimación
            // del 20 %; misma regla que la pantalla de Corte (solo con la ganancia visible)
            if (r.productosComunes > 0.004) {
                ajustar(textoProductosComunes(r), cols).forEach(d::linea);
            }
        }
        if (!r.porCaja.isEmpty()) {
            d.linea(raya);
            d.negrita("Por caja");
            for (VentaService.VentasCaja vc : r.porCaja) {
                d.linea(dosColumnas("Caja " + vc.caja() + " (" + vc.ventas() + ")", Moneda.formato(vc.total()), cols));
            }
        }
        if (!r.porDepartamento.isEmpty()) {
            d.linea(raya);
            d.negrita("Por departamento");
            r.porDepartamento.forEach((n, m) -> d.linea(dosColumnas(n, Moneda.formato(m), cols)));
        }
        if (!r.vouchers.isEmpty()) {
            d.linea(raya);
            d.negrita("Vouchers de tarjeta");
            for (VentaService.VoucherTarjeta v : r.vouchers) {
                d.linea(dosColumnas("#" + v.folio() + " " + v.fecha().format(fmtHora), Moneda.formato(v.monto()), cols));
                String det = (v.terminal() != null ? v.terminal() : "s/terminal")
                        + (v.ultimos4() != null ? " ****" + v.ultimos4() : "")
                        + (v.autorizacion() != null ? " aut. " + v.autorizacion() : "");
                ajustar("  " + det, cols).forEach(d::linea);
            }
        }
        if (!e.cortes.isEmpty() && !e.turno) {
            d.linea(raya);
            d.negrita("Cortes del día");
            for (Corte ct : e.cortes) {
                d.linea(dosColumnas(ct.getRealizadoEn().format(fmtHora) + " " + recortar(ct.getCajeroNombre(), Math.max(6, cols - 24)),
                        "dif. " + Moneda.formato(ct.getDiferencia()), cols));
            }
        }
        d.linea(raya);
        return d;
    }

    /**
     * Comprobante de una entrada o salida de efectivo (CRT-08), para guardarlo
     * en el cajón o entregarlo a quien recibió el dinero.
     */
    public DocumentoImpresion documentoMovimientoCaja(MovimientoCaja m, Configuracion c) {
        int cols = columnas(c.getTicketAnchoMm());
        String raya = "-".repeat(cols);
        DocumentoImpresion d = new DocumentoImpresion();
        d.titulo(c.getNombreTienda());
        d.agregar(m.esEntrada() ? "ENTRADA DE EFECTIVO" : "SALIDA DE EFECTIVO", DocumentoImpresion.Alineacion.CENTRO, true, false);
        d.linea(raya);
        d.linea(dosColumnas("Folio: " + m.getId(), m.getFecha().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), cols));
        if (multiCaja()) d.linea(IdentidadCaja.etiqueta(m.getCaja()));
        d.linea("Cajero: " + (m.getCajeroNombre() != null ? m.getCajeroNombre() : "—"));
        if (m.getAutorizadoPor() != null) d.linea("Autorizó: " + m.getAutorizadoPor());
        d.linea(raya);
        d.linea(m.getCategoria().getEtiqueta());
        ajustar(m.getConcepto(), cols).forEach(d::linea);
        if (m.getProveedorNombre() != null) d.linea("Proveedor: " + m.getProveedorNombre());
        if (m.getOrdenCompraId() != null) d.linea("Orden de compra #" + m.getOrdenCompraId());
        if (m.getVentaId() != null) d.linea("Venta #" + m.getVentaId());
        d.linea(raya);
        d.agregar(dosColumnas(m.esEntrada() ? "ENTRA" : "SALE", Moneda.formato(m.getMonto()), cols),
                DocumentoImpresion.Alineacion.IZQUIERDA, true, true);
        d.linea(raya);
        d.vacia();
        d.vacia();
        d.centrada("_".repeat(Math.min(cols - 4, 28)));
        d.centrada(m.esEntrada() ? "Recibió" : "Entregó / Recibió");
        return d;
    }

    /** "Incluye $X de productos comunes (ganancia estimada 20 %)" para el corte impreso */
    public static String textoProductosComunes(VentaService.ResumenCorte r) {
        return String.format("Incluye %s de productos comunes (ganancia estimada %d %%)",
                Moneda.formato(r.productosComunes), Math.round(ProductoComun.MARGEN_GANANCIA * 100));
    }

    private static String recortar(String texto, int max) {
        if (texto == null) return "";
        return texto.length() <= max ? texto : texto.substring(0, Math.max(1, max - 1)) + "…";
    }

    /** Orden de compra para llevar o mandar impresa al proveedor (sin precios de venta ni ganancias) */
    public DocumentoImpresion documentoOrdenCompra(OrdenCompra o, Configuracion c) {
        int cols = columnas(c.getTicketAnchoMm());
        String raya = "-".repeat(cols);
        DocumentoImpresion d = new DocumentoImpresion();
        d.titulo(c.getNombreTienda());
        d.agregar("ORDEN DE COMPRA #" + o.getId(), DocumentoImpresion.Alineacion.CENTRO, true, false);
        d.linea(raya);
        d.linea("Proveedor: " + o.getProveedor().getNombre());
        if (o.getProveedor().getContacto() != null) d.linea("Contacto: " + o.getProveedor().getContacto());
        if (o.getProveedor().getTelefono() != null) d.linea("Tel: " + o.getProveedor().getTelefono());
        d.linea(dosColumnas("Fecha", o.getFecha().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), cols));
        d.linea(dosColumnas("Estado", o.getEstado().getEtiqueta(), cols));
        d.linea(raya);
        for (OrdenCompraLinea l : o.getLineas()) {
            ajustar(cantidad(l.getCantidad()) + " x " + l.getProducto().getDescripcion(), cols).forEach(d::linea);
            d.linea(dosColumnas("  a " + Moneda.formato(l.getCostoUnitario()), Moneda.formato(l.getImporte()), cols));
        }
        d.linea(raya);
        d.agregar(dosColumnas("TOTAL", Moneda.formato(o.getTotal()), cols), DocumentoImpresion.Alineacion.IZQUIERDA, true, false);
        if (o.getNotas() != null) {
            d.linea(raya);
            ajustar("Notas: " + o.getNotas(), cols).forEach(d::linea);
        }
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
