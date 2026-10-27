package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.bascula.BasculaService;
import me.eliassanchezfernandez.puntodeventa.bascula.PesoParser;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ProtocoloBascula;
import me.eliassanchezfernandez.puntodeventa.service.DispositivosService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/**
 * Configuración → Báscula (solo ADMIN). Buscar puertos y leer el peso se hacen
 * fuera del hilo de JavaFX: un puerto sin respuesta no congela la pantalla.
 * La "trama de ejemplo" permite afinar la expresión regular sin báscula.
 */
@Component
@Scope("prototype")
public class ConfiguracionBasculaController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private CheckBox chkActiva;
    @FXML private ComboBox<String> cmbPuerto;
    @FXML private Label lblPuertosEstado;
    @FXML private ComboBox<Integer> cmbBaudios;
    @FXML private Spinner<Integer> spEspera;
    @FXML private ComboBox<ProtocoloBascula> cmbProtocolo;
    @FXML private VBox boxComando;
    @FXML private TextField txtComando;
    @FXML private TextField txtRegex;
    @FXML private TextField txtTrama;
    @FXML private Label lblTramaResultado;
    @FXML private Label lblLectura;
    @FXML private Button btnLeer;

    @Autowired private DispositivosService dispositivosService;
    @Autowired private BasculaService basculaService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        ConfiguracionDispositivos c = dispositivosService.obtener();
        chkActiva.setSelected(c.isBasculaActiva());
        cmbPuerto.setItems(FXCollections.observableArrayList());
        cmbPuerto.setValue(c.getBasculaPuerto());
        cmbBaudios.setItems(FXCollections.observableArrayList(DispositivosService.BAUDIOS_PERMITIDOS));
        cmbBaudios.setValue(c.getBasculaBaudios());
        spEspera.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(200, 10_000, c.getBasculaTimeoutMs(), 100));
        cmbProtocolo.setItems(FXCollections.observableArrayList(ProtocoloBascula.values()));
        cmbProtocolo.setValue(c.getProtocoloBascula());
        txtComando.setText(c.getBasculaComando());
        txtRegex.setText(c.getBasculaRegex());
        txtRegex.textProperty().addListener((o, a, b) -> onTramaCambio());
        actualizarVisibilidad();
        onBuscarPuertos();
    }

    @FXML
    private void onProtocoloCambio() {
        actualizarVisibilidad();
    }

    private void actualizarVisibilidad() {
        boolean peticion = cmbProtocolo.getValue() == ProtocoloBascula.PETICION;
        boxComando.setVisible(peticion);
        boxComando.setManaged(peticion);
    }

    @FXML
    private void onBuscarPuertos() {
        lblPuertosEstado.setText("Buscando puertos…");
        Thread t = new Thread(() -> {
            List<String> puertos = basculaService.listarPuertos();
            Platform.runLater(() -> {
                String actual = cmbPuerto.getValue();
                List<String> lista = new ArrayList<>(puertos);
                if (actual != null && !actual.isBlank() && !lista.contains(actual)) lista.add(0, actual);
                cmbPuerto.setItems(FXCollections.observableArrayList(lista));
                cmbPuerto.setValue(actual);
                lblPuertosEstado.setText(puertos.isEmpty()
                        ? "No se detectaron puertos serie. Conecta el cable (o el adaptador USB-serie) y vuelve a buscar; también puedes escribir el nombre del puerto."
                        : puertos.size() + " puerto(s) detectado(s).");
            });
        }, "bascula-puertos");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onTramaCambio() {
        String trama = txtTrama.getText();
        if (trama == null || trama.isBlank()) {
            lblTramaResultado.setText("—");
            return;
        }
        try {
            Pattern p = DispositivosService.validarRegex(txtRegex.getText() == null || txtRegex.getText().isBlank()
                    ? ConfiguracionDispositivos.REGEX_PESO_POR_DEFECTO : txtRegex.getText().trim());
            Optional<Double> peso = PesoParser.extraer(trama, p);
            lblTramaResultado.setText(peso.map(v -> String.format("%.3f", v)).orElse("sin peso"));
        } catch (NegocioException e) {
            lblTramaResultado.setText("regex inválida");
        }
    }

    @FXML
    private void onGuardar() {
        if (guardar()) {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Báscula", "La configuración de la báscula se guardó.");
        }
    }

    /** Lee con lo escrito en pantalla (sin guardar) para probar antes de dejarlo */
    @FXML
    private void onLeerPrueba() {
        ConfiguracionDispositivos borrador;
        try {
            borrador = leerFormulario();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Báscula", e.getMessage());
            return;
        }
        btnLeer.setDisable(true);
        lblLectura.setText("Leyendo…");
        Thread t = new Thread(() -> {
            String texto;
            try {
                texto = String.format("Peso leído: %.3f kg", basculaService.probar(borrador));
            } catch (RuntimeException e) {
                texto = e.getMessage();
            }
            final String resultado = texto;
            Platform.runLater(() -> {
                btnLeer.setDisable(false);
                lblLectura.setText(resultado);
            });
        }, "bascula-prueba");
        t.setDaemon(true);
        t.start();
    }

    private ConfiguracionDispositivos leerFormulario() {
        spEspera.commitValue();
        ConfiguracionDispositivos c = new ConfiguracionDispositivos();
        c.setBasculaActiva(chkActiva.isSelected());
        String puerto = cmbPuerto.getEditor().getText();
        c.setBasculaPuerto(puerto == null || puerto.isBlank() ? cmbPuerto.getValue() : puerto.trim());
        if (cmbBaudios.getValue() == null) throw new NegocioException("Elige la velocidad (baudios).");
        c.setBasculaBaudios(cmbBaudios.getValue());
        c.setProtocoloBascula(cmbProtocolo.getValue());
        c.setBasculaComando(txtComando.getText());
        c.setBasculaRegex(txtRegex.getText());
        c.setBasculaTimeoutMs(spEspera.getValue());
        return c;
    }

    private boolean guardar() {
        try {
            ConfiguracionDispositivos c = leerFormulario();
            dispositivosService.guardarBascula(c.isBasculaActiva(), c.getBasculaPuerto(), c.getBasculaBaudios(),
                    c.getProtocoloBascula(), c.getBasculaComando(), c.getBasculaRegex(), c.getBasculaTimeoutMs());
            return true;
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
            return false;
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }
}
