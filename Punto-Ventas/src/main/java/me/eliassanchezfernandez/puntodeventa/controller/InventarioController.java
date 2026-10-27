package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controlador de Inventario (F4).
 *
 * Tabla con todos los productos que manejan inventario (se puede filtrar a
 * "solo bajos") y un formulario con dos modos:
 *   - ENTRADA → suma unidades con proveedor y costo (INV-04)
 *   - AJUSTE  → fija la existencia en un valor exacto con motivo (INV-05)
 * Requiere AJUSTAR_INVENTARIO o autorización de administrador.
 * Todo queda en la bitácora de movimientos (INV-07).
 */
@Component
@Scope("prototype")
public class InventarioController implements Initializable {

    private enum Modo { ENTRADA, AJUSTE }

    // ── FXML – formulario ─────────────────────────────────────────────────
    @FXML private Label     lblTituloForm;
    @FXML private TextField txtCodigo;
    @FXML private TextField txtCantidad;
    @FXML private Label     labelDescripcion;
    @FXML private Label     labelCantidadActual;
    @FXML private Label     lblEtiquetaCantidad;
    @FXML private VBox      vboxEntrada;
    @FXML private ComboBox<Proveedor> cmbProveedor;
    @FXML private Label     lblSinProveedores;
    @FXML private Label     lblVisitanHoy;
    @FXML private Button    btnProveedores;
    @FXML private Button    btnOrdenes;
    @FXML private TextField txtCosto;
    @FXML private VBox      vboxAjuste;
    @FXML private ComboBox<String> comboMotivo;
    @FXML private Button    btnAplicar;
    @FXML private ToggleButton btnSoloBajos;

    // ── FXML – tabla ─────────────────────────────────────────────────────
    @FXML private TableView<Producto>           tablaInventario;
    @FXML private TableColumn<Producto, String> colCodigo;
    @FXML private TableColumn<Producto, String> colDescripcion;
    @FXML private TableColumn<Producto, Double> colActual;
    @FXML private TableColumn<Producto, Double> colMinimo;
    @FXML private TableColumn<Producto, String> colEstado;

    @Autowired private ProductoService     productoService;
    @Autowired private ProveedorService    proveedorService;
    @Autowired private SesionService       sesionService;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private ApplicationContext  springContext;

    private final ObservableList<Producto> lista = FXCollections.observableArrayList();
    private Producto productoSeleccionado = null;
    private Modo modo = Modo.ENTRADA;

    @Override public void initialize(URL url, ResourceBundle rb) {
        configurarColumnas();
        tablaInventario.setItems(lista);
        tablaInventario.setPlaceholder(new Label("No hay productos con inventario."));
        comboMotivo.setItems(FXCollections.observableArrayList(
                "Conteo físico", "Merma", "Robo o extravío", "Caducidad", "Devolución a proveedor", "Otro"));
        comboMotivo.setEditable(true);

        // Al seleccionar una fila se carga en el formulario
        tablaInventario.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            if (p != null) mostrarProducto(p);
        });

        boolean gestiona = sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES);
        for (Button b : List.of(btnProveedores, btnOrdenes)) {
            b.setVisible(gestiona);
            b.setManaged(gestiona);
        }
        aplicarModo(Modo.ENTRADA);
        recargar();
    }

    /** Recarga la tabla (lo llama MainController al entrar a la pestaña) */
    public void recargar() {
        recargarProveedores();
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

    /** Combo de proveedores activos y el aviso de quiénes visitan hoy; un fallo aquí no estorba al inventario */
    private void recargarProveedores() {
        try {
            Proveedor elegido = cmbProveedor.getValue();
            List<Proveedor> activos = proveedorService.listarActivos();
            cmbProveedor.setItems(FXCollections.observableArrayList(activos));
            if (elegido != null) {
                activos.stream().filter(p -> p.getId().equals(elegido.getId())).findFirst().ifPresent(cmbProveedor::setValue);
            }
            lblSinProveedores.setVisible(activos.isEmpty());
            lblSinProveedores.setManaged(activos.isEmpty());

            List<Proveedor> hoy = proveedorService.queVisitanHoy();
            lblVisitanHoy.setText(hoy.isEmpty() ? "Hoy no visita ningún proveedor."
                    : "Proveedores que visitan hoy: " + hoy.stream()
                        .map(p -> p.getNombre() + (p.getContacto() != null ? " (" + p.getContacto() + ")" : ""))
                        .collect(java.util.stream.Collectors.joining(", ")));
        } catch (RuntimeException e) {
            lblVisitanHoy.setText("");
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────

    @FXML void onProveedores() {
        abrirModal("/fxml/proveedores.fxml", "Proveedores", 980, 620);
        recargarProveedores();
    }

    @FXML void onOrdenesCompra() {
        abrirModal("/fxml/ordenes_compra.fxml", "Órdenes de compra", 1100, 680);
        recargar();
    }

    private void abrirModal(String fxml, String titulo, double ancho, double alto) {
        try {
            Ventanas.abrirModal(springContext, fxml, titulo, tablaInventario.getScene().getWindow(), ancho, alto);
        } catch (Exception e) {
            e.printStackTrace();
            alerta(Alert.AlertType.ERROR, "Error", "No se pudo abrir la ventana: " + e.getMessage());
        }
    }

    @FXML void onAgregar() {
        aplicarModo(Modo.ENTRADA);
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
                "La generación de reportes en PDF no está disponible en esta versión. "
                + "Usa \"Productos Bajos\" para ver los faltantes.");
    }

    /** Bitácora de movimientos de inventario (INV-07) */
    @FXML void onReporteMovimientos() {
        try {
            Ventanas.abrirModal(springContext, "/fxml/movimientos.fxml", "Movimientos de inventario",
                    tablaInventario.getScene().getWindow(), 1050, 600);
        } catch (Exception e) {
            e.printStackTrace();
            alerta(Alert.AlertType.ERROR, "Error", "No se pudo abrir la ventana: " + e.getMessage());
        }
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

        // Validar la captura ANTES de pedir cualquier autorización
        double costo = 0;
        String motivo = null;
        if (modo == Modo.ENTRADA) {
            if (cmbProveedor.getValue() == null) {
                alerta(Alert.AlertType.WARNING, "Proveedor requerido", cmbProveedor.getItems().isEmpty()
                        ? "Aún no hay proveedores. Da de alta uno en el botón Proveedores."
                        : "Elige el proveedor de la entrada.");
                cmbProveedor.requestFocus();
                return;
            }
            try {
                costo = txtCosto.getText().isBlank() ? 0
                        : Double.parseDouble(txtCosto.getText().trim().replace("$", "").replace(",", ""));
            } catch (NumberFormatException e) {
                alerta(Alert.AlertType.WARNING, "Costo inválido", "Escribe el costo unitario como número (ej: 12.50).");
                txtCosto.requestFocus();
                return;
            }
            if (costo < 0) {
                alerta(Alert.AlertType.WARNING, "Costo inválido", "El costo no puede ser negativo.");
                return;
            }
        } else {
            motivo = comboMotivo.getEditor().getText();
            if (motivo == null || motivo.isBlank()) motivo = comboMotivo.getValue();
            if (motivo == null || motivo.isBlank()) {
                alerta(Alert.AlertType.WARNING, "Motivo requerido",
                        "Indica el motivo del ajuste (merma, robo, caducidad, conteo...).");
                comboMotivo.requestFocus();
                return;
            }
        }

        // Permiso propio o autorización de administrador
        List<Autorizacion> auts = List.of();
        if (!sesionService.tienePermiso(Permiso.AJUSTAR_INVENTARIO)) {
            Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, Permiso.AJUSTAR_INVENTARIO);
            if (aut.isEmpty()) return;
            auts = List.of(aut.get());
        }

        try {
            Producto actualizado;
            if (modo == Modo.ENTRADA) {
                actualizado = productoService.registrarEntradaDeProveedor(productoSeleccionado.getId(), cantidad,
                        cmbProveedor.getValue().getId(), null, costo, auts);
            } else {
                actualizado = productoService.ajustarStock(productoSeleccionado.getId(), cantidad, motivo, auts);
            }

            productoSeleccionado = actualizado;
            txtCantidad.clear();
            cmbProveedor.setValue(null);
            txtCosto.clear();
            comboMotivo.setValue(null);          // el motivo no se arrastra al siguiente ajuste
            comboMotivo.getEditor().clear();
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
        boolean entrada = modo == Modo.ENTRADA;
        vboxEntrada.setVisible(entrada);
        vboxEntrada.setManaged(entrada);
        vboxAjuste.setVisible(!entrada);
        vboxAjuste.setManaged(!entrada);
        if (entrada) {
            lblTituloForm.setText("ENTRADA DE MERCANCÍA");
            lblEtiquetaCantidad.setText("Cantidad que entra:");
            btnAplicar.setText("✅ Registrar entrada");
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
                + "   (mínimo: " + formatoCantidad(p.getCantidadMinima())
                + ", costo: $" + String.format("%.2f", p.getPrecioCosto()) + ")");
        if (txtCosto.getText().isBlank() && p.getPrecioCosto() > 0) {
            txtCosto.setPromptText(String.format("%.2f", p.getPrecioCosto()));
        }
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
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
