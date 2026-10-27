package me.eliassanchezfernandez.puntodeventa.util;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Pantallas que existen ANTES (o en lugar) del contexto de Spring, por eso son
 * JavaFX puro sin FXML ni servicios:
 *
 *  - Primera vez: "¿Cómo se usará esta caja?" (una sola caja con archivo local,
 *    o varias cajas contra un servidor PostgreSQL) y el número de caja.
 *  - Sin conexión: el servidor no respondió al arrancar. Se explica en español,
 *    con "Reintentar", "Cambiar conexión…" (pide la contraseña del administrador,
 *    comparada con el hash guardado localmente la última vez que un administrador
 *    entró en esta caja) y "Salir".
 *
 * Nunca se cambia solo a SQLite cuando falla el servidor: las ventas quedarían
 * repartidas en dos bases. Si en el futuro se quiere vender sin red habrá que
 * hacer una "cola fuera de línea" (guardar las ventas localmente y reenviarlas
 * al servidor al reconectar, con folios reservados por caja); está documentado
 * en docs/varias-cajas.md.
 */
public final class PantallaConexion {

    private PantallaConexion() { }

    // ── Primera vez ───────────────────────────────────────────────────────

    /**
     * Pregunta cómo se usará esta caja y devuelve la configuración elegida (ya
     * guardada en la carpeta de datos). Si el usuario cierra el diálogo se asume
     * "una sola caja", que es lo que el programa hacía siempre.
     */
    public static ConexionLocal elegirPrimeraVez(Stage owner, java.nio.file.Path carpeta) {
        Dialog<ConexionLocal> dlg = new Dialog<>();
        dlg.setTitle("Punto de Venta · primera vez");
        dlg.setHeaderText("¿Cómo se usará esta caja?");
        if (owner != null && owner.isShowing()) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        ToggleGroup grupo = new ToggleGroup();
        RadioButton rbUna = new RadioButton("Una sola caja: los datos se guardan en este equipo (recomendado para empezar)");
        RadioButton rbVarias = new RadioButton("Varias cajas: este equipo se conecta a un servidor PostgreSQL de la tienda");
        rbUna.setToggleGroup(grupo);
        rbVarias.setToggleGroup(grupo);
        rbUna.setSelected(true);
        rbUna.setWrapText(true);
        rbVarias.setWrapText(true);
        FormularioServidor form = new FormularioServidor(new ConexionLocal());
        form.setDisable(true);
        grupo.selectedToggleProperty().addListener((o, a, b) -> form.setDisable(b != rbVarias));
        Label nota = new Label("Puedes cambiarlo después en Configuración → Base de datos (solo el administrador).");
        nota.getStyleClass().add("hint");
        nota.setWrapText(true);
        VBox caja = new VBox(10, rbUna, rbVarias, form, nota);
        caja.setPadding(new Insets(10));
        caja.setPrefWidth(560);
        dlg.getDialogPane().setContent(caja);
        ButtonType continuar = new ButtonType("Continuar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().add(continuar);
        Button btn = (Button) dlg.getDialogPane().lookupButton(continuar);
        btn.getStyleClass().add("button-primary");
        btn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (rbVarias.isSelected()) {
                try {
                    form.leer().validarServidor();
                } catch (IllegalArgumentException e) {
                    form.mostrar(e.getMessage(), false);
                    ev.consume();
                }
            }
        });
        dlg.setResultConverter(b -> {
            if (b != continuar) return null;
            ConexionLocal c = form.leer();
            c.setModo(rbVarias.isSelected() ? PerfilBaseDatos.POSTGRESQL : PerfilBaseDatos.SQLITE);
            return c;
        });
        ConexionLocal elegida = dlg.showAndWait().orElseGet(ConexionLocal::new);
        try {
            elegida.guardar(carpeta);
        } catch (IOException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Configuración",
                    "No se pudo guardar la configuración de la caja (" + e.getMessage() + "). Se usará una sola caja por ahora.");
            elegida = new ConexionLocal();
        }
        return elegida;
    }

    // ── Sin conexión con el servidor ──────────────────────────────────────

    /**
     * Muestra en el Stage la pantalla de error de conexión.
     * @param reintentar  se llama (en el hilo de JavaFX) al pulsar Reintentar
     * @param alCambiar   recibe la configuración nueva ya guardada, para reintentar con ella
     */
    public static void mostrarSinConexion(Stage stage, ConexionLocal conexion, Throwable causa,
                                          Runnable reintentar, Consumer<ConexionLocal> alCambiar) {
        Label titulo = new Label("No hay conexión con el servidor de la tienda");
        titulo.getStyleClass().add("form-section-title");
        titulo.setWrapText(true);
        Label detalle = new Label("Esta caja (" + conexion.getCajaNombre() + ") está configurada para trabajar contra el servidor "
                + conexion.getHost() + ":" + conexion.getPuerto() + ", base \"" + conexion.getBaseDatos() + "\", y no respondió.\n\n"
                + "Revisa que el equipo servidor esté encendido, que esté conectado a la misma red y que PostgreSQL esté corriendo. "
                + "Mientras tanto no es posible vender desde esta caja: el programa no cambia solo al archivo local para que las "
                + "ventas no queden repartidas en dos bases de datos.");
        detalle.setWrapText(true);
        Label tecnico = new Label("Detalle técnico: " + causaRaiz(causa));
        tecnico.setWrapText(true);
        tecnico.getStyleClass().add("hint");
        ProgressIndicator espera = new ProgressIndicator();
        espera.setVisible(false);
        espera.setPrefSize(28, 28);
        Label estado = new Label();
        estado.getStyleClass().add("hint");

        Button btnReintentar = new Button("Reintentar");
        btnReintentar.getStyleClass().add("button-primary");
        btnReintentar.setDefaultButton(true);
        Button btnCambiar = new Button("Cambiar conexión…");
        Button btnSalir = new Button("Salir");
        btnSalir.getStyleClass().add("button-danger");
        HBox botones = new HBox(10, btnReintentar, btnCambiar, btnSalir, espera, estado);
        botones.setAlignment(Pos.CENTER_LEFT);

        btnReintentar.setOnAction(e -> {
            btnReintentar.setDisable(true);
            btnCambiar.setDisable(true);
            espera.setVisible(true);
            estado.setText("Conectando con el servidor…");
            reintentar.run();
        });
        btnCambiar.setOnAction(e -> {
            if (!pedirClaveAdmin(stage, conexion)) return;
            Optional<ConexionLocal> nueva = editarConexion(stage, conexion);
            if (nueva.isEmpty()) return;
            btnReintentar.setDisable(true);
            btnCambiar.setDisable(true);
            espera.setVisible(true);
            estado.setText("Conectando con el servidor…");
            alCambiar.accept(nueva.get());
        });
        btnSalir.setOnAction(e -> Platform.exit());

        VBox raiz = new VBox(14, titulo, detalle, tecnico, botones);
        raiz.setPadding(new Insets(24));
        raiz.getStyleClass().add("form-panel");
        Scene scene = new Scene(raiz, 640, 360);
        java.net.URL css = PantallaConexion.class.getResource("/css/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setFullScreen(false);
        stage.setMaximized(false);
        stage.setScene(scene);
        stage.setTitle("Punto de Venta · sin conexión");
        stage.setResizable(true);
        stage.sizeToScene();
        stage.centerOnScreen();
        stage.show();
    }

    /** Mensaje de la causa más profunda (la del driver), para no mostrar la cadena entera de Spring */
    public static String causaRaiz(Throwable t) {
        if (t == null) return "sin detalle";
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        if (msg == null || msg.isBlank()) msg = c.getClass().getSimpleName();
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }

    /**
     * Contraseña del administrador comparada con el hash guardado localmente
     * (ConexionLocal.adminHash). Sin hash guardado (ningún administrador ha
     * entrado aún en esta caja) no hay nada que proteger y se permite.
     */
    static boolean esClaveAdminValida(String hash, String clave) {
        if (hash == null || hash.isBlank()) return true;
        if (clave == null || clave.isEmpty()) return false;
        try {
            return new BCryptPasswordEncoder().matches(clave, hash);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean pedirClaveAdmin(Stage owner, ConexionLocal conexion) {
        if (conexion.getAdminHash() == null) return true;
        Dialog<String> dlg = new Dialog<>();
        dlg.setTitle("Contraseña del administrador");
        dlg.setHeaderText("Para cambiar la conexión escribe la contraseña del administrador\n(la última con la que entró en esta caja).");
        if (owner != null && owner.isShowing()) dlg.initOwner(owner);
        PasswordField txt = new PasswordField();
        txt.setPromptText("Contraseña");
        VBox caja = new VBox(8, txt);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? txt.getText() : null);
        Ventanas.estilizar(dlg);
        Platform.runLater(txt::requestFocus);
        Optional<String> clave = dlg.showAndWait();
        if (clave.isEmpty()) return false;
        if (!esClaveAdminValida(conexion.getAdminHash(), clave.get())) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Contraseña incorrecta", "La contraseña del administrador no es correcta.");
            return false;
        }
        return true;
    }

    /** Formulario de servidor y caja; guarda el archivo y devuelve la configuración nueva */
    private static Optional<ConexionLocal> editarConexion(Stage owner, ConexionLocal actual) {
        Dialog<ConexionLocal> dlg = new Dialog<>();
        dlg.setTitle("Conexión al servidor");
        dlg.setHeaderText("Datos del servidor PostgreSQL de la tienda");
        if (owner != null && owner.isShowing()) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);
        FormularioServidor form = new FormularioServidor(actual);
        VBox caja = new VBox(10, form);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        ButtonType guardar = new ButtonType("Guardar y reintentar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);
        Button btn = (Button) dlg.getDialogPane().lookupButton(guardar);
        btn.getStyleClass().add("button-primary");
        btn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                form.leer().validarServidor();
            } catch (IllegalArgumentException e) {
                form.mostrar(e.getMessage(), false);
                ev.consume();
            }
        });
        dlg.setResultConverter(b -> b == guardar ? form.leer() : null);
        Optional<ConexionLocal> nueva = dlg.showAndWait();
        if (nueva.isEmpty()) return Optional.empty();
        ConexionLocal c = nueva.get();
        c.setModo(PerfilBaseDatos.POSTGRESQL);
        c.setAdminHash(actual.getAdminHash());
        try {
            c.guardar(RutasApp.carpetaAplicacion());
        } catch (IOException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Conexión", "No se pudo guardar la configuración: " + e.getMessage());
            return Optional.empty();
        }
        return Optional.of(c);
    }

    // ── Prueba de conexión sin Spring ─────────────────────────────────────

    /** Conecta con el servidor descrito y devuelve su versión; lanza SQLException si no se pudo */
    static String probar(ConexionLocal c) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", c.getUsuario());
        props.setProperty("password", c.getContrasena());
        props.setProperty("connectTimeout", "5");
        props.setProperty("loginTimeout", "5");
        try (Connection con = DriverManager.getConnection(c.urlPostgresql(), props);
             java.sql.Statement st = con.createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT version()")) {
            return rs.next() ? rs.getString(1) : "PostgreSQL";
        }
    }

    /** Campos del servidor y de la caja (compartidos por la primera vez y por "Cambiar conexión") */
    static final class FormularioServidor extends VBox {
        private final TextField txtHost = new TextField();
        private final TextField txtPuerto = new TextField();
        private final TextField txtBase = new TextField();
        private final TextField txtUsuario = new TextField();
        private final PasswordField txtContrasena = new PasswordField();
        private final Spinner<Integer> spnCaja = new Spinner<>(1, 999, 1);
        private final TextField txtNombre = new TextField();
        private final Label lblEstado = new Label();
        private final ConexionLocal base;

        FormularioServidor(ConexionLocal c) {
            super(8);
            this.base = c;
            txtHost.setText(c.getHost());
            txtHost.setPromptText("ej. 192.168.1.10");
            txtPuerto.setText(String.valueOf(c.getPuerto()));
            txtPuerto.setPrefWidth(90);
            txtPuerto.setMaxWidth(90);
            txtBase.setText(c.getBaseDatos());
            txtUsuario.setText(c.getUsuario());
            txtContrasena.setText(c.getContrasena());
            spnCaja.setEditable(true);
            spnCaja.getValueFactory().setValue(c.getCajaNumero());
            spnCaja.setPrefWidth(90);
            txtNombre.setText(c.getCajaNombre());
            txtNombre.setPromptText("ej. Caja 1, Mostrador");
            Button btnProbar = new Button("Probar conexión");
            btnProbar.setOnAction(e -> probarEnSegundoPlano(btnProbar));
            lblEstado.setWrapText(true);
            lblEstado.getStyleClass().add("hint");
            GridPane g = new GridPane();
            g.setHgap(10);
            g.setVgap(6);
            g.addRow(0, new Label("Servidor (IP o nombre):"), txtHost);
            g.addRow(1, new Label("Puerto:"), txtPuerto);
            g.addRow(2, new Label("Base de datos:"), txtBase);
            g.addRow(3, new Label("Usuario:"), txtUsuario);
            g.addRow(4, new Label("Contraseña:"), txtContrasena);
            g.add(btnProbar, 1, 5);
            g.addRow(6, new Label("Número de esta caja:"), spnCaja);
            g.addRow(7, new Label("Nombre de esta caja:"), txtNombre);
            g.add(lblEstado, 0, 8, 2, 1);
            getChildren().add(g);
        }

        ConexionLocal leer() {
            ConexionLocal c = base.copia();
            c.setHost(txtHost.getText());
            try {
                c.setPuerto(Integer.parseInt(txtPuerto.getText().trim()));
            } catch (NumberFormatException e) {
                c.setPuerto(ConexionLocal.PUERTO_PG_POR_DEFECTO);
            }
            c.setBaseDatos(txtBase.getText());
            c.setUsuario(txtUsuario.getText());
            c.setContrasena(txtContrasena.getText());
            try {
                spnCaja.commitValue();
            } catch (RuntimeException ignorada) { /* texto inválido: se queda el valor anterior */ }
            c.setCajaNumero(spnCaja.getValue());
            c.setCajaNombre(txtNombre.getText());
            return c;
        }

        void mostrar(String texto, boolean bien) {
            lblEstado.setText(texto);
            lblEstado.getStyleClass().removeAll("field-error-label", "text-success");
            lblEstado.getStyleClass().add(bien ? "text-success" : "field-error-label");
        }

        private void probarEnSegundoPlano(Button boton) {
            ConexionLocal c = leer();
            c.setModo(PerfilBaseDatos.POSTGRESQL);
            try {
                c.validarServidor();
            } catch (IllegalArgumentException e) {
                mostrar(e.getMessage(), false);
                return;
            }
            boton.setDisable(true);
            mostrar("Conectando con " + c.getHost() + ":" + c.getPuerto() + "…", true);
            Thread t = new Thread(() -> {
                String texto;
                boolean bien;
                try {
                    texto = "Conexión correcta. " + probar(c);
                    bien = true;
                } catch (SQLException e) {
                    texto = me.eliassanchezfernandez.puntodeventa.service.ConexionService.explicar(e, c);
                    bien = false;
                } catch (RuntimeException e) {
                    texto = "No se pudo probar la conexión: " + e.getMessage();
                    bien = false;
                }
                final String msg = texto;
                final boolean ok = bien;
                Platform.runLater(() -> {
                    boton.setDisable(false);
                    mostrar(msg, ok);
                });
            }, "probar-conexion");
            t.setDaemon(true);
            t.start();
        }
    }
}
