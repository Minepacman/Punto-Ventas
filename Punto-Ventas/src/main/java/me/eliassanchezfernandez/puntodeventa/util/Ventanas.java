package me.eliassanchezfernandez.puntodeventa.util;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.net.URL;

/**
 * Utilidades de navegación y diálogos.
 *
 * Centraliza la carga de FXML con el contexto de Spring como fábrica de
 * controladores, para que login → principal → login y las ventanas
 * modales (usuarios, promociones, historial) se abran siempre igual.
 * También aplica app.css a los Alert/Dialog, que no heredan los estilos
 * de la escena principal.
 */
public final class Ventanas {

    private static final String CSS_APP = "/css/app.css";

    private Ventanas() { }

    // ── Navegación ────────────────────────────────────────────────────────

    /** Pantalla de inicio de sesión en el Stage principal (ventana chica, fija) */
    public static void mostrarLogin(Stage stage, ApplicationContext ctx) throws IOException {
        Parent root = cargar(ctx, "/fxml/login.fxml");

        stage.setFullScreen(false);
        stage.setMaximized(false);
        stage.setScene(new Scene(root, 300, 400));
        stage.setTitle("Punto de Venta");
        stage.setResizable(false);
        stage.sizeToScene();
        stage.centerOnScreen();
        stage.show();
    }

    /** Ventana principal (pestañas) a pantalla completa */
    public static void mostrarPrincipal(Stage stage, ApplicationContext ctx, String titulo)
            throws IOException {
        Parent root = cargar(ctx, "/fxml/main.fxml");

        stage.setScene(new Scene(root));
        stage.setTitle(titulo);
        stage.setResizable(true);
        stage.setMaximized(true);
        stage.setFullScreen(true);
        stage.show();
    }

    /**
     * Abre un FXML en una ventana modal y espera a que se cierre.
     * @param owner ventana padre (puede ser null)
     */
    public static void abrirModal(ApplicationContext ctx, String fxml, String titulo,
                                  Window owner, double ancho, double alto) throws IOException {
        Parent root = cargar(ctx, fxml);

        Stage stage = new Stage();
        stage.setTitle(titulo);
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        Scene scene = new Scene(root, ancho, alto);
        aplicarCss(scene);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(ancho, 640));
        stage.setMinHeight(Math.min(alto, 420));
        stage.showAndWait();
    }

    // ── Diálogos ──────────────────────────────────────────────────────────

    /** Aplica app.css a un Alert/Dialog (no heredan los estilos de la escena) */
    public static void estilizar(Dialog<?> dialog) {
        URL css = Ventanas.class.getResource(CSS_APP);
        if (css != null && !dialog.getDialogPane().getStylesheets().contains(css.toExternalForm())) {
            dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        }
    }

    /** Alert simple (sin encabezado) con los estilos de la app */
    public static void alerta(Alert.AlertType tipo, String titulo, String mensaje) {
        Alert a = new Alert(tipo);
        a.setTitle(titulo);
        a.setHeaderText(null);
        a.setContentText(mensaje);
        estilizar(a);
        a.showAndWait();
    }

    /** Confirmación Sí / Cancelar. true solo si el usuario eligió Sí. */
    public static boolean confirmar(String titulo, String mensaje) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, mensaje, ButtonType.YES, ButtonType.CANCEL);
        a.setTitle(titulo);
        a.setHeaderText(null);
        estilizar(a);
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void aplicarCss(Scene scene) {
        URL css = Ventanas.class.getResource(CSS_APP);
        if (css != null && !scene.getStylesheets().contains(css.toExternalForm())) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    private static Parent cargar(ApplicationContext ctx, String fxml) throws IOException {
        FXMLLoader loader = new FXMLLoader(Ventanas.class.getResource(fxml));
        loader.setControllerFactory(ctx::getBean);
        return loader.load();
    }
}
