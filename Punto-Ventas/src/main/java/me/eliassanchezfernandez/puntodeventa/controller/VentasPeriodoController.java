package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Registro de ventas por período (ventana modal abierta desde
 * Productos → Ventas por Período).
 *
 * Arriba: rango de fechas. Centro: tabla de ventas y, debajo,
 * los renglones de la venta seleccionada. Abajo: totales del período.
 */
@Component
@Scope("prototype")
public class VentasPeriodoController implements Initializable {

    private static final DateTimeFormatter FMT_FECHA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private BorderPane raiz;
    @FXML private DatePicker dpDesde;
    @FXML private DatePicker dpHasta;

    // ── Tabla de ventas ──────────────────────────────────────────────────
    @FXML private TableView<Venta>           tablaVentas;
    @FXML private TableColumn<Venta, String> colFolio;
    @FXML private TableColumn<Venta, String> colFecha;
    @FXML private TableColumn<Venta, String> colCajero;
    @FXML private TableColumn<Venta, String> colFormaPago;
    @FXML private TableColumn<Venta, String> colPiezas;
    @FXML private TableColumn<Venta, String> colTotal;

    // ── Tabla de detalle ─────────────────────────────────────────────────
    @FXML private TableView<DetalleVenta>           tablaDetalle;
    @FXML private TableColumn<DetalleVenta, String> colDetCodigo;
    @FXML private TableColumn<DetalleVenta, String> colDetDescripcion;
    @FXML private TableColumn<DetalleVenta, String> colDetCantidad;
    @FXML private TableColumn<DetalleVenta, String> colDetPrecio;
    @FXML private TableColumn<DetalleVenta, String> colDetDescuento;
    @FXML private TableColumn<DetalleVenta, String> colDetImporte;

    @FXML private Label lblResumen;
    @FXML private Label lblDetalleTitulo;

    @Autowired private VentaService ventaService;

    private final ObservableList<Venta>        ventas   = FXCollections.observableArrayList();
    private final ObservableList<DetalleVenta> detalles = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configurarColumnas();
        tablaVentas.setItems(ventas);
        tablaDetalle.setItems(detalles);
        tablaVentas.setPlaceholder(new Label("No hay ventas en el período seleccionado."));
        tablaDetalle.setPlaceholder(new Label("Selecciona una venta para ver sus productos."));

        tablaVentas.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> {
            detalles.setAll(v != null ? v.getDetalles() : List.of());
            lblDetalleTitulo.setText(v != null
                    ? "Detalle de la venta #" + v.getId() + "  —  " + v.getFecha().format(FMT_FECHA)
                    : "Detalle de la venta");
        });

        onHoy();
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    @FXML private void onBuscar() {
        try {
            ventas.setAll(ventaService.ventasEntre(dpDesde.getValue(), dpHasta.getValue()));
        } catch (NegocioException e) {
            alerta(Alert.AlertType.WARNING, "Rango inválido", e.getMessage());
            return;
        }
        detalles.clear();
        actualizarResumen();
    }

    @FXML private void onHoy() {
        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy);
        dpHasta.setValue(hoy);
        onBuscar();
    }

    @FXML private void onEstaSemana() {
        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy.with(DayOfWeek.MONDAY));
        dpHasta.setValue(hoy);
        onBuscar();
    }

    @FXML private void onEsteMes() {
        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy.withDayOfMonth(1));
        dpHasta.setValue(hoy);
        onBuscar();
    }

    @FXML private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void actualizarResumen() {
        double total = ventas.stream().mapToDouble(Venta::getTotal).sum();
        int piezas = ventas.stream()
                .flatMap(v -> v.getDetalles().stream())
                .mapToInt(DetalleVenta::getCantidad).sum();
        lblResumen.setText(String.format("%d venta(s)  ·  %d producto(s)  ·  Total: $%.2f",
                ventas.size(), piezas, total));
    }

    private void configurarColumnas() {
        colFolio.setCellValueFactory(c -> new SimpleStringProperty("#" + c.getValue().getId()));
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFecha().format(FMT_FECHA)));
        colCajero.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCajeroNombre() != null ? c.getValue().getCajeroNombre() : "—"));
        colFormaPago.setCellValueFactory(c -> new SimpleStringProperty(nombreFormaPago(c.getValue().getFormaPago())));
        colPiezas.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(
                c.getValue().getDetalles().stream().mapToInt(DetalleVenta::getCantidad).sum())));
        colTotal.setCellValueFactory(c -> new SimpleStringProperty(String.format("$%.2f", c.getValue().getTotal())));

        colDetCodigo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProducto().getCodigoBarras()));
        colDetDescripcion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProducto().getDescripcion()));
        colDetCantidad.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getCantidad())));
        colDetPrecio.setCellValueFactory(c -> new SimpleStringProperty(String.format("$%.2f", c.getValue().getPrecioVenta())));
        colDetDescuento.setCellValueFactory(c -> new SimpleStringProperty(String.format("$%.2f", c.getValue().getDescuento())));
        colDetImporte.setCellValueFactory(c -> new SimpleStringProperty(String.format("$%.2f", c.getValue().getImporte())));
    }

    private static String nombreFormaPago(Venta.FormaPago fp) {
        if (fp == null) return "—";
        return switch (fp) {
            case EFECTIVO -> "Efectivo";
            case TARJETA  -> "Tarjeta";
            case CREDITO  -> "Crédito";
        };
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
