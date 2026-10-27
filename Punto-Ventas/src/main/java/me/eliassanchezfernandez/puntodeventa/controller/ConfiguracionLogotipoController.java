package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.service.LogotipoService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.ResourceBundle;

/**
 * Logotipo (Configuración): elegir un PNG/JPG (máx. 1 MB), verlo y quitarlo.
 * LogotipoService valida y copia el archivo a la carpeta "datos".
 */
@Component
@Scope("prototype")
public class ConfiguracionLogotipoController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private ImageView  imgLogo;
    @FXML private Label      lblSinLogo;
    @FXML private Button     btnQuitar;

    @Autowired private LogotipoService logotipoService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        mostrar();
    }

    @FXML
    private void onElegir() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elegir logotipo");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes PNG o JPG", "*.png", "*.jpg", "*.jpeg"));
        File archivo = fc.showOpenDialog(raiz.getScene().getWindow());
        if (archivo == null) return;
        try {
            logotipoService.establecer(archivo.toPath());
            mostrar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Logotipo no válido", e.getMessage());
        }
    }

    @FXML
    private void onQuitar() {
        if (!Ventanas.confirmar("Quitar logotipo", "¿Quitar el logotipo del negocio?")) return;
        try {
            logotipoService.quitar();
            mostrar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo quitar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private void mostrar() {
        Path logo = logotipoService.logoActual().orElse(null);
        Image img = logo == null ? null : new Image(logo.toUri().toString(), 260, 130, true, true);
        boolean hay = img != null && !img.isError();
        imgLogo.setImage(hay ? img : null);
        lblSinLogo.setVisible(!hay);
        btnQuitar.setDisable(logo == null);
    }
}
