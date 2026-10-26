package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controlador de Inventario (F4).
 *
 * Tabla con todos los productos que manejan inventario (se puede filtrar a
 * "solo bajos") y un formulario con dos modos:
 *   - AGREGAR → suma unidades a la existencia (entrada de mercancía)
 *   - AJUSTE  → fija la existencia en un valor exacto (conteo físico)
 */
@Component
@Scope("prototype")
public class InventarioController implements Initializable {

    private enum Modo { AGREGAR, AJUSTE }

    // ── FXML – formulario ─────────────────────────────────────────────────
    @FXML private Label     lblTituloForm;
    @FXML private TextField txtCodigo;
    @FXML private TextField txtCantidad;
    @FXML private Label     labelDescripcion;
    @FXML private Label     labelCantidadActual;
    @FXML private Label     lblEtiquetaCantidad;
    @FXML private Button    btnAplicar;
    @FXML private ToggleButton btnSoloBajos;

    // ── FXML – tabla ─────────────────────────────────────────────────────
    @FXML private TableView<Producto>           tablaInventario;
    @FXML private TableColumn<Producto, String> colCodigo;
    @FXML private TableColumn<Producto, String> colDescripcion;
    @FXML private TableColumn<Producto, Double> colActual;
    @FXML private TableColumn<Producto, Double> colMinimo;
    @FXML private TableColumn<Producto, String> colEstado;

    @Autowired private ProductoService productoService;

    private final ObservableList<Producto> lista = FXCollections.observableArrayList();
    private Producto productoSeleccionado = null;
    private Modo modo = Modo.AGREGAR;

    @Override public void initialize(URL url, ResourceBundle rb) {
        configurarColumnas();
        tablaInventario.setItems(lista);
        tablaInventario.setPlaceholder(new Label("No hay productos con inventario."));

        // Al seleccionar una fila se carga en el formulario
        tablaInventario.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            if (p != null) mostrarProducto(p);
        });

        aplicarModo(Modo.AGREGAR);
        recargar();
    }

    /** Recarga la tabla (lo llama MainController al entrar a la pestaña) */
    public void recargar() {
        boolean soloBajos = btnSoloBajos != null && btnSoloBajos.isSelected();
        lista.setAll(soloBajos
                ? productoService.productosConStockBajo()
                : productoService.productosConInventario());

        // Mantener el producto del formulario con datos frescos
        if (productoSeleccionado != null) {
            productoService.buscarPorId(productoSeleccionado.getId())
                    .ifPresentOrElse(this::mostrarProducto, this::limpiarFormulario);
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────

    @FXML void onAgregar() {
        aplicarModo(Modo.AGREGAR);
        txtCodigo.requestFocus();
    }

    @FXML void onAjustes() {
        aplicarModo(Modo.AJUSTE);
        txtCodigo.requestFocus();
    }

    @FXML void onProductosBajos() {
        recargar();
    }

    @FXML void onReporteInventario() {
        alerta(Alert.AlertType.INFORMATION, "Reporte de inventario",
                "La generación de reportes en PDF no está disponible en esta versión.");
    }

    @FXML void onReporteMovimientos() {
        alerta(Alert.AlertType.INFORMATION, "Reporte de movimientos",
                "La generación de reportes en PDF no está disponible en esta versión.");
    }

    // ── Formulario ────────────────────────────────────────────────────────

    @FXML void onBuscarProducto() {
        String codigo = txtCodigo.getText().trim();
        if (codigo.isEmpty()) return;

        Optional<Producto> encontrado = productoService.buscarPorCodigo(codigo);
        if (encontrado.isEmpty()) {
            alerta(Alert.AlertType.WARNING, "Producto no encontrado",
                    "No existe un producto con el código \"" + codigo + "\".");
            txtCodigo.selectAll();
            return;
        }
        Producto p = encontrado.get();
        if (!p.isUsaInventario()) {
            alerta(Alert.AlertType.WARNING, "Sin inventario",
                    "\"" + p.getDescripcion() + "\" no maneja inventario.");
            return;
        }
        mostrarProducto(p);
        tablaInventario.getSelectionModel().select(p);
        tablaInventario.scrollTo(p);
        txtCantidad.requestFocus();
    }

    @FXML void onAgregarCantidad() {
        if (productoSeleccionado == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona un producto",
                    "Escanea un código o elige un producto de la tabla.");
            txtCodigo.requestFocus();
            return;
        }

        double cantidad;
        try {
            cantidad = Double.parseDouble(txtCantidad.getText().trim().replace(",", "."));
        } catch (NumberFormatException e) {
            alerta(Alert.AlertType.WARNING, "Cantidad inválida",
                    "Escribe un número válido (ej: 12 o 2.5).");
            txtCantidad.requestFocus();
            return;
        }

        try {
            Producto actualizado = (modo == Modo.AGREGAR)
                    ? productoService.agregarStock(productoSeleccionado.getId(), cantidad)
                    : productoService.ajustarStock(productoSeleccionado.getId(), cantidad);

            productoSeleccionado = actualizado;
            txtCantidad.clear();
            recargar();
            tablaInventario.getSelectionModel().select(
                    lista.stream().filter(p -> p.getId().equals(actualizado.getId())).findFirst().orElse(null));

            alerta(Alert.AlertType.INFORMATION, "Inventario actualizado", String.format(
                    "\"%s\" ahora tiene %s en existencia.",
                    actualizado.getDescripcion(), formatoCantidad(actualizado.getCantidadActual())));
            txtCodigo.clear();
            txtCodigo.requestFocus();

        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo actualizar", e.getMessage());
        }
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void aplicarModo(Modo nuevo) {
        modo = nuevo;
        if (modo == Modo.AGREGAR) {
            lblTituloForm.setText("AGREGAR INVENTARIO");
            lblEtiquetaCantidad.setText("Cantidad a Agregar:");
            btnAplicar.setText("✅ Agregar cantidad a Inventario");
        } else {
            lblTituloForm.setText("AJUSTE DE INVENTARIO");
            lblEtiquetaCantidad.setText("Nueva existencia (conteo físico):");
            btnAplicar.setText("⚖ Fijar existencia");
        }
    }

    private void mostrarProducto(Producto p) {
        productoSeleccionado = p;
        txtCodigo.setText(p.getCodigoBarras());
        labelDescripcion.setText(p.getDescripcion());
        labelCantidadActual.setText(formatoCantidad(p.getCantidadActual())
                + "   (mínimo: " + formatoCantidad(p.getCantidadMinima()) + ")");
    }

    private void limpiarFormulario() {
        productoSeleccionado = null;
        txtCodigo.clear();
        txtCantidad.clear();
        labelDescripcion.setText("—");
        labelCantidadActual.setText("0.00");
    }

    private void configurarColumnas() {
        colCodigo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCodigoBarras()));
        colDescripcion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDescripcion()));
        colActual.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().getCantidadActual()).asObject());
        colMinimo.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().getCantidadMinima()).asObject());
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(estado(c.getValue())));

        formatoCantidad(colActual);
        formatoCantidad(colMinimo);

        colEstado.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                getStyleClass().removeAll("estado-ok", "estado-bajo", "estado-agotado");
                if (empty || v == null) { setText(null); return; }
                setText(v);
                getStyleClass().add(switch (v) {
                    case "AGOTADO" -> "estado-agotado";
                    case "BAJO"    -> "estado-bajo";
                    default        -> "estado-ok";
                });
            }
        });

        // Resaltar filas con stock bajo / agotado
        tablaInventario.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(Producto p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().removeAll("fila-stock-bajo", "fila-agotado");
                if (empty || p == null) return;
                if (p.getCantidadActual() <= 0)             getStyleClass().add("fila-agotado");
                else if (ProductoService.tieneStockBajo(p)) getStyleClass().add("fila-stock-bajo");
            }
        });
    }

    private static String estado(Producto p) {
        if (p.getCantidadActual() <= 0)       return "AGOTADO";
        if (ProductoService.tieneStockBajo(p)) return "BAJO";
        return "OK";
    }

    private void formatoCantidad(TableColumn<Producto, Double> col) {
        col.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : formatoCantidad(v));
            }
        });
    }

    private static String formatoCantidad(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
