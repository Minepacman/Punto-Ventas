package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Catálogo de proveedores (ventana modal de Inventario). Requiere
 * GESTIONAR_PROVEEDORES; sin él la pantalla solo avisa y no consulta nada.
 */
@Component
@Scope("prototype")
public class ProveedoresController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private CheckBox chkVerInactivos;
    @FXML private Label lblAviso;
    @FXML private TableView<Proveedor> tabla;
    @FXML private TableColumn<Proveedor, String> colNombre;
    @FXML private TableColumn<Proveedor, String> colContacto;
    @FXML private TableColumn<Proveedor, String> colTelefono;
    @FXML private TableColumn<Proveedor, String> colVisita;
    @FXML private TableColumn<Proveedor, String> colEstado;
    @FXML private Label lblTitulo;
    @FXML private TextField txtNombre;
    @FXML private TextField txtRfc;
    @FXML private TextField txtTelefono;
    @FXML private TextField txtContacto;
    @FXML private CheckBox chkLun, chkMar, chkMie, chkJue, chkVie, chkSab, chkDom;
    @FXML private TextArea txtNotas;
    @FXML private Button btnGuardar;
    @FXML private Button btnActivo;

    @Autowired private ProveedorService proveedorService;
    @Autowired private SesionService sesionService;

    private final ObservableList<Proveedor> lista = FXCollections.observableArrayList();
    private Map<DayOfWeek, CheckBox> checks;
    private Proveedor seleccionado;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        checks = Map.of(DayOfWeek.MONDAY, chkLun, DayOfWeek.TUESDAY, chkMar, DayOfWeek.WEDNESDAY, chkMie,
                DayOfWeek.THURSDAY, chkJue, DayOfWeek.FRIDAY, chkVie, DayOfWeek.SATURDAY, chkSab, DayOfWeek.SUNDAY, chkDom);
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));
        colContacto.setCellValueFactory(c -> new SimpleStringProperty(nz(c.getValue().getContacto())));
        colTelefono.setCellValueFactory(c -> new SimpleStringProperty(nz(c.getValue().getTelefono())));
        colVisita.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().etiquetaDias()));
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActivo() ? "Activo" : "Desactivado"));
        tabla.setItems(lista);
        tabla.setPlaceholder(new Label("Aún no hay proveedores. Captura el primero a la derecha."));
        tabla.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> { if (p != null) mostrar(p); });
        tabla.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override protected void updateItem(Proveedor p, boolean empty) {
                super.updateItem(p, empty);
                setStyle(!empty && p != null && !p.isActivo() ? "-fx-opacity: 0.55;" : "");
            }
        });

        if (!sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES)) {
            raiz.setDisable(true);
            lblAviso.setText("Requiere el permiso «" + Permiso.GESTIONAR_PROVEEDORES.getDescripcion() + "».");
            return;
        }
        nuevo();
        recargar();
    }

    private void recargar() {
        try {
            List<Proveedor> todos = proveedorService.listarTodos();
            lista.setAll(chkVerInactivos.isSelected() ? todos : todos.stream().filter(Proveedor::isActivo).toList());
            if (seleccionado != null && seleccionado.getId() != null) {
                lista.stream().filter(p -> p.getId().equals(seleccionado.getId())).findFirst()
                        .ifPresent(p -> tabla.getSelectionModel().select(p));
            }
        } catch (NegocioException e) {
            lista.clear();
            Ventanas.alerta(Alert.AlertType.WARNING, "Proveedores", e.getMessage());
        }
    }

    @FXML private void onFiltro() { recargar(); }

    @FXML private void onNuevo() {
        tabla.getSelectionModel().clearSelection();
        nuevo();
        txtNombre.requestFocus();
    }

    private void nuevo() {
        seleccionado = null;
        lblTitulo.setText("NUEVO PROVEEDOR");
        txtNombre.clear();
        txtRfc.clear();
        txtTelefono.clear();
        txtContacto.clear();
        txtNotas.clear();
        checks.values().forEach(c -> c.setSelected(false));
        btnActivo.setVisible(false);
        btnActivo.setManaged(false);
    }

    private void mostrar(Proveedor p) {
        seleccionado = p;
        lblTitulo.setText("EDITAR PROVEEDOR");
        txtNombre.setText(p.getNombre());
        txtRfc.setText(nz(p.getRfc()));
        txtTelefono.setText(nz(p.getTelefono()));
        txtContacto.setText(nz(p.getContacto()));
        txtNotas.setText(nz(p.getNotas()));
        Set<DayOfWeek> dias = p.getDias();
        checks.forEach((d, c) -> c.setSelected(dias.contains(d)));
        btnActivo.setVisible(true);
        btnActivo.setManaged(true);
        btnActivo.setText(p.isActivo() ? "Desactivar" : "Reactivar");
    }

    @FXML private void onGuardar() {
        Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
        checks.forEach((d, c) -> { if (c.isSelected()) dias.add(d); });
        try {
            Proveedor g = proveedorService.guardar(seleccionado != null ? seleccionado.getId() : null,
                    txtNombre.getText(), txtRfc.getText(), txtTelefono.getText(), txtContacto.getText(), dias, txtNotas.getText());
            seleccionado = g;
            recargar();
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Proveedores", "\"" + g.getNombre() + "\" se guardó.");
            mostrar(g);
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML private void onCambiarActivo() {
        if (seleccionado == null) return;
        boolean desactivar = seleccionado.isActivo();
        if (desactivar && !Ventanas.confirmar("Desactivar proveedor", "¿Desactivar a \"" + seleccionado.getNombre()
                + "\"? Ya no se ofrecerá en entradas ni órdenes nuevas; su historial se conserva.")) {
            return;
        }
        try {
            seleccionado = proveedorService.cambiarActivo(seleccionado.getId(), !desactivar);
            recargar();
            mostrar(seleccionado);
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Proveedores", e.getMessage());
        }
    }

    @FXML private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
