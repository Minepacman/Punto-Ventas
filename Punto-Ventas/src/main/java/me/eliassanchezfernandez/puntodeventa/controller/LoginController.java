package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
 * Flujo completo:
 *  1. Cajero ingresa usuario + contraseña → onIniciarSesion()
 *  2. SesionService.autenticar() verifica credenciales (BCrypt)
 *  3a. Error  → mostrar aviso rojo
 *  3b. Éxito  → ocultar formulario, mostrar panel "Fondo de Caja"
 *  4. Cajero ingresa monto inicial → onAceptarDineroCaja()
 *  5. Se valida el monto y se abre main.fxml en el mismo Stage
 */
@Component
public class LoginController implements Initializable {

    // ── FXML – Formulario de credenciales ────────────────────────────────
    @FXML private TextField         txtUsuario;
    @FXML private PasswordField     txtContrasena;
    @FXML private Button            btnLogin;
    @FXML private HBox              hboxError;
    @FXML private Label             lblError;
    @FXML private ProgressIndicator progressLogin;
    @FXML private Label             lblNombreTienda;
    @FXML private VBox              vboxForm;       // contenedor del formulario

    // ── FXML – Panel Fondo de Caja ────────────────────────────────────────
    @FXML private HBox      dineroEnCaja;   // panel completo (oculto al inicio)
    @FXML private TextField txtDineroCaja;  // monto inicial

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private SesionService      sesionService;
    @Autowired private ApplicationContext springContext;

    // ── Estado ────────────────────────────────────────────────────────────
    /** Cajero autenticado — se guarda para usarlo al abrir main.fxml */
    private Cajero cajeroAutenticado = null;

    // ─────────────────────────────────────────────────────────────────────
    //  INICIALIZACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // TODO: leer nombre de la tienda desde application.properties
        lblNombreTienda.setText("Mi Tienda");

        // Ocultar error al volver a escribir
        txtUsuario.setOnKeyTyped(e    -> ocultarError());
        txtContrasena.setOnKeyTyped(e -> ocultarError());

        // Fondo de caja oculto hasta autenticarse
        dineroEnCaja.setVisible(false);
        dineroEnCaja.setManaged(false);

        Platform.runLater(() -> txtUsuario.requestFocus());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 1 – AUTENTICACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onIniciarSesion() {
        String usuario    = txtUsuario.getText().trim();
        String contrasena = txtContrasena.getText();

        if (usuario.isEmpty() || contrasena.isEmpty()) {
            mostrarError("Por favor ingresa tu usuario y contraseña.");
            return;
        }

        setUICargando(true);

        new Thread(() -> {
            try {
                Cajero cajero = sesionService.autenticar(usuario, contrasena);

                Platform.runLater(() -> {
                    cajeroAutenticado = cajero;
                    setUICargando(false);
                    mostrarPanelFondoDeCaja();
                });

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

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 2 – FONDO DE CAJA
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Oculta el formulario de credenciales y muestra el panel
     * para ingresar el dinero inicial en caja.
     */
    
    private void mostrarPanelFondoDeCaja() {
        // Ocultar formulario de login
        vboxForm.setVisible(false);
        vboxForm.setManaged(false);

        // Mostrar panel de fondo de caja
        dineroEnCaja.setVisible(true);
        dineroEnCaja.setManaged(true);

        Platform.runLater(() -> txtDineroCaja.requestFocus());
    }

    /**
     * Valida el monto ingresado y abre la ventana principal.
     * Acepta $0 si el cajero no tiene fondo inicial.
     */
    @FXML
    private void onAceptarDineroCaja() {
        String texto = txtDineroCaja.getText().trim()
                .replace("$", "").replace(",", "");

        double fondoCaja;
        try {
            fondoCaja = texto.isEmpty() ? 0.0 : Double.parseDouble(texto);
        } catch (NumberFormatException e) {
            mostrarErrorFondo("Ingresa un monto válido (ej: 500 o 0).");
            return;
        }

        if (fondoCaja < 0) {
            mostrarErrorFondo("El fondo de caja no puede ser negativo.");
            return;
        }

        // TODO: guardar fondoCaja en la entidad Corte del día actual
        //       corteService.registrarFondoInicial(fondoCaja, cajeroAutenticado);

        abrirVentanaPrincipal(cajeroAutenticado, fondoCaja);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 3 – ABRIR VENTANA PRINCIPAL
    // ─────────────────────────────────────────────────────────────────────

    private void abrirVentanaPrincipal(Cajero cajero, double fondoCaja) {
        try {
            System.out.println("DEBUG: Intentando cargar main.fxml...");
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/main.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();
                System.out.println("DEBUG: main.fxml cargado exitosamente.");
            Stage stage = (Stage) btnLogin.getScene().getWindow();
            Scene scene = new Scene(root, 1200, 720);
            scene.getStylesheets().addAll(btnLogin.getScene().getStylesheets());

            stage.setScene(scene);
            stage.setTitle("Punto de Venta  —  " + cajero.getNombreCompleto()
                    + "  |  Fondo: $" + String.format("%.2f", fondoCaja));
            stage.setFullScreen(true);
            stage.show();

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("ERROR FATAL AL CARGAR MAIN: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void mostrarError(String mensaje) {
        lblError.setText(mensaje);
        hboxError.setVisible(true);
        hboxError.setManaged(true);
    }

    private void ocultarError() {
        hboxError.setVisible(false);
        hboxError.setManaged(false);
    }

    /** Muestra el error dentro del panel de fondo de caja */
    private void mostrarErrorFondo(String mensaje) {
        // Reutiliza el mismo label de error que ya existe en el FXML
        lblError.setText(mensaje);
        hboxError.setVisible(true);
        hboxError.setManaged(true);
    }

    private void setUICargando(boolean cargando) {
        btnLogin.setDisable(cargando);
        txtUsuario.setDisable(cargando);
        txtContrasena.setDisable(cargando);
        progressLogin.setVisible(cargando);
        progressLogin.setManaged(cargando);
    }
}