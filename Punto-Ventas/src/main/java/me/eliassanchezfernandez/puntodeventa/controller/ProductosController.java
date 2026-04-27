package me.eliassanchezfernandez.puntodeventa.controller;

import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javafx.util.StringConverter;

import java.net.URL;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controlador de la pantalla de Productos (F3).
 *
 * Responsabilidades:
 *  - Listar, buscar, agregar, modificar y eliminar productos
 *  - Validar código de barras duplicado en tiempo real
 *  - Validar precios no negativos
 *  - Validar cantidades de inventario no negativas
 *  - Mostrar / ocultar el panel de formulario en el SplitPane
 */
@Component
public class ProductosController implements Initializable {

    // ── FXML – Toolbar / Búsqueda ─────────────────────────────────────────
    @FXML private SplitPane    splitPane;
    @FXML private TableView<Producto> tablaProductos;
    @FXML private TextField    txtBuscar;
    @FXML private Button       btnNuevo;
    @FXML private Button       btnModificar;
    @FXML private Button       btnEliminar;

    // ── FXML – Columnas ───────────────────────────────────────────────────
    @FXML private TableColumn<Producto, String> colCodigo;
    @FXML private TableColumn<Producto, String> colDescripcion;
    @FXML private TableColumn<Producto, Double> colPrecioCosto;
    @FXML private TableColumn<Producto, Double> colPrecioVenta;
    @FXML private TableColumn<Producto, Double> colPrecioMayor;
    @FXML private TableColumn<Producto, Double> colExistencia;
    @FXML private TableColumn<Producto, String> colDepartamento;

    // ── FXML – Formulario ─────────────────────────────────────────────────
    @FXML private ScrollPane scrollFormulario;
    @FXML private Label      lblTituloForm;

    @FXML private TextField  txtCodigoBarras;
    @FXML private HBox       hboxYaExiste;
    @FXML private Label      lblProductoExistente;

    @FXML private TextField  txtDescripcion;

    @FXML private ToggleGroup toggleTipoVenta;
    @FXML private RadioButton rbUnidad;
    @FXML private RadioButton rbGranel;
    @FXML private RadioButton rbPaquete;

    @FXML private TextField  txtPrecioCosto;
    @FXML private TextField  txtPrecioVenta;
    @FXML private TextField  txtPrecioMayoreo;
    @FXML private Label      lblErrorPrecio;

    @FXML private ComboBox<Departamento> comboDepartamento;

    @FXML private CheckBox   chkUsaInventario;
    @FXML private GridPane   gridInventario;
    @FXML private TextField  txtCantidadActual;
    @FXML private TextField  txtCantidadMinima;
    @FXML private Label      lblErrorInventario;

    @FXML private Button     btnGuardar;
    @FXML private Button     btnCancelarForm;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private ProductoService        productoService;
    @Autowired private DepartamentoRepository departamentoRepo;

    // ── Estado interno ────────────────────────────────────────────────────
    private final ObservableList<Producto> listaProductos =
            FXCollections.observableArrayList();
    /** Producto que se está editando (null = modo nuevo) */
    private Producto productoEnEdicion = null;
    private boolean formularioVisible  = false;

    private static final NumberFormat FMT_MONEDA =
            NumberFormat.getNumberInstance(new Locale("es", "MX"));

    // ── Inicialización ────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configurarColumnas();
        cargarProductos();
        cargarDepartamentos();
        tablaProductos.setItems(listaProductos);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACCIONES DE TOOLBAR
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onNuevo() {
        productoEnEdicion = null;
        lblTituloForm.setText("NUEVO PRODUCTO");
        limpiarFormulario();
        mostrarFormulario(true);
        txtCodigoBarras.requestFocus();
    }

    @FXML
    private void onModificar() {
        Producto sel = tablaProductos.getSelectionModel().getSelectedItem();
        if (sel == null) {
            mostrarAlerta(Alert.AlertType.WARNING,
                    "Selecciona un producto", "Haz clic en un producto de la tabla para modificarlo.");
            return;
        }
        productoEnEdicion = sel;
        lblTituloForm.setText("MODIFICAR PRODUCTO");
        cargarProductoEnFormulario(sel);
        mostrarFormulario(true);
    }

    @FXML
    private void onEliminar() {
        Producto sel = tablaProductos.getSelectionModel().getSelectedItem();
        if (sel == null) {
            mostrarAlerta(Alert.AlertType.WARNING,
                    "Selecciona un producto", "Haz clic en un producto de la tabla para eliminarlo.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "¿Eliminar \"" + sel.getDescripcion() + "\"?\nEsta acción no se puede deshacer.",
                ButtonType.YES, ButtonType.CANCEL);
        confirm.setTitle("Confirmar eliminación");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                productoService.eliminar(sel.getId());
                cargarProductos();
                mostrarFormulario(false);
            }
        });
    }

    @FXML
    private void onTablaClick() {
        // Doble clic → modo editar directamente
        if (tablaProductos.getSelectionModel().getSelectedItem() != null
                && formularioVisible) {
            onModificar();
        }
    }

    @FXML
    private void onBuscar() {
        String texto = txtBuscar.getText().trim();
        if (texto.isEmpty()) {
            tablaProductos.setItems(listaProductos);
        } else {
            String lower = texto.toLowerCase();
            ObservableList<Producto> filtrados = listaProductos.filtered(p ->
                    p.getDescripcion().toLowerCase().contains(lower) ||
                    p.getCodigoBarras().toLowerCase().contains(lower));
            tablaProductos.setItems(filtrados);
        }
    }

    // Sin implementación aún
    @FXML void onDepartamentos() { }
    @FXML void onVentasPeriodo() { }
    @FXML void onPromociones()   { }
    @FXML void onImportar()      { }

    // ─────────────────────────────────────────────────────────────────────
    //  VALIDACIONES EN TIEMPO REAL
    // ─────────────────────────────────────────────────────────────────────

    /** Verifica en tiempo real si el código de barras ya existe */
    @FXML
    private void onCodigoBarrasChanged() {
        String codigo = txtCodigoBarras.getText().trim();
        if (codigo.isEmpty()) {
            ocultarAvisoExiste();
            return;
        }
        Optional<Producto> encontrado = productoService.buscarPorCodigo(codigo);
        encontrado.ifPresentOrElse(p -> {
            // No mostrar aviso si estamos editando ESE mismo producto
            boolean esMismo = productoEnEdicion != null &&
                              productoEnEdicion.getId().equals(p.getId());
            if (!esMismo) {
                hboxYaExiste.setVisible(true);
                hboxYaExiste.setManaged(true);
                lblProductoExistente.setText("Producto: " + p.getDescripcion());
                bloquearFormulario(true);
            } else {
                ocultarAvisoExiste();
            }
        }, this::ocultarAvisoExiste);
    }

    /** Valida que los precios no sean negativos */
    @FXML
    private void onValidarPrecio() {
        boolean hayError = esNegativo(txtPrecioCosto.getText())
                        || esNegativo(txtPrecioVenta.getText())
                        || esNegativo(txtPrecioMayoreo.getText());
        lblErrorPrecio.setVisible(hayError);
        lblErrorPrecio.setManaged(hayError);
    }

    /** Valida que las cantidades de inventario no sean negativas */
    @FXML
    private void onValidarInventario() {
        boolean hayError = esNegativo(txtCantidadActual.getText())
                        || esNegativo(txtCantidadMinima.getText());
        lblErrorInventario.setVisible(hayError);
        lblErrorInventario.setManaged(hayError);
    }

    /** Habilita / deshabilita los campos de inventario según el checkbox */
    @FXML
    private void onToggleInventario() {
        boolean usa = chkUsaInventario.isSelected();
        gridInventario.setDisable(!usa);
        if (!usa) {
            txtCantidadActual.clear();
            txtCantidadMinima.clear();
            lblErrorInventario.setVisible(false);
            lblErrorInventario.setManaged(false);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GUARDAR / CANCELAR
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onGuardarProducto() {

        // 1. Validar campos obligatorios
        if (txtCodigoBarras.getText().trim().isEmpty() ||
            txtDescripcion.getText().trim().isEmpty()) {
            mostrarAlerta(Alert.AlertType.WARNING,
                    "Campos requeridos",
                    "El código de barras y la descripción son obligatorios.");
            return;
        }

        // 2. Código de barras duplicado
        if (hboxYaExiste.isVisible()) {
            mostrarAlerta(Alert.AlertType.ERROR,
                    "Código duplicado",
                    "Ya existe un producto con ese código de barras.");
            return;
        }

        // 3. Precios
        double precioCosto   = parsearDouble(txtPrecioCosto.getText());
        double precioVenta   = parsearDouble(txtPrecioVenta.getText());
        double precioMayoreo = parsearDouble(txtPrecioMayoreo.getText());

        if (precioCosto < 0 || precioVenta < 0 || precioMayoreo < 0) {
            mostrarAlerta(Alert.AlertType.ERROR,
                    "Precios inválidos", "Los precios no pueden ser negativos.");
            return;
        }

        // 4. Inventario
        double cantActual = 0, cantMinima = 0;
        if (chkUsaInventario.isSelected()) {
            cantActual = parsearDouble(txtCantidadActual.getText());
            cantMinima = parsearDouble(txtCantidadMinima.getText());
            if (cantActual < 0 || cantMinima < 0) {
                mostrarAlerta(Alert.AlertType.ERROR,
                        "Cantidades inválidas",
                        "Las cantidades de inventario no pueden ser negativas.");
                return;
            }
        }

        // 5. Construir entidad
        Producto p = (productoEnEdicion != null) ? productoEnEdicion : new Producto();
        p.setCodigoBarras(txtCodigoBarras.getText().trim());
        p.setDescripcion(txtDescripcion.getText().trim());
        p.setTipoVenta(tipoVentaSeleccionado());
        p.setPrecioCosto(precioCosto);
        p.setPrecioVenta(precioVenta);
        p.setPrecioMayoreo(precioMayoreo);
        p.setDepartamento(comboDepartamento.getValue());
        p.setUsaInventario(chkUsaInventario.isSelected());
        p.setCantidadActual(cantActual);
        p.setCantidadMinima(cantMinima);

        // 6. Persistir
        productoService.guardar(p);

        // 7. Refrescar tabla y cerrar formulario
        cargarProductos();
        mostrarFormulario(false);

        mostrarAlerta(Alert.AlertType.INFORMATION,
                "Producto guardado",
                "\"" + p.getDescripcion() + "\" se guardó correctamente.");
    }

    @FXML
    private void onCancelarFormulario() {
        mostrarFormulario(false);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS PRIVADOS
    // ─────────────────────────────────────────────────────────────────────

    /** Configura las celdas de la TableView */
    private void configurarColumnas() {
        colCodigo.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getCodigoBarras()));
        colDescripcion.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getDescripcion()));
        colPrecioCosto.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getPrecioCosto()).asObject());
        colPrecioVenta.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getPrecioVenta()).asObject());
        colPrecioMayor.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getPrecioMayoreo()).asObject());
        colExistencia.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getCantidadActual()).asObject());
        colDepartamento.setCellValueFactory(c -> {
            Departamento d = c.getValue().getDepartamento();
            return new SimpleStringProperty(d != null ? d.getNombre() : "—");
        });

        // Formato moneda en columnas de precio
        formatoMonedaColumna(colPrecioCosto);
        formatoMonedaColumna(colPrecioVenta);
        formatoMonedaColumna(colPrecioMayor);
    }

    @SuppressWarnings("unchecked")
    private void formatoMonedaColumna(TableColumn<Producto, Double> col) {
        col.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                setText((empty || val == null) ? null : "$" + String.format("%.2f", val));
            }
        });
    }

    private void cargarProductos() {
        listaProductos.setAll(productoService.listarTodos());
    }

    private void cargarDepartamentos() {
        List<Departamento> deptos = departamentoRepo.findAll();
        comboDepartamento.setItems(FXCollections.observableArrayList(deptos));
        comboDepartamento.setConverter(new StringConverter<>() {
            @Override public String toString(Departamento d) {
                return d == null ? "— Sin Departamento —" : d.getNombre();
            }
            @Override public Departamento fromString(String s) { return null; }
        });
    }

    /** Hace visible / invisible el panel de formulario en el SplitPane */
    private void mostrarFormulario(boolean mostrar) {
        formularioVisible = mostrar;
        scrollFormulario.setVisible(mostrar);
        scrollFormulario.setManaged(mostrar);
        if (mostrar) {
            splitPane.setDividerPositions(0.55);
        } else {
            splitPane.setDividerPositions(1.0);
            limpiarFormulario();
        }
    }

    private void cargarProductoEnFormulario(Producto p) {
        limpiarFormulario();
        txtCodigoBarras.setText(p.getCodigoBarras());
        txtDescripcion.setText(p.getDescripcion());

        switch (p.getTipoVenta()) {
            case GRANEL  -> rbGranel.setSelected(true);
            case PAQUETE -> rbPaquete.setSelected(true);
            default      -> rbUnidad.setSelected(true);
        }

        txtPrecioCosto.setText(String.valueOf(p.getPrecioCosto()));
        txtPrecioVenta.setText(String.valueOf(p.getPrecioVenta()));
        txtPrecioMayoreo.setText(String.valueOf(p.getPrecioMayoreo()));

        if (p.getDepartamento() != null) {
            comboDepartamento.setValue(p.getDepartamento());
        }

        chkUsaInventario.setSelected(p.isUsaInventario());
        gridInventario.setDisable(!p.isUsaInventario());
        txtCantidadActual.setText(String.valueOf(p.getCantidadActual()));
        txtCantidadMinima.setText(String.valueOf(p.getCantidadMinima()));
    }

    private void limpiarFormulario() {
        txtCodigoBarras.clear();
        txtDescripcion.clear();
        rbUnidad.setSelected(true);
        txtPrecioCosto.clear();
        txtPrecioVenta.clear();
        txtPrecioMayoreo.clear();
        comboDepartamento.setValue(null);
        chkUsaInventario.setSelected(true);
        gridInventario.setDisable(false);
        txtCantidadActual.clear();
        txtCantidadMinima.clear();

        ocultarAvisoExiste();
        lblErrorPrecio.setVisible(false);
        lblErrorPrecio.setManaged(false);
        lblErrorInventario.setVisible(false);
        lblErrorInventario.setManaged(false);
    }

    private void ocultarAvisoExiste() {
        hboxYaExiste.setVisible(false);
        hboxYaExiste.setManaged(false);
        bloquearFormulario(false);
    }

    /** Bloquea / desbloquea los campos debajo del código de barras */
    private void bloquearFormulario(boolean bloquear) {
        txtDescripcion.setDisable(bloquear);
        rbUnidad.setDisable(bloquear);
        rbGranel.setDisable(bloquear);
        rbPaquete.setDisable(bloquear);
        txtPrecioCosto.setDisable(bloquear);
        txtPrecioVenta.setDisable(bloquear);
        txtPrecioMayoreo.setDisable(bloquear);
        comboDepartamento.setDisable(bloquear);
        chkUsaInventario.setDisable(bloquear);
        gridInventario.setDisable(bloquear);
        btnGuardar.setDisable(bloquear);
    }

    private Producto.TipoVenta tipoVentaSeleccionado() {
        if (rbGranel.isSelected())  return Producto.TipoVenta.GRANEL;
        if (rbPaquete.isSelected()) return Producto.TipoVenta.PAQUETE;
        return Producto.TipoVenta.UNIDAD;
    }

    /** Devuelve el double o 0 si el texto es inválido */
    private double parsearDouble(String texto) {
        try {
            return Double.parseDouble(texto.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Retorna true si el texto representa un número negativo */
    private boolean esNegativo(String texto) {
        if (texto == null || texto.trim().isEmpty()) return false;
        try {
            return Double.parseDouble(texto.trim().replace(",", ".")) < 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void mostrarAlerta(Alert.AlertType tipo, String titulo, String mensaje) {
        Alert alert = new Alert(tipo);
        alert.setTitle(titulo);
        alert.setHeaderText(null);
        alert.setContentText(mensaje);
        alert.showAndWait();
    }
}
