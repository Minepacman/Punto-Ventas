package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.CuentaClienteMovimiento;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.CuentaClienteService;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.TicketService;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Supplier;

/**
 * Clientes (F2): cuentas de crédito ("fiado"). Lista con lo que debe cada uno,
 * alta y edición, abonos (efectivo, tarjeta o transferencia; el efectivo entra
 * al corte), estado de cuenta (imprimir / exportar) y ajustes de saldo. Las
 * reglas y permisos los aplica {@link CuentaClienteService}; aquí solo se pide
 * la autorización de un administrador cuando el servicio la exige.
 */
@Component
@Scope("prototype")
public class ClientesController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Autowired private CuentaClienteService cuentaService;
    @Autowired private AutorizacionService  autorizacionService;
    @Autowired private ImpresionService     impresionService;
    @Autowired private TicketService        ticketService;
    @Autowired private ConfiguracionService configuracionService;

    @FXML private BorderPane raiz;
    @FXML private TableView<Cliente> tablaClientes;
    @FXML private TableColumn<Cliente, String> colNombre;
    @FXML private TableColumn<Cliente, String> colTelefono;
    @FXML private TableColumn<Cliente, String> colSaldo;
    @FXML private TableColumn<Cliente, String> colLimite;
    @FXML private TableColumn<Cliente, String> colDisponible;
    @FXML private TableColumn<Cliente, String> colEstado;
    @FXML private TableColumn<Cliente, String> colNota;
    @FXML private TextField txtBuscar;
    @FXML private CheckBox  chkSoloDeudores;
    @FXML private CheckBox  chkInactivos;
    @FXML private Label     lblTotal;

    private final ObservableList<Cliente> clientes = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));
        colTelefono.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTelefono() != null ? c.getValue().getTelefono() : ""));
        colSaldo.setCellValueFactory(c -> new SimpleStringProperty(textoSaldo(c.getValue().getSaldoDeuda())));
        colLimite.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tieneLimite()
                ? Moneda.formato(c.getValue().getLimiteCredito()) : "Sin límite"));
        colDisponible.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDisponible() != null
                ? Moneda.formato(c.getValue().getDisponible()) : "—"));
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActivo() ? "Activo" : "Desactivado"));
        colNota.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNota() != null ? c.getValue().getNota() : ""));
        tablaClientes.setItems(clientes);
        tablaClientes.setPlaceholder(new Label("Aún no hay clientes. Se dan de alta con «Nuevo cliente» o al cobrar con «A cuenta (fiado)»."));
        tablaClientes.setRowFactory(t -> {
            TableRow<Cliente> fila = new TableRow<>();
            fila.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !fila.isEmpty()) estadoDeCuenta(fila.getItem()); });
            return fila;
        });
        txtBuscar.textProperty().addListener((o, a, b) -> recargar());
        recargar();
    }

    static String textoSaldo(double saldo) {
        if (saldo < -0.004) return "A favor " + Moneda.formato(-saldo);
        return Moneda.formato(Math.max(0, saldo));
    }

    /** Relee la lista (lo llama MainController al entrar a la pestaña) */
    public void recargar() {
        Long seleccionado = Optional.ofNullable(tablaClientes.getSelectionModel().getSelectedItem()).map(Cliente::getId).orElse(null);
        try {
            List<Cliente> lista = new ArrayList<>(cuentaService.buscar(txtBuscar.getText(), chkInactivos.isSelected()));
            if (chkSoloDeudores.isSelected()) lista.removeIf(c -> c.getSaldoDeuda() <= 0.004);
            clientes.setAll(lista);
            lblTotal.setText("Por cobrar: " + Moneda.formato(cuentaService.totalPorCobrar()));
        } catch (NegocioException e) {
            tablaClientes.setPlaceholder(new Label(e.getMessage()));
            clientes.clear();
        }
        if (seleccionado != null) {
            clientes.stream().filter(c -> seleccionado.equals(c.getId())).findFirst()
                    .ifPresent(c -> tablaClientes.getSelectionModel().select(c));
        }
    }

    @FXML void onFiltro() { recargar(); }

    @FXML void onExportar() {
        ExportadorTablas.exportar(tablaClientes, "clientes-saldos", "Clientes y saldos");
    }

    private Cliente seleccionado(String accion) {
        Cliente c = tablaClientes.getSelectionModel().getSelectedItem();
        if (c == null) Ventanas.alerta(Alert.AlertType.INFORMATION, accion, "Selecciona un cliente de la lista.");
        return c;
    }

    private Window ventana() {
        return raiz != null && raiz.getScene() != null ? raiz.getScene().getWindow() : null;
    }

    /**
     * Ejecuta una acción del servicio; si falta un permiso, pide la autorización de un
     * administrador y la reintenta una vez con ella.
     */
    private <T> Optional<T> conAutorizacion(String titulo, java.util.function.Function<List<Autorizacion>, T> accion) {
        List<Autorizacion> autorizaciones = new ArrayList<>();
        for (int intento = 0; intento < 3; intento++) {
            try {
                return Optional.ofNullable(accion.apply(autorizaciones));
            } catch (PermisoRequeridoException e) {
                Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, e.getPermiso());
                if (aut.isEmpty()) return Optional.empty();
                autorizaciones.add(aut.get());
            } catch (NegocioException e) {
                Ventanas.alerta(Alert.AlertType.ERROR, titulo, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // ── Alta y edición ───────────────────────────────────────────────────

    @FXML void onNuevoCliente() { editar(null); }

    @FXML void onModificar() {
        Cliente c = seleccionado("Modificar cliente");
        if (c != null) editar(c);
    }

    private void editar(Cliente original) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(original == null ? "Nuevo cliente" : "Modificar cliente");
        dlg.setHeaderText(original == null ? "Datos del cliente. Solo el nombre es obligatorio."
                : original.getNombre() + " — debe " + textoSaldo(original.getSaldoDeuda()));
        if (ventana() != null) dlg.initOwner(ventana());
        Ventanas.estilizar(dlg);
        TextField txtNombre = new TextField(original != null ? original.getNombre() : "");
        txtNombre.setPromptText("Ej. Doña Lupe (calle Juárez)");
        TextField txtTelefono = new TextField(original != null && original.getTelefono() != null ? original.getTelefono() : "");
        txtTelefono.setPromptText("10 dígitos (opcional)");
        TextField txtLimite = new TextField(original != null && original.tieneLimite() ? CalculoCobro.formatoCampo(original.getLimiteCredito()) : "");
        txtLimite.setPromptText("Vacío = sin límite");
        TextArea txtNota = new TextArea(original != null && original.getNota() != null ? original.getNota() : "");
        txtNota.setPromptText("Nota (opcional): dónde vive, quién responde por él, día de pago...");
        txtNota.setPrefRowCount(2);
        txtNota.setWrapText(true);
        CheckBox chkActivo = new CheckBox("Activo (se le puede fiar)");
        chkActivo.setSelected(original == null || original.isActivo());
        Label ayudaLimite = new Label("Tope de lo que puede deber. Pasarlo, o cambiar el límite de un cliente que ya existe, "
                + "pide la autorización de un administrador.");
        ayudaLimite.getStyleClass().add("hint");
        ayudaLimite.setWrapText(true);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Nombre:"), txtNombre);
        grid.addRow(1, new Label("Teléfono:"), txtTelefono);
        grid.addRow(2, new Label("Límite de crédito: " + Moneda.simbolo()), txtLimite);
        grid.add(ayudaLimite, 0, 3, 2, 1);
        grid.addRow(4, new Label("Nota:"), txtNota);
        grid.add(chkActivo, 1, 5);
        GridPane.setHgrow(txtNombre, Priority.ALWAYS);
        grid.setPrefWidth(460);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dlg.getDialogPane().lookupButton(ButtonType.OK)).setText("Guardar");
        Platform.runLater(txtNombre::requestFocus);
        if (dlg.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        double limite = CalculoCobro.parsearMonto(txtLimite.getText(), 0);
        if (Double.isNaN(limite)) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Límite de crédito", "Escribe el límite como número (ej: 500) o déjalo vacío.");
            return;
        }
        Long id = original != null ? original.getId() : null;
        conAutorizacion("No se pudo guardar el cliente", auts -> cuentaService.guardar(id, txtNombre.getText(),
                txtTelefono.getText(), txtNota.getText(), limite, chkActivo.isSelected(), auts))
                .ifPresent(c -> { recargar(); seleccionar(c.getId()); });
    }

    private void seleccionar(Long id) {
        clientes.stream().filter(c -> c.getId().equals(id)).findFirst().ifPresent(c -> {
            tablaClientes.getSelectionModel().select(c);
            tablaClientes.scrollTo(c);
        });
    }

    // ── Abonos ───────────────────────────────────────────────────────────

    @FXML void onAbono() {
        Cliente c = seleccionado("Registrar abono");
        if (c == null) return;
        if (c.getSaldoDeuda() <= 0.004) {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Registrar abono", "«" + c.getNombre() + "» no debe nada.");
            return;
        }
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Registrar abono");
        dlg.setHeaderText(c.getNombre() + "\nDebe: " + Moneda.formato(c.getSaldoDeuda()));
        if (ventana() != null) dlg.initOwner(ventana());
        Ventanas.estilizar(dlg);
        TextField txtMonto = new TextField();
        txtMonto.setPromptText("0.00");
        Button btnTodo = new Button("Todo (" + Moneda.formato(c.getSaldoDeuda()) + ")");
        btnTodo.getStyleClass().addAll("button-ghost", "button-sm");
        btnTodo.setOnAction(e -> txtMonto.setText(CalculoCobro.formatoCampo(c.getSaldoDeuda())));
        ComboBox<Venta.FormaPago> cmbForma = new ComboBox<>(FXCollections.observableArrayList(
                Venta.FormaPago.EFECTIVO, Venta.FormaPago.TRANSFERENCIA, Venta.FormaPago.TARJETA));
        cmbForma.getSelectionModel().select(Venta.FormaPago.EFECTIVO);
        cmbForma.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Venta.FormaPago f) { return f == null ? "" : f.getEtiqueta(); }
            @Override public Venta.FormaPago fromString(String s) { return null; }
        });
        TextField txtNota = new TextField();
        txtNota.setPromptText("Nota (opcional)");
        Label lblQueda = new Label();
        lblQueda.getStyleClass().add("hint");
        Label lblForma = new Label();
        lblForma.getStyleClass().add("hint");
        lblForma.setWrapText(true);
        Runnable calcular = () -> {
            double m = CalculoCobro.parsearMonto(txtMonto.getText(), 0);
            lblQueda.setText(Double.isNaN(m) || m <= 0 ? "" : m > c.getSaldoDeuda() + 0.004
                    ? "Es más de lo que debe." : "Quedará debiendo " + Moneda.formato(Math.max(0, c.getSaldoDeuda() - m)) + ".");
            lblForma.setText(cmbForma.getValue() == Venta.FormaPago.EFECTIVO
                    ? "El efectivo entra al cajón y cuenta en el corte." : "No entra efectivo al cajón.");
        };
        txtMonto.textProperty().addListener((o, a, b) -> calcular.run());
        cmbForma.valueProperty().addListener((o, a, b) -> calcular.run());
        calcular.run();
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        HBox filaMonto = new HBox(6, txtMonto, btnTodo);
        HBox.setHgrow(txtMonto, Priority.ALWAYS);
        grid.addRow(0, new Label("Abona: " + Moneda.simbolo()), filaMonto);
        grid.add(lblQueda, 1, 1);
        grid.addRow(2, new Label("Cómo paga:"), cmbForma);
        grid.add(lblForma, 1, 3);
        grid.addRow(4, new Label("Nota:"), txtNota);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dlg.getDialogPane().lookupButton(ButtonType.OK)).setText("Registrar abono");
        Platform.runLater(txtMonto::requestFocus);
        if (dlg.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        double monto = CalculoCobro.parsearMonto(txtMonto.getText(), 0);
        if (Double.isNaN(monto) || monto <= 0) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Registrar abono", "Escribe cuánto abona el cliente (ej: 200).");
            return;
        }
        CuentaClienteService.Abono abono;
        try {
            abono = cuentaService.registrarAbono(c.getId(), monto, cmbForma.getValue(), txtNota.getText());
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se registró el abono", e.getMessage());
            return;
        }
        recargar();
        seleccionar(c.getId());
        String texto = String.format("Abono de %s registrado.\n\n%s %s.", Moneda.formato(abono.monto()), c.getNombre(),
                abono.saldoDespues() <= 0.004 ? "ya no debe nada" : "queda debiendo " + Moneda.formato(abono.saldoDespues()));
        if (impresionService.hayImpresora()) {
            if (Ventanas.confirmar("Abono registrado", texto + "\n\n¿Imprimir el comprobante para el cliente?")) {
                imprimir(() -> ticketService.documentoAbono(abono.movimiento(), c.getNombre(), configuracionService.obtener()),
                        "CLIENTE_ABONO_IMPRESO", "Abono A-" + abono.movimiento().getId());
            }
        } else {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Abono registrado", texto);
        }
    }

    private void imprimir(Supplier<me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion> documento,
                          String accion, String detalle) {
        try {
            impresionService.imprimirDocumentoAsync(documento.get(), accion, detalle)
                    .whenComplete((res, error) -> Platform.runLater(() -> {
                        if (error != null || res == null || res.esFallo()) {
                            Ventanas.alerta(Alert.AlertType.WARNING, "Imprimir", res != null ? res.mensaje() : "No se pudo imprimir.");
                        }
                    }));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Imprimir", e.getMessage());
        }
    }

    // ── Ajuste de saldo ──────────────────────────────────────────────────

    @FXML void onAjustar() {
        Cliente c = seleccionado("Ajustar saldo");
        if (c == null) return;
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Ajustar saldo");
        dlg.setHeaderText(c.getNombre() + " — debe " + textoSaldo(c.getSaldoDeuda())
                + "\nPara deudas de antes del sistema o corregir un error. Queda en la bitácora.");
        if (ventana() != null) dlg.initOwner(ventana());
        Ventanas.estilizar(dlg);
        ToggleGroup grupo = new ToggleGroup();
        RadioButton rbAumentar = new RadioButton("Aumentar lo que debe");
        RadioButton rbDisminuir = new RadioButton("Disminuir lo que debe");
        rbAumentar.setToggleGroup(grupo);
        rbDisminuir.setToggleGroup(grupo);
        rbAumentar.setSelected(true);
        TextField txtMonto = new TextField();
        txtMonto.setPromptText("0.00");
        TextField txtMotivo = new TextField();
        txtMotivo.setPromptText("Motivo (obligatorio), ej. «Deuda anterior de la libreta»");
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new HBox(12, rbAumentar, rbDisminuir), 0, 0, 2, 1);
        grid.addRow(1, new Label("Monto: " + Moneda.simbolo()), txtMonto);
        grid.addRow(2, new Label("Motivo:"), txtMotivo);
        GridPane.setHgrow(txtMotivo, Priority.ALWAYS);
        grid.setPrefWidth(460);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dlg.getDialogPane().lookupButton(ButtonType.OK)).setText("Ajustar");
        Platform.runLater(txtMonto::requestFocus);
        if (dlg.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        double monto = CalculoCobro.parsearMonto(txtMonto.getText(), 0);
        if (Double.isNaN(monto) || monto <= 0) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Ajustar saldo", "Escribe el monto del ajuste (ej: 150).");
            return;
        }
        double conSigno = rbAumentar.isSelected() ? monto : -monto;
        conAutorizacion("No se pudo ajustar", auts -> cuentaService.ajustar(c.getId(), conSigno, txtMotivo.getText(), auts))
                .ifPresent(m -> {
                    recargar();
                    seleccionar(c.getId());
                    Ventanas.alerta(Alert.AlertType.INFORMATION, "Saldo ajustado",
                            c.getNombre() + " ahora debe " + textoSaldo(m.getSaldoDespues()) + ".");
                });
    }

    // ── Estado de cuenta ─────────────────────────────────────────────────

    @FXML void onEstadoCuenta() {
        Cliente c = seleccionado("Estado de cuenta");
        if (c != null) estadoDeCuenta(c);
    }

    private void estadoDeCuenta(Cliente cliente) {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Estado de cuenta");
        dlg.setHeaderText(cliente.getNombre() + (cliente.getTelefono() != null ? "  ·  " + cliente.getTelefono() : ""));
        if (ventana() != null) dlg.initOwner(ventana());
        Ventanas.estilizar(dlg);

        DatePicker dpDesde = new DatePicker(LocalDate.now().minusDays(90));
        DatePicker dpHasta = new DatePicker(LocalDate.now());
        CheckBox chkTodo = new CheckBox("Todo el historial");
        TableView<CuentaClienteMovimiento> tabla = new TableView<>();
        tabla.setPlaceholder(new Label("Sin movimientos en el periodo."));
        tabla.getColumns().add(columna("Fecha", 120, m -> m.getFecha().format(FMT)));
        tabla.getColumns().add(columna("Movimiento", 120, CuentaClienteMovimiento::getTipoEtiqueta));
        tabla.getColumns().add(columna("Concepto", 220, m -> m.getConcepto() != null ? m.getConcepto() : ""));
        tabla.getColumns().add(columna("Cargo", 90, m -> m.getMonto() > 0 ? Moneda.formato(m.getMonto()) : ""));
        tabla.getColumns().add(columna("Abono", 90, m -> m.getMonto() < 0 ? Moneda.formato(-m.getMonto()) : ""));
        tabla.getColumns().add(columna("Saldo", 95, m -> textoSaldo(m.getSaldoDespues())));
        tabla.getColumns().add(columna("Atendió", 120, m -> m.getCajeroNombre() != null ? m.getCajeroNombre() : ""));
        tabla.setPrefHeight(320);
        Label lblResumen = new Label();
        lblResumen.setWrapText(true);
        final CuentaClienteService.EstadoCuenta[] actual = { null };
        Runnable cargar = () -> {
            dpDesde.setDisable(chkTodo.isSelected());
            dpHasta.setDisable(chkTodo.isSelected());
            try {
                CuentaClienteService.EstadoCuenta e = chkTodo.isSelected()
                        ? cuentaService.estadoDeCuenta(cliente.getId(), null, null)
                        : cuentaService.estadoDeCuenta(cliente.getId(), dpDesde.getValue(), dpHasta.getValue());
                actual[0] = e;
                tabla.getItems().setAll(e.movimientos());
                lblResumen.setText((e.desde() != null ? "Saldo anterior: " + textoSaldo(e.saldoInicial()) + "   ·   " : "")
                        + "Cargos: " + Moneda.formato(e.cargos()) + "   ·   Abonos: " + Moneda.formato(e.abonos())
                        + "   ·   Saldo: " + textoSaldo(e.saldoFinal()));
            } catch (NegocioException ex) {
                lblResumen.setText(ex.getMessage());
            }
        };
        dpDesde.valueProperty().addListener((o, a, b) -> cargar.run());
        dpHasta.valueProperty().addListener((o, a, b) -> cargar.run());
        chkTodo.setOnAction(e -> cargar.run());
        cargar.run();

        Button btnImprimir = new Button("Imprimir");
        btnImprimir.setOnAction(e -> {
            if (actual[0] == null) return;
            CuentaClienteService.EstadoCuenta ec = actual[0];
            imprimir(() -> ticketService.documentoEstadoCuenta(ec, configuracionService.obtener()),
                    "CLIENTE_ESTADO_CUENTA_IMPRESO", "Cliente #" + cliente.getId() + " " + cliente.getNombre());
        });
        btnImprimir.setDisable(!impresionService.hayImpresora());
        Button btnExportar = new Button("Exportar");
        btnExportar.setOnAction(e -> ExportadorTablas.exportar(tabla, "estado-de-cuenta",
                "Estado de cuenta — " + cliente.getNombre()));
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox filtros = new HBox(8, new Label("Del:"), dpDesde, new Label("al:"), dpHasta, chkTodo, espacio, btnImprimir, btnExportar);
        filtros.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        dpDesde.setPrefWidth(130);
        dpHasta.setPrefWidth(130);
        VBox contenido = new VBox(10, filtros, tabla, lblResumen);
        contenido.setPadding(new Insets(10));
        contenido.setPrefWidth(900);
        dlg.getDialogPane().setContent(contenido);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.setResizable(true);
        dlg.showAndWait();
    }

    private static TableColumn<CuentaClienteMovimiento, String> columna(String titulo, double ancho,
            java.util.function.Function<CuentaClienteMovimiento, String> valor) {
        TableColumn<CuentaClienteMovimiento, String> col = new TableColumn<>(titulo);
        col.setPrefWidth(ancho);
        col.setCellValueFactory(c -> new SimpleStringProperty(valor.apply(c.getValue())));
        return col;
    }
}
