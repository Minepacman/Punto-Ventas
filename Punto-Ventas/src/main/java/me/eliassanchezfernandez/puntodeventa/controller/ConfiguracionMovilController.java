package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.servidor.AnunciadorMdns;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import me.eliassanchezfernandez.puntodeventa.util.CodigoQr;
import java.io.File;
import java.io.IOException;
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

    /** Instrucciones para el personal; van en pantalla y en la hoja impresa */
    static final String[] PASOS_QR = {
            "1. Conecta el teléfono al Wi-Fi de la tienda.",
            "2. Abre la cámara y apunta al código.",
            "3. Toca el enlace e inicia sesión con tu usuario.",
            "Úsalo solo en el Wi-Fi del negocio (con contraseña), nunca en el de los clientes ni en redes públicas: "
                    + "la conexión no va cifrada."
    };
    private static final int QR_PANTALLA_PX = 300;   // ≈ 8 cm en pantalla: se lee a 50 cm
    private static final int QR_IMPRESION_PX = 360;  // puntos de impresión (≈ 12.7 cm a 72 dpi)

    /** URL cuyo QR se muestra cuando hay varias direcciones (se conserva al refrescar) */
    private String urlQrElegida;

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private BorderPane raiz;
    @FXML private CheckBox   chkActivo;
    @FXML private TextField  txtPuerto;
    @FXML private CheckBox   chkPuertoManual;
    @FXML private TextField  txtNombre;
    @FXML private CheckBox   chkQrLocal;
    @FXML private Label      lblNombreEstado;
    @FXML private HBox       cajaCambio;
    @FXML private Label      lblCambio;
    @FXML private Label      lblEstado;
    @FXML private VBox       cajaUrls;
    @FXML private VBox       cajaQr;
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
        chkPuertoManual.setSelected(c.isMovilPuertoManual());
        txtNombre.setText(c.getMovilNombre() == null ? "" : c.getMovilNombre());
        chkQrLocal.setSelected(c.isMovilQrLocal());
        refrescar();
    }

    @FXML
    private void onAplicar() {
        boolean manual = chkPuertoManual.isSelected();
        int puerto = 0;
        if (manual) {
            try {
                puerto = Integer.parseInt(txtPuerto.getText().trim());
            } catch (NumberFormatException e) {
                Ventanas.alerta(Alert.AlertType.ERROR, "Puerto inválido", "El puerto debe ser un número entero.");
                return;
            }
        }
        try {
            accesoMovilService.guardarOpciones(manual, txtNombre.getText(), chkQrLocal.isSelected());
            if (manual) accesoMovilService.configurar(chkActivo.isSelected(), puerto);
            else accesoMovilService.configurarAutomatico(chkActivo.isSelected());
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Acceso móvil", e.getMessage());
            chkActivo.setSelected(accesoMovilService.servidorActivo());
        }
        refrescar();
    }

    @FXML
    private void onSugerirNombre() {
        txtNombre.setText(accesoMovilService.nombreLocalSugerido());
    }

    @FXML
    private void onEntendidoCambio() {
        try {
            accesoMovilService.confirmarCambioDireccion();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Acceso móvil", e.getMessage());
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

    private int intentosNombre;

    private void refrescar() {
        Configuracion cfg = accesoMovilService.obtenerConfiguracion();
        Integer anterior = cfg.getMovilPuertoAnterior();
        boolean hayCambio = anterior != null;
        cajaCambio.setVisible(hayCambio);
        cajaCambio.setManaged(hayCambio);
        if (hayCambio) {
            lblCambio.setText("La dirección cambió: antes era el puerto " + anterior + " y ahora es el "
                    + cfg.getMovilPuerto() + ". Los códigos QR impresos con la dirección anterior ya no sirven: imprime uno nuevo.");
        }
        boolean activo = accesoMovilService.servidorActivo();
        int puerto = activo ? accesoMovilService.puertoEnUso() : cfg.getMovilPuerto();
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
        List<String> urlsQr = urls;
        AccesoMovilService.NombreLocalInfo nombre = accesoMovilService.nombreLocal();
        String nombreCfg = cfg.getMovilNombre();
        if (activo && nombreCfg != null) {
            switch (nombre.estado()) {
                case ACTIVO -> {
                    String local = nombre.host() + ":" + puerto;
                    lblNombreEstado.setText("También puedes escribir: " + local);
                    cajaUrls.getChildren().add(hint("También puedes escribir: " + local
                            + " (si tu teléfono lo reconoce; la dirección con números siempre funciona)"));
                    if (cfg.isMovilQrLocal()) urlsQr = List.of("http://" + local + "/");
                }
                case CONFLICTO -> lblNombreEstado.setText("Ya hay otro equipo en la red con el nombre \"" + nombreCfg
                        + ".local\"; se usó \"" + nombre.host() + "\". Elige otro nombre.");
                case NO_DISPONIBLE -> lblNombreEstado.setText("Nombre local no disponible en esta red. La dirección con números sigue funcionando.");
                default -> lblNombreEstado.setText("Publicando el nombre local...");
            }
            if (nombre.estado() == AnunciadorMdns.Estado.INICIANDO && intentosNombre++ < 10) {
                PauseTransition espera = new PauseTransition(Duration.seconds(1.5));
                espera.setOnFinished(e -> { if (raiz.getScene() != null) refrescar(); });
                espera.play();
            } else if (nombre.estado() != AnunciadorMdns.Estado.INICIANDO) {
                intentosNombre = 0;
            }
        } else {
            lblNombreEstado.setText(nombreCfg != null ? "El nombre local se publica cuando el servidor está encendido." : "");
        }
        pintarQr(activo, urlsQr);
        try {
            tablaSesiones.setItems(FXCollections.observableArrayList(accesoMovilService.sesionesActivas()));
        } catch (NegocioException e) {
            tablaSesiones.setItems(FXCollections.observableArrayList());
        }
    }

    // ── Código QR ─────────────────────────────────────────────────────────

    private void pintarQr(boolean activo, List<String> urls) {
        cajaQr.getChildren().clear();
        if (!activo || urls.isEmpty()) {
            Label aviso = new Label(activo
                    ? "No hay una dirección de red para mostrar el código"
                    : "Activa el acceso móvil para ver el código");
            aviso.getStyleClass().add("hint");
            aviso.setWrapText(true);
            aviso.setAlignment(Pos.CENTER);
            aviso.setMinSize(QR_PANTALLA_PX, QR_PANTALLA_PX);
            aviso.setPrefSize(QR_PANTALLA_PX, QR_PANTALLA_PX);
            aviso.setStyle("-fx-border-color: #bbbbbb; -fx-border-style: dashed; -fx-padding: 16;");
            cajaQr.getChildren().add(aviso);
            Button imprimir = new Button("Imprimir QR");
            Button guardar = new Button("Guardar imagen");
            imprimir.setDisable(true);
            guardar.setDisable(true);
            cajaQr.getChildren().add(new HBox(8, imprimir, guardar));
            cajaQr.setDisable(true);
            return;
        }
        cajaQr.setDisable(false);
        String url = urls.contains(urlQrElegida) ? urlQrElegida : urls.get(0);
        urlQrElegida = url;

        if (urls.size() > 1) {
            ComboBox<String> selector = new ComboBox<>(FXCollections.observableArrayList(urls));
            selector.setValue(url);
            selector.setOnAction(e -> {
                urlQrElegida = selector.getValue();
                pintarQr(true, urls);
            });
            Label ayuda = new Label("Hay varias redes. Elige la del Wi-Fi de la tienda:");
            ayuda.getStyleClass().add("hint");
            cajaQr.getChildren().addAll(ayuda, selector);
        }

        ImageView vista = new ImageView(CodigoQr.imagen(url, CodigoQr.escalaPara(url, QR_PANTALLA_PX * 2)));
        vista.setFitWidth(QR_PANTALLA_PX);
        vista.setFitHeight(QR_PANTALLA_PX);
        vista.setSmooth(false);
        vista.setPreserveRatio(true);
        Label etiquetaUrl = new Label(url);
        etiquetaUrl.setFont(Font.font(null, FontWeight.BOLD, 16));
        VBox pasos = new VBox(2);
        for (String paso : PASOS_QR) {
            Label l = new Label(paso);
            l.setWrapText(true);
            pasos.getChildren().add(l);
        }
        Button imprimir = new Button("Imprimir QR");
        imprimir.setOnAction(e -> onImprimirQr(url));
        Button guardar = new Button("Guardar imagen");
        guardar.setOnAction(e -> onGuardarQr(url));
        cajaQr.getChildren().addAll(vista, etiquetaUrl, pasos, new HBox(8, imprimir, guardar));
    }

    /** Hoja de una página: título, QR grande, dirección e instrucciones */
    private static VBox hojaQr(String url) {
        VBox hoja = new VBox(14);
        hoja.setAlignment(Pos.TOP_CENTER);
        hoja.setPrefWidth(460);
        hoja.setMaxWidth(460);
        hoja.setStyle("-fx-padding: 24; -fx-background-color: white;");
        Label titulo = new Label("Acceso móvil");
        titulo.setFont(Font.font(null, FontWeight.BOLD, 28));
        ImageView qr = new ImageView(CodigoQr.imagen(url, CodigoQr.escalaPara(url, QR_IMPRESION_PX * 3)));
        qr.setFitWidth(QR_IMPRESION_PX);
        qr.setFitHeight(QR_IMPRESION_PX);
        qr.setPreserveRatio(true);
        qr.setSmooth(false);
        Label direccion = new Label(url);
        direccion.setFont(Font.font(null, FontWeight.BOLD, 16));
        direccion.setWrapText(true);
        direccion.setTextAlignment(TextAlignment.CENTER);
        hoja.getChildren().addAll(titulo, qr, direccion);
        for (String paso : PASOS_QR) {
            Label l = new Label(paso);
            l.setFont(Font.font(null, 15));
            l.setWrapText(true);
            l.setMaxWidth(412);
            hoja.getChildren().add(l);
        }
        return hoja;
    }

    private void onImprimirQr(String url) {
        PrinterJob trabajo = PrinterJob.createPrinterJob();
        if (trabajo == null) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Imprimir QR", "No se encontró una impresora disponible.");
            return;
        }
        try {
            if (!trabajo.showPrintDialog(raiz.getScene().getWindow())) return;
            VBox hoja = hojaQr(url);
            new Scene(hoja);                 // da estilo y distribución al nodo fuera de pantalla
            hoja.applyCss();
            hoja.layout();
            PageLayout pagina = trabajo.getJobSettings().getPageLayout();
            double ancho = Math.max(1, hoja.prefWidth(-1));
            double alto = Math.max(1, hoja.prefHeight(ancho));
            hoja.resize(ancho, alto);
            double escala = Math.min(1.0, Math.min(pagina.getPrintableWidth() / ancho, pagina.getPrintableHeight() / alto));
            hoja.getTransforms().add(new Scale(escala, escala));
            if (trabajo.printPage(hoja)) trabajo.endJob();
            else Ventanas.alerta(Alert.AlertType.ERROR, "Imprimir QR", "No se pudo imprimir la hoja.");
        } catch (RuntimeException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Imprimir QR", "No se pudo imprimir: " + e.getMessage());
        }
    }

    private void onGuardarQr(String url) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar código QR");
        fc.setInitialFileName("acceso-movil-qr.png");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagen PNG", "*.png"));
        File destino = fc.showSaveDialog(raiz.getScene().getWindow());
        if (destino == null) return;
        if (!destino.getName().toLowerCase().endsWith(".png")) destino = new File(destino.getPath() + ".png");
        try {
            CodigoQr.guardarPng(url, Math.max(1, 1000 / CodigoQr.matriz(url).length), destino);
        } catch (IOException | RuntimeException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Guardar imagen", "No se pudo guardar la imagen: " + e.getMessage());
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
