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
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.bascula.BasculaService;
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
 *     Configuración) → cambio en vivo
 *  4. Cobrar (botón o F12) → VentaService.cobrar() guarda la venta y descuenta stock
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
    @FXML private Button     btnLeerPeso;
    @FXML private Button     btnAbrirCajon;
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
    @FXML private Label labelCambio;
    @FXML private Label labelNumProductos;
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

        // Supr / Retroceso sobre un renglón → quitarlo
        tablaProductos.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                onQuitarLinea();
            }
        });

        configurarSugerencias();
        restaurarTicketPendiente();
        actualizarTotales();
        actualizarVendidoHoy();
        programarSugerencias();
        Platform.runLater(() -> txtCodigo.requestFocus());
    }

    // ── Acciones FXML ─────────────────────────────────────────────────────

    @FXML
    private void onAgregarProducto() {
        String codigo = txtCodigo.getText().trim();
        if (codigo.isEmpty()) return;

        Optional<Producto> encontrado = ventaService.buscarPorCodigo(codigo);
        if (encontrado.isEmpty()) {
            alerta(Alert.AlertType.WARNING, "Producto no encontrado",
                    "No existe un producto activo con el código \"" + codigo + "\".\n" +
                    "Usa 🔍 Buscar para localizarlo por nombre.");
            txtCodigo.selectAll();
            return;
        }

        agregarAlTicket(encontrado.get(), 1);
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
        // Enter en el filtro = seleccionar el primero
        txtFiltro.setOnAction(e -> {
            if (!tabla.getItems().isEmpty()) {
                tabla.getSelectionModel().selectFirst();
                dialogo.setResult(tabla.getSelectionModel().getSelectedItem());
                dialogo.close();
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
        if (d == null) return;
        itemsTicket.remove(d);
        recalcularTodo();
        txtCodigo.requestFocus();
    }

    @FXML
    private void onPagoChanged() {
        actualizarCambio();
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

    @FXML
    private void onCobrar() {
        if (itemsTicket.isEmpty()) {
            alerta(Alert.AlertType.INFORMATION, "Ticket vacío",
                    "Agrega al menos un producto antes de cobrar.");
            return;
        }

        double total = ventaService.calcularTotal(itemsTicket);
        Map<Venta.FormaPago, Double> montos = leerPagos();
        if (montos == null) {
            alerta(Alert.AlertType.WARNING, "Pago inválido", "Escribe montos válidos (ej: 200).");
            txtEfectivo.requestFocus();
            return;
        }
        double pagado = montos.values().stream().mapToDouble(Double::doubleValue).sum();
        if (pagado + 0.001 < total) {
            alerta(Alert.AlertType.WARNING, "Pago insuficiente", String.format(
                    "El cliente pagó %s pero el total es %s.", Moneda.formato(pagado), Moneda.formato(total)));
            txtEfectivo.requestFocus();
            return;
        }
        if (configuracionService.isConfirmarCobro() && !Ventanas.confirmar("Confirmar cobro",
                "¿Cobrar la venta por " + Moneda.formato(total) + "?")) {
            return;
        }

        // Cobro con tarjeta: terminal, últimos 4 dígitos y autorización del voucher (captura manual)
        DatosTarjeta tarjeta = null;
        if (montos.containsKey(Venta.FormaPago.TARJETA)) {
            Optional<DatosTarjeta> capturada = dispositivosDialogos.capturarTarjeta(
                    tablaProductos.getScene().getWindow(), montos.get(Venta.FormaPago.TARJETA), tarjetaCapturada);
            if (capturada.isEmpty()) return;          // cobro cancelado: no se guarda nada
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

            String msg = String.format("Venta #%d registrada.\n\nTotal:  %s\nPagó:   %s\nCambio: %s",
                    venta.getId(), Moneda.formato(venta.getTotal()), Moneda.formato(venta.getPagoCon()), Moneda.formato(venta.getCambio()));
            alerta(Alert.AlertType.INFORMATION, "Venta cobrada", msg);

            avisarStockBajo(venta);
            limpiarTicket();
            actualizarVendidoHoy();

        } catch (PermisoRequeridoException e) {
            // Falta un permiso del ticket (p. ej. renglón restaurado con mayoreo): pedir autorización
            if (asegurarPermiso(e.getPermiso())) {
                onCobrar();
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
        dispositivosDialogos.abrirCajon(tablaProductos.getScene().getWindow());
        txtCodigo.requestFocus();
    }

    @FXML
    private void onCancelarTicket() {
        if (itemsTicket.isEmpty()) {
            limpiarTicket();
            return;
        }
        if (Ventanas.confirmar("Cancelar ticket",
                "¿Cancelar el ticket actual? Se quitarán todos los productos.")) {
            ticketPendienteService.descartarDelCajeroActual();
            limpiarTicket();
        }
        txtCodigo.requestFocus();
    }

    /** Invocado por MainController con la tecla F12 */
    public void cobrarDesdeAtajo() {
        onCobrar();
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
        if (txt == null) return porDefecto;
        String t = txt.trim().replace("$", "").replace(",", "").replace("%", "");
        if (t.isEmpty()) return porDefecto;
        try {
            double v = Double.parseDouble(t);
            return Double.isFinite(v) && v >= 0 ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
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
        double total = ventaService.calcularTotal(itemsTicket);
        Map<Venta.FormaPago, Double> montos = leerPagos();
        if (montos == null) {
            labelPagado.setText("—");
            labelCambio.setText("—");
            return;
        }
        double pagado = montos.values().stream().mapToDouble(Double::doubleValue).sum();
        labelPagado.setText(Moneda.formato(pagado));
        if (pagado <= 0) {
            labelCambio.setText(Moneda.formato(0));
            return;
        }
        double cambio = pagado - total;
        labelCambio.setText(cambio < 0
                ? "Faltan " + Moneda.formato(-cambio)
                : Moneda.formato(cambio));
    }

    /** Montos capturados > 0 por forma de pago; null si algún campo no es válido */
    private Map<Venta.FormaPago, Double> leerPagos() {
        Map<Venta.FormaPago, Double> montos = new LinkedHashMap<>();
        for (Map.Entry<Venta.FormaPago, TextField> e : camposPago.entrySet()) {
            double monto = parsearODefault(e.getValue().getText(), 0);
            if (Double.isNaN(monto)) return null;
            if (monto > 0) montos.put(e.getKey(), monto);
        }
        return montos;
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
        txtEfectivo.clear();
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
