package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.model.Factura;
import me.eliassanchezfernandez.puntodeventa.model.FacturaVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.service.FacturacionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/**
 * Facturas emitidas (ventana modal desde Ventas por período): listado por
 * fechas, archivos, correo, clientes fiscales. Requiere FACTURAR.
 */
@Component
@Scope("prototype")
public class FacturasController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private BorderPane raiz;
    @FXML private DatePicker dpDesde;
    @FXML private DatePicker dpHasta;
    @FXML private TableView<Factura> tabla;
    @FXML private TableColumn<Factura, String> colFolio;
    @FXML private TableColumn<Factura, String> colFecha;
    @FXML private TableColumn<Factura, String> colTipo;
    @FXML private TableColumn<Factura, String> colVentas;
    @FXML private TableColumn<Factura, String> colReceptor;
    @FXML private TableColumn<Factura, String> colTotal;
    @FXML private TableColumn<Factura, String> colEstado;
    @FXML private TableColumn<Factura, String> colUuid;
    @FXML private Label lblDetalle;
    @FXML private Label lblResumen;

    @Autowired private FacturacionService facturacionService;
    @Autowired private FacturacionDialogos dialogos;
    @Autowired private SesionService sesionService;
    @Autowired private org.springframework.context.ApplicationContext springContext;

    private final ObservableList<Factura> facturas = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        colFolio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSerieFolio()));
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFecha().format(FMT)));
        colTipo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().esGlobal() ? "Global" : "Venta"));
        colVentas.setCellValueFactory(c -> new SimpleStringProperty(ventasDe(c.getValue())));
        colReceptor.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getReceptorRfc() + " " + c.getValue().getReceptorNombre()));
        colTotal.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(c.getValue().getTotal())));
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getEstadoEnum().getEtiqueta()
                + (c.getValue().isSandbox() ? " (pruebas)" : "")));
        colUuid.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUuid() != null ? c.getValue().getUuid() : "—"));
        tabla.setItems(facturas);
        tabla.setPlaceholder(new Label("No hay facturas en el período."));
        tabla.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(Factura f, boolean empty) {
                super.updateItem(f, empty);
                setStyle(!empty && f != null && f.isCancelada() ? "-fx-opacity: 0.55;" : "");
            }
        });
        tabla.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> lblDetalle.setText(f == null ? "" : detalle(f)));
        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy.withDayOfMonth(1));
        dpHasta.setValue(hoy);
        onBuscar();
    }

    @FXML private void onBuscar() {
        try {
            facturas.setAll(facturacionService.facturasEntre(dpDesde.getValue(), dpHasta.getValue()));
        } catch (NegocioException e) {
            facturas.clear();
            Ventanas.alerta(Alert.AlertType.WARNING, "Facturas", e.getMessage());
            if (!sesionService.tienePermiso(Permiso.FACTURAR)) raiz.setDisable(true);
            return;
        }
        long vigentes = facturas.stream().filter(Factura::isVigente).count();
        double total = facturas.stream().filter(Factura::isVigente).mapToDouble(Factura::getTotal).sum();
        lblResumen.setText(facturas.size() + " factura(s)  ·  " + vigentes + " vigente(s) por " + Moneda.formato(total));
    }

    @FXML private void onAcciones() {
        Factura f = seleccionada();
        if (f == null) return;
        dialogos.accionesFactura(raiz.getScene().getWindow(), f, detalle(f));
    }

    @FXML private void onClientes() {
        try {
            Ventanas.abrirModal(springContext, "/fxml/clientes_fiscales.fxml", "Clientes fiscales", raiz.getScene().getWindow(), 900, 560);
        } catch (Exception e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Clientes fiscales", "No se pudo abrir la ventana: " + e.getMessage());
        }
    }

    @FXML private void onExportar() {
        me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas.exportar(tabla, "facturas",
                "Facturas del " + dpDesde.getValue() + " al " + dpHasta.getValue());
    }

    @FXML private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    protected Factura seleccionada() {
        Factura f = tabla.getSelectionModel().getSelectedItem();
        if (f == null) Ventanas.alerta(Alert.AlertType.WARNING, "Selecciona una factura", "Haz clic en una factura de la tabla.");
        return f;
    }

    protected void recargar() { onBuscar(); }

    static String ventasDe(Factura f) {
        if (f.getVentas() == null || f.getVentas().isEmpty()) return f.getVenta() != null ? f.getVenta().getFolioMostrado() : "";
        if (f.getVentas().size() > 4) return f.getVentas().size() + " ventas";
        return f.getVentas().stream().map(FacturaVenta::getVentaFolio).collect(Collectors.joining(", "));
    }

    static String detalle(Factura f) {
        StringBuilder sb = new StringBuilder("Factura " + f.getSerieFolio() + " — " + f.getEstadoEnum().getEtiqueta());
        if (f.isSandbox()) sb.append(" (PRUEBAS, sin validez fiscal)");
        sb.append("\nReceptor: ").append(f.getReceptorRfc()).append(" ").append(f.getReceptorNombre())
          .append("  ·  Uso ").append(f.getUsoCfdi()).append("  ·  Forma de pago ").append(f.getFormaPago())
          .append(" ").append(CatalogoSat.descripcionFormaPago(f.getFormaPago()));
        sb.append("\nSubtotal ").append(Moneda.formato(f.getSubtotal()));
        if (f.getDescuento() > 0) sb.append("  ·  Descuento ").append(Moneda.formato(f.getDescuento()));
        sb.append("  ·  IVA ").append(Moneda.formato(f.getIva())).append("  ·  Total ").append(Moneda.formato(f.getTotal()));
        if (f.esGlobal()) sb.append("\nGlobal: periodicidad ").append(f.getPeriodicidad()).append(", mes ").append(f.getMeses())
                .append(", año ").append(f.getAnio()).append(" — ").append(f.getVentas().size()).append(" venta(s)");
        if (f.getUuid() != null) sb.append("\nUUID: ").append(f.getUuid());
        if (f.getEmitidaPorNombre() != null) sb.append("  ·  emitió ").append(f.getEmitidaPorNombre());
        if (f.getEmailEnviado() != null) sb.append("\nEnviada por correo a ").append(f.getReceptorEmail()).append(" el ").append(f.getEmailEnviado().format(FMT));
        if (f.getFechaCancelacion() != null) sb.append("\nCancelada el ").append(f.getFechaCancelacion().format(FMT))
                .append(" motivo ").append(f.getMotivoCancelacion())
                .append(f.getUuidSustitucion() != null ? " sustituida por " + f.getUuidSustitucion() : "")
                .append(f.getCanceladaPorNombre() != null ? " (" + f.getCanceladaPorNombre() + ")" : "");
        if (f.getUltimoError() != null) sb.append("\nAviso: ").append(f.getUltimoError());
        return sb.toString();
    }
}
