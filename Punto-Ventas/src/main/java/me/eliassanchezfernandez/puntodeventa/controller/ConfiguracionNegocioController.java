package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.TextField;
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
 * Configuración del negocio (CFG-01, VEN-04, VEN-14): nombre, dirección,
 * tasa de IVA (los precios ya la incluyen) y plazo de cancelación de ventas.
 * Ventana modal, solo ADMIN (lo verifica ConfiguracionService).
 */
@Component
@Scope("prototype")
public class ConfiguracionNegocioController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private TextField  txtNombre;
    @FXML private TextField  txtDireccion;
    @FXML private TextField  txtIva;
    @FXML private TextField  txtPlazo;

    @Autowired private ConfiguracionService configuracionService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        Configuracion c = configuracionService.obtener();
        txtNombre.setText(c.getNombreTienda());
        txtDireccion.setText(c.getDireccion() != null ? c.getDireccion() : "");
        txtIva.setText(formato(c.getTasaIva() * 100));
        txtPlazo.setText(String.valueOf(c.getPlazoCancelacionHoras()));
    }

    @FXML
    private void onGuardar() {
        double iva;
        int plazo;
        try {
            iva = Double.parseDouble(txtIva.getText().trim().replace("%", "").replace(",", "."));
            plazo = Integer.parseInt(txtPlazo.getText().trim());
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Datos inválidos",
                    "La tasa de IVA debe ser un número (ej: 16) y el plazo un entero en horas (ej: 48).");
            return;
        }
        try {
            configuracionService.guardar(txtNombre.getText(), txtDireccion.getText(), iva, plazo);
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Configuración guardada",
                    "Los cambios se guardaron. El nuevo plazo de cancelación aplica solo a ventas futuras.");
            onCerrar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private static String formato(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
