package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import java.net.URL;
import java.util.ResourceBundle;

/** Controlador de Clientes (F2) — pendiente de implementar */
@Component
@Scope("prototype")
public class ClientesController implements Initializable {
    @FXML private TableView<?> tablaClientes;
    @FXML private TextField    txtBuscar;

    @Override public void initialize(URL url, ResourceBundle rb) {
        tablaClientes.setPlaceholder(new Label("El módulo de clientes no está disponible en esta versión."));
    }

    @FXML void onEstadoCuenta()  { noDisponible("Estado de cuenta"); }
    @FXML void onNuevoCliente()  { noDisponible("Nuevo cliente"); }
    @FXML void onModificar()     { noDisponible("Modificar cliente"); }
    @FXML void onEliminar()      { noDisponible("Eliminar cliente"); }
    @FXML void onReporteSaldos() { noDisponible("Reporte de saldos"); }
    @FXML void onBuscar()        { /* sin datos que filtrar todavía */ }

    private void noDisponible(String opcion) {
        Ventanas.alerta(Alert.AlertType.INFORMATION, opcion,
                "\"" + opcion + "\" no está disponible en esta versión.");
    }
}
