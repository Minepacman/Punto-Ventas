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
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Controlador del Corte de Caja (solo ADMIN).
 * Muestra el resumen de las ventas de hoy calculado por VentaService.
 */
@Component
@Scope("prototype")
public class CorteController implements Initializable {

    @Autowired private VentaService  ventaService;
    @Autowired private SesionService sesionService;

    // Entradas de efectivo
    @FXML private Label lblEntradaCambio;
    @FXML private Label lblDineroInicial;
    @FXML private Label lblTotalEntradas;
    // Dinero en caja
    @FXML private Label lblVentasEfectivo;
    @FXML private Label lblEntradas;
    @FXML private Label lblPagoProveedores;
    @FXML private Label lblTotalCaja;
    // Pagos de contado
    @FXML private Label lblEfectivo;
    @FXML private Label lblTarjeta;
    // Totales
    @FXML private Label lblVentasTotales;
    @FXML private Label lblGanancia;

    @FXML private TableView<Map.Entry<String, Double>>           tablaDepartamentos;
    @FXML private TableColumn<Map.Entry<String, Double>, String> colDepto;
    @FXML private TableColumn<Map.Entry<String, Double>, String> colMonto;

    private final ObservableList<Map.Entry<String, Double>> porDepartamento =
            FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colDepto.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getKey()));
        colMonto.setCellValueFactory(c -> new SimpleStringProperty(moneda(c.getValue().getValue())));
        tablaDepartamentos.setItems(porDepartamento);
        tablaDepartamentos.setPlaceholder(new Label("Sin ventas hoy."));
        recargar();
    }

    @FXML
    void onHacerCorte() {
        recargar();
        alerta(Alert.AlertType.INFORMATION, "Corte de hoy",
                "Resumen actualizado.\n\nVentas totales: " + lblVentasTotales.getText()
                + "\nDinero esperado en caja: " + lblTotalCaja.getText());
    }

    @FXML
    void onImprimirCorte() {
        alerta(Alert.AlertType.INFORMATION, "Imprimir corte",
                "La impresión del corte no está disponible en esta versión.");
    }

    /** Recalcula el resumen (lo llama MainController al entrar a la pestaña) */
    public void recargar() {
        VentaService.ResumenCorte r = ventaService.resumenDelDia();
        Double fondo = sesionService.getFondoDeCajaDelDia();
        double dineroInicial = fondo != null ? fondo : 0.0;

        lblEntradaCambio.setText(moneda(0));
        lblDineroInicial.setText(moneda(dineroInicial));
        lblTotalEntradas.setText(moneda(dineroInicial));

        lblVentasEfectivo.setText(moneda(r.efectivo));
        lblEntradas.setText(moneda(dineroInicial));
        lblPagoProveedores.setText(moneda(0));
        lblTotalCaja.setText(moneda(r.efectivo + dineroInicial));

        lblEfectivo.setText(moneda(r.efectivo));
        lblTarjeta.setText(moneda(r.tarjeta));

        lblVentasTotales.setText(moneda(r.ventasTotales));
        lblGanancia.setText(moneda(r.ganancia));

        porDepartamento.setAll(r.porDepartamento.entrySet());
    }

    private static String moneda(double v) {
        return String.format("$%.2f", v);
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
