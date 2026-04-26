package me.eliassanchezfernandez.puntodeventa.controller;

import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controlador de la pantalla de Ventas (F1).
 *
 * Flujo principal:
 *  1. Cajero escanea código → onAgregarProducto()
 *  2. Servicio busca producto y actualiza la tabla
 *  3. Cajero ingresa pago → onCobrar()
 *  4. Servicio registra la venta y descuenta inventario
 */
@Component
public class VentasController implements Initializable {

    // ── FXML ─────────────────────────────────────────────────────────────
    @FXML private TextField  txtCodigo;
    @FXML private TextField  txtPago;
    @FXML private TableView<DetalleVenta> tablaProductos;
    @FXML private TableColumn<DetalleVenta, String>  colCodigo;
    @FXML private TableColumn<DetalleVenta, String>  colDescripcion;
    @FXML private TableColumn<DetalleVenta, Double>  colPrecio;
    @FXML private TableColumn<DetalleVenta, Integer> colCantidad;
    @FXML private TableColumn<DetalleVenta, Double>  colImporte;
    @FXML private TableColumn<DetalleVenta, Double>  colExistencia;
    @FXML private TableColumn<DetalleVenta, Double>  colDescuento;
    @FXML private Label labelTotal;
    @FXML private Label labelCambio;
    @FXML private Label labelNumProductos;
    @FXML private Label labelTotalPagado;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired
    private VentaService ventaService;

    private final ObservableList<DetalleVenta> itemsTicket =
            FXCollections.observableArrayList();

    // ── Inicialización ────────────────────────────────────────────────────
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configurarColumnas();
        tablaProductos.setItems(itemsTicket);
        txtCodigo.requestFocus();
    }

    // ── Acciones FXML ─────────────────────────────────────────────────────

    @FXML
    private void onAgregarProducto() {
        String codigo = txtCodigo.getText().trim();
        if (codigo.isEmpty()) return;

        // TODO: llamar ventaService.buscarProducto(codigo)
        //       y agregar al ticket si existe

        txtCodigo.clear();
        actualizarTotales();
    }

    @FXML
    private void onBuscar() {
        // TODO: abrir diálogo de búsqueda por nombre/código
    }

    @FXML
    private void onMayoreo() {
        // TODO: cambiar precio al precio mayoreo del producto seleccionado
    }

    @FXML
    private void onCobrar() {
        if (itemsTicket.isEmpty()) return;
        // TODO: ventaService.cobrar(itemsTicket, montoPagado)
        //       Imprimir ticket → JasperReports
        //       Limpiar ticket
        limpiarTicket();
    }

    @FXML
    private void onCancelarTicket() {
        // TODO: confirmar antes de limpiar
        limpiarTicket();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void configurarColumnas() {
        // TODO: setCellValueFactory para cada columna con PropertyValueFactory
    }

    private void actualizarTotales() {
        double total = itemsTicket.stream()
                .mapToDouble(d -> d.getPrecioVenta() * d.getCantidad())
                .sum();
        labelTotal.setText(String.format("$%.2f", total));
        labelNumProductos.setText(itemsTicket.size() + " productos en la venta actual.");
    }

    private void limpiarTicket() {
        itemsTicket.clear();
        txtCodigo.clear();
        txtPago.clear();
        labelTotal.setText("$0.00");
        labelCambio.setText("$0.00");
        actualizarTotales();
    }
}