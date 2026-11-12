package me.eliassanchezfernandez.puntodeventa.util;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.net.URL;
import java.util.Optional;

/**
 * Utilidades de navegación y diálogos.
 *
 * Centraliza la carga de FXML con el contexto de Spring como fábrica de
 * controladores, para que login → principal → login y las ventanas
 * modales se abran siempre igual. También aplica app.css a los
 * Alert/Dialog, que no heredan los estilos de la escena principal, y
 * ofrece los diálogos comunes (autorización de administrador, motivo).
 */
public final class Ventanas {

    private static final String CSS_APP = "/css/app.css";

    /** PNG del icono de la aplicación (los genera packaging/icono/GeneradorIcono.java) */
    private static final int[] TAMANOS_ICONO = {16, 32, 64, 128, 256};
    private static volatile java.util.List<javafx.scene.image.Image> iconos;

    /** Lo que hay que hacer antes de que la aplicación se cierre (guardar el ticket pendiente) */
    private static volatile Runnable accionAlCerrar;

    private Ventanas() { }

    /** Icono de la aplicación en la barra de título / barra de tareas (en macOS lo da el paquete .app) */
    public static void aplicarIcono(Stage stage) {
        if (stage == null) return;
        try {
            java.util.List<javafx.scene.image.Image> lista = iconos;
            if (lista == null) {
                lista = new java.util.ArrayList<>();
                for (int t : TAMANOS_ICONO) {
                    URL url = Ventanas.class.getResource("/icono/icono-" + t + ".png");
                    if (url != null) lista.add(new javafx.scene.image.Image(url.toExternalForm()));
                }
                iconos = lista;
            }
            if (!lista.isEmpty() && stage.getIcons().isEmpty()) stage.getIcons().setAll(lista);
        } catch (RuntimeException e) {
            // sin icono el programa funciona igual
        }
    }

    public static void setAccionAlCerrar(Runnable accion) {
        accionAlCerrar = accion;
    }

    /** Ejecuta (una vez) la acción registrada; nunca lanza */
    public static void ejecutarAccionAlCerrar() {
        Runnable accion = accionAlCerrar;
        accionAlCerrar = null;
        if (accion == null) return;
        try {
            accion.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── Navegación ────────────────────────────────────────────────────────

    /**
     * Pantalla de inicio de sesión en el Stage principal (ventana chica, fija).
     * El tamaño lo da el contenido (sizeToScene): el LoginController lo vuelve a
     * ajustar cada vez que cambia de paso o muestra un aviso.
     */
    public static void mostrarLogin(Stage stage, ApplicationContext ctx) throws IOException {
        Parent root = cargar(ctx, "/fxml/login.fxml");

        stage.setFullScreen(false);
        stage.setMaximized(false);
        stage.setMinWidth(0);
        stage.setMinHeight(0);
        stage.setScene(new Scene(root));
        stage.setTitle("Punto de Venta");
        aplicarIcono(stage);
        stage.setResizable(false);
        stage.sizeToScene();
        stage.centerOnScreen();
        stage.show();
    }

    /** Ventana ligera que aparece de inmediato mientras Spring arranca en segundo plano (ver PuntoDeVentaApp) */
    public static void mostrarArranque(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(Ventanas.class.getResource("/fxml/arranque.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root);
        aplicarCss(scene);
        stage.setScene(scene);
        stage.setTitle("Punto de Venta");
        aplicarIcono(stage);
        stage.setResizable(false);
        stage.sizeToScene();
        stage.centerOnScreen();
        stage.show();
    }

    /**
     * Carga en el hilo de JavaFX las clases, pieles y estilos de los controles que usan el login y
     * la ventana principal, sin mostrar nada: ocurre mientras Spring arranca en el otro núcleo, así
     * el login aparece antes. Solo es un calentamiento: cualquier fallo se ignora.
     */
    public static void precalentar() {
        try {
            javafx.scene.layout.VBox raiz = new javafx.scene.layout.VBox(
                    new javafx.scene.control.TextField("a"), new javafx.scene.control.PasswordField(),
                    new javafx.scene.control.Button("b"), new javafx.scene.control.CheckBox("c"),
                    new javafx.scene.control.ComboBox<String>(), new javafx.scene.control.TableView<String>(),
                    new javafx.scene.control.TabPane(), new javafx.scene.control.ScrollPane(),
                    new javafx.scene.control.Hyperlink("d"), new javafx.scene.control.ProgressBar(),
                    new javafx.scene.control.Spinner<Integer>(0, 9, 1), new javafx.scene.control.ToggleButton("e"));
            Scene escena = new Scene(raiz, 300, 300);
            aplicarCss(escena);
            raiz.applyCss();
            raiz.layout();
        } catch (RuntimeException | LinkageError e) {
            // solo es un calentamiento
        }
    }

    /** Ventana principal (pestañas) a pantalla completa */
    public static void mostrarPrincipal(Stage stage, ApplicationContext ctx, String titulo)
            throws IOException {
        Parent root = cargar(ctx, "/fxml/main.fxml");

        stage.setScene(new Scene(root));
        stage.setTitle(titulo);
        stage.setResizable(true);
        // Maximizada (no pantalla completa): se ve la barra de tareas y los diálogos no quedan detrás.
        // Mínimo 1000×600 (o lo que quepa): sirve para pantallas de 1024×768.
        Rectangle2D area = Screen.getPrimary().getVisualBounds();
        stage.setMinWidth(Math.min(ANCHO_MINIMO, area.getWidth()));
        stage.setMinHeight(Math.min(ALTO_MINIMO, area.getHeight()));
        stage.setMaximized(true);
        stage.show();
    }

    /**
     * Abre un FXML en una ventana modal y espera a que se cierre.
     * @param owner ventana padre (puede ser null)
     */
    public static void abrirModal(ApplicationContext ctx, String fxml, String titulo,
                                  Window owner, double ancho, double alto) throws IOException {
        Parent root = cargar(ctx, fxml);

        // Nunca más grande que el área visible de la pantalla (1024×768 con barra de tareas)
        double[] tam = ajustarAPantalla(ancho, alto, Screen.getPrimary().getVisualBounds());
        ancho = tam[0];
        alto = tam[1];

        Stage stage = new Stage();
        stage.setTitle(titulo);
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        aplicarIcono(stage);
        Scene scene = new Scene(root, ancho, alto);
        aplicarCss(scene);
        instalarTecladoModal(scene, stage);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(ancho, 640));
        stage.setMinHeight(Math.min(alto, 420));
        stage.showAndWait();
    }

    /**
     * Teclado uniforme en TODAS las ventanas modales FXML (un POS se usa sin soltar el teclado):
     *  - Esc cierra la ventana (salvo que un control ya lo haya usado: p. ej. para cerrar la
     *    lista de un ComboBox);
     *  - Enter en un campo de texto de una línea dispara el botón principal de la ventana
     *    (el marcado como defaultButton o, si no hay, el primero con estilo button-primary /
     *    button-success), igual que harían las ventanas de cobro. En un TextArea, botón o tabla
     *    Enter conserva su función normal.
     * Se instala como manejador (no filtro) para respetar lo que los controles ya consumieron.
     */
    public static void instalarTecladoModal(Scene scene, Stage stage) {
        scene.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) return;
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                e.consume();
                stage.close();
            } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER && !e.isShortcutDown() && !e.isShiftDown()) {
                Node foco = scene.getFocusOwner();
                boolean campoDeUnaLinea = foco instanceof javafx.scene.control.TextField
                        || foco instanceof javafx.scene.control.PasswordField;
                if (!campoDeUnaLinea) return;
                if (foco instanceof javafx.scene.control.TextField tf && tf.getOnAction() != null) return;   // ya hace algo
                Button principal = botonPrincipal(scene.getRoot());
                if (principal != null && !principal.isDisabled() && principal.isVisible()) {
                    e.consume();
                    principal.fire();
                }
            }
        });
    }

    /** El botón "principal" visible de la ventana: defaultButton, o el primero con estilo primario/éxito */
    static Button botonPrincipal(Parent raiz) {
        Button porEstilo = null;
        java.util.ArrayDeque<Node> cola = new java.util.ArrayDeque<>();
        cola.add(raiz);
        while (!cola.isEmpty()) {
            Node n = cola.poll();
            if (!n.isVisible() || !n.isManaged()) continue;
            if (n instanceof Button b) {
                if (b.isDefaultButton()) return b;
                if (porEstilo == null && (b.getStyleClass().contains("button-primary") || b.getStyleClass().contains("button-success"))) {
                    porEstilo = b;
                }
            }
            if (n instanceof Parent p) cola.addAll(p.getChildrenUnmodifiable());
            if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) cola.add(sp.getContent());
            if (n instanceof javafx.scene.control.TitledPane tp && tp.getContent() != null) cola.add(tp.getContent());
            if (n instanceof javafx.scene.control.TabPane tabs && tabs.getSelectionModel().getSelectedItem() != null
                    && tabs.getSelectionModel().getSelectedItem().getContent() != null) {
                cola.add(tabs.getSelectionModel().getSelectedItem().getContent());
            }
        }
        return porEstilo;
    }

    /**
     * Toma lo escrito a mano en el campo de un Spinner. Si no es un número,
     * en vez de la NumberFormatException cruda al hilo de JavaFX lanza una
     * NegocioException con el nombre del campo, que la pantalla ya sabe mostrar.
     */
    public static void confirmarSpinner(javafx.scene.control.Spinner<?> spinner, String campo) {
        try {
            spinner.commitValue();
        } catch (RuntimeException e) {
            throw new me.eliassanchezfernandez.puntodeventa.service.NegocioException(
                    "\"" + campo + "\" debe ser un número entero (ej: " + spinner.getValue() + ").");
        }
    }

    static final double ANCHO_MINIMO = 1000;
    static final double ALTO_MINIMO = 600;
    /** Fracción del área visible que puede ocupar una ventana modal (deja margen para el título y la barra de tareas) */
    static final double FRACCION_MAXIMA_ANCHO = 0.96;
    static final double FRACCION_MAXIMA_ALTO = 0.92;

    /** Recorta el tamaño pedido para que quepa en el área visible; nunca lo agranda */
    static double[] ajustarAPantalla(double ancho, double alto, Rectangle2D visible) {
        return new double[] {
                Math.min(ancho, Math.floor(visible.getWidth() * FRACCION_MAXIMA_ANCHO)),
                Math.min(alto, Math.floor(visible.getHeight() * FRACCION_MAXIMA_ALTO)) };
    }

    // ── Diálogos ──────────────────────────────────────────────────────────

    /** Aplica app.css a un Alert/Dialog (no heredan los estilos de la escena) */
    public static void estilizar(Dialog<?> dialog) {
        URL css = Ventanas.class.getResource(CSS_APP);
        if (css != null && !dialog.getDialogPane().getStylesheets().contains(css.toExternalForm())) {
            dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        }
        Scene escena = dialog.getDialogPane().getScene();
        if (escena != null && escena.getWindow() instanceof Stage s) aplicarIcono(s);
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

    /** Pide un texto obligatorio (motivo, observación). Vacío si se cancela. */
    public static Optional<String> pedirTexto(String titulo, String encabezado, String prompt) {
        Dialog<String> dlg = new Dialog<>();
        dlg.setTitle(titulo);
        dlg.setHeaderText(encabezado);
        TextArea txt = new TextArea();
        txt.setPromptText(prompt);
        txt.setPrefRowCount(3);
        txt.setWrapText(true);
        VBox caja = new VBox(8, txt);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? txt.getText().trim() : null);
        estilizar(dlg);
        Platform.runLater(txt::requestFocus);
        return dlg.showAndWait().filter(s -> !s.isEmpty());
    }

    /**
     * Diálogo de autorización con clave de administrador (USR-19). Permite
     * hasta 3 intentos; cada fallo cuenta para el bloqueo del usuario.
     * @return la autorización concedida, o vacío si se canceló o falló
     */
    public static Optional<Autorizacion> pedirAutorizacion(AutorizacionService servicio, Permiso permiso) {
        Dialog<Autorizacion> dlg = new Dialog<>();
        dlg.setTitle("Autorización de administrador");
        dlg.setHeaderText("Se requiere: " + permiso.getDescripcion()
                + "\nUn administrador debe capturar su usuario y contraseña.");

        TextField txtUsuario = new TextField();
        txtUsuario.setPromptText("Usuario administrador");
        PasswordField txtClave = new PasswordField();
        txtClave.setPromptText("Contraseña");
        Label lblError = new Label();
        lblError.getStyleClass().add("field-error-label");
        lblError.setWrapText(true);
        lblError.setVisible(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Usuario:"), txtUsuario);
        grid.addRow(1, new Label("Contraseña:"), txtClave);
        grid.add(lblError, 0, 2, 2, 1);
        dlg.getDialogPane().setContent(grid);

        ButtonType autorizar = new ButtonType("Autorizar", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(autorizar, ButtonType.CANCEL);
        estilizar(dlg);

        final int[] intentos = {0};
        final Autorizacion[] resultado = {null};
        javafx.scene.control.Button btn = (javafx.scene.control.Button) dlg.getDialogPane().lookupButton(autorizar);
        btn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                resultado[0] = servicio.autorizar(txtUsuario.getText(), txtClave.getText(), permiso);
            } catch (NegocioException e) {
                intentos[0]++;
                resultado[0] = null;
                lblError.setText(e.getMessage() + (intentos[0] >= 3 ? "" : "  (intento " + intentos[0] + " de 3)"));
                lblError.setVisible(true);
                txtClave.clear();
                if (intentos[0] >= 3) {
                    return;              // tercer fallo: el diálogo se cierra sin autorizar
                }
                ev.consume();            // mantener el diálogo abierto
                txtClave.requestFocus();
            }
        });
        dlg.setResultConverter(b -> b == autorizar ? resultado[0] : null);
        Platform.runLater(txtUsuario::requestFocus);
        return dlg.showAndWait().filter(a -> a != null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Aplica app.css a una escena creada a mano (ventanas sin FXML, como el tutorial y la donación) */
    public static void aplicarCss(Scene scene) {
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
