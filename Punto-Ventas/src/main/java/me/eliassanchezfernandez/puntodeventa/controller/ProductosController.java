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
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
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
 * Controlador de la pantalla de Productos (F3).
 *
 * La pantalla tiene TRES vistas que se intercambian a pantalla completa:
 *   - vistaTabla         → lista de productos (por defecto)
 *   - vistaFormulario    → formulario de nuevo/editar producto
 *   - vistaDepartamentos → catálogo de departamentos
 *
 * Al guardar, cancelar o terminar una edición siempre se regresa
 * a vistaTabla con la tabla actualizada.
 *
 * Las validaciones de negocio están en ProductoService; aquí solo se
 * convierte el texto de los campos y se muestran los mensajes.
 */
@Component
@Scope("prototype")
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
    @FXML private TableColumn<Producto, String> colEstado;
    @FXML private TextField                     txtBuscar;
    @FXML private Button                        btnNuevo;
    @FXML private Button                        btnModificar;
    @FXML private Button                        btnEliminar;
    @FXML private Button                        btnActivar;
    @FXML private Button                        btnDepartamentos;
    @FXML private Button                        btnVentasPeriodo;
    @FXML private Button                        btnPromociones;

    //tabla departamentos
    @FXML private TableView<Departamento>           tablaDepartamentos;
    @FXML private TableColumn<Departamento, String> colNombreDepartamento;
    @FXML private TextField                         txtBuscarDepartamento;
    @FXML private TextField                         txtNombreDepartamento;
    @FXML private Label                             lblErrorDepartamento;
    @FXML private Button                            btnGuardarDepartamento;

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
    @FXML private Label       lblAvisoStock;
    @FXML private TextField   txtCantidadMinima;
    @FXML private Label       lblErrorInventario;
    @FXML private Button      btnGuardar;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private ProductoService    productoService;
    @Autowired private SesionService      sesionService;
    @Autowired private ApplicationContext springContext;

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
        configurarComboDepartamento();
        cargarProductos();
        cargarDepartamentos();

        tablaProductos.setItems(listaProductos);
        tablaDepartamentos.setItems(listaDepartamentos);
        aplicarPermisos();

        // Aseguramos el estado inicial: tabla visible, formulario oculto
        mostrarVistas(true, false, false);
    }

    /**
     * Botones según permisos (USR-13, PRD-17, HIS-03). El servicio vuelve a
     * verificarlos; aquí solo se evita ofrecer lo que no se puede hacer.
     */
    private void aplicarPermisos() {
        boolean editar = sesionService.tienePermiso(Permiso.EDITAR_PRODUCTOS);
        btnNuevo.setDisable(!editar);
        btnModificar.setDisable(!editar);
        btnEliminar.setDisable(!editar);
        btnActivar.setDisable(!editar);
        btnDepartamentos.setDisable(!editar);
        btnVentasPeriodo.setDisable(!sesionService.tienePermiso(Permiso.VER_HISTORIAL));
        // Promociones se puede consultar; la ventana bloquea guardar sin permiso
    }

    /** Recarga catálogos desde la BD (lo llama MainController al entrar a la pestaña) */
    public void recargar() {
        cargarProductos();
        cargarDepartamentos();
        onBuscar();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACCIONES DE LA TOOLBAR (vista tabla)
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onNuevo() {
        productoEnEdicion = null;
        lblTituloForm.setText("NUEVO PRODUCTO");
        limpiarFormulario();
        txtPrecioCosto.setEditable(true);
        txtPrecioVenta.setEditable(true);
        txtPrecioMayoreo.setEditable(true);
        lblErrorPrecio.setText("⚠ Los precios no pueden ser negativos.");
        mostrarVistas(false, true, false);
        txtCodigoBarras.requestFocus();
    }

    /** Desactiva (descontinúa) o reactiva el producto seleccionado (PRD-09) */
    @FXML
    private void onCambiarActivo() {
        Producto sel = tablaProductos.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona un producto",
                    "Haz clic en un producto de la tabla para activarlo o desactivarlo.");
            return;
        }
        boolean activar = !sel.isActivo();
        if (!Ventanas.confirmar(activar ? "Activar producto" : "Desactivar producto", activar
                ? "¿Volver a vender \"" + sel.getDescripcion() + "\"?"
                : "¿Desactivar \"" + sel.getDescripcion() + "\"? Dejará de aparecer en Ventas, pero conserva su historial.")) {
            return;
        }
        try {
            productoService.cambiarActivo(sel.getId(), activar);
            cargarProductos();
            onBuscar();
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo cambiar", e.getMessage());
        }
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
        if (!Ventanas.confirmar("Confirmar eliminación",
                "¿Eliminar \"" + sel.getDescripcion() + "\"?\nEsta acción no se puede deshacer.")) {
            return;
        }
        try {
            productoService.eliminar(sel.getId());
            cargarProductos();
            onBuscar();
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se puede eliminar", e.getMessage());
        }
    }

    /** Doble clic en fila → abrir edición directamente */
    @FXML
    private void onTablaDobleClick(javafx.scene.input.MouseEvent e) {
        if (!sesionService.tienePermiso(Permiso.EDITAR_PRODUCTOS)) return;   // solo quien puede editar
        if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
            Producto sel = tablaProductos.getSelectionModel().getSelectedItem();
            if (sel != null) abrirEdicion(sel);
        }
    }

    /** Filtra por código, descripción o departamento */
    @FXML
    private void onBuscar() {
        String texto = txtBuscar.getText().trim().toLowerCase();
        if (texto.isEmpty()) {
            tablaProductos.setItems(listaProductos);
        } else {
            tablaProductos.setItems(listaProductos.filtered(p ->
                    p.getDescripcion().toLowerCase().contains(texto) ||
                    p.getCodigoBarras().toLowerCase().contains(texto) ||
                    (p.getDepartamento() != null &&
                     p.getDepartamento().getNombre().toLowerCase().contains(texto))));
        }
    }

    @FXML void onDepartamentos() {
        departamentoEnEdicion = null;
        txtNombreDepartamento.clear();
        txtBuscarDepartamento.clear();
        cargarDepartamentos();
        mostrarVistas(false, false, true);
    }

    @FXML
    private void onNuevoDepartamento() {
        departamentoEnEdicion = null;
        tablaDepartamentos.getSelectionModel().clearSelection();
        txtNombreDepartamento.clear();
        txtNombreDepartamento.requestFocus();
    }

    @FXML
    private void onGuardarDepartamento() {
        String nombre = txtNombreDepartamento.getText().trim();
        if (nombre.isEmpty()) {
            lblErrorDepartamento.setVisible(true);
            lblErrorDepartamento.setManaged(true);
            return;
        }

        Departamento d = (departamentoEnEdicion != null) ? departamentoEnEdicion : new Departamento();
        d.setNombre(nombre);
        try {
            productoService.guardarDepartamento(d);
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
            return;
        }

        cargarDepartamentos();
        onNuevoDepartamento();
        alerta(Alert.AlertType.INFORMATION, "Éxito", "El departamento se ha guardado correctamente.");
    }

    @FXML
    private void onEliminarDepartamento() {
        Departamento sel = tablaDepartamentos.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona un departamento",
                    "Haz clic en un departamento de la tabla para eliminarlo.");
            return;
        }

        if (!Ventanas.confirmar("Confirmar eliminación",
                "¿Eliminar el departamento \"" + sel.getNombre() + "\"?")) {
            return;
        }
        try {
            productoService.eliminarDepartamento(sel);
            cargarDepartamentos();
            onNuevoDepartamento();
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se puede eliminar", e.getMessage());
        }
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
        cargarProductos();   // por si cambió el nombre de algún departamento
        mostrarVistas(true, false, false);
    }

    @FXML
    private void onValidarDepartamento(){
        boolean error = txtNombreDepartamento.getText().trim().isEmpty();
        lblErrorDepartamento.setVisible(error);
        lblErrorDepartamento.setManaged(error);
    }

    /** Abre el historial de ventas con filtro por fechas (ventana modal) */
    @FXML
    void onVentasPeriodo() {
        abrirModal("/fxml/ventas_periodo.fxml", "Ventas por período", 1000, 640);
    }

    /** Abre el catálogo de promociones (ventana modal) */
    @FXML
    void onPromociones() {
        abrirModal("/fxml/promociones.fxml", "Promociones", 1000, 600);
        cargarProductos(); // las promociones no cambian productos, pero refrescamos por consistencia
    }

    @FXML
    void onImportar() {
        alerta(Alert.AlertType.INFORMATION, "Importar",
                "La importación de productos desde archivo no está disponible en esta versión.");
    }

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
                lblErrorDepartamento.setVisible(false);
                lblErrorDepartamento.setManaged(false);
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

        try {
            double precioCosto   = parsear(txtPrecioCosto.getText(),   "Precio costo");
            double precioVenta   = parsear(txtPrecioVenta.getText(),   "Precio venta");
            double precioMayoreo = parsear(txtPrecioMayoreo.getText(), "Precio mayoreo");

            double cantActual = 0, cantMinima = 0;
            if (chkUsaInventario.isSelected()) {
                cantActual = parsear(txtCantidadActual.getText(), "Cantidad actual");
                cantMinima = parsear(txtCantidadMinima.getText(), "Cantidad mínima");
            }

            // En edición, ProductoService ignora la existencia capturada si el
            // producto ya manejaba inventario (solo Inventario F4 y las ventas la cambian).

            // Se arma un objeto nuevo (con el mismo id si es edición) para no
            // modificar la fila de la tabla si el servicio rechaza los datos
            Producto p = new Producto();
            if (productoEnEdicion != null) p.setId(productoEnEdicion.getId());
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
            p.setActivo(productoEnEdicion != null ? productoEnEdicion.isActivo() : Boolean.TRUE);

            // El servicio valida precios, cantidades y código duplicado
            productoService.guardar(p);

            // ← Regresar a la tabla con datos actualizados
            cargarProductos();
            onBuscar();
            mostrarVista(false);

            alerta(Alert.AlertType.INFORMATION, "Producto guardado",
                    "\"" + p.getDescripcion() + "\" se guardó correctamente.");

        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "Datos inválidos", e.getMessage());
        }
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
        // Cambiar precios de un producto existente exige CAMBIAR_PRECIOS (PRD-11)
        boolean puedePrecios = sesionService.tienePermiso(Permiso.CAMBIAR_PRECIOS);
        txtPrecioCosto.setEditable(puedePrecios);
        txtPrecioVenta.setEditable(puedePrecios);
        txtPrecioMayoreo.setEditable(puedePrecios);
        lblErrorPrecio.setText(puedePrecios ? "⚠ Los precios no pueden ser negativos."
                : "Solo un usuario con permiso de cambiar precios puede modificarlos.");
        lblErrorPrecio.setVisible(!puedePrecios);
        lblErrorPrecio.setManaged(!puedePrecios);
        mostrarVista(true);
    }

    private void abrirModal(String fxml, String titulo, double ancho, double alto) {
        try {
            Ventanas.abrirModal(springContext, fxml, titulo,
                    vistaTabla.getScene().getWindow(), ancho, alto);
        } catch (Exception e) {
            e.printStackTrace();
            alerta(Alert.AlertType.ERROR, "Error", "No se pudo abrir la ventana: " + e.getMessage());
        }
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
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActivo() ? "Activo" : "Inactivo"));
        tablaProductos.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(Producto p, boolean empty) {
                super.updateItem(p, empty);
                setStyle(!empty && p != null && !p.isActivo() ? "-fx-opacity: 0.55;" : "");
            }
        });

        formatoMoneda(colPrecioCosto);
        formatoMoneda(colPrecioVenta);
        formatoMoneda(colPrecioMayor);

        // Stock: "—" si el producto no maneja inventario
        colExistencia.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); return; }
                Producto p = getTableRow() != null ? getTableRow().getItem() : null;
                setText(p != null && !p.isUsaInventario() ? "—" : formatoCantidad(v));
            }
        });
    }

    private void formatoMoneda(TableColumn<Producto, Double> col) {
        col.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("$%.2f", v));
            }
        });
    }

    private static String formatoCantidad(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private void cargarProductos() {
        listaProductos.setAll(productoService.listarTodos());
    }

    private void cargarDepartamentos() {
        List<Departamento> deptos = productoService.listarDepartamentos();
        listaDepartamentos.setAll(deptos);
        Departamento seleccionado = comboDepartamento.getValue();
        comboDepartamento.setItems(FXCollections.observableArrayList(deptos));
        if (seleccionado != null) comboDepartamento.setValue(seleccionado);
    }

    private void configurarComboDepartamento() {
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

        // La existencia de un producto con inventario se cambia desde Inventario (F4)
        boolean bloquearStock = p.isUsaInventario();
        txtCantidadActual.setEditable(!bloquearStock);
        lblAvisoStock.setVisible(bloquearStock);
        lblAvisoStock.setManaged(bloquearStock);
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
        txtCantidadActual.setEditable(true);
        lblAvisoStock.setVisible(false);
        lblAvisoStock.setManaged(false);
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

    /**
     * Convierte el texto de un campo numérico. Vacío = 0.
     * Si no es un número válido lanza NegocioException con el nombre del campo.
     */
    private double parsear(String txt, String campo) {
        if (txt == null || txt.trim().isEmpty()) return 0;
        try {
            return Double.parseDouble(txt.trim().replace(",", ".").replace("$", ""));
        } catch (NumberFormatException e) {
            throw new NegocioException("\"" + campo + "\" debe ser un número válido (ej: 12.50).");
        }
    }

    private boolean esNegativo(String txt) {
        if (txt == null || txt.trim().isEmpty()) return false;
        try { return Double.parseDouble(txt.trim().replace(",", ".")) < 0; }
        catch (NumberFormatException e) { return false; }
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
