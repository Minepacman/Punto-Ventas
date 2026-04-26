package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import org.springframework.stereotype.Component;
import java.net.URL;
import java.util.ResourceBundle;

/** Controlador del Catálogo de Productos (F3) */
@Component
public class ProductosController implements Initializable {
    @FXML private TableView<?> tablaProductos;
    @FXML private TextField    txtBuscar;

    @Override public void initialize(URL url, ResourceBundle rb) {
        // TODO: cargar productos desde ProductoService
    }

    @FXML void onNuevo()         { /* TODO: abrir formulario NuevoProducto */ }
    @FXML void onModificar()     { /* TODO */ }
    @FXML void onEliminar()      { /* TODO */ }
    @FXML void onDepartamentos() { /* TODO: abrir gestión de departamentos */ }
    @FXML void onVentasPeriodo() { /* TODO: reporte ventas por período */ }
    @FXML void onPromociones()   { /* TODO */ }
    @FXML void onImportar()      { /* TODO: importar desde CSV/Excel */ }
    @FXML void onBuscar()        { /* TODO: filtrar tabla en tiempo real */ }
}
