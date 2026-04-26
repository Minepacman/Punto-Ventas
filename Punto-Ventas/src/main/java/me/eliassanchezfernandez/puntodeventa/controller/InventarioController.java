package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import org.springframework.stereotype.Component;
import java.net.URL;
import java.util.ResourceBundle;

/** Controlador de Inventario (F4) */
@Component
public class InventarioController implements Initializable {
    @FXML private TextField txtCodigo;
    @FXML private TextField txtCantidad;
    @FXML private Label     labelDescripcion;
    @FXML private Label     labelCantidadActual;
    @FXML private TableView<?> tablaInventario;

    @Override public void initialize(URL url, ResourceBundle rb) {
        // TODO: cargar productos bajos en inventario
    }

    @FXML void onAgregar()              { /* TODO: modo agregar */ }
    @FXML void onAjustes()              { /* TODO: ajuste manual */ }
    @FXML void onProductosBajos()       { /* TODO: filtrar tabla */ }
    @FXML void onReporteInventario()    { /* TODO: generar PDF */ }
    @FXML void onReporteMovimientos()   { /* TODO */ }
    @FXML void onBuscarProducto()       { /* TODO: buscar por código */ }
    @FXML void onAgregarCantidad()      { /* TODO: actualizar stock */ }
}
