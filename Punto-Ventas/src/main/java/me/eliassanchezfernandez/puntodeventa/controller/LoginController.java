package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.service.BaseDatosService;
import me.eliassanchezfernandez.puntodeventa.service.CajaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.LogotipoService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PoliticaContrasena;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controlador de la pantalla de Login.
 *
 * Flujo completo:
 *  1. Cajero ingresa usuario + contraseña → onIniciarSesion()
 *  2. SesionService.autenticar() verifica credenciales (BCrypt, intentos, bloqueo)
 *  3a. Error  → mostrar aviso rojo
 *  3b. Éxito y debe cambiar contraseña (USR-06) → panel "Nueva contraseña"
 *  3c. Éxito → panel "Fondo de Caja" si el cajero aún no capturó el de hoy (CRT-02)
 *  4. Se abre main.fxml en el mismo Stage
 *
 * Es prototype porque cada vez que se cierra sesión se vuelve a cargar
 * el FXML y necesitamos un controlador limpio.
 */
@Component
@Scope("prototype")
public class LoginController implements Initializable {

    // ── FXML – Formulario de credenciales ────────────────────────────────
    @FXML private TextField         txtUsuario;
    @FXML private PasswordField     txtContrasena;
    @FXML private Button            btnLogin;
    @FXML private HBox              hboxError;
    @FXML private Label             lblError;
    @FXML private ProgressIndicator progressLogin;
    @FXML private Label             lblNombreTienda;
    @FXML private ImageView         imgLogo;        // logotipo (si hay)
    @FXML private VBox              vboxForm;       // contenedor del formulario

    // ── FXML – Panel cambio de contraseña obligatorio ────────────────────
    @FXML private VBox          vboxCambioContrasena;
    @FXML private PasswordField txtNuevaContrasena;
    @FXML private PasswordField txtConfirmarContrasena;
    @FXML private Label         lblPoliticaContrasena;

    // ── FXML – Panel Fondo de Caja ────────────────────────────────────────
    @FXML private HBox      dineroEnCaja;   // panel completo (oculto al inicio)
    @FXML private TextField txtDineroCaja;  // monto inicial

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private SesionService        sesionService;
    @Autowired private CajaService          cajaService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private LogotipoService      logotipoService;
    @Autowired private BaseDatosService     baseDatosService;
    @Autowired private ApplicationContext   springContext;

    // ── Estado ────────────────────────────────────────────────────────────
    /** Cajero autenticado — se guarda para usarlo al abrir main.fxml */
    private Cajero cajeroAutenticado = null;
    /** Contraseña tecleada en el login (necesaria para el cambio obligatorio) */
    private String contrasenaCapturada = null;

    // ─────────────────────────────────────────────────────────────────────
    //  INICIALIZACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        try {
            lblNombreTienda.setText(configuracionService.getNombreTienda());
        } catch (Exception e) {
            lblNombreTienda.setText("Punto de Venta");
        }
        mostrarLogotipo();
        lblPoliticaContrasena.setText(PoliticaContrasena.DESCRIPCION);

        // Ocultar error al volver a escribir
        txtUsuario.setOnKeyTyped(e    -> ocultarError());
        txtContrasena.setOnKeyTyped(e -> ocultarError());
        txtDineroCaja.setOnKeyTyped(e -> ocultarError());
        txtNuevaContrasena.setOnKeyTyped(e -> ocultarError());
        txtConfirmarContrasena.setOnKeyTyped(e -> ocultarError());

        // Paneles secundarios ocultos hasta autenticarse
        mostrar(dineroEnCaja, false);
        mostrar(vboxCambioContrasena, false);

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
                    contrasenaCapturada = contrasena;
                    setUICargando(false);
                    txtContrasena.clear();
                    if (cajero.isDebeCambiarContrasena()) {
                        mostrarPanelCambioContrasena();
                    } else {
                        continuarTrasAutenticar();
                    }
                });

            } catch (SesionService.CredencialesInvalidasException e) {
                Platform.runLater(() -> {
                    mostrarError(e.getMessage().startsWith("Usuario bloqueado")
                            ? e.getMessage() : "Usuario o contraseña incorrectos.");
                    txtContrasena.clear();
                    txtContrasena.requestFocus();
                    setUICargando(false);
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    mostrarError("No se pudo conectar con la base de datos.");
                    setUICargando(false);
                });
            }
        }, "login-auth").start();
    }

    /** Después de autenticar (y de cambiar contraseña si tocaba): fondo o principal */
    private void continuarTrasAutenticar() {
        // Respaldo automático del día (si está activo); nunca detiene el inicio de sesión
        // en un hilo aparte: en un disco duro lento no debe congelar la pantalla
        Thread respaldo = new Thread(() -> {
            try {
                baseDatosService.respaldoAutomaticoDelDia();
            } catch (Exception e) {
                System.err.println("Respaldo automático del día: " + e.getMessage());
            }
        }, "respaldo-inicio-sesion");
        respaldo.setDaemon(true);
        respaldo.start();
        if (cajaService.requiereFondo(cajeroAutenticado)) {
            mostrarPanelFondoDeCaja();
        } else {
            // Ya capturó su fondo hoy (p. ej. reinicio del programa): entrar directo
            abrirVentanaPrincipal(cajeroAutenticado, cajaService.fondoDelCajeroActual());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 1b – CAMBIO DE CONTRASEÑA OBLIGATORIO (USR-06)
    // ─────────────────────────────────────────────────────────────────────

    private void mostrarPanelCambioContrasena() {
        ocultarError();
        mostrar(vboxForm, false);
        mostrar(vboxCambioContrasena, true);
        btnLogin.setText("Guardar contraseña");
        btnLogin.setOnAction(e -> onGuardarNuevaContrasena());
        Platform.runLater(() -> txtNuevaContrasena.requestFocus());
    }

    @FXML
    private void onGuardarNuevaContrasena() {
        String nueva = txtNuevaContrasena.getText();
        String confirmar = txtConfirmarContrasena.getText();
        if (!nueva.equals(confirmar)) {
            mostrarError("Las contraseñas no coinciden.");
            txtConfirmarContrasena.clear();
            txtConfirmarContrasena.requestFocus();
            return;
        }
        try {
            sesionService.cambiarContrasenaPropia(contrasenaCapturada, nueva);
        } catch (NegocioException e) {
            mostrarError(e.getMessage());
            return;
        }
        contrasenaCapturada = null;
        cajeroAutenticado = sesionService.getCajeroActual();
        mostrar(vboxCambioContrasena, false);
        Ventanas.alerta(Alert.AlertType.INFORMATION, "Contraseña actualizada",
                "Tu contraseña se cambió correctamente.");
        continuarTrasAutenticar();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 2 – FONDO DE CAJA
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Oculta el formulario de credenciales y muestra el panel
     * para ingresar el dinero inicial en caja.
     */
    private void mostrarPanelFondoDeCaja() {
        ocultarError();
        mostrar(vboxForm, false);
        mostrar(vboxCambioContrasena, false);
        mostrar(dineroEnCaja, true);

        // El botón principal ahora confirma el fondo en lugar de autenticar
        btnLogin.setText("Abrir caja");
        btnLogin.setOnAction(e -> onAceptarDineroCaja());

        Platform.runLater(() -> txtDineroCaja.requestFocus());
    }

    /**
     * Valida el monto ingresado, lo guarda en la BD (una vez por día y por
     * cajero) y abre la ventana principal. Acepta $0.
     */
    @FXML
    private void onAceptarDineroCaja() {
        String texto = txtDineroCaja.getText().trim()
                .replace("$", "").replace(",", "");

        double fondoCaja;
        try {
            fondoCaja = texto.isEmpty() ? 0.0 : Double.parseDouble(texto);
        } catch (NumberFormatException e) {
            mostrarError("Ingresa un monto válido (ej: 500 o 0).");
            return;
        }
        if (!Double.isFinite(fondoCaja) || fondoCaja < 0) {
            mostrarError("El fondo de caja no puede ser negativo.");
            return;
        }

        try {
            cajaService.registrarFondo(cajeroAutenticado, fondoCaja);
        } catch (NegocioException e) {
            mostrarError(e.getMessage());
            return;
        }
        abrirVentanaPrincipal(cajeroAutenticado, fondoCaja);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 3 – ABRIR VENTANA PRINCIPAL
    // ─────────────────────────────────────────────────────────────────────

    private void abrirVentanaPrincipal(Cajero cajero, double fondoCaja) {
        try {
            Stage stage = (Stage) btnLogin.getScene().getWindow();
            String titulo = "Punto de Venta  —  " + cajero.getNombreCompleto()
                    + "  |  Fondo: " + Moneda.formato(fondoCaja);
            Ventanas.mostrarPrincipal(stage, springContext, titulo);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("ERROR FATAL AL CARGAR MAIN: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** Logotipo de Configuración → Logotipo, si existe y se puede leer */
    private void mostrarLogotipo() {
        try {
            var logo = logotipoService.logoActual();
            if (logo.isPresent()) {
                Image img = new Image(logo.get().toUri().toString(), 160, 64, true, true);
                if (!img.isError()) {
                    imgLogo.setImage(img);
                    mostrar(imgLogo, true);
                    // La ventana de login es de tamaño fijo: darle el espacio del logotipo
                    Platform.runLater(() -> {
                        if (imgLogo.getScene() != null && imgLogo.getScene().getWindow() != null) {
                            var ventana = imgLogo.getScene().getWindow();
                            ventana.setHeight(ventana.getHeight() + img.getHeight() + 6);
                        }
                    });
                }
            }
        } catch (Exception e) {
            // sin logotipo el login se ve igual
        }
    }

    private static void mostrar(javafx.scene.Node nodo, boolean visible) {
        nodo.setVisible(visible);
        nodo.setManaged(visible);
    }

    private void mostrarError(String mensaje) {
        lblError.setText(mensaje);
        mostrar(hboxError, true);
    }

    private void ocultarError() {
        mostrar(hboxError, false);
    }

    private void setUICargando(boolean cargando) {
        btnLogin.setDisable(cargando);
        txtUsuario.setDisable(cargando);
        txtContrasena.setDisable(cargando);
        mostrar(progressLogin, cargando);
    }
}
