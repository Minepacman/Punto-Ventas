package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.service.BitacoraService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;
import java.util.TreeMap;

/**
 * Bitácora de acciones sensibles (USR-18), ventana modal solo para el administrador:
 * aperturas del cajón (automáticas y manuales con su motivo), efectivo, cancelaciones,
 * autorizaciones, usuarios, configuración... Filtra por fechas, acción y texto; el botón
 * "Solo cajón" deja únicamente las aperturas (logradas y fallidas).
 */
@Component
@Scope("prototype")
public class BitacoraController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String TODAS = "Todas las acciones";

    @FXML private BorderPane raiz;
    @FXML private DatePicker dpDesde;
    @FXML private DatePicker dpHasta;
    @FXML private ComboBox<String> cmbAccion;
    @FXML private ToggleButton tgCajon;
    @FXML private TextField txtFiltro;
    @FXML private Label lblResumen;
    @FXML private Label lblNota;

    @FXML private TableView<Auditoria> tabla;
    @FXML private TableColumn<Auditoria, String> colFecha;
    @FXML private TableColumn<Auditoria, String> colUsuario;
    @FXML private TableColumn<Auditoria, String> colAccion;
    @FXML private TableColumn<Auditoria, String> colDetalle;
    @FXML private TableColumn<Auditoria, String> colAutorizo;

    @Autowired private BitacoraService bitacoraService;

    private final ObservableList<Auditoria> todos = FXCollections.observableArrayList();
    /** Etiqueta mostrada → clave de acción, de las acciones presentes en el periodo */
    private final TreeMap<String, String> accionesPorEtiqueta = new TreeMap<>();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFecha().format(FMT)));
        colUsuario.setCellValueFactory(c -> new SimpleStringProperty(usuario(c.getValue())));
        colAccion.setCellValueFactory(c -> new SimpleStringProperty(BitacoraService.etiqueta(c.getValue().getAccion())));
        colDetalle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDetalle() == null ? "" : c.getValue().getDetalle()));
        colAutorizo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getAutorizadoPor() == null ? "" : c.getValue().getAutorizadoPor()));
        tabla.setPlaceholder(new Label("No hay registros en el período."));
        txtFiltro.textProperty().addListener((o, a, b) -> aplicarFiltro());
        cmbAccion.valueProperty().addListener((o, a, b) -> aplicarFiltro());

        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy.minusDays(7));
        dpHasta.setValue(hoy);
        onBuscar();
    }

    @FXML private void onBuscar() {
        try {
            todos.setAll(bitacoraService.entre(dpDesde.getValue(), dpHasta.getValue()));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Bitácora", e.getMessage());
            return;
        }
        lblNota.setText(todos.size() >= BitacoraService.MAXIMO_FILAS
                ? "Se muestran los " + BitacoraService.MAXIMO_FILAS + " registros más recientes: acorta el periodo para ver los demás."
                : "");
        // Acciones presentes en el periodo (se conserva la elegida si sigue existiendo)
        String elegida = cmbAccion.getValue();
        accionesPorEtiqueta.clear();
        for (Auditoria a : todos) accionesPorEtiqueta.put(BitacoraService.etiqueta(a.getAccion()), a.getAccion());
        ObservableList<String> opciones = FXCollections.observableArrayList(TODAS);
        opciones.addAll(accionesPorEtiqueta.keySet());
        cmbAccion.setItems(opciones);
        cmbAccion.setValue(elegida != null && opciones.contains(elegida) ? elegida : TODAS);
        aplicarFiltro();
    }

    /** "Solo cajón": deja las aperturas del cajón (y sus fallos) sin importar la acción elegida */
    @FXML private void onSoloCajon() {
        if (tgCajon.isSelected()) cmbAccion.setValue(TODAS);
        aplicarFiltro();
    }

    /** Exporta lo visible a CSV, Excel o PDF */
    @FXML private void onExportar() {
        ExportadorTablas.exportar(tabla, "bitacora", "Bitácora " + dpDesde.getValue() + " a " + dpHasta.getValue());
    }

    @FXML private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private void aplicarFiltro() {
        String t = txtFiltro.getText() == null ? "" : txtFiltro.getText().trim().toLowerCase();
        String accion = cmbAccion.getValue() == null || TODAS.equals(cmbAccion.getValue()) ? null
                : accionesPorEtiqueta.get(cmbAccion.getValue());
        boolean soloCajon = tgCajon.isSelected();
        tabla.setItems(todos.filtered(a ->
                (!soloCajon || BitacoraService.esDelCajon(a))
                && (accion == null || accion.equals(a.getAccion()))
                && (t.isEmpty() || contiene(a.getDetalle(), t) || contiene(usuario(a), t)
                    || contiene(BitacoraService.etiqueta(a.getAccion()), t) || contiene(a.getAutorizadoPor(), t))));
        lblResumen.setText(tabla.getItems().size() + " registro(s)");
    }

    private static boolean contiene(String texto, String buscado) {
        return texto != null && texto.toLowerCase().contains(buscado);
    }

    private static String usuario(Auditoria a) {
        if (a.getUsuarioNombre() == null) return a.getUsuarioLogin() == null ? "—" : a.getUsuarioLogin();
        return a.getUsuarioLogin() != null && !a.getUsuarioLogin().equals(a.getUsuarioNombre())
                ? a.getUsuarioNombre() + " (" + a.getUsuarioLogin() + ")" : a.getUsuarioNombre();
    }
}
