package me.eliassanchezfernandez.puntodeventa.controller;

import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/** Controlador del Corte de Caja */
@Component
public class CorteController implements Initializable {

    @Autowired private VentaService ventaService;

    @FXML private Label lblVentasTotales;
    @FXML private Label lblGanancia;
    @FXML private Label lblEfectivo;
    @FXML private Label lblTarjeta;
    @FXML private TableView<?> tablaDepartamentos;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        actualizarDatos();
    }

    @FXML
    void onHacerCorte() {
        actualizarDatos();
        // TODO: marcar el corte como procesado y guardar en BD
    }

    @FXML
    void onImprimirCorte() {
        // TODO: generar PDF/ticket con JasperReports
    }

    private void actualizarDatos() {
        double totalHoy = ventaService.totalVentasHoy();
        lblVentasTotales.setText(String.format("$%.2f", totalHoy));
        // TODO: calcular ganancia (precio venta - precio costo) × cantidad
        lblGanancia.setText("$0.00");
    }
}
