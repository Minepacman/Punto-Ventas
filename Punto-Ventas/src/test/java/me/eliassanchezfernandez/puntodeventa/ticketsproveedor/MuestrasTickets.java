package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Las 16 muestras reales (transcritas y anonimizadas) de {@code src/test/resources/tickets-proveedor/}
 * y la comparación campo por campo contra lo esperado.
 */
final class MuestrasTickets {

    private MuestrasTickets() { }

    static final List<DetectorProveedor.ProveedorRef> PROVEEDORES = List.of(
            new DetectorProveedor.ProveedorRef(1L, "Cervezas y Refrescos de Jalapa", "CRJ010101AB1"),
            new DetectorProveedor.ProveedorRef(2L, "Gamesa", null),
            new DetectorProveedor.ProveedorRef(3L, "Distribuidor Ejemplo", null),
            new DetectorProveedor.ProveedorRef(4L, "Barcel", null),
            new DetectorProveedor.ProveedorRef(5L, "Distribuidora de Dulces y Chicles Ramírez", null),
            new DetectorProveedor.ProveedorRef(6L, "Lala", null),
            new DetectorProveedor.ProveedorRef(7L, "Mars", null),
            new DetectorProveedor.ProveedorRef(8L, "BT Last Mile", null),
            new DetectorProveedor.ProveedorRef(9L, "Comercializadora MB", null));

    record Muestra(String nombre, String texto, JsonNode esperado) { }

    static List<Muestra> todas() throws IOException {
        ObjectMapper om = new ObjectMapper();
        List<Muestra> out = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            String n = String.format("t%02d", i);
            out.add(new Muestra(n, recurso(n + ".txt"), om.readTree(recurso(n + ".esperado.json"))));
        }
        return out;
    }

    static String recurso(String nombre) throws IOException {
        try (InputStream in = MuestrasTickets.class.getResourceAsStream("/tickets-proveedor/" + nombre)) {
            if (in == null) throw new IOException("No existe " + nombre);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Aciertos sobre campos comparados, con la lista de diferencias para el reporte */
    static final class Marcador {
        int campos;
        int aciertos;
        final List<String> diferencias = new ArrayList<>();

        void campo(String nombre, boolean ok, Object esperado, Object leido) {
            campos++;
            if (ok) aciertos++;
            else diferencias.add(nombre + ": esperado=" + esperado + " leído=" + leido);
        }

        double precision() { return campos == 0 ? 1 : (double) aciertos / campos; }
    }

    static Marcador comparar(List<TicketLeido> leidos, JsonNode esperado, boolean tolerante) {
        Marcador m = new Marcador();
        JsonNode tickets = esperado.get("tickets");
        m.campo("tickets", leidos.size() == tickets.size(), tickets.size(), leidos.size());
        for (int i = 0; i < tickets.size(); i++) {
            JsonNode e = tickets.get(i);
            TicketLeido t = i < leidos.size() ? leidos.get(i) : null;
            String p = "ticket" + (i + 1) + ".";
            DetectorProveedor.Deteccion d = t == null ? null : DetectorProveedor.detectar(t, PROVEEDORES);
            String prov = d == null ? null : d.proveedor().nombre();
            m.campo(p + "proveedor", Objects.equals(texto(e, "proveedor"), prov), texto(e, "proveedor"), prov);
            m.campo(p + "folio", Objects.equals(clave(texto(e, "folio")), t == null ? null : clave(t.getFolio())), texto(e, "folio"), t == null ? null : t.getFolio());
            LocalDate f = e.get("fecha").isNull() ? null : LocalDate.parse(e.get("fecha").asText());
            m.campo(p + "fecha", Objects.equals(f, t == null ? null : t.getFecha()), f, t == null ? null : t.getFecha());
            for (String campo : List.of("subtotal", "descuento", "impuestos", "cargos", "total")) {
                Double esp = numero(e, campo);
                Double lei = t == null ? null : switch (campo) {
                    case "subtotal" -> t.getSubtotal();
                    case "descuento" -> t.getDescuento();
                    case "impuestos" -> t.getImpuestos();
                    case "cargos" -> t.getCargos();
                    default -> t.getTotal();
                };
                m.campo(p + campo, mismoNumero(esp, lei), esp, lei);
            }
            JsonNode ls = e.get("lineas");
            int nl = t == null ? 0 : t.getLineas().size();
            m.campo(p + "renglones", nl == ls.size(), ls.size(), nl);
            for (int j = 0; j < ls.size(); j++) {
                JsonNode el = ls.get(j);
                LineaTicket l = t != null && j < nl ? t.getLineas().get(j) : null;
                String q = p + "linea" + (j + 1) + ".";
                m.campo(q + "codigo", Objects.equals(texto(el, "codigoProveedor"), l == null ? null : l.getCodigoProveedor()),
                        texto(el, "codigoProveedor"), l == null ? null : l.getCodigoProveedor());
                String de = texto(el, "descripcion");
                String dl = l == null ? null : l.getDescripcion();
                boolean descOk = dl != null && (clave(de).equals(clave(dl))
                        || (tolerante && TextoTicket.jaroWinkler(clave(de), clave(dl)) >= 0.85));
                m.campo(q + "descripcion", descOk, de, dl);
                m.campo(q + "cantidad", l != null && mismoNumero(numero(el, "cantidad"), l.getCantidad()), numero(el, "cantidad"), l == null ? null : l.getCantidad());
                m.campo(q + "unidad", Objects.equals(texto(el, "unidad"), l == null ? null : l.getUnidad()), texto(el, "unidad"), l == null ? null : l.getUnidad());
                Integer ee = el.get("empaque").isNull() ? null : el.get("empaque").asInt();
                m.campo(q + "empaque", Objects.equals(ee, l == null ? null : l.getEmpaque()), ee, l == null ? null : l.getEmpaque());
                m.campo(q + "precio", l != null && mismoNumero(numero(el, "precioUnitario"), l.getPrecioUnitario()), numero(el, "precioUnitario"), l == null ? null : l.getPrecioUnitario());
                m.campo(q + "descuento", l != null && mismoNumero(numero(el, "descuento"), l.getDescuento()), numero(el, "descuento"), l == null ? null : l.getDescuento());
                m.campo(q + "importe", l != null && mismoNumero(numero(el, "importe"), l.getImporte()), numero(el, "importe"), l == null ? null : l.getImporte());
            }
        }
        return m;
    }

    static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }

    static Double numero(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    static String clave(String s) {
        return s == null ? null : TextoTicket.clave(s);
    }

    static boolean mismoNumero(Double a, Double b) {
        if (a == null || b == null) return a == null && b == null;
        return Math.abs(a - b) < 0.006;
    }

    // ── Ruido típico del OCR ───────────────────────────────────────────────

    /**
     * Mete errores típicos del OCR en el texto, de forma reproducible (misma semilla = mismo texto):
     * 0→O, 1→l, 5→S, 8→B dentro de números; punto decimal perdido; espacios de más o de menos;
     * acentos perdidos; alguna letra cambiada en palabras.
     */
    static String conRuido(String texto, long semilla, double intensidad) {
        Random r = new Random(semilla);
        StringBuilder out = new StringBuilder();
        for (String renglon : texto.split("\n", -1)) {
            String[] fichas = renglon.split(" ", -1);
            List<String> nuevas = new ArrayList<>();
            for (String f : fichas) {
                String g = f;
                boolean numero = g.matches("\\$?\\d[\\d,]*\\.\\d{2}");
                if (numero && r.nextDouble() < 0.10 * intensidad) g = g.replace(".", "");          // se perdió el punto
                StringBuilder sb = new StringBuilder();
                for (char c : g.toCharArray()) {
                    double x = r.nextDouble();
                    if (Character.isDigit(c) && x < 0.04 * intensidad) {
                        sb.append(switch (c) { case '0' -> 'O'; case '1' -> 'l'; case '5' -> 'S'; case '8' -> 'B'; default -> c; });
                    } else if (Character.isLetter(c) && x < 0.02 * intensidad) {
                        sb.append((char) ('a' + r.nextInt(26)));
                    } else {
                        sb.append(c);
                    }
                }
                nuevas.add(TextoTicket.sinAcentos(sb.toString()));
            }
            String linea = String.join(" ", nuevas);
            if (r.nextDouble() < 0.10 * intensidad) linea = linea.replaceFirst(" {2,}", " ");      // columnas pegadas
            if (r.nextDouble() < 0.10 * intensidad) linea = "  " + linea + "   ";                 // sangría
            out.append(linea).append('\n');
        }
        return out.toString();
    }

    static String porcentaje(double v) {
        return String.format(Locale.ROOT, "%5.1f %%", v * 100);
    }
}
