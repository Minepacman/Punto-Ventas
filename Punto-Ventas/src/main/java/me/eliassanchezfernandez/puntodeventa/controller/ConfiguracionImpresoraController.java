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
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.impresion.EscPos;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ModoImpresora;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.DispositivosService;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Configuración → Impresora de tickets (solo ADMIN; el servicio lo vuelve a
 * verificar y audita). La lista de impresoras se pide en un hilo aparte porque
 * en Windows puede tardar. Imprimir la prueba también va fuera del hilo de JavaFX.
 */
@Component
@Scope("prototype")
public class ConfiguracionImpresoraController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private ComboBox<ModoImpresora> cmbModo;
    @FXML private Label lblModoAyuda;
    @FXML private VBox boxImpresora;
    @FXML private ComboBox<String> cmbImpresora;
    @FXML private Label lblListaEstado;
    @FXML private VBox boxCodePage;
    @FXML private ComboBox<EscPos.CodePage> cmbCodePage;
    @FXML private RadioButton rb58;
    @FXML private RadioButton rb80;
    @FXML private CheckBox chkAutomatico;
    @FXML private Spinner<Integer> spCopias;
    @FXML private CheckBox chkCajon;
    @FXML private Button btnPrueba;

    @Autowired private DispositivosService dispositivosService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ImpresionService impresionService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        ConfiguracionDispositivos c = dispositivosService.obtener();
        cmbModo.setItems(FXCollections.observableArrayList(ModoImpresora.values()));
        cmbModo.setValue(c.getModoImpresora());
        cmbCodePage.setItems(FXCollections.observableArrayList(EscPos.CodePage.values()));
        cmbCodePage.setValue(EscPos.CodePage.de(c.getImpresoraCodePage()));
        (configuracionService.obtener().getTicketAnchoMm() == 58 ? rb58 : rb80).setSelected(true);
        chkAutomatico.setSelected(c.isImprimirAutomatico());
        chkCajon.setSelected(c.isAbrirCajonEfectivo());
        spCopias.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, ConfiguracionDispositivos.COPIAS_MAXIMO, c.getCopias()));

        if (c.getImpresoraNombre() != null) {
            cmbImpresora.setItems(FXCollections.observableArrayList(c.getImpresoraNombre()));
            cmbImpresora.setValue(c.getImpresoraNombre());
        }
        actualizarVisibilidad();
        onActualizarLista();
    }

    @FXML
    private void onModoCambio() {
        actualizarVisibilidad();
    }

    private void actualizarVisibilidad() {
        ModoImpresora modo = cmbModo.getValue();
        boolean hayImpresora = modo != ModoImpresora.NINGUNA;
        boxImpresora.setVisible(hayImpresora);
        boxImpresora.setManaged(hayImpresora);
        boolean termica = modo == ModoImpresora.TERMICA;
        boxCodePage.setVisible(termica);
        boxCodePage.setManaged(termica);
        chkCajon.setDisable(!termica);
        btnPrueba.setDisable(!hayImpresora);
        lblModoAyuda.setText(switch (modo == null ? ModoImpresora.NINGUNA : modo) {
            case NINGUNA -> "No se imprime nada. Los tickets se pueden seguir viendo en pantalla.";
            case TERMICA -> "Impresora de tickets térmica (Epson, Star, Bixolon, 3nStar, genéricas...). Se le manda ESC/POS directo: es rápida, corta el papel y abre el cajón.";
            case SISTEMA -> "Cualquier impresora normal (láser, inyección de tinta). Imprime el ticket como texto en una hoja; no abre el cajón.";
        });
    }

    @FXML
    private void onActualizarLista() {
        lblListaEstado.setText("Buscando impresoras…");
        Thread t = new Thread(() -> {
            List<String> nombres = impresionService.listarImpresoras();
            Platform.runLater(() -> {
                String actual = cmbImpresora.getValue();
                List<String> lista = new ArrayList<>(nombres);
                if (actual != null && !lista.contains(actual)) lista.add(0, actual);
                cmbImpresora.setItems(FXCollections.observableArrayList(lista));
                cmbImpresora.setValue(actual);
                lblListaEstado.setText(nombres.isEmpty()
                        ? "No se encontraron impresoras instaladas en este equipo."
                        : nombres.size() + " impresora(s) encontrada(s).");
            });
        }, "impresoras-listar");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onGuardar() {
        if (guardar()) {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Impresora", "La configuración de la impresora se guardó.");
        }
    }

    @FXML
    private void onPrueba() {
        if (!guardar()) return;
        btnPrueba.setDisable(true);
        try {
            impresionService.imprimirPruebaAsync().whenComplete((res, error) -> Platform.runLater(() -> {
                btnPrueba.setDisable(false);
                if (error != null || res == null || res.esFallo()) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Impresión de prueba",
                            res != null ? res.mensaje() : "No se pudo imprimir la prueba.");
                } else {
                    Ventanas.alerta(Alert.AlertType.INFORMATION, "Impresión de prueba",
                            "Se envió la prueba a la impresora. Revisa que salga con acentos (á é í ó ú ñ).");
                }
            }));
        } catch (NegocioException e) {
            btnPrueba.setDisable(false);
            Ventanas.alerta(Alert.AlertType.ERROR, "Impresión de prueba", e.getMessage());
        }
    }

    private boolean guardar() {
        try {
            spCopias.commitValue();   // toma lo escrito a mano en el campo
            dispositivosService.guardarImpresora(cmbModo.getValue(), cmbImpresora.getValue(),
                    cmbCodePage.getValue() != null ? cmbCodePage.getValue().name() : null,
                    chkAutomatico.isSelected(), spCopias.getValue(), chkCajon.isSelected(),
                    rb58.isSelected() ? 58 : 80);
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
