package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.service.BaseDatosService;
import me.eliassanchezfernandez.puntodeventa.service.ConexionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Base de datos (Configuración): ruta o servidor, tamaño y conteo de
 * registros; modo de trabajo de esta caja (una sola caja con SQLite o varias
 * cajas contra un servidor PostgreSQL) con "Probar conexión"; y "Respaldar
 * ahora" / "Restaurar respaldo" (solo en SQLite: en modo servidor el respaldo
 * se hace en el servidor con pg_dump). Solo ADMIN; las reglas viven en
 * BaseDatosService y ConexionService.
 */
@Component
@Scope("prototype")
public class ConfiguracionBaseDatosController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private Label      lblRutaTitulo;
    @FXML private Label      lblRuta;
    @FXML private Label      lblTamano;
    @FXML private Label      lblPendiente;
    @FXML private GridPane   gridRegistros;

    // Cajas y servidor
    @FXML private Label         lblModoActual;
    @FXML private ToggleGroup   grupoModo;
    @FXML private RadioButton   rbUnaCaja;
    @FXML private RadioButton   rbVariasCajas;
    @FXML private GridPane      gridServidor;
    @FXML private TextField     txtHost;
    @FXML private TextField     txtPuerto;
    @FXML private TextField     txtBase;
    @FXML private TextField     txtUsuario;
    @FXML private PasswordField txtContrasena;
    @FXML private Button        btnProbar;
    @FXML private Label         lblPrueba;
    @FXML private Spinner<Integer> spnCaja;
    @FXML private TextField     txtCajaNombre;
    @FXML private Button        btnGuardarConexion;
    @FXML private Label         lblMigrarHint;
    @FXML private Button        btnMigrar;

    // Respaldo
    @FXML private Label    lblRespaldoHint;
    @FXML private TextArea txtRespaldoServidor;
    @FXML private Button   btnRespaldar;
    @FXML private Label    lblRestaurarHint;
    @FXML private Button   btnRestaurar;

    @Autowired private BaseDatosService baseDatosService;
    @Autowired private ConexionService  conexionService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.MigracionServidorService migracionService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        spnCaja.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 999, 1));
        cargar();
        cargarConexion();
    }

    // ── Cajas y servidor ──────────────────────────────────────────────────

    private void cargarConexion() {
        ConexionLocal c = conexionService.actual();
        PerfilBaseDatos enUso = conexionService.perfilEnUso();
        lblModoActual.setText("Ahora mismo este programa trabaja "
                + (enUso.esSqlite() ? "con una sola caja (archivo SQLite local)." : "contra el servidor PostgreSQL (varias cajas).")
                + "  Esta es la " + conexionService.identidad().nombre() + ".");
        (c.esPostgresql() ? rbVariasCajas : rbUnaCaja).setSelected(true);
        txtHost.setText(c.getHost());
        txtPuerto.setText(String.valueOf(c.getPuerto()));
        txtBase.setText(c.getBaseDatos());
        txtUsuario.setText(c.getUsuario());
        txtContrasena.setText(c.getContrasena());
        spnCaja.getValueFactory().setValue(c.getCajaNumero());
        txtCajaNombre.setText(c.getCajaNombre());
        lblPrueba.setText("");
        // El asistente de migración solo tiene sentido mientras el programa trabaja con el archivo local
        boolean sqlite = enUso.esSqlite();
        btnMigrar.setVisible(sqlite);
        btnMigrar.setManaged(sqlite);
        lblMigrarHint.setVisible(sqlite);
        lblMigrarHint.setManaged(sqlite);
        onCambiarModo();
    }

    /** Asistente "Migrar datos de esta caja al servidor" (ADMIN): copia el archivo local a una base vacía y cambia el modo */
    @FXML
    void onMigrar() {
        ConexionLocal destino;
        try {
            destino = leerFormulario();
            destino.setModo(PerfilBaseDatos.POSTGRESQL);
            destino.validarServidor();
        } catch (NegocioException | IllegalArgumentException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Migrar al servidor", "Captura primero los datos del servidor: " + e.getMessage());
            return;
        }
        if (!Ventanas.confirmar("Migrar datos de esta caja al servidor",
                "Se copiarán TODOS los datos del archivo local (productos, ventas, usuarios, cortes, bitácoras...) a la base "
                + "\"" + destino.getBaseDatos() + "\" del servidor " + destino.getHost() + ":" + destino.getPuerto() + ".\n\n"
                + "La base del servidor debe estar VACÍA (recién creada). El archivo local no se modifica y queda como respaldo.\n"
                + "Al terminar, esta caja quedará configurada como \"Varias cajas\" y habrá que reiniciar el programa.\n\n"
                + "Mientras se copia, no hagas ventas en esta caja. ¿Continuar?")) {
            return;
        }
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Migrando al servidor");
        dlg.setHeaderText("Copiando los datos al servidor…");
        javafx.scene.control.ProgressBar barra = new javafx.scene.control.ProgressBar(-1);
        barra.setPrefWidth(420);
        Label lblEstado = new Label("Conectando con el servidor…");
        lblEstado.setWrapText(true);
        VBox caja = new VBox(10, barra, lblEstado);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dlg.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);   // no se puede cancelar a medias
        Ventanas.estilizar(dlg);
        if (raiz.getScene() != null) dlg.initOwner(raiz.getScene().getWindow());

        Thread t = new Thread(() -> {
            String error = null;
            me.eliassanchezfernandez.puntodeventa.service.MigracionServidorService.Resultado resultado = null;
            try {
                resultado = migracionService.migrarYCambiarModo(destino, p -> Platform.runLater(() -> {
                    if (p.total() > 0) barra.setProgress(Math.min(1.0, (p.indice() - (p.filas() > 0 ? 0.5 : 1.0)) / p.total() + 0.5 / p.total()));
                    lblEstado.setText(p.mensaje());
                }));
            } catch (NegocioException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                error = "Error inesperado: " + e.getMessage();
            }
            final String msg = error;
            final var res = resultado;
            Platform.runLater(() -> {
                dlg.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
                dlg.close();
                if (msg != null) {
                    Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo migrar", msg);
                    return;
                }
                cargarConexion();
                Ventanas.alerta(Alert.AlertType.INFORMATION, "Migración terminada", String.format(
                        "Se copiaron %d tablas (%d filas) al servidor.%s\n\nEsta caja quedó configurada como \"Varias cajas\". "
                        + "Cierra y vuelve a abrir el programa para trabajar contra el servidor. El archivo local sigue en su "
                        + "lugar como respaldo.", res.tablas(), res.filas(),
                        res.omitidas().isEmpty() ? "" : "\nTablas del archivo que ya no usa el programa y no se copiaron: "
                                + String.join(", ", res.omitidas()) + "."));
            });
        }, "migracion-servidor");
        t.setDaemon(true);
        t.start();
        dlg.showAndWait();
    }

    @FXML
    void onCambiarModo() {
        boolean servidor = rbVariasCajas.isSelected();
        gridServidor.setDisable(!servidor);
    }

    /** Lee el formulario; lanza NegocioException si algo no es válido */
    private ConexionLocal leerFormulario() {
        ConexionLocal c = conexionService.actual().copia();
        c.setModo(rbVariasCajas.isSelected() ? PerfilBaseDatos.POSTGRESQL : PerfilBaseDatos.SQLITE);
        c.setHost(txtHost.getText());
        try {
            c.setPuerto(Integer.parseInt(txtPuerto.getText().trim()));
        } catch (NumberFormatException e) {
            throw new NegocioException("El puerto debe ser un número (normalmente 5432).");
        }
        c.setBaseDatos(txtBase.getText());
        c.setUsuario(txtUsuario.getText());
        c.setContrasena(txtContrasena.getText());
        try {
            spnCaja.commitValue();
        } catch (RuntimeException ignorada) { /* texto inválido: se queda el valor anterior */ }
        c.setCajaNumero(spnCaja.getValue());
        c.setCajaNombre(txtCajaNombre.getText());
        return c;
    }

    @FXML
    void onProbarConexion() {
        ConexionLocal c;
        try {
            c = leerFormulario();
            c.setModo(PerfilBaseDatos.POSTGRESQL);
        } catch (NegocioException e) {
            lblPrueba.setText(e.getMessage());
            return;
        }
        btnProbar.setDisable(true);
        lblPrueba.setText("Conectando con " + c.getHost() + ":" + c.getPuerto() + "…");
        Thread t = new Thread(() -> {
            String resultado;
            boolean ok;
            try {
                resultado = "Conexión correcta. " + conexionService.probar(c);
                ok = true;
            } catch (NegocioException e) {
                resultado = e.getMessage();
                ok = false;
            } catch (RuntimeException e) {
                resultado = "No se pudo probar la conexión: " + e.getMessage();
                ok = false;
            }
            final String texto = resultado;
            final boolean bien = ok;
            Platform.runLater(() -> {
                btnProbar.setDisable(false);
                lblPrueba.setText(texto);
                lblPrueba.getStyleClass().removeAll("field-error-label", "text-success");
                lblPrueba.getStyleClass().add(bien ? "text-success" : "field-error-label");
            });
        }, "probar-conexion");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    void onGuardarConexion() {
        ConexionLocal nueva;
        try {
            nueva = leerFormulario();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Conexión", e.getMessage());
            return;
        }
        if (nueva.esPostgresql() && !Ventanas.confirmar("Varias cajas",
                "Al reiniciar, esta caja dejará de usar el archivo local y trabajará contra el servidor "
                + nueva.getHost() + ":" + nueva.getPuerto() + "/" + nueva.getBaseDatos() + ".\n\n"
                + "Si esta caja ya tiene ventas en el archivo local y el servidor está vacío, usa antes "
                + "\"Migrar datos de esta caja al servidor\" para no perderlas de vista.\n\n¿Guardar?")) {
            return;
        }
        try {
            boolean reiniciar = conexionService.guardar(nueva);
            cargarConexion();
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Conexión guardada", reiniciar
                    ? "La configuración se guardó. Cierra y vuelve a abrir el programa para aplicarla."
                    : "La configuración se guardó.");
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    // ── Respaldo y restauración ───────────────────────────────────────────

    @FXML
    private void onRespaldar() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar respaldo");
        fc.setInitialFileName("respaldo-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".db");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Base de datos SQLite (*.db)", "*.db"));
        File destino = fc.showSaveDialog(raiz.getScene().getWindow());
        if (destino == null) return;
        // VACUUM INTO de una base grande en un disco duro (o a una memoria USB) tarda: fuera del hilo de JavaFX
        enSegundoPlano("Respaldando", "Creando el respaldo… En un disco lento o una memoria USB puede tardar un momento.",
                () -> baseDatosService.respaldar(destino.toPath()),
                guardado -> Ventanas.alerta(Alert.AlertType.INFORMATION, "Respaldo creado",
                        "El respaldo se guardó en:\n" + guardado + "\n\nConsejo: guarda una copia en una memoria USB u otro disco."),
                "No se pudo respaldar");
    }

    /**
     * Corre {@code trabajo} en un hilo aparte con una ventana de espera (sin cancelar: un
     * respaldo a medias no sirve) y los botones de respaldo apagados; el resultado o el
     * error vuelven al hilo de JavaFX. Un fallo inesperado se registra y se avisa sin pila.
     */
    private <T> void enSegundoPlano(String titulo, String mensaje, java.util.concurrent.Callable<T> trabajo,
                                    java.util.function.Consumer<T> alTerminar, String tituloError) {
        btnRespaldar.setDisable(true);
        btnRestaurar.setDisable(true);
        Dialog<Void> espera = new Dialog<>();
        espera.setTitle(titulo);
        espera.setHeaderText(null);
        javafx.scene.control.ProgressIndicator indicador = new javafx.scene.control.ProgressIndicator(-1);
        indicador.setPrefSize(36, 36);
        Label lblEspera = new Label(mensaje);
        lblEspera.setWrapText(true);
        lblEspera.setMaxWidth(360);
        javafx.scene.layout.HBox caja = new javafx.scene.layout.HBox(14, indicador, lblEspera);
        caja.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        caja.setPadding(new Insets(14));
        espera.getDialogPane().setContent(caja);
        espera.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        espera.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);   // no se puede dejar a medias
        Ventanas.estilizar(espera);
        if (raiz.getScene() != null) espera.initOwner(raiz.getScene().getWindow());

        javafx.concurrent.Task<T> tarea = new javafx.concurrent.Task<>() {
            @Override protected T call() throws Exception { return trabajo.call(); }
        };
        Runnable cerrarEspera = () -> {
            espera.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
            espera.close();
            btnRespaldar.setDisable(false);
            btnRestaurar.setDisable(false);
        };
        tarea.setOnSucceeded(ev -> {
            cerrarEspera.run();
            alTerminar.accept(tarea.getValue());
        });
        tarea.setOnFailed(ev -> {
            cerrarEspera.run();
            Throwable e = tarea.getException();
            if (e instanceof NegocioException) {
                Ventanas.alerta(Alert.AlertType.ERROR, tituloError, e.getMessage());
            } else {
                me.eliassanchezfernandez.puntodeventa.util.RegistroErrores.manejar(Thread.currentThread(), e);
            }
        });
        Thread t = new Thread(tarea, "base-datos-" + titulo.toLowerCase(java.util.Locale.ROOT));
        t.setDaemon(true);
        t.start();
        espera.show();   // modal respecto a la ventana de configuración; se cierra desde la tarea
    }

    @FXML
    private void onRestaurar() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elegir respaldo a restaurar");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Base de datos SQLite (*.db)", "*.db"));
        File origen = fc.showOpenDialog(raiz.getScene().getWindow());
        if (origen == null) return;

        if (!Ventanas.confirmar("Restaurar respaldo",
                "Se reemplazarán TODOS los datos actuales por los del respaldo:\n" + origen.getName()
                + "\n\nAntes se guardará una copia de seguridad de la base actual. ¿Continuar?")) {
            return;
        }
        Optional<String> clave = pedirContrasena();
        if (clave.isEmpty()) return;
        // Validar el respaldo (quick_check) y copiar la base actual tardan en un disco lento: fuera del hilo de JavaFX
        enSegundoPlano("Preparando la restauración", "Revisando el respaldo y guardando una copia de seguridad de la base actual…",
                () -> baseDatosService.restaurar(origen.toPath(), clave.get()),
                this::restauracionPreparada, "No se pudo restaurar");
    }

    /** El respaldo quedó validado y en espera: avisar y ofrecer cerrar ya (hasta reiniciar no se puede cobrar) */
    private void restauracionPreparada(Path seguridad) {
        lblPendiente.setVisible(true);
        lblPendiente.setManaged(true);
        Alert a = new Alert(Alert.AlertType.INFORMATION,
                "La restauración se aplica al volver a abrir el programa. Mientras tanto NO se puede vender "
                + "(lo que se registrara ahora no quedaría en la base restaurada).\n\n"
                + "Copia de seguridad de los datos actuales:\n" + seguridad + "\n\n¿Cerrar el programa ahora?",
                new ButtonType("Cerrar ahora", javafx.scene.control.ButtonBar.ButtonData.OK_DONE),
                new ButtonType("Después", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        a.setTitle("Respaldo listo para restaurar");
        a.setHeaderText(null);
        Ventanas.estilizar(a);
        boolean cerrar = a.showAndWait().filter(b -> b.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE).isPresent();
        if (cerrar) {
            ((Stage) raiz.getScene().getWindow()).close();
            Platform.exit();   // PuntoDeVentaApp.stop() cierra Spring; Launcher aplica el respaldo al abrir
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private void cargar() {
        try {
            BaseDatosService.Informacion info = baseDatosService.informacion();
            if (info.esSqlite()) {
                lblRutaTitulo.setText("Archivo");
                lblRuta.setText(info.archivo().toString());
            } else {
                lblRutaTitulo.setText("Servidor PostgreSQL");
                lblRuta.setText(info.servidor());
            }
            lblTamano.setText(info.bytes() >= 1024 * 1024
                    ? String.format("%.1f MB", info.bytes() / (1024.0 * 1024.0))
                    : String.format("%.1f KB", info.bytes() / 1024.0));
            gridRegistros.getChildren().clear();
            // Dos conteos por renglón: la ventana cabe en 1024×768 sin desplazarse
            int i = 0;
            for (var e : info.registros().entrySet()) {
                Label nombre = new Label(e.getKey() + ":");
                Label valor = new Label(String.valueOf(e.getValue()));
                nombre.setMinWidth(Region.USE_PREF_SIZE);   // nunca «Movimientos de inv…»: la rejilla pasa a otro renglón
                valor.setMinWidth(Region.USE_PREF_SIZE);
                gridRegistros.add(nombre, (i % 2) * 2, i / 2);
                gridRegistros.add(valor, (i % 2) * 2 + 1, i / 2);
                i++;
            }
            boolean pendiente = baseDatosService.hayRestauracionPendiente();
            lblPendiente.setVisible(pendiente);
            lblPendiente.setManaged(pendiente);

            // En modo servidor el respaldo es del servidor: se explica y se apagan los botones
            boolean sqlite = info.esSqlite();
            btnRespaldar.setDisable(!sqlite);
            btnRestaurar.setDisable(!sqlite);
            txtRespaldoServidor.setVisible(!sqlite);
            txtRespaldoServidor.setManaged(!sqlite);
            if (!sqlite) {
                lblRespaldoHint.setText("La base de datos está en el servidor PostgreSQL: el respaldo se hace en ese equipo. "
                        + "Copia estas instrucciones para quien administre el servidor.");
                lblRestaurarHint.setText("En modo servidor la restauración también se hace en el servidor (pg_restore).");
                txtRespaldoServidor.setText(baseDatosService.instruccionesRespaldoServidor());
            }
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Base de datos", e.getMessage());
        }
    }

    private Optional<String> pedirContrasena() {
        Dialog<String> dlg = new Dialog<>();
        dlg.setTitle("Contraseña del administrador");
        dlg.setHeaderText("Escribe tu contraseña para confirmar la restauración");
        PasswordField txt = new PasswordField();
        txt.setPromptText("Contraseña");
        VBox caja = new VBox(8, txt);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? txt.getText() : null);
        Ventanas.estilizar(dlg);
        return dlg.showAndWait().filter(s -> !s.isEmpty());
    }
}
