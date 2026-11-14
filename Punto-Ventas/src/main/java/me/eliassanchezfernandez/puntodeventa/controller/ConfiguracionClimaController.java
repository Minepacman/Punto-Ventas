package me.eliassanchezfernandez.puntodeventa.controller;

import atlantafx.base.controls.ToggleSwitch;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.service.ClimaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.GeolocalizacionIpService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.UbicacionService;
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
 * ConfiguracionService). La ubicación se elige con {@link SelectorUbicacion}
 * (detectar por internet, buscar la ciudad o coordenadas a mano en Opciones
 * avanzadas). Las coordenadas son lo único que se envía al servicio de clima;
 * "Probar conexión" consulta una vez sin guardar nada.
 */
@Component
@Scope("prototype")
public class ConfiguracionClimaController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    @FXML private BorderPane raiz;
    @FXML private SelectorUbicacion selectorUbicacion;
    @FXML private ToggleSwitch swClima;
    @FXML private Button     btnProbar;
    @FXML private Label      lblEstado;

    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ClimaService         climaService;
    @Autowired private UbicacionService     ubicacionService;
    @Autowired private GeolocalizacionIpService geolocalizacionService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        Configuracion c = configuracionService.obtener();
        selectorUbicacion.configurar(ubicacionService, geolocalizacionService);
        selectorUbicacion.setValores(c.getCiudad(), c.getLatitud(), c.getLongitud());
        swClima.setSelected(c.isClimaActivo());
        // Sin ubicación el clima no puede encenderse (ConfiguracionService lo rechazaría)
        selectorUbicacion.setOnCambio(this::ajustarInterruptor);
        ajustarInterruptor();
        Optional<ClimaRegistro> ultimo = climaService.actual();
        lblEstado.setText(ultimo.map(r -> "Último registro: " + r.getDescripcion() + " (" + r.getFechaHora().format(FMT) + ")")
                .orElse(c.isClimaActivo() ? "Sin datos de clima todavía." : ""));
    }

    private void ajustarInterruptor() {
        boolean hay = selectorUbicacion.tieneCoordenadas();
        swClima.setDisable(!hay);
        if (!hay) swClima.setSelected(false);
    }

    /** Consulta una vez el servicio con las coordenadas capturadas (sin guardar) */
    @FXML
    private void onProbar() {
        Double[] coords;
        try {
            coords = selectorUbicacion.coordenadas();
        } catch (NegocioException e) {
            lblEstado.setText(e.getMessage());
            return;
        }
        if (coords[0] == null) {
            lblEstado.setText("Primero elige la ubicación (Detectar o Buscar) para probar.");
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
        try {
            Double[] coords = selectorUbicacion.coordenadas();
            Configuracion g = configuracionService.guardarUbicacionClima(coords[0], coords[1],
                    selectorUbicacion.ciudad(), swClima.isSelected());
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
}
