package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.CatalogoCodigo;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Qué proponer en el formulario de Productos a partir de una ficha del
 * catálogo de códigos de barras: descripción al estilo de una tienda de
 * abarrotes ("Coca-Cola 600 ml", "Alpura Leche Entera 1 L") y el departamento
 * de la tienda que corresponde a la categoría del catálogo. Nunca crea
 * departamentos: si ninguno encaja, no propone ninguno.
 *
 * Reglas puras (sin Spring) para probarlas solas.
 */
public final class AutollenadoProducto {

    private AutollenadoProducto() { }

    /** Longitud de Producto.descripcion */
    public static final int DESCRIPCION_MAX = 200;

    /**
     * Palabras que delatan el departamento, por categoría del catálogo. Se comparan sin
     * acentos ni mayúsculas contra el nombre de cada departamento de la tienda. "Otros" no
     * se mapea: mejor dejar el departamento vacío que adivinar.
     */
    static final Map<String, List<String>> SINONIMOS = Map.ofEntries(
            Map.entry("bebidas", List.of("bebidas", "bebida", "refrescos", "refresco", "jugos", "aguas", "liquidos")),
            Map.entry("botanas", List.of("botanas", "botana", "frituras", "snacks", "snack", "papitas", "sabritas")),
            Map.entry("lacteos", List.of("lacteos", "lacteo", "leche", "leches", "quesos", "cremeria", "yogur", "yogurt")),
            Map.entry("panaderia", List.of("panaderia", "pan", "panes", "pan y tortillas", "tortillas", "tortilleria", "pasteleria", "galletas")),
            Map.entry("abarrotes", List.of("abarrotes", "abarrote", "despensa", "secos", "granos", "semillas", "basicos", "general", "varios")),
            Map.entry("limpieza", List.of("limpieza", "hogar", "detergentes", "detergente", "aseo", "jarciería", "jarceria")),
            Map.entry("higiene personal", List.of("higiene", "higiene personal", "cuidado personal", "belleza", "farmacia", "perfumeria", "aseo personal", "salud")),
            Map.entry("dulces", List.of("dulces", "dulce", "dulceria", "confiteria", "chocolates", "golosinas", "chicles", "caramelos")),
            Map.entry("enlatados", List.of("enlatados", "enlatado", "conservas", "latas", "lata")),
            Map.entry("congelados", List.of("congelados", "congelado", "helados", "nieves", "hielo")),
            Map.entry("carnes y embutidos", List.of("carnes", "carne", "embutidos", "salchichoneria", "carnes frias", "carniceria")),
            Map.entry("mascotas", List.of("mascotas", "mascota", "perros", "gatos", "alimento para mascotas", "veterinaria")),
            Map.entry("bebes", List.of("bebes", "bebe", "panales", "infantil", "maternidad", "ninos")));

    /** Descripción propuesta: [marca] nombre [contenido], sin repetir lo que el nombre ya trae */
    public static String descripcion(CatalogoCodigo f) {
        String nombre = limpiar(f.getNombre());
        if (nombre.isEmpty()) return "";
        String marca = limpiar(f.getMarca());
        StringBuilder d = new StringBuilder();
        if (!marca.isEmpty() && !contieneNormalizado(nombre, marca) && !contieneNormalizado(marca, nombre)) {
            d.append(marca).append(' ');
        }
        d.append(nombre);
        String contenido = contenido(f);
        if (!contenido.isEmpty() && !nombreTraeCantidad(nombre, f)) {
            d.append(' ').append(contenido);
        }
        String r = d.toString().trim();
        return r.length() <= DESCRIPCION_MAX ? r : r.substring(0, DESCRIPCION_MAX).trim();
    }

    /** "600 ml" / "6 x 355 ml": el texto original si existe; si no, cantidad + unidad; si no, nada */
    static String contenido(CatalogoCodigo f) {
        String texto = limpiar(f.getContenido());
        if (!texto.isEmpty()) return texto;
        if (f.getCantidad() != null && f.getUnidad() != null && !f.getUnidad().isBlank()) {
            return numero(f.getCantidad()) + " " + f.getUnidad().trim();
        }
        return "";
    }

    /** ¿El nombre ya dice la cantidad ("Coca Cola Light 600", "Leche 1 L")? */
    static boolean nombreTraeCantidad(String nombre, CatalogoCodigo f) {
        String n = normalizar(nombre);
        if (f.getCantidad() != null) {
            String num = numero(f.getCantidad());
            if (contieneToken(n, normalizar(num))) return true;
        }
        String contenido = normalizar(limpiar(f.getContenido()));
        if (contenido.isEmpty()) return false;
        // "45 g" dentro de "Sabritas 45g": se compara también sin espacios
        return n.contains(contenido) || n.replace(" ", "").contains(contenido.replace(" ", ""));
    }

    /** Departamento de la tienda para la categoría del catálogo, si alguno encaja */
    public static Optional<Departamento> departamento(String categoria, List<Departamento> departamentos) {
        if (categoria == null || departamentos == null || departamentos.isEmpty()) return Optional.empty();
        String cat = normalizar(categoria);
        if (cat.isEmpty() || cat.equals("otros")) return Optional.empty();
        // 1) Un departamento que se llame igual que la categoría
        for (Departamento d : departamentos) {
            if (normalizar(d.getNombre()).equals(cat)) return Optional.of(d);
        }
        // 2) Sinónimos, en orden de prioridad: el nombre del departamento es el sinónimo o lo contiene como palabra
        List<String> sinonimos = SINONIMOS.getOrDefault(cat, List.of(cat));
        for (String s : sinonimos) {
            for (Departamento d : departamentos) {
                if (normalizar(d.getNombre()).equals(s)) return Optional.of(d);
            }
            for (Departamento d : departamentos) {
                if (contieneToken(normalizar(d.getNombre()), s)) return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    // ── Texto ─────────────────────────────────────────────────────────────

    static String limpiar(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** Minúsculas, sin acentos, solo letras/dígitos/espacios simples */
    static String normalizar(String s) {
        if (s == null) return "";
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String r = sinAcentos.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9ñ]+", " ").trim();
        return r.replace('ñ', 'n');
    }

    /** ¿b aparece dentro de a, ignorando acentos, mayúsculas y signos? ("Coca-Cola" dentro de "Coca Cola Light") */
    static boolean contieneNormalizado(String a, String b) {
        String na = normalizar(a).replace(" ", "");
        String nb = normalizar(b).replace(" ", "");
        return !nb.isEmpty() && na.contains(nb);
    }

    /** ¿token aparece como palabra completa (o secuencia de palabras) dentro de texto normalizado? */
    static boolean contieneToken(String textoNormalizado, String token) {
        if (token == null || token.isEmpty()) return false;
        String t = " " + textoNormalizado + " ";
        return t.contains(" " + token + " ");
    }

    /** 600.0 → "600"; 1.5 → "1.5" */
    static String numero(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e12) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
