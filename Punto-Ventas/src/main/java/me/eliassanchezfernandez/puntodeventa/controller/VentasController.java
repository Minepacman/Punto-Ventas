package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.Node;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.bascula.BasculaService;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.ClimaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.DispositivosService;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.RecomendacionService;
import me.eliassanchezfernandez.puntodeventa.service.RecomendacionService.Sugerencia;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.StockInsuficienteException;
import me.eliassanchezfernandez.puntodeventa.service.TicketPendienteService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import me.eliassanchezfernandez.puntodeventa.terminal.DatosTarjeta;
import me.eliassanchezfernandez.puntodeventa.util.Atajo;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.EntradaCodigo;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Controlador de la pantalla de Ventas (F1).
 *
 * Flujo:
 *  1. Se escanea/escribe un código (o se busca por nombre) → renglón en el ticket
 *  2. Se ajustan cantidades, mayoreo o descuento manual (con permiso o
 *     autorización de administrador)
 *  3. Se captura el pago (una o varias formas de pago habilitadas en
 *     Configuración) → cambio en vivo; o se abre la ventana de cobro (F12)
 *  4. Cobrar (botón, F12 o Enter en un pago) → VentaService.cobrar() guarda la
 *     venta y descuenta stock; se imprime, se abre el cajón y se ofrece facturar
 *
 * Teclado: todos los atajos están en el catálogo util/Atajo. Los que caen sobre
 * teclas F o combinaciones con Ctrl/Cmd llegan como aceleradores desde
 * MainController (ejecutarAtajo); los que compiten con la escritura (Supr, +, −,
 * Esc, flechas, Enter en el ticket) se atienden aquí con filtros según dónde
 * esté el cursor. Los diálogos modales tienen su propia escena, así que ningún
 * atajo de la ventana principal se dispara mientras uno está abierto.
 *
 * Al cerrar sesión el ticket sin cobrar se guarda y se restaura cuando el
 * mismo cajero vuelve a entrar. Las reglas viven en VentaService; aquí solo UI.
 *
 * Sugerencias: bajo el ticket hay una tira con 3–5 productos que
 * RecomendacionService propone para el carrito actual (se compran juntos,
 * día y hora, clima). Se calcula fuera del hilo de JavaFX con un pequeño
 * retardo cada vez que cambia el ticket; un fallo solo deja la tira vacía.
 */
@Component
@Scope("prototype")
public class VentasController implements Initializable {

    // ── FXML ─────────────────────────────────────────────────────────────
    @FXML private BorderPane raiz;
    @FXML private TextField  txtCodigo;
    @FXML private TextField  txtEfectivo;
    @FXML private TextField  txtTarjeta;
    @FXML private TextField  txtTransferencia;
    @FXML private TextField  txtVales;
    @FXML private Region     boxEfectivo;
    @FXML private Region     boxTarjeta;
    @FXML private Region     boxTransferencia;
    @FXML private Region     boxVales;
    @FXML private Button     btnTodoEfectivo;
    @FXML private Button     btnTodoTarjeta;
    @FXML private Button     btnTodoTransferencia;
    @FXML private Button     btnTodoVales;
    @FXML private Button     btnBuscar;
    @FXML private Button     btnCantidad;
    @FXML private Button     btnQuitar;
    @FXML private Button     btnDescuento;
    @FXML private Button     btnMayoreo;
    @FXML private Button     btnLeerPeso;
    @FXML private Button     btnAbrirCajon;
    @FXML private Button     btnEnEspera;
    @FXML private Button     btnRecuperar;
    @FXML private Button     btnReimprimir;
    @FXML private Button     btnCobrar;
    @FXML private Button     btnCancelar;
    @FXML private TableView<DetalleVenta> tablaProductos;
    @FXML private TableColumn<DetalleVenta, String>  colCodigo;
    @FXML private TableColumn<DetalleVenta, String>  colDescripcion;
    @FXML private TableColumn<DetalleVenta, Double>  colPrecio;
    @FXML private TableColumn<DetalleVenta, String>  colCantidad;
    @FXML private TableColumn<DetalleVenta, Double>  colDescuento;
    @FXML private TableColumn<DetalleVenta, String>  colTipoDescuento;
    @FXML private TableColumn<DetalleVenta, Double>  colImporte;
    @FXML private TableColumn<DetalleVenta, Double>  colExistencia;
    @FXML private Label labelTotal;
    @FXML private Label labelPagado;
    @FXML private Label labelCambioTitulo;
    @FXML private Label labelCambio;
    @FXML private Label labelNumProductos;
    @FXML private Label labelEnEspera;
    @FXML private Label labelTotalPagado;
    @FXML private VBox     boxSugerencias;
    @FXML private Label    lblSugerenciasEstado;
    @FXML private Button   btnOcultarSugerencias;
    @FXML private FlowPane contenedorSugerencias;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private VentaService           ventaService;
    @Autowired private ProductoService        productoService;
    @Autowired private SesionService          sesionService;
    @Autowired private AutorizacionService    autorizacionService;
    @Autowired private TicketPendienteService ticketPendienteService;
    @Autowired private ConfiguracionService   configuracionService;
    @Autowired private RecomendacionService   recomendacionService;
    @Autowired private ClimaService           climaService;
    @Autowired private ImpresionService       impresionService;
    @Autowired private BasculaService         basculaService;
    @Autowired private DispositivosService    dispositivosService;
    @Autowired private DispositivosDialogos   dispositivosDialogos;
    @Autowired private FacturacionDialogos    facturacionDialogos;

    private static final java.time.format.DateTimeFormatter FMT_HORA = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    /** Sugerencias: retardo tras el último cambio del ticket y hilo de cálculo */
    private static final Duration RETARDO_SUGERENCIAS = Duration.millis(400);
    private final PauseTransition esperaSugerencias = new PauseTransition(RETARDO_SUGERENCIAS);
    private final ExecutorService hiloSugerencias = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sugerencias-venta");
        t.setDaemon(true);
        return t;
    });
    /** Número de la última petición: los resultados viejos se descartan */
    private final AtomicInteger generacionSugerencias = new AtomicInteger();
    /** La tira está plegada por el botón (solo durante la sesión) */
    private boolean sugerenciasPlegadas = false;

    /** Campo de captura de cada forma de pago (en orden de pantalla) */
    private final Map<Venta.FormaPago, TextField> camposPago = new LinkedHashMap<>();

    private final ObservableList<DetalleVenta> itemsTicket =
            FXCollections.observableArrayList();

    /** Últimos datos de voucher capturados para este ticket (si el cobro se repite no se vuelven a teclear) */
    private DatosTarjeta tarjetaCapturada;

    /** Autorizaciones de administrador obtenidas para ESTE ticket (se agotan al cobrar) */
    private final List<Autorizacion> autorizaciones = new ArrayList<>();

    // ── Inicialización ────────────────────────────────────────────────────
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        camposPago.put(Venta.FormaPago.EFECTIVO, txtEfectivo);
        camposPago.put(Venta.FormaPago.TARJETA, txtTarjeta);
        camposPago.put(Venta.FormaPago.TRANSFERENCIA, txtTransferencia);
        camposPago.put(Venta.FormaPago.VALES, txtVales);
        configurarColumnas();
        aplicarConfiguracion();
        tablaProductos.setItems(itemsTicket);
        tablaProductos.setPlaceholder(new Label(
                "Escanea un código o usa 🔍 Buscar para agregar productos."));

        // Los montos se recalculan al cambiar el texto (también al pegar con el ratón)
        camposPago.values().forEach(c -> c.textProperty().addListener((o, a, b) -> actualizarCambio()));
        configurarTeclado();
        etiquetarBotones();

        configurarSugerencias();
        restaurarTicketPendiente();
        actualizarTotales();
        actualizarVendidoHoy();
        actualizarEnEspera();
        programarSugerencias();
        Platform.runLater(() -> txtCodigo.requestFocus());
    }

    // ── Teclado ───────────────────────────────────────────────────────────

    /** Texto de cada botón con su tecla (Cmd en Mac) y tooltip con la alternativa */
    private void etiquetarBotones() {
        boolean mac = Atajo.esMac();
        etiquetar(btnBuscar,     "🔍 Buscar",     Atajo.BUSCAR_PRODUCTO, mac);
        etiquetar(btnCantidad,   "Cantidad",      Atajo.CANTIDAD, mac);
        etiquetar(btnQuitar,     "🗑 Quitar",     Atajo.QUITAR_RENGLON, mac);
        etiquetar(btnDescuento,  "Descuento",     Atajo.DESCUENTO_MANUAL, mac);
        etiquetar(btnMayoreo,    "Mayoreo",       Atajo.MAYOREO, mac);
        etiquetar(btnEnEspera,   "⏸ En espera",   Atajo.PONER_EN_ESPERA, mac);
        etiquetar(btnCobrar,     "Cobrar",        Atajo.COBRAR, mac);
        // Combinaciones con Ctrl/Cmd: la tecla va solo en el tooltip (caben en 1024 px)
        soloTooltip(btnLeerPeso,   Atajo.LEER_PESO, mac);
        soloTooltip(btnReimprimir, Atajo.REIMPRIMIR_ULTIMO, mac);
        soloTooltip(btnAbrirCajon, Atajo.ABRIR_CAJON, mac);
        soloTooltip(btnRecuperar,  Atajo.RECUPERAR_ESPERA, mac);
        soloTooltip(btnCancelar,   Atajo.CANCELAR_TICKET, mac);
    }

    private static void etiquetar(Button b, String texto, Atajo a, boolean mac) {
        b.setText(texto + "  " + a.etiquetaCorta(mac));
        soloTooltip(b, a, mac);
    }

    private static void soloTooltip(Button b, Atajo a, boolean mac) {
        b.setTooltip(new Tooltip(a.getDescripcion() + "  —  " + a.etiquetaTeclas(mac)
                + (a.getPermiso() != null ? "\nRequiere: " + a.getPermiso().getDescripcion() + " (o autorización)" : "")));
    }

    /**
     * Teclas que dependen de dónde está el cursor (no pueden ser aceleradores
     * de la escena porque un campo de texto las usa para escribir):
     *  - Esc: limpia el código o regresa el cursor al código;
     *  - ↓/↑ en el código: pasan al ticket; Enter en el ticket: cantidad;
     *  - Supr/Retroceso fuera de un campo de texto: quitar renglón;
     *  - + / − en el ticket o con el código vacío: una pieza más/menos;
     *  - Ctrl+Supr (Cmd+Supr): cancelar ticket (el campo de texto lo consumiría).
     */
    private void configurarTeclado() {
        raiz.addEventFilter(KeyEvent.KEY_PRESSED, this::teclaPulsada);
        raiz.addEventFilter(KeyEvent.KEY_TYPED, this::teclaEscrita);
    }

    private void teclaPulsada(KeyEvent e) {
        Node foco = raiz.getScene() != null ? raiz.getScene().getFocusOwner() : null;
        boolean enTexto = foco instanceof TextInputControl;
        boolean enCodigo = foco == txtCodigo;
        boolean enTabla = foco == tablaProductos;
        KeyCode c = e.getCode();

        if (c == KeyCode.ESCAPE) {
            if (enCodigo && !txtCodigo.getText().isEmpty()) {
                txtCodigo.clear();
            } else if (enTexto && foco != txtCodigo) {
                ((TextInputControl) foco).clear();
                txtCodigo.requestFocus();
            } else if (!enCodigo) {
                txtCodigo.requestFocus();
            }
            e.consume();
            return;
        }
        if (enCodigo && (c == KeyCode.DOWN || c == KeyCode.UP) && !itemsTicket.isEmpty()) {
            irAlTicket(c == KeyCode.UP);
            e.consume();
            return;
        }
        if (enTabla && c == KeyCode.ENTER) {
            onCambiarCantidad();
            e.consume();
            return;
        }
        // Ctrl/Cmd+Supr = cancelar ticket, aunque el cursor esté en un campo de texto
        if (Atajo.CANCELAR_TICKET.coincide(c, e.isShortcutDown(), e.isShiftDown(), e.isAltDown())) {
            onCancelarTicket();
            e.consume();
            return;
        }
        if (!enTexto && Atajo.QUITAR_RENGLON.coincide(c, e.isShortcutDown(), e.isShiftDown(), e.isAltDown())) {
            onQuitarLinea();
            e.consume();
        }
    }

    /** + y − se leen como carácter escrito: así valen en teclado numérico, español y en inglés (Shift+=) */
    private void teclaEscrita(KeyEvent e) {
        String ch = e.getCharacter();
        if (!"+".equals(ch) && !"-".equals(ch)) return;
        Node foco = raiz.getScene() != null ? raiz.getScene().getFocusOwner() : null;
        boolean permitido = !(foco instanceof TextInputControl) || (foco == txtCodigo && txtCodigo.getText().isEmpty());
        if (!permitido) return;
        ajustarCantidad("+".equals(ch) ? 1 : -1);
        e.consume();
    }

    private void irAlTicket(boolean alFinal) {
        if (tablaProductos.getSelectionModel().getSelectedItem() == null) {
            if (alFinal) tablaProductos.getSelectionModel().selectLast();
            else tablaProductos.getSelectionModel().selectFirst();
        }
        tablaProductos.requestFocus();
        DetalleVenta sel = tablaProductos.getSelectionModel().getSelectedItem();
        if (sel != null) tablaProductos.scrollTo(sel);
    }

    /** Una pieza más o menos al renglón seleccionado (o al último si no hay selección) */
    private void ajustarCantidad(int delta) {
        DetalleVenta d = tablaProductos.getSelectionModel().getSelectedItem();
        if (d == null && !itemsTicket.isEmpty()) {
            d = itemsTicket.get(itemsTicket.size() - 1);
            tablaProductos.getSelectionModel().select(d);
        }
        if (d == null) return;
        // A granel (p. ej. 0.5 kg) "−" no debe dejar la cantidad en cero o negativa ni quitar
        // el renglón sin querer: solo se quita con "−" un renglón de exactamente 1 pieza.
        if (d.getCantidad() + delta <= 0 && d.getCantidad() != 1) return;
        cambiarCantidad(d, d.getCantidad() + delta);
        if (itemsTicket.contains(d)) tablaProductos.getSelectionModel().select(d);
    }

    /**
     * Atajos de Ventas que llegan como aceleradores de la escena (MainController
     * los registra y los manda aquí solo cuando la pestaña Ventas está activa).
     * Cada acción aplica sus permisos como si se hubiera pulsado el botón.
     */
    public void ejecutarAtajo(Atajo atajo) {
        switch (atajo) {
            case BUSCAR_PRODUCTO   -> onBuscar();
            case CANTIDAD          -> onCambiarCantidad();
            case DESCUENTO_MANUAL  -> onDescuentoManual();
            case MAYOREO           -> onMayoreo();
            case PONER_EN_ESPERA   -> onPonerEnEspera();
            case RECUPERAR_ESPERA  -> onRecuperarEspera();
            case COBRAR            -> onCobrar();
            case REIMPRIMIR_ULTIMO -> onReimprimirUltimo();
            case CANCELAR_TICKET   -> onCancelarTicket();
            case QUITAR_RENGLON    -> onQuitarLinea();
            case MAS_UNO           -> ajustarCantidad(1);
            case MENOS_UNO         -> ajustarCantidad(-1);
            case ABRIR_CAJON -> {
                if (btnAbrirCajon.isVisible()) onAbrirCajon();
                else alerta(Alert.AlertType.INFORMATION, "Cajón de dinero",
                        "El cajón se abre con el pulso de una impresora térmica (ESC/POS). Configúrala en Configuración → Impresora de tickets.");
            }
            case LEER_PESO -> {
                if (btnLeerPeso.isVisible()) onLeerPeso();
                else alerta(Alert.AlertType.INFORMATION, "Báscula",
                        "No hay báscula activa. Configúrala en Configuración → Báscula.");
            }
            default -> { }
        }
    }


    // ── Acciones FXML ─────────────────────────────────────────────────────

    /** Enter en el código: "código" o "cantidad*código" (3*7501…, 1.5*granel) */
    @FXML
    private void onAgregarProducto() {
        String texto = txtCodigo.getText();
        if (texto.isBlank()) return;
        // Un código que de por sí parece "cantidad x código" (p. ej. la bolsa "40X60")
        // gana: si existe tal cual, se agrega una pieza de ese producto
        if (EntradaCodigo.traeCantidad(texto)) {
            Optional<Producto> talCual = ventaService.buscarPorCodigo(texto.trim());
            if (talCual.isPresent()) {
                agregarAlTicket(talCual.get(), 1);
                txtCodigo.clear();
                txtCodigo.requestFocus();
                return;
            }
        }
        Optional<EntradaCodigo.Captura> captura = EntradaCodigo.interpretar(texto);
        if (captura.isEmpty()) {
            alerta(Alert.AlertType.WARNING, "Cantidad inválida",
                    "Escribe la cantidad, un asterisco y el código: por ejemplo 3*7501055300006.");
            txtCodigo.selectAll();
            return;
        }
        String codigo = captura.get().codigo();
        Optional<Producto> encontrado = ventaService.buscarPorCodigo(codigo);
        if (encontrado.isEmpty()) {
            alerta(Alert.AlertType.WARNING, "Producto no encontrado",
                    "No existe un producto activo con el código \"" + codigo + "\".\n" +
                    "Usa 🔍 Buscar (F6) para localizarlo por nombre.");
            txtCodigo.selectAll();
            return;
        }

        agregarAlTicket(encontrado.get(), captura.get().cantidad());
        txtCodigo.clear();
        txtCodigo.requestFocus();
    }

    /** Diálogo de búsqueda por nombre o código (solo productos activos) */
    @FXML
    private void onBuscar() {
        Dialog<Producto> dialogo = new Dialog<>();
        dialogo.setTitle("Buscar producto");
        dialogo.setHeaderText("Escribe parte del nombre o del código");
        dialogo.initOwner(tablaProductos.getScene().getWindow());
        Ventanas.estilizar(dialogo);

        TextField txtFiltro = new TextField();
        txtFiltro.setPromptText("Nombre o código...");

        TableView<Producto> tabla = new TableView<>();
        tabla.setPrefSize(640, 320);
        TableColumn<Producto, String> cCod = new TableColumn<>("Código");
        cCod.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCodigoBarras()));
        cCod.setPrefWidth(130);
        TableColumn<Producto, String> cDesc = new TableColumn<>("Descripción");
        cDesc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDescripcion()));
        cDesc.setPrefWidth(300);
        TableColumn<Producto, String> cPrecio = new TableColumn<>("Precio");
        cPrecio.setCellValueFactory(c -> new SimpleStringProperty(
                Moneda.formato(c.getValue().getPrecioVenta())));
        cPrecio.getStyleClass().add("align-right");
        TableColumn<Producto, String> cStock = new TableColumn<>("Existencia");
        cStock.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isUsaInventario() ? formatoCantidad(c.getValue().getCantidadActual()) : "—"));
        cStock.getStyleClass().add("align-right");
        tabla.getColumns().add(cCod);
        tabla.getColumns().add(cDesc);
        tabla.getColumns().add(cPrecio);
        tabla.getColumns().add(cStock);
        tabla.setPlaceholder(new Label("Sin resultados"));

        Runnable filtrar = () -> tabla.getItems().setAll(productoService.buscarParaVenta(txtFiltro.getText()));
        txtFiltro.textProperty().addListener((o, a, b) -> filtrar.run());
        filtrar.run();

        VBox contenido = new VBox(8, txtFiltro, tabla);
        contenido.setPadding(new Insets(10));
        dialogo.getDialogPane().setContent(contenido);
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Doble clic en una fila = aceptar
        tabla.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && tabla.getSelectionModel().getSelectedItem() != null) {
                dialogo.setResult(tabla.getSelectionModel().getSelectedItem());
                dialogo.close();
            }
        });
        // Enter en el filtro = seleccionar el primero (o el elegido con las flechas)
        txtFiltro.setOnAction(e -> {
            if (!tabla.getItems().isEmpty()) {
                if (tabla.getSelectionModel().getSelectedItem() == null) tabla.getSelectionModel().selectFirst();
                dialogo.setResult(tabla.getSelectionModel().getSelectedItem());
                dialogo.close();
            }
        });
        // ↓ desde el filtro pasa a la lista; Enter en la lista acepta; cualquier letra regresa al filtro
        txtFiltro.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN && !tabla.getItems().isEmpty()) {
                tabla.getSelectionModel().selectFirst();
                tabla.requestFocus();
                e.consume();
            }
        });
        tabla.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && tabla.getSelectionModel().getSelectedItem() != null) {
                dialogo.setResult(tabla.getSelectionModel().getSelectedItem());
                dialogo.close();
                e.consume();
            } else if (e.getCode() == KeyCode.UP && tabla.getSelectionModel().getSelectedIndex() == 0) {
                txtFiltro.requestFocus();
                txtFiltro.end();
                e.consume();
            }
        });
        tabla.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            String ch = e.getCharacter();
            if (ch != null && ch.length() == 1 && Character.isLetterOrDigit(ch.charAt(0))) {
                txtFiltro.requestFocus();
                txtFiltro.end();
            }
        });

        dialogo.setResultConverter(btn ->
                btn == ButtonType.OK ? tabla.getSelectionModel().getSelectedItem() : null);
        Platform.runLater(txtFiltro::requestFocus);

        dialogo.showAndWait().ifPresent(p -> agregarAlTicket(p, 1));
        txtCodigo.requestFocus();
    }

    /** Alterna el renglón seleccionado entre precio de lista y precio de mayoreo (APLICAR_MAYOREO) */
    @FXML
    private void onMayoreo() {
        DetalleVenta d = seleccionado("Selecciona un renglón del ticket para aplicar mayoreo.");
        if (d == null) return;

        boolean activar = !d.isMayoreo();
        if (activar && !asegurarPermiso(Permiso.APLICAR_MAYOREO)) return;
        try {
            ventaService.aplicarMayoreo(d, activar, autorizaciones);
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "Mayoreo", e.getMessage());
        }
        recalcularTodo();
    }

    /** Descuento manual por renglón: porcentaje o importe (DESCUENTO_MANUAL) */
    @FXML
    private void onDescuentoManual() {
        DetalleVenta d = seleccionado("Selecciona un renglón del ticket para aplicarle un descuento.");
        if (d == null) return;

        Dialog<double[]> dlg = new Dialog<>();
        dlg.setTitle("Descuento manual");
        dlg.setHeaderText(d.getProducto().getDescripcion() + "  —  importe actual " + Moneda.formato(
                d.getProducto().getPrecioVenta() * d.getCantidad()));
        dlg.initOwner(tablaProductos.getScene().getWindow());
        Ventanas.estilizar(dlg);
        TextField txtPct = new TextField(d.getDescuentoManualPct() != null ? formatoCantidad(d.getDescuentoManualPct()) : "");
        txtPct.setPromptText("ej. 10");
        TextField txtImp = new TextField(d.getDescuentoManualImporte() != null ? String.format("%.2f", d.getDescuentoManualImporte()) : "");
        txtImp.setPromptText("ej. 15.00");
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Porcentaje (%):"), txtPct);
        grid.addRow(1, new Label("o importe ($):"), txtImp);
        grid.add(new Label("Deja ambos vacíos para quitar el descuento."), 0, 2, 2, 1);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            return new double[] { parsearODefault(txtPct.getText(), 0), parsearODefault(txtImp.getText(), 0) };
        });
        Platform.runLater(txtPct::requestFocus);

        Optional<double[]> res = dlg.showAndWait();
        if (res.isEmpty()) return;
        double pct = res.get()[0], imp = res.get()[1];
        if (Double.isNaN(pct) || Double.isNaN(imp)) {
            alerta(Alert.AlertType.WARNING, "Descuento inválido", "Escribe un número válido.");
            return;
        }
        boolean quitar = pct <= 0 && imp <= 0;
        if (!quitar && !asegurarPermiso(Permiso.DESCUENTO_MANUAL)) return;
        try {
            ventaService.aplicarDescuentoManual(d, pct > 0 ? pct : null, imp > 0 ? imp : null, autorizaciones);
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "Descuento manual", e.getMessage());
        }
        recalcularTodo();
        txtCodigo.requestFocus();
    }

    @FXML
    private void onCambiarCantidad() {
        DetalleVenta d = seleccionado("Selecciona un renglón del ticket para cambiar la cantidad.");
        if (d == null) return;

        boolean granel = d.getProducto().permiteDecimales();
        TextInputDialog dlg = new TextInputDialog(formatoCantidad(d.getCantidad()));
        dlg.setTitle("Cantidad");
        dlg.setHeaderText(d.getProducto().getDescripcion()
                + (granel ? "  (a granel: acepta decimales, ej. 1.250)" : "  (por pieza: número entero)"));
        dlg.setContentText("Nueva cantidad:");
        dlg.initOwner(tablaProductos.getScene().getWindow());
        Ventanas.estilizar(dlg);
        dlg.showAndWait().ifPresent(txt -> {
            double nueva = parsearODefault(txt, Double.NaN);
            if (Double.isNaN(nueva)) {
                alerta(Alert.AlertType.WARNING, "Cantidad inválida",
                        granel ? "Escribe un número (ej: 1.5)." : "Escribe un número entero (ej: 3).");
                return;
            }
            cambiarCantidad(d, nueva);
        });
        txtCodigo.requestFocus();
    }

    @FXML
    private void onQuitarLinea() {
        DetalleVenta d = tablaProductos.getSelectionModel().getSelectedItem();
        if (d == null) {
            if (!itemsTicket.isEmpty()) alerta(Alert.AlertType.WARNING, "Sin selección", "Selecciona un renglón del ticket para quitarlo.");
            return;
        }
        int idx = itemsTicket.indexOf(d);
        boolean focoEnTabla = tablaProductos.isFocused();
        itemsTicket.remove(d);
        recalcularTodo();
        if (focoEnTabla && !itemsTicket.isEmpty()) {
            tablaProductos.getSelectionModel().select(Math.min(idx, itemsTicket.size() - 1));
            tablaProductos.requestFocus();
        } else {
            txtCodigo.requestFocus();
        }
    }

    @FXML private void onTodoEfectivo()      { pagarTodoCon(Venta.FormaPago.EFECTIVO); }
    @FXML private void onTodoTarjeta()       { pagarTodoCon(Venta.FormaPago.TARJETA); }
    @FXML private void onTodoTransferencia() { pagarTodoCon(Venta.FormaPago.TRANSFERENCIA); }
    @FXML private void onTodoVales()         { pagarTodoCon(Venta.FormaPago.VALES); }

    private void pagarTodoCon(Venta.FormaPago forma) {
        camposPago.values().forEach(TextField::clear);
        camposPago.get(forma).setText(String.format("%.2f", ventaService.calcularTotal(itemsTicket)));
        actualizarCambio();
        camposPago.get(forma).requestFocus();
        camposPago.get(forma).selectAll();
    }

    /**
     * Cobrar (F12, botón o Enter en un campo de pago).
     *  - Ticket vacío: aviso.
     *  - Si en el panel ya hay un pago válido y suficiente y la opción "Confirmar
     *    antes de cobrar" está apagada, cobra directo (el camino más rápido:
     *    teclear el efectivo y Enter).
     *  - Si no, abre la ventana de cobro (CobroDialogo) con lo ya capturado; ahí
     *    Enter cobra y Esc regresa al ticket sin tocar nada.
     */
    @FXML
    private void onCobrar() {
        if (itemsTicket.isEmpty()) {
            alerta(Alert.AlertType.INFORMATION, "Ticket vacío",
                    "Agrega al menos un producto antes de cobrar.");
            txtCodigo.requestFocus();
            return;
        }
        double total = ventaService.calcularTotal(itemsTicket);
        Map<Venta.FormaPago, Double> montosPanel = leerPagos();
        if (montosPanel == null) {
            alerta(Alert.AlertType.WARNING, "Pago inválido", "Escribe montos válidos (ej: 200).");
            txtEfectivo.requestFocus();
            txtEfectivo.selectAll();
            return;
        }
        Map<Venta.FormaPago, Double> montos;
        CalculoCobro.Resultado cuentas = CalculoCobro.calcular(total, montosPanel);
        if (cuentas.esValido() && !configuracionService.isConfirmarCobro()) {
            montos = montosPanel;
        } else {
            Optional<Map<Venta.FormaPago, Double>> elegido = CobroDialogo.mostrar(
                    raiz.getScene().getWindow(), total, configuracionService.formasPagoHabilitadas(), montosPanel);
            if (elegido.isEmpty()) {                  // Esc: nada cambia, el ticket sigue en captura
                txtCodigo.requestFocus();
                return;
            }
            montos = elegido.get();
        }
        cobrarCon(montos);
    }

    /** Registra la venta con los montos ya validados en pantalla; pide los datos de tarjeta si aplica */
    private void cobrarCon(Map<Venta.FormaPago, Double> montos) {
        // Cobro con tarjeta: terminal, últimos 4 dígitos y autorización del voucher (captura manual)
        DatosTarjeta tarjeta = null;
        if (montos.containsKey(Venta.FormaPago.TARJETA)) {
            Optional<DatosTarjeta> capturada = dispositivosDialogos.capturarTarjeta(
                    raiz.getScene().getWindow(), montos.get(Venta.FormaPago.TARJETA), tarjetaCapturada);
            if (capturada.isEmpty()) {                // cobro cancelado: no se guarda nada
                txtCodigo.requestFocus();
                return;
            }
            tarjeta = capturada.get();
            tarjetaCapturada = tarjeta;
        }
        List<PagoVenta> pagos = new ArrayList<>();
        for (Map.Entry<Venta.FormaPago, Double> e : montos.entrySet()) {
            if (e.getKey() == Venta.FormaPago.TARJETA && tarjeta != null) {
                pagos.add(new PagoVenta(e.getKey(), e.getValue(), tarjeta.terminal(), tarjeta.ultimos4(), tarjeta.autorizacion()));
            } else {
                pagos.add(new PagoVenta(e.getKey(), e.getValue()));
            }
        }

        try {
            // El cajero y los permisos los verifica el servicio
            Venta venta = ventaService.cobrar(new ArrayList<>(itemsTicket), pagos, autorizaciones);

            imprimirTicketSinBloquear(venta);
            limpiarTicket();                          // el ticket queda limpio antes de cualquier diálogo
            mostrarCambioUltimaVenta(venta);
            actualizarVendidoHoy();
            actualizarEnEspera();

            avisarVentaCobrada(venta);
            avisarStockBajo(venta);

        } catch (PermisoRequeridoException e) {
            // Falta un permiso del ticket (p. ej. renglón restaurado con mayoreo o
            // existencia que otra caja se llevó): pedir autorización y reintentar con los mismos montos
            if (asegurarPermiso(e.getPermiso())) {
                cobrarCon(montos);
                return;
            }
            refrescarTicket();
        } catch (NegocioException e) {
            // Stock, promoción o precio cambiaron desde que se armó el ticket:
            // releer productos, recalcular y avisar
            refrescarTicket();
            alerta(Alert.AlertType.ERROR, "No se pudo cobrar", e.getMessage());
        }
        txtCodigo.requestFocus();
    }

    /** En el panel queda a la vista el cambio de la venta recién cobrada (hasta que se agregue otro producto) */
    private void mostrarCambioUltimaVenta(Venta venta) {
        labelCambioTitulo.setText("Cambio de la venta " + venta.getFolioMostrado() + ":");
        labelCambio.setText(Moneda.formato(venta.getCambio()));
        labelPagado.setText(Moneda.formato(venta.getPagoCon()));
    }

    /**
     * Ventana "Venta cobrada" con el cambio en grande. Enter o Esc continúan; si la
     * facturación está configurada y el usuario puede facturar, "Facturar" (F).
     */
    private void avisarVentaCobrada(Venta venta) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Venta cobrada");
        dlg.setHeaderText("Venta " + venta.getFolioMostrado() + " registrada");
        dlg.initOwner(raiz.getScene().getWindow());
        Ventanas.estilizar(dlg);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Total:"), new Label(Moneda.formato(venta.getTotal())));
        grid.addRow(1, new Label("Pagó:"), new Label(Moneda.formato(venta.getPagoCon())));
        Label cambioTitulo = new Label("CAMBIO");
        cambioTitulo.getStyleClass().add("total-label-title");
        Label cambio = new Label(Moneda.formato(venta.getCambio()));
        cambio.getStyleClass().add("total-amount");
        VBox contenido = new VBox(8, grid, cambioTitulo, cambio);
        contenido.setAlignment(javafx.geometry.Pos.CENTER);
        contenido.setPadding(new Insets(6, 20, 6, 20));
        dlg.getDialogPane().setContent(contenido);

        // CANCEL_CLOSE: así la X de la ventana también continúa (JavaFX exige un botón de cancelar para cerrarla)
        ButtonType continuar = new ButtonType("Continuar  (Enter)", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType facturar = new ButtonType("Facturar  (F)", javafx.scene.control.ButtonBar.ButtonData.OTHER);
        boolean puedeFacturar = facturacionDialogos.puedeFacturar();
        if (puedeFacturar) dlg.getDialogPane().getButtonTypes().addAll(continuar, facturar);
        else dlg.getDialogPane().getButtonTypes().add(continuar);
        Button btnContinuar = (Button) dlg.getDialogPane().lookupButton(continuar);
        btnContinuar.getStyleClass().add("button-primary");
        btnContinuar.setDefaultButton(true);   // Enter continúa
        dlg.setOnShown(e -> {
            var acc = dlg.getDialogPane().getScene().getAccelerators();
            acc.put(new KeyCodeCombination(KeyCode.ESCAPE), () -> { dlg.setResult(continuar); dlg.close(); });
            if (puedeFacturar) acc.put(new KeyCodeCombination(KeyCode.F), () -> { dlg.setResult(facturar); dlg.close(); });
            Platform.runLater(() -> dlg.getDialogPane().lookupButton(continuar).requestFocus());
        });
        if (dlg.showAndWait().filter(b -> b == facturar).isPresent()) {
            facturacionDialogos.facturarVenta(raiz.getScene().getWindow(), venta);
        }
    }

    /**
     * Imprime el ticket (y abre el cajón) en otro hilo. La venta ya está guardada:
     * si la impresora falla solo se muestra un aviso, nunca se deshace ni se frena nada.
     */
    private void imprimirTicketSinBloquear(Venta venta) {
        try {
            impresionService.despuesDeCobrarAsync(venta.getId()).whenComplete((res, error) -> {
                String aviso = error != null ? "No se pudo imprimir el ticket: " + error.getMessage()
                        : res != null && res.esFallo() ? res.mensaje() : null;
                if (aviso != null) {
                    Platform.runLater(() -> alerta(Alert.AlertType.WARNING, "Impresión", aviso));
                }
            });
        } catch (RuntimeException e) {
            alerta(Alert.AlertType.WARNING, "Impresión", "La venta se guardó, pero no se pudo imprimir el ticket: " + e.getMessage());
        }
    }

    /** Lee el peso de la báscula y lo pone como cantidad del renglón a granel seleccionado */
    @FXML
    private void onLeerPeso() {
        DetalleVenta d = seleccionado("Selecciona el renglón a granel del ticket y pulsa Leer peso.");
        if (d == null) return;
        if (!d.getProducto().permiteDecimales()) {
            alerta(Alert.AlertType.INFORMATION, "Leer peso",
                    "\"" + d.getProducto().getDescripcion() + "\" se vende por pieza. Leer peso es para productos a granel.");
            return;
        }
        btnLeerPeso.setDisable(true);
        Thread t = new Thread(() -> {
            double peso = 0;
            String error = null;
            try {
                peso = basculaService.leerPeso();
            } catch (RuntimeException e) {
                error = e.getMessage() != null ? e.getMessage() : "No se pudo leer la báscula.";
            }
            final double pesoLeido = peso;
            final String fallo = error;
            Platform.runLater(() -> {
                btnLeerPeso.setDisable(false);
                if (fallo != null) {
                    alerta(Alert.AlertType.WARNING, "Báscula", fallo + "\nPuedes teclear la cantidad con el botón Cantidad.");
                } else if (itemsTicket.contains(d)) {
                    cambiarCantidad(d, pesoLeido);
                }
                txtCodigo.requestFocus();
            });
        }, "bascula-leer");
        t.setDaemon(true);
        t.start();
    }

    /** Pulso al cajón de dinero (ABRIR_CAJON o autorización de administrador; queda en la bitácora) */
    @FXML
    private void onAbrirCajon() {
        dispositivosDialogos.abrirCajon(raiz.getScene().getWindow());
        txtCodigo.requestFocus();
    }

    @FXML
    private void onCancelarTicket() {
        if (itemsTicket.isEmpty()) {
            limpiarTicket();
            txtCodigo.requestFocus();
            return;
        }
        if (Ventanas.confirmar("Cancelar ticket",
                "¿Cancelar el ticket actual? Se quitarán todos los productos.")) {
            ticketPendienteService.descartarDelCajeroActual();
            limpiarTicket();
        }
        txtCodigo.requestFocus();
    }

    // ── Tickets en espera (F11 / Shift+F11) ──────────────────────────────

    /** Aparta el ticket en captura con una nota opcional y deja la pantalla lista para otro cliente */
    @FXML
    private void onPonerEnEspera() {
        if (itemsTicket.isEmpty()) {
            alerta(Alert.AlertType.INFORMATION, "Ticket en espera", "El ticket está vacío: no hay nada que poner en espera.");
            txtCodigo.requestFocus();
            return;
        }
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle("Poner ticket en espera");
        dlg.setHeaderText(itemsTicket.size() + " renglón(es) por " + Moneda.formato(ventaService.calcularTotal(itemsTicket))
                + "\nEl ticket se guarda sin cobrar y lo recuperas con Shift+F11.");
        dlg.setContentText("Nota (opcional):");
        dlg.getEditor().setPromptText("Ej. Sra. Lupita, el de la camioneta…");
        dlg.initOwner(raiz.getScene().getWindow());
        Ventanas.estilizar(dlg);
        Optional<String> nota = dlg.showAndWait();
        if (nota.isEmpty()) { txtCodigo.requestFocus(); return; }
        try {
            ticketPendienteService.ponerEnEspera(new ArrayList<>(itemsTicket), nota.get());
            ticketPendienteService.descartarDelCajeroActual();   // el autoguardado era este mismo ticket
            limpiarTicket();
            actualizarEnEspera();
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "Ticket en espera", e.getMessage());
        }
        txtCodigo.requestFocus();
    }

    /**
     * Al bloquear la pantalla (Ctrl/Cmd+L) el ticket en captura pasa a "en espera" con
     * la nota "Bloqueo de <cajero>", para que CUALQUIER cajero de la caja lo recupere
     * (si no, el siguiente cajero no lo vería y el cliente se queda esperando).
     * Devuelve false si no se pudo (p. ej. tope de tickets en espera): entonces el
     * llamador lo guarda como pendiente del cajero, como antes.
     */
    public boolean ponerEnEsperaPorBloqueo() {
        if (itemsTicket.isEmpty()) return true;
        try {
            Cajero c = sesionService.getCajeroActual();
            String nombre = c != null ? c.getNombreCompleto() : "cajero";
            String nota = "Bloqueo de " + nombre;
            if (nota.length() > TicketPendienteService.MAX_LARGO_NOTA) nota = nota.substring(0, TicketPendienteService.MAX_LARGO_NOTA);
            ticketPendienteService.ponerEnEspera(new ArrayList<>(itemsTicket), nota);
            ticketPendienteService.descartarDelCajeroActual();
            limpiarTicket();
            actualizarEnEspera();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Lista de tickets en espera de esta caja: Enter recupera, Supr descarta, Esc cierra */
    @FXML
    private void onRecuperarEspera() {
        List<TicketPendienteService.EnEspera> lista;
        try {
            lista = ticketPendienteService.listarEnEspera();
        } catch (RuntimeException e) {
            lista = List.of();
        }
        if (lista.isEmpty()) {
            alerta(Alert.AlertType.INFORMATION, "Recuperar ticket", "No hay tickets en espera en esta caja.");
            txtCodigo.requestFocus();
            return;
        }
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Tickets en espera");
        dlg.setHeaderText("Elige el ticket y pulsa Enter para recuperarlo (Supr lo descarta)");
        dlg.initOwner(raiz.getScene().getWindow());
        Ventanas.estilizar(dlg);

        TableView<TicketPendienteService.EnEspera> tabla = new TableView<>(FXCollections.observableArrayList(lista));
        tabla.setPrefSize(640, 260);
        TableColumn<TicketPendienteService.EnEspera, String> cHora = new TableColumn<>("Hora");
        cHora.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().guardadoEn().format(FMT_HORA)));
        cHora.setPrefWidth(70);
        TableColumn<TicketPendienteService.EnEspera, String> cNota = new TableColumn<>("Nota");
        cNota.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nota() != null ? c.getValue().nota() : "—"));
        cNota.setPrefWidth(220);
        TableColumn<TicketPendienteService.EnEspera, String> cCajero = new TableColumn<>("Cajero");
        cCajero.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().cajero()));
        cCajero.setPrefWidth(150);
        TableColumn<TicketPendienteService.EnEspera, String> cRenglones = new TableColumn<>("Renglones");
        cRenglones.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().renglones())));
        cRenglones.getStyleClass().add("align-right");
        TableColumn<TicketPendienteService.EnEspera, String> cPiezas = new TableColumn<>("Piezas");
        cPiezas.setCellValueFactory(c -> new SimpleStringProperty(formatoCantidad(c.getValue().piezas())));
        cPiezas.getStyleClass().add("align-right");
        tabla.getColumns().add(cHora);
        tabla.getColumns().add(cNota);
        tabla.getColumns().add(cCajero);
        tabla.getColumns().add(cRenglones);
        tabla.getColumns().add(cPiezas);
        tabla.getSelectionModel().selectFirst();

        ButtonType recuperar = new ButtonType("Recuperar  (Enter)", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType descartar = new ButtonType("Descartar  (Supr)", javafx.scene.control.ButtonBar.ButtonData.OTHER);
        dlg.getDialogPane().getButtonTypes().addAll(recuperar, descartar, ButtonType.CANCEL);
        ((Button) dlg.getDialogPane().lookupButton(recuperar)).getStyleClass().add("button-primary");
        ((Button) dlg.getDialogPane().lookupButton(descartar)).getStyleClass().add("button-danger");
        Button btnRecuperarDlg = (Button) dlg.getDialogPane().lookupButton(recuperar);
        Button btnDescartarDlg = (Button) dlg.getDialogPane().lookupButton(descartar);
        btnRecuperarDlg.disableProperty().bind(tabla.getSelectionModel().selectedItemProperty().isNull());
        btnDescartarDlg.disableProperty().bind(tabla.getSelectionModel().selectedItemProperty().isNull());

        // Descartar no cierra la ventana: confirma, borra y refresca la lista
        btnDescartarDlg.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            TicketPendienteService.EnEspera sel = tabla.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (!Ventanas.confirmar("Descartar ticket en espera",
                    "¿Descartar el ticket" + (sel.nota() != null ? " \"" + sel.nota() + "\"" : "") + " de las " + sel.guardadoEn().format(FMT_HORA)
                    + " con " + sel.renglones() + " renglón(es)? No se cobrará.")) return;
            try {
                ticketPendienteService.descartarEnEspera(sel.id());
            } catch (NegocioException e) {
                alerta(Alert.AlertType.ERROR, "Descartar", e.getMessage());
            }
            tabla.getItems().remove(sel);
            tabla.getSelectionModel().selectFirst();
            actualizarEnEspera();
            if (tabla.getItems().isEmpty()) { dlg.setResult(ButtonType.CANCEL); dlg.close(); }
        });
        tabla.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !btnRecuperarDlg.isDisable()) { btnRecuperarDlg.fire(); e.consume(); }
            else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) { btnDescartarDlg.fire(); e.consume(); }
        });
        tabla.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !btnRecuperarDlg.isDisable()) btnRecuperarDlg.fire();
        });
        VBox caja = new VBox(8, tabla);
        caja.setPadding(new Insets(8));
        dlg.getDialogPane().setContent(caja);
        Platform.runLater(tabla::requestFocus);

        Optional<ButtonType> res = dlg.showAndWait();
        TicketPendienteService.EnEspera elegido = tabla.getSelectionModel().getSelectedItem();
        if (res.isEmpty() || res.get() != recuperar || elegido == null) {
            txtCodigo.requestFocus();
            return;
        }
        // Si hay un ticket en captura, se aparta primero (no se pierde ni se mezcla)
        boolean actualApartado = false;
        if (!itemsTicket.isEmpty()) {
            if (!Ventanas.confirmar("Recuperar ticket",
                    "El ticket actual tiene " + itemsTicket.size() + " renglón(es). ¿Ponerlo en espera y recuperar el elegido?")) {
                txtCodigo.requestFocus();
                return;
            }
            try {
                ticketPendienteService.ponerEnEspera(new ArrayList<>(itemsTicket), null);
                actualApartado = true;
            } catch (NegocioException e) {
                alerta(Alert.AlertType.ERROR, "Ticket en espera", e.getMessage());
                txtCodigo.requestFocus();
                return;
            }
        }
        try {
            List<DetalleVenta> detalles = ticketPendienteService.recuperarEnEspera(elegido.id());
            ticketPendienteService.descartarDelCajeroActual();
            limpiarTicket();
            int omitidos = 0;
            for (DetalleVenta d : detalles) {
                try {
                    ventaService.recalcularPrecio(d);
                    itemsTicket.add(d);
                } catch (NegocioException ignorada) { omitidos++; }   // renglón ya no válido
            }
            recalcularTodo();
            if (!itemsTicket.isEmpty()) tablaProductos.getSelectionModel().selectLast();
            if (omitidos > 0) {
                alerta(Alert.AlertType.WARNING, "Ticket recuperado",
                        omitidos + " renglón(es) ya no se pueden vender y se omitieron.");
            }
        } catch (NegocioException e) {
            if (actualApartado) {
                // El ticket de la pantalla ya quedó en espera: si siguiera aquí también,
                // se podría cobrar dos veces (una aquí y otra al recuperarlo)
                ticketPendienteService.descartarDelCajeroActual();
                limpiarTicket();
            }
            alerta(Alert.AlertType.ERROR, "Recuperar ticket", e.getMessage()
                    + (actualApartado ? "\nEl ticket que tenías en pantalla quedó en espera." : ""));
        }
        actualizarEnEspera();
        txtCodigo.requestFocus();
    }

    /** Contador de tickets en espera en el botón "Recuperar" y en el pie */
    private void actualizarEnEspera() {
        int n;
        try {
            n = ticketPendienteService.contarEnEspera();
        } catch (RuntimeException e) {
            n = 0;
        }
        btnRecuperar.setText("▶ Recuperar" + (n > 0 ? " (" + n + ")" : ""));
        labelEnEspera.setText(n == 0 ? "" : n + " en espera");
        labelEnEspera.setVisible(n > 0);
        labelEnEspera.setManaged(n > 0);
    }

    // ── Reimprimir último ticket (Ctrl+I) ────────────────────────────────

    @FXML
    private void onReimprimirUltimo() {
        // Reimprimir pide permiso de historial o la clave de un administrador (de un solo uso)
        Autorizacion aut = null;
        if (!sesionService.tienePermiso(Permiso.VER_HISTORIAL)) {
            Optional<Autorizacion> obtenida = Ventanas.pedirAutorizacion(autorizacionService, Permiso.VER_HISTORIAL);
            if (obtenida.isEmpty()) { txtCodigo.requestFocus(); return; }
            aut = obtenida.get();
        }
        btnReimprimir.setDisable(true);
        try {
            impresionService.reimprimirUltimaVentaAsync(aut).whenComplete((res, error) -> Platform.runLater(() -> {
                btnReimprimir.setDisable(false);
                if (error != null || res == null || res.esFallo()) {
                    alerta(Alert.AlertType.WARNING, "Reimprimir",
                            res != null ? res.mensaje() : "No se pudo reimprimir el ticket.");
                }
            }));
        } catch (NegocioException e) {
            btnReimprimir.setDisable(false);
            alerta(Alert.AlertType.ERROR, "Reimprimir", e.getMessage());
        }
        txtCodigo.requestFocus();
    }

    public boolean hayTicketEnCaptura() {
        return !itemsTicket.isEmpty();
    }

    /** Guarda el ticket sin cobrar para el cajero en sesión (lo llama MainController al salir) */
    public void guardarTicketPendiente() {
        try {
            ticketPendienteService.guardar(new ArrayList<>(itemsTicket));
        } catch (NegocioException e) {
            // sin sesión válida no hay a quién guardárselo
        }
    }

    /** Pliega o despliega la tira de sugerencias (no se guarda; la opción persistente está en Configuración) */
    @FXML
    private void onOcultarSugerencias() {
        sugerenciasPlegadas = !sugerenciasPlegadas;
        contenedorSugerencias.setVisible(!sugerenciasPlegadas);
        contenedorSugerencias.setManaged(!sugerenciasPlegadas);
        btnOcultarSugerencias.setText(sugerenciasPlegadas ? "Mostrar" : "Ocultar");
        if (!sugerenciasPlegadas) programarSugerencias();
        txtCodigo.requestFocus();
    }

    // ── Sugerencias (RecomendacionService) ───────────────────────────────

    private void configurarSugerencias() {
        esperaSugerencias.setOnFinished(e -> actualizarSugerencias());
        itemsTicket.addListener((ListChangeListener<DetalleVenta>) c -> programarSugerencias());
        mostrarTiraSugerencias(configuracionService.isMostrarSugerencias());
    }

    private void mostrarTiraSugerencias(boolean mostrar) {
        boxSugerencias.setVisible(mostrar);
        boxSugerencias.setManaged(mostrar);
        if (!mostrar) contenedorSugerencias.getChildren().clear();
    }

    /** Espera a que el cajero termine de capturar y luego calcula (una sola vez) */
    private void programarSugerencias() {
        if (!boxSugerencias.isVisible() || sugerenciasPlegadas) return;
        esperaSugerencias.playFromStart();
    }

    /** Calcula en segundo plano y pinta en el hilo de JavaFX; nunca estorba al cobro */
    private void actualizarSugerencias() {
        if (!boxSugerencias.isVisible() || sugerenciasPlegadas) return;
        final int generacion = generacionSugerencias.incrementAndGet();
        final List<Long> carrito = itemsTicket.stream()
                .map(d -> d.getProducto().getId()).filter(id -> id != null).toList();
        lblSugerenciasEstado.setText("calculando…");
        hiloSugerencias.submit(() -> {
            List<Sugerencia> sugerencias;
            try {
                ClimaRegistro clima = climaService.actual().orElse(null);
                sugerencias = recomendacionService.sugerir(carrito, LocalDateTime.now(), clima,
                        RecomendacionService.MAX_SUGERENCIAS);
            } catch (Exception e) {
                sugerencias = List.of();   // sin sugerencias; la venta sigue igual
            }
            final List<Sugerencia> resultado = sugerencias;
            Platform.runLater(() -> {
                if (generacion != generacionSugerencias.get()) return;   // ya hay una petición más nueva
                pintarSugerencias(resultado);
            });
        });
    }

    private void pintarSugerencias(List<Sugerencia> sugerencias) {
        contenedorSugerencias.getChildren().clear();
        if (sugerencias.isEmpty()) {
            lblSugerenciasEstado.setText(itemsTicket.isEmpty() ? "" : "sin sugerencias para este ticket");
            return;
        }
        lblSugerenciasEstado.setText("");
        for (Sugerencia s : sugerencias) {
            Label nombre = new Label(s.producto().getDescripcion());
            nombre.getStyleClass().add("sugerencia-nombre");
            Label motivo = new Label(s.motivo());
            motivo.getStyleClass().add("sugerencia-motivo");
            VBox textos = new VBox(1, nombre, motivo);

            Label badge = new Label(s.nivelConfianza());
            badge.getStyleClass().addAll("badge", switch (s.nivelConfianza()) {
                case "Alta"  -> "badge-success";
                case "Media" -> "badge-accent";
                default      -> "badge-warning";
            });
            badge.setTooltip(new Tooltip("Confianza de la sugerencia: " + s.nivelConfianza().toLowerCase()));

            Button agregar = new Button("Agregar");
            agregar.getStyleClass().addAll("button-primary", "button-sm");
            agregar.setFocusTraversable(false);
            final Long id = s.producto().getId();
            agregar.setOnAction(e -> agregarSugerencia(id));

            HBox chip = new HBox(textos, badge, agregar);
            chip.getStyleClass().add("sugerencia-chip");
            contenedorSugerencias.getChildren().add(chip);
        }
    }

    /** Mismo camino que escanear el código: se relee el producto y se valida existencia */
    private void agregarSugerencia(Long productoId) {
        productoService.buscarPorId(productoId).filter(Producto::isActivo)
                .ifPresentOrElse(p -> agregarAlTicket(p, 1),
                        () -> alerta(Alert.AlertType.WARNING, "Sugerencia", "El producto ya no está disponible."));
        txtCodigo.requestFocus();
    }

    // ── Lógica del ticket (delegando reglas a VentaService) ───────────────

    private void restaurarTicketPendiente() {
        List<DetalleVenta> pendientes;
        try {
            pendientes = ticketPendienteService.recuperar();
        } catch (Exception e) {
            return;
        }
        if (pendientes.isEmpty()) return;
        for (DetalleVenta d : pendientes) {
            try {
                ventaService.recalcularPrecio(d);
                itemsTicket.add(d);
            } catch (NegocioException ignorada) { /* renglón ya no válido */ }
        }
        recalcularTodo();
        if (!itemsTicket.isEmpty()) {
            Platform.runLater(() -> alerta(Alert.AlertType.INFORMATION, "Ticket recuperado",
                    "Se restauró tu ticket pendiente con " + itemsTicket.size() + " renglón(es)."));
        }
    }

    private void agregarAlTicket(Producto producto, double cantidad) {
        // ¿Ya está en el ticket? → sumar cantidad
        Optional<DetalleVenta> existente = itemsTicket.stream()
                .filter(d -> d.getProducto().getId().equals(producto.getId()))
                .findFirst();

        if (existente.isPresent()) {
            cambiarCantidad(existente.get(), existente.get().getCantidad() + cantidad);
            tablaProductos.getSelectionModel().select(existente.get());
            return;
        }
        try {
            DetalleVenta d;
            try {
                d = ventaService.crearDetalle(producto, cantidad);
            } catch (StockInsuficienteException sinStock) {
                if (!autorizarVentaSinStock(sinStock)) return;
                d = ventaService.crearDetalle(producto, cantidad, true);
            }
            itemsTicket.add(d);
            tablaProductos.getSelectionModel().select(d);
            tablaProductos.scrollTo(d);
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se puede agregar", e.getMessage());
        }
        recalcularTodo();
    }

    /**
     * Recalcula todo el ticket (los límites de una promoción se comparten
     * entre renglones), refresca la tabla y los totales.
     */
    private void recalcularTodo() {
        try {
            ventaService.recalcularTicket(itemsTicket);
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "Ticket", e.getMessage());
        }
        tablaProductos.refresh();
        actualizarTotales();
    }

    private void cambiarCantidad(DetalleVenta d, double nuevaCantidad) {
        if (nuevaCantidad <= 0) {
            itemsTicket.remove(d);
            actualizarTotales();
            return;
        }
        try {
            ventaService.validarCantidad(d.getProducto(), nuevaCantidad);
            try {
                ventaService.validarStock(d.getProducto(), nuevaCantidad);
            } catch (StockInsuficienteException sinStock) {
                if (!autorizarVentaSinStock(sinStock)) { tablaProductos.refresh(); return; }
            }
            d.setCantidad(nuevaCantidad);
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se puede cambiar la cantidad", e.getMessage());
        }
        recalcularTodo();
    }

    /** Sin existencia: permiso propio (con confirmación) o autorización de administrador (INV-08) */
    private boolean autorizarVentaSinStock(StockInsuficienteException sinStock) {
        if (ventaService.puedeVenderSinStock(autorizaciones)) {
            return Ventanas.confirmar("Vender sin existencia",
                    sinStock.getMessage() + "\n\n¿Vender de todos modos? La existencia quedará en negativo.");
        }
        alerta(Alert.AlertType.WARNING, "Stock insuficiente",
                sinStock.getMessage() + "\n\nPara vender sin existencia se requiere la autorización de un administrador.");
        return asegurarPermiso(Permiso.VENDER_SIN_STOCK);
    }

    /**
     * true si el usuario tiene el permiso o ya hay/obtiene una autorización de
     * administrador para él (se guarda en la lista del ticket).
     */
    private boolean asegurarPermiso(Permiso permiso) {
        if (sesionService.tienePermiso(permiso)) return true;
        if (Autorizacion.buscar(autorizaciones, permiso) != null) return true;
        Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, permiso);
        aut.ifPresent(autorizaciones::add);
        return aut.isPresent();
    }

    /** Aviso en pantalla SOLO de los productos que con esta venta cruzaron su mínimo (INV-03) */
    private void avisarStockBajo(Venta venta) {
        if (!configuracionService.isAvisoStockBajo()) return;   // opción apagada en Configuración
        List<Producto> bajos = venta.getAlertasStockBajo();
        if (bajos.isEmpty()) return;
        String lista = bajos.stream()
                .map(p -> String.format("• %s: quedan %s (mínimo %s)", p.getDescripcion(),
                        formatoCantidad(p.getCantidadActual()), formatoCantidad(p.getCantidadMinima())))
                .collect(Collectors.joining("\n"));
        alerta(Alert.AlertType.WARNING, "Productos con existencia baja", lista);
    }

    /**
     * Vuelve a leer los productos del ticket desde la BD (existencias,
     * precios y promociones actuales) y recalcula cada renglón.
     */
    private void refrescarTicket() {
        for (DetalleVenta d : itemsTicket) {
            productoService.buscarPorId(d.getProducto().getId()).ifPresent(d::setProducto);
        }
        recalcularTodo();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void configurarColumnas() {
        colCodigo.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getProducto().getCodigoBarras()));
        colDescripcion.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getProducto().getDescripcion()));
        colPrecio.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getPrecioVenta()).asObject());
        colCantidad.setCellValueFactory(c ->
                new SimpleStringProperty(formatoCantidad(c.getValue().getCantidad())));
        colImporte.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getImporte()).asObject());
        colDescuento.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getDescuento()).asObject());
        colTipoDescuento.setCellValueFactory(c -> {
            DetalleVenta d = c.getValue();
            String texto = switch (d.getTipoDescuento()) {
                case PROMOCION -> d.getPromocionNombre() != null ? d.getPromocionNombre() : "Promoción";
                case MAYOREO   -> "Mayoreo";
                case MANUAL    -> "Descuento manual";
                default        -> "";
            };
            return new SimpleStringProperty(texto);
        });
        colExistencia.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getProducto().getCantidadActual()).asObject());

        formatoMoneda(colPrecio);
        formatoMoneda(colImporte);
        formatoMoneda(colDescuento);

        colExistencia.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); return; }
                DetalleVenta d = getTableRow() != null ? getTableRow().getItem() : null;
                setText(d != null && !d.getProducto().isUsaInventario() ? "—" : formatoCantidad(v));
            }
        });

        // Doble clic en un renglón = cambiar cantidad
        tablaProductos.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && tablaProductos.getSelectionModel().getSelectedItem() != null) {
                onCambiarCantidad();
            }
        });
    }

    private void formatoMoneda(TableColumn<DetalleVenta, Double> col) {
        col.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : Moneda.formato(v));
            }
        });
    }

    private DetalleVenta seleccionado(String mensajeSiNoHay) {
        DetalleVenta d = tablaProductos.getSelectionModel().getSelectedItem();
        if (d == null) {
            alerta(Alert.AlertType.WARNING, "Sin selección", mensajeSiNoHay);
        }
        return d;
    }

    /**
     * Número del campo; vacío = porDefecto; inválido/NaN/infinito/negativo = NaN.
     * La coma es separador de miles ("1,500" = 1500), igual que en Login y Corte.
     */
    private static double parsearODefault(String txt, double porDefecto) {
        return CalculoCobro.parsearMonto(txt, porDefecto);
    }

    private void actualizarTotales() {
        double total = ventaService.calcularTotal(itemsTicket);
        double piezas = itemsTicket.stream().mapToDouble(DetalleVenta::getCantidad).sum();
        labelTotal.setText(Moneda.formato(total));
        labelNumProductos.setText(itemsTicket.size() + " renglones · " + formatoCantidad(piezas)
                + " productos en la venta actual.");
        actualizarCambio();
    }

    private void actualizarCambio() {
        if (labelCambioTitulo != null) labelCambioTitulo.setText("Cambio:");
        double total = ventaService.calcularTotal(itemsTicket);
        Map<Venta.FormaPago, Double> montos = leerPagos();
        if (montos == null) {
            labelPagado.setText("—");
            labelCambio.setText("—");
            return;
        }
        CalculoCobro.Resultado r = CalculoCobro.calcular(total, montos);
        labelPagado.setText(Moneda.formato(r.pagado()));
        if (!r.hayPago() && !montos.isEmpty()) {     // monto rechazado (p. ej. un código escaneado en el campo)
            labelPagado.setText("—");
            labelCambio.setText("—");
        } else if (!r.hayPago()) {
            labelCambio.setText(Moneda.formato(0));
        } else if (r.faltante() > 0) {
            labelCambio.setText("Faltan " + Moneda.formato(r.faltante()));
        } else {
            labelCambio.setText(Moneda.formato(r.cambio()));
        }
    }

    /** Montos capturados > 0 por forma de pago; null si algún campo no es válido */
    private Map<Venta.FormaPago, Double> leerPagos() {
        Map<Venta.FormaPago, String> textos = new LinkedHashMap<>();
        camposPago.forEach((f, c) -> textos.put(f, c.getText()));
        return CalculoCobro.leerMontos(textos);
    }

    /**
     * Muestra solo las formas de pago habilitadas en Configuración. Se llama
     * al abrir la pantalla y cada vez que se vuelve a la pestaña Ventas; el
     * servicio vuelve a validarlas al cobrar.
     */
    public void aplicarConfiguracion() {
        Map<Venta.FormaPago, Region> cajas = Map.of(
                Venta.FormaPago.EFECTIVO, boxEfectivo, Venta.FormaPago.TARJETA, boxTarjeta,
                Venta.FormaPago.TRANSFERENCIA, boxTransferencia, Venta.FormaPago.VALES, boxVales);
        Map<Venta.FormaPago, Button> botones = Map.of(
                Venta.FormaPago.EFECTIVO, btnTodoEfectivo, Venta.FormaPago.TARJETA, btnTodoTarjeta,
                Venta.FormaPago.TRANSFERENCIA, btnTodoTransferencia, Venta.FormaPago.VALES, btnTodoVales);
        List<Venta.FormaPago> habilitadas = configuracionService.formasPagoHabilitadas();
        for (Venta.FormaPago forma : camposPago.keySet()) {
            boolean activa = habilitadas.contains(forma);
            for (javafx.scene.Node nodo : List.of(cajas.get(forma), botones.get(forma))) {
                nodo.setVisible(activa);
                nodo.setManaged(activa);
            }
            if (!activa) camposPago.get(forma).clear();
        }
        actualizarTotales();
        actualizarDispositivos();
        if (btnRecuperar != null) actualizarEnEspera();
        if (boxSugerencias != null) {
            mostrarTiraSugerencias(configuracionService.isMostrarSugerencias());
            programarSugerencias();
        }
    }

    /** "Leer peso" solo con báscula activa; "Abrir cajón" solo con impresora ESC/POS */
    private void actualizarDispositivos() {
        if (btnLeerPeso == null) return;
        boolean bascula = false, cajon = false;
        try {
            bascula = basculaService.disponible();
            cajon = dispositivosService.obtener().getModoImpresora()
                    == me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ModoImpresora.TERMICA;
        } catch (RuntimeException e) {
            // sin dispositivos configurables: los botones quedan ocultos
        }
        btnLeerPeso.setVisible(bascula);
        btnLeerPeso.setManaged(bascula);
        btnAbrirCajon.setVisible(cajon);
        btnAbrirCajon.setManaged(cajon);
    }

    private void actualizarVendidoHoy() {
        try {
            labelTotalPagado.setText(Moneda.formato(ventaService.totalVentasHoy()));
        } catch (Exception e) {
            labelTotalPagado.setText("$0.00");
        }
    }

    private void limpiarTicket() {
        itemsTicket.clear();
        autorizaciones.clear();
        tarjetaCapturada = null;
        txtCodigo.clear();
        camposPago.values().forEach(TextField::clear);
        labelTotal.setText("$0.00");
        labelPagado.setText("$0.00");
        labelCambio.setText("$0.00");
        actualizarTotales();
    }

    private static String formatoCantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
