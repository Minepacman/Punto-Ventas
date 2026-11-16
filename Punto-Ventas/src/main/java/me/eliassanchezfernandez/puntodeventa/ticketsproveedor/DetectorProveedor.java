package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ¿De qué proveedor es el ticket? Busca, en este orden:
 * <ol>
 *   <li>el RFC del proveedor impreso en el ticket;</li>
 *   <li>el nombre del proveedor en el encabezado (tolera letras cortadas por la orilla de la foto
 *       y palabras repartidas en varios renglones);</li>
 *   <li>el nombre que sugiere el formato del ticket (el sistema de Gamesa, Lala…) cuando el
 *       ticket no lo imprime.</li>
 * </ol>
 * Lo aprendido (claves de producto del proveedor ya vistas) lo agrega el servicio, que tiene la base.
 */
public final class DetectorProveedor {

    private DetectorProveedor() { }

    public record ProveedorRef(Long id, String nombre, String rfc) { }

    /** @param confianza 0..1 ; @param motivo texto para el usuario ("por el RFC", "por el nombre"…) */
    public record Deteccion(ProveedorRef proveedor, double confianza, String motivo) { }

    private static final Set<String> VACIAS = Set.of("S", "A", "SA", "DE", "C", "V", "CV", "Y", "LA", "EL", "LOS",
            "LAS", "DEL", "SAPI", "SC", "RL", "SRL", "B", "E", "MI");

    public static Deteccion detectar(TicketLeido t, List<ProveedorRef> proveedores) {
        if (t == null || proveedores == null || proveedores.isEmpty()) return null;
        for (ProveedorRef p : proveedores) {
            if (p.rfc() == null || p.rfc().isBlank()) continue;
            String rfc = TextoTicket.clave(p.rfc());
            for (String r : t.getRfcs()) if (TextoTicket.clave(r).equals(rfc)) return new Deteccion(p, 1.0, "por el RFC del ticket");
        }
        List<String> palabrasEncabezado = palabras(String.join(" ", t.getEncabezado()));
        Deteccion mejor = null;
        double segundo = 0;
        for (ProveedorRef p : proveedores) {
            double c = cobertura(palabras(p.nombre()), palabrasEncabezado);
            if (c <= 0) continue;
            if (mejor == null || c > mejor.confianza() + 1e-9
                    || (Math.abs(c - mejor.confianza()) < 1e-9 && p.nombre().length() > mejor.proveedor().nombre().length())) {
                if (mejor != null) segundo = Math.max(segundo, mejor.confianza());
                mejor = new Deteccion(p, c, "por el nombre impreso en el ticket");
            } else {
                segundo = Math.max(segundo, c);
            }
        }
        if (mejor != null && mejor.confianza() >= 0.75) return mejor;
        // Nombre de 3+ palabras con una mal leída ("BT Last Mwle"): basta si nadie más se le acerca
        if (mejor != null && mejor.confianza() >= 0.6 && palabras(mejor.proveedor().nombre()).size() >= 3 && segundo < 0.5) {
            return new Deteccion(mejor.proveedor(), mejor.confianza(), mejor.motivo());
        }
        String sugerido = t.getProveedorPorFormato();
        if (sugerido != null) {
            List<String> ps = palabras(sugerido);
            for (ProveedorRef p : proveedores) {
                double c = Math.max(cobertura(palabras(p.nombre()), ps), cobertura(ps, palabras(p.nombre())));
                if (c >= 0.99) return new Deteccion(p, 0.6, "por el formato del ticket (no trae el nombre impreso)");
            }
        }
        return null;
    }

    /** Palabras significativas, normalizadas */
    static List<String> palabras(String s) {
        List<String> out = new ArrayList<>();
        for (String p : TextoTicket.normal(s).split("[^A-Z0-9&]+")) {
            if (p.isEmpty() || VACIAS.contains(p)) continue;
            out.add(p);
        }
        return out;
    }

    /** Qué fracción de las palabras del nombre aparece en el texto (0 si el nombre no tiene palabras útiles) */
    static double cobertura(List<String> nombre, List<String> texto) {
        if (nombre.isEmpty()) return 0;
        int encontradas = 0;
        for (String n : nombre) {
            for (String w : texto) {
                if (n.length() <= 3 ? n.equals(w) : TextoTicket.mismaPalabra(n, w)) { encontradas++; break; }
            }
        }
        return (double) encontradas / nombre.size();
    }
}
