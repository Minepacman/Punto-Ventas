package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.service.ClimaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Ubicación del negocio y clima (Configuración, solo ADMIN; lo verifica
 * ConfiguracionService). Las coordenadas son lo único que se envía al
 * servicio de clima; "Probar conexión" consulta una vez sin guardar nada.
 */
@Component
@Scope("prototype")
public class ConfiguracionClimaController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    @FXML private BorderPane raiz;
    @FXML private TextField  txtCiudad;
    @FXML private TextField  txtLatitud;
    @FXML private TextField  txtLongitud;
    @FXML private CheckBox   chkClimaActivo;
    @FXML private Button     btnProbar;
    @FXML private Label      lblEstado;

    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ClimaService         climaService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        Configuracion c = configuracionService.obtener();
        txtCiudad.setText(c.getCiudad() != null ? c.getCiudad() : "");
        txtLatitud.setText(c.getLatitud() != null ? String.valueOf(c.getLatitud()) : "");
        txtLongitud.setText(c.getLongitud() != null ? String.valueOf(c.getLongitud()) : "");
        chkClimaActivo.setSelected(c.isClimaActivo());
        Optional<ClimaRegistro> ultimo = climaService.actual();
        lblEstado.setText(ultimo.map(r -> "Último registro: " + r.getDescripcion() + " (" + r.getFechaHora().format(FMT) + ")")
                .orElse(c.isClimaActivo() ? "Sin datos de clima todavía." : ""));
    }

    /** Consulta una vez el servicio con las coordenadas capturadas (sin guardar) */
    @FXML
    private void onProbar() {
        Double[] coords = leerCoordenadas();
        if (coords == null) return;
        if (coords[0] == null) {
            lblEstado.setText("Captura la latitud y la longitud para probar.");
            return;
        }
        btnProbar.setDisable(true);
        lblEstado.setText("Consultando…");
        Thread t = new Thread(() -> {
            String mensaje;
            try {
                ClimaService.Lectura l = climaService.consultar(coords[0], coords[1]);
                mensaje = String.format("Conexión correcta: %s, %.0f °C (%s).", l.categoria(), l.temperatura(), l.banda());
            } catch (IOException e) {
                mensaje = "Sin conexión con el servicio de clima: " + e.getMessage()
                        + " El programa funciona igual sin clima.";
            }
            final String texto = mensaje;
            Platform.runLater(() -> {
                btnProbar.setDisable(false);
                lblEstado.setText(texto);
            });
        }, "clima-probar");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onGuardar() {
        Double[] coords = leerCoordenadas();
        if (coords == null) return;
        try {
            Configuracion g = configuracionService.guardarUbicacionClima(coords[0], coords[1],
                    txtCiudad.getText(), chkClimaActivo.isSelected());
            if (g.isClimaActivo()) {
                // Primer registro de inmediato, en segundo plano (si no hay internet, nada pasa)
                Thread t = new Thread(climaService::registrarAhora, "clima-primer-registro");
                t.setDaemon(true);
                t.start();
            }
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Ubicación guardada",
                    g.isClimaActivo() ? "El clima se registrará cada hora." : "El clima está desactivado.");
            onCerrar();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    @FXML
    private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    /** [latitud, longitud] (ambos null si los campos están vacíos); null si hay un número inválido */
    private Double[] leerCoordenadas() {
        String lat = txtLatitud.getText().trim().replace(",", ".");
        String lon = txtLongitud.getText().trim().replace(",", ".");
        if (lat.isEmpty() && lon.isEmpty()) return new Double[] { null, null };
        try {
            return new Double[] { lat.isEmpty() ? null : Double.parseDouble(lat), lon.isEmpty() ? null : Double.parseDouble(lon) };
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Coordenadas inválidas",
                    "Escribe la latitud y la longitud como números decimales (ej: 19.4326 y -99.1332).");
            return null;
        }
    }
}
