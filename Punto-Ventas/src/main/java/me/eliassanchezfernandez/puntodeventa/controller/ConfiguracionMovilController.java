package me.eliassanchezfernandez.puntodeventa.controller;

import atlantafx.base.controls.ToggleSwitch;
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
import me.eliassanchezfernandez.puntodeventa.util.Iconos;
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
 * Configuración → Acceso móvil (solo ADMIN). En este orden: el código QR grande
 * con la dirección, el interruptor "Permitir acceso desde teléfonos" (enciende o
 * apaga el servidor EN VIVO, sin botón Aplicar), los teléfonos conectados (con
 * "Cerrar") y, plegadas, las opciones avanzadas (nombre .local, puerto fijo,
 * todas las direcciones), que sí se guardan con su propio botón Aplicar.
 */
@Component
@Scope("prototype")
public class ConfiguracionMovilController implements Initializable {

    /** Una sola línea en pantalla, bajo el QR */
    static final String INSTRUCCION_QR = "Escanéalo con la cámara del teléfono conectado al Wi-Fi de la tienda.";
    /** Instrucciones para el personal en la hoja impresa */
    static final String[] PASOS_QR = {
            "1. Conecta el teléfono al Wi-Fi de la tienda.",
            "2. Abre la cámara y apunta al código.",
            "3. Toca el enlace e inicia sesión con tu usuario.",
            "Úsalo solo en el Wi-Fi del negocio (con contraseña), nunca en el de los clientes ni en redes públicas: "
                    + "la conexión no va cifrada."
    };
    private static final int QR_PANTALLA_PX = 210;   // ≈ 5.5 cm en pantalla: se lee a 40 cm y deja ver el interruptor sin desplazar a 1024×768
    private static final int QR_IMPRESION_PX = 360;  // puntos de impresión (≈ 12.7 cm a 72 dpi)

    /** URL cuyo QR se muestra cuando hay varias direcciones (se conserva al refrescar) */
    private String urlQrElegida;

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private BorderPane raiz;
    @FXML private ToggleSwitch swActivo;
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
    @FXML private VBox       cajaQrCertificado;
    @FXML private Label      lblHuella;
    @FXML private Label      lblHuellaCompleta;
    @FXML private Label      lblHttpsEstado;
    @FXML private CheckBox   chkHttpsManual;
    @FXML private TextField  txtPuertoHttps;
    @FXML private TableView<AccesoMovilService.TelefonoPush> tablaPush;
    @FXML private TableColumn<AccesoMovilService.TelefonoPush, String> colPushUsuario;
    @FXML private TableColumn<AccesoMovilService.TelefonoPush, String> colPushDispositivo;
    @FXML private TableColumn<AccesoMovilService.TelefonoPush, String> colPushEntrega;
    @FXML private TableColumn<AccesoMovilService.TelefonoPush, String> colPushEstado;
    @FXML private TableColumn<AccesoMovilService.TelefonoPush, AccesoMovilService.TelefonoPush> colPushAccion;
    private static final int QR_CERTIFICADO_PX = 150;
    /** URL de la guía del certificado elegida cuando hay varias redes */
    private String urlCertificadoElegida;
    @FXML private TableView<SesionInfo> tablaSesiones;
    @FXML private TableColumn<SesionInfo, String> colUsuario;
    @FXML private TableColumn<SesionInfo, String> colCreada;
    @FXML private TableColumn<SesionInfo, String> colExpira;
    @FXML private TableColumn<SesionInfo, String> colUso;
    @FXML private TableColumn<SesionInfo, String> colIp;
    @FXML private TableColumn<SesionInfo, SesionInfo> colAccion;
    @FXML private TableColumn<SesionInfo, SesionInfo> colAvisos;

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
        colAvisos.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        colAvisos.setCellFactory(col -> new TableCell<>() {
            private final Label estado = new Label();
            private final Button prueba = new Button("Enviar prueba");
            private final HBox caja = new HBox(8, estado, prueba);
            { caja.setAlignment(Pos.CENTER_LEFT); prueba.getStyleClass().add("small"); }
            @Override protected void updateItem(SesionInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                estado.setText(AccesoMovilService.estadoAvisos(item.avisos()));
                estado.getStyleClass().removeAll("text-success", "hint");
                estado.getStyleClass().add(item.avisos() != null && item.avisos().isActiva() ? "text-success" : "hint");
                prueba.setVisible(item.avisos() != null);
                prueba.setManaged(item.avisos() != null);
                prueba.setOnAction(e -> onEnviarPrueba(item, prueba));
                setGraphic(caja);
            }
        });
        tablaSesiones.setPlaceholder(new Label("No hay sesiones móviles abiertas."));

        colPushUsuario.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().usuario()));
        colPushDispositivo.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().dispositivo()));
        colPushEntrega.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().suscripcion().getUltimoOk() == null ? "Ninguna aún" : local(c.getValue().suscripcion().getUltimoOk())));
        colPushEstado.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(estadoPush(c.getValue().suscripcion())));
        colPushAccion.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        colPushAccion.setCellFactory(col -> new TableCell<>() {
            private final Button quitar = new Button("Quitar");
            @Override protected void updateItem(AccesoMovilService.TelefonoPush item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                quitar.setOnAction(e -> onQuitarTelefono(item));
                setGraphic(quitar);
            }
        });
        tablaPush.setPlaceholder(new Label("Ningún teléfono tiene notificaciones activadas."));

        Configuracion c = accesoMovilService.obtenerConfiguracion();
        swActivo.setSelected(accesoMovilService.servidorActivo() || c.isMovilActivo());
        txtPuerto.setText(String.valueOf(c.getMovilPuerto()));
        chkPuertoManual.setSelected(c.isMovilPuertoManual());
        chkHttpsManual.setSelected(c.isMovilPuertoHttpsManual());
        txtPuertoHttps.setText(c.getMovilPuertoHttps() == null ? String.valueOf(c.getMovilPuerto() + 1) : String.valueOf(c.getMovilPuertoHttps()));
        txtNombre.setText(c.getMovilNombre() == null ? "" : c.getMovilNombre());
        chkQrLocal.setSelected(c.isMovilQrLocal());
        // El interruptor aplica al momento (sin botón): un cambio hecho desde el código no vuelve a aplicar
        swActivo.selectedProperty().addListener((obs, antes, ahora) -> { if (!ajustandoInterruptor) onAplicar(); });
        refrescar();
    }

    /** true mientras el código mueve el interruptor (p. ej. al fallar el encendido) */
    private boolean ajustandoInterruptor;

    /** Enciende/apaga según el interruptor y guarda las opciones avanzadas tal como están */
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
        Integer puertoHttps = null;
        if (chkHttpsManual.isSelected()) {
            try {
                puertoHttps = Integer.parseInt(txtPuertoHttps.getText().trim());
            } catch (NumberFormatException e) {
                Ventanas.alerta(Alert.AlertType.ERROR, "Puerto inválido", "El puerto HTTPS debe ser un número entero.");
                return;
            }
        }
        try {
            accesoMovilService.guardarOpciones(manual, txtNombre.getText(), chkQrLocal.isSelected());
            accesoMovilService.guardarPuertoHttps(puertoHttps);
            if (manual) accesoMovilService.configurar(swActivo.isSelected(), puerto);
            else accesoMovilService.configurarAutomatico(swActivo.isSelected());
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Acceso móvil", e.getMessage());
        }
        ajustandoInterruptor = true;
        try {
            swActivo.setSelected(accesoMovilService.servidorActivo());
        } finally {
            ajustandoInterruptor = false;
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
    private void onRegenerarCertificado() {
        if (!Ventanas.confirmar("Regenerar certificado",
                "Se creará un certificado nuevo para la conexión segura. TODOS los teléfonos que ya lo instalaron tendrán que "
                + "instalar el nuevo (volver a escanear el código de «Notificaciones en el teléfono») y, mientras no lo hagan, "
                + "no recibirán notificaciones. La dirección normal (sin candado) sigue funcionando. ¿Continuar?")) return;
        try {
            accesoMovilService.regenerarCertificado();
        } catch (NegocioException | IllegalStateException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Regenerar certificado", e.getMessage());
        }
        refrescar();
    }

    private void onQuitarTelefono(AccesoMovilService.TelefonoPush t) {
        if (!Ventanas.confirmar("Quitar notificaciones", "El teléfono de " + t.usuario() + " dejará de recibir notificaciones. ¿Continuar?")) return;
        try {
            accesoMovilService.quitarTelefono(t.suscripcion().getId());
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Acceso móvil", e.getMessage());
        }
        refrescar();
    }

    /** Aviso de prueba a ese teléfono; sale a internet, así que corre fuera del hilo de la pantalla */
    private void onEnviarPrueba(SesionInfo info, Button boton) {
        if (info.avisos() == null) return;
        Long id = info.avisos().getId();
        boton.setDisable(true);
        boton.setText("Enviando…");
        Thread t = new Thread(() -> {
            String titulo = "Aviso de prueba";
            String mensaje;
            Alert.AlertType tipo;
            try {
                var r = accesoMovilService.enviarPrueba(id);
                tipo = r.ok() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING;
                mensaje = r.ok() ? "Enviado al teléfono de " + info.usuario() + ". Debe aparecer en unos segundos."
                        : "No se pudo enviar: " + r.detalle() + "\n\nRevisa que esta computadora tenga internet.";
            } catch (RuntimeException e) {
                tipo = Alert.AlertType.ERROR;
                mensaje = e.getMessage();
            }
            Alert.AlertType tipoFinal = tipo;
            String mensajeFinal = mensaje;
            javafx.application.Platform.runLater(() -> {
                boton.setDisable(false);
                boton.setText("Enviar prueba");
                Ventanas.alerta(tipoFinal, titulo, mensajeFinal);
                if (raiz.getScene() != null) refrescar();
            });
        }, "aviso-prueba-telefono");
        t.setDaemon(true);
        t.start();
    }

    private static String estadoPush(me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush s) {
        if (!s.isActiva()) return "En pausa (lo apagó el usuario)";
        if (s.getUltimoError() != null && (s.getUltimoOk() == null || s.getUltimoError().isAfter(s.getUltimoOk()))) {
            return "Último envío falló" + (s.getErrorDetalle() == null ? "" : ": " + s.getErrorDetalle());
        }
        return "Activas";
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
        lblEstado.setText(activo ? "Encendido: los teléfonos ya pueden entrar (puerto " + puerto + ")."
                : "Apagado: ningún teléfono puede entrar.");
        lblEstado.getStyleClass().removeAll("text-success", "hint");
        lblEstado.getStyleClass().add(activo ? "text-success" : "hint");

        cajaUrls.getChildren().clear();
        List<String> urls = accesoMovilService.urlsLan(puerto);
        if (!activo) {
            cajaUrls.getChildren().add(hint("Enciende «Permitir acceso desde teléfonos» para ver las direcciones."));
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
        pintarCertificado(activo, puerto);
        try {
            tablaSesiones.setItems(FXCollections.observableArrayList(accesoMovilService.sesionesActivas()));
        } catch (NegocioException e) {
            tablaSesiones.setItems(FXCollections.observableArrayList());
        }
        try {
            tablaPush.setItems(FXCollections.observableArrayList(accesoMovilService.telefonosConNotificaciones()));
        } catch (NegocioException e) {
            tablaPush.setItems(FXCollections.observableArrayList());
        }
    }

    // ── Certificado y notificaciones ──────────────────────────────────────

    private void pintarCertificado(boolean activo, int puertoHttp) {
        cajaQrCertificado.getChildren().clear();
        try {
            lblHuella.setText(accesoMovilService.huellaCorta());
            lblHuellaCompleta.setText("SHA-256 completa: " + accesoMovilService.huellaCompleta());
        } catch (RuntimeException e) {
            lblHuella.setText("");
            lblHuellaCompleta.setText("No se pudo leer el certificado: " + e.getMessage());
        }
        if (!activo) {
            lblHttpsEstado.setText("Enciende «Permitir acceso desde teléfonos» para ver el código.");
            cajaQrCertificado.getChildren().add(hintCentrado("Acceso apagado", QR_CERTIFICADO_PX));
            return;
        }
        boolean https = accesoMovilService.httpsActivo();
        lblHttpsEstado.setText(https
                ? "Listo para activar avisos (conexión segura en el puerto " + accesoMovilService.puertoHttps() + ")."
                : "Los avisos no están disponibles: la conexión segura no pudo abrirse (puerto ocupado). Revisa el puerto HTTPS en Opciones avanzadas.");
        lblHttpsEstado.getStyleClass().removeAll("text-success", "hint");
        lblHttpsEstado.getStyleClass().add(https ? "text-success" : "hint");
        List<String> urls = accesoMovilService.urlsAvisos(puertoHttp);
        if (urls.isEmpty()) {
            cajaQrCertificado.getChildren().add(hintCentrado("Sin red local", QR_CERTIFICADO_PX));
            return;
        }
        String url = urls.contains(urlCertificadoElegida) ? urlCertificadoElegida : urls.get(0);
        urlCertificadoElegida = url;
        if (urls.size() > 1) {
            ComboBox<String> selector = new ComboBox<>(FXCollections.observableArrayList(urls));
            selector.setValue(url);
            selector.setOnAction(e -> { urlCertificadoElegida = selector.getValue(); pintarCertificado(true, puertoHttp); });
            cajaQrCertificado.getChildren().add(selector);
        }
        ImageView vista = new ImageView(CodigoQr.imagen(url, CodigoQr.escalaPara(url, QR_CERTIFICADO_PX * 2)));
        vista.setFitWidth(QR_CERTIFICADO_PX);
        vista.setFitHeight(QR_CERTIFICADO_PX);
        vista.setSmooth(false);
        vista.setPreserveRatio(true);
        VBox marco = new VBox(vista);
        marco.getStyleClass().add("qr-marco");
        marco.setMaxWidth(QR_CERTIFICADO_PX + 24);
        Label etiqueta = new Label(url);
        etiqueta.getStyleClass().add("qr-url");
        etiqueta.setWrapText(true);
        etiqueta.setMaxWidth(QR_CERTIFICADO_PX + 60);
        Button copiar = new Button("Copiar", Iconos.de("portapapeles"));
        copiar.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(url);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        cajaQrCertificado.getChildren().addAll(marco, etiqueta, copiar);
    }

    private static Label hintCentrado(String texto, int ancho) {
        Label l = hint(texto);
        l.getStyleClass().add("qr-marco-vacio");
        l.setTextAlignment(TextAlignment.CENTER);
        l.setAlignment(Pos.CENTER);
        l.setMinSize(ancho, ancho * 0.5);
        l.setPrefSize(ancho, ancho * 0.5);
        l.setMaxWidth(ancho);
        return l;
    }

    // ── Código QR ─────────────────────────────────────────────────────────

    private void pintarQr(boolean activo, List<String> urls) {
        cajaQr.getChildren().clear();
        if (!activo || urls.isEmpty()) {
            Label aviso = new Label(activo
                    ? "No se encontró una red local. Conecta esta computadora al Wi-Fi de la tienda."
                    : "Enciende «Permitir acceso desde teléfonos» (abajo) para ver el código.");
            aviso.getStyleClass().addAll("hint", "qr-marco-vacio");
            aviso.setWrapText(true);
            aviso.setTextAlignment(TextAlignment.CENTER);
            aviso.setAlignment(Pos.CENTER);
            aviso.setMinSize(QR_PANTALLA_PX, QR_PANTALLA_PX * 0.6);
            aviso.setPrefSize(QR_PANTALLA_PX, QR_PANTALLA_PX * 0.6);
            aviso.setMaxWidth(QR_PANTALLA_PX);
            cajaQr.getChildren().add(aviso);
            return;
        }
        String url = urls.contains(urlQrElegida) ? urlQrElegida : urls.get(0);
        urlQrElegida = url;

        if (urls.size() > 1) {
            ComboBox<String> selector = new ComboBox<>(FXCollections.observableArrayList(urls));
            selector.setValue(url);
            selector.setOnAction(e -> {
                urlQrElegida = selector.getValue();
                pintarQr(true, urls);
            });
            Label ayuda = new Label("Esta computadora tiene varias redes. Elige la del Wi-Fi de la tienda:");
            ayuda.getStyleClass().add("hint");
            HBox fila = new HBox(8, ayuda, selector);
            fila.setAlignment(Pos.CENTER);
            cajaQr.getChildren().add(fila);
        }

        ImageView vista = new ImageView(CodigoQr.imagen(url, CodigoQr.escalaPara(url, QR_PANTALLA_PX * 2)));
        vista.setFitWidth(QR_PANTALLA_PX);
        vista.setFitHeight(QR_PANTALLA_PX);
        vista.setSmooth(false);
        vista.setPreserveRatio(true);
        VBox marco = new VBox(vista);
        marco.getStyleClass().add("qr-marco");
        marco.setMaxWidth(QR_PANTALLA_PX + 24);
        Label etiquetaUrl = new Label(url);
        etiquetaUrl.getStyleClass().add("qr-url");
        Label instruccion = new Label(INSTRUCCION_QR);
        instruccion.getStyleClass().add("hint");
        instruccion.setWrapText(true);
        instruccion.setTextAlignment(TextAlignment.CENTER);
        Button copiar = new Button("Copiar dirección", Iconos.de("portapapeles"));
        copiar.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(url);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        Button imprimir = new Button("Imprimir QR", Iconos.de("impresora"));
        imprimir.setOnAction(e -> onImprimirQr(url));
        Button guardar = new Button("Guardar imagen", Iconos.de("imagen"));
        guardar.setOnAction(e -> onGuardarQr(url));
        HBox botones = new HBox(8, imprimir, guardar, copiar);
        botones.setAlignment(Pos.CENTER);
        cajaQr.getChildren().addAll(marco, etiquetaUrl, instruccion, botones);
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
