package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import org.springframework.stereotype.Component;
import java.net.URL;
import java.util.ResourceBundle;

/** Controlador de Clientes (F2) */
@Component
public class ClientesController implements Initializable {
    @FXML private TableView<?> tablaClientes;
    @FXML private TextField    txtBuscar;

    @Override public void initialize(URL url, ResourceBundle rb) {
        // TODO: cargar clientes desde ClienteService
    }

    @FXML void onEstadoCuenta()  { /* TODO */ }
    @FXML void onNuevoCliente()  { /* TODO: abrir diálogo NuevoCliente.fxml */ }
    @FXML void onModificar()     { /* TODO */ }
    @FXML void onEliminar()      { /* TODO */ }
    @FXML void onReporteSaldos() { /* TODO */ }
    @FXML void onBuscar()        { /* TODO: filtrar tabla */ }
}
