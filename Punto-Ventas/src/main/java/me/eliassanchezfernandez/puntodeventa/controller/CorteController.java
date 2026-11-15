package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import me.eliassanchezfernandez.puntodeventa.model.Corte;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Categoria;
import me.eliassanchezfernandez.puntodeventa.model.ProductoComun;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.service.CajaService;
import me.eliassanchezfernandez.puntodeventa.service.IdentidadCaja;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.CalculoIva;
import me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Function;

/**
 * Controlador del Corte de Caja (CRT-06, CRT-08, CRT-09).
 *
 * Muestra el estado del efectivo de ESTA caja en dos modos: el <b>turno
 * actual</b> (desde el corte anterior: lo que debe haber ahora en el cajón) y
 * el <b>día completo</b> (reporte acumulado). Desde aquí se registran entradas
 * y salidas de efectivo, se guarda el corte (contado, lo que queda en caja y
 * faltante/sobrante), se consulta el historial de cortes y se imprime o
 * exporta. La ganancia solo se muestra a quien tiene VER_GANANCIAS (CRT-11).
 */
@Component
@Scope("prototype")
public class CorteController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter FMT_LARGA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired private CajaService   cajaService;
    @Autowired private SesionService sesionService;
    @Autowired private ImpresionService impresionService;
    @Autowired private IdentidadCaja identidadCaja;
    @Autowired private MovimientoCajaDialogo movimientoCajaDialogo;

    // Barra
    @FXML private Button btnHacerCorte;
    @FXML private Button btnEntrada;
    @FXML private Button btnSalida;
    @FXML private Button btnCorregirFondo;
    @FXML private Button btnCorteGeneral;
    @FXML private Label  lblCaja;
    @FXML private ToggleButton tglTurno;
    @FXML private ToggleButton tglDia;
    @FXML private Label  lblPeriodo;
    @FXML private Label  lblUltimoCorte;
    // Efectivo en caja
    @FXML private Label lblTituloEfectivo;
    @FXML private Label lblInicialTitulo;
    @FXML private Label lblEfectivoInicial;
    @FXML private Label lblVentasEfectivo;
    @FXML private Label lblEntradas;
    @FXML private Label lblSalidas;
    @FXML private Label lblRetirosTitulo;
    @FXML private Label lblRetiros;
    @FXML private Label lblTotalCaja;
    @FXML private Label lblFondoNota;
    // Entradas y salidas
    @FXML private Label lblCambio;
    @FXML private Label lblOtrasEntradasTitulo;
    @FXML private Label lblOtrasEntradas;
    @FXML private Label lblRetiro;
    @FXML private Label lblPagoProveedores;
    @FXML private Label lblGastos;
    @FXML private Label lblDevolucionesTitulo;
    @FXML private Label lblDevoluciones;
    @FXML private Label lblDevArticulosTitulo;
    @FXML private Label lblDevArticulos;
    @FXML private Label lblOtrasSalidasTitulo;
    @FXML private Label lblOtrasSalidas;
    @FXML private Label lblNetoMovimientos;
    // Pagos de contado
    @FXML private Label lblEfectivo;
    @FXML private Label lblTarjeta;
    @FXML private Label lblTransferenciaTitulo;
    @FXML private Label lblTransferencia;
    @FXML private Label lblValesTitulo;
    @FXML private Label lblVales;
    @FXML private Label lblSubtotal;
    @FXML private Label lblIva;
    @FXML private Label lblIvaPorTasaTitulo;
    @FXML private Label lblIvaPorTasa;
    @FXML private Label lblCanceladas;
    // Totales
    @FXML private Label lblVentasTotalesTitulo;
    @FXML private Label lblVentasTotales;
    @FXML private Label lblGanancia;
    @FXML private Label lblGananciaTitulo;
    @FXML private Label lblGananciaComunes;

    @FXML private TableView<Map.Entry<String, Double>>           tablaDepartamentos;
    @FXML private TableColumn<Map.Entry<String, Double>, String> colDepto;
    @FXML private TableColumn<Map.Entry<String, Double>, String> colMonto;

    @FXML private TableView<MovimientoCaja>           tablaMovimientos;
    @FXML private TableColumn<MovimientoCaja, String> colMovHora;
    @FXML private TableColumn<MovimientoCaja, String> colMovTipo;
    @FXML private TableColumn<MovimientoCaja, String> colMovCategoria;
    @FXML private TableColumn<MovimientoCaja, String> colMovConcepto;
    @FXML private TableColumn<MovimientoCaja, String> colMovMonto;
    @FXML private TableColumn<MovimientoCaja, String> colMovCajero;
    @FXML private TableColumn<MovimientoCaja, String> colMovAutorizo;

    @FXML private DatePicker dpDesde;
    @FXML private DatePicker dpHasta;
    @FXML private TableView<Corte>           tablaCortes;
    @FXML private TableColumn<Corte, String> colCorteFecha;
    @FXML private TableColumn<Corte, String> colCortePeriodo;
    @FXML private TableColumn<Corte, String> colCorteCaja;
    @FXML private TableColumn<Corte, String> colCorteCajero;
    @FXML private TableColumn<Corte, String> colCorteInicial;
    @FXML private TableColumn<Corte, String> colCorteVentas;
    @FXML private TableColumn<Corte, String> colCorteEntradas;
    @FXML private TableColumn<Corte, String> colCorteSalidas;
    @FXML private TableColumn<Corte, String> colCorteEsperado;
    @FXML private TableColumn<Corte, String> colCorteContado;
    @FXML private TableColumn<Corte, String> colCorteDiferencia;
    @FXML private TableColumn<Corte, String> colCorteDejado;
    @FXML private TableColumn<Corte, String> colCorteObs;

    // Cobros con tarjeta (voucher capturado en Ventas)
    @FXML private Label lblTarjetaTerminales;
    @FXML private TableView<VentaService.VoucherTarjeta>           tablaVouchers;
    @FXML private TableColumn<VentaService.VoucherTarjeta, String> colVHora;
    @FXML private TableColumn<VentaService.VoucherTarjeta, String> colVFolio;
    @FXML private TableColumn<VentaService.VoucherTarjeta, String> colVTerminal;
    @FXML private TableColumn<VentaService.VoucherTarjeta, String> colVTarjeta;
    @FXML private TableColumn<VentaService.VoucherTarjeta, String> colVAut;
    @FXML private TableColumn<VentaService.VoucherTarjeta, String> colVMonto;

    @FXML private TableView<Producto>           tablaBajos;
    @FXML private TableColumn<Producto, String> colBajoDescripcion;
    @FXML private TableColumn<Producto, String> colBajoActual;
    @FXML private TableColumn<Producto, String> colBajoMinimo;

    private final ObservableList<Map.Entry<String, Double>> porDepartamento = FXCollections.observableArrayList();
    private final ObservableList<Producto> bajos = FXCollections.observableArrayList();
    private final ObservableList<VentaService.VoucherTarjeta> vouchers = FXCollections.observableArrayList();
    private final ObservableList<MovimientoCaja> movimientos = FXCollections.observableArrayList();
    private final ObservableList<Corte> cortes = FXCollections.observableArrayList();

    /** Último estado cargado (para los diálogos) */
    private CajaService.EstadoCaja estado;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colDepto.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getKey()));
        colMonto.setCellValueFactory(c -> new SimpleStringProperty(moneda(c.getValue().getValue())));
        tablaDepartamentos.setItems(porDepartamento);
        tablaDepartamentos.setPlaceholder(new Label("Sin ventas en el periodo."));

        colBajoDescripcion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDescripcion()));
        colBajoActual.setCellValueFactory(c -> new SimpleStringProperty(cantidad(c.getValue().getCantidadActual())));
        colBajoMinimo.setCellValueFactory(c -> new SimpleStringProperty(cantidad(c.getValue().getCantidadMinima())));
        tablaBajos.setItems(bajos);
        tablaBajos.setPlaceholder(new Label("Ningún producto está en o por debajo de su mínimo."));

        colVHora.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fecha().format(HORA)));
        colVFolio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().folio()));
        colVTerminal.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().terminal() != null ? c.getValue().terminal() : "—"));
        colVTarjeta.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ultimos4() != null ? "****" + c.getValue().ultimos4() : "—"));
        colVAut.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().autorizacion() != null ? c.getValue().autorizacion() : "—"));
        colVMonto.setCellValueFactory(c -> new SimpleStringProperty(moneda(c.getValue().monto())));
        tablaVouchers.setItems(vouchers);
        tablaVouchers.setPlaceholder(new Label("Sin cobros con tarjeta en el periodo."));

        colMovHora.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFecha().format(
                c.getValue().getFecha().toLocalDate().equals(LocalDate.now()) ? HORA : FMT)));
        colMovTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTipo().getEtiqueta()));
        colMovCategoria.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCategoria().getEtiqueta()));
        colMovConcepto.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getConcepto()
                + (c.getValue().getProveedorNombre() != null ? " [" + c.getValue().getProveedorNombre() + "]" : "")));
        colMovMonto.setCellValueFactory(c -> new SimpleStringProperty((c.getValue().esEntrada() ? "+" : "−") + moneda(c.getValue().getMonto())));
        colMovCajero.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCajeroNombre() != null ? c.getValue().getCajeroNombre() : "—"));
        colMovAutorizo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getAutorizadoPor() != null ? c.getValue().getAutorizadoPor() : ""));
        tablaMovimientos.setItems(movimientos);
        tablaMovimientos.setPlaceholder(new Label("Sin entradas ni salidas de efectivo en el periodo."));

        colCorteFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRealizadoEn().format(FMT_LARGA)));
        colCortePeriodo.setCellValueFactory(c -> new SimpleStringProperty(periodoDe(c.getValue())));
        colCorteCaja.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getEtiquetaCaja()));
        colCorteCajero.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCajeroNombre()));
        colCorteInicial.setCellValueFactory(dinero(Corte::getEfectivoInicialOFondo));
        colCorteVentas.setCellValueFactory(dinero(Corte::getVentasEfectivo));
        colCorteEntradas.setCellValueFactory(dinero(Corte::getEntradasOCero));
        colCorteSalidas.setCellValueFactory(dinero(Corte::getSalidasOCero));
        colCorteEsperado.setCellValueFactory(dinero(Corte::getEfectivoEsperado));
        colCorteContado.setCellValueFactory(dinero(Corte::getEfectivoContado));
        colCorteDiferencia.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().hayFaltante() ? "Faltan " : c.getValue().haySobrante() ? "Sobran " : "") + moneda(Math.abs(c.getValue().getDiferencia()))));
        colCorteDejado.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getEfectivoDejado() != null ? moneda(c.getValue().getEfectivoDejado()) : "—"));
        colCorteObs.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getObservaciones() != null ? c.getValue().getObservaciones() : ""));
        tablaCortes.setItems(cortes);
        tablaCortes.setPlaceholder(new Label("No hay cortes guardados en esas fechas."));
        dpDesde.setValue(LocalDate.now());
        dpHasta.setValue(LocalDate.now());

        boolean veGanancia = sesionService.tienePermiso(Permiso.VER_GANANCIAS);
        lblGanancia.setVisible(veGanancia);
        lblGanancia.setManaged(veGanancia);
        lblGananciaTitulo.setVisible(veGanancia);
        lblGananciaTitulo.setManaged(veGanancia);
        btnHacerCorte.setDisable(!sesionService.tienePermiso(Permiso.HACER_CORTE));
        boolean admin = sesionService.esAdmin();
        btnCorregirFondo.setVisible(admin);
        btnCorregirFondo.setManaged(admin);
        // El corte general suma todas las cajas: solo tiene sentido con varias cajas y solo lo hace el ADMIN
        boolean general = admin && identidadCaja.esMultiCaja();
        btnCorteGeneral.setVisible(general);
        btnCorteGeneral.setManaged(general);
        lblCaja.setText(identidadCaja.nombre() + (identidadCaja.esMultiCaja() ? " (caja " + identidadCaja.numero() + ")" : ""));
        btnHacerCorte.setText(identidadCaja.esMultiCaja() ? "Hacer corte de esta caja" : "Hacer corte");
        ToggleGroup modo = new ToggleGroup();
        tglTurno.setToggleGroup(modo);
        tglDia.setToggleGroup(modo);
        modo.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); });
        boolean mac = me.eliassanchezfernandez.puntodeventa.util.Atajo.esMac();
        btnEntrada.setTooltip(new Tooltip("Meter dinero al cajón (cambio, morralla). En Ventas: "
                + me.eliassanchezfernandez.puntodeventa.util.Atajo.MOVIMIENTO_EFECTIVO.etiquetaCorta(mac)));
        btnSalida.setTooltip(new Tooltip("Sacar dinero del cajón (retiro, pago a proveedor, gasto). En Ventas: "
                + me.eliassanchezfernandez.puntodeventa.util.Atajo.MOVIMIENTO_EFECTIVO.etiquetaCorta(mac)));

        // main.fxml incluye esta vista para todos; sin permiso no hay nada que calcular
        // (MainController quita la pestaña) y no debe aparecer ningún aviso.
        if (sesionService.tienePermiso(Permiso.HACER_CORTE) || veGanancia) {
            recargar();
        }
    }

    private static javafx.util.Callback<TableColumn.CellDataFeatures<Corte, String>, javafx.beans.value.ObservableValue<String>> dinero(Function<Corte, Double> f) {
        return c -> new SimpleStringProperty(moneda(f.apply(c.getValue())));
    }

    private static String periodoDe(Corte c) {
        if (c.isGeneral()) return "Día (todas)";
        LocalDateTime inicio = c.getInicioOInicioDelDia();
        boolean mismoDia = inicio.toLocalDate().equals(c.getRealizadoEn().toLocalDate());
        return inicio.format(mismoDia ? HORA : FMT) + " – " + c.getRealizadoEn().format(HORA);
    }

    private boolean modoTurno() {
        return tglTurno.isSelected();
    }

    @FXML
    void onCambiarModo() {
        recargar();
    }

    // ── Entradas y salidas de efectivo ───────────────────────────────────

    @FXML
    void onEntradaEfectivo() {
        movimientoCajaDialogo.mostrar(ventana(), MovimientoCajaDialogo.Prellenado.entrada()).ifPresent(m -> recargar());
    }

    @FXML
    void onSalidaEfectivo() {
        movimientoCajaDialogo.mostrar(ventana(), MovimientoCajaDialogo.Prellenado.salida()).ifPresent(m -> recargar());
    }

    @FXML
    void onImprimirComprobante() {
        MovimientoCaja m = tablaMovimientos.getSelectionModel().getSelectedItem();
        if (m == null) {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Comprobante", "Selecciona un movimiento de la tabla para imprimir su comprobante.");
            return;
        }
        movimientoCajaDialogo.imprimir(m.getId());
    }

    @FXML
    void onExportarMovimientos() {
        ExportadorTablas.exportar(tablaMovimientos, "movimientos-efectivo", "Movimientos de efectivo");
    }

    // ── Cortes guardados ─────────────────────────────────────────────────

    @FXML
    void onBuscarCortes() {
        try {
            cortes.setAll(cajaService.cortesEntre(dpDesde.getValue(), dpHasta.getValue()));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Cortes guardados", e.getMessage());
        }
    }

    @FXML
    void onReimprimirCorte() {
        Corte c = tablaCortes.getSelectionModel().getSelectedItem();
        if (c == null) {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Reimprimir corte", "Selecciona un corte de la tabla.");
            return;
        }
        imprimirGuardado(c);
    }

    private void imprimirGuardado(Corte c) {
        try {
            impresionService.imprimirCorteGuardadoAsync(c).whenComplete((res, error) -> Platform.runLater(() -> {
                if (error != null || res == null || res.esFallo()) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Imprimir corte",
                            res != null ? res.mensaje() : "No se pudo imprimir el corte.");
                }
            }));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Imprimir corte", e.getMessage());
        }
    }

    @FXML
    void onExportarCortes() {
        ExportadorTablas.exportar(tablaCortes, "cortes-de-caja", "Cortes de caja");
    }

    // ── Fondo ────────────────────────────────────────────────────────────

    /** Corrección del fondo del día (solo ADMIN, con motivo; queda como registro nuevo) */
    @FXML
    void onCorregirFondo() {
        Dialog<String[]> dlg = new Dialog<>();
        dlg.setTitle("Corregir fondo de caja");
        dlg.setHeaderText("Fondo vigente de hoy: " + moneda(estado != null ? estado.fondo : cajaService.fondoVigenteDelDia(LocalDate.now()))
                + "\nEl fondo anterior se conserva en la bitácora.");
        Ventanas.estilizar(dlg);
        TextField txtMonto = new TextField();
        txtMonto.setPromptText("0.00");
        TextArea txtMotivo = new TextArea();
        txtMotivo.setPromptText("Motivo de la corrección (obligatorio)");
        txtMotivo.setPrefRowCount(2);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Fondo correcto: " + Moneda.simbolo()), txtMonto);
        grid.add(txtMotivo, 0, 1, 2, 1);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? new String[] { txtMonto.getText(), txtMotivo.getText() } : null);
        Platform.runLater(txtMonto::requestFocus);

        Optional<String[]> res = dlg.showAndWait();
        if (res.isEmpty()) return;
        Double monto = numero(res.get()[0], "Escribe el fondo como número (ej: 500).");
        if (monto == null) return;
        try {
            cajaService.corregirFondo(monto, res.get()[1]);
            recargar();
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Fondo corregido", "El fondo de hoy ahora es " + moneda(monto) + ".");
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo corregir", e.getMessage());
        }
    }

    // ── Hacer corte ──────────────────────────────────────────────────────

    /** Pide el efectivo contado y lo que queda en caja, y guarda el corte del turno (HACER_CORTE) */
    @FXML
    void onHacerCorte() {
        CajaService.EstadoCaja turno;
        try {
            turno = cajaService.estadoTurnoActual();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Corte", e.getMessage());
            return;
        }
        Dialog<String[]> dlg = new Dialog<>();
        dlg.setTitle("Hacer corte de caja");
        String desde = turno.corteAnterior != null
                ? "desde el corte de las " + turno.inicio.format(turno.inicio.toLocalDate().equals(LocalDate.now()) ? HORA : FMT)
                : "desde el inicio del día";
        dlg.setHeaderText(String.format("Turno %s.\nEfectivo esperado en el cajón: %s\n(%s %s + ventas en efectivo %s + entradas %s − salidas %s)\n\n"
                        + "Cuenta el efectivo, captura el total y cuánto dejas en caja para el siguiente turno.",
                desde, moneda(turno.esperado()),
                turno.efectivoInicialEsFondo ? "fondo" : "dejado por el corte anterior", moneda(turno.efectivoInicial),
                moneda(turno.ventas.efectivo), moneda(turno.entradas), moneda(turno.salidas)));
        Ventanas.estilizar(dlg);
        TextField txtContado = new TextField();
        txtContado.setPromptText("0.00");
        TextField txtDejado = new TextField(String.format("%.2f", turno.fondo));
        txtDejado.setPromptText("0.00");
        Label lblRetiro = new Label("Retiro: —");
        lblRetiro.getStyleClass().add("hint");
        Runnable calcular = () -> {
            try {
                double c = CalculoCobro.parsearNumero(txtContado.getText());
                double d = CalculoCobro.parsearNumero(txtDejado.getText());
                if (Double.isNaN(c) || Double.isNaN(d)) throw new NumberFormatException();
                double dif = c - turno.esperado();
                lblRetiro.setText(String.format("Retiro: %s   ·   %s", moneda(c - d),
                        dif < -0.005 ? "FALTANTE de " + moneda(-dif) : dif > 0.005 ? "SOBRANTE de " + moneda(dif) : "sin diferencia"));
            } catch (NumberFormatException e) {
                lblRetiro.setText("Retiro: —");
            }
        };
        txtContado.textProperty().addListener((o, a, b) -> calcular.run());
        txtDejado.textProperty().addListener((o, a, b) -> calcular.run());
        TextArea txtObs = new TextArea();
        txtObs.setPromptText("Observaciones (opcional)");
        txtObs.setPrefRowCount(2);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Efectivo contado: " + Moneda.simbolo()), txtContado);
        grid.addRow(1, new Label("Queda en caja: " + Moneda.simbolo()), txtDejado);
        grid.add(lblRetiro, 0, 2, 2, 1);
        grid.add(txtObs, 0, 3, 2, 1);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? new String[] { txtContado.getText(), txtDejado.getText(), txtObs.getText() } : null);
        Platform.runLater(txtContado::requestFocus);

        Optional<String[]> res = dlg.showAndWait();
        if (res.isEmpty()) return;
        Double contado = numero(res.get()[0], "Escribe el efectivo contado como número (ej: 1250.50).");
        if (contado == null) return;
        Double dejado = numero(res.get()[1], "Escribe lo que queda en caja como número (ej: 500).");
        if (dejado == null) return;
        try {
            Corte c = cajaService.hacerCorte(contado, dejado, res.get()[2]);
            recargar();
            String dif = c.hayFaltante() ? "FALTANTE de " + moneda(-c.getDiferencia())
                       : c.haySobrante() ? "SOBRANTE de " + moneda(c.getDiferencia())
                       : "sin diferencia";
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Corte guardado", String.format(
                    "Corte #%d registrado por %s.\n\nEsperado: %s\nContado:  %s\nResultado: %s\nQueda en caja: %s   Retiro: %s",
                    c.getId(), c.getCajeroNombre(), moneda(c.getEfectivoEsperado()), moneda(c.getEfectivoContado()), dif,
                    moneda(c.getEfectivoDejadoOFondo()), moneda(c.getRetiro())));
            if (impresionService.hayImpresora() && Ventanas.confirmar("Imprimir corte", "¿Imprimir el corte #" + c.getId() + "?")) {
                imprimirGuardado(c);
            }
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar el corte", e.getMessage());
        }
    }

    /** Corte general del día (solo ADMIN, varias cajas): lo que debe haber ahora en todos los cajones */
    @FXML
    void onCorteGeneral() {
        CajaService.EstadoCaja g;
        try {
            g = cajaService.estadoGeneralDelDia();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Corte general", e.getMessage());
            return;
        }
        StringBuilder detalle = new StringBuilder();
        for (VentaService.VentasCaja vc : g.ventas.porCaja) {
            detalle.append("Caja ").append(vc.caja()).append(": ").append(vc.ventas()).append(" venta(s), ")
                   .append(moneda(vc.total())).append('\n');
        }
        cajaService.fondosPorCaja(LocalDate.now()).forEach((caja, fondo) ->
                detalle.append("Fondo caja ").append(caja).append(": ").append(moneda(fondo)).append('\n'));
        if (g.retiros != 0) detalle.append("Ya retirado en cortes de turno: ").append(moneda(g.retiros)).append('\n');

        Dialog<String[]> dlg = new Dialog<>();
        dlg.setTitle("Corte general (todas las cajas)");
        dlg.setHeaderText("Ventas del día de todas las cajas: " + moneda(g.ventas.ventasTotales) + " (" + g.ventas.numVentas + " ventas)\n"
                + "Efectivo que debe haber ahora en todos los cajones: " + moneda(g.esperado())
                + "\n\nCuenta el efectivo de TODAS las cajas y captura el total.");
        Ventanas.estilizar(dlg);
        TextField txtContado = new TextField();
        txtContado.setPromptText("0.00");
        TextArea txtObs = new TextArea();
        txtObs.setPromptText("Observaciones (opcional)");
        txtObs.setPrefRowCount(2);
        Label lblDetalle = new Label(detalle.length() == 0 ? "Hoy no hay ventas ni fondos registrados." : detalle.toString().trim());
        lblDetalle.getStyleClass().add("hint");
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(lblDetalle, 0, 0, 2, 1);
        grid.addRow(1, new Label("Efectivo contado (todas): " + Moneda.simbolo()), txtContado);
        grid.add(txtObs, 0, 2, 2, 1);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? new String[] { txtContado.getText(), txtObs.getText() } : null);
        Platform.runLater(txtContado::requestFocus);

        Optional<String[]> res = dlg.showAndWait();
        if (res.isEmpty()) return;
        Double contado = numero(res.get()[0], "Escribe el efectivo contado como número (ej: 1250.50).");
        if (contado == null) return;
        try {
            Corte c = cajaService.hacerCorteGeneral(contado, res.get()[1]);
            recargar();
            String dif = c.hayFaltante() ? "FALTANTE de " + moneda(-c.getDiferencia())
                       : c.haySobrante() ? "SOBRANTE de " + moneda(c.getDiferencia())
                       : "sin diferencia";
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Corte general guardado", String.format(
                    "Corte general #%d registrado por %s.\n\nEsperado: %s\nContado:  %s\nResultado: %s",
                    c.getId(), c.getCajeroNombre(), moneda(c.getEfectivoEsperado()), moneda(c.getEfectivoContado()), dif));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar el corte general", e.getMessage());
        }
    }

    /** Imprime el reporte del modo visible (turno o día) en la impresora de tickets (fuera del hilo de JavaFX) */
    @FXML
    void onImprimirCorte() {
        try {
            impresionService.imprimirCorteAsync(modoTurno()).whenComplete((res, error) -> Platform.runLater(() -> {
                if (error != null || res == null || res.esFallo()) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Imprimir corte",
                            res != null ? res.mensaje() : "No se pudo imprimir el corte.");
                }
            }));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Imprimir corte", e.getMessage());
        }
    }

    // ── Recarga ──────────────────────────────────────────────────────────

    /** Recalcula el estado (lo llama MainController al entrar a la pestaña) */
    public void recargar() {
        CajaService.EstadoCaja e;
        try {
            e = modoTurno() ? cajaService.estadoTurnoActual() : cajaService.estadoDelDia();
        } catch (NegocioException ex) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Corte", ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            // Un dato inconsistente en la BD no debe impedir abrir la ventana principal
            ex.printStackTrace();
            Ventanas.alerta(Alert.AlertType.ERROR, "Corte", "No se pudo calcular el resumen: " + ex.getMessage());
            return;
        }
        estado = e;
        VentaService.ResumenCorte r = e.ventas;
        boolean turno = e.turno;
        String que = turno ? "del turno" : "del día";

        boolean inicioHoy = e.inicio.toLocalDate().equals(LocalDate.now());
        lblPeriodo.setText(turno
                ? (e.corteAnterior != null
                    ? "Turno actual: desde el corte #" + e.corteAnterior.getId() + " de las " + e.inicio.format(inicioHoy ? HORA : FMT)
                      + " (" + e.corteAnterior.getCajeroNombre() + ") hasta ahora."
                    : "Turno actual: desde el inicio del día hasta ahora (aún no hay cortes hoy).")
                : "Día completo: todo lo de hoy desde las 00:00, incluidos los turnos ya cortados.");
        lblTituloEfectivo.setText(turno ? "Efectivo en caja (turno actual)" : "Efectivo del día");
        lblInicialTitulo.setText(e.efectivoInicialEsFondo ? "Fondo inicial:" : "Dejado por el corte anterior:");
        lblEfectivoInicial.setText(moneda(e.efectivoInicial));
        lblVentasEfectivo.setText(moneda(r.efectivo));
        lblEntradas.setText(moneda(e.entradas));
        lblSalidas.setText(moneda(e.salidas));
        boolean verRetiros = !turno;
        lblRetirosTitulo.setVisible(verRetiros); lblRetirosTitulo.setManaged(verRetiros);
        lblRetiros.setVisible(verRetiros);       lblRetiros.setManaged(verRetiros);
        lblRetiros.setText(moneda(e.retiros));
        lblTotalCaja.setText(moneda(e.esperado()));
        lblFondoNota.setText(!e.efectivoInicialEsFondo && e.fondo != e.efectivoInicial
                ? "El fondo del día es " + moneda(e.fondo) + "; el corte anterior dejó " + moneda(e.efectivoInicial) + " en el cajón."
                : (e.fondo == 0 && e.efectivoInicialEsFondo ? "Sin fondo capturado hoy en esta caja." : ""));

        lblCambio.setText(moneda(e.monto(Categoria.CAMBIO)));
        mostrarFila(lblOtrasEntradasTitulo, lblOtrasEntradas, e.monto(Categoria.OTRA_ENTRADA));
        lblRetiro.setText(moneda(e.monto(Categoria.RETIRO)));
        lblPagoProveedores.setText(moneda(e.monto(Categoria.PAGO_PROVEEDOR)));
        lblGastos.setText(moneda(e.monto(Categoria.GASTO)));
        mostrarFila(lblDevolucionesTitulo, lblDevoluciones, e.monto(Categoria.DEVOLUCION));
        mostrarFila(lblOtrasSalidasTitulo, lblOtrasSalidas, e.monto(Categoria.OTRA_SALIDA));
        double neto = e.entradas - e.salidas;
        lblNetoMovimientos.setText((neto < -0.005 ? "−" : "") + moneda(Math.abs(neto)));

        lblEfectivo.setText(moneda(r.efectivo));
        lblTarjeta.setText(moneda(r.tarjeta));
        mostrarFila(lblTransferenciaTitulo, lblTransferencia, r.transferencia);
        mostrarFila(lblValesTitulo, lblVales, r.vales);
        lblSubtotal.setText(moneda(r.subtotal));
        lblIva.setText(moneda(r.iva));
        // Desglose por tasa solo cuando el periodo mezcla tasas (básicos al 0 % con artículos al 16 %)
        boolean mixto = CalculoIva.esMixto(r.porTasaIva);
        lblIvaPorTasaTitulo.setVisible(mixto); lblIvaPorTasaTitulo.setManaged(mixto);
        lblIvaPorTasa.setVisible(mixto);       lblIvaPorTasa.setManaged(mixto);
        lblIvaPorTasa.setText(mixto ? textoIvaPorTasa(r.porTasaIva) : "");
        lblCanceladas.setText(r.numCanceladas + " (" + moneda(r.totalCanceladas) + ")");
        boolean hayDevoluciones = r.numDevoluciones > 0;
        lblDevArticulosTitulo.setVisible(hayDevoluciones); lblDevArticulosTitulo.setManaged(hayDevoluciones);
        lblDevArticulos.setVisible(hayDevoluciones);       lblDevArticulos.setManaged(hayDevoluciones);
        lblDevArticulos.setText(r.numDevoluciones + " (−" + moneda(r.totalDevoluciones) + ")");

        lblVentasTotalesTitulo.setText("Ventas " + que);
        lblGananciaTitulo.setText("Ganancia " + que);
        lblVentasTotales.setText(r.numDevoluciones > 0
                ? moneda(r.getVentasNetas()) + " netas  (" + r.numVentas + " ventas por " + moneda(r.ventasTotales)
                  + "; devoluciones −" + moneda(r.totalDevoluciones) + ")"
                : moneda(r.ventasTotales) + "  (" + r.numVentas + " ventas)");
        lblGanancia.setText(r.gananciaVisible ? moneda(r.ganancia) : "—");
        // Productos comunes (Ctrl+P, fuera del catálogo): su costo no es real sino una estimación del 20 %
        boolean hayComunes = r.gananciaVisible && r.productosComunes > 0.004;
        lblGananciaComunes.setVisible(hayComunes);
        lblGananciaComunes.setManaged(hayComunes);
        if (hayComunes) {
            lblGananciaComunes.setText(String.format("Incluye %s de productos comunes con ganancia estimada del %d %% (%s)",
                    moneda(r.productosComunes), Math.round(ProductoComun.MARGEN_GANANCIA * 100), moneda(r.gananciaProductosComunes)));
        }

        porDepartamento.setAll(r.porDepartamento.entrySet());
        bajos.setAll(r.productosBajos);
        vouchers.setAll(r.vouchers);
        movimientos.setAll(e.movimientos);
        lblTarjetaTerminales.setText(r.tarjetaPorTerminal.isEmpty() ? "Sin cobros con tarjeta."
                : r.tarjetaPorTerminal.stream()
                    .map(t -> t.terminal() + ": " + t.pagos() + " cobro(s), " + moneda(t.monto()))
                    .collect(java.util.stream.Collectors.joining("   ·   ")));

        lblUltimoCorte.setText(cajaService.cortesDeHoyDeEstaCaja().stream().findFirst()
                .map(c -> String.format("Último corte hoy (%s): %s por %s — esperado %s, contado %s, %s",
                        c.getEtiquetaCaja(), c.getRealizadoEn().format(FMT), c.getCajeroNombre(), moneda(c.getEfectivoEsperado()),
                        moneda(c.getEfectivoContado()),
                        c.hayFaltante() ? "faltante " + moneda(-c.getDiferencia()) : c.haySobrante() ? "sobrante " + moneda(c.getDiferencia()) : "sin diferencia"))
                .orElse("Hoy no se ha hecho ningún corte en esta caja."));
        onBuscarCortes();
    }

    private javafx.stage.Window ventana() {
        return tablaMovimientos.getScene() != null ? tablaMovimientos.getScene().getWindow() : null;
    }

    private static Double numero(String texto, String mensajeError) {
        try {
            double v = CalculoCobro.parsearNumero(texto);
            if (Double.isNaN(v)) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Monto inválido", mensajeError);
            return null;
        }
    }

    /** "Gravado 16 %: $100.00 (IVA $16.00) · Tasa 0 %: $84.00 · Exento: $20.00" */
    static String textoIvaPorTasa(java.util.List<CalculoIva.Grupo> grupos) {
        java.util.List<String> partes = new java.util.ArrayList<>();
        for (CalculoIva.Grupo g : grupos) {
            if (g.gravado()) {
                partes.add("Gravado " + g.tasaIva().porcentaje() + " %: " + Moneda.formato(g.base()) + " (IVA " + Moneda.formato(g.iva()) + ")");
            } else if (g.exento()) {
                partes.add("Exento: " + Moneda.formato(g.base()));
            } else {
                partes.add("Tasa 0 %: " + Moneda.formato(g.base()));
            }
        }
        return String.join("  ·  ", partes);
    }

    private static void mostrarFila(Label titulo, Label valor, double monto) {
        boolean ver = monto > 0;
        titulo.setVisible(ver); titulo.setManaged(ver);
        valor.setVisible(ver);  valor.setManaged(ver);
        valor.setText(Moneda.formato(monto));
    }

    private static String moneda(double v) {
        return Moneda.formato(v);
    }

    private static String cantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }
}
