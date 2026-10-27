package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.LogotipoService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.TicketService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Ticket (Configuración): encabezado, pie, RFC, teléfono, agradecimiento y
 * ancho del papel (58/80 mm), con vista previa en texto que se actualiza al
 * escribir. Solo guarda los datos; la impresión no está en esta etapa.
 */
@Component
@Scope("prototype")
public class ConfiguracionTicketController implements Initializable {

    @FXML private BorderPane  raiz;
    @FXML private TextArea    txtEncabezado;
    @FXML private TextArea    txtPie;
    @FXML private TextField   txtRfc;
    @FXML private TextField   txtTelefono;
    @FXML private TextField   txtAgradecimiento;
    @FXML private RadioButton rb58;
    @FXML private RadioButton rb80;
    @FXML private VBox        hojaTicket;
    @FXML private ImageView   imgLogo;
    @FXML private Label       lblVistaPrevia;

    @Autowired private ConfiguracionService configuracionService;
    @Autowired private TicketService        ticketService;
    @Autowired private LogotipoService      logotipoService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        Configuracion c = configuracionService.obtener();
        txtEncabezado.setText(c.getTicketEncabezado());
        txtPie.setText(c.getTicketPie());
        txtRfc.setText(c.getTicketRfc());
        txtTelefono.setText(c.getTicketTelefono());
        txtAgradecimiento.setText(c.getTicketAgradecimiento());
        (c.getTicketAnchoMm() == 58 ? rb58 : rb80).setSelected(true);

        logotipoService.logoActual().ifPresent(p -> {
            Image img = new Image(p.toUri().toString(), 160, 60, true, true);
            if (!img.isError()) {
                imgLogo.setImage(img);
                imgLogo.setManaged(true);
                imgLogo.setVisible(true);
            }
        });

        Runnable actualizar = this::actualizarVistaPrevia;
        txtEncabezado.textProperty().addListener((o, a, b) -> actualizar.run());
        txtPie.textProperty().addListener((o, a, b) -> actualizar.run());
        txtRfc.textProperty().addListener((o, a, b) -> actualizar.run());
        txtTelefono.textProperty().addListener((o, a, b) -> actualizar.run());
        txtAgradecimiento.textProperty().addListener((o, a, b) -> actualizar.run());
        rb58.selectedProperty().addListener((o, a, b) -> actualizar.run());
        actualizarVistaPrevia();
    }

    /** Arma una configuración SIN guardar con lo escrito y la pinta como ticket */
    private void actualizarVistaPrevia() {
        Configuracion c = configuracionService.obtener();
        Configuracion vista = new Configuracion();
        vista.setNombreTienda(c.getNombreTienda());
        vista.setDireccion(c.getDireccion());
        vista.setTasaIva(c.getTasaIva());
        vista.setMonedaSimbolo(c.getMonedaSimbolo());
        vista.setMonedaCodigo(c.getMonedaCodigo());
        vista.setTicketEncabezado(vacioANull(txtEncabezado.getText()));
        vista.setTicketPie(vacioANull(txtPie.getText()));
        vista.setTicketRfc(vacioANull(txtRfc.getText()));
        vista.setTicketTelefono(vacioANull(txtTelefono.getText()));
        vista.setTicketAgradecimiento(vacioANull(txtAgradecimiento.getText()));
        vista.setTicketAnchoMm(rb58.isSelected() ? 58 : 80);
        lblVistaPrevia.setText(ticketService.vistaPrevia(vista, configuracionService.siguienteFolio()));
    }

    @FXML
    private void onGuardar() {
        try {
            configuracionService.guardarTicket(txtEncabezado.getText(), txtPie.getText(), txtRfc.getText(),
                    txtTelefono.getText(), txtAgradecimiento.getText(), rb58.isSelected() ? 58 : 80);
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Ticket guardado",
                    "Los datos del ticket se guardaron.");
            onCerrar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private static String vacioANull(String t) {
        return t == null || t.isBlank() ? null : t.trim();
    }
}
