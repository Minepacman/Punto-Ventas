package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controlador de la pantalla de Login.
 *
 * Flujo:
 *  1. Usuario escribe credenciales y pulsa "Iniciar Sesión" (o Enter)
 *  2. Se validan campos vacíos
 *  3. SesionService.autenticar() verifica usuario + BCrypt hash
 *  4a. Éxito → cargar main.fxml en la misma ventana
 *  4b. Error  → mostrar aviso rojo con el mensaje
 */
@Component
public class LoginController implements Initializable {

    // ── FXML ─────────────────────────────────────────────────────────────
    @FXML private TextField         txtUsuario;
    @FXML private PasswordField     txtContrasena;
    @FXML private Button            btnLogin;
    @FXML private HBox              hboxError;
    @FXML private Label             lblError;
    @FXML private ProgressIndicator progressLogin;
    @FXML private Label             lblNombreTienda;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private SesionService       sesionService;
    @Autowired private ApplicationContext  springContext;

    // ── Inicialización ────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // lee nombre de la tienda desde application.properties
        lblNombreTienda.setText("El Chamgarro");

        // Ocultar error al empezar a escribir de nuevo
        txtUsuario.setOnKeyTyped(e   -> ocultarError());
        txtContrasena.setOnKeyTyped(e -> ocultarError());

        // Foco inicial en el campo de usuario
        Platform.runLater(() -> txtUsuario.requestFocus());
    }

    // ── Acción principal ──────────────────────────────────────────────────

    @FXML
    private void onIniciarSesion() {

        String usuario    = txtUsuario.getText().trim();
        String contrasena = txtContrasena.getText();

        // 1. Validar vacíos
        if (usuario.isEmpty() || contrasena.isEmpty()) {
            mostrarError("Por favor ingresa tu usuario y contraseña.");
            return;
        }

        // 2. Bloquear UI mientras autenticamos
        setUICargando(true);

        // 3. Autenticar en hilo de fondo para no bloquear la UI
        new Thread(() -> {
            try {
                Cajero cajero = sesionService.autenticar(usuario, contrasena);

                // 4. Éxito → cargar ventana principal en el hilo de JavaFX
                Platform.runLater(() -> abrirVentanaPrincipal(cajero));

            } catch (SesionService.CredencialesInvalidasException e) {
                Platform.runLater(() -> {
                    mostrarError("Usuario o contraseña incorrectos.");
                    txtContrasena.clear();
                    txtContrasena.requestFocus();
                    setUICargando(false);
                });
            }
        }).start();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    /**
     * Reemplaza la escena de login por la ventana principal (main.fxml).
     * La misma ventana (Stage) se reutiliza para evitar parpadeos.
     */
    private void abrirVentanaPrincipal(Cajero cajero) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/main.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            Stage stage = (Stage) btnLogin.getScene().getWindow();
            Scene scene = new Scene(root, 1200, 720);

            // Pasar el mismo stylesheet
            scene.getStylesheets().addAll(
                    btnLogin.getScene().getStylesheets());

            stage.setScene(scene);
            stage.setTitle("Punto de Venta  —  " + cajero.getNombreCompleto());
            stage.setMaximized(true);

        } catch (Exception e) {
            e.printStackTrace();
            mostrarError("Error al cargar la ventana principal: " + e.getMessage());
            setUICargando(false);
        }
    }

    private void mostrarError(String mensaje) {
        lblError.setText(mensaje);
        hboxError.setVisible(true);
        hboxError.setManaged(true);
    }

    private void ocultarError() {
        hboxError.setVisible(false);
        hboxError.setManaged(false);
    }

    private void setUICargando(boolean cargando) {
        btnLogin.setDisable(cargando);
        txtUsuario.setDisable(cargando);
        txtContrasena.setDisable(cargando);
        progressLogin.setVisible(cargando);
        progressLogin.setManaged(cargando);
    }
}