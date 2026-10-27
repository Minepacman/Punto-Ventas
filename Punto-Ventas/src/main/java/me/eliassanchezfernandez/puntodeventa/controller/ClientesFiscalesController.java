package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.service.ClienteFiscalService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/** Catálogo de receptores de factura (clientes fiscales): alta, edición y baja. Requiere FACTURAR. */
@Component
@Scope("prototype")
public class ClientesFiscalesController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private TextField txtBuscar;
    @FXML private TableView<ClienteFiscal> tabla;
    @FXML private TableColumn<ClienteFiscal, String> colRfc;
    @FXML private TableColumn<ClienteFiscal, String> colNombre;
    @FXML private TableColumn<ClienteFiscal, String> colRegimen;
    @FXML private TableColumn<ClienteFiscal, String> colCp;
    @FXML private TableColumn<ClienteFiscal, String> colUso;
    @FXML private TableColumn<ClienteFiscal, String> colEmail;

    @Autowired private ClienteFiscalService clienteFiscalService;
    @Autowired private FacturacionDialogos dialogos;

    private final ObservableList<ClienteFiscal> clientes = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colRfc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRfc()));
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));
        colRegimen.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRegimenFiscal() + " "
                + CatalogoSat.descripcionRegimen(c.getValue().getRegimenFiscal())));
        colCp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCodigoPostal()));
        colUso.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsoCfdi()));
        colEmail.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getEmail() != null ? c.getValue().getEmail() : ""));
        tabla.setItems(clientes);
        tabla.setPlaceholder(new Label("Aún no hay clientes fiscales. Se dan de alta aquí o al facturar."));
        txtBuscar.textProperty().addListener((o, a, b) -> recargar());
        recargar();
    }

    private void recargar() {
        clientes.setAll(clienteFiscalService.buscar(txtBuscar.getText()));
    }

    @FXML private void onNuevo() {
        dialogos.editarCliente(raiz.getScene().getWindow(), null).ifPresent(c -> recargar());
    }

    @FXML private void onEditar() {
        ClienteFiscal c = tabla.getSelectionModel().getSelectedItem();
        if (c == null) { Ventanas.alerta(Alert.AlertType.WARNING, "Clientes fiscales", "Selecciona un cliente."); return; }
        dialogos.editarCliente(raiz.getScene().getWindow(), c).ifPresent(x -> recargar());
    }

    @FXML private void onEliminar() {
        ClienteFiscal c = tabla.getSelectionModel().getSelectedItem();
        if (c == null) { Ventanas.alerta(Alert.AlertType.WARNING, "Clientes fiscales", "Selecciona un cliente."); return; }
        if (!Ventanas.confirmar("Eliminar cliente fiscal", "¿Eliminar a " + c.getRfc() + " " + c.getNombre()
                + "?\nLas facturas ya emitidas no cambian.")) return;
        try {
            clienteFiscalService.eliminar(c.getId());
            recargar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo eliminar", e.getMessage());
        }
    }

    @FXML private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }
}
