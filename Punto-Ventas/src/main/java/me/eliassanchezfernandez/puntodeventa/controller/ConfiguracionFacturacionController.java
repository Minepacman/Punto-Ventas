package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;

import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.Entrada;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionFiscalService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Configuración → Facturación (solo ADMIN; lo verifica el servicio):
 * datos del emisor y serie, credenciales de Facturama (cifradas; la
 * contraseña nunca se vuelve a mostrar), modo pruebas/producción y la clave
 * SAT por defecto de cada departamento.
 */
@Component
@Scope("prototype")
public class ConfiguracionFacturacionController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private TextField txtRfc;
    @FXML private TextField txtNombre;
    @FXML private ComboBox<Entrada> cmbRegimen;
    @FXML private TextField txtCodigoPostal;
    @FXML private TextField txtSerie;
    @FXML private Label lblFolio;

    @FXML private TextField txtUsuario;
    @FXML private PasswordField txtContrasena;
    @FXML private CheckBox chkSandbox;
    @FXML private Label lblEstadoCredenciales;
    @FXML private Label lblModo;

    @FXML private GridPane gridDepartamentos;
    @FXML private Button btnGuardarClaves;

    @Autowired private ConfiguracionFiscalService fiscalService;
    @Autowired private ProductoService productoService;

    private final Map<Long, TextField> clavePorDepartamento = new LinkedHashMap<>();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        cmbRegimen.setItems(FXCollections.observableArrayList(CatalogoSat.REGIMENES));
        txtRfc.textProperty().addListener((o, a, b) -> filtrarRegimenes());
        cargar();
        cargarDepartamentos();
    }

    private void cargar() {
        ConfiguracionFiscal c = fiscalService.obtener();
        txtRfc.setText(c.getEmisorRfc() != null ? c.getEmisorRfc() : "");
        txtNombre.setText(c.getEmisorNombre() != null ? c.getEmisorNombre() : "");
        txtCodigoPostal.setText(c.getEmisorCodigoPostal() != null ? c.getEmisorCodigoPostal() : "");
        txtSerie.setText(c.getSerie());
        filtrarRegimenes();
        cmbRegimen.setValue(CatalogoSat.regimen(c.getEmisorRegimen()).orElse(null));
        lblFolio.setText("Último folio emitido en esta serie: " + c.getFolioUltimo());

        chkSandbox.setSelected(c.isPacSandbox());
        txtContrasena.clear();
        if (c.tieneCredenciales()) {
            txtUsuario.setText(fiscalService.usuarioEnClaro(c) != null ? fiscalService.usuarioEnClaro(c) : "");
            txtUsuario.setPromptText("");
            txtContrasena.setPromptText("(guardada; escribe una nueva solo para cambiarla)");
            lblEstadoCredenciales.setText("Credenciales guardadas: " + fiscalService.usuarioEnmascarado());
        } else {
            txtUsuario.clear();
            txtContrasena.setPromptText("Contraseña de la API");
            lblEstadoCredenciales.setText("Sin credenciales. Crea una cuenta de pruebas en dev.facturama.mx y captura su usuario y contraseña.");
        }
        actualizarModo();
    }

    private void filtrarRegimenes() {
        Entrada actual = cmbRegimen.getValue();
        cmbRegimen.setItems(FXCollections.observableArrayList(CatalogoSat.regimenesPara(txtRfc.getText())));
        if (actual != null && cmbRegimen.getItems().contains(actual)) cmbRegimen.setValue(actual);
    }

    private void actualizarModo() {
        boolean sandbox = chkSandbox.isSelected();
        lblModo.setText(sandbox
                ? "MODO PRUEBAS: las facturas se timbran en apisandbox.facturama.mx y NO tienen validez fiscal."
                : "MODO PRODUCCIÓN: las facturas se timbran ante el SAT con validez fiscal.");
        lblModo.getStyleClass().removeAll("callout-warning", "callout-danger");
        lblModo.getStyleClass().add(sandbox ? "callout-warning" : "callout-danger");
    }

    @FXML private void onCambioModo() { actualizarModo(); }

    @FXML
    private void onGuardarEmisor() {
        try {
            fiscalService.guardarEmisor(txtRfc.getText(), txtNombre.getText(),
                    cmbRegimen.getValue() != null ? cmbRegimen.getValue().clave() : null,
                    txtCodigoPostal.getText(), txtSerie.getText());
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Facturación", "Los datos del emisor se guardaron.");
            cargar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onGuardarCredenciales() {
        try {
            fiscalService.guardarCredenciales(txtUsuario.getText(), txtContrasena.getText(), chkSandbox.isSelected());
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Facturación",
                    "Las credenciales se guardaron cifradas. Usa \"Probar conexión\" para verificarlas.");
            cargar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onBorrarCredenciales() {
        if (!Ventanas.confirmar("Borrar credenciales", "¿Borrar el usuario y la contraseña de Facturama guardados?")) return;
        try {
            fiscalService.borrarCredenciales();
            cargar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Facturación", e.getMessage());
        }
    }

    @FXML
    private void onBuscarRegimen() {
        // Atajo: el combo ya lista los regímenes; este botón abre la lista filtrada por el RFC
        filtrarRegimenes();
        cmbRegimen.show();
    }

    // ── Claves por departamento ──────────────────────────────────────────

    private void cargarDepartamentos() {
        gridDepartamentos.getChildren().clear();
        gridDepartamentos.getColumnConstraints().setAll(
                new ColumnConstraints(160), columna(Priority.ALWAYS), new ColumnConstraints());
        clavePorDepartamento.clear();
        int fila = 0;
        for (Departamento d : productoService.listarDepartamentos()) {
            TextField txt = new TextField(d.getClaveProdServ() != null ? d.getClaveProdServ() : "");
            txt.setPromptText(CatalogoSat.PROD_SERV_ABARROTES + " (genérica)");
            Button buscar = new Button("Buscar…");
            buscar.setOnAction(e -> CatalogoSatDialogo.elegirProdServ(txt.getText()).ifPresent(en -> txt.setText(en.clave())));
            gridDepartamentos.addRow(fila++, new Label(d.getNombre()), txt, buscar);
            clavePorDepartamento.put(d.getId(), txt);
        }
        if (fila == 0) {
            Label vacio = new Label("Aún no hay departamentos. Créalos en Productos → Departamentos.");
            vacio.getStyleClass().add("hint");
            gridDepartamentos.add(vacio, 0, 0, 3, 1);
            btnGuardarClaves.setDisable(true);
        }
    }

    private static ColumnConstraints columna(Priority p) {
        ColumnConstraints c = new ColumnConstraints();
        c.setHgrow(p);
        return c;
    }

    @FXML
    private void onGuardarClaves() {
        Map<Long, String> claves = new LinkedHashMap<>();
        clavePorDepartamento.forEach((id, txt) -> claves.put(id, txt.getText()));
        try {
            fiscalService.guardarClavesDepartamentos(claves);
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Facturación", "Las claves por departamento se guardaron.");
            cargarDepartamentos();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }
}
