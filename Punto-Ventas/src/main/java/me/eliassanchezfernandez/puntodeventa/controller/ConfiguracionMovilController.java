package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.service.AccesoMovilService;
import me.eliassanchezfernandez.puntodeventa.service.AccesoMovilService.SesionInfo;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Configuración → Acceso móvil (solo ADMIN): enciende o apaga el servidor web
 * para teléfonos EN VIVO, define el puerto, muestra las direcciones de la red
 * local y lista las sesiones móviles abiertas para cerrarlas.
 */
@Component
@Scope("prototype")
public class ConfiguracionMovilController implements Initializable {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private BorderPane raiz;
    @FXML private CheckBox   chkActivo;
    @FXML private TextField  txtPuerto;
    @FXML private Label      lblEstado;
    @FXML private VBox       cajaUrls;
    @FXML private TableView<SesionInfo> tablaSesiones;
    @FXML private TableColumn<SesionInfo, String> colUsuario;
    @FXML private TableColumn<SesionInfo, String> colCreada;
    @FXML private TableColumn<SesionInfo, String> colExpira;
    @FXML private TableColumn<SesionInfo, String> colUso;
    @FXML private TableColumn<SesionInfo, String> colIp;
    @FXML private TableColumn<SesionInfo, SesionInfo> colAccion;

    @Autowired private AccesoMovilService accesoMovilService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colUsuario.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().usuario()));
        colCreada.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(local(c.getValue().sesion().getCreada())));
        colExpira.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().sesion().isPermanente() ? "Permanente (60 días sin uso)" : local(c.getValue().sesion().getExpira())));
        colUso.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(local(c.getValue().sesion().getUltimoUso())));
        colIp.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().sesion().getIp() == null ? "" : c.getValue().sesion().getIp()));
        colAccion.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        colAccion.setCellFactory(col -> new TableCell<>() {
            private final Button cerrar = new Button("Cerrar");
            @Override protected void updateItem(SesionInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                cerrar.setOnAction(e -> onCerrarSesion(item));
                setGraphic(cerrar);
            }
        });
        tablaSesiones.setPlaceholder(new Label("No hay sesiones móviles abiertas."));

        Configuracion c = accesoMovilService.obtenerConfiguracion();
        chkActivo.setSelected(accesoMovilService.servidorActivo() || c.isMovilActivo());
        txtPuerto.setText(String.valueOf(c.getMovilPuerto()));
        refrescar();
    }

    @FXML
    private void onAplicar() {
        int puerto;
        try {
            puerto = Integer.parseInt(txtPuerto.getText().trim());
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Puerto inválido", "El puerto debe ser un número entero.");
            return;
        }
        try {
            accesoMovilService.configurar(chkActivo.isSelected(), puerto);
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Acceso móvil", e.getMessage());
            chkActivo.setSelected(accesoMovilService.servidorActivo());
        }
        refrescar();
    }

    @FXML private void onActualizar() { refrescar(); }

    @FXML
    private void onCerrarTodas() {
        if (!Ventanas.confirmar("Cerrar sesiones", "Se cerrarán TODAS las sesiones móviles. ¿Continuar?")) return;
        try {
            accesoMovilService.revocarTodas();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Acceso móvil", e.getMessage());
        }
        refrescar();
    }

    private void onCerrarSesion(SesionInfo info) {
        try {
            accesoMovilService.revocar(info.sesion().getId());
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Acceso móvil", e.getMessage());
        }
        refrescar();
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private void refrescar() {
        boolean activo = accesoMovilService.servidorActivo();
        int puerto = activo ? accesoMovilService.puertoEnUso() : puertoCapturado();
        lblEstado.setText(activo ? "Servidor ENCENDIDO en el puerto " + puerto : "Servidor apagado");
        lblEstado.getStyleClass().removeAll("badge", "badge-success", "hint");
        if (activo) lblEstado.getStyleClass().addAll("badge", "badge-success");
        else lblEstado.getStyleClass().add("hint");

        cajaUrls.getChildren().clear();
        List<String> urls = accesoMovilService.urlsLan(puerto);
        if (!activo) {
            cajaUrls.getChildren().add(hint("Enciende el servidor para ver la dirección que debes abrir en el teléfono."));
        } else if (urls.isEmpty()) {
            cajaUrls.getChildren().add(hint("No se encontró una red local. Conecta esta computadora al Wi-Fi."));
        } else {
            for (String u : urls) {
                TextField campo = new TextField(u);
                campo.setEditable(false);
                HBox.setHgrow(campo, javafx.scene.layout.Priority.ALWAYS);
                Button copiar = new Button("Copiar");
                copiar.setOnAction(e -> {
                    ClipboardContent cc = new ClipboardContent();
                    cc.putString(u);
                    Clipboard.getSystemClipboard().setContent(cc);
                });
                cajaUrls.getChildren().add(new HBox(8, campo, copiar));
            }
        }
        try {
            tablaSesiones.setItems(FXCollections.observableArrayList(accesoMovilService.sesionesActivas()));
        } catch (NegocioException e) {
            tablaSesiones.setItems(FXCollections.observableArrayList());
        }
    }

    private int puertoCapturado() {
        try {
            return Integer.parseInt(txtPuerto.getText().trim());
        } catch (NumberFormatException e) {
            return Configuracion.MOVIL_PUERTO_POR_DEFECTO;
        }
    }

    private static Label hint(String texto) {
        Label l = new Label(texto);
        l.setWrapText(true);
        l.getStyleClass().add("hint");
        return l;
    }

    /** Las fechas se guardan en UTC; aquí se muestran en la hora local */
    private static String local(LocalDateTime utc) {
        if (utc == null) return "";
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).format(FECHA);
    }
}
