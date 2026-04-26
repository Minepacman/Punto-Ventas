package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

/**
 * Controlador de la ventana principal.
 *
 * Responsabilidades:
 *  - Actualizar el reloj de la barra de estado
 *  - Controlar accesos por rol (cajero / admin)
 *  - Escuchar atajos de teclado F1–F4
 */
@Component
public class MainController implements Initializable {

    @FXML private TabPane tabPane;
    @FXML private Label   labelUsuario;
    @FXML private Label   labelFecha;
    @FXML private Label   labelVersion;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy  HH:mm");

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        actualizarReloj();
        // TODO: cargar usuario autenticado desde SecurityContext
        labelUsuario.setText("Usuario: Sandi");
        labelVersion.setText("v1.0.0");
        configurarAtajosTeclado();
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    @FXML
    private void onSalir() {
        // TODO: confirmar cierre y cerrar Spring context
        javafx.application.Platform.exit();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void actualizarReloj() {
        labelFecha.setText(LocalDateTime.now().format(FMT));
        // Actualizar cada minuto
        javafx.animation.Timeline clock = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.minutes(1),
                        e -> labelFecha.setText(LocalDateTime.now().format(FMT))));
        clock.setCycleCount(javafx.animation.Animation.INDEFINITE);
        clock.play();
    }

    private void configurarAtajosTeclado() {
        // Los atajos se registran en start() desde la Scene;
        // aquí solo dejamos el placeholder.
        // Ejemplo: F1 → tabPane.getSelectionModel().select(0)
    }
}
