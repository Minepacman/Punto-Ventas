package me.eliassanchezfernandez.puntodeventa.controller;

import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

/** Bitácora de movimientos de inventario por período (INV-07), ventana modal */
@Component
@Scope("prototype")
public class MovimientosController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private BorderPane raiz;
    @FXML private DatePicker dpDesde;
    @FXML private DatePicker dpHasta;
    @FXML private TextField  txtFiltro;
    @FXML private Label      lblResumen;

    @FXML private TableView<MovimientoInventario>           tabla;
    @FXML private TableColumn<MovimientoInventario, String> colFecha;
    @FXML private TableColumn<MovimientoInventario, String> colTipo;
    @FXML private TableColumn<MovimientoInventario, String> colProducto;
    @FXML private TableColumn<MovimientoInventario, String> colCantidad;
    @FXML private TableColumn<MovimientoInventario, String> colAntes;
    @FXML private TableColumn<MovimientoInventario, String> colDespues;
    @FXML private TableColumn<MovimientoInventario, String> colUsuario;
    @FXML private TableColumn<MovimientoInventario, String> colDetalle;

    @Autowired private ProductoService productoService;

    private final ObservableList<MovimientoInventario> todos = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFecha().format(FMT)));
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTipo().getEtiqueta()));
        colProducto.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getProducto().getCodigoBarras() + "  " + c.getValue().getProducto().getDescripcion()));
        colCantidad.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().getCantidad() > 0 ? "+" : "") + formatoCantidad(c.getValue().getCantidad())));
        colAntes.setCellValueFactory(c -> new SimpleStringProperty(formatoCantidad(c.getValue().getExistenciaAntes())));
        colDespues.setCellValueFactory(c -> new SimpleStringProperty(formatoCantidad(c.getValue().getExistenciaDespues())));
        colUsuario.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().getUsuarioNombre() != null ? c.getValue().getUsuarioNombre() : "—")
                + (c.getValue().getAutorizadoPor() != null ? " (autorizó " + c.getValue().getAutorizadoPor() + ")" : "")));
        colDetalle.setCellValueFactory(c -> {
            MovimientoInventario m = c.getValue();
            StringBuilder sb = new StringBuilder();
            if (m.getMotivo() != null) sb.append(m.getMotivo());
            if (m.getProveedor() != null) sb.append(sb.length() > 0 ? " · " : "").append("Proveedor: ").append(m.getProveedor());
            if (m.getCostoUnitario() != null && m.getCostoUnitario() > 0) sb.append(" · Costo " + Moneda.formato(m.getCostoUnitario()));
            return new SimpleStringProperty(sb.toString());
        });
        tabla.setPlaceholder(new Label("No hay movimientos en el período."));
        txtFiltro.textProperty().addListener((o, a, b) -> aplicarFiltro());

        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy.minusDays(7));
        dpHasta.setValue(hoy);
        onBuscar();
    }

    @FXML private void onBuscar() {
        try {
            todos.setAll(productoService.movimientosEntre(dpDesde.getValue(), dpHasta.getValue()));
            aplicarFiltro();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Rango inválido", e.getMessage());
        }
    }

    @FXML private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private void aplicarFiltro() {
        String t = txtFiltro.getText() == null ? "" : txtFiltro.getText().trim().toLowerCase();
        if (t.isEmpty()) {
            tabla.setItems(todos);
        } else {
            tabla.setItems(todos.filtered(m ->
                    m.getProducto().getDescripcion().toLowerCase().contains(t)
                    || m.getProducto().getCodigoBarras().toLowerCase().contains(t)));
        }
        lblResumen.setText(tabla.getItems().size() + " movimiento(s)");
    }

    private static String formatoCantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }
}
