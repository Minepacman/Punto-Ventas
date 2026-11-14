package me.eliassanchezfernandez.puntodeventa.util;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Guardia contra el lector de códigos en las ventanas modales (Alert/Dialog).
 *
 * Un lector escribe el código y manda Enter en milisegundos. Si en ese momento
 * hay un aviso abierto ("Vender sin existencia", "Producto no encontrado",
 * "Existencia baja"...), el Enter "confirma" el aviso y el artículo escaneado se
 * pierde o, peor, se acepta algo que el cajero no leyó. Aquí se reconoce la
 * ráfaga por su ritmo ({@link RafagaLector}): el Enter del lector no llega al
 * botón, y el código se entrega al destino registrado (la pantalla de Ventas)
 * cuando la ventana se cierra, para que entre al ticket como si se hubiera
 * escaneado después. Un Enter pulsado por una persona pasa normal.
 *
 * Solo actúa cuando el foco NO está en un campo de texto: si el cursor está en
 * uno, escanear ahí es intencional (p. ej. el código de un producto nuevo).
 *
 * Se instala en {@link Ventanas#estilizar(Dialog)}, es decir, en todos los
 * diálogos de la aplicación.
 */
public final class LectorEnModales {

    private static final String CLAVE_INSTALADO = "pos.lector.guardia";
    private static final String CLAVE_PENDIENTES = "pos.lector.pendientes";

    /** Quien recibe los códigos leídos durante un modal; devuelve false si no puede tomarlos ahora */
    private static volatile Predicate<String> destino;

    private LectorEnModales() { }

    /** La pantalla de Ventas se registra al crearse; la última registrada gana */
    public static void registrarDestino(Predicate<String> nuevoDestino) {
        destino = nuevoDestino;
    }

    public static Predicate<String> destinoActual() {
        return destino;
    }

    /** Instala el guardia en el diálogo (una sola vez por diálogo) */
    public static void proteger(Dialog<?> dialogo) {
        if (dialogo == null) return;
        DialogPane pane = dialogo.getDialogPane();
        if (pane == null || pane.getProperties().containsKey(CLAVE_INSTALADO)) return;
        pane.getProperties().put(CLAVE_INSTALADO, Boolean.TRUE);
        instalar(pane, () -> ventanaDe(pane));
    }

    /**
     * Instala el guardia sobre un nodo raíz cualquiera (filtros de teclado). {@code ventana}
     * da la ventana que lo contiene, para entregar los códigos cuando se cierre.
     */
    static void instalar(Node raiz, java.util.function.Supplier<Window> ventana) {
        RafagaLector lector = new RafagaLector();
        raiz.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (focoEnTexto(raiz)) { lector.limpiar(); return; }
            String ch = e.getCharacter();
            if (ch == null || ch.isEmpty() || Character.isISOControl(ch.charAt(0))) return;
            if (lector.caracter(ch.charAt(0), System.nanoTime())) e.consume();   // ya es ráfaga: ni llega al botón
        });
        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.ENTER) return;
            if (focoEnTexto(raiz)) { lector.limpiar(); return; }
            Optional<String> codigo = lector.enter(System.nanoTime());
            if (codigo.isEmpty()) return;             // Enter de una persona: sigue su curso
            e.consume();
            encolar(raiz, ventana.get(), codigo.get());
        });
    }

    private static boolean focoEnTexto(Node raiz) {
        Scene scene = raiz.getScene();
        return scene != null && scene.getFocusOwner() instanceof TextInputControl;
    }

    private static Window ventanaDe(Node n) {
        return n.getScene() != null ? n.getScene().getWindow() : null;
    }

    /** Guarda el código y, la primera vez, programa la entrega para cuando la ventana se cierre */
    @SuppressWarnings("unchecked")
    private static void encolar(Node raiz, Window ventana, String codigo) {
        List<String> pendientes = (List<String>) raiz.getProperties().get(CLAVE_PENDIENTES);
        if (pendientes == null) {
            pendientes = new ArrayList<>();
            raiz.getProperties().put(CLAVE_PENDIENTES, pendientes);
            List<String> cola = pendientes;
            if (ventana != null && ventana.isShowing()) {
                // Al cerrarse: showAndWait() regresa y quien lo llamó termina su trabajo; después
                // (runLater) los códigos entran al ticket, en orden
                ventana.addEventHandler(WindowEvent.WINDOW_HIDDEN, ev -> {
                    raiz.getProperties().remove(CLAVE_PENDIENTES);
                    Platform.runLater(() -> entregar(cola));
                });
            } else {
                raiz.getProperties().remove(CLAVE_PENDIENTES);
                Platform.runLater(() -> entregar(cola));
            }
        }
        pendientes.add(codigo);
    }

    static void entregar(List<String> codigos) {
        Predicate<String> d = destino;
        for (String codigo : codigos) {
            if (d == null || !d.test(codigo)) {
                // No hay pantalla de Ventas a la vista: el código se descarta (el Enter tampoco
                // confirmó nada, que es lo que importa)
                break;
            }
        }
    }
}
