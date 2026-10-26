package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controlador de la pantalla de Ventas (F1).
 *
 * Flujo:
 *  1. Se escanea/escribe un código (o se busca por nombre) → renglón en el ticket
 *  2. Se ajustan cantidades (doble clic en "Cant.", botón Cantidad o Supr para quitar)
 *  3. Se captura "Pagó con" → se muestra el cambio en vivo
 *  4. Cobrar (botón o F12) → VentaService.cobrar() guarda la venta y descuenta stock
 *
 * El cálculo de precios/promociones/stock vive en VentaService; aquí solo UI.
 */
@Component
@Scope("prototype")
public class VentasController implements Initializable {

    // ── FXML ─────────────────────────────────────────────────────────────
    @FXML private TextField  txtCodigo;
    @FXML private TextField  txtPago;
    @FXML private ComboBox<Venta.FormaPago> comboFormaPago;
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
    @Autowired private VentaService    ventaService;
    @Autowired private ProductoService productoService;

    private final ObservableList<DetalleVenta> itemsTicket =
            FXCollections.observableArrayList();

    // ── Inicialización ────────────────────────────────────────────────────
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configurarColumnas();
        configurarFormaPago();
        tablaProductos.setItems(itemsTicket);
        tablaProductos.setEditable(true);
        tablaProductos.setPlaceholder(new Label(
                "Escanea un código o usa 🔍 Buscar para agregar productos."));

        // Supr / Retroceso sobre un renglón → quitarlo
        tablaProductos.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                onQuitarLinea();
            }
        });

        actualizarTotales();
        actualizarVendidoHoy();
        Platform.runLater(() -> txtCodigo.requestFocus());
    }

    // ── Acciones FXML ─────────────────────────────────────────────────────

    @FXML
    private void onAgregarProducto() {
        String codigo = txtCodigo.getText().trim();
        if (codigo.isEmpty()) return;

        Optional<Producto> encontrado = ventaService.buscarPorCodigo(codigo);
        if (encontrado.isEmpty()) {
            alerta(Alert.AlertType.WARNING, "Producto no encontrado",
                    "No existe un producto con el código \"" + codigo + "\".\n" +
                    "Usa 🔍 Buscar para localizarlo por nombre.");
            txtCodigo.selectAll();
            return;
        }

        agregarAlTicket(encontrado.get(), 1);
        txtCodigo.clear();
        txtCodigo.requestFocus();
    }

    /** Diálogo de búsqueda por nombre o código */
    @FXML
    private void onBuscar() {
        Dialog<Producto> dialogo = new Dialog<>();
        dialogo.setTitle("Buscar producto");
        dialogo.setHeaderText("Escribe parte del nombre o del código");
        dialogo.initOwner(tablaProductos.getScene().getWindow());
        Ventanas.estilizar(dialogo);

        TextField txtFiltro = new TextField();
        txtFiltro.setPromptText("Nombre o código...");

        TableView<Producto> tabla = new TableView<>();
        tabla.setPrefSize(640, 320);
        TableColumn<Producto, String> cCod = new TableColumn<>("Código");
        cCod.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCodigoBarras()));
        cCod.setPrefWidth(130);
        TableColumn<Producto, String> cDesc = new TableColumn<>("Descripción");
        cDesc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDescripcion()));
        cDesc.setPrefWidth(300);
        TableColumn<Producto, String> cPrecio = new TableColumn<>("Precio");
        cPrecio.setCellValueFactory(c -> new SimpleStringProperty(
                String.format("$%.2f", c.getValue().getPrecioVenta())));
        TableColumn<Producto, String> cStock = new TableColumn<>("Existencia");
        cStock.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isUsaInventario() ? formatoCantidad(c.getValue().getCantidadActual()) : "—"));
        tabla.getColumns().add(cCod);
        tabla.getColumns().add(cDesc);
        tabla.getColumns().add(cPrecio);
        tabla.getColumns().add(cStock);
        tabla.setPlaceholder(new Label("Sin resultados"));

        Runnable filtrar = () -> tabla.getItems().setAll(productoService.buscar(txtFiltro.getText()));
        txtFiltro.textProperty().addListener((o, a, b) -> filtrar.run());
        filtrar.run();

        VBox contenido = new VBox(8, txtFiltro, tabla);
        contenido.setPadding(new Insets(10));
        dialogo.getDialogPane().setContent(contenido);
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Doble clic en una fila = aceptar
        tabla.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && tabla.getSelectionModel().getSelectedItem() != null) {
                dialogo.setResult(tabla.getSelectionModel().getSelectedItem());
                dialogo.close();
            }
        });
        // Enter en el filtro = seleccionar el primero
        txtFiltro.setOnAction(e -> {
            if (!tabla.getItems().isEmpty()) {
                tabla.getSelectionModel().selectFirst();
                dialogo.setResult(tabla.getSelectionModel().getSelectedItem());
                dialogo.close();
            }
        });

        dialogo.setResultConverter(btn ->
                btn == ButtonType.OK ? tabla.getSelectionModel().getSelectedItem() : null);
        Platform.runLater(txtFiltro::requestFocus);

        dialogo.showAndWait().ifPresent(p -> agregarAlTicket(p, 1));
        txtCodigo.requestFocus();
    }

    /** Alterna el renglón seleccionado entre precio de lista y precio de mayoreo */
    @FXML
    private void onMayoreo() {
        DetalleVenta d = seleccionado("Selecciona un renglón del ticket para aplicar mayoreo.");
        if (d == null) return;

        Producto p = d.getProducto();
        if (p.getPrecioMayoreo() <= 0) {
            alerta(Alert.AlertType.INFORMATION, "Sin precio de mayoreo",
                    "\"" + p.getDescripcion() + "\" no tiene precio de mayoreo registrado.");
            return;
        }
        boolean yaEsMayoreo = Math.abs(d.getPrecioVenta() - p.getPrecioMayoreo()) < 0.0001;
        d.setPrecioVenta(yaEsMayoreo ? p.getPrecioVenta() : p.getPrecioMayoreo());
        ventaService.aplicarPromocion(d);
        tablaProductos.refresh();
        actualizarTotales();
    }

    @FXML
    private void onCambiarCantidad() {
        DetalleVenta d = seleccionado("Selecciona un renglón del ticket para cambiar la cantidad.");
        if (d == null) return;

        TextInputDialog dlg = new TextInputDialog(String.valueOf(d.getCantidad()));
        dlg.setTitle("Cantidad");
        dlg.setHeaderText(d.getProducto().getDescripcion());
        dlg.setContentText("Nueva cantidad:");
        dlg.initOwner(tablaProductos.getScene().getWindow());
        Ventanas.estilizar(dlg);
        dlg.showAndWait().ifPresent(txt -> {
            try {
                cambiarCantidad(d, Integer.parseInt(txt.trim()));
            } catch (NumberFormatException e) {
                alerta(Alert.AlertType.WARNING, "Cantidad inválida",
                        "Escribe un número entero (ej: 3).");
            }
        });
        txtCodigo.requestFocus();
    }

    @FXML
    private void onQuitarLinea() {
        DetalleVenta d = tablaProductos.getSelectionModel().getSelectedItem();
        if (d == null) return;
        itemsTicket.remove(d);
        actualizarTotales();
        txtCodigo.requestFocus();
    }

    @FXML
    private void onPagoChanged() {
        actualizarCambio();
    }

    @FXML
    private void onCobrar() {
        if (itemsTicket.isEmpty()) {
            alerta(Alert.AlertType.INFORMATION, "Ticket vacío",
                    "Agrega al menos un producto antes de cobrar.");
            return;
        }

        double total = ventaService.calcularTotal(itemsTicket);
        Venta.FormaPago formaPago = comboFormaPago.getValue();
        double pago = total;

        if (formaPago == Venta.FormaPago.EFECTIVO) {
            Double capturado = parsearPago();
            if (capturado == null) {
                alerta(Alert.AlertType.WARNING, "Pago inválido",
                        "Escribe con cuánto pagó el cliente (ej: 200).");
                txtPago.requestFocus();
                return;
            }
            if (capturado + 0.001 < total) {
                alerta(Alert.AlertType.WARNING, "Pago insuficiente", String.format(
                        "El cliente pagó $%.2f pero el total es $%.2f.", capturado, total));
                txtPago.requestFocus();
                return;
            }
            pago = capturado;
        }

        try {
            // El cajero lo toma el servicio de la sesión activa
            Venta venta = ventaService.cobrar(new ArrayList<>(itemsTicket), formaPago, pago);

            String msg = String.format("Venta #%d registrada.\n\nTotal:  $%.2f\nPagó:   $%.2f\nCambio: $%.2f",
                    venta.getId(), venta.getTotal(), venta.getPagoCon(), venta.getCambio());
            alerta(Alert.AlertType.INFORMATION, "Venta cobrada", msg);

            limpiarTicket();
            actualizarVendidoHoy();

        } catch (NegocioException e) {
            // Stock, promoción o precio cambiaron desde que se armó el ticket:
            // releer productos, recalcular y avisar
            refrescarTicket();
            alerta(Alert.AlertType.ERROR, "No se pudo cobrar", e.getMessage());
        }
        txtCodigo.requestFocus();
    }

    @FXML
    private void onCancelarTicket() {
        if (itemsTicket.isEmpty()) {
            limpiarTicket();
            return;
        }
        if (Ventanas.confirmar("Cancelar ticket",
                "¿Cancelar el ticket actual? Se quitarán todos los productos.")) {
            limpiarTicket();
        }
        txtCodigo.requestFocus();
    }

    /** Invocado por MainController con la tecla F12 */
    public void cobrarDesdeAtajo() {
        onCobrar();
    }

    // ── Lógica del ticket (delegando reglas a VentaService) ───────────────

    private void agregarAlTicket(Producto producto, int cantidad) {
        // ¿Ya está en el ticket? → sumar cantidad
        Optional<DetalleVenta> existente = itemsTicket.stream()
                .filter(d -> d.getProducto().getId().equals(producto.getId()))
                .findFirst();

        try {
            if (existente.isPresent()) {
                cambiarCantidad(existente.get(), existente.get().getCantidad() + cantidad);
                tablaProductos.getSelectionModel().select(existente.get());
            } else {
                DetalleVenta d = ventaService.crearDetalle(producto, cantidad);
                itemsTicket.add(d);
                tablaProductos.getSelectionModel().select(d);
                tablaProductos.scrollTo(d);
            }
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se puede agregar", e.getMessage());
        }
        actualizarTotales();
    }

    private void cambiarCantidad(DetalleVenta d, int nuevaCantidad) {
        if (nuevaCantidad <= 0) {
            itemsTicket.remove(d);
            actualizarTotales();
            return;
        }
        try {
            ventaService.validarStock(d.getProducto(), nuevaCantidad);
            d.setCantidad(nuevaCantidad);
            ventaService.aplicarPromocion(d);
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se puede cambiar la cantidad", e.getMessage());
        }
        tablaProductos.refresh();
        actualizarTotales();
    }

    /**
     * Vuelve a leer los productos del ticket desde la BD (existencias,
     * precios y promociones actuales) y recalcula cada renglón.
     */
    private void refrescarTicket() {
        for (DetalleVenta d : itemsTicket) {
            productoService.buscarPorId(d.getProducto().getId()).ifPresent(p -> {
                d.setProducto(p);
                ventaService.recalcularPrecio(d);
            });
        }
        tablaProductos.refresh();
        actualizarTotales();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void configurarColumnas() {
        colCodigo.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getProducto().getCodigoBarras()));
        colDescripcion.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getProducto().getDescripcion()));
        colPrecio.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getPrecioVenta()).asObject());
        colCantidad.setCellValueFactory(c ->
                new SimpleIntegerProperty(c.getValue().getCantidad()).asObject());
        colImporte.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getImporte()).asObject());
        colDescuento.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getDescuento()).asObject());
        colExistencia.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getProducto().getCantidadActual()).asObject());

        formatoMoneda(colPrecio);
        formatoMoneda(colImporte);
        formatoMoneda(colDescuento);

        colExistencia.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); return; }
                DetalleVenta d = getTableRow() != null ? getTableRow().getItem() : null;
                setText(d != null && !d.getProducto().isUsaInventario() ? "—" : formatoCantidad(v));
            }
        });

        // Cantidad editable con doble clic
        colCantidad.setEditable(true);
        colCantidad.setCellFactory(TextFieldTableCell.forTableColumn(new StringConverter<>() {
            @Override public String toString(Integer v) { return v == null ? "" : String.valueOf(v); }
            @Override public Integer fromString(String s) {
                try { return Integer.parseInt(s.trim()); }
                catch (NumberFormatException e) { return null; }
            }
        }));
        colCantidad.setOnEditCommit(ev -> {
            DetalleVenta d = ev.getRowValue();
            if (ev.getNewValue() == null) {
                alerta(Alert.AlertType.WARNING, "Cantidad inválida", "Escribe un número entero (ej: 3).");
                tablaProductos.refresh();
                return;
            }
            cambiarCantidad(d, ev.getNewValue());
        });
    }

    private void configurarFormaPago() {
        comboFormaPago.setItems(FXCollections.observableArrayList(Venta.FormaPago.values()));
        comboFormaPago.setConverter(new StringConverter<>() {
            @Override public String toString(Venta.FormaPago fp) {
                if (fp == null) return "";
                return switch (fp) {
                    case EFECTIVO -> "Efectivo";
                    case TARJETA  -> "Tarjeta";
                    case CREDITO  -> "Crédito";
                };
            }
            @Override public Venta.FormaPago fromString(String s) { return null; }
        });
        comboFormaPago.setValue(Venta.FormaPago.EFECTIVO);
        comboFormaPago.valueProperty().addListener((o, a, fp) -> {
            boolean efectivo = fp == Venta.FormaPago.EFECTIVO;
            txtPago.setDisable(!efectivo);
            if (!efectivo) txtPago.clear();
            actualizarCambio();
        });
    }

    private void formatoMoneda(TableColumn<DetalleVenta, Double> col) {
        col.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("$%.2f", v));
            }
        });
    }

    private DetalleVenta seleccionado(String mensajeSiNoHay) {
        DetalleVenta d = tablaProductos.getSelectionModel().getSelectedItem();
        if (d == null) {
            alerta(Alert.AlertType.WARNING, "Sin selección", mensajeSiNoHay);
        }
        return d;
    }

    /** Monto capturado en "Pagó con"; null si no es un número */
    private Double parsearPago() {
        String txt = txtPago.getText().trim().replace("$", "").replace(",", "");
        if (txt.isEmpty()) return null;
        try {
            double v = Double.parseDouble(txt);
            // parseDouble acepta "NaN" e "Infinity": no son montos válidos
            return (Double.isFinite(v) && v >= 0) ? v : null;
        } catch (NumberFormatException e) { return null; }
    }

    private void actualizarTotales() {
        double total = ventaService.calcularTotal(itemsTicket);
        int piezas = itemsTicket.stream().mapToInt(DetalleVenta::getCantidad).sum();
        labelTotal.setText(String.format("$%.2f", total));
        labelNumProductos.setText(itemsTicket.size() + " renglones · " + piezas + " productos en la venta actual.");
        actualizarCambio();
    }

    private void actualizarCambio() {
        double total = ventaService.calcularTotal(itemsTicket);
        Double pago = parsearPago();
        if (comboFormaPago.getValue() != Venta.FormaPago.EFECTIVO || pago == null) {
            labelCambio.setText("$0.00");
            return;
        }
        double cambio = pago - total;
        labelCambio.setText(cambio < 0
                ? String.format("Faltan $%.2f", -cambio)
                : String.format("$%.2f", cambio));
    }

    private void actualizarVendidoHoy() {
        try {
            labelTotalPagado.setText(String.format("$%.2f", ventaService.totalVentasHoy()));
        } catch (Exception e) {
            labelTotalPagado.setText("$0.00");
        }
    }

    private void limpiarTicket() {
        itemsTicket.clear();
        txtCodigo.clear();
        txtPago.clear();
        comboFormaPago.setValue(Venta.FormaPago.EFECTIVO);
        labelTotal.setText("$0.00");
        labelCambio.setText("$0.00");
        actualizarTotales();
    }

    private static String formatoCantidad(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
