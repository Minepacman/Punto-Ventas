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
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
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

    @Autowired private VentaService  ventaService;
    @Autowired private CajaService   cajaService;
    @Autowired private SesionService sesionService;

    // Entradas de efectivo
    @FXML private Label lblEntradaCambio;
    @FXML private Label lblDineroInicial;
    @FXML private Label lblTotalEntradas;
    // Dinero en caja
    @FXML private Label lblVentasEfectivo;
    @FXML private Label lblEntradas;
    @FXML private Label lblPagoProveedores;
    @FXML private Label lblTotalCaja;
    // Pagos de contado
    @FXML private Label lblEfectivo;
    @FXML private Label lblTarjeta;
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

    @FXML private TableView<Map.Entry<String, Double>>           tablaDepartamentos;
    @FXML private TableColumn<Map.Entry<String, Double>, String> colDepto;
    @FXML private TableColumn<Map.Entry<String, Double>, String> colMonto;

    @FXML private TableView<Producto>           tablaBajos;
    @FXML private TableColumn<Producto, String> colBajoDescripcion;
    @FXML private TableColumn<Producto, String> colBajoActual;
    @FXML private TableColumn<Producto, String> colBajoMinimo;

    private final ObservableList<Map.Entry<String, Double>> porDepartamento =
            FXCollections.observableArrayList();
    private final ObservableList<Producto> bajos = FXCollections.observableArrayList();

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

        boolean veGanancia = sesionService.tienePermiso(Permiso.VER_GANANCIAS);
        lblGanancia.setVisible(veGanancia);
        lblGanancia.setManaged(veGanancia);
        lblGananciaTitulo.setVisible(veGanancia);
        lblGananciaTitulo.setManaged(veGanancia);
        btnHacerCorte.setDisable(!sesionService.tienePermiso(Permiso.HACER_CORTE));
        boolean admin = sesionService.esAdmin();
        btnCorregirFondo.setVisible(admin);
        btnCorregirFondo.setManaged(admin);

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
            monto = Double.parseDouble(res.get()[0].trim().replace("$", "").replace(",", ""));
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Monto inválido", "Escribe el fondo como número (ej: 500).");
            return;
        }
        try {
            cajaService.corregirFondo(monto, res.get()[1]);
            recargar();
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Fondo corregido",
                    String.format("El fondo de hoy ahora es $%.2f.", monto));
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
            contado = Double.parseDouble(res.get()[0].trim().replace("$", "").replace(",", ""));
        } catch (NumberFormatException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Monto inválido", "Escribe el efectivo contado como número (ej: 1250.50).");
            return;
        }
        try {
            Corte c = cajaService.hacerCorte(contado, res.get()[1]);
            recargar();
            String dif = c.hayFaltante() ? String.format("FALTANTE de $%.2f", -c.getDiferencia())
                       : c.haySobrante() ? String.format("SOBRANTE de $%.2f", c.getDiferencia())
                       : "sin diferencia";
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Corte guardado", String.format(
                    "Corte #%d registrado por %s.\n\nEsperado: $%.2f\nContado:  $%.2f\nResultado: %s",
                    c.getId(), c.getCajeroNombre(), c.getEfectivoEsperado(), c.getEfectivoContado(), dif));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar el corte", e.getMessage());
        }
    }

    @FXML
    void onImprimirCorte() {
        Ventanas.alerta(Alert.AlertType.INFORMATION, "Imprimir corte",
                "La impresión del corte no está disponible en esta versión.");
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

        lblEntradaCambio.setText(moneda(0));
        lblDineroInicial.setText(moneda(dineroInicial));
        lblTotalEntradas.setText(moneda(dineroInicial));

        lblVentasEfectivo.setText(moneda(r.efectivo));
        lblEntradas.setText(moneda(dineroInicial));
        lblPagoProveedores.setText(moneda(0));
        lblTotalCaja.setText(moneda(r.efectivo + dineroInicial));

        lblEfectivo.setText(moneda(r.efectivo));
        lblTarjeta.setText(moneda(r.tarjeta));
        lblSubtotal.setText(moneda(r.subtotal));
        lblIva.setText(moneda(r.iva));
        lblCanceladas.setText(r.numCanceladas + " (" + moneda(r.totalCanceladas) + ")");

        lblVentasTotales.setText(moneda(r.ventasTotales) + "  (" + r.numVentas + " ventas)");
        lblGanancia.setText(r.gananciaVisible ? moneda(r.ganancia) : "—");

        porDepartamento.setAll(r.porDepartamento.entrySet());
        bajos.setAll(r.productosBajos);

        lblUltimoCorte.setText(cajaService.cortesDeHoy().stream().findFirst()
                .map(c -> String.format("Último corte hoy: %s por %s — esperado %s, contado %s, diferencia %s",
                        c.getRealizadoEn().format(FMT), c.getCajeroNombre(), moneda(c.getEfectivoEsperado()),
                        moneda(c.getEfectivoContado()), moneda(c.getDiferencia())))
                .orElse("Hoy no se ha hecho ningún corte."));
    }

    private static String moneda(double v) {
        return String.format("$%.2f", v);
    }

    private static String cantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }
}
