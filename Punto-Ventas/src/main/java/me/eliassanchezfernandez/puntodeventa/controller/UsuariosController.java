package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Administración de usuarios (ventana modal abierta desde Configuración → Cajeros).
 *
 * Tabla a la izquierda, formulario a la derecha. Al seleccionar un usuario se
 * carga para editarlo; "Nuevo" limpia el formulario. En edición, la contraseña
 * vacía significa "sin cambios". Todas las reglas (admin, duplicados, último
 * administrador) las aplica CajeroService.
 */
@Component
@Scope("prototype")
public class UsuariosController implements Initializable {

    @FXML private BorderPane raiz;

    // ── Tabla ────────────────────────────────────────────────────────────
    @FXML private TableView<Cajero>           tablaUsuarios;
    @FXML private TableColumn<Cajero, String> colUsuario;
    @FXML private TableColumn<Cajero, String> colNombre;
    @FXML private TableColumn<Cajero, String> colRol;
    @FXML private TableColumn<Cajero, String> colActivo;

    // ── Formulario ───────────────────────────────────────────────────────
    @FXML private Label         lblTituloForm;
    @FXML private TextField     txtUsuario;
    @FXML private TextField     txtNombre;
    @FXML private PasswordField txtContrasena;
    @FXML private Label         lblAyudaContrasena;
    @FXML private ComboBox<Cajero.Rol> comboRol;
    @FXML private CheckBox      chkActivo;
    @FXML private Button        btnEstado;

    @Autowired private CajeroService cajeroService;

    private final ObservableList<Cajero> lista = FXCollections.observableArrayList();
    /** null = modo nuevo */
    private Cajero usuarioEnEdicion = null;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colUsuario.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsuario()));
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombreCompleto()));
        colRol.setCellValueFactory(c -> new SimpleStringProperty(nombreRol(c.getValue().getRol())));
        colActivo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActivo() ? "Sí" : "No"));
        tablaUsuarios.setItems(lista);
        tablaUsuarios.setPlaceholder(new Label("No hay usuarios registrados."));

        comboRol.setItems(FXCollections.observableArrayList(Cajero.Rol.values()));
        comboRol.setConverter(new StringConverter<>() {
            @Override public String toString(Cajero.Rol r) { return nombreRol(r); }
            @Override public Cajero.Rol fromString(String s) { return null; }
        });

        tablaUsuarios.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> {
            if (sel != null) cargarEnFormulario(sel);
        });

        onNuevo();
        recargar();
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    @FXML
    private void onNuevo() {
        usuarioEnEdicion = null;
        tablaUsuarios.getSelectionModel().clearSelection();
        lblTituloForm.setText("NUEVO USUARIO");
        txtUsuario.clear();
        txtNombre.clear();
        txtContrasena.clear();
        txtContrasena.setPromptText("Mínimo " + CajeroService.LONGITUD_MINIMA_CONTRASENA + " caracteres");
        lblAyudaContrasena.setText("Obligatoria para usuarios nuevos.");
        comboRol.setValue(Cajero.Rol.CAJERO);
        chkActivo.setSelected(true);
        chkActivo.setDisable(true);   // un usuario nuevo siempre nace activo
        btnEstado.setDisable(true);
        txtUsuario.requestFocus();
    }

    @FXML
    private void onGuardar() {
        String usuario    = txtUsuario.getText();
        String nombre     = txtNombre.getText();
        String contrasena = txtContrasena.getText();
        Cajero.Rol rol    = comboRol.getValue();

        try {
            if (usuarioEnEdicion == null) {
                Cajero creado = cajeroService.crear(usuario, nombre, contrasena, rol);
                recargar();
                seleccionar(creado.getId());
                alerta(Alert.AlertType.INFORMATION, "Usuario creado",
                        "El usuario \"" + creado.getUsuario() + "\" se creó correctamente.");
            } else {
                Long id = usuarioEnEdicion.getId();
                // Datos y contraseña (si se capturó) en una sola transacción
                cajeroService.actualizar(id, usuario, nombre, rol, chkActivo.isSelected(),
                        contrasena.isEmpty() ? null : contrasena);
                recargar();
                seleccionar(id);
                alerta(Alert.AlertType.INFORMATION, "Usuario actualizado",
                        "Los cambios se guardaron correctamente."
                        + (contrasena.isEmpty() ? "" : "\nLa contraseña también fue cambiada."));
            }
            txtContrasena.clear();
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onCambiarEstado() {
        Cajero sel = tablaUsuarios.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        boolean activar = !sel.isActivo();
        try {
            cajeroService.cambiarEstado(sel.getId(), activar);
            recargar();
            seleccionar(sel.getId());
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, activar ? "No se pudo activar" : "No se pudo desactivar",
                    e.getMessage());
        }
    }

    @FXML
    private void onEliminar() {
        Cajero sel = tablaUsuarios.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona un usuario",
                    "Haz clic en un usuario de la tabla para eliminarlo.");
            return;
        }
        if (!Ventanas.confirmar("Confirmar eliminación",
                "¿Eliminar al usuario \"" + sel.getUsuario() + "\"?\n" +
                "Si solo quieres bloquear su acceso, es mejor desactivarlo.")) {
            return;
        }
        try {
            cajeroService.eliminar(sel.getId());
            recargar();
            onNuevo();
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se puede eliminar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void recargar() {
        try {
            lista.setAll(cajeroService.listar());
        } catch (NegocioException e) {
            // No es admin: bloquear toda la ventana
            raiz.setDisable(true);
            alerta(Alert.AlertType.ERROR, "Acceso denegado", e.getMessage());
        }
    }

    private void seleccionar(Long id) {
        lista.stream().filter(c -> c.getId().equals(id)).findFirst()
                .ifPresent(c -> tablaUsuarios.getSelectionModel().select(c));
    }

    private void cargarEnFormulario(Cajero c) {
        usuarioEnEdicion = c;
        lblTituloForm.setText("MODIFICAR USUARIO");
        txtUsuario.setText(c.getUsuario());
        txtNombre.setText(c.getNombreCompleto());
        txtContrasena.clear();
        txtContrasena.setPromptText("(sin cambios)");
        lblAyudaContrasena.setText("Déjala vacía para conservar la contraseña actual.");
        comboRol.setValue(c.getRol());
        chkActivo.setSelected(c.isActivo());
        chkActivo.setDisable(false);
        btnEstado.setDisable(false);
        btnEstado.setText(c.isActivo() ? "⛔ Desactivar" : "✅ Activar");
    }

    private static String nombreRol(Cajero.Rol r) {
        if (r == null) return "";
        return r == Cajero.Rol.ADMIN ? "Administrador" : "Cajero";
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
