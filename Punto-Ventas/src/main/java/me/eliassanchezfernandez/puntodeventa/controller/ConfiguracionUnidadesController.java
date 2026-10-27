package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.UnidadService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/** Catálogo de unidades de medida (alta, cambio y baja). Solo ADMIN (lo verifica UnidadService). */
@Component
@Scope("prototype")
public class ConfiguracionUnidadesController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private TableView<Unidad> tabla;
    @FXML private TableColumn<Unidad, String> colNombre;
    @FXML private TableColumn<Unidad, String> colAbrev;
    @FXML private TableColumn<Unidad, String> colDecimales;
    @FXML private Label     lblTitulo;
    @FXML private TextField txtNombre;
    @FXML private TextField txtAbreviatura;
    @FXML private CheckBox  chkDecimales;

    @Autowired private UnidadService unidadService;

    private final ObservableList<Unidad> unidades = FXCollections.observableArrayList();
    private Unidad enEdicion;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));
        colAbrev.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getAbreviatura() != null ? c.getValue().getAbreviatura() : ""));
        colDecimales.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isPermiteDecimales() ? "Sí" : "No"));
        tabla.setItems(unidades);
        tabla.getSelectionModel().selectedItemProperty().addListener((o, a, u) -> { if (u != null) editar(u); });
        recargar();
    }

    @FXML
    private void onGuardar() {
        Unidad u = new Unidad();
        if (enEdicion != null) u.setId(enEdicion.getId());
        u.setNombre(txtNombre.getText());
        u.setAbreviatura(txtAbreviatura.getText());
        u.setPermiteDecimales(chkDecimales.isSelected());
        try {
            unidadService.guardar(u);
            recargar();
            onNueva();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onNueva() {
        enEdicion = null;
        tabla.getSelectionModel().clearSelection();
        lblTitulo.setText("Nueva unidad");
        txtNombre.clear();
        txtAbreviatura.clear();
        chkDecimales.setSelected(false);
    }

    @FXML
    private void onEliminar() {
        Unidad u = tabla.getSelectionModel().getSelectedItem();
        if (u == null) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Sin selección", "Selecciona la unidad que quieres eliminar.");
            return;
        }
        if (!Ventanas.confirmar("Eliminar unidad", "¿Eliminar la unidad \"" + u.getNombre() + "\"?")) return;
        try {
            unidadService.eliminar(u.getId());
            recargar();
            onNueva();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo eliminar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private void editar(Unidad u) {
        enEdicion = u;
        lblTitulo.setText("Modificar unidad");
        txtNombre.setText(u.getNombre());
        txtAbreviatura.setText(u.getAbreviatura());
        chkDecimales.setSelected(u.isPermiteDecimales());
    }

    private void recargar() {
        unidades.setAll(unidadService.listar());
    }
}
