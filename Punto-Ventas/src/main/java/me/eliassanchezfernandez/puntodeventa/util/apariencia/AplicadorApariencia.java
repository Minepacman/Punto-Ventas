package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.util.EstiloIconos;
import me.eliassanchezfernandez.puntodeventa.util.ModoLigero;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

/**
 * Aplica una {@link Apariencia} a TODO el programa sin reiniciar: tema base de AtlantaFX (claro u
 * oscuro), la hoja {@link HojaApariencia} en cada escena (ventanas abiertas y las que se abran
 * después: escucha la lista de ventanas de JavaFX), las fuentes incluidas y el estilo de los iconos.
 * Solo en el hilo de JavaFX.
 */
public final class AplicadorApariencia {

    private AplicadorApariencia() { }

    private static Apariencia actual = Apariencia.porDefecto();
    /** URL data: de la hoja vigente (null = de fábrica, no se agrega nada) */
    private static String urlHoja;
    private static String textoAppCss;
    private static boolean instalado;
    private static boolean aplicada;
    private static Boolean uaOscuro;
    /** URL de css/ligero.css si el modo ligero está encendido (null = apagado) */
    private static String urlLigero;
    private static String urlLigeroRecurso;
    private static final Set<Apariencia.Letra> FUENTES_CARGADAS = EnumSet.noneOf(Apariencia.Letra.class);

    public static Apariencia actual() {
        return actual;
    }

    /** Aplica la apariencia (si es la misma no hace nada) */
    public static void aplicar(Apariencia a) {
        if (a == null) a = Apariencia.porDefecto();
        if (!Platform.isFxApplicationThread()) {
            Apariencia copia = a;
            Platform.runLater(() -> aplicar(copia));
            return;
        }
        instalar();
        String ligero = ModoLigero.activo() ? urlHojaLigera() : null;
        if (aplicada && a.equals(actual) && java.util.Objects.equals(ligero, urlLigero)) return;
        aplicada = true;
        String ligeroAnterior = urlLigero;
        urlLigero = ligero;
        cargarFuente(a.letra());
        boolean oscuro = a.tema().enEscritorio() == Apariencia.Tema.OSCURO;
        if (uaOscuro == null || uaOscuro != oscuro) {
            Application.setUserAgentStylesheet(oscuro ? new NordDark().getUserAgentStylesheet()
                    : new NordLight().getUserAgentStylesheet());
            uaOscuro = oscuro;
        }
        String anterior = urlHoja;
        // Modo ligero: el vidrio no se genera (la elección se conserva y vuelve al apagar el modo)
        Apariencia pintada = ligero != null && a.vidrio() ? a.conVidrio(false) : a;
        urlHoja = pintada.esPorDefecto() ? null : HojaApariencia.comoUrl(HojaApariencia.css(pintada, appCss()));
        actual = a;
        EstiloIconos.configurar(a.paqueteIconos(), a.estiloIconos(), a.grosorIconos().ancho(), a.iconosGrandes() ? 1.25 : 1.0);
        for (Window w : Window.getWindows()) {
            if (w.getScene() != null) {
                if (ligeroAnterior != null && ligero == null) w.getScene().getStylesheets().remove(ligeroAnterior);
                ponerHoja(w.getScene(), anterior);
            }
        }
    }

    /** Vuelve a aplicar la apariencia vigente (p. ej. al cambiar el modo ligero en esta computadora) */
    public static void reaplicar() {
        aplicar(actual);
    }

    /** ¿Se está pintando con el modo ligero? */
    public static boolean ligeroAplicado() {
        return urlLigero != null;
    }

    private static String urlHojaLigera() {
        if (urlLigeroRecurso == null) {
            var u = AplicadorApariencia.class.getResource(ModoLigero.HOJA);
            urlLigeroRecurso = u == null ? null : u.toExternalForm();
        }
        return urlLigeroRecurso;
    }

    /** Escucha las ventanas que se abran (diálogos, ventanas modales) para ponerles la hoja vigente */
    private static void instalar() {
        if (instalado) return;
        instalado = true;
        HojaApariencia.vidrioLigero = vidrioLigero();
        for (Window w : Window.getWindows()) vigilar(w);
        Window.getWindows().addListener((ListChangeListener<Window>) cambio -> {
            while (cambio.next()) {
                for (Window w : cambio.getAddedSubList()) vigilar(w);
            }
        });
    }

    private static final Set<Window> VIGILADAS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static void vigilar(Window w) {
        if (w instanceof PopupWindow) return;   // menús y listas toman el estilo de su dueño
        if (w.getScene() != null) ponerHoja(w.getScene(), null);
        if (!VIGILADAS.add(w)) return;
        w.sceneProperty().addListener((o, vieja, nueva) -> {
            if (nueva != null) ponerHoja(nueva, null);
        });
    }

    /** Pone la hoja vigente al final de la escena y quita cualquier hoja de apariencia anterior */
    public static void ponerHoja(Scene escena, String anterior) {
        var hojas = escena.getStylesheets();
        if (anterior != null) hojas.remove(anterior);
        hojas.removeIf(h -> h.startsWith("data:text/css") && !h.equals(urlHoja));
        if (urlHoja != null && !hojas.contains(urlHoja)) hojas.add(urlHoja);
        // La hoja ligera siempre al final (manda sobre las sombras de app.css y de la apariencia)
        if (urlLigero != null) {
            int i = hojas.indexOf(urlLigero);
            if (i != hojas.size() - 1) {
                if (i >= 0) hojas.remove(i);
                hojas.add(urlLigero);
            }
        }
    }

    /** Carga (una vez) los archivos de la familia de letra elegida */
    public static void cargarFuente(Apariencia.Letra letra) {
        if (letra == null || letra.archivo() == null || FUENTES_CARGADAS.contains(letra)) return;
        for (String peso : new String[]{"Regular", "Bold"}) {
            try (InputStream in = AplicadorApariencia.class.getResourceAsStream("/fuentes/" + letra.archivo() + "-" + peso + ".ttf")) {
                if (in != null) Font.loadFont(in, HojaApariencia.LETRA_BASE_PX);
            } catch (Exception e) {
                System.err.println("No se pudo cargar la letra " + letra.familia() + ": " + e.getMessage());
            }
        }
        FUENTES_CARGADAS.add(letra);
    }

    private static String appCss() {
        if (textoAppCss == null) {
            try (InputStream in = AplicadorApariencia.class.getResourceAsStream("/css/app.css")) {
                textoAppCss = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                textoAppCss = "";
            }
        }
        return textoAppCss;
    }

    /**
     * ¿Vidrio en su versión ligera? En equipos modestos o cuando JavaFX dibuja por software (sin aceleración
     * gráfica: tarjetas viejas, escritorio remoto). {@code -Dapp.vidrio.ligero=true|false} lo fuerza.
     */
    static boolean vidrioLigero() {
        String forzado = System.getProperty("app.vidrio.ligero");
        if (forzado != null) return Boolean.parseBoolean(forzado);
        return equipoModesto() || dibujoPorSoftware();
    }

    /** El "pipeline" de JavaFX es el de software (com.sun.prism.sw); cualquier duda = no */
    static boolean dibujoPorSoftware() {
        try {
            Object p = Class.forName("com.sun.prism.GraphicsPipeline").getMethod("getPipeline").invoke(null);
            return p != null && p.getClass().getName().startsWith("com.sun.prism.sw.");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ¿Equipo modesto? (2 núcleos o menos, o menos de 4 GB de RAM): el efecto vidrio puede hacerlo lento.
     */
    public static boolean equipoModesto() {
        int nucleos = Runtime.getRuntime().availableProcessors();
        long ram = 0;
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) ram = sun.getTotalMemorySize();
        } catch (RuntimeException | LinkageError e) {
            ram = 0;
        }
        return nucleos <= 2 || (ram > 0 && ram < 4L * 1024 * 1024 * 1024 - 256L * 1024 * 1024);
    }
}
