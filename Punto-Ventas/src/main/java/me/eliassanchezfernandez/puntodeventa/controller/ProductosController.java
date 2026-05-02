package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;

import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import javafx.util.StringConverter;

import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.method.P;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controlador de la pantalla de Productos (F3).
 *
 * La pantalla tiene DOS vistas que se intercambian a pantalla completa:
 *   - vistaTabla      → lista de productos (por defecto)
 *   - vistaFormulario → formulario de nuevo/editar producto
 *
 * Al guardar, cancelar o terminar una edición siempre se regresa
 * a vistaTabla con la tabla actualizada.
 */
@Component
public class ProductosController implements Initializable {

    // ── FXML – Vistas principales ─────────────────────────────────────────
    @FXML private BorderPane vistaTabla;
    @FXML private BorderPane vistaFormulario;
    @FXML private BorderPane vistaDepartamentos; 

    // ── FXML – Tabla ─────────────────────────────────────────────────────
    @FXML private TableView<Producto>           tablaProductos;
    @FXML private TableColumn<Producto, String> colCodigo;
    @FXML private TableColumn<Producto, String> colDescripcion;
    @FXML private TableColumn<Producto, Double> colPrecioCosto;
    @FXML private TableColumn<Producto, Double> colPrecioVenta;
    @FXML private TableColumn<Producto, Double> colPrecioMayor;
    @FXML private TableColumn<Producto, Double> colExistencia;
    @FXML private TableColumn<Producto, String> colDepartamento;
    @FXML private TextField                     txtBuscar;

    //tabla departamentos
    @FXML private TableView<Departamento>          tablaDepartamentos;
    @FXML private TableColumn<Departamento, String> colNombreDepartamento;
    @FXML private TextField                       txtBuscarDepartamento;
    @FXML private TextField                      txtNombreDepartamento;
    @FXML private Label                          lblErrorDepartamento;
    @FXML private Button                         btnGuardarDepartamento;

    // ── FXML – Formulario ─────────────────────────────────────────────────
    @FXML private Label       lblTituloForm;
    @FXML private TextField   txtCodigoBarras;
    @FXML private HBox        hboxYaExiste;
    @FXML private Label       lblProductoExistente;
    @FXML private TextField   txtDescripcion;
    @FXML private ToggleGroup toggleTipoVenta;
    @FXML private RadioButton rbUnidad;
    @FXML private RadioButton rbGranel;
    @FXML private RadioButton rbPaquete;
    @FXML private TextField   txtPrecioCosto;
    @FXML private TextField   txtPrecioVenta;
    @FXML private TextField   txtPrecioMayoreo;
    @FXML private Label       lblErrorPrecio;
    @FXML private ComboBox<Departamento> comboDepartamento;
    @FXML private CheckBox    chkUsaInventario;
    @FXML private GridPane    gridInventario;
    @FXML private TextField   txtCantidadActual;
    @FXML private TextField   txtCantidadMinima;
    @FXML private Label       lblErrorInventario;
    @FXML private Button      btnGuardar;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private ProductoService        productoService;
    @Autowired private DepartamentoRepository departamentoRepo;

    // ── Estado interno ────────────────────────────────────────────────────
    
    
    private final ObservableList<Producto> listaProductos =
            FXCollections.observableArrayList();
    private final ObservableList<Departamento> listaDepartamentos =
            FXCollections.observableArrayList();
    /** null = modo nuevo;  non-null = modo editar */
    private Producto productoEnEdicion = null;
    private Departamento departamentoEnEdicion = null;

    // ─────────────────────────────────────────────────────────────────────
    //  INICIALIZACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {

        configurarColumnas();
        configurarColumnasDepartamentos();
        cargarProductos();
        cargarDepartamentos();


        tablaProductos.setItems(listaProductos);
        tablaDepartamentos.setItems(listaDepartamentos);

        // Aseguramos el estado inicial: tabla visible, formulario oculto
        mostrarVistas(true,false,false);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACCIONES DE LA TOOLBAR (vista tabla)
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onNuevo() {
        productoEnEdicion = null;
        lblTituloForm.setText("NUEVO PRODUCTO");
        limpiarFormulario();
        mostrarVistas(false,true,false);
        txtCodigoBarras.requestFocus();
    }

    @FXML
    private void onModificar() {
        Producto sel = tablaProductos.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alerta(Alert.AlertType.WARNING,
                    "Selecciona un producto",
                    "Haz clic en un producto de la tabla para modificarlo.");
            return;
        }
        abrirEdicion(sel);
    }

    @FXML
    private void onEliminar() {
        Producto sel = tablaProductos.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alerta(Alert.AlertType.WARNING,
                    "Selecciona un producto",
                    "Haz clic en un producto de la tabla para eliminarlo.");
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
            }
        });
    }

    /** Doble clic en fila → abrir edición directamente */
    @FXML
    private void onTablaDobleClick(javafx.scene.input.MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
            Producto sel = tablaProductos.getSelectionModel().getSelectedItem();
            if (sel != null) abrirEdicion(sel);
        }
    }

    @FXML
    private void onBuscar() {
        String texto = txtBuscar.getText().trim().toLowerCase();
        if (texto.isEmpty()) {
            tablaProductos.setItems(listaProductos);
        } else {
            tablaProductos.setItems(listaProductos.filtered(p ->
                    p.getDescripcion().toLowerCase().contains(texto) ||
                    p.getCodigoBarras().toLowerCase().contains(texto)));
        }
    }

    @FXML void onDepartamentos() { 
        departamentoEnEdicion = null;
        txtNombreDepartamento.clear();
        mostrarVistas(false,false,true);
    }

    @FXML 
    private void onNuevoDepartamento() {
        departamentoEnEdicion = null;
        txtNombreDepartamento.clear();
        txtNombreDepartamento.requestFocus();
    }

    @FXML
    private void onGuardarDepartamento() {
        String nombre = txtNombreDepartamento.getText().trim();
        if (nombre.isEmpty()) {
            lblErrorDepartamento.setVisible(true );
            lblErrorDepartamento.setManaged(true);
            return;
        }

        Departamento d = (departamentoEnEdicion != null) ? departamentoEnEdicion : new Departamento();
        d.setNombre(nombre);
        departamentoRepo.save(d);

        cargarDepartamentos();
        onNuevoDepartamento();
        alerta(Alert.AlertType.INFORMATION, "Exito," , "El departamento se ha guardado correctamente.");       
    }

    @FXML 

    private void onEliminarDepartamento() {
        Departamento sel = tablaDepartamentos.getSelectionModel().getSelectedItem();
        if(sel == null) return;

        departamentoRepo.delete(sel);
        cargarDepartamentos();
    }

    @FXML
    private void onBuscarDepartamento(){
        String texto = txtBuscarDepartamento.getText().toLowerCase();
        if (texto.isEmpty()) {
            tablaDepartamentos.setItems(listaDepartamentos);
        } else {
            tablaDepartamentos.setItems(listaDepartamentos.filtered(d ->
                    d.getNombre().toLowerCase().contains(texto)));
        }
    }

    @FXML
    private void onCancelarDepartamento(){
        mostrarVistas(true,false,false);
    }

    @FXML

    private void onValidarDepartamento(){
        boolean error = txtNombreDepartamento.getText().trim().isEmpty();
        lblErrorDepartamento.setVisible(error);
        lblErrorDepartamento.setManaged(error);
    }

    @FXML void onVentasPeriodo() { /* TODO */ }
    @FXML void onPromociones()   { /* TODO */ }
 

    // ─────────────────────────────────────────────────────────────────────
    //  Mostrar Vistas 
    // ─────────────────────────────────────────────────────────────────────

    private void mostrarVistas(boolean tabla, boolean formulario, boolean departamentos) {
        vistaTabla.setVisible(tabla);
        vistaTabla.setManaged(tabla);

        vistaFormulario.setVisible(formulario);
        vistaFormulario.setManaged(formulario);
        
        vistaDepartamentos.setVisible(departamentos);
        vistaDepartamentos.setManaged(departamentos);
    }

    private void configurarColumnasDepartamentos(){
        colNombreDepartamento.setCellValueFactory(c -> 
            new SimpleStringProperty(c.getValue().getNombre()));

            //cargar formulario al seleccionar
        tablaDepartamentos.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                departamentoEnEdicion = sel;
                txtNombreDepartamento.setText(sel.getNombre());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  VALIDACIONES EN TIEMPO REAL (vista formulario)
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onCodigoBarrasChanged() {
        String codigo = txtCodigoBarras.getText().trim();
        if (codigo.isEmpty()) {
            ocultarAvisoExiste();
            return;
        }
        Optional<Producto> encontrado = productoService.buscarPorCodigo(codigo);
        encontrado.ifPresentOrElse(p -> {
            boolean esMismo = productoEnEdicion != null &&
                              productoEnEdicion.getId().equals(p.getId());
            if (!esMismo) {
                hboxYaExiste.setVisible(true);
                hboxYaExiste.setManaged(true);
                lblProductoExistente.setText("Producto: " + p.getDescripcion());
                bloquearCampos(true);
            } else {
                ocultarAvisoExiste();
            }
        }, this::ocultarAvisoExiste);
    }

    @FXML
    private void onValidarPrecio() {
        boolean error = esNegativo(txtPrecioCosto.getText())
                     || esNegativo(txtPrecioVenta.getText())
                     || esNegativo(txtPrecioMayoreo.getText());
        lblErrorPrecio.setVisible(error);
        lblErrorPrecio.setManaged(error);
    }

    @FXML
    private void onValidarInventario() {
        boolean error = esNegativo(txtCantidadActual.getText())
                     || esNegativo(txtCantidadMinima.getText());
        lblErrorInventario.setVisible(error);
        lblErrorInventario.setManaged(error);
    }

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

        if (txtCodigoBarras.getText().trim().isEmpty() ||
            txtDescripcion.getText().trim().isEmpty()) {
            alerta(Alert.AlertType.WARNING, "Campos requeridos",
                    "El código de barras y la descripción son obligatorios.");
            return;
        }

        if (hboxYaExiste.isVisible()) {
            alerta(Alert.AlertType.ERROR, "Código duplicado",
                    "Ya existe un producto con ese código de barras.");
            return;
        }

        double precioCosto   = parsear(txtPrecioCosto.getText());
        double precioVenta   = parsear(txtPrecioVenta.getText());
        double precioMayoreo = parsear(txtPrecioMayoreo.getText());

        if (precioCosto < 0 || precioVenta < 0 || precioMayoreo < 0) {
            alerta(Alert.AlertType.ERROR, "Precios inválidos",
                    "Los precios no pueden ser negativos.");
            return;
        }

        double cantActual = 0, cantMinima = 0;
        if (chkUsaInventario.isSelected()) {
            cantActual = parsear(txtCantidadActual.getText());
            cantMinima = parsear(txtCantidadMinima.getText());
            if (cantActual < 0 || cantMinima < 0) {
                alerta(Alert.AlertType.ERROR, "Cantidades inválidas",
                        "Las cantidades de inventario no pueden ser negativas.");
                return;
            }
        }

        Producto p = (productoEnEdicion != null) ? productoEnEdicion : new Producto();
        p.setCodigoBarras(txtCodigoBarras.getText().trim());
        p.setDescripcion(txtDescripcion.getText().trim());
        p.setTipoVenta(tipoSeleccionado());
        p.setPrecioCosto(precioCosto);
        p.setPrecioVenta(precioVenta);
        p.setPrecioMayoreo(precioMayoreo);
        p.setDepartamento(comboDepartamento.getValue());
        p.setUsaInventario(chkUsaInventario.isSelected());
        p.setCantidadActual(cantActual);
        p.setCantidadMinima(cantMinima);

        productoService.guardar(p);

        // ← Regresar a la tabla con datos actualizados
        cargarProductos();
        mostrarVista(false);

        alerta(Alert.AlertType.INFORMATION, "Producto guardado",
                "\"" + p.getDescripcion() + "\" se guardó correctamente.");
    }

    @FXML
    private void onCancelarFormulario() {
        mostrarVistas(true, false, false);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS PRIVADOS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Alterna entre tabla y formulario.
     * @param mostrarFormulario true = mostrar formulario / false = mostrar tabla
     */
    private void mostrarVista(boolean mostrarFormulario) {
        mostrarVistas(!mostrarFormulario, mostrarFormulario, false);
    }

    private void abrirEdicion(Producto p) {
        productoEnEdicion = p;
        lblTituloForm.setText("MODIFICAR PRODUCTO");
        cargarProductoEnFormulario(p);
        mostrarVista(true);
    }

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

        formatoMoneda(colPrecioCosto);
        formatoMoneda(colPrecioVenta);
        formatoMoneda(colPrecioMayor);
    }

    private void formatoMoneda(TableColumn<Producto, Double> col) {
        col.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("$%.2f", v));
            }
        });
    }

    private void cargarProductos() {
        listaProductos.setAll(productoService.listarTodos());
    }

    private void cargarDepartamentos() {
        List<Departamento> deptos = departamentoRepo.findAll();
        listaDepartamentos.setAll(deptos);
        comboDepartamento.setItems(FXCollections.observableArrayList(deptos));

        comboDepartamento.setConverter(new StringConverter<>() {
            @Override public String toString(Departamento d) {
                return d == null ? "— Sin Departamento —" : d.getNombre();
            }
            @Override public Departamento fromString(String s) { return null; }
        });
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
        if (p.getDepartamento() != null) comboDepartamento.setValue(p.getDepartamento());
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
        bloquearCampos(false);
    }

    private void bloquearCampos(boolean bloquear) {
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

    private Producto.TipoVenta tipoSeleccionado() {
        if (rbGranel.isSelected())  return Producto.TipoVenta.GRANEL;
        if (rbPaquete.isSelected()) return Producto.TipoVenta.PAQUETE;
        return Producto.TipoVenta.UNIDAD;
    }

    private double parsear(String txt) {
        try { return Double.parseDouble(txt.trim().replace(",", ".")); }
        catch (NumberFormatException e) { return 0; }
    }

    private boolean esNegativo(String txt) {
        if (txt == null || txt.trim().isEmpty()) return false;
        try { return Double.parseDouble(txt.trim().replace(",", ".")) < 0; }
        catch (NumberFormatException e) { return false; }
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Alert a = new Alert(tipo);
        a.setTitle(titulo);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }
}