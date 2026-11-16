package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Busca en el catálogo de la tienda el producto que más se parece a la descripción del ticket.
 *
 * El ticket abrevia ("MZ CHOCO GT", "DANONE FRESA 220G DS ASS") y la tienda escribe a su modo
 * ("Mazapán de chocolate gigante"). Se compara por palabras (tolerando abreviaturas y plurales),
 * con expansión de abreviaturas comunes, y la MEDIDA manda: "220 g" contra "220G" suma, contra "1 kg" resta
 * mucho (un yogur chico nunca debe caer en el litro).
 * Puro: el servicio le pasa el catálogo ya cargado.
 */
public final class EmparejadorProductos {

    private EmparejadorProductos() { }

    public record ProductoRef(Long id, String descripcion, String codigoBarras, Long departamentoId) { }

    public record Candidato(ProductoRef producto, double puntaje) { }

    /** Arriba de esto se propone solo; entre DUDOSO y CLARO se propone en amarillo; abajo, no se propone */
    public static final double CLARO = 0.75;
    public static final double DUDOSO = 0.5;
    /** Si el segundo queda a menos de esto del primero, el usuario elige */
    public static final double EMPATE = 0.08;

    private static final Map<String, String> ABREVIATURAS = Map.ofEntries(
            Map.entry("CHOCO", "CHOCOLATE"), Map.entry("AZU", "AZUCAR"), Map.entry("MZ", "MAZAPAN"),
            Map.entry("REG", "REGULAR"), Map.entry("GDE", "GRANDE"), Map.entry("MED", "MEDIANO"),
            Map.entry("LIM", "LIMON"), Map.entry("NJA", "NARANJA"), Map.entry("MZA", "MANZANA"),
            Map.entry("FSA", "FRESA"), Map.entry("VAI", "VAINILLA"), Map.entry("YOG", "YOGHURT"),
            Map.entry("YOGURT", "YOGHURT"), Map.entry("BEB", "BEBIBLE"), Map.entry("PED", "PEDIGREE"),
            Map.entry("WKS", "WHISKAS"), Map.entry("SURTID", "SURTIDO"), Map.entry("GT", "GIGANTE"),
            Map.entry("PAQ", "PAQUETE"), Map.entry("PQT", "PAQUETE"), Map.entry("CC", "COCA COLA"),
            Map.entry("CHOC", "CHOCOLATE"), Map.entry("CLASICA", "CLASICO"), Map.entry("DORADAS", "DORADA"),
            Map.entry("PINA", "PIÑA"));

    /** Palabras de relleno del ticket que no ayudan a distinguir */
    private static final java.util.Set<String> RUIDO = java.util.Set.of("DS", "NR", "PZA", "PZS", "PZ", "PET", "DTS",
            "FU", "NF", "RG", "RG2", "RG3", "NI", "RF", "PC", "FLOW", "F", "FL", "B", "BAR", "1P", "CN", "TPR", "IS",
            "ASS", "EVO", "PRE", "DE", "CON", "Y", "LA", "EL", "SIN", "X", "C/U", "PIEZA", "PIEZAS");

    private static final Pattern MEDIDA = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(KG|KILO|GRS?|G|ML|LTS?|LITROS?|L|OZ)\\b");

    /** Los mejores candidatos (máximo {@code max}) con puntaje ≥ 0.3, de mayor a menor */
    public static List<Candidato> candidatos(String descripcionTicket, List<ProductoRef> catalogo, int max) {
        Texto t = Texto.de(descripcionTicket);
        List<Candidato> out = new ArrayList<>();
        for (ProductoRef p : catalogo) {
            double s = puntaje(t, Texto.de(p.descripcion()));
            if (s >= 0.3) out.add(new Candidato(p, s));
        }
        out.sort(Comparator.comparingDouble(Candidato::puntaje).reversed());
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Parecido entre dos descripciones (0..1) */
    public static double parecido(String a, String b) {
        return puntaje(Texto.de(a), Texto.de(b));
    }

    record Texto(List<String> palabras, Double medida, String unidadMedida) {
        static Texto de(String s) {
            String n = TextoTicket.normal(s == null ? "" : s).replace('Ñ', 'N');
            // "117GRX48X1" → "117GR X48X1"; "220G" → "220 G"
            n = n.replaceAll("(\\d)(KG|GRS?|G|ML|LTS?|L)(?=X|\\b)", "$1 $2 ");
            Double medida = null;
            String unidad = null;
            Matcher m = MEDIDA.matcher(n);
            if (m.find()) {
                double v = Double.parseDouble(m.group(1).replace(',', '.'));
                String u = m.group(2);
                if (u.startsWith("K")) { v *= 1000; u = "G"; }
                else if (u.startsWith("G")) u = "G";
                else if (u.startsWith("L")) { v *= 1000; u = "ML"; }
                medida = v;
                unidad = u;
                n = n.substring(0, m.start()) + " " + n.substring(m.end());
            }
            List<String> ps = new ArrayList<>();
            for (String p : n.split("[^A-Z0-9/]+")) {
                if (p.isEmpty() || RUIDO.contains(p) || p.matches("\\d+(/.*)?|X\\d+X\\d+|\\d+X\\d+.*|\\d+/\\d*")) continue;
                String e = ABREVIATURAS.getOrDefault(p, p);
                for (String q : e.split(" ")) ps.add(raiz(q.replace('Ñ', 'N')));
            }
            return new Texto(ps, medida, unidad);
        }
    }

    /** Quita plurales simples para comparar (GALLETAS → GALLETA) */
    static String raiz(String p) {
        if (p.length() > 4 && p.endsWith("ES") && !p.endsWith("RES")) return p.substring(0, p.length() - 2);
        if (p.length() > 4 && p.endsWith("S")) return p.substring(0, p.length() - 1);
        return p;
    }

    private static boolean coinciden(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() >= 3 && b.length() >= 3 && (a.startsWith(b) || b.startsWith(a))) return true;
        return a.length() >= 4 && b.length() >= 4 && TextoTicket.jaroWinkler(a, b) >= 0.9;
    }

    private static double cobertura(List<String> de, List<String> en) {
        if (de.isEmpty()) return 0;
        int n = 0;
        for (String a : de) for (String b : en) if (coinciden(a, b)) { n++; break; }
        return (double) n / de.size();
    }

    static double puntaje(Texto ticket, Texto producto) {
        if (ticket.palabras().isEmpty() || producto.palabras().isEmpty()) return 0;
        double ct = cobertura(ticket.palabras(), producto.palabras());
        double cp = cobertura(producto.palabras(), ticket.palabras());
        double jw = TextoTicket.jaroWinkler(String.join(" ", ticket.palabras()), String.join(" ", producto.palabras()));
        double s = 0.45 * ct + 0.35 * cp + 0.2 * jw;
        if (ticket.medida() != null && producto.medida() != null) {
            boolean misma = ticket.unidadMedida().equals(producto.unidadMedida())
                    && Math.abs(ticket.medida() - producto.medida()) <= Math.max(1, 0.03 * producto.medida());
            s += misma ? 0.15 : -0.35;
        }
        return Math.max(0, Math.min(1, s));
    }
}
