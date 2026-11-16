package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import me.eliassanchezfernandez.puntodeventa.service.GeolocalizacionIpService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.UbicacionService;
import me.eliassanchezfernandez.puntodeventa.util.Iconos;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Bloque "¿Dónde está tu negocio?" que comparten el asistente inicial y
 * Configuración → Ubicación y clima. Pensado para quien no sabe qué es una
 * coordenada:
 *  1. «Detectar mi ubicación»: estima la ciudad por la conexión a internet
 *     ({@link GeolocalizacionIpService}) y pide confirmar: "Parece que estás en…".
 *  2. «Buscar mi ciudad»: escribe el nombre y elige de la lista
 *     ({@link UbicacionService}, geocodificador de Open-Meteo).
 *  3. Latitud y longitud a mano, solo dentro de «Opciones avanzadas».
 * Nada se guarda aquí: el controlador que lo contiene lee {@link #coordenadas()}
 * y {@link #ciudad()} y llama a ConfiguracionService. En FXML:
 * {@code <SelectorUbicacion fx:id="selectorUbicacion"/>} y después
 * {@link #configurar} con los servicios.
 */
public class SelectorUbicacion extends VBox {

    private final Label lblActual = new Label();
    private final Button btnDetectar = new Button("Detectar mi ubicación", Iconos.de("mundo"));
    private final TextField txtCiudad = new TextField();
    private final Button btnBuscar = new Button("Buscar", Iconos.de("buscar"));
    private final ComboBox<UbicacionService.Lugar> cmbLugares = new ComboBox<>();
    private final Label lblEstado = new Label();
    private final HBox cajaConfirmar = new HBox(10);
    private final Label lblConfirmar = new Label();
    private final TextField txtLatitud = new TextField();
    private final TextField txtLongitud = new TextField();
    private final TitledPane avanzadas = new TitledPane();

    private UbicacionService ubicacionService;
    private GeolocalizacionIpService geolocalizacionService;
    private GeolocalizacionIpService.Ubicacion estimada;
    private Runnable alCambiar = () -> { };

    public SelectorUbicacion() {
        setSpacing(8);

        lblActual.getStyleClass().add("text-bold");
        lblActual.setWrapText(true);

        btnDetectar.getStyleClass().add("button-primary");
        btnDetectar.setOnAction(e -> detectar());
        Label o = new Label("o escribe tu ciudad:");
        o.getStyleClass().add("text-muted");
        txtCiudad.setPromptText("ej: Puebla, Toluca, Mérida");
        txtCiudad.setOnAction(e -> buscar());
        HBox.setHgrow(txtCiudad, Priority.ALWAYS);
        btnBuscar.setOnAction(e -> buscar());
        // Botones y etiqueta nunca se recortan con «…» (letra grande): primero cede el campo de la
        // ciudad y, si aun así no cabe, «o escribe tu ciudad» pasa al renglón de abajo
        for (javafx.scene.layout.Region r : List.of(btnDetectar, o, btnBuscar)) r.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        txtCiudad.setPrefWidth(200);
        txtCiudad.setMinWidth(140);
        HBox buscarCiudad = new HBox(8, o, txtCiudad, btnBuscar);
        buscarCiudad.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane acciones = new javafx.scene.layout.FlowPane(8, 8, btnDetectar, buscarCiudad);
        acciones.setAlignment(Pos.CENTER_LEFT);

        cmbLugares.setPromptText("Elige tu ciudad en la lista");
        cmbLugares.setMaxWidth(Double.MAX_VALUE);
        mostrar(cmbLugares, false);
        cmbLugares.valueProperty().addListener((obs, antes, lugar) -> {
            if (lugar == null) return;
            aplicar(lugar.nombre(), lugar.latitud(), lugar.longitud());
            lblEstado.setText("Ubicación elegida: " + lugar.etiqueta() + ".");
        });

        lblConfirmar.setWrapText(true);
        HBox.setHgrow(lblConfirmar, Priority.ALWAYS);
        Button usar = new Button("Sí, usar esta", Iconos.de("listo"));
        usar.getStyleClass().add("button-primary");
        usar.setOnAction(e -> confirmarEstimada());
        Button no = new Button("No, buscar mi ciudad");
        no.setOnAction(e -> {
            mostrar(cajaConfirmar, false);
            txtCiudad.requestFocus();
        });
        cajaConfirmar.getChildren().addAll(lblConfirmar, usar, no);
        cajaConfirmar.setAlignment(Pos.CENTER_LEFT);
        cajaConfirmar.getStyleClass().addAll("callout", "callout-info");
        mostrar(cajaConfirmar, false);

        lblEstado.getStyleClass().add("hint");
        lblEstado.setWrapText(true);
        lblEstado.managedProperty().bind(lblEstado.textProperty().isNotEmpty());   // sin texto no ocupa renglón

        Label privacidad = new Label("«Detectar» usa tu conexión a internet para estimar la ciudad (sin GPS: la precisión es la de "
                + "tu proveedor de internet). No se envía ningún dato de la tienda y solo se consulta cuando pulsas el botón.");
        privacidad.getStyleClass().add("hint");
        privacidad.setWrapText(true);

        // Opciones avanzadas: coordenadas a mano
        txtLatitud.setPromptText("ej: 19.4326");
        txtLongitud.setPromptText("ej: -99.1332");
        VBox lat = new VBox(4, etiqueta("Latitud"), txtLatitud);
        VBox lon = new VBox(4, etiqueta("Longitud"), txtLongitud);
        HBox.setHgrow(lat, Priority.ALWAYS);
        HBox.setHgrow(lon, Priority.ALWAYS);
        Label ayuda = new Label("Solo si quieres afinar: las coordenadas se obtienen en cualquier mapa en línea (clic derecho "
                + "sobre la tienda). Con dos decimales basta; solo se necesita la zona, no la dirección exacta.");
        ayuda.getStyleClass().add("hint");
        ayuda.setWrapText(true);
        VBox contenidoAvanzadas = new VBox(8, new HBox(12, lat, lon), ayuda);
        contenidoAvanzadas.setPadding(new Insets(8));
        avanzadas.setText("Opciones avanzadas (coordenadas)");
        avanzadas.setContent(contenidoAvanzadas);
        avanzadas.setExpanded(false);
        avanzadas.setAnimated(false);
        avanzadas.getStyleClass().add("opciones-avanzadas");
        txtLatitud.textProperty().addListener((o1, a, b) -> refrescarActual());
        txtLongitud.textProperty().addListener((o1, a, b) -> refrescarActual());

        getChildren().addAll(lblActual, acciones, cmbLugares, cajaConfirmar, lblEstado, privacidad, avanzadas);
        setValores(null, null, null);
    }

    /** Servicios que usan los botones (los inyecta el controlador que contiene el bloque) */
    public void configurar(UbicacionService ubicacion, GeolocalizacionIpService geolocalizacion) {
        this.ubicacionService = ubicacion;
        this.geolocalizacionService = geolocalizacion;
    }

    /** Se llama cuando cambian la ciudad o las coordenadas (por detectar, buscar o teclear) */
    public void setOnCambio(Runnable accion) {
        this.alCambiar = accion == null ? () -> { } : accion;
    }

    public void setValores(String ciudad, Double latitud, Double longitud) {
        txtCiudad.setText(ciudad == null ? "" : ciudad);
        txtLatitud.setText(latitud == null ? "" : String.valueOf(latitud));
        txtLongitud.setText(longitud == null ? "" : String.valueOf(longitud));
        refrescarActual();
    }

    public String ciudad() {
        return txtCiudad.getText() == null ? "" : txtCiudad.getText().trim();
    }

    /** ¿Hay coordenadas (completas o incompletas) tecleadas? */
    public boolean tieneCoordenadas() {
        return !txtLatitud.getText().isBlank() || !txtLongitud.getText().isBlank();
    }

    /**
     * [latitud, longitud]; ambos null si los campos están vacíos (ubicación omitida).
     * @throws NegocioException si falta uno o no son números
     */
    public Double[] coordenadas() {
        String lat = txtLatitud.getText().trim().replace(",", ".");
        String lon = txtLongitud.getText().trim().replace(",", ".");
        if (lat.isEmpty() && lon.isEmpty()) return new Double[] { null, null };
        if (lat.isEmpty() || lon.isEmpty()) {
            throw new NegocioException("Faltan datos de la ubicación: usa «Detectar mi ubicación» o busca tu ciudad "
                    + "(o deja las coordenadas vacías para omitirla).");
        }
        try {
            return new Double[] { Double.parseDouble(lat), Double.parseDouble(lon) };
        } catch (NumberFormatException e) {
            throw new NegocioException("La latitud y la longitud deben ser números (ej: 19.4326 y -99.1332). "
                    + "Lo más fácil: usa «Detectar mi ubicación» o busca tu ciudad.");
        }
    }

    /** Campo de ciudad (para dar el foco desde fuera) */
    public TextField campoCiudad() { return txtCiudad; }

    // ── Detectar por IP ──────────────────────────────────────────────────

    private void detectar() {
        if (geolocalizacionService == null) return;
        btnDetectar.setDisable(true);
        mostrar(cajaConfirmar, false);
        lblEstado.setText("Estimando tu ubicación por la conexión a internet…");
        Thread t = new Thread(() -> {
            GeolocalizacionIpService.Ubicacion u = null;
            String error = null;
            try {
                u = geolocalizacionService.estimar();
            } catch (IOException e) {
                error = e.getMessage();
            }
            GeolocalizacionIpService.Ubicacion ubicacion = u;
            String mensaje = error;
            Platform.runLater(() -> {
                btnDetectar.setDisable(false);
                if (ubicacion == null) {
                    lblEstado.setText("No se pudo detectar (¿sin internet?). Escribe tu ciudad y pulsa Buscar. Detalle: " + mensaje);
                    return;
                }
                estimada = ubicacion;
                lblConfirmar.setText("Parece que estás en " + ubicacion.etiqueta() + ". ¿Es correcto?");
                mostrar(cajaConfirmar, true);
                lblEstado.setText("Si no es tu ciudad, escríbela abajo y pulsa Buscar.");
            });
        }, "ubicacion-detectar");
        t.setDaemon(true);
        t.start();
    }

    private void confirmarEstimada() {
        if (estimada == null) return;
        aplicar(estimada.nombreParaGuardar(), estimada.latitud(), estimada.longitud());
        mostrar(cajaConfirmar, false);
        lblEstado.setText("Ubicación elegida: " + estimada.etiqueta() + ".");
    }

    // ── Buscar por nombre ────────────────────────────────────────────────

    private void buscar() {
        if (ubicacionService == null) return;
        String texto = txtCiudad.getText();
        if (texto == null || texto.trim().length() < 2) {
            lblEstado.setText("Escribe al menos dos letras de la ciudad.");
            txtCiudad.requestFocus();
            return;
        }
        btnBuscar.setDisable(true);
        mostrar(cajaConfirmar, false);
        lblEstado.setText("Buscando…");
        Thread t = new Thread(() -> {
            List<UbicacionService.Lugar> lugares;
            String aviso = null;
            try {
                lugares = ubicacionService.buscar(texto);
            } catch (IOException e) {
                lugares = List.of();
                aviso = "Sin conexión a internet. Puedes dejar la ubicación para después o capturar las coordenadas en «Opciones avanzadas».";
            } catch (NegocioException e) {
                lugares = List.of();
                aviso = e.getMessage();
            }
            List<UbicacionService.Lugar> encontrados = lugares;
            String mensaje = aviso;
            Platform.runLater(() -> {
                btnBuscar.setDisable(false);
                if (mensaje != null) {
                    lblEstado.setText(mensaje);
                    return;
                }
                cmbLugares.getItems().setAll(encontrados);
                if (encontrados.isEmpty()) {
                    mostrar(cmbLugares, false);
                    lblEstado.setText("No se encontró esa ciudad. Prueba con otro nombre (sin abreviaturas) o con el municipio.");
                    return;
                }
                mostrar(cmbLugares, true);
                lblEstado.setText(encontrados.size() == 1
                        ? "Se encontró un lugar; revisa que sea el correcto."
                        : "Elige tu ciudad en la lista (" + encontrados.size() + " coincidencias).");
                cmbLugares.getSelectionModel().select(0);
                if (encontrados.size() > 1) cmbLugares.show();
            });
        }, "ubicacion-buscar");
        t.setDaemon(true);
        t.start();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void aplicar(String ciudad, double latitud, double longitud) {
        txtCiudad.setText(ciudad == null ? "" : ciudad);
        txtLatitud.setText(String.format(Locale.ROOT, "%.4f", latitud));
        txtLongitud.setText(String.format(Locale.ROOT, "%.4f", longitud));
        refrescarActual();
    }

    private void refrescarActual() {
        String ciudad = ciudad();
        Double[] coords;
        try {
            coords = coordenadas();
        } catch (NegocioException e) {
            coords = null;
        }
        if (coords != null && coords[0] != null) {
            lblActual.setText("Ubicación del negocio: " + (ciudad.isEmpty() ? "sin nombre" : ciudad)
                    + String.format(Locale.ROOT, "  (%.2f, %.2f)", coords[0], coords[1]));
            lblActual.getStyleClass().remove("text-muted");
        } else {
            lblActual.setText(coords == null ? "Ubicación incompleta: revisa las coordenadas en «Opciones avanzadas»."
                    : "Todavía no hay ubicación. Pulsa «Detectar mi ubicación» o busca tu ciudad.");
            if (!lblActual.getStyleClass().contains("text-muted")) lblActual.getStyleClass().add("text-muted");
        }
        alCambiar.run();
    }

    private static Label etiqueta(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("field-label");
        return l;
    }

    private static void mostrar(Region nodo, boolean visible) {
        nodo.setVisible(visible);
        nodo.setManaged(visible);
    }
}
