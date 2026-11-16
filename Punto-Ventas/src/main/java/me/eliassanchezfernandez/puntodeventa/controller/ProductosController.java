package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.service.CatalogoCodigosService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoImagenService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.UnidadService;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Supplier;

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
    @FXML private TableColumn<Producto, Long>   colImagen;
    @FXML private TableColumn<Producto, String> colCodigo;
    @FXML private TableColumn<Producto, String> colDescripcion;
    @FXML private TableColumn<Producto, Double> colPrecioCosto;
    @FXML private TableColumn<Producto, Double> colPrecioVenta;
    @FXML private TableColumn<Producto, Double> colPrecioMayor;
    @FXML private TableColumn<Producto, Double> colExistencia;
    @FXML private TableColumn<Producto, String> colDepartamento;
    @FXML private TableColumn<Producto, String> colProveedor;
    @FXML private TableColumn<Producto, String> colEstado;
    @FXML private TextField                     txtBuscar;
    @FXML private Button                        btnNuevo;
    @FXML private Button                        btnModificar;
    @FXML private Button                        btnEliminar;
    @FXML private Button                        btnActivar;
    @FXML private Button                        btnDepartamentos;
    @FXML private Button                        btnVentasPeriodo;
    @FXML private Button                        btnPromociones;
    @FXML private Button                        btnImportar;
    @FXML private Button                        btnCambiarIva;
    @FXML private Label                         lblSeleccion;
    @FXML private Button                        btnAplicarIvaDepartamento;

    //tabla departamentos
    @FXML private TableView<Departamento>           tablaDepartamentos;
    @FXML private TableColumn<Departamento, String> colNombreDepartamento;
    @FXML private TableColumn<Departamento, String> colIvaDepartamento;
    @FXML private ComboBox<TipoIva>                 comboIvaDepartamento;
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
    // Autollenado por código de barras (ver sección CATÁLOGO DE CÓDIGOS)
    @FXML private Button      btnBuscarCatalogo;
    @FXML private HBox        hboxCatalogo;
    @FXML private Label       lblCatalogoTitulo;
    @FXML private Label       lblCatalogoDetalle;
    @FXML private Label       lblCatalogoAtribucion;
    @FXML private Button      btnCatalogoUsar;
    @FXML private Button      btnCatalogoDeshacer;
    @FXML private StackPane   zonaImagen;
    @FXML private ImageView   imgProducto;
    @FXML private Label       lblSinImagen;
    @FXML private Button      btnQuitarImagen;
    @FXML private Label       lblImagenEstado;
    @FXML private ToggleGroup toggleTipoVenta;
    @FXML private RadioButton rbUnidad;
    @FXML private RadioButton rbGranel;
    @FXML private RadioButton rbPaquete;
    @FXML private TextField   txtPrecioCosto;
    @FXML private TextField   txtPrecioVenta;
    @FXML private TextField   txtPrecioMayoreo;
    @FXML private Label       lblErrorPrecio;
    @FXML private ComboBox<Departamento> comboDepartamento;
    @FXML private ComboBox<TipoIva>      comboIva;
    @FXML private Label                  lblAyudaIva;
    @FXML private ComboBox<Proveedor> comboProveedor;
    @FXML private Button      btnNuevoProveedor;
    @FXML private ComboBox<Unidad> comboUnidad;
    @FXML private TextField   txtClaveProdServ;
    @FXML private TextField   txtClaveUnidad;
    @FXML private ComboBox<me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.Entrada> comboObjetoImp;
    @FXML private Label       lblClavesDefecto;
    @FXML private CheckBox    chkUsaInventario;
    @FXML private GridPane    gridInventario;
    @FXML private TextField   txtCantidadActual;
    @FXML private Label       lblAvisoStock;
    @FXML private TextField   txtCantidadMinima;
    @FXML private Label       lblErrorInventario;
    @FXML private Button      btnGuardar;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private ProductoService    productoService;
    @Autowired private ProductoImagenService productoImagenService;
    @Autowired private CatalogoCodigosService catalogoCodigosService;
    @Autowired private ImagenProductoCache   imagenes;
    @Autowired private SesionService      sesionService;
    @Autowired private UnidadService      unidadService;
    @Autowired private ProveedorService   proveedorService;
    @Autowired private ApplicationContext springContext;

    // ── Estado interno ────────────────────────────────────────────────────

    private final ObservableList<Producto> listaProductos =
            FXCollections.observableArrayList();
    private final ObservableList<Departamento> listaDepartamentos =
            FXCollections.observableArrayList();
    /** null = modo nuevo;  non-null = modo editar */
    private Producto productoEnEdicion = null;
    private Departamento departamentoEnEdicion = null;
    /** true mientras se llena el formulario con un producto existente (no proponer IVA del departamento) */
    private boolean cargandoFormulario;

    // ── Imagen del formulario ────────────────────────────────────────────
    /** Imagen elegida y ya normalizada, pendiente de guardarse con el producto (null = sin cambio) */
    private ProductoImagenService.Normalizada imagenPendiente;
    private String origenImagen;
    /** true = al guardar se quita la imagen que el producto tenía */
    private boolean quitarImagen;
    /** true = el producto en edición tiene imagen guardada en la base */
    private boolean imagenGuardada;
    /** Cuenta de peticiones de imagen: una respuesta tardía de otro producto no pisa la actual */
    private int secuenciaImagen;
    private static final String AYUDA_IMAGEN = "JPG o PNG hasta 10 MB. Se guarda reducida (512 px) en la base de datos.";

    // ─────────────────────────────────────────────────────────────────────
    //  INICIALIZACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {

        configurarColumnas();
        configurarColumnasDepartamentos();
        configurarComboDepartamento();
        configurarComboProveedor();
        configurarComboUnidad();
        configurarComboIva();
        configurarZonaImagen();
        configurarCatalogo();
        cargarProductos();
        cargarDepartamentos();
        cargarProveedores();

        tablaProductos.setItems(listaProductos);
        tablaDepartamentos.setItems(listaDepartamentos);
        // Varios a la vez (Shift o Ctrl/Cmd + clic) solo para acciones en grupo (Cambiar IVA…);
        // Modificar, Eliminar y Activar siguen siendo de uno en uno
        tablaProductos.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tablaProductos.getSelectionModel().getSelectedIndices().addListener(
                (javafx.collections.ListChangeListener<Integer>) c -> actualizarBotonesSeleccion());
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
        btnImportar.setDisable(!editar);
        actualizarBotonesSeleccion();
        btnVentasPeriodo.setDisable(!sesionService.tienePermiso(Permiso.VER_HISTORIAL));
        // Promociones se puede consultar; la ventana bloquea guardar sin permiso
        // Dar de alta proveedores desde el formulario exige GESTIONAR_PROVEEDORES (elegir uno, no)
        boolean gestionaProveedores = sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES);
        btnNuevoProveedor.setVisible(gestionaProveedores);
        btnNuevoProveedor.setManaged(gestionaProveedores);
    }

    /**
     * Con un producto seleccionado todo funciona como siempre; con varios solo quedan las
     * acciones en grupo (Cambiar IVA…) y se avisa cuántos hay.
     */
    private void actualizarBotonesSeleccion() {
        boolean editar = sesionService.tienePermiso(Permiso.EDITAR_PRODUCTOS);
        int n = tablaProductos.getSelectionModel().getSelectedItems().size();
        boolean varios = n > 1;
        btnModificar.setDisable(!editar || varios);
        btnEliminar.setDisable(!editar || varios);
        btnActivar.setDisable(!editar || varios);
        btnCambiarIva.setDisable(!editar || n == 0);
        btnCambiarIva.setText(varios ? "Cambiar IVA a " + n + "…" : "Cambiar IVA…");
        lblSeleccion.setText(n + " seleccionados");
        lblSeleccion.setVisible(varios);
        lblSeleccion.setManaged(varios);
    }

    /** Pone el mismo IVA a todos los productos seleccionados (una operación, queda en la bitácora) */
    @FXML
    private void onCambiarIvaSeleccionados() {
        List<Producto> sel = new java.util.ArrayList<>(tablaProductos.getSelectionModel().getSelectedItems());
        sel.removeIf(java.util.Objects::isNull);
        if (sel.isEmpty()) {
            alerta(Alert.AlertType.WARNING, "Selecciona productos",
                    "Haz clic en los productos de la tabla. Para elegir varios, mantén Shift o Ctrl (Cmd en Mac).");
            return;
        }
        // El IVA que ya comparten todos (si es el mismo) queda propuesto
        TipoIva comun = sel.get(0).getIvaOTienda();
        for (Producto p : sel) if (p.getIvaOTienda() != comun) { comun = TipoIva.TASA_16; break; }
        javafx.scene.control.ChoiceDialog<TipoIva> dlg = new javafx.scene.control.ChoiceDialog<>(comun, TipoIva.values());
        dlg.setTitle("Cambiar IVA");
        dlg.setHeaderText(sel.size() == 1
                ? "IVA de \"" + sel.get(0).getDescripcion() + "\""
                : "IVA de los " + sel.size() + " productos seleccionados");
        dlg.setContentText("Nuevo IVA:");
        if (vistaTabla.getScene() != null) dlg.initOwner(vistaTabla.getScene().getWindow());
        Ventanas.estilizar(dlg);
        java.util.Optional<TipoIva> elegido = dlg.showAndWait();
        if (elegido.isEmpty()) return;
        try {
            int cambiados = productoService.cambiarIva(sel.stream().map(Producto::getId).toList(), elegido.get());
            cargarProductos();
            onBuscar();
            alerta(Alert.AlertType.INFORMATION, "IVA cambiado", cambiados == 0
                    ? "Los productos ya tenían «" + elegido.get().getEtiqueta() + "». No se cambió nada."
                    : (cambiados == 1 ? "Se cambió 1 producto" : "Se cambiaron " + cambiados + " productos")
                            + " a «" + elegido.get().getEtiqueta() + "». Queda anotado en la bitácora."
                            + " Los precios no cambian: el IVA ya va incluido.");
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo cambiar el IVA", e.getMessage());
        }
    }

    /** Recarga catálogos desde la BD (lo llama MainController al entrar a la pestaña) */
    public void recargar() {
        cargarUnidades();
        cargarProductos();
        cargarDepartamentos();
        cargarProveedores();
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
        lblErrorPrecio.setText("Los precios no pueden ser negativos.");
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

    /** Filtra por código, descripción, departamento o proveedor habitual */
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
                     p.getDepartamento().getNombre().toLowerCase().contains(texto)) ||
                    (p.getProveedor() != null &&
                     p.getProveedor().getNombre().toLowerCase().contains(texto))));
        }
    }

    @FXML void onDepartamentos() {
        departamentoEnEdicion = null;
        txtNombreDepartamento.clear();
        comboIvaDepartamento.setValue(null);
        txtBuscarDepartamento.clear();
        cargarDepartamentos();
        mostrarVistas(false, false, true);
    }

    @FXML
    private void onNuevoDepartamento() {
        departamentoEnEdicion = null;
        tablaDepartamentos.getSelectionModel().clearSelection();
        txtNombreDepartamento.clear();
        comboIvaDepartamento.setValue(null);
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

        // Objeto nuevo (con el mismo id si es edición): si el servicio rechaza el nombre, la fila
        // de la tabla y el combo siguen mostrando el nombre real, no el que se intentó
        Departamento d = new Departamento();
        if (departamentoEnEdicion != null) {
            d.setId(departamentoEnEdicion.getId());
            // Conservar lo que esta pantalla no edita (la clave SAT se captura en Configuración → Facturación)
            d.setClaveProdServ(departamentoEnEdicion.getClaveProdServ());
        }
        d.setNombre(nombre);
        d.setIvaPorDefecto(comboIvaDepartamento.getValue());
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

    /** Pone el IVA guardado del departamento a todos sus productos que ya existen */
    @FXML
    private void onAplicarIvaDepartamento() {
        Departamento d = departamentoEnEdicion;
        if (d == null || d.getId() == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona un departamento",
                    "Haz clic en un departamento de la tabla.");
            return;
        }
        if (!java.util.Objects.equals(comboIvaDepartamento.getValue(), d.getIvaPorDefecto())) {
            alerta(Alert.AlertType.WARNING, "Guarda primero",
                    "Cambiaste el IVA del departamento pero aún no lo guardas. Pulsa «Guardar Departamento» y luego vuelve a intentarlo.");
            return;
        }
        TipoIva iva = TipoIva.oTienda(d.getIvaPorDefecto());
        long cuantos = listaProductos.stream()
                .filter(p -> p.getDepartamento() != null && d.getId().equals(p.getDepartamento().getId()))
                .count();
        if (cuantos == 0) {
            alerta(Alert.AlertType.INFORMATION, "Sin productos", "«" + d.getNombre() + "» todavía no tiene productos.");
            return;
        }
        if (!Ventanas.confirmar("Aplicar IVA del departamento",
                "¿Poner «" + iva.getEtiqueta() + "» a " + (cuantos == 1 ? "el producto" : "los " + cuantos + " productos")
                        + " de «" + d.getNombre() + "»?\nLos que ya tienen ese IVA no cambian. Los precios no cambian.")) {
            return;
        }
        try {
            int cambiados = productoService.aplicarIvaDelDepartamento(d.getId());
            cargarProductos();
            alerta(Alert.AlertType.INFORMATION, "IVA aplicado", cambiados == 0
                    ? "Todos los productos de «" + d.getNombre() + "» ya tenían ese IVA."
                    : (cambiados == 1 ? "Se cambió 1 producto." : "Se cambiaron " + cambiados + " productos.")
                            + " Queda anotado en la bitácora.");
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo aplicar el IVA", e.getMessage());
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

    /** Asistente para migrar el catálogo desde otro punto de venta o Excel/CSV (ventana modal) */
    @FXML
    void onImportar() {
        boolean huboCambios;
        try {
            huboCambios = ImportarDialogo.mostrar(springContext, vistaTabla.getScene().getWindow());
        } catch (RuntimeException e) {
            e.printStackTrace();
            alerta(Alert.AlertType.ERROR, "Error", "No se pudo abrir el asistente de importación: " + e.getMessage());
            return;
        }
        if (huboCambios) {
            cargarProductos();
            cargarDepartamentos();
            onBuscar();
        }
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
        colIvaDepartamento.setCellValueFactory(c -> new SimpleStringProperty(
            c.getValue().getIvaPorDefecto() == null ? "—" : c.getValue().getIvaPorDefecto().getEtiqueta()));
        // Propuesta de IVA del departamento: nada (null) o una tasa concreta; "usar el de la tienda" sobra aquí
        comboIvaDepartamento.setItems(FXCollections.observableArrayList(null, TipoIva.TASA_16, TipoIva.TASA_0, TipoIva.EXENTO));
        comboIvaDepartamento.setConverter(new StringConverter<>() {
            @Override public String toString(TipoIva t) { return t == null ? "— Sin propuesta (usar el de la tienda) —" : t.getEtiqueta(); }
            @Override public TipoIva fromString(String s) { return null; }
        });
        comboIvaDepartamento.setValue(null);

            //cargar formulario al seleccionar
        tablaDepartamentos.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            btnAplicarIvaDepartamento.setDisable(sel == null
                    || !sesionService.tienePermiso(Permiso.EDITAR_PRODUCTOS));
            if (sel != null) {
                departamentoEnEdicion = sel;
                txtNombreDepartamento.setText(sel.getNombre());
                comboIvaDepartamento.setValue(sel.getIvaPorDefecto());
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
            p.setIva(comboIva.getValue() != null ? comboIva.getValue() : TipoIva.TIENDA);
            p.setProveedor(comboProveedor.getValue());
            p.setUnidad(comboUnidad.getValue());
            p.setClaveProdServ(txtClaveProdServ.getText());
            p.setClaveUnidad(txtClaveUnidad.getText());
            p.setObjetoImp(comboObjetoImp.getValue() != null ? comboObjetoImp.getValue().clave() : null);
            p.setUsaInventario(chkUsaInventario.isSelected());
            p.setCantidadActual(cantActual);
            p.setCantidadMinima(cantMinima);
            p.setActivo(productoEnEdicion != null ? productoEnEdicion.isActivo() : Boolean.TRUE);

            // Mayoreo más caro que el precio normal: se avisa, no se bloquea (decisión del dueño)
            Optional<String> avisoMayoreo = ProductoService.advertenciaMayoreo(p);
            if (avisoMayoreo.isPresent() && !Ventanas.confirmar("Precio de mayoreo", avisoMayoreo.get())) return;

            // El servicio valida precios, cantidades y código duplicado
            Producto guardado = productoService.guardar(p);
            String avisoImagen = aplicarImagen(guardado.getId());

            // ← Regresar a la tabla con datos actualizados
            cargarProductos();
            onBuscar();
            mostrarVista(false);

            if (avisoImagen != null) {
                alerta(Alert.AlertType.WARNING, "Producto guardado, imagen no",
                        "\"" + p.getDescripcion() + "\" se guardó, pero la imagen no: " + avisoImagen);
            } else {
                alerta(Alert.AlertType.INFORMATION, "Producto guardado",
                        "\"" + p.getDescripcion() + "\" se guardó correctamente.");
            }

        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "Datos inválidos", e.getMessage());
        }
    }

    /** Guarda o quita la imagen elegida en el formulario; devuelve el mensaje de error o null si todo fue bien */
    private String aplicarImagen(Long productoId) {
        try {
            if (imagenPendiente != null) {
                productoImagenService.guardar(productoId, imagenPendiente, origenImagen);
            } else if (quitarImagen && productoEnEdicion != null) {
                productoImagenService.quitar(productoId);
            } else {
                return null;
            }
            imagenes.invalidar(productoId);
            return null;
        } catch (NegocioException e) {
            return e.getMessage();
        } finally {
            imagenPendiente = null;
            quitarImagen = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  IMAGEN DEL PRODUCTO (archivo, arrastrar, portapapeles, URL)
    // ─────────────────────────────────────────────────────────────────────

    private void configurarZonaImagen() {
        zonaImagen.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (e.getGestureSource() != zonaImagen && (db.hasFiles() || db.hasImage() || db.hasUrl())) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!zonaImagen.getStyleClass().contains("imagen-zona-activa")) {
                    zonaImagen.getStyleClass().add("imagen-zona-activa");
                }
            }
            e.consume();
        });
        zonaImagen.setOnDragExited(e -> zonaImagen.getStyleClass().remove("imagen-zona-activa"));
        zonaImagen.setOnDragDropped(this::onImagenSoltada);
        zonaImagen.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) onElegirImagen();
        });
    }

    private void onImagenSoltada(DragEvent e) {
        Dragboard db = e.getDragboard();
        boolean tomado = false;
        if (db.hasFiles() && !db.getFiles().isEmpty()) {
            File f = db.getFiles().get(0);
            procesarImagen(() -> ProductoImagenService.leerArchivo(f.toPath()), "archivo " + f.getName());
            tomado = true;
        } else if (db.hasImage()) {
            Image img = db.getImage();
            procesarImagen(() -> bytesDeImagen(img), "imagen arrastrada");
            tomado = true;
        } else if (db.hasUrl() && db.getUrl() != null && db.getUrl().startsWith("http")) {
            String url = db.getUrl();
            procesarImagen(() -> productoImagenService.descargarDesdeUrl(url), "URL " + url);
            tomado = true;
        }
        e.setDropCompleted(tomado);
        e.consume();
    }

    @FXML
    private void onElegirImagen() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elegir imagen del producto");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Imágenes (PNG, JPG, GIF, BMP)", ProductoImagenService.extensiones()),
                new FileChooser.ExtensionFilter("Todos los archivos", "*.*"));
        File f = fc.showOpenDialog(zonaImagen.getScene().getWindow());
        if (f == null) return;
        procesarImagen(() -> ProductoImagenService.leerArchivo(f.toPath()), "archivo " + f.getName());
    }

    @FXML
    private void onPegarImagen() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasFiles() && !cb.getFiles().isEmpty()) {
            File f = cb.getFiles().get(0);
            procesarImagen(() -> ProductoImagenService.leerArchivo(f.toPath()), "archivo " + f.getName());
        } else if (cb.hasImage()) {
            Image img = cb.getImage();
            procesarImagen(() -> bytesDeImagen(img), "portapapeles");
        } else if (cb.hasString() && cb.getString() != null && cb.getString().trim().startsWith("http")) {
            String url = cb.getString().trim();
            procesarImagen(() -> productoImagenService.descargarDesdeUrl(url), "URL " + url);
        } else {
            alerta(Alert.AlertType.INFORMATION, "Portapapeles vacío",
                    "Copia primero una imagen, un archivo de imagen o la dirección (URL) de una imagen.");
        }
    }

    @FXML
    private void onImagenDesdeUrl() {
        Optional<String> url = Ventanas.pedirTexto("Imagen desde internet",
                "Pega la dirección (URL) de la imagen", "https://...");
        if (url.isEmpty() || url.get().isBlank()) return;
        String u = url.get().trim();
        procesarImagen(() -> productoImagenService.descargarDesdeUrl(u), "URL " + u);
    }

    @FXML
    private void onQuitarImagen() {
        secuenciaImagen++;
        imagenPendiente = null;
        quitarImagen = productoEnEdicion != null && imagenGuardada;
        mostrarVistaPrevia(null);
        lblImagenEstado.setText(quitarImagen ? "La imagen se quitará al guardar." : AYUDA_IMAGEN);
    }

    private static byte[] bytesDeImagen(Image img) {
        byte[] b = ImagenProductoCache.aPng(img);
        if (b == null) throw new NegocioException("No se pudo leer la imagen del portapapeles.");
        return b;
    }

    /**
     * Lee/descarga y normaliza la imagen FUERA del hilo de JavaFX (una foto de 5 MB tarda
     * un par de segundos en una PC vieja) y deja la vista previa lista. Nada se guarda
     * hasta "Guardar Producto".
     */
    private void procesarImagen(Supplier<byte[]> fuente, String origen) {
        int mia = ++secuenciaImagen;
        lblImagenEstado.setText("Procesando imagen…");
        Thread t = new Thread(() -> {
            ProductoImagenService.Normalizada n;
            try {
                n = ProductoImagenService.normalizar(fuente.get());
            } catch (NegocioException e) {
                Platform.runLater(() -> {
                    if (mia != secuenciaImagen) return;
                    lblImagenEstado.setText(AYUDA_IMAGEN);
                    alerta(Alert.AlertType.ERROR, "Imagen no válida", e.getMessage());
                });
                return;
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (mia != secuenciaImagen) return;
                    lblImagenEstado.setText(AYUDA_IMAGEN);
                    alerta(Alert.AlertType.ERROR, "Imagen no válida", "No se pudo procesar la imagen: " + e.getMessage());
                });
                return;
            }
            Image vista = ImagenProductoCache.decodificar(n.imagen(), 0);
            Platform.runLater(() -> {
                if (mia != secuenciaImagen) return;
                imagenPendiente = n;
                origenImagen = origen;
                quitarImagen = false;
                mostrarVistaPrevia(vista);
                lblImagenEstado.setText(n.ancho() + " × " + n.alto() + " px · " + Math.max(1, n.imagen().length / 1024)
                        + " KB · se guarda con el producto");
            });
        }, "imagen-producto-formulario");
        t.setDaemon(true);
        t.start();
    }

    private void mostrarVistaPrevia(Image img) {
        imgProducto.setImage(img);
        lblSinImagen.setVisible(img == null);
        lblSinImagen.setManaged(img == null);
        btnQuitarImagen.setDisable(img == null);
    }

    /** Carga la imagen actual del producto en edición (asíncrono) */
    private void cargarImagenActual(Producto p) {
        int mia = ++secuenciaImagen;
        imagenes.imagen(p.getId(), img -> {
            if (mia != secuenciaImagen) return;
            imagenGuardada = img != null;
            mostrarVistaPrevia(img);
            lblImagenEstado.setText(img == null ? AYUDA_IMAGEN : "Imagen guardada. Elige otra para reemplazarla.");
        });
    }

    @FXML
    private void onCancelarFormulario() {
        mostrarVistas(true, false, false);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CATÁLOGO DE CÓDIGOS DE BARRAS (autollenado al dar de alta)
    //
    //  Al capturar un código (Enter del lector o al salir del campo) se busca
    //  en CatalogoCodigosService fuera del hilo de JavaFX; con la ficha se
    //  proponen descripción, departamento e imagen SOLO en los campos vacíos.
    //  Un aviso dice qué se llenó y de dónde, con Deshacer / Usar estos datos.
    //  En edición solo actúa el botón "Buscar datos".
    // ─────────────────────────────────────────────────────────────────────

    private enum CampoCatalogo { DESCRIPCION, DEPARTAMENTO, IMAGEN }

    /** Cuenta de consultas: una respuesta tardía de otro código no pisa la actual */
    private int secuenciaCatalogo;
    /** Último código consultado automáticamente (no se repite la consulta al salir del campo varias veces) */
    private String codigoCatalogoConsultado;
    private CatalogoCodigosService.Ficha fichaCatalogo;
    /** Campos que llenó el catálogo (lo que Deshacer revierte) */
    private final java.util.EnumSet<CampoCatalogo> llenadosPorCatalogo = java.util.EnumSet.noneOf(CampoCatalogo.class);
    /** Campos de la ficha que NO se aplicaron porque el usuario ya tenía algo (lo que "Usar estos datos" aplica) */
    private final java.util.EnumSet<CampoCatalogo> pendientesCatalogo = java.util.EnumSet.noneOf(CampoCatalogo.class);
    /** Valores previos para Deshacer */
    private String descripcionAntesCatalogo;
    private Departamento departamentoAntesCatalogo;
    private ProductoImagenService.Normalizada imagenAntesCatalogo;
    private String origenImagenAntesCatalogo;
    private String descripcionDelCatalogo;
    private Departamento departamentoDelCatalogo;

    private void configurarCatalogo() {
        // Al salir del campo de código (Tab, clic en Descripción) se consulta en el alta
        txtCodigoBarras.focusedProperty().addListener((o, tenia, tiene) -> {
            if (tenia && !tiene && productoEnEdicion == null && vistaFormulario.isVisible()) consultarCatalogo(false);
        });
        // Si el usuario cambia el código, el aviso anterior ya no corresponde
        txtCodigoBarras.textProperty().addListener((o, a, b) -> {
            if (hboxCatalogo.isVisible() && codigoCatalogoConsultado != null
                    && !codigoCatalogoConsultado.equals(b == null ? "" : b.trim())) {
                ocultarCatalogo();
            }
        });
        lblCatalogoAtribucion.setText(CatalogoCodigosService.ATRIBUCION);
    }

    /** Enter en el campo de código (los lectores lo envían al final) */
    @FXML
    private void onCodigoBarrasEnter() {
        onCodigoBarrasChanged();
        if (productoEnEdicion == null) consultarCatalogo(false);
        if (!hboxYaExiste.isVisible()) txtDescripcion.requestFocus();
    }

    /** Botón "Buscar datos": también en edición; avisa si no se encuentra */
    @FXML
    private void onBuscarCatalogo() {
        consultarCatalogo(true);
    }

    private void consultarCatalogo(boolean manual) {
        String codigo = txtCodigoBarras.getText() == null ? "" : txtCodigoBarras.getText().trim();
        if (codigo.isEmpty()) {
            if (manual) alerta(Alert.AlertType.INFORMATION, "Código de barras", "Escanea o escribe primero el código de barras.");
            return;
        }
        if (hboxYaExiste.isVisible()) return;   // ya existe: no hay nada que llenar
        if (!manual && codigo.equals(codigoCatalogoConsultado)) return;
        if (me.eliassanchezfernandez.puntodeventa.util.CodigoBarras.normalizar(codigo).isEmpty()) {
            if (manual) {
                mostrarCatalogo("Este código no es un código de barras EAN/UPC.",
                        "El catálogo solo tiene códigos de barras comerciales (8, 12 o 13 dígitos). Los códigos internos se capturan a mano.",
                        false, false);
            }
            return;
        }
        codigoCatalogoConsultado = codigo;
        int mia = ++secuenciaCatalogo;
        if (manual) {
            mostrarCatalogo("Buscando «" + codigo + "» en el catálogo…", "", false, false);
            btnBuscarCatalogo.setDisable(true);
        }
        catalogoCodigosService.buscarEnSegundoPlano(codigo).thenAccept(r -> Platform.runLater(() -> {
            btnBuscarCatalogo.setDisable(false);
            if (mia != secuenciaCatalogo || !vistaFormulario.isVisible()) return;
            if (!codigo.equals(txtCodigoBarras.getText() == null ? "" : txtCodigoBarras.getText().trim())) return;
            if (r.isPresent()) {
                aplicarFichaCatalogo(r.get());
            } else if (manual) {
                mostrarCatalogo("«" + codigo + "» no está en el catálogo.",
                        catalogoCodigosService.isEnLinea()
                                ? "Captura los datos a mano. Si no hay internet, solo se consulta la copia local."
                                : "Captura los datos a mano. La consulta en internet está apagada (Configuración → Códigos de barras).",
                        false, false);
            }
        }));
    }

    /** Llena los campos vacíos con la ficha y muestra el aviso; recuerda lo previo para Deshacer */
    private void aplicarFichaCatalogo(CatalogoCodigosService.Ficha ficha) {
        fichaCatalogo = ficha;
        llenadosPorCatalogo.clear();
        pendientesCatalogo.clear();
        var datos = ficha.datos();
        descripcionDelCatalogo = me.eliassanchezfernandez.puntodeventa.util.AutollenadoProducto.descripcion(datos);
        departamentoDelCatalogo = me.eliassanchezfernandez.puntodeventa.util.AutollenadoProducto
                .departamento(datos.getCategoria(), comboDepartamento.getItems()).orElse(null);

        if (!descripcionDelCatalogo.isEmpty()) {
            if (txtDescripcion.getText() == null || txtDescripcion.getText().isBlank()) {
                descripcionAntesCatalogo = txtDescripcion.getText();
                txtDescripcion.setText(descripcionDelCatalogo);
                llenadosPorCatalogo.add(CampoCatalogo.DESCRIPCION);
            } else if (!descripcionDelCatalogo.equals(txtDescripcion.getText().trim())) {
                pendientesCatalogo.add(CampoCatalogo.DESCRIPCION);
            }
        }
        if (departamentoDelCatalogo != null) {
            if (comboDepartamento.getValue() == null) {
                departamentoAntesCatalogo = null;
                comboDepartamento.setValue(departamentoDelCatalogo);
                llenadosPorCatalogo.add(CampoCatalogo.DEPARTAMENTO);
            } else if (!departamentoDelCatalogo.getId().equals(comboDepartamento.getValue().getId())) {
                pendientesCatalogo.add(CampoCatalogo.DEPARTAMENTO);
            }
        }
        boolean puedeBajarImagen = datos.tieneImagen() && catalogoCodigosService.isEnLinea();
        if (puedeBajarImagen) {
            if (imagenPendiente == null && !imagenGuardada) {
                imagenAntesCatalogo = null;
                origenImagenAntesCatalogo = null;
                bajarImagenDelCatalogo(datos.getImagen());
                llenadosPorCatalogo.add(CampoCatalogo.IMAGEN);
            } else {
                pendientesCatalogo.add(CampoCatalogo.IMAGEN);
            }
        }
        mostrarResultadoCatalogo();
    }

    /** "Usar estos datos": aplica también lo que no se tocó por tener ya un valor (todo reversible) */
    @FXML
    private void onCatalogoUsar() {
        if (fichaCatalogo == null) return;
        if (pendientesCatalogo.remove(CampoCatalogo.DESCRIPCION)) {
            descripcionAntesCatalogo = txtDescripcion.getText();
            txtDescripcion.setText(descripcionDelCatalogo);
            llenadosPorCatalogo.add(CampoCatalogo.DESCRIPCION);
        }
        if (pendientesCatalogo.remove(CampoCatalogo.DEPARTAMENTO)) {
            departamentoAntesCatalogo = comboDepartamento.getValue();
            comboDepartamento.setValue(departamentoDelCatalogo);
            llenadosPorCatalogo.add(CampoCatalogo.DEPARTAMENTO);
        }
        if (pendientesCatalogo.remove(CampoCatalogo.IMAGEN)) {
            imagenAntesCatalogo = imagenPendiente;
            origenImagenAntesCatalogo = origenImagen;
            bajarImagenDelCatalogo(fichaCatalogo.datos().getImagen());
            llenadosPorCatalogo.add(CampoCatalogo.IMAGEN);
        }
        mostrarResultadoCatalogo();
    }

    /** Deshacer: regresa cada campo que llenó el catálogo a lo que tenía (si el usuario no lo cambió después) */
    @FXML
    private void onCatalogoDeshacer() {
        if (llenadosPorCatalogo.remove(CampoCatalogo.DESCRIPCION)
                && descripcionDelCatalogo != null && descripcionDelCatalogo.equals(txtDescripcion.getText())) {
            txtDescripcion.setText(descripcionAntesCatalogo == null ? "" : descripcionAntesCatalogo);
        }
        if (llenadosPorCatalogo.remove(CampoCatalogo.DEPARTAMENTO)
                && departamentoDelCatalogo != null && comboDepartamento.getValue() != null
                && departamentoDelCatalogo.getId().equals(comboDepartamento.getValue().getId())) {
            comboDepartamento.setValue(departamentoAntesCatalogo);
        }
        if (llenadosPorCatalogo.remove(CampoCatalogo.IMAGEN)) {
            secuenciaImagen++;   // cancela una descarga en curso
            imagenPendiente = imagenAntesCatalogo;
            origenImagen = origenImagenAntesCatalogo;
            quitarImagen = false;
            if (imagenPendiente != null) {
                mostrarVistaPrevia(ImagenProductoCache.decodificar(imagenPendiente.imagen(), 0));
                lblImagenEstado.setText(imagenPendiente.ancho() + " × " + imagenPendiente.alto() + " px · se guarda con el producto");
            } else if (imagenGuardada && productoEnEdicion != null) {
                cargarImagenActual(productoEnEdicion);
            } else {
                mostrarVistaPrevia(null);
                lblImagenEstado.setText(AYUDA_IMAGEN);
            }
        }
        llenadosPorCatalogo.clear();
        ocultarCatalogo();
    }

    @FXML
    private void onCatalogoCerrar() {
        ocultarCatalogo();
    }

    private void bajarImagenDelCatalogo(String url) {
        procesarImagen(() -> productoImagenService.descargarDesdeUrl(url), "catálogo de códigos (Open Food Facts)");
    }

    /** Arma el aviso con lo encontrado, lo llenado, lo pendiente y la procedencia */
    private void mostrarResultadoCatalogo() {
        var datos = fichaCatalogo.datos();
        StringBuilder titulo = new StringBuilder("Encontrado en el catálogo: «").append(descripcionDelCatalogo).append("»");
        if (datos.getCategoria() != null) titulo.append(" · ").append(datos.getCategoria());
        titulo.append(fichaCatalogo.origen() == CatalogoCodigosService.Origen.LOCAL ? " (copia local)" : " (en línea)");

        StringBuilder detalle = new StringBuilder();
        if (!llenadosPorCatalogo.isEmpty()) {
            detalle.append("Se llenó: ").append(nombresCampos(llenadosPorCatalogo)).append(". ");
        }
        if (!pendientesCatalogo.isEmpty()) {
            detalle.append("Ya tenías ").append(nombresCampos(pendientesCatalogo))
                    .append(": no se cambió; «Usar estos datos» lo reemplaza. ");
        }
        if (llenadosPorCatalogo.isEmpty() && pendientesCatalogo.isEmpty()) {
            detalle.append("No había nada que llenar. ");
        }
        if (departamentoDelCatalogo == null && datos.getCategoria() != null && !"Otros".equalsIgnoreCase(datos.getCategoria())) {
            detalle.append("Categoría del catálogo: ").append(datos.getCategoria())
                    .append(" (no hay un departamento equivalente; créalo en Departamentos si lo quieres). ");
        }
        if (datos.tieneImagen() && !catalogoCodigosService.isEnLinea()) {
            detalle.append("La imagen no se baja porque la consulta en internet está apagada. ");
        }
        detalle.append("Revisa el precio y el resto de los datos antes de guardar.");
        mostrarCatalogo(titulo.toString(), detalle.toString().trim(), !pendientesCatalogo.isEmpty(), !llenadosPorCatalogo.isEmpty());
    }

    private static String nombresCampos(java.util.Set<CampoCatalogo> campos) {
        java.util.List<String> n = new java.util.ArrayList<>();
        if (campos.contains(CampoCatalogo.DESCRIPCION)) n.add("descripción");
        if (campos.contains(CampoCatalogo.DEPARTAMENTO)) n.add("departamento");
        if (campos.contains(CampoCatalogo.IMAGEN)) n.add("imagen");
        if (n.size() <= 1) return String.join("", n);
        return String.join(", ", n.subList(0, n.size() - 1)) + " e " + n.get(n.size() - 1);
    }

    private void mostrarCatalogo(String titulo, String detalle, boolean usar, boolean deshacer) {
        lblCatalogoTitulo.setText(titulo);
        lblCatalogoDetalle.setText(detalle);
        lblCatalogoDetalle.setVisible(!detalle.isEmpty());
        lblCatalogoDetalle.setManaged(!detalle.isEmpty());
        btnCatalogoUsar.setVisible(usar);
        btnCatalogoUsar.setManaged(usar);
        btnCatalogoDeshacer.setVisible(deshacer);
        btnCatalogoDeshacer.setManaged(deshacer);
        hboxCatalogo.setVisible(true);
        hboxCatalogo.setManaged(true);
    }

    private void ocultarCatalogo() {
        hboxCatalogo.setVisible(false);
        hboxCatalogo.setManaged(false);
    }

    /** Al limpiar el formulario: sin ficha, sin aviso y las respuestas en vuelo se descartan */
    private void reiniciarCatalogo() {
        secuenciaCatalogo++;
        codigoCatalogoConsultado = null;
        fichaCatalogo = null;
        llenadosPorCatalogo.clear();
        pendientesCatalogo.clear();
        descripcionAntesCatalogo = null;
        departamentoAntesCatalogo = null;
        imagenAntesCatalogo = null;
        origenImagenAntesCatalogo = null;
        descripcionDelCatalogo = null;
        departamentoDelCatalogo = null;
        if (btnBuscarCatalogo != null) btnBuscarCatalogo.setDisable(false);
        if (hboxCatalogo != null) ocultarCatalogo();
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
        lblErrorPrecio.setText(puedePrecios ? "Los precios no pueden ser negativos."
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
        colImagen.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getId()));
        colImagen.setCellFactory(col -> imagenes.celdaMiniatura(col, 30));
        colImagen.setSortable(false);
        colImagen.setUserData(me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas.SIN_EXPORTAR);
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
        // El proveedor viene cargado con la lista (JOIN FETCH en ProductoService.listarTodos): sin consulta por fila
        colProveedor.setCellValueFactory(c -> {
            Proveedor prov = c.getValue().getProveedor();
            return new SimpleStringProperty(prov != null ? prov.getNombre() : "—");
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
                setText(empty || v == null ? null : Moneda.formato(v));
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

    // ─────────────────────────────────────────────────────────────────────
    //  PROVEEDOR HABITUAL
    //  El combo ofrece los proveedores activos (más el que el producto ya
    //  tenga aunque esté desactivado) con "— Sin proveedor —" como primera
    //  opción. "+ Nuevo" da de alta uno con nombre y teléfono sin salir del
    //  formulario (solo con GESTIONAR_PROVEEDORES; el servicio lo vuelve a exigir).
    // ─────────────────────────────────────────────────────────────────────

    private void configurarComboProveedor() {
        comboProveedor.setConverter(new StringConverter<>() {
            @Override public String toString(Proveedor p) {
                if (p == null) return "— Sin proveedor —";
                return p.isActivo() ? p.getNombre() : p.getNombre() + " (desactivado)";
            }
            @Override public Proveedor fromString(String s) { return null; }
        });
    }

    /** Proveedores activos por nombre; "Sin proveedor" (null) va primero. Un fallo aquí no estorba al catálogo. */
    private void cargarProveedores() {
        Proveedor elegido = comboProveedor.getValue();
        List<Proveedor> activos;
        try {
            activos = proveedorService.listarActivos();
        } catch (RuntimeException e) {
            activos = List.of();
        }
        ObservableList<Proveedor> items = FXCollections.observableArrayList();
        items.add(null);
        items.addAll(activos);
        comboProveedor.setItems(items);
        if (elegido != null) seleccionarProveedor(elegido);
    }

    /** Deja elegido ese proveedor; si ya no está activo se agrega al final para que la edición lo conserve */
    private void seleccionarProveedor(Proveedor p) {
        if (p == null || p.getId() == null) { comboProveedor.setValue(null); return; }
        Optional<Proveedor> enLista = comboProveedor.getItems().stream()
                .filter(x -> x != null && p.getId().equals(x.getId())).findFirst();
        if (enLista.isPresent()) {
            comboProveedor.setValue(enLista.get());
        } else {
            comboProveedor.getItems().add(p);
            comboProveedor.setValue(p);
        }
    }

    @FXML
    private void onNuevoProveedor() {
        if (!sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES)) return;
        Dialog<Proveedor> dlg = new Dialog<>();
        dlg.setTitle("Nuevo proveedor");
        dlg.setHeaderText("Dar de alta un proveedor");
        TextField txtNombre = new TextField();
        txtNombre.setPromptText("Nombre del proveedor (obligatorio)");
        TextField txtTelefono = new TextField();
        txtTelefono.setPromptText("Teléfono (opcional)");
        Label ayuda = new Label("Los días de entrega, RFC y demás datos se capturan en Inventario → Proveedores.");
        ayuda.setWrapText(true);
        ayuda.getStyleClass().add("hint");
        javafx.scene.layout.VBox caja = new javafx.scene.layout.VBox(8,
                new Label("Nombre"), txtNombre, new Label("Teléfono"), txtTelefono, ayuda);
        caja.setPadding(new javafx.geometry.Insets(10));
        caja.setPrefWidth(380);
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button ok = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Guardar");
        ok.disableProperty().bind(txtNombre.textProperty().isEmpty());
        // Si el servicio rechaza los datos, el diálogo sigue abierto para corregirlos
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                dlg.setResult(proveedorService.guardar(null, txtNombre.getText(), null, txtTelefono.getText(), null,
                        java.util.Set.of(), java.util.Set.of(), null));
            } catch (NegocioException e) {
                ev.consume();
                alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
            }
        });
        dlg.setResultConverter(b -> b == ButtonType.OK ? dlg.getResult() : null);
        Ventanas.estilizar(dlg);
        Platform.runLater(txtNombre::requestFocus);
        Optional<Proveedor> creado = dlg.showAndWait();
        if (creado.isEmpty() || creado.get() == null) return;
        cargarProveedores();
        seleccionarProveedor(creado.get());
    }

    /** Unidades de medida del catálogo; elegir una ajusta "Se vende" a su regla de decimales */
    private void configurarComboUnidad() {
        comboUnidad.setConverter(new StringConverter<>() {
            @Override public String toString(Unidad u) {
                return u == null ? "— Sin unidad —" : u.getNombre() + (u.isPermiteDecimales() ? " (decimales)" : "");
            }
            @Override public Unidad fromString(String s) { return null; }
        });
        comboUnidad.valueProperty().addListener((o, antes, u) -> {
            if (u == null) return;
            if (u.isPermiteDecimales()) rbGranel.setSelected(true);
            else if (rbGranel.isSelected()) rbUnidad.setSelected(true);
        });
        cargarUnidades();
        comboObjetoImp.setItems(FXCollections.observableArrayList(
                me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.OBJETOS_IMP));
        comboObjetoImp.setPromptText("02 Sí objeto (por defecto)");
        comboDepartamento.valueProperty().addListener((o, a, b) -> actualizarClavesPorDefecto());
        comboUnidad.valueProperty().addListener((o, a, b) -> actualizarClavesPorDefecto());
        actualizarClavesPorDefecto();
    }

    /**
     * Combo de IVA del producto. En un alta, elegir departamento propone el IVA del
     * departamento (si tiene) mientras el usuario no haya elegido otro a mano.
     */
    private void configurarComboIva() {
        comboIva.setItems(FXCollections.observableArrayList(TipoIva.values()));
        comboIva.setConverter(new StringConverter<>() {
            @Override public String toString(TipoIva t) { return t == null ? TipoIva.TIENDA.getEtiqueta() : t.getEtiqueta(); }
            @Override public TipoIva fromString(String s) { return null; }
        });
        comboIva.setValue(TipoIva.TIENDA);
        comboDepartamento.valueProperty().addListener((o, antes, depto) -> {
            if (productoEnEdicion != null || cargandoFormulario) return;
            TipoIva propuestoAntes = antes != null ? antes.getIvaPorDefecto() : null;
            TipoIva actual = comboIva.getValue();
            // Solo se pisa lo que no eligió el usuario: "usar el de la tienda" o lo que propuso el departamento anterior
            boolean sinTocar = actual == null || actual == TipoIva.TIENDA || actual == propuestoAntes;
            if (!sinTocar) return;
            TipoIva propuesto = depto != null ? depto.getIvaPorDefecto() : null;
            comboIva.setValue(propuesto != null ? propuesto : TipoIva.TIENDA);
        });
    }

    /** Pie de ayuda: qué claves SAT se usarán si el producto no tiene las suyas */
    private void actualizarClavesPorDefecto() {
        if (lblClavesDefecto == null) return;
        String prodServ = comboDepartamento.getValue() != null && comboDepartamento.getValue().getClaveProdServ() != null
                ? comboDepartamento.getValue().getClaveProdServ() + " (departamento)"
                : me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.PROD_SERV_ABARROTES + " (genérica de abarrotes)";
        String unidad = me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.claveUnidadPara(comboUnidad.getValue());
        lblClavesDefecto.setText("Si se dejan vacías, al facturar se usa producto/servicio " + prodServ
                + " y unidad " + unidad + ".");
    }

    @FXML
    private void onBuscarClaveProdServ() {
        CatalogoSatDialogo.elegirProdServ(txtClaveProdServ.getText()).ifPresent(e -> txtClaveProdServ.setText(e.clave()));
    }

    @FXML
    private void onBuscarClaveUnidad() {
        CatalogoSatDialogo.elegirUnidad(txtClaveUnidad.getText()).ifPresent(e -> txtClaveUnidad.setText(e.clave()));
    }

    private void cargarUnidades() {
        Unidad seleccionada = comboUnidad.getValue();
        comboUnidad.setItems(FXCollections.observableArrayList(unidadService.listar()));
        if (seleccionada != null) {
            comboUnidad.getItems().stream().filter(u -> u.getId().equals(seleccionada.getId()))
                    .findFirst().ifPresent(comboUnidad::setValue);
        }
    }

    private void cargarProductoEnFormulario(Producto p) {
        cargandoFormulario = true;
        try {
            llenarFormulario(p);
        } finally {
            cargandoFormulario = false;
        }
    }

    private void llenarFormulario(Producto p) {
        limpiarFormulario();
        txtCodigoBarras.setText(p.getCodigoBarras());
        txtDescripcion.setText(p.getDescripcion());
        cargarImagenActual(p);
        switch (p.getTipoVenta()) {
            case GRANEL  -> rbGranel.setSelected(true);
            case PAQUETE -> rbPaquete.setSelected(true);
            default      -> rbUnidad.setSelected(true);
        }
        txtPrecioCosto.setText(String.valueOf(p.getPrecioCosto()));
        txtPrecioVenta.setText(String.valueOf(p.getPrecioVenta()));
        txtPrecioMayoreo.setText(String.valueOf(p.getPrecioMayoreo()));
        if (p.getDepartamento() != null) comboDepartamento.setValue(p.getDepartamento());
        comboIva.setValue(p.getIvaOTienda());
        if (p.getProveedor() != null) seleccionarProveedor(p.getProveedor());
        if (p.getUnidad() != null) {
            comboUnidad.getItems().stream().filter(u -> u.getId().equals(p.getUnidad().getId()))
                    .findFirst().ifPresent(comboUnidad::setValue);
        }
        txtClaveProdServ.setText(p.getClaveProdServ() != null ? p.getClaveProdServ() : "");
        txtClaveUnidad.setText(p.getClaveUnidad() != null ? p.getClaveUnidad() : "");
        comboObjetoImp.setValue(comboObjetoImp.getItems().stream()
                .filter(e -> e.clave().equals(p.getObjetoImp())).findFirst().orElse(null));
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
        reiniciarCatalogo();
        secuenciaImagen++;
        imagenPendiente = null;
        origenImagen = null;
        quitarImagen = false;
        imagenGuardada = false;
        mostrarVistaPrevia(null);
        lblImagenEstado.setText(AYUDA_IMAGEN);
        rbUnidad.setSelected(true);
        txtPrecioCosto.clear();
        txtPrecioVenta.clear();
        txtPrecioMayoreo.clear();
        comboDepartamento.setValue(null);
        comboIva.setValue(TipoIva.TIENDA);
        comboProveedor.setValue(null);
        comboProveedor.getItems().removeIf(x -> x != null && !x.isActivo());   // el desactivado de una edición anterior
        comboUnidad.setValue(null);
        txtClaveProdServ.clear();
        txtClaveUnidad.clear();
        comboObjetoImp.setValue(null);
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
        comboIva.setDisable(bloquear);
        comboProveedor.setDisable(bloquear);
        btnNuevoProveedor.setDisable(bloquear);
        comboUnidad.setDisable(bloquear);
        chkUsaInventario.setDisable(bloquear);
        gridInventario.setDisable(bloquear || !chkUsaInventario.isSelected());
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
        double v = me.eliassanchezfernandez.puntodeventa.util.CalculoCobro.parsearNumero(txt);
        if (Double.isNaN(v)) throw new NegocioException("\"" + campo + "\" debe ser un número válido (ej: 12.50).");
        return v;
    }

    private boolean esNegativo(String txt) {
        if (txt == null || txt.trim().isEmpty()) return false;
        double v = me.eliassanchezfernandez.puntodeventa.util.CalculoCobro.parsearNumero(txt);
        return !Double.isNaN(v) && v < 0;
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
