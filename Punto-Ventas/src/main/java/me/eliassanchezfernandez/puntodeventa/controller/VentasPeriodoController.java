package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Registro de ventas por período (ventana modal abierta desde
 * Productos → Ventas por Período). Requiere VER_HISTORIAL.
 *
 * Arriba: rango de fechas. Centro: tabla de ventas (con estado, IVA y
 * cajero) y, debajo, los renglones de la venta seleccionada. Abajo:
 * totales del período. Desde aquí se cancela una venta (VEN-14).
 */
@Component
@Scope("prototype")
public class VentasPeriodoController implements Initializable {

    private static final DateTimeFormatter FMT_FECHA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private BorderPane raiz;
    @FXML private DatePicker dpDesde;
    @FXML private DatePicker dpHasta;
    @FXML private Button     btnCancelarVenta;
    @FXML private Button     btnReimprimir;
    @FXML private Button     btnFacturar;
    @FXML private Button     btnFacturas;

    // ── Tabla de ventas ──────────────────────────────────────────────────
    @FXML private TableView<Venta>           tablaVentas;
    @FXML private TableColumn<Venta, String> colFolio;
    @FXML private TableColumn<Venta, String> colFecha;
    @FXML private TableColumn<Venta, String> colCajero;
    @FXML private TableColumn<Venta, String> colFormaPago;
    @FXML private TableColumn<Venta, String> colPiezas;
    @FXML private TableColumn<Venta, String> colSubtotal;
    @FXML private TableColumn<Venta, String> colIva;
    @FXML private TableColumn<Venta, String> colTotal;
    @FXML private TableColumn<Venta, String> colEstado;
    @FXML private TableColumn<Venta, String> colFactura;

    // ── Tabla de detalle ─────────────────────────────────────────────────
    @FXML private TableView<DetalleVenta>           tablaDetalle;
    @FXML private TableColumn<DetalleVenta, String> colDetCodigo;
    @FXML private TableColumn<DetalleVenta, String> colDetDescripcion;
    @FXML private TableColumn<DetalleVenta, String> colDetCantidad;
    @FXML private TableColumn<DetalleVenta, String> colDetPrecio;
    @FXML private TableColumn<DetalleVenta, String> colDetDescuento;
    @FXML private TableColumn<DetalleVenta, String> colDetConcepto;
    @FXML private TableColumn<DetalleVenta, String> colDetImporte;

    @FXML private Label lblResumen;
    @FXML private Label lblDetalleTitulo;

    @Autowired private VentaService        ventaService;
    @Autowired private SesionService       sesionService;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private ImpresionService    impresionService;
    @Autowired private FacturacionDialogos facturacionDialogos;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.FacturacionService facturacionService;
    @Autowired private org.springframework.context.ApplicationContext springContext;

    /** Factura de cada venta de la tabla (vigente, pendiente o en cancelación) */
    private java.util.Map<Long, me.eliassanchezfernandez.puntodeventa.model.Factura> facturaPorVenta = java.util.Map.of();

    private final ObservableList<Venta>        ventas   = FXCollections.observableArrayList();
    private final ObservableList<DetalleVenta> detalles = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configurarColumnas();
        tablaVentas.setItems(ventas);
        tablaDetalle.setItems(detalles);
        tablaVentas.setPlaceholder(new Label("No hay ventas en el período seleccionado."));
        tablaDetalle.setPlaceholder(new Label("Selecciona una venta para ver sus productos."));

        // Ventas canceladas atenuadas
        tablaVentas.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(Venta v, boolean empty) {
                super.updateItem(v, empty);
                setStyle(!empty && v != null && v.isCancelada() ? "-fx-opacity: 0.55;" : "");
            }
        });

        tablaVentas.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> {
            detalles.setAll(v != null ? v.getDetalles() : List.of());
            lblDetalleTitulo.setText(v != null
                    ? "Detalle de la venta #" + v.getId() + "  —  " + v.getFecha().format(FMT_FECHA)
                      + (v.isCancelada() ? "  —  CANCELADA: " + v.getMotivoCancelacion()
                        + " (" + v.getCanceladaPorNombre()
                        + (v.getCancelacionAutorizadaPor() != null ? ", autorizó " + v.getCancelacionAutorizadaPor() : "") + ")"
                        : "")
                    : "Detalle de la venta");
        });

        boolean puedeFacturar = sesionService.tienePermiso(Permiso.FACTURAR);
        btnFacturar.setDisable(!puedeFacturar);
        btnFacturas.setDisable(!puedeFacturar);
        onHoy();
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    /** Factura la venta seleccionada (receptor, uso y forma de pago en el diálogo) */
    @FXML private void onFacturar() {
        Venta v = tablaVentas.getSelectionModel().getSelectedItem();
        if (v == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona una venta", "Haz clic en una venta de la tabla para facturarla.");
            return;
        }
        if (v.isCancelada()) {
            alerta(Alert.AlertType.INFORMATION, "Venta cancelada", "Una venta cancelada no se puede facturar.");
            return;
        }
        facturacionDialogos.facturarVenta(raiz.getScene().getWindow(), v).ifPresent(f -> onBuscar());
    }

    /** Facturas emitidas (archivos, correo, cancelación, factura global) */
    @FXML private void onFacturas() {
        try {
            Ventanas.abrirModal(springContext, "/fxml/facturas.fxml", "Facturas electrónicas", raiz.getScene().getWindow(), 1100, 640);
        } catch (Exception e) {
            alerta(Alert.AlertType.ERROR, "Facturas", "No se pudo abrir la ventana: " + e.getMessage());
        }
        onBuscar();
    }

    @FXML private void onBuscar() {
        try {
            ventas.setAll(ventaService.ventasEntre(dpDesde.getValue(), dpHasta.getValue()));
        } catch (NegocioException e) {
            ventas.clear();
            alerta(Alert.AlertType.WARNING, "Historial de ventas", e.getMessage());
            if (!sesionService.tienePermiso(Permiso.VER_HISTORIAL)) raiz.setDisable(true);
            return;
        }
        detalles.clear();
        try {
            facturaPorVenta = facturacionService.facturasDeVentas(ventas.stream().map(Venta::getId).toList());
        } catch (RuntimeException e) {
            facturaPorVenta = java.util.Map.of();
        }
        tablaVentas.refresh();
        actualizarResumen();
    }

    @FXML private void onHoy() {
        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy);
        dpHasta.setValue(hoy);
        onBuscar();
    }

    @FXML private void onEstaSemana() {
        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy.with(DayOfWeek.MONDAY));
        dpHasta.setValue(hoy);
        onBuscar();
    }

    @FXML private void onEsteMes() {
        LocalDate hoy = LocalDate.now();
        dpDesde.setValue(hoy.withDayOfMonth(1));
        dpHasta.setValue(hoy);
        onBuscar();
    }

    /** Cancela la venta seleccionada: motivo + permiso o autorización de administrador */
    @FXML private void onCancelarVenta() {
        Venta v = tablaVentas.getSelectionModel().getSelectedItem();
        if (v == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona una venta", "Haz clic en una venta de la tabla para cancelarla.");
            return;
        }
        if (v.isCancelada()) {
            alerta(Alert.AlertType.INFORMATION, "Venta cancelada", "La venta #" + v.getId() + " ya está cancelada.");
            return;
        }
        var factura = facturaPorVenta.get(v.getId());
        if (factura != null) {
            alerta(Alert.AlertType.WARNING, "Venta facturada", String.format(
                    "La venta #%d está en la factura %s%s (%s).\nPrimero cancela o sustituye esa factura en \"Facturas…\" y después cancela la venta.",
                    v.getId(), factura.getSerieFolio(), factura.esGlobal() ? " (global)" : "", factura.getEstadoEnum().getEtiqueta()));
            return;
        }
        if (java.time.LocalDateTime.now().isAfter(v.getLimiteCancelacion())) {
            alerta(Alert.AlertType.WARNING, "Fuera de plazo", String.format(
                    "La venta #%d ya no se puede cancelar: el plazo de %d horas venció el %s.",
                    v.getId(), v.getPlazoCancelacionHorasOPorDefecto(), v.getLimiteCancelacion().format(FMT_FECHA)));
            return;
        }
        if (!Ventanas.confirmar("Cancelar venta", String.format(
                "¿Cancelar la venta #%d por %s?\nLa mercancía regresará al inventario y la venta dejará de contar en los totales.",
                v.getId(), Moneda.formato(v.getTotal())))) {
            return;
        }
        Optional<String> motivo = Ventanas.pedirTexto("Motivo de la cancelación",
                "Venta #" + v.getId(), "Ej. error de captura, devolución del cliente...");
        if (motivo.isEmpty()) return;

        List<Autorizacion> auts = List.of();
        if (!sesionService.tienePermiso(Permiso.CANCELAR_VENTA)) {
            Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, Permiso.CANCELAR_VENTA);
            if (aut.isEmpty()) return;
            auts = List.of(aut.get());
        }
        try {
            ventaService.cancelar(v.getId(), motivo.get(), auts);
            alerta(Alert.AlertType.INFORMATION, "Venta cancelada",
                    "La venta #" + v.getId() + " fue cancelada y la mercancía regresó al inventario.");
            onBuscar();
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo cancelar", e.getMessage());
        }
    }

    /** Reimprime el ticket de la venta seleccionada en la impresora de tickets (fuera del hilo de JavaFX) */
    @FXML private void onReimprimir() {
        Venta v = tablaVentas.getSelectionModel().getSelectedItem();
        if (v == null) {
            alerta(Alert.AlertType.WARNING, "Selecciona una venta", "Haz clic en una venta de la tabla para reimprimir su ticket.");
            return;
        }
        btnReimprimir.setDisable(true);
        try {
            impresionService.reimprimirVentaAsync(v.getId()).whenComplete((res, error) -> Platform.runLater(() -> {
                btnReimprimir.setDisable(false);
                if (error != null || res == null || res.esFallo()) {
                    alerta(Alert.AlertType.WARNING, "Reimprimir", res != null ? res.mensaje() : "No se pudo imprimir el ticket.");
                }
            }));
        } catch (NegocioException e) {
            btnReimprimir.setDisable(false);
            alerta(Alert.AlertType.ERROR, "Reimprimir", e.getMessage());
        }
    }

    /** Exporta las ventas del período (lo que se ve en la tabla) a CSV, Excel o PDF */
    @FXML private void onExportar() {
        me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas.exportar(tablaVentas, "ventas",
                "Ventas del " + dpDesde.getValue() + " al " + dpHasta.getValue());
    }

    @FXML private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void actualizarResumen() {
        List<Venta> activas = ventas.stream().filter(Venta::estaActiva).toList();
        double total = activas.stream().mapToDouble(Venta::getTotal).sum();
        double iva   = activas.stream().mapToDouble(Venta::getIvaODefault).sum();
        double piezas = activas.stream()
                .flatMap(v -> v.getDetalles().stream())
                .mapToDouble(DetalleVenta::getCantidad).sum();
        long canceladas = ventas.size() - activas.size();
        lblResumen.setText(String.format(
                "%d venta(s)  ·  %s producto(s)  ·  Subtotal: %s  ·  IVA: %s  ·  Total: %s%s",
                activas.size(), formatoCantidad(piezas), Moneda.formato(total - iva), Moneda.formato(iva), Moneda.formato(total),
                canceladas > 0 ? "  ·  " + canceladas + " cancelada(s) no incluida(s)" : ""));
    }

    private void configurarColumnas() {
        colFolio.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFolioMostrado()));
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFecha().format(FMT_FECHA)));
        colCajero.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCajeroNombre() != null ? c.getValue().getCajeroNombre() : "—"));
        colFormaPago.setCellValueFactory(c -> new SimpleStringProperty(formaPago(c.getValue())));
        colPiezas.setCellValueFactory(c -> new SimpleStringProperty(formatoCantidad(
                c.getValue().getDetalles().stream().mapToDouble(DetalleVenta::getCantidad).sum())));
        colSubtotal.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(c.getValue().getSubtotalODefault())));
        colIva.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(c.getValue().getIvaODefault())));
        colTotal.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(c.getValue().getTotal())));
        colEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isCancelada() ? "Cancelada" : "Cobrada"));
        colFactura.setCellValueFactory(c -> {
            var f = facturaPorVenta.get(c.getValue().getId());
            return new SimpleStringProperty(f == null ? "" : f.getSerieFolio() + (f.esGlobal() ? " (global)" : "")
                    + (f.isPendiente() ? " ?" : ""));
        });

        colDetCodigo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProducto().getCodigoBarras()));
        colDetDescripcion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProducto().getDescripcion()));
        colDetCantidad.setCellValueFactory(c -> new SimpleStringProperty(formatoCantidad(c.getValue().getCantidad())));
        colDetPrecio.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(c.getValue().getPrecioVenta())));
        colDetDescuento.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(c.getValue().getDescuento())));
        colDetConcepto.setCellValueFactory(c -> {
            DetalleVenta d = c.getValue();
            String texto = switch (d.getTipoDescuento() != null ? d.getTipoDescuento() : DetalleVenta.TipoDescuento.NINGUNO) {
                case PROMOCION -> d.getPromocionNombre() != null ? d.getPromocionNombre() : "Promoción";
                case MAYOREO   -> "Mayoreo";
                case MANUAL    -> "Descuento manual";
                default        -> "";
            };
            if (d.getAutorizadoPorUsuario() != null) texto += " (autorizó " + d.getAutorizadoPorUsuario() + ")";
            return new SimpleStringProperty(texto);
        });
        colDetImporte.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(c.getValue().getImporte())));
    }

    private static String formaPago(Venta v) {
        if (v.getPagos() == null || v.getPagos().isEmpty()) {
            return v.getFormaPago() != null ? v.getFormaPago().getEtiqueta() : "—";
        }
        if (v.getPagos().size() == 1) {
            PagoVenta unico = v.getPagos().get(0);
            return unico.getFormaPago().getEtiqueta() + (unico.tieneDatosTarjeta() ? " (" + unico.resumenTarjeta() + ")" : "");
        }
        StringBuilder sb = new StringBuilder();
        v.getPagos().forEach(p -> sb.append(sb.length() > 0 ? " + " : "")
                .append(p.getFormaPago().getEtiqueta()).append(" " + Moneda.formato(p.getMonto()))
                .append(p.tieneDatosTarjeta() ? " (" + p.resumenTarjeta() + ")" : ""));
        return sb.toString();
    }

    private static String formatoCantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
