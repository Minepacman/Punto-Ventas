package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.PromocionService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Catálogo de promociones (ventana modal abierta desde Productos → Promociones).
 *
 * Tabla a la izquierda, formulario a la derecha. Tipos: % de descuento,
 * precio fijo y NxM (2x1, 3x2); alcance por producto o por departamento;
 * vigencia por fechas, días de la semana, horario y "hasta agotar".
 * Editar exige CREAR_PROMOCIONES; sin él la ventana es solo de consulta.
 */
@Component
@Scope("prototype")
public class PromocionesController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @FXML private BorderPane raiz;
    @FXML private Button btnNueva;
    @FXML private Button btnEliminar;
    @FXML private Button btnGuardar;
    @FXML private Label  lblSoloLectura;

    // ── Tabla ────────────────────────────────────────────────────────────
    @FXML private TableView<Promocion>           tablaPromociones;
    @FXML private TableColumn<Promocion, String> colAlcance;
    @FXML private TableColumn<Promocion, String> colDescripcion;
    @FXML private TableColumn<Promocion, String> colTipo;
    @FXML private TableColumn<Promocion, String> colValor;
    @FXML private TableColumn<Promocion, String> colVigencia;
    @FXML private TableColumn<Promocion, String> colEstado;

    // ── Formulario ───────────────────────────────────────────────────────
    @FXML private Label       lblTituloForm;
    @FXML private ToggleGroup toggleAlcance;
    @FXML private RadioButton rbPorProducto;
    @FXML private RadioButton rbPorDepartamento;
    @FXML private VBox        vboxProducto;
    @FXML private TextField   txtFiltroProducto;
    @FXML private ComboBox<Producto> comboProducto;
    @FXML private Label       lblPrecioActual;
    @FXML private VBox        vboxDepartamento;
    @FXML private ComboBox<Departamento> comboDepartamento;
    @FXML private TextField   txtDescripcion;
    @FXML private ToggleGroup toggleTipo;
    @FXML private RadioButton rbPorcentaje;
    @FXML private RadioButton rbPrecioFijo;
    @FXML private RadioButton rbNxM;
    @FXML private HBox        hboxValor;
    @FXML private TextField   txtValor;
    @FXML private HBox        hboxNxM;
    @FXML private TextField   txtN;
    @FXML private TextField   txtM;
    @FXML private Label       lblPrecioResultante;
    @FXML private DatePicker  dpInicio;
    @FXML private DatePicker  dpFin;
    @FXML private CheckBox    chkLun;
    @FXML private CheckBox    chkMar;
    @FXML private CheckBox    chkMie;
    @FXML private CheckBox    chkJue;
    @FXML private CheckBox    chkVie;
    @FXML private CheckBox    chkSab;
    @FXML private CheckBox    chkDom;
    @FXML private TextField   txtHoraInicio;
    @FXML private TextField   txtHoraFin;
    @FXML private TextField   txtLimitePiezas;
    @FXML private TextField   txtLimiteTicket;
    @FXML private CheckBox    chkActiva;

    @Autowired private PromocionService promocionService;
    @Autowired private ProductoService  productoService;
    @Autowired private SesionService    sesionService;

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
        comboDepartamento.setConverter(new StringConverter<>() {
            @Override public String toString(Departamento d) { return d == null ? "" : d.getNombre(); }
            @Override public Departamento fromString(String s) { return null; }
        });
        comboDepartamento.setItems(FXCollections.observableArrayList(productoService.listarDepartamentos()));

        comboProducto.valueProperty().addListener((o, a, p) -> actualizarPrecios());
        txtValor.textProperty().addListener((o, a, b) -> actualizarPrecios());
        toggleTipo.selectedToggleProperty().addListener((o, a, b) -> aplicarTipo());
        toggleAlcance.selectedToggleProperty().addListener((o, a, b) -> aplicarAlcance());

        boolean puedeEditar = sesionService.tienePermiso(Permiso.CREAR_PROMOCIONES);
        btnNueva.setDisable(!puedeEditar);
        btnEliminar.setDisable(!puedeEditar);
        btnGuardar.setDisable(!puedeEditar);
        lblSoloLectura.setVisible(!puedeEditar);
        lblSoloLectura.setManaged(!puedeEditar);

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
        rbPorProducto.setSelected(true);
        comboProducto.setValue(null);
        comboDepartamento.setValue(null);
        txtDescripcion.clear();
        rbPorcentaje.setSelected(true);
        txtValor.clear();
        txtN.setText("2");
        txtM.setText("1");
        dpInicio.setValue(LocalDate.now());
        dpFin.setValue(null);
        for (CheckBox c : diasCheck()) c.setSelected(true);
        txtHoraInicio.clear();
        txtHoraFin.clear();
        txtLimitePiezas.clear();
        txtLimiteTicket.clear();
        chkActiva.setSelected(true);
        aplicarTipo();
        aplicarAlcance();
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
        if (promocionEnEdicion != null) {
            p.setId(promocionEnEdicion.getId());
            p.setPiezasUsadas(promocionEnEdicion.getPiezasUsadas());
        }

        Double valor = numero(txtValor.getText());
        Integer n = entero(txtN.getText()), m = entero(txtM.getText());
        Integer limitePiezas = entero(txtLimitePiezas.getText()), limiteTicket = entero(txtLimiteTicket.getText());
        if (valor == null && rbNxM.isSelected()) valor = 0.0;
        if (valor == null || (rbNxM.isSelected() && (n == null || m == null))
                || (!txtLimitePiezas.getText().isBlank() && limitePiezas == null)
                || (!txtLimiteTicket.getText().isBlank() && limiteTicket == null)) {
            alerta(Alert.AlertType.WARNING, "Datos inválidos",
                    "Revisa que valor, N, M y los límites sean números válidos.");
            return;
        }

        if (rbPorProducto.isSelected()) p.setProducto(comboProducto.getValue());
        else p.setDepartamento(comboDepartamento.getValue());
        p.setDescripcion(txtDescripcion.getText());
        p.setTipo(rbNxM.isSelected() ? Promocion.Tipo.NXM
                : rbPrecioFijo.isSelected() ? Promocion.Tipo.PRECIO_FIJO : Promocion.Tipo.PORCENTAJE);
        p.setValor(valor);
        p.setCantidadN(rbNxM.isSelected() ? n : null);
        p.setCantidadM(rbNxM.isSelected() ? m : null);
        p.setFechaInicio(dpInicio.getValue());
        p.setFechaFin(dpFin.getValue());
        p.setDias(diasSeleccionados());
        p.setHoraInicio(txtHoraInicio.getText().isBlank() ? null : txtHoraInicio.getText().trim());
        p.setHoraFin(txtHoraFin.getText().isBlank() ? null : txtHoraFin.getText().trim());
        p.setLimitePiezas(txtLimitePiezas.getText().isBlank() ? null : limitePiezas);
        p.setLimitePorTicket(txtLimiteTicket.getText().isBlank() ? null : limiteTicket);
        p.setActiva(chkActiva.isSelected());

        try {
            Promocion guardada = promocionService.guardar(p);
            recargar();
            lista.stream().filter(x -> x.getId().equals(guardada.getId())).findFirst()
                    .ifPresent(x -> tablaPromociones.getSelectionModel().select(x));
            alerta(Alert.AlertType.INFORMATION, "Promoción guardada",
                    "La promoción \"" + guardada.getNombre() + "\" se guardó correctamente.");
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
                "¿Eliminar la promoción \"" + sel.getNombre() + "\"?")) {
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

    private void aplicarTipo() {
        boolean nxm = rbNxM.isSelected();
        hboxValor.setVisible(!nxm);
        hboxValor.setManaged(!nxm);
        hboxNxM.setVisible(nxm);
        hboxNxM.setManaged(nxm);
        actualizarPrecios();
    }

    private void aplicarAlcance() {
        boolean porProducto = rbPorProducto.isSelected();
        vboxProducto.setVisible(porProducto);
        vboxProducto.setManaged(porProducto);
        vboxDepartamento.setVisible(!porProducto);
        vboxDepartamento.setManaged(!porProducto);
        if (!porProducto && rbPrecioFijo.isSelected()) rbPorcentaje.setSelected(true);
        rbPrecioFijo.setDisable(!porProducto);
        actualizarPrecios();
    }

    private void cargarEnFormulario(Promocion p) {
        promocionEnEdicion = p;
        lblTituloForm.setText("MODIFICAR PROMOCIÓN");
        if (p.esPorDepartamento()) {
            rbPorDepartamento.setSelected(true);
            comboDepartamento.setValue(p.getDepartamento());
        } else {
            rbPorProducto.setSelected(true);
            if (p.getProducto() != null && !comboProducto.getItems().contains(p.getProducto())) {
                comboProducto.getItems().add(0, p.getProducto());
            }
            comboProducto.setValue(p.getProducto());
        }
        txtDescripcion.setText(p.getDescripcion());
        switch (p.getTipo()) {
            case PRECIO_FIJO -> rbPrecioFijo.setSelected(true);
            case NXM         -> rbNxM.setSelected(true);
            default          -> rbPorcentaje.setSelected(true);
        }
        txtValor.setText(p.getTipo() == Promocion.Tipo.NXM ? "" : formatoNumero(p.getValor()));
        txtN.setText(p.getCantidadN() != null ? String.valueOf(p.getCantidadN()) : "2");
        txtM.setText(p.getCantidadM() != null ? String.valueOf(p.getCantidadM()) : "1");
        dpInicio.setValue(p.getFechaInicio());
        dpFin.setValue(p.getFechaFin());
        Set<DayOfWeek> dias = p.getDias();
        CheckBox[] checks = diasCheck();
        DayOfWeek[] orden = DayOfWeek.values();
        for (int i = 0; i < 7; i++) checks[i].setSelected(dias.contains(orden[i]));
        txtHoraInicio.setText(p.getHoraInicio() != null ? p.getHoraInicio() : "");
        txtHoraFin.setText(p.getHoraFin() != null ? p.getHoraFin() : "");
        txtLimitePiezas.setText(p.getLimitePiezas() != null ? String.valueOf(p.getLimitePiezas()) : "");
        txtLimiteTicket.setText(p.getLimitePorTicket() != null ? String.valueOf(p.getLimitePorTicket()) : "");
        chkActiva.setSelected(p.isActiva());
        aplicarTipo();
        aplicarAlcance();
    }

    /** Muestra el precio de lista del producto y el precio que quedaría con la promoción */
    private void actualizarPrecios() {
        Producto p = comboProducto.getValue();
        if (rbPorDepartamento.isSelected() || p == null) {
            lblPrecioActual.setText(rbPorDepartamento.isSelected()
                    ? "Aplica a todos los productos del departamento" : "Precio actual: —");
            lblPrecioResultante.setText("");
            return;
        }
        lblPrecioActual.setText(String.format("Precio actual: %s  (costo %s)", Moneda.formato(p.getPrecioVenta()), Moneda.formato(p.getPrecioCosto())));
        if (rbNxM.isSelected()) {
            Integer n = entero(txtN.getText()), m = entero(txtM.getText());
            lblPrecioResultante.setText(n != null && m != null && n > m && m > 0
                    ? String.format("Lleva %d, paga %d: cada %d piezas se regalan %d.", n, m, n, n - m) : "");
            return;
        }
        Double v = numero(txtValor.getText());
        if (v == null) { lblPrecioResultante.setText(""); return; }
        Promocion tmp = new Promocion();
        tmp.setTipo(rbPrecioFijo.isSelected() ? Promocion.Tipo.PRECIO_FIJO : Promocion.Tipo.PORCENTAJE);
        tmp.setValor(v);
        lblPrecioResultante.setText("Precio con promoción: " + Moneda.formato(
                tmp.precioConPromocion(p.getPrecioVenta())));
    }

    private CheckBox[] diasCheck() {
        return new CheckBox[] { chkLun, chkMar, chkMie, chkJue, chkVie, chkSab, chkDom };
    }

    private Set<DayOfWeek> diasSeleccionados() {
        Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
        CheckBox[] checks = diasCheck();
        DayOfWeek[] orden = DayOfWeek.values();
        for (int i = 0; i < 7; i++) if (checks[i].isSelected()) dias.add(orden[i]);
        return dias;
    }

    private void configurarColumnas() {
        colAlcance.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().esPorDepartamento()
                        ? "Depto: " + c.getValue().getDepartamento().getNombre()
                        : c.getValue().getProducto() != null ? c.getValue().getProducto().getDescripcion() : "—"));
        colDescripcion.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getDescripcion() != null ? c.getValue().getDescripcion() : ""));
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTipo().getEtiqueta()));
        colValor.setCellValueFactory(c -> {
            Promocion p = c.getValue();
            String v = switch (p.getTipo()) {
                case PORCENTAJE  -> formatoNumero(p.getValor()) + " %";
                case PRECIO_FIJO -> Moneda.formato(p.getValor());
                case NXM         -> p.getCantidadN() + "x" + p.getCantidadM();
            };
            if (p.getLimitePiezas() != null) {
                v += String.format("  (%s/%d)", formatoNumero(p.getPiezasUsadasOCero()), p.getLimitePiezas());
            }
            return new SimpleStringProperty(v);
        });
        colVigencia.setCellValueFactory(c -> {
            Promocion p = c.getValue();
            String ini = p.getFechaInicio() != null ? p.getFechaInicio().format(FMT) : "—";
            String fin = p.getFechaFin()    != null ? p.getFechaFin().format(FMT)    : "sin vencimiento";
            String extra = "";
            if (p.getDiasSemana() != null) extra += " · " + abreviarDias(p.getDias());
            if (p.getHoraInicio() != null) extra += " · " + p.getHoraInicio() + "-" + p.getHoraFin();
            return new SimpleStringProperty(ini + " → " + fin + extra);
        });
        colEstado.setCellValueFactory(c -> {
            Promocion p = c.getValue();
            String estado = !p.isActiva() ? "Inactiva"
                    : !p.tieneCupo() ? "Agotada"
                    : p.estaVigente(LocalDateTime.now()) ? "Vigente" : "Fuera de horario/fecha";
            return new SimpleStringProperty(estado);
        });
    }

    private static String abreviarDias(Set<DayOfWeek> dias) {
        String[] abr = { "L", "M", "X", "J", "V", "S", "D" };
        StringBuilder sb = new StringBuilder();
        DayOfWeek[] orden = DayOfWeek.values();
        for (int i = 0; i < 7; i++) if (dias.contains(orden[i])) sb.append(abr[i]);
        return sb.toString();
    }

    private static Double numero(String txt) {
        if (txt == null || txt.isBlank()) return null;
        try {
            double v = Double.parseDouble(txt.trim().replace(",", ".").replace("%", "").replace("$", ""));
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) { return null; }
    }

    private static Integer entero(String txt) {
        if (txt == null || txt.isBlank()) return null;
        try { return Integer.parseInt(txt.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String formatoNumero(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
