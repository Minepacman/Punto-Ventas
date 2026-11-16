package me.eliassanchezfernandez.puntodeventa.servidor;

import me.eliassanchezfernandez.puntodeventa.util.PaquetesIconos;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Apariencia;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Paleta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La apariencia de la tienda (o la "solo para mí" del usuario) traducida para la página móvil
 * (movil/index.html): variables CSS para claro / oscuro / alto contraste, letra, escala, vidrio y,
 * con sesión, las rutas del paquete de iconos elegido. La página lo aplica en {@code :root}.
 */
public final class AparienciaWeb {

    private AparienciaWeb() { }

    /** Fuentes incluidas que sirve el servidor (lista cerrada; ver ServidorMovilService.RECURSOS_ESTATICOS) */
    public static final List<String> ARCHIVOS_FUENTES = List.of(
            "Inter-Regular.ttf", "Inter-Bold.ttf",
            "AtkinsonHyperlegible-Regular.ttf", "AtkinsonHyperlegible-Bold.ttf",
            "Nunito-Regular.ttf", "Nunito-Bold.ttf");

    /** Variable -pos-* del escritorio → variable de la página móvil */
    private static final Map<String, String> VARIABLES = new LinkedHashMap<>();

    static {
        VARIABLES.put("-pos-bg-app", "--bg");
        VARIABLES.put("-pos-surface", "--surface");
        VARIABLES.put("-pos-surface-alt", "--surface-alt");
        VARIABLES.put("-pos-surface-inset", "--inset");
        VARIABLES.put("-pos-border", "--border");
        VARIABLES.put("-pos-border-subtle", "--border-subtle");
        VARIABLES.put("-pos-text", "--text");
        VARIABLES.put("-pos-text-muted", "--muted");
        VARIABLES.put("-pos-text-subtle", "--subtle");
        VARIABLES.put("-pos-accent", "--accent");
        VARIABLES.put("-pos-accent-hover", "--accent-hover");
        VARIABLES.put("-pos-accent-text", "--accent-text");
        VARIABLES.put("-pos-accent-subtle", "--accent-subtle");
        VARIABLES.put("-pos-text-on-accent", "--on-accent");
        VARIABLES.put("-pos-focus-ring", "--ring");
        VARIABLES.put("-pos-danger", "--danger");
        VARIABLES.put("-pos-danger-text", "--danger-text");
        VARIABLES.put("-pos-danger-subtle", "--danger-subtle");
        VARIABLES.put("-pos-success", "--success");
        VARIABLES.put("-pos-success-text", "--success-text");
        VARIABLES.put("-pos-success-subtle", "--success-subtle");
        VARIABLES.put("-pos-warning", "--warning");
        VARIABLES.put("-pos-warning-text", "--warning-text");
        VARIABLES.put("-pos-warning-subtle", "--warning-subtle");
        VARIABLES.put("-pos-band-bg-flat", "--banda");
    }

    /**
     * Datos para la página. {@code conIconos}: incluir las rutas del paquete de iconos (solo con sesión;
     * la pantalla de entrada usa Lucide).
     */
    public static Map<String, Object> datos(Apariencia a, boolean conIconos) {
        Map<String, Object> r = new LinkedHashMap<>();
        boolean deFabrica = a.esPorDefecto();
        r.put("porDefecto", deFabrica);
        r.put("tema", a.tema().clave());
        r.put("acento", a.acento());
        // Variables por modo. Vacías = la página conserva sus colores propios (azul de fábrica en claro)
        r.put("claro", variables(a.conTema(Apariencia.Tema.CLARO)));
        r.put("oscuro", variables(a.conTema(Apariencia.Tema.OSCURO)));
        r.put("altoContraste", variables(a.conTema(Apariencia.Tema.ALTO_CONTRASTE)));
        Map<String, String> banda = new LinkedHashMap<>();
        banda.put("claro", Paleta.colorBanda(a.conTema(Apariencia.Tema.CLARO)));
        banda.put("oscuro", Paleta.colorBanda(a.conTema(Apariencia.Tema.OSCURO)));
        banda.put("altoContraste", "#000000");
        r.put("colorBanda", banda);
        if (a.letra().familia() != null) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("clave", a.letra().clave());
            l.put("familia", a.letra().familia());
            l.put("regular", "/fuentes/" + a.letra().archivo() + "-Regular.ttf");
            l.put("negrita", "/fuentes/" + a.letra().archivo() + "-Bold.ttf");
            r.put("letra", l);
        } else {
            r.put("letra", null);
        }
        r.put("escala", a.escala());
        r.put("vidrio", a.vidrio());
        r.put("grosorIconos", a.grosorIconos().ancho());
        r.put("iconosGrandes", a.iconosGrandes());
        r.put("paqueteIconos", a.paqueteIconos());
        r.put("estiloIconos", a.estiloIconos());
        if (conIconos) r.put("iconos", iconos(a));
        r.put("version", huella(r));
        return r;
    }

    /** Variables CSS de la página para esa apariencia (vacío si son los colores de fábrica) */
    static Map<String, String> variables(Apariencia a) {
        Map<String, String> tokens = Paleta.tokens(a);
        Map<String, String> v = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : VARIABLES.entrySet()) {
            String valor = tokens.get(e.getKey());
            if (valor != null) v.put(e.getValue(), valor);
        }
        return v;
    }

    /** Rutas del paquete elegido; null con Lucide (la página ya trae sus trazos) */
    static Map<String, Object> iconos(Apariencia a) {
        if ("lucide".equals(a.paqueteIconos()) || PaquetesIconos.paquete(a.paqueteIconos()) == null) return null;
        Map<String, Object> lista = new LinkedHashMap<>();
        double grosorBase = 2;
        for (String nombre : PaquetesIconos.nombres(a.paqueteIconos())) {
            PaquetesIconos.Dibujo d = PaquetesIconos.dibujo(a.paqueteIconos(), a.estiloIconos(), nombre);
            grosorBase = d.grosorBase();
            List<Map<String, Object>> rutas = new ArrayList<>();
            for (PaquetesIconos.Ruta ruta : d.rutas()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("d", ruta.d());
                if (ruta.rellenar()) m.put("r", 1);
                if (ruta.parImpar()) m.put("e", 1);
                if (ruta.translucida()) m.put("o", 1);
                rutas.add(m);
            }
            lista.put(nombre, rutas);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("paquete", a.paqueteIconos());
        r.put("estilo", a.estiloIconos());
        r.put("grosorBase", grosorBase);
        r.put("lista", lista);
        return r;
    }

    private static String huella(Map<String, Object> datos) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(String.valueOf(datos).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            return "0";
        }
    }
}
