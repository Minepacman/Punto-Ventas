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
        if (aplicada && a.equals(actual)) return;
        aplicada = true;
        cargarFuente(a.letra());
        boolean oscuro = a.tema().enEscritorio() == Apariencia.Tema.OSCURO;
        if (uaOscuro == null || uaOscuro != oscuro) {
            Application.setUserAgentStylesheet(oscuro ? new NordDark().getUserAgentStylesheet()
                    : new NordLight().getUserAgentStylesheet());
            uaOscuro = oscuro;
        }
        String anterior = urlHoja;
        urlHoja = a.esPorDefecto() ? null : HojaApariencia.comoUrl(HojaApariencia.css(a, appCss()));
        actual = a;
        EstiloIconos.configurar(a.paqueteIconos(), a.estiloIconos(), a.grosorIconos().ancho(), a.iconosGrandes() ? 1.25 : 1.0);
        for (Window w : Window.getWindows()) {
            if (w.getScene() != null) ponerHoja(w.getScene(), anterior);
        }
    }

    /** Escucha las ventanas que se abran (diálogos, ventanas modales) para ponerles la hoja vigente */
    private static void instalar() {
        if (instalado) return;
        instalado = true;
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
