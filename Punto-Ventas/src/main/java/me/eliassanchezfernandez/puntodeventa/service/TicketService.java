package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
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
