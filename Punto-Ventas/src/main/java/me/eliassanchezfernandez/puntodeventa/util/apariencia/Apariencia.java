package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cómo se ve el programa (y la página móvil): tema, color de acento, iconos, letra y efecto vidrio.
 * Inmutable; se guarda como JSON plano en {@code configuracion.apariencia} (toda la tienda) y en
 * {@code cajeros.apariencia} ("solo para mí"). Los tickets impresos no la usan.
 *
 * <p>Todo valor desconocido (un JSON viejo o escrito a mano) cae en el de fábrica: nunca falla.</p>
 */
public record Apariencia(
        Tema tema,
        String acento,
        String paqueteIconos,
        String estiloIconos,
        Grosor grosorIconos,
        boolean iconosGrandes,
        Letra letra,
        int escala,
        boolean vidrio) {

    /** Azul del programa (botones primarios, foco y selección); la banda oscura sale de él */
    public static final String ACENTO_DE_FABRICA = "#2F6CAD";

    /** Escalas de letra que se ofrecen (por ciento) */
    public static final List<Integer> ESCALAS = List.of(90, 100, 115, 130);

    public enum Tema {
        CLARO("claro", "Claro"),
        OSCURO("oscuro", "Oscuro"),
        ALTO_CONTRASTE("alto-contraste", "Alto contraste"),
        /** Sigue al teléfono (prefers-color-scheme); en la computadora se ve Claro */
        AUTOMATICO("automatico", "Automático");

        private final String clave;
        private final String nombre;

        Tema(String clave, String nombre) {
            this.clave = clave;
            this.nombre = nombre;
        }

        public String clave() { return clave; }
        public String nombre() { return nombre; }

        /** El tema que de verdad pinta la computadora (Automático = Claro) */
        public Tema enEscritorio() { return this == AUTOMATICO ? CLARO : this; }

        public boolean oscuro() { return this == OSCURO; }

        public static Tema deClave(String clave) {
            for (Tema t : values()) if (t.clave.equalsIgnoreCase(String.valueOf(clave))) return t;
            return CLARO;
        }
    }

    public enum Grosor {
        FINO("fino", "Fino", 1.5),
        NORMAL("normal", "Normal", 2.0),
        GRUESO("grueso", "Grueso", 2.5);

        private final String clave;
        private final String nombre;
        private final double ancho;

        Grosor(String clave, String nombre, double ancho) {
            this.clave = clave;
            this.nombre = nombre;
            this.ancho = ancho;
        }

        public String clave() { return clave; }
        public String nombre() { return nombre; }
        /** Ancho del trazo en el viewBox de 24 (Lucide trae 2) */
        public double ancho() { return ancho; }

        public static Grosor deClave(String clave) {
            for (Grosor g : values()) if (g.clave.equalsIgnoreCase(String.valueOf(clave))) return g;
            return NORMAL;
        }
    }

    /** Familias de letra incluidas en el programa (resources/fuentes, licencia OFL) */
    public enum Letra {
        SISTEMA("sistema", "Del sistema", null, null),
        INTER("inter", "Inter", "Inter", "Inter"),
        ATKINSON("atkinson", "Atkinson Hyperlegible (muy legible)", "Atkinson Hyperlegible", "AtkinsonHyperlegible"),
        NUNITO("nunito", "Nunito (redondeada)", "Nunito", "Nunito");

        private final String clave;
        private final String nombre;
        private final String familia;
        private final String archivo;

        Letra(String clave, String nombre, String familia, String archivo) {
            this.clave = clave;
            this.nombre = nombre;
            this.familia = familia;
            this.archivo = archivo;
        }

        public String clave() { return clave; }
        public String nombre() { return nombre; }
        /** Nombre de familia de la fuente (null = la del sistema) */
        public String familia() { return familia; }
        /** Prefijo de los archivos en resources/fuentes ("Inter" → Inter-Regular.ttf, Inter-Bold.ttf) */
        public String archivo() { return archivo; }

        public static Letra deClave(String clave) {
            for (Letra l : values()) if (l.clave.equalsIgnoreCase(String.valueOf(clave))) return l;
            return SISTEMA;
        }
    }

    /** Colores de acento sugeridos: nombre visible y color */
    public record Muestra(String nombre, String color) { }

    public static final List<Muestra> ACENTOS = List.of(
            new Muestra("Azul (de fábrica)", ACENTO_DE_FABRICA),
            new Muestra("Azul marino", "#2A4566"),
            new Muestra("Verde", "#2E7D4F"),
            new Muestra("Turquesa", "#0F7C86"),
            new Muestra("Morado", "#6B4BA8"),
            new Muestra("Rosa", "#B83C7A"),
            new Muestra("Rojo", "#B5342F"),
            new Muestra("Naranja", "#C25E12"),
            new Muestra("Gris", "#56606C"));

    public Apariencia {
        tema = tema == null ? Tema.CLARO : tema;
        Rgb a = Rgb.deHex(acento);
        acento = a == null ? ACENTO_DE_FABRICA : a.hex();
        paqueteIconos = paqueteIconos == null || !paqueteIconos.matches("[a-z0-9-]{1,30}") ? "lucide" : paqueteIconos;
        estiloIconos = estiloIconos == null || !estiloIconos.matches("[a-z-]{1,20}") ? "contorno" : estiloIconos;
        grosorIconos = grosorIconos == null ? Grosor.NORMAL : grosorIconos;
        letra = letra == null ? Letra.SISTEMA : letra;
        escala = escalaCercana(escala);
    }

    private static int escalaCercana(int pedida) {
        int mejor = 100;
        for (int e : ESCALAS) if (Math.abs(e - pedida) < Math.abs(mejor - pedida)) mejor = e;
        return mejor;
    }

    public static Apariencia porDefecto() {
        return new Apariencia(Tema.CLARO, ACENTO_DE_FABRICA, "lucide", "contorno", Grosor.NORMAL, false,
                Letra.SISTEMA, 100, false);
    }

    public boolean esPorDefecto() {
        return equals(porDefecto());
    }

    /** ¿Los colores son exactamente los de app.css? (tema claro con el azul de fábrica) */
    public boolean coloresDeFabrica() {
        return tema.enEscritorio() == Tema.CLARO && ACENTO_DE_FABRICA.equalsIgnoreCase(acento);
    }

    public Rgb colorAcento() {
        return Rgb.deHex(acento);
    }

    public double factorEscala() {
        return escala / 100.0;
    }

    // ── "withers" para la ventana de Apariencia ──────────────────────────
    public Apariencia conTema(Tema t) { return new Apariencia(t, acento, paqueteIconos, estiloIconos, grosorIconos, iconosGrandes, letra, escala, vidrio); }
    public Apariencia conAcento(String a) { return new Apariencia(tema, a, paqueteIconos, estiloIconos, grosorIconos, iconosGrandes, letra, escala, vidrio); }
    public Apariencia conPaquete(String p, String estilo) { return new Apariencia(tema, acento, p, estilo, grosorIconos, iconosGrandes, letra, escala, vidrio); }
    public Apariencia conEstiloIconos(String e) { return new Apariencia(tema, acento, paqueteIconos, e, grosorIconos, iconosGrandes, letra, escala, vidrio); }
    public Apariencia conGrosor(Grosor g) { return new Apariencia(tema, acento, paqueteIconos, estiloIconos, g, iconosGrandes, letra, escala, vidrio); }
    public Apariencia conIconosGrandes(boolean g) { return new Apariencia(tema, acento, paqueteIconos, estiloIconos, grosorIconos, g, letra, escala, vidrio); }
    public Apariencia conLetra(Letra l) { return new Apariencia(tema, acento, paqueteIconos, estiloIconos, grosorIconos, iconosGrandes, l, escala, vidrio); }
    public Apariencia conEscala(int e) { return new Apariencia(tema, acento, paqueteIconos, estiloIconos, grosorIconos, iconosGrandes, letra, e, vidrio); }
    public Apariencia conVidrio(boolean v) { return new Apariencia(tema, acento, paqueteIconos, estiloIconos, grosorIconos, iconosGrandes, letra, escala, v); }

    // ── JSON plano (sin Jackson: se lee en cada inicio de sesión) ────────

    public Map<String, Object> comoMapa() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tema", tema.clave());
        m.put("acento", acento);
        m.put("paqueteIconos", paqueteIconos);
        m.put("estiloIconos", estiloIconos);
        m.put("grosorIconos", grosorIconos.clave());
        m.put("iconosGrandes", iconosGrandes);
        m.put("letra", letra.clave());
        m.put("escala", escala);
        m.put("vidrio", vidrio);
        return m;
    }

    public String aJson() {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> e : comoMapa().entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String s) sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            else sb.append(v);
        }
        return sb.append('}').toString();
    }

    /** Lee el JSON guardado; null, vacío o dañado = de fábrica (campo por campo) */
    public static Apariencia desdeJson(String json) {
        if (json == null || json.isBlank()) return porDefecto();
        Map<String, String> m = new LinkedHashMap<>();
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("\"([A-Za-z]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|true|false|-?\\d+(?:\\.\\d+)?)")
                .matcher(json);
        while (mt.find()) m.put(mt.group(1), mt.group(3) != null ? mt.group(3) : mt.group(2));
        return desdeMapa(m);
    }

    public static Apariencia desdeMapa(Map<String, ?> m) {
        Apariencia d = porDefecto();
        return new Apariencia(
                Tema.deClave(texto(m.get("tema"), d.tema.clave())),
                texto(m.get("acento"), d.acento),
                texto(m.get("paqueteIconos"), d.paqueteIconos),
                texto(m.get("estiloIconos"), d.estiloIconos),
                Grosor.deClave(texto(m.get("grosorIconos"), d.grosorIconos.clave())),
                Boolean.parseBoolean(texto(m.get("iconosGrandes"), "false")),
                Letra.deClave(texto(m.get("letra"), d.letra.clave())),
                entero(m.get("escala"), 100),
                Boolean.parseBoolean(texto(m.get("vidrio"), "false")));
    }

    private static String texto(Object v, String porOmision) {
        return v == null ? porOmision : String.valueOf(v).trim().toLowerCase(Locale.ROOT).equals("null") ? porOmision : String.valueOf(v).trim();
    }

    private static int entero(Object v, int porOmision) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? porOmision : (int) Math.round(Double.parseDouble(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return porOmision;
        }
    }
}
