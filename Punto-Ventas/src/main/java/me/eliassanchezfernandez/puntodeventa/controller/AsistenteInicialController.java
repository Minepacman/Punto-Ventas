package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.service.BienvenidaService;
import me.eliassanchezfernandez.puntodeventa.service.ClimaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.LogotipoService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.UbicacionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Asistente de primera vez del administrador (asistente_inicial.fxml): nombre
 * del negocio, logotipo opcional y ubicación para el clima. Lo abre
 * MainController una sola vez por instalación (BienvenidaService decide).
 *
 * El logotipo se guarda al momento con LogotipoService (igual que en
 * Configuración → Logotipo). La ciudad se busca en línea (UbicacionService) y
 * rellena las coordenadas, que también se pueden teclear a mano; sin internet
 * se avisa y el paso se puede omitir. "Guardar y continuar" guarda el nombre
 * (BienvenidaService) y la ubicación (ConfiguracionService, la misma vía que
 * Configuración → Ubicación y clima). "Omitir por ahora" no vuelve a preguntar.
 * Cerrar con la X deja el asistente pendiente para el siguiente inicio.
 */
@Component
@Scope("prototype")
public class AsistenteInicialController implements Initializable {

    @FXML private BorderPane raiz;
    @FXML private TextField  txtNombre;
    @FXML private ImageView  imgLogo;
    @FXML private Label      lblSinLogo;
    @FXML private Button     btnQuitarLogo;
    @FXML private TextField  txtCiudad;
    @FXML private Button     btnBuscar;
    @FXML private ComboBox<UbicacionService.Lugar> cmbLugares;
    @FXML private TextField  txtLatitud;
    @FXML private TextField  txtLongitud;
    @FXML private CheckBox   chkClima;
    @FXML private Label      lblEstadoUbicacion;
    @FXML private Label      lblError;

    @Autowired private BienvenidaService    bienvenidaService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private LogotipoService      logotipoService;
    @Autowired private UbicacionService     ubicacionService;
    @Autowired private ClimaService         climaService;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        Configuracion c = configuracionService.obtener();
        txtNombre.setText(c.getNombreTienda() != null ? c.getNombreTienda() : "");
        txtCiudad.setText(c.getCiudad() != null ? c.getCiudad() : "");
        txtLatitud.setText(c.getLatitud() != null ? String.valueOf(c.getLatitud()) : "");
        txtLongitud.setText(c.getLongitud() != null ? String.valueOf(c.getLongitud()) : "");
        chkClima.setSelected(c.isClimaActivo() || !c.tieneUbicacion());
        mostrarLogo();
        mostrar(cmbLugares, false);
        cmbLugares.valueProperty().addListener((obs, antes, lugar) -> {
            if (lugar == null) return;
            txtCiudad.setText(lugar.nombre());
            txtLatitud.setText(String.format(java.util.Locale.ROOT, "%.4f", lugar.latitud()));
            txtLongitud.setText(String.format(java.util.Locale.ROOT, "%.4f", lugar.longitud()));
            lblEstadoUbicacion.setText("Ubicación: " + lugar.etiqueta());
            ocultarError();
        });
        Platform.runLater(() -> {
            txtNombre.requestFocus();
            txtNombre.selectAll();
        });
    }

    // ── Logotipo ─────────────────────────────────────────────────────────

    @FXML
    private void onElegirLogo() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elegir logotipo");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes PNG o JPG", "*.png", "*.jpg", "*.jpeg"));
        File archivo = fc.showOpenDialog(raiz.getScene().getWindow());
        if (archivo == null) return;
        try {
            logotipoService.establecer(archivo.toPath());
            mostrarLogo();
            ocultarError();
        } catch (NegocioException e) {
            mostrarError(e.getMessage());
        }
    }

    @FXML
    private void onQuitarLogo() {
        try {
            logotipoService.quitar();
            mostrarLogo();
        } catch (NegocioException e) {
            mostrarError(e.getMessage());
        }
    }

    private void mostrarLogo() {
        Path logo = logotipoService.logoActual().orElse(null);
        Image img = logo == null ? null : new Image(logo.toUri().toString(), 200, 90, true, true);
        boolean hay = img != null && !img.isError();
        imgLogo.setImage(hay ? img : null);
        mostrar(imgLogo, hay);
        mostrar(lblSinLogo, !hay);
        btnQuitarLogo.setDisable(!hay);
    }

    // ── Ubicación ────────────────────────────────────────────────────────

    /** Busca la ciudad en línea (hilo aparte) y ofrece las coincidencias en el combo */
    @FXML
    private void onBuscarCiudad() {
        String texto = txtCiudad.getText();
        ocultarError();
        try {
            if (texto == null || texto.trim().length() < 2) throw new NegocioException("Escribe al menos dos letras de la ciudad.");
        } catch (NegocioException e) {
            mostrarError(e.getMessage());
            txtCiudad.requestFocus();
            return;
        }
        btnBuscar.setDisable(true);
        lblEstadoUbicacion.setText("Buscando…");
        Thread t = new Thread(() -> {
            List<UbicacionService.Lugar> lugares;
            String aviso = null;
            try {
                lugares = ubicacionService.buscar(texto);
            } catch (IOException e) {
                lugares = List.of();
                aviso = "Sin conexión a internet. Puedes escribir la latitud y la longitud a mano, "
                        + "o dejar la ubicación para después (Configuración → Ubicación y clima).";
            } catch (NegocioException e) {
                lugares = List.of();
                aviso = e.getMessage();
            }
            List<UbicacionService.Lugar> encontrados = lugares;
            String mensaje = aviso;
            Platform.runLater(() -> {
                btnBuscar.setDisable(false);
                if (mensaje != null) {
                    lblEstadoUbicacion.setText(mensaje);
                    return;
                }
                cmbLugares.getItems().setAll(encontrados);
                if (encontrados.isEmpty()) {
                    mostrar(cmbLugares, false);
                    lblEstadoUbicacion.setText("No se encontró esa ciudad. Prueba con otro nombre o captura las coordenadas.");
                    return;
                }
                mostrar(cmbLugares, true);
                lblEstadoUbicacion.setText(encontrados.size() == 1
                        ? "Se encontró un lugar; revisa que sea el correcto."
                        : "Elige tu ciudad en la lista (" + encontrados.size() + " coincidencias).");
                cmbLugares.getSelectionModel().select(0);
                if (encontrados.size() > 1) cmbLugares.show();
            });
        }, "asistente-buscar-ciudad");
        t.setDaemon(true);
        t.start();
    }

    // ── Guardar / omitir ─────────────────────────────────────────────────

    @FXML
    private void onGuardar() {
        ocultarError();
        Double[] coords = leerCoordenadas();
        if (coords == null) return;
        try {
            bienvenidaService.completarAsistenteInicial(txtNombre.getText());
        } catch (NegocioException e) {
            mostrarError(e.getMessage());
            txtNombre.requestFocus();
            return;
        } catch (RuntimeException e) {
            mostrarError("No se pudo guardar: " + e.getMessage());
            return;
        }
        boolean climaActivo = false;
        if (coords[0] != null) {
            try {
                Configuracion g = configuracionService.guardarUbicacionClima(coords[0], coords[1],
                        txtCiudad.getText(), chkClima.isSelected());
                climaActivo = g.isClimaActivo();
            } catch (NegocioException e) {
                // El nombre ya quedó guardado y el asistente hecho; la ubicación se corrige aquí mismo
                mostrarError(e.getMessage());
                return;
            }
        }
        if (climaActivo) {
            Thread t = new Thread(climaService::registrarAhora, "clima-primer-registro");
            t.setDaemon(true);
            t.start();
        }
        cerrar();
    }

    @FXML
    private void onOmitir() {
        if (!Ventanas.confirmar("Omitir por ahora",
                "Puedes capturar el nombre, el logotipo y la ubicación cuando quieras en la pestaña Configuración.\n"
                + "No se volverá a mostrar este asistente. ¿Omitir?")) {
            return;
        }
        try {
            bienvenidaService.omitirAsistenteInicial();
        } catch (RuntimeException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
            return;
        }
        cerrar();
    }

    private void cerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** [latitud, longitud] (ambos null si están vacíos); null si hay un número inválido o falta uno */
    private Double[] leerCoordenadas() {
        String lat = txtLatitud.getText().trim().replace(",", ".");
        String lon = txtLongitud.getText().trim().replace(",", ".");
        if (lat.isEmpty() && lon.isEmpty()) return new Double[] { null, null };
        if (lat.isEmpty() || lon.isEmpty()) {
            mostrarError("Captura la latitud Y la longitud (o deja las dos vacías para omitir la ubicación).");
            return null;
        }
        try {
            return new Double[] { Double.parseDouble(lat), Double.parseDouble(lon) };
        } catch (NumberFormatException e) {
            mostrarError("Escribe la latitud y la longitud como números decimales (ej: 19.4326 y -99.1332).");
            return null;
        }
    }

    private void mostrarError(String mensaje) {
        lblError.setText(mensaje);
        mostrar(lblError, true);
    }

    private void ocultarError() {
        mostrar(lblError, false);
    }

    private static void mostrar(javafx.scene.Node nodo, boolean visible) {
        nodo.setVisible(visible);
        nodo.setManaged(visible);
    }
}
