package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.service.BaseDatosService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Base de datos (Configuración): ruta, tamaño y conteo de registros, más
 * "Respaldar ahora" y "Restaurar respaldo". Solo ADMIN; las reglas viven en
 * BaseDatosService.
 */
@Component
@Scope("prototype")
public class ConfiguracionBaseDatosController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private Label      lblRuta;
    @FXML private Label      lblTamano;
    @FXML private Label      lblPendiente;
    @FXML private GridPane   gridRegistros;

    @Autowired private BaseDatosService baseDatosService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        cargar();
    }

    @FXML
    private void onRespaldar() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar respaldo");
        fc.setInitialFileName("respaldo-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".db");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Base de datos SQLite (*.db)", "*.db"));
        File destino = fc.showSaveDialog(raiz.getScene().getWindow());
        if (destino == null) return;
        try {
            Path guardado = baseDatosService.respaldar(destino.toPath());
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Respaldo creado", "El respaldo se guardó en:\n" + guardado);
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo respaldar", e.getMessage());
        }
    }

    @FXML
    private void onRestaurar() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elegir respaldo a restaurar");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Base de datos SQLite (*.db)", "*.db"));
        File origen = fc.showOpenDialog(raiz.getScene().getWindow());
        if (origen == null) return;

        if (!Ventanas.confirmar("Restaurar respaldo",
                "Se reemplazarán TODOS los datos actuales por los del respaldo:\n" + origen.getName()
                + "\n\nAntes se guardará una copia de seguridad de la base actual. ¿Continuar?")) {
            return;
        }
        Optional<String> clave = pedirContrasena();
        if (clave.isEmpty()) return;
        try {
            Path seguridad = baseDatosService.restaurar(origen.toPath(), clave.get());
            lblPendiente.setVisible(true);
            lblPendiente.setManaged(true);
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Respaldo listo para restaurar",
                    "Cierra y vuelve a abrir el programa para terminar la restauración.\n\n"
                    + "Copia de seguridad de los datos actuales:\n" + seguridad);
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo restaurar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private void cargar() {
        try {
            BaseDatosService.Informacion info = baseDatosService.informacion();
            lblRuta.setText(info.archivo().toString());
            lblTamano.setText(String.format("%.1f KB", info.bytes() / 1024.0));
            gridRegistros.getChildren().clear();
            int fila = 0;
            for (var e : info.registros().entrySet()) {
                gridRegistros.add(new Label(e.getKey() + ":"), 0, fila);
                gridRegistros.add(new Label(String.valueOf(e.getValue())), 1, fila);
                fila++;
            }
            boolean pendiente = baseDatosService.hayRestauracionPendiente();
            lblPendiente.setVisible(pendiente);
            lblPendiente.setManaged(pendiente);
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Base de datos", e.getMessage());
        }
    }

    private Optional<String> pedirContrasena() {
        Dialog<String> dlg = new Dialog<>();
        dlg.setTitle("Contraseña del administrador");
        dlg.setHeaderText("Escribe tu contraseña para confirmar la restauración");
        PasswordField txt = new PasswordField();
        txt.setPromptText("Contraseña");
        VBox caja = new VBox(8, txt);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? txt.getText() : null);
        Ventanas.estilizar(dlg);
        return dlg.showAndWait().filter(s -> !s.isEmpty());
    }
}
