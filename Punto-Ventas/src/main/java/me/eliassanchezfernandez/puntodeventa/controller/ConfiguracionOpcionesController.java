package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Opciones habilitadas (Configuración): interruptores guardados en
 * Configuracion que el resto de la aplicación consulta (fondo de caja al
 * iniciar el día, aviso de stock bajo, venta a granel, confirmar cobro,
 * sugerencias en venta).
 * Ventana modal, solo ADMIN (lo verifica ConfiguracionService).
 */
@Component
@Scope("prototype")
public class ConfiguracionOpcionesController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private CheckBox   chkFondoCaja;
    @FXML private CheckBox   chkStockBajo;
    @FXML private CheckBox   chkGranel;
    @FXML private CheckBox   chkConfirmarCobro;
    @FXML private CheckBox   chkSugerencias;

    @Autowired private ConfiguracionService configuracionService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        Configuracion c = configuracionService.obtener();
        chkFondoCaja.setSelected(c.isPedirFondoCaja());
        chkStockBajo.setSelected(c.isAvisoStockBajo());
        chkGranel.setSelected(c.isPermitirGranel());
        chkConfirmarCobro.setSelected(c.isConfirmarCobro());
        chkSugerencias.setSelected(c.isMostrarSugerencias());
    }

    @FXML
    private void onGuardar() {
        try {
            configuracionService.guardarOpciones(chkFondoCaja.isSelected(), chkStockBajo.isSelected(),
                    chkGranel.isSelected(), chkConfirmarCobro.isSelected(), chkSugerencias.isSelected());
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Opciones guardadas",
                    "Los cambios se aplican de inmediato.");
            onCerrar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }
}
