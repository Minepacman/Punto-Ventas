package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.PromocionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

/**
 * Catálogo de promociones (ventana modal abierta desde Productos → Promociones).
 *
 * Tabla a la izquierda, formulario a la derecha. Las reglas de validación
 * están en PromocionService; aquí solo se arma la entidad y se muestran avisos.
 */
@Component
@Scope("prototype")
public class PromocionesController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @FXML private BorderPane raiz;

    // ── Tabla ────────────────────────────────────────────────────────────
    @FXML private TableView<Promocion>           tablaPromociones;
    @FXML private TableColumn<Promocion, String> colProducto;
    @FXML private TableColumn<Promocion, String> colDescripcion;
    @FXML private TableColumn<Promocion, String> colTipo;
    @FXML private TableColumn<Promocion, String> colValor;
    @FXML private TableColumn<Promocion, String> colVigencia;
    @FXML private TableColumn<Promocion, String> colEstado;

    // ── Formulario ───────────────────────────────────────────────────────
    @FXML private Label       lblTituloForm;
    @FXML private TextField   txtFiltroProducto;
    @FXML private ComboBox<Producto> comboProducto;
    @FXML private Label       lblPrecioActual;
    @FXML private TextField   txtDescripcion;
    @FXML private ToggleGroup toggleTipo;
    @FXML private RadioButton rbPorcentaje;
    @FXML private RadioButton rbPrecioFijo;
    @FXML private TextField   txtValor;
    @FXML private Label       lblPrecioResultante;
    @FXML private DatePicker  dpInicio;
    @FXML private DatePicker  dpFin;
    @FXML private CheckBox    chkActiva;

    @Autowired private PromocionService promocionService;
    @Autowired private ProductoService  productoService;

    private final ObservableList<Promocion> lista = FXCollections.observableArrayList();
    /** null = modo nueva */
    private Promocion promocionEnEdicion = null;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configurarColumnas();
        tablaPromociones.setItems(lista);
        tablaPromociones.setPlaceholder(new Label("No hay promociones registradas."));
        tablaPromociones.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> {
            if (sel != null) cargarEnFormulario(sel);
        });

        comboProducto.setConverter(new StringConverter<>() {
            @Override public String toString(Producto p) {
                return p == null ? "" : p.getCodigoBarras() + "  —  " + p.getDescripcion();
            }
            @Override public Producto fromString(String s) { return null; }
        });
        comboProducto.valueProperty().addListener((o, a, p) -> actualizarPrecios());
        txtValor.textProperty().addListener((o, a, b) -> actualizarPrecios());
        toggleTipo.selectedToggleProperty().addListener((o, a, b) -> actualizarPrecios());

        filtrarProductos();
        onNueva();
        recargar();
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    @FXML
    private void onNueva() {
        promocionEnEdicion = null;
        tablaPromociones.getSelectionModel().clearSelection();
        lblTituloForm.setText("NUEVA PROMOCIÓN");
        comboProducto.setValue(null);
        txtDescripcion.clear();
        rbPorcentaje.setSelected(true);
        txtValor.clear();
        dpInicio.setValue(LocalDate.now());
        dpFin.setValue(null);
        chkActiva.setSelected(true);
        actualizarPrecios();
        txtFiltroProducto.requestFocus();
    }

    @FXML
    private void onFiltrarProducto() {
        filtrarProductos();
    }

    @FXML
    private void onGuardar() {
        // Objeto nuevo (con el mismo id si es edición) para no modificar la
        // fila de la tabla si el servicio rechaza los datos
        Promocion p = new Promocion();
        if (promocionEnEdicion != null) p.setId(promocionEnEdicion.getId());

        double valor;
        try {
            String txt = txtValor.getText().trim().replace(",", ".").replace("%", "").replace("$", "");
            valor = txt.isEmpty() ? 0 : Double.parseDouble(txt);
        } catch (NumberFormatException e) {
            alerta(Alert.AlertType.WARNING, "Valor inválido",
                    "El valor debe ser un número (ej: 10 para 10 % o 25.50 para precio fijo).");
            return;
        }

        p.setProducto(comboProducto.getValue());
        p.setDescripcion(txtDescripcion.getText());
        p.setTipo(rbPrecioFijo.isSelected() ? Promocion.Tipo.PRECIO_FIJO : Promocion.Tipo.PORCENTAJE);
        p.setValor(valor);
        p.setFechaInicio(dpInicio.getValue());
        p.setFechaFin(dpFin.getValue());
        p.setActiva(chkActiva.isSelected());

        try {
            Promocion guardada = promocionService.guardar(p);
            recargar();
            lista.stream().filter(x -> x.getId().equals(guardada.getId())).findFirst()
                    .ifPresent(x -> tablaPromociones.getSelectionModel().select(x));
            alerta(Alert.AlertType.INFORMATION, "Promoción guardada",
                    "La promoción se guardó correctamente.");
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onEliminar() {
        Promocion sel = tablaPromociones.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona una promoción",
                    "Haz clic en una promoción de la tabla para eliminarla.");
            return;
        }
        if (!Ventanas.confirmar("Confirmar eliminación",
                "¿Eliminar la promoción de \"" + sel.getProducto().getDescripcion() + "\"?")) {
            return;
        }
        try {
            promocionService.eliminar(sel.getId());
            recargar();
            onNueva();
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se puede eliminar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void recargar() {
        lista.setAll(promocionService.listarTodas());
    }

    private void filtrarProductos() {
        Producto actual = comboProducto.getValue();
        comboProducto.setItems(FXCollections.observableArrayList(
                productoService.buscar(txtFiltroProducto.getText())));
        if (actual != null && comboProducto.getItems().contains(actual)) {
            comboProducto.setValue(actual);
        }
    }

    private void cargarEnFormulario(Promocion p) {
        promocionEnEdicion = p;
        lblTituloForm.setText("MODIFICAR PROMOCIÓN");
        // Asegurar que el producto esté en la lista del combo
        if (!comboProducto.getItems().contains(p.getProducto())) {
            comboProducto.getItems().add(0, p.getProducto());
        }
        comboProducto.setValue(p.getProducto());
        txtDescripcion.setText(p.getDescripcion());
        if (p.getTipo() == Promocion.Tipo.PRECIO_FIJO) rbPrecioFijo.setSelected(true);
        else rbPorcentaje.setSelected(true);
        txtValor.setText(formatoNumero(p.getValor()));
        dpInicio.setValue(p.getFechaInicio());
        dpFin.setValue(p.getFechaFin());
        chkActiva.setSelected(p.isActiva());
        actualizarPrecios();
    }

    /** Muestra el precio de lista del producto y el precio que quedaría con la promoción */
    private void actualizarPrecios() {
        Producto p = comboProducto.getValue();
        if (p == null) {
            lblPrecioActual.setText("Precio actual: —");
            lblPrecioResultante.setText("");
            return;
        }
        lblPrecioActual.setText(String.format("Precio actual: $%.2f", p.getPrecioVenta()));
        try {
            String txt = txtValor.getText().trim().replace(",", ".").replace("%", "").replace("$", "");
            if (txt.isEmpty()) { lblPrecioResultante.setText(""); return; }
            Promocion tmp = new Promocion();
            tmp.setTipo(rbPrecioFijo.isSelected() ? Promocion.Tipo.PRECIO_FIJO : Promocion.Tipo.PORCENTAJE);
            tmp.setValor(Double.parseDouble(txt));
            lblPrecioResultante.setText(String.format("Precio con promoción: $%.2f",
                    tmp.precioConPromocion(p.getPrecioVenta())));
        } catch (NumberFormatException e) {
            lblPrecioResultante.setText("");
        }
    }

    private void configurarColumnas() {
        colProducto.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getProducto() != null ? c.getValue().getProducto().getDescripcion() : "—"));
        colDescripcion.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getDescripcion() != null ? c.getValue().getDescripcion() : ""));
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getTipo() == Promocion.Tipo.PORCENTAJE ? "% descuento" : "Precio fijo"));
        colValor.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getTipo() == Promocion.Tipo.PORCENTAJE
                        ? formatoNumero(c.getValue().getValor()) + " %"
                        : String.format("$%.2f", c.getValue().getValor())));
        colVigencia.setCellValueFactory(c -> {
            Promocion p = c.getValue();
            String ini = p.getFechaInicio() != null ? p.getFechaInicio().format(FMT) : "—";
            String fin = p.getFechaFin()    != null ? p.getFechaFin().format(FMT)    : "sin vencimiento";
            return new SimpleStringProperty(ini + " → " + fin);
        });
        colEstado.setCellValueFactory(c -> {
            Promocion p = c.getValue();
            String estado = !p.isActiva() ? "Inactiva"
                    : p.estaVigente(LocalDate.now()) ? "Vigente" : "Fuera de fecha";
            return new SimpleStringProperty(estado);
        });
    }

    private static String formatoNumero(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
