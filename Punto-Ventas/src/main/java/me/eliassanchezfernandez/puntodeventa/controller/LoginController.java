package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.service.BaseDatosService;
import me.eliassanchezfernandez.puntodeventa.service.CajaService;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.ConexionService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.IdentidadCaja;
import me.eliassanchezfernandez.puntodeventa.service.LogotipoService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PoliticaContrasena;
import me.eliassanchezfernandez.puntodeventa.service.RecuperacionService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.PreferenciasLogin;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.URL;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controlador de la pantalla de inicio de sesión.
 *
 * Pasos (uno visible a la vez, ver {@link #mostrarPaso}):
 *  1. CREDENCIALES: usuario + contraseña → onIniciarSesion()
 *     SesionService.autenticar() verifica (BCrypt, intentos, bloqueo, límite de la caja).
 *  2. CAMBIO_CONTRASENA (USR-06): si el usuario debe definir una contraseña nueva.
 *  3. FONDO (CRT-02): si todavía nadie capturó el fondo de hoy en esta caja.
 *  4. Se abre main.fxml en el mismo Stage.
 *  Alterno: RECUPERACION, para el administrador que olvidó su contraseña
 *  (código de recuperación de un solo uso, ver RecuperacionService).
 *
 * Diseñada para el cajero: todo con teclado (Enter avanza, Esc limpia o
 * regresa, F8 muestra la contraseña), recuerda al último usuario de esta
 * computadora, avisa si Bloq Mayús está activado, muestra el negocio, la caja
 * y, con varias cajas, si hay conexión con el servidor. Ningún fallo de la
 * base de datos tumba el programa: se informa y se puede reintentar.
 *
 * ANTES de autenticar solo se consultan datos sin permiso (nombre del
 * negocio, logotipo, lista de usuarios si el admin la activó, si hay código
 * de recuperación), y en un hilo aparte para que un servidor caído no
 * congele la pantalla.
 *
 * Es prototype porque cada vez que se cierra sesión se vuelve a cargar
 * el FXML y necesitamos un controlador limpio.
 */
@Component
@Scope("prototype")
public class LoginController implements Initializable {

    private enum Paso { CREDENCIALES, CAMBIO_CONTRASENA, RECUPERACION, FONDO }

    /** Cada cuánto se vuelve a comprobar la conexión con el servidor mientras el login está visible */
    static final int SEGUNDOS_ENTRE_COMPROBACIONES = 20;

    // ── FXML – cabecera ──────────────────────────────────────────────────
    @FXML private StackPane raiz;
    @FXML private ImageView imgLogo;
    @FXML private Label     lblNombreTienda;
    @FXML private Label     lblCaja;
    @FXML private HBox      hboxConexion;
    @FXML private Circle    puntoConexion;
    @FXML private Label     lblConexion;

    // ── FXML – credenciales ──────────────────────────────────────────────
    @FXML private VBox          vboxForm;
    @FXML private FlowPane      panelUsuarios;
    @FXML private TextField     txtUsuario;
    @FXML private PasswordField txtContrasena;
    @FXML private TextField     txtContrasenaVisible;
    @FXML private ToggleButton  btnVerContrasena;
    @FXML private Hyperlink     lnkOlvide;

    // ── FXML – cambio de contraseña obligatorio ──────────────────────────
    @FXML private VBox          vboxCambioContrasena;
    @FXML private Label         lblCambioTitulo;
    @FXML private Label         lblPoliticaContrasena;
    @FXML private PasswordField txtNuevaContrasena;
    @FXML private PasswordField txtConfirmarContrasena;

    // ── FXML – recuperación del administrador ────────────────────────────
    @FXML private VBox          vboxRecuperacion;
    @FXML private Label         lblRecAyuda;
    @FXML private TextField     txtRecUsuario;
    @FXML private TextField     txtRecCodigo;
    @FXML private PasswordField txtRecNueva;
    @FXML private PasswordField txtRecConfirmar;

    // ── FXML – fondo de caja ─────────────────────────────────────────────
    @FXML private VBox      vboxFondo;
    @FXML private Label     lblFondoTitulo;
    @FXML private TextField txtDineroCaja;

    // ── FXML – pie ───────────────────────────────────────────────────────
    @FXML private HBox              hboxBloqMayus;
    @FXML private HBox              hboxError;
    @FXML private Label             lblError;
    @FXML private Button            btnLogin;
    @FXML private ProgressIndicator progressLogin;
    @FXML private Hyperlink         lnkVolver;
    @FXML private Label             lblAyudaTeclas;
    @FXML private Label             lblVersion;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private SesionService        sesionService;
    @Autowired private CajaService          cajaService;
    @Autowired private CajeroService        cajeroService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private LogotipoService      logotipoService;
    @Autowired private BaseDatosService     baseDatosService;
    @Autowired private ConexionService      conexionService;
    @Autowired private IdentidadCaja        identidadCaja;
    @Autowired private RecuperacionService  recuperacionService;
    @Autowired private DataSource           dataSource;
    @Autowired private ApplicationContext   springContext;

    // ── Estado ────────────────────────────────────────────────────────────
    private Paso paso = Paso.CREDENCIALES;
    /** Cajero autenticado — se guarda para usarlo al abrir main.fxml */
    private Cajero cajeroAutenticado = null;
    /** Contraseña tecleada en el login: solo vive mientras el cambio obligatorio la necesita como "actual" */
    private String contrasenaCapturada = null;
    private boolean cargando = false;
    private volatile boolean hayCodigoRecuperacion = false;
    private Timeline monitorConexion;
    private final AtomicBoolean comprobandoConexion = new AtomicBoolean(false);

    // ─────────────────────────────────────────────────────────────────────
    //  INICIALIZACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        lblPoliticaContrasena.setText(PoliticaContrasena.DESCRIPCION);
        lblVersion.setText(me.eliassanchezfernandez.puntodeventa.util.VersionApp.etiqueta());
        txtContrasenaVisible.textProperty().bindBidirectional(txtContrasena.textProperty());

        // Ocultar el error al volver a escribir
        for (TextField campo : List.of(txtUsuario, txtContrasena, txtContrasenaVisible, txtNuevaContrasena,
                txtConfirmarContrasena, txtRecUsuario, txtRecCodigo, txtRecNueva, txtRecConfirmar, txtDineroCaja)) {
            campo.setOnKeyTyped(e -> ocultarError());
        }
        configurarAvisoBloqMayus();
        mostrarLogotipo();
        mostrarCaja();
        recordarUltimoUsuario();
        mostrarPaso(Paso.CREDENCIALES);
        cargarDatosEnSegundoPlano();
        configurarIndicadorConexion();
        Platform.runLater(this::detenerMonitorAlCerrar);
    }

    /** Nombre del negocio, lista de usuarios y código de recuperación: datos sin permiso, fuera del hilo de JavaFX */
    private void cargarDatosEnSegundoPlano() {
        lblNombreTienda.setText("Caja Libre");
        Thread t = new Thread(() -> {
            String nombre;
            try {
                nombre = configuracionService.getNombreTienda();
            } catch (Exception e) {
                nombre = null;
            }
            List<CajeroService.UsuarioLogin> usuarios;
            try {
                usuarios = cajeroService.usuariosParaLogin();
            } catch (Exception e) {
                usuarios = List.of();
            }
            try {
                hayCodigoRecuperacion = recuperacionService.hayCodigo();
            } catch (Exception e) {
                hayCodigoRecuperacion = false;
            }
            String nombreFinal = nombre;
            List<CajeroService.UsuarioLogin> usuariosFinal = usuarios;
            Platform.runLater(() -> {
                if (nombreFinal != null && !nombreFinal.isBlank()) lblNombreTienda.setText(nombreFinal);
                mostrarSelectorDeUsuarios(usuariosFinal);
            });
        }, "login-datos");
        t.setDaemon(true);
        t.start();
    }

    /** Botones con los usuarios activos (solo si el administrador activó la opción) */
    private void mostrarSelectorDeUsuarios(List<CajeroService.UsuarioLogin> usuarios) {
        panelUsuarios.getChildren().clear();
        if (usuarios.isEmpty()) {
            mostrar(panelUsuarios, false);
            return;
        }
        for (CajeroService.UsuarioLogin u : usuarios) {
            Button chip = new Button(u.nombreCompleto() == null || u.nombreCompleto().isBlank() ? u.usuario() : u.nombreCompleto());
            chip.getStyleClass().add("login-chip");
            chip.setFocusTraversable(false);   // con teclado se teclea el usuario; el chip es para el ratón
            chip.setTooltip(new Tooltip("Usuario: " + u.usuario()));
            chip.setOnAction(e -> {
                txtUsuario.setText(u.usuario());
                ocultarError();
                campoContrasena().requestFocus();
            });
            panelUsuarios.getChildren().add(chip);
        }
        mostrar(panelUsuarios, true);
        ajustarVentana();
    }

    private void recordarUltimoUsuario() {
        try {
            PreferenciasLogin.ultimoUsuario(carpetaPreferencias()).ifPresent(txtUsuario::setText);
        } catch (RuntimeException e) {
            // sin preferencia el login funciona igual
        }
    }

    private static Path carpetaPreferencias() {
        return RutasApp.carpetaAplicacion();
    }

    /** "Caja 1 · Una sola caja" o "Caja 2 — Mostrador · Varias cajas" + indicador de conexión */
    private void mostrarCaja() {
        try {
            String caja = identidadCaja.nombre();
            if (identidadCaja.esMultiCaja()) {
                if (!caja.contains(String.valueOf(identidadCaja.numero()))) {
                    caja += " (" + identidadCaja.numero() + ")";
                }
                lblCaja.setText(caja + "  ·  Varias cajas");
                mostrar(hboxConexion, true);
            } else {
                lblCaja.setText(caja + "  ·  Una sola caja");
                mostrar(hboxConexion, false);
            }
        } catch (RuntimeException e) {
            lblCaja.setText("");
        }
    }

    // ── Conexión con el servidor (solo con varias cajas) ─────────────────

    private void configurarIndicadorConexion() {
        if (!identidadCaja.esMultiCaja()) return;
        comprobarConexion();
        monitorConexion = new Timeline(new KeyFrame(Duration.seconds(SEGUNDOS_ENTRE_COMPROBACIONES), e -> comprobarConexion()));
        monitorConexion.setCycleCount(Animation.INDEFINITE);
        monitorConexion.play();
    }

    /** Pide una conexión al pool y la valida, en un hilo aparte (si el servidor no responde puede tardar) */
    private void comprobarConexion() {
        if (!comprobandoConexion.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            boolean ok;
            try (Connection c = dataSource.getConnection()) {
                ok = c.isValid(3);
            } catch (Exception e) {
                ok = false;
            }
            boolean conectado = ok;
            Platform.runLater(() -> {
                comprobandoConexion.set(false);
                pintarConexion(conectado);
            });
        }, "login-conexion");
        t.setDaemon(true);
        t.start();
    }

    private void pintarConexion(boolean conectado) {
        String servidor;
        try {
            ConexionLocal c = conexionService.actual();
            servidor = c.getHost() + ":" + c.getPuerto();
        } catch (RuntimeException e) {
            servidor = "el servidor";
        }
        puntoConexion.getStyleClass().removeAll("login-conexion-pendiente", "login-conexion-ok", "login-conexion-error");
        puntoConexion.getStyleClass().add(conectado ? "login-conexion-ok" : "login-conexion-error");
        lblConexion.setText(conectado
                ? "Conectado al servidor " + servidor
                : "Sin conexión con el servidor " + servidor);
    }

    private void detenerMonitor() {
        if (monitorConexion != null) {
            monitorConexion.stop();
            monitorConexion = null;
        }
    }

    /** Al sustituir la escena (ventana principal) o cerrar la ventana, el monitor se detiene solo */
    private void detenerMonitorAlCerrar() {
        Scene escena = raiz.getScene();
        if (escena == null) return;
        escena.windowProperty().addListener((obs, anterior, ventana) -> {
            if (ventana == null) detenerMonitor();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TECLADO
    // ─────────────────────────────────────────────────────────────────────

    /** Esc: limpia (credenciales) o regresa (otros pasos). F8: mostrar/ocultar contraseña. */
    @FXML
    private void onTeclaGlobal(KeyEvent e) {
        if (cargando) return;
        if (e.getCode() == KeyCode.ESCAPE) {
            if (paso == Paso.CREDENCIALES) {
                limpiarCredenciales();
            } else {
                onVolver();
            }
            e.consume();
        } else if (e.getCode() == KeyCode.F8 && paso == Paso.CREDENCIALES) {
            btnVerContrasena.setSelected(!btnVerContrasena.isSelected());
            onVerContrasena();
            e.consume();
        }
    }

    /** Primero borra la contraseña; si ya estaba vacía, también el usuario */
    private void limpiarCredenciales() {
        ocultarError();
        if (!txtContrasena.getText().isEmpty()) {
            txtContrasena.clear();
            campoContrasena().requestFocus();
        } else {
            txtUsuario.clear();
            txtUsuario.requestFocus();
        }
    }

    /** Enter en usuario: pasa a la contraseña (o entra si ya está escrita) */
    @FXML
    private void onEnterUsuario() {
        if (txtUsuario.getText().isBlank()) return;
        if (!txtContrasena.getText().isEmpty()) {
            onIniciarSesion();
        } else {
            campoContrasena().requestFocus();
        }
    }

    @FXML
    private void onEnterNuevaContrasena() {
        txtConfirmarContrasena.requestFocus();
    }

    @FXML
    private void onEnterRecUsuario() { txtRecCodigo.requestFocus(); }

    @FXML
    private void onEnterRecCodigo() { txtRecNueva.requestFocus(); }

    @FXML
    private void onEnterRecNueva() { txtRecConfirmar.requestFocus(); }

    @FXML
    private void onVerContrasena() {
        boolean ver = btnVerContrasena.isSelected();
        mostrar(txtContrasenaVisible, ver);
        mostrar(txtContrasena, !ver);
        btnVerContrasena.setText(ver ? "Ocultar (F8)" : "Mostrar (F8)");
        TextField activo = campoContrasena();
        activo.requestFocus();
        activo.positionCaret(activo.getText().length());
    }

    /** El campo de contraseña que se ve en este momento */
    private TextField campoContrasena() {
        return btnVerContrasena.isSelected() ? txtContrasenaVisible : txtContrasena;
    }

    /** Aviso de Bloq Mayús mientras un campo de contraseña tiene el foco */
    private void configurarAvisoBloqMayus() {
        List<TextField> contrasenas = List.of(txtContrasena, txtContrasenaVisible, txtNuevaContrasena,
                txtConfirmarContrasena, txtRecNueva, txtRecConfirmar);
        for (TextField campo : contrasenas) {
            campo.addEventHandler(KeyEvent.KEY_RELEASED, e -> actualizarAvisoBloqMayus());
            campo.focusedProperty().addListener((obs, antes, conFoco) -> actualizarAvisoBloqMayus());
        }
    }

    private void actualizarAvisoBloqMayus() {
        boolean enContrasena = raiz.getScene() != null && raiz.getScene().getFocusOwner() instanceof TextField campo
                && (campo == txtContrasena || campo == txtContrasenaVisible || campo == txtNuevaContrasena
                    || campo == txtConfirmarContrasena || campo == txtRecNueva || campo == txtRecConfirmar);
        boolean activo = false;
        if (enContrasena) {
            try {
                activo = Platform.isKeyLocked(KeyCode.CAPS).orElse(false);
            } catch (RuntimeException e) {
                activo = false;   // plataforma sin soporte: sin aviso
            }
        }
        boolean cambia = hboxBloqMayus.isVisible() != activo;
        mostrar(hboxBloqMayus, activo);
        if (cambia) ajustarVentana();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASOS
    // ─────────────────────────────────────────────────────────────────────

    private void mostrarPaso(Paso nuevo) {
        paso = nuevo;
        ocultarError();
        mostrar(vboxForm,              nuevo == Paso.CREDENCIALES);
        mostrar(vboxCambioContrasena,  nuevo == Paso.CAMBIO_CONTRASENA);
        mostrar(vboxRecuperacion,      nuevo == Paso.RECUPERACION);
        mostrar(vboxFondo,             nuevo == Paso.FONDO);
        mostrar(lnkVolver,             nuevo != Paso.CREDENCIALES);
        switch (nuevo) {
            case CREDENCIALES -> {
                btnLogin.setText("Iniciar sesión");
                btnLogin.setOnAction(e -> onIniciarSesion());
                lblAyudaTeclas.setText("Enter: continuar  ·  Esc: limpiar  ·  F8: ver contraseña");
            }
            case CAMBIO_CONTRASENA -> {
                btnLogin.setText("Guardar contraseña");
                btnLogin.setOnAction(e -> onGuardarNuevaContrasena());
                lblAyudaTeclas.setText("Enter: guardar  ·  Esc: cancelar");
            }
            case RECUPERACION -> {
                btnLogin.setText("Restablecer contraseña");
                btnLogin.setOnAction(e -> onRestablecer());
                lblAyudaTeclas.setText("Enter: continuar  ·  Esc: volver");
            }
            case FONDO -> {
                btnLogin.setText("Abrir caja");
                btnLogin.setOnAction(e -> onAceptarDineroCaja());
                lblAyudaTeclas.setText("Enter: abrir caja  ·  Esc: cancelar");
            }
        }
        ajustarVentana();
        Platform.runLater(() -> enfocar(nuevo));
    }

    private void enfocar(Paso p) {
        switch (p) {
            case CREDENCIALES -> {
                if (txtUsuario.getText().isBlank()) txtUsuario.requestFocus();
                else campoContrasena().requestFocus();
            }
            case CAMBIO_CONTRASENA -> txtNuevaContrasena.requestFocus();
            case RECUPERACION -> {
                if (txtRecUsuario.getText().isBlank()) txtRecUsuario.requestFocus();
                else txtRecCodigo.requestFocus();
            }
            case FONDO -> txtDineroCaja.requestFocus();
        }
    }

    /** Regresa al paso de credenciales; si había alguien autenticado a medias, se cierra esa sesión */
    @FXML
    private void onVolver() {
        if (cargando) return;
        if (cajeroAutenticado != null) {
            try {
                sesionService.cerrarSesion();
            } catch (RuntimeException e) {
                // nada que hacer: la sesión en memoria se descarta igual
            }
        }
        cajeroAutenticado = null;
        contrasenaCapturada = null;
        txtContrasena.clear();
        txtNuevaContrasena.clear();
        txtConfirmarContrasena.clear();
        txtRecCodigo.clear();
        txtRecNueva.clear();
        txtRecConfirmar.clear();
        txtDineroCaja.clear();
        mostrarPaso(Paso.CREDENCIALES);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 1 – AUTENTICACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onIniciarSesion() {
        if (cargando || paso != Paso.CREDENCIALES) return;
        String usuario    = txtUsuario.getText().trim();
        String contrasena = txtContrasena.getText();

        if (usuario.isEmpty()) {
            mostrarError("Escribe tu usuario.");
            txtUsuario.requestFocus();
            return;
        }
        if (contrasena.isEmpty()) {
            mostrarError("Escribe tu contraseña.");
            campoContrasena().requestFocus();
            return;
        }

        setUICargando(true);

        Thread t = new Thread(() -> {
            try {
                Cajero cajero = sesionService.autenticar(usuario, contrasena);
                // Para la pantalla "sin conexión con el servidor" (pide la clave del admin sin base de datos)
                conexionService.recordarAdministrador(cajero);
                PreferenciasLogin.guardarUltimoUsuario(carpetaPreferencias(), cajero.getUsuario());

                Platform.runLater(() -> {
                    cajeroAutenticado = cajero;
                    setUICargando(false);
                    txtContrasena.clear();
                    if (cajero.isDebeCambiarContrasena()) {
                        contrasenaCapturada = contrasena;   // solo para validarla como "actual" en el cambio
                        mostrarPanelCambioContrasena();
                    } else {
                        continuarTrasAutenticar();
                    }
                });

            } catch (SesionService.CredencialesInvalidasException e) {
                Platform.runLater(() -> {
                    setUICargando(false);
                    mostrarError(e.isBloqueo() ? e.getMessage() : SesionService.MENSAJE_CREDENCIALES);
                    txtContrasena.clear();
                    campoContrasena().requestFocus();
                });
            } catch (Exception e) {
                System.err.println("Inicio de sesión: no se pudo consultar la base de datos: " + e);
                Platform.runLater(() -> {
                    setUICargando(false);
                    mostrarError(mensajeErrorBaseDatos());
                    comprobarConexionSiAplica();
                    campoContrasena().requestFocus();
                });
            }
        }, "login-auth");
        t.setDaemon(true);
        t.start();
    }

    /** Después de autenticar (y de cambiar contraseña si tocaba): fondo o principal */
    /** Espera antes del respaldo del día tras iniciar sesión (ver continuarTrasAutenticar) */
    static final long RESPALDO_DEMORA_MS = Long.getLong("app.respaldo.demora-ms", 45_000L);

    private void continuarTrasAutenticar() {
        // Respaldo automático del día (si está activo); nunca detiene el inicio de sesión.
        // En un hilo aparte: en un disco duro lento no debe congelar la pantalla.
        Thread respaldo = new Thread(() -> {
            try {
                // Se espera a que la ventana principal ya esté dibujada: el VACUUM INTO de una base grande
                // pone el disco duro al 100 % y competiría con la carga de la pantalla de ventas
                Thread.sleep(RESPALDO_DEMORA_MS);
                baseDatosService.respaldoAutomaticoDelDia();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("Respaldo automático del día: " + e.getMessage());
            }
        }, "respaldo-inicio-sesion");
        respaldo.setDaemon(true);
        respaldo.setPriority(Thread.MIN_PRIORITY);
        respaldo.start();

        boolean requiereFondo;
        double fondo = 0;
        try {
            requiereFondo = cajaService.requiereFondo(cajeroAutenticado);
            if (!requiereFondo) fondo = cajaService.fondoDelCajeroActual();
        } catch (Exception e) {
            System.err.println("Inicio de sesión: no se pudo consultar el fondo de caja: " + e);
            abandonarInicio(mensajeErrorBaseDatos());
            return;
        }
        if (requiereFondo) {
            mostrarPanelFondoDeCaja();
        } else {
            // Ya capturó su fondo hoy (p. ej. reinicio del programa): entrar directo
            abrirVentanaPrincipal(cajeroAutenticado, fondo);
        }
    }

    /** Algo falló después de autenticar: se cierra la sesión a medias y se vuelve a las credenciales con el aviso */
    private void abandonarInicio(String mensaje) {
        onVolver();
        mostrarError(mensaje);
        comprobarConexionSiAplica();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 1b – CAMBIO DE CONTRASEÑA OBLIGATORIO (USR-06)
    // ─────────────────────────────────────────────────────────────────────

    private void mostrarPanelCambioContrasena() {
        lblCambioTitulo.setText("Hola, " + cajeroAutenticado.getNombreCompleto() + ". Define tu contraseña nueva para continuar.");
        txtNuevaContrasena.clear();
        txtConfirmarContrasena.clear();
        mostrarPaso(Paso.CAMBIO_CONTRASENA);
    }

    @FXML
    private void onGuardarNuevaContrasena() {
        if (cargando || paso != Paso.CAMBIO_CONTRASENA) return;
        String nueva = txtNuevaContrasena.getText();
        String confirmar = txtConfirmarContrasena.getText();
        if (nueva.isEmpty()) {
            mostrarError("Escribe tu contraseña nueva.");
            txtNuevaContrasena.requestFocus();
            return;
        }
        if (!nueva.equals(confirmar)) {
            mostrarError("Las contraseñas no coinciden.");
            txtConfirmarContrasena.clear();
            txtConfirmarContrasena.requestFocus();
            return;
        }
        try {
            sesionService.cambiarContrasenaPropia(contrasenaCapturada, nueva);
            // La pantalla "sin conexión" debe pedir la contraseña NUEVA del administrador, no la vieja
            conexionService.recordarAdministrador(sesionService.getCajeroActual());
        } catch (NegocioException e) {
            mostrarError(e.getMessage());
            txtNuevaContrasena.requestFocus();
            return;
        } catch (Exception e) {
            System.err.println("Cambio de contraseña: no se pudo guardar: " + e);
            mostrarError(mensajeErrorBaseDatos());
            return;
        }
        contrasenaCapturada = null;
        txtNuevaContrasena.clear();
        txtConfirmarContrasena.clear();
        cajeroAutenticado = sesionService.getCajeroActual();
        mostrar(vboxCambioContrasena, false);
        Ventanas.alerta(Alert.AlertType.INFORMATION, "Contraseña actualizada",
                "Tu contraseña se cambió correctamente. Úsala la próxima vez que entres.");
        continuarTrasAutenticar();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO ALTERNO – RECUPERAR LA CONTRASEÑA DEL ADMINISTRADOR
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onOlvideContrasena() {
        if (cargando) return;
        lnkOlvide.setVisited(false);
        txtRecUsuario.setText(txtUsuario.getText().trim());
        txtRecCodigo.clear();
        txtRecNueva.clear();
        txtRecConfirmar.clear();
        if (hayCodigoRecuperacion) {
            lblRecAyuda.setText("Escribe tu usuario de administrador, el código de recuperación que generaste en "
                    + "Configuración → Cajeros y tu contraseña nueva. El código se usa una sola vez.");
        } else {
            lblRecAyuda.setText("No hay un código de recuperación generado en esta tienda (se crea en Configuración → Cajeros). "
                    + "Pide a otro administrador que restablezca tu contraseña, o consulta "
                    + "docs/recuperacion-administrador.md. Si eres cajero, pídeselo al administrador.");
        }
        mostrarPaso(Paso.RECUPERACION);
    }

    @FXML
    private void onRestablecer() {
        if (cargando || paso != Paso.RECUPERACION) return;
        String usuario = txtRecUsuario.getText().trim();
        String codigo  = txtRecCodigo.getText();
        String nueva   = txtRecNueva.getText();
        if (usuario.isEmpty()) { mostrarError("Escribe tu usuario de administrador."); txtRecUsuario.requestFocus(); return; }
        if (codigo.isBlank())  { mostrarError("Escribe el código de recuperación."); txtRecCodigo.requestFocus(); return; }
        if (nueva.isEmpty())   { mostrarError("Escribe tu contraseña nueva."); txtRecNueva.requestFocus(); return; }
        if (!nueva.equals(txtRecConfirmar.getText())) {
            mostrarError("Las contraseñas no coinciden.");
            txtRecConfirmar.clear();
            txtRecConfirmar.requestFocus();
            return;
        }
        setUICargando(true);
        Thread t = new Thread(() -> {
            try {
                Cajero admin = recuperacionService.restablecerConCodigo(usuario, codigo, nueva);
                hayCodigoRecuperacion = false;
                Platform.runLater(() -> {
                    setUICargando(false);
                    txtRecCodigo.clear();
                    txtRecNueva.clear();
                    txtRecConfirmar.clear();
                    txtUsuario.setText(admin.getUsuario());
                    txtContrasena.clear();
                    mostrarPaso(Paso.CREDENCIALES);
                    Ventanas.alerta(Alert.AlertType.INFORMATION, "Contraseña restablecida",
                            "La contraseña de \"" + admin.getUsuario() + "\" se restableció y el código quedó consumido.\n"
                            + "Entra con tu contraseña nueva y genera otro código en Configuración → Cajeros.");
                    campoContrasena().requestFocus();
                });
            } catch (NegocioException e) {
                Platform.runLater(() -> {
                    setUICargando(false);
                    mostrarError(e.getMessage());
                    txtRecCodigo.requestFocus();
                });
            } catch (Exception e) {
                System.err.println("Recuperación: no se pudo consultar la base de datos: " + e);
                Platform.runLater(() -> {
                    setUICargando(false);
                    mostrarError(mensajeErrorBaseDatos());
                    comprobarConexionSiAplica();
                });
            }
        }, "login-recuperacion");
        t.setDaemon(true);
        t.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PASO 2 – FONDO DE CAJA
    // ─────────────────────────────────────────────────────────────────────

    private void mostrarPanelFondoDeCaja() {
        lblFondoTitulo.setText("Hola, " + cajeroAutenticado.getNombreCompleto() + ". Fondo de caja de hoy");
        txtDineroCaja.clear();
        mostrarPaso(Paso.FONDO);
    }

    /**
     * Valida el monto ingresado, lo guarda en la BD (una vez por día y por
     * caja) y abre la ventana principal. Acepta $0.
     */
    @FXML
    private void onAceptarDineroCaja() {
        if (cargando || paso != Paso.FONDO) return;
        String texto = me.eliassanchezfernandez.puntodeventa.util.CalculoCobro.normalizarNumero(txtDineroCaja.getText());

        double fondoCaja;
        try {
            fondoCaja = texto.isEmpty() ? 0.0 : Double.parseDouble(texto);
        } catch (NumberFormatException e) {
            mostrarError("Escribe un monto válido (por ejemplo 500 o 0).");
            txtDineroCaja.requestFocus();
            return;
        }
        if (!Double.isFinite(fondoCaja) || fondoCaja < 0) {
            mostrarError("El fondo de caja no puede ser negativo.");
            txtDineroCaja.requestFocus();
            return;
        }

        try {
            cajaService.registrarFondo(cajeroAutenticado, fondoCaja);
        } catch (NegocioException e) {
            mostrarError(e.getMessage());
            return;
        } catch (Exception e) {
            System.err.println("Fondo de caja: no se pudo guardar: " + e);
            mostrarError(mensajeErrorBaseDatos());
            comprobarConexionSiAplica();
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
            String titulo = "Caja Libre  —  " + identidadCaja.nombre() + "  —  " + cajero.getNombreCompleto()
                    + "  |  Fondo: " + Moneda.formato(fondoCaja);
            detenerMonitor();
            contrasenaCapturada = null;
            Ventanas.mostrarPrincipal(stage, springContext, titulo);
        } catch (Exception e) {
            e.printStackTrace();
            abandonarInicio("No se pudo abrir la ventana principal: " + e.getMessage()
                    + "\nIntenta de nuevo; si sigue fallando, cierra y vuelve a abrir el programa.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** Logotipo de Configuración → Logotipo, si existe y se puede leer (archivo local, sin base de datos) */
    private void mostrarLogotipo() {
        try {
            var logo = logotipoService.logoActual();
            if (logo.isPresent()) {
                Image img = new Image(logo.get().toUri().toString(), 220, 72, true, true);
                if (!img.isError()) {
                    imgLogo.setImage(img);
                    mostrar(imgLogo, true);
                }
            }
        } catch (Exception e) {
            // sin logotipo el login se ve igual
        }
    }

    /** Mensaje según el modo: con varias cajas el problema casi siempre es la red o el servidor */
    private String mensajeErrorBaseDatos() {
        return identidadCaja.esMultiCaja()
                ? "No se pudo conectar con el servidor de la tienda. Revisa el cable de red o el Wi-Fi y vuelve a intentar; si sigue igual, avisa al encargado."
                : "No se pudo abrir la base de datos. Vuelve a intentar; si sigue fallando, cierra y abre el programa de nuevo o avisa al encargado.";
    }

    private void comprobarConexionSiAplica() {
        if (identidadCaja.esMultiCaja()) comprobarConexion();
    }

    /** La ventana es de tamaño fijo: cada vez que cambia el contenido se ajusta a él (sin salirse de la pantalla) */
    private void ajustarVentana() {
        Platform.runLater(() -> {
            if (raiz.getScene() == null) return;
            Window ventana = raiz.getScene().getWindow();
            if (!(ventana instanceof Stage stage) || !stage.isShowing()) return;
            stage.sizeToScene();
            var visible = Screen.getPrimary().getVisualBounds();
            if (stage.getY() + stage.getHeight() > visible.getMaxY() || stage.getX() + stage.getWidth() > visible.getMaxX()
                    || stage.getY() < visible.getMinY() || stage.getX() < visible.getMinX()) {
                stage.centerOnScreen();
            }
        });
    }

    private static void mostrar(Node nodo, boolean visible) {
        nodo.setVisible(visible);
        nodo.setManaged(visible);
    }

    private void mostrarError(String mensaje) {
        lblError.setText(mensaje);
        mostrar(hboxError, true);
        ajustarVentana();
    }

    private void ocultarError() {
        if (!hboxError.isVisible()) return;
        mostrar(hboxError, false);
        ajustarVentana();
    }

    private void setUICargando(boolean cargando) {
        this.cargando = cargando;
        btnLogin.setDisable(cargando);
        vboxForm.setDisable(cargando);
        vboxRecuperacion.setDisable(cargando);
        lnkVolver.setDisable(cargando);
        mostrar(progressLogin, cargando);
    }
}
