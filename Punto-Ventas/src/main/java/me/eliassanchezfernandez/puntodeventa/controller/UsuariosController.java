package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PoliticaContrasena;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Administración de usuarios (ventana modal abierta desde Configuración → Cajeros).
 *
 * Tabla a la izquierda, formulario a la derecha con rol y permisos por
 * usuario (USR-16). El ADMIN puede todo; el GERENTE solo ve la lista y
 * desactiva cajeros/gerentes. En edición, la contraseña vacía significa
 * "sin cambios". Las reglas las aplica CajeroService.
 */
@Component
@Scope("prototype")
public class UsuariosController implements Initializable {

    private static final DateTimeFormatter FMT_HORA = DateTimeFormatter.ofPattern("HH:mm");

    @FXML private BorderPane raiz;

    // ── Tabla ────────────────────────────────────────────────────────────
    @FXML private TableView<Cajero>           tablaUsuarios;
    @FXML private TableColumn<Cajero, String> colUsuario;
    @FXML private TableColumn<Cajero, String> colNombre;
    @FXML private TableColumn<Cajero, String> colRol;
    @FXML private TableColumn<Cajero, String> colActivo;
    @FXML private TableColumn<Cajero, String> colEstado;

    // ── Toolbar ──────────────────────────────────────────────────────────
    @FXML private Button btnNuevo;
    @FXML private Button btnEstado;
    @FXML private Button btnDesbloquear;
    @FXML private Button btnEliminar;

    // ── Formulario ───────────────────────────────────────────────────────
    @FXML private VBox          vboxFormulario;
    @FXML private Label         lblTituloForm;
    @FXML private TextField     txtUsuario;
    @FXML private TextField     txtNombre;
    @FXML private PasswordField txtContrasena;
    @FXML private Label         lblAyudaContrasena;
    @FXML private ComboBox<Cajero.Rol> comboRol;
    @FXML private CheckBox      chkActivo;
    @FXML private VBox          vboxPermisos;
    @FXML private Button        btnGuardar;
    @FXML private Button        btnPermisosDelRol;

    @Autowired private CajeroService cajeroService;
    @Autowired private SesionService sesionService;

    private final ObservableList<Cajero> lista = FXCollections.observableArrayList();
    private final Map<Permiso, CheckBox> checksPermiso = new EnumMap<>(Permiso.class);
    /** null = modo nuevo */
    private Cajero usuarioEnEdicion = null;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colUsuario.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsuario()));
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombreCompleto()));
        colRol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRol().getEtiqueta()));
        colActivo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActivo() ? "Sí" : "No"));
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(estado(c.getValue())));
        tablaUsuarios.setItems(lista);
        tablaUsuarios.setPlaceholder(new Label("No hay usuarios registrados."));

        comboRol.setItems(FXCollections.observableArrayList(Cajero.Rol.values()));
        comboRol.setConverter(new StringConverter<>() {
            @Override public String toString(Cajero.Rol r) { return r == null ? "" : r.getEtiqueta(); }
            @Override public Cajero.Rol fromString(String s) { return null; }
        });
        comboRol.valueProperty().addListener((o, a, rol) -> {
            boolean admin = rol == Cajero.Rol.ADMIN;
            vboxPermisos.setDisable(admin);   // el ADMIN siempre tiene todos
            if (rol != null && (a == null || usuarioEnEdicion == null || rol != usuarioEnEdicion.getRol())) {
                marcarPermisos(Permiso.porDefecto(rol));
            }
        });

        // Un CheckBox por permiso
        for (Permiso p : Permiso.values()) {
            CheckBox chk = new CheckBox(p.getDescripcion());
            checksPermiso.put(p, chk);
            vboxPermisos.getChildren().add(chk);
        }

        tablaUsuarios.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> {
            if (sel != null) cargarEnFormulario(sel);
        });

        aplicarRolDelOperador();
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
        txtContrasena.setPromptText(PoliticaContrasena.DESCRIPCION);
        lblAyudaContrasena.setText("Obligatoria. El usuario deberá cambiarla en su primer inicio de sesión.");
        comboRol.setValue(Cajero.Rol.CAJERO);
        marcarPermisos(Permiso.porDefecto(Cajero.Rol.CAJERO));
        chkActivo.setSelected(true);
        chkActivo.setDisable(true);   // un usuario nuevo siempre nace activo
        btnEstado.setDisable(true);
        btnDesbloquear.setDisable(true);
        txtUsuario.requestFocus();
    }

    @FXML
    private void onPermisosDelRol() {
        marcarPermisos(Permiso.porDefecto(comboRol.getValue()));
    }

    @FXML
    private void onGuardar() {
        String usuario    = txtUsuario.getText();
        String nombre     = txtNombre.getText();
        String contrasena = txtContrasena.getText();
        Cajero.Rol rol    = comboRol.getValue();
        Set<Permiso> permisos = permisosMarcados();

        try {
            if (usuarioEnEdicion == null) {
                Cajero creado = cajeroService.crear(usuario, nombre, contrasena, rol, permisos);
                recargar();
                seleccionar(creado.getId());
                alerta(Alert.AlertType.INFORMATION, "Usuario creado",
                        "El usuario \"" + creado.getUsuario() + "\" se creó correctamente.\n"
                        + "Deberá cambiar su contraseña al iniciar sesión por primera vez.");
            } else {
                Long id = usuarioEnEdicion.getId();
                // Datos, permisos y contraseña (si se capturó) en una sola transacción
                cajeroService.actualizar(id, usuario, nombre, rol, chkActivo.isSelected(),
                        contrasena.isEmpty() ? null : contrasena, permisos);
                recargar();
                seleccionar(id);
                alerta(Alert.AlertType.INFORMATION, "Usuario actualizado",
                        "Los cambios se guardaron correctamente."
                        + (contrasena.isEmpty() ? "" : "\nLa contraseña fue restablecida; deberá cambiarla al entrar."));
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
    private void onDesbloquear() {
        Cajero sel = tablaUsuarios.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        try {
            cajeroService.desbloquear(sel.getId());
            recargar();
            seleccionar(sel.getId());
            alerta(Alert.AlertType.INFORMATION, "Usuario desbloqueado",
                    "\"" + sel.getUsuario() + "\" ya puede volver a iniciar sesión.");
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo desbloquear", e.getMessage());
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

    /** El GERENTE solo consulta y desactiva; el formulario queda bloqueado */
    private void aplicarRolDelOperador() {
        boolean admin = sesionService.esAdmin();
        vboxFormulario.setDisable(!admin);
        btnNuevo.setDisable(!admin);
        btnEliminar.setDisable(!admin);
        btnGuardar.setDisable(!admin);
        btnDesbloquear.setVisible(admin);
        btnDesbloquear.setManaged(admin);
    }

    private void recargar() {
        try {
            lista.setAll(cajeroService.listar());
        } catch (NegocioException e) {
            // No es admin ni gerente: bloquear toda la ventana
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
        lblAyudaContrasena.setText("Déjala vacía para conservar la contraseña actual. " + PoliticaContrasena.DESCRIPCION);
        comboRol.setValue(c.getRol());
        marcarPermisos(c.getPermisosEfectivos());
        chkActivo.setSelected(c.isActivo());
        chkActivo.setDisable(false);
        boolean admin = sesionService.esAdmin();
        boolean gerentePuedeDesactivar = sesionService.esGerente() && c.isActivo()
                && c.getRol() != Cajero.Rol.ADMIN;
        btnEstado.setDisable(!(admin || gerentePuedeDesactivar));
        btnEstado.setText(c.isActivo() ? "⛔ Desactivar" : "✅ Activar");
        btnDesbloquear.setDisable(!c.isBloqueado());
    }

    private void marcarPermisos(Set<Permiso> permisos) {
        for (Map.Entry<Permiso, CheckBox> e : checksPermiso.entrySet()) {
            e.getValue().setSelected(permisos != null && permisos.contains(e.getKey()));
        }
    }

    private Set<Permiso> permisosMarcados() {
        Set<Permiso> s = EnumSet.noneOf(Permiso.class);
        checksPermiso.forEach((p, chk) -> { if (chk.isSelected()) s.add(p); });
        return s;
    }

    private static String estado(Cajero c) {
        if (c.isBloqueado()) return "Bloqueado hasta " + c.getBloqueadoHasta().format(FMT_HORA);
        if (c.isDebeCambiarContrasena()) return "Debe cambiar contraseña";
        return "OK";
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
