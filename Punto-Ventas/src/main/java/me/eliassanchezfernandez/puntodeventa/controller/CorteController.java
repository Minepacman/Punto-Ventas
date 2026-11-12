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
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.service.CajaService;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controlador del Corte de Caja.
 * Muestra el resumen de las ventas de hoy calculado por VentaService y
 * guarda cortes con efectivo contado y faltante/sobrante (CRT-06).
 * La ganancia solo se muestra a quien tiene VER_GANANCIAS (CRT-11).
 */
@Component
@Scope("prototype")
public class CorteController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired private VentaService  ventaService;
    @Autowired private CajaService   cajaService;
    @Autowired private SesionService sesionService;
    @Autowired private ImpresionService impresionService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.IdentidadCaja identidadCaja;

    // Entradas de efectivo
    @FXML private Label lblDineroInicial;
    @FXML private Label lblTotalEntradas;
    // Dinero en caja
    @FXML private Label lblVentasEfectivo;
    @FXML private Label lblEntradas;
    @FXML private Label lblTotalCaja;
    // Pagos de contado
    @FXML private Label lblEfectivo;
    @FXML private Label lblTarjeta;
    @FXML private Label lblTransferenciaTitulo;
    @FXML private Label lblTransferencia;
    @FXML private Label lblValesTitulo;
    @FXML private Label lblVales;
    // IVA y canceladas
    @FXML private Label lblSubtotal;
    @FXML private Label lblIva;
    @FXML private Label lblCanceladas;
    // Totales
    @FXML private Label lblVentasTotales;
    @FXML private Label lblGanancia;
    @FXML private Label lblGananciaTitulo;
    // Último corte
    @FXML private Label lblUltimoCorte;
    @FXML private Button btnHacerCorte;
    @FXML private Button btnCorregirFondo;
    @FXML private Button btnCorteGeneral;
    @FXML private Label  lblCaja;

    @FXML private TableView<Map.Entry<String, Double>>           tablaDepartamentos;
    @FXML private TableColumn<Map.Entry<String, Double>, String> colDepto;
    @FXML private TableColumn<Map.Entry<String, Double>, String> colMonto;

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

    private final ObservableList<Map.Entry<String, Double>> porDepartamento =
            FXCollections.observableArrayList();
    private final ObservableList<Producto> bajos = FXCollections.observableArrayList();
    private final ObservableList<VentaService.VoucherTarjeta> vouchers = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colDepto.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getKey()));
        colMonto.setCellValueFactory(c -> new SimpleStringProperty(moneda(c.getValue().getValue())));
        tablaDepartamentos.setItems(porDepartamento);
        tablaDepartamentos.setPlaceholder(new Label("Sin ventas hoy."));

        colBajoDescripcion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDescripcion()));
        colBajoActual.setCellValueFactory(c -> new SimpleStringProperty(cantidad(c.getValue().getCantidadActual())));
        colBajoMinimo.setCellValueFactory(c -> new SimpleStringProperty(cantidad(c.getValue().getCantidadMinima())));
        tablaBajos.setItems(bajos);
        tablaBajos.setPlaceholder(new Label("Ningún producto por debajo de su mínimo."));

        colVHora.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fecha().format(HORA)));
        colVFolio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().folio()));
        colVTerminal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().terminal() != null ? c.getValue().terminal() : "—"));
        colVTarjeta.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().ultimos4() != null ? "****" + c.getValue().ultimos4() : "—"));
        colVAut.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().autorizacion() != null ? c.getValue().autorizacion() : "—"));
        colVMonto.setCellValueFactory(c -> new SimpleStringProperty(moneda(c.getValue().monto())));
        tablaVouchers.setItems(vouchers);
        tablaVouchers.setPlaceholder(new Label("Hoy no hay cobros con tarjeta."));

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
        btnHacerCorte.setText(identidadCaja.esMultiCaja() ? "Hacer corte de esta caja" : "Hacer Corte de Hoy");

        // main.fxml incluye esta vista para todos; sin permiso no hay nada que calcular
        // (MainController quita la pestaña) y no debe aparecer ningún aviso.
        if (sesionService.tienePermiso(Permiso.HACER_CORTE) || veGanancia) {
            recargar();
        }
    }

    /** Corrección del fondo del día (solo ADMIN, con motivo; queda como registro nuevo) */
    @FXML
    void onCorregirFondo() {
        Dialog<String[]> dlg = new Dialog<>();
        dlg.setTitle("Corregir fondo de caja");
        dlg.setHeaderText("Fondo vigente de hoy: " + lblDineroInicial.getText()
                + "\nEl fondo anterior se conserva en la bitácora.");
        Ventanas.estilizar(dlg);
        TextField txtMonto = new TextField();
        txtMonto.setPromptText("0.00");
        TextArea txtMotivo = new TextArea();
        txtMotivo.setPromptText("Motivo de la corrección (obligatorio)");
        txtMotivo.setPrefRowCount(2);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Fondo correcto: $"), txtMonto);
        grid.add(txtMotivo, 0, 1, 2, 1);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? new String[] { txtMonto.getText(), txtMotivo.getText() } : null);
        Platform.runLater(txtMonto::requestFocus);

        Optional<String[]> res = dlg.showAndWait();
        if (res.isEmpty()) return;
        double monto;
        try {
            monto = me.eliassanchezfernandez.puntodeventa.util.CalculoCobro.parsearNumero(res.get()[0]);
            if (Double.isNaN(monto)) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Monto inválido", "Escribe el fondo como número (ej: 500).");
            return;
        }
        try {
            cajaService.corregirFondo(monto, res.get()[1]);
            recargar();
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Fondo corregido",
                    "El fondo de hoy ahora es " + Moneda.formato(monto) + ".");
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo corregir", e.getMessage());
        }
    }

    /** Pide el efectivo contado y guarda el corte (HACER_CORTE) */
    @FXML
    void onHacerCorte() {
        recargar();
        Dialog<String[]> dlg = new Dialog<>();
        dlg.setTitle("Hacer corte de caja");
        dlg.setHeaderText("Efectivo esperado en caja: " + lblTotalCaja.getText()
                + "\nCuenta el efectivo y captura el total.");
        Ventanas.estilizar(dlg);
        TextField txtContado = new TextField();
        txtContado.setPromptText("0.00");
        TextArea txtObs = new TextArea();
        txtObs.setPromptText("Observaciones (opcional)");
        txtObs.setPrefRowCount(2);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Efectivo contado: $"), txtContado);
        grid.add(txtObs, 0, 1, 2, 1);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? new String[] { txtContado.getText(), txtObs.getText() } : null);
        Platform.runLater(txtContado::requestFocus);

        Optional<String[]> res = dlg.showAndWait();
        if (res.isEmpty()) return;
        double contado;
        try {
            contado = me.eliassanchezfernandez.puntodeventa.util.CalculoCobro.parsearNumero(res.get()[0]);
            if (Double.isNaN(contado)) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Monto inválido", "Escribe el efectivo contado como número (ej: 1250.50).");
            return;
        }
        try {
            Corte c = cajaService.hacerCorte(contado, res.get()[1]);
            recargar();
            String dif = c.hayFaltante() ? "FALTANTE de " + Moneda.formato(-c.getDiferencia())
                       : c.haySobrante() ? "SOBRANTE de " + Moneda.formato(c.getDiferencia())
                       : "sin diferencia";
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Corte guardado", String.format(
                    "Corte #%d registrado por %s.\n\nEsperado: %s\nContado:  %s\nResultado: %s",
                    c.getId(), c.getCajeroNombre(), Moneda.formato(c.getEfectivoEsperado()), Moneda.formato(c.getEfectivoContado()), dif));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar el corte", e.getMessage());
        }
    }

    /** Corte general del día (solo ADMIN, varias cajas): suma las ventas y los fondos de todas las cajas */
    @FXML
    void onCorteGeneral() {
        VentaService.ResumenCorte r;
        double fondoTotal;
        try {
            r = ventaService.resumenGeneralDe(LocalDate.now());
            fondoTotal = cajaService.fondoTotalDelDia(LocalDate.now());
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Corte general", e.getMessage());
            return;
        }
        StringBuilder detalle = new StringBuilder();
        for (VentaService.VentasCaja vc : r.porCaja) {
            detalle.append("Caja ").append(vc.caja()).append(": ").append(vc.ventas()).append(" venta(s), ")
                   .append(moneda(vc.total())).append('\n');
        }
        cajaService.fondosPorCaja(LocalDate.now()).forEach((caja, fondo) ->
                detalle.append("Fondo caja ").append(caja).append(": ").append(moneda(fondo)).append('\n'));

        Dialog<String[]> dlg = new Dialog<>();
        dlg.setTitle("Corte general (todas las cajas)");
        dlg.setHeaderText("Ventas de todas las cajas: " + moneda(r.ventasTotales) + " (" + r.numVentas + " ventas)\n"
                + "Efectivo esperado en todas las cajas: " + moneda(fondoTotal + r.efectivo)
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
        grid.addRow(1, new Label("Efectivo contado (todas): $"), txtContado);
        grid.add(txtObs, 0, 2, 2, 1);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? new String[] { txtContado.getText(), txtObs.getText() } : null);
        Platform.runLater(txtContado::requestFocus);

        Optional<String[]> res = dlg.showAndWait();
        if (res.isEmpty()) return;
        double contado;
        try {
            contado = me.eliassanchezfernandez.puntodeventa.util.CalculoCobro.parsearNumero(res.get()[0]);
            if (Double.isNaN(contado)) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Monto inválido", "Escribe el efectivo contado como número (ej: 1250.50).");
            return;
        }
        try {
            Corte c = cajaService.hacerCorteGeneral(contado, res.get()[1]);
            recargar();
            String dif = c.hayFaltante() ? "FALTANTE de " + Moneda.formato(-c.getDiferencia())
                       : c.haySobrante() ? "SOBRANTE de " + Moneda.formato(c.getDiferencia())
                       : "sin diferencia";
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Corte general guardado", String.format(
                    "Corte general #%d registrado por %s.\n\nEsperado: %s\nContado:  %s\nResultado: %s",
                    c.getId(), c.getCajeroNombre(), Moneda.formato(c.getEfectivoEsperado()), Moneda.formato(c.getEfectivoContado()), dif));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar el corte general", e.getMessage());
        }
    }

    /** Imprime el reporte del corte de hoy en la impresora de tickets (fuera del hilo de JavaFX) */
    @FXML
    void onImprimirCorte() {
        try {
            impresionService.imprimirCorteAsync().whenComplete((res, error) -> Platform.runLater(() -> {
                if (error != null || res == null || res.esFallo()) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Imprimir corte",
                            res != null ? res.mensaje() : "No se pudo imprimir el corte.");
                }
            }));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Imprimir corte", e.getMessage());
        }
    }

    /** Recalcula el resumen (lo llama MainController al entrar a la pestaña) */
    public void recargar() {
        VentaService.ResumenCorte r;
        double dineroInicial;
        try {
            r = ventaService.resumenDelDia();
            dineroInicial = cajaService.fondoVigenteDelDia(LocalDate.now());
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Corte", e.getMessage());
            return;
        } catch (RuntimeException e) {
            // Un dato inconsistente en la BD no debe impedir abrir la ventana principal
            e.printStackTrace();
            Ventanas.alerta(Alert.AlertType.ERROR, "Corte",
                    "No se pudo calcular el resumen del día: " + e.getMessage());
            return;
        }

        lblDineroInicial.setText(moneda(dineroInicial));
        lblTotalEntradas.setText(moneda(dineroInicial));

        lblVentasEfectivo.setText(moneda(r.efectivo));
        lblEntradas.setText(moneda(dineroInicial));   // fondo inicial (también en "Dinero en caja")
        lblTotalCaja.setText(moneda(r.efectivo + dineroInicial));

        lblEfectivo.setText(moneda(r.efectivo));
        lblTarjeta.setText(moneda(r.tarjeta));
        // Transferencia y vales solo aparecen si hubo ventas con ellos
        mostrarFila(lblTransferenciaTitulo, lblTransferencia, r.transferencia);
        mostrarFila(lblValesTitulo, lblVales, r.vales);
        lblSubtotal.setText(moneda(r.subtotal));
        lblIva.setText(moneda(r.iva));
        lblCanceladas.setText(r.numCanceladas + " (" + moneda(r.totalCanceladas) + ")");

        lblVentasTotales.setText(moneda(r.ventasTotales) + "  (" + r.numVentas + " ventas)");
        lblGanancia.setText(r.gananciaVisible ? moneda(r.ganancia) : "—");

        porDepartamento.setAll(r.porDepartamento.entrySet());
        bajos.setAll(r.productosBajos);
        vouchers.setAll(r.vouchers);
        lblTarjetaTerminales.setText(r.tarjetaPorTerminal.isEmpty() ? "Sin cobros con tarjeta."
                : r.tarjetaPorTerminal.stream()
                    .map(t -> t.terminal() + ": " + t.pagos() + " cobro(s), " + moneda(t.monto()))
                    .collect(java.util.stream.Collectors.joining("   ·   ")));

        lblUltimoCorte.setText(cajaService.cortesDeHoyDeEstaCaja().stream().findFirst()
                .map(c -> String.format("Último corte hoy (%s): %s por %s — esperado %s, contado %s, diferencia %s",
                        c.getEtiquetaCaja(), c.getRealizadoEn().format(FMT), c.getCajeroNombre(), moneda(c.getEfectivoEsperado()),
                        moneda(c.getEfectivoContado()), moneda(c.getDiferencia())))
                .orElse("Hoy no se ha hecho ningún corte en esta caja."));
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
