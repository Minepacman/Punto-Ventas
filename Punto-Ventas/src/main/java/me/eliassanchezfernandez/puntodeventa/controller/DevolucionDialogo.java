package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Devolucion;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.DevolucionService;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ventana "Devolver artículos…" (Historial de ventas): el cliente regresa algunos artículos
 * de una venta y se le reembolsa en efectivo lo que pagó por ellos. Por renglón: cuánto devuelve
 * (hasta lo que queda) y si regresa al inventario (dañado/caducado → no, queda como merma). El
 * motivo es obligatorio. Si el usuario no tiene DEVOLVER_VENTAS se pide la autorización de un
 * administrador. Las reglas viven en {@link DevolucionService}; aquí solo se arma la solicitud.
 */
@Component
public class DevolucionDialogo {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final List<String> MOTIVOS = List.of(
            "El cliente cambió de opinión", "Producto equivocado", "Producto dañado", "Producto caducado", "Se cobró de más");

    @Autowired private DevolucionService devolucionService;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private ImpresionService impresionService;
    @Autowired private SesionService sesionService;

    /** Lo que se captura por renglón */
    private record Fila(DetalleVenta detalle, TextField cantidad, CheckBox regresa, Label reembolso) { }

    /** Abre la ventana para la venta; devuelve la devolución registrada o vacío si se cerró sin devolver */
    public Optional<Devolucion> mostrar(Window owner, Long ventaId) {
        DevolucionService.Estado estado;
        try {
            estado = devolucionService.estadoDe(ventaId);
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Devolver artículos", e.getMessage());
            return Optional.empty();
        }
        Venta v = estado.venta();
        String impedimento = devolucionService.motivoParaNoDevolver(v);
        if (impedimento != null) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Devolver artículos", impedimento);
            return Optional.empty();
        }

        Dialog<Devolucion> dlg = new Dialog<>();
        dlg.setTitle("Devolver artículos");
        dlg.setHeaderText(String.format("Venta %s del %s por %s%s", v.getFolioMostrado(), v.getFecha().format(FMT),
                Moneda.formato(v.getTotal()),
                v.getTotalDevueltoOCero() > 0.004 ? "  (ya se devolvieron " + Moneda.formato(v.getTotalDevueltoOCero()) + ")" : ""));
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        // ── Renglones ───────────────────────────────────────────────────
        GridPane tabla = new GridPane();
        tabla.setHgap(10);
        tabla.setVgap(6);
        tabla.getColumnConstraints().addAll(
                new ColumnConstraints(200, 260, Double.MAX_VALUE, Priority.ALWAYS, javafx.geometry.HPos.LEFT, true),
                new ColumnConstraints(110), new ColumnConstraints(80), new ColumnConstraints(150), new ColumnConstraints(90));
        String[] titulos = { "Artículo", "Vendido / devuelto", "Devolver", "Regresa al inventario", "Reembolso" };
        for (int i = 0; i < titulos.length; i++) {
            Label t = new Label(titulos[i]);
            t.getStyleClass().add("form-section-title");
            tabla.add(t, i, 0);
        }
        Label lblTotal = new Label();
        lblTotal.getStyleClass().add("corte-total-value");
        List<Fila> filas = new ArrayList<>();
        final DevolucionService.Estado est = estado;
        Runnable recalcular = () -> lblTotal.setText(textoReembolso(est, totalEstimado(filas)));
        int r = 1;
        for (DetalleVenta d : v.getDetalles()) {
            double pendiente = d.getCantidadPorDevolver();
            Label nombre = new Label(d.getDescripcionMostrada());
            nombre.setWrapText(true);
            Label vendido = new Label(DevolucionService.cantidadTexto(d.getCantidad())
                    + (d.getCantidadDevueltaOCero() > 0 ? " / " + DevolucionService.cantidadTexto(d.getCantidadDevueltaOCero()) : ""));
            TextField txtCantidad = new TextField(pendiente > 0 ? "0" : "—");
            txtCantidad.setPrefColumnCount(5);
            txtCantidad.setAlignment(Pos.CENTER_RIGHT);
            txtCantidad.setDisable(pendiente <= 0);
            boolean manejaInventario = d.getProducto() != null && d.getProducto().isUsaInventario() && !d.esProductoComun();
            CheckBox chkRegresa = new CheckBox(manejaInventario ? "Sí" : "No aplica");
            chkRegresa.setSelected(manejaInventario);
            chkRegresa.setDisable(!manejaInventario || pendiente <= 0);
            Label reembolso = new Label(Moneda.formato(0));
            Fila f = new Fila(d, txtCantidad, chkRegresa, reembolso);
            filas.add(f);
            txtCantidad.textProperty().addListener((o, a, b) -> recalcular.run());
            tabla.addRow(r++, nombre, vendido, txtCantidad, chkRegresa, reembolso);
        }
        ScrollPane desplazable = new ScrollPane(tabla);
        desplazable.setFitToWidth(true);
        desplazable.setPrefViewportHeight(Math.min(320, 40 + 34.0 * filas.size()));
        desplazable.setStyle("-fx-background-color: transparent;");

        // ── Motivo, reembolso, comprobante ──────────────────────────────
        ComboBox<String> cmbMotivo = new ComboBox<>();
        cmbMotivo.setEditable(true);
        cmbMotivo.getItems().addAll(MOTIVOS);
        cmbMotivo.setPromptText("Motivo (obligatorio): elige o escribe");
        cmbMotivo.setMaxWidth(Double.MAX_VALUE);
        // Dañado o caducado: por defecto no regresa al anaquel (se registra como merma); se puede cambiar
        cmbMotivo.valueProperty().addListener((o, a, b) -> {
            String m = b == null ? "" : b.toLowerCase(Locale.ROOT);
            boolean merma = m.contains("dañad") || m.contains("danad") || m.contains("caducad");
            for (Fila f : filas) if (!f.regresa().isDisabled()) f.regresa().setSelected(!merma);
        });

        Label lblForma = new Label("El reembolso sale en efectivo del cajón de esta caja y aparece en el corte como "
                + "«Devolución al cliente». Por ahora no hay vales ni notas de crédito: solo efectivo.");
        lblForma.getStyleClass().add("hint");
        lblForma.setWrapText(true);
        Label lblMerma = new Label("Si el artículo viene dañado o caducado, desmarca «Regresa al inventario»: "
                + "quedará registrado como merma.");
        lblMerma.getStyleClass().add("hint");
        lblMerma.setWrapText(true);

        boolean hayImpresora = impresionService.hayImpresora();
        CheckBox chkImprimir = new CheckBox("Imprimir comprobante de devolución");
        chkImprimir.setSelected(hayImpresora);
        chkImprimir.setVisible(hayImpresora);
        chkImprimir.setManaged(hayImpresora);

        Label lblError = new Label();
        lblError.getStyleClass().add("field-error-label");
        lblError.setWrapText(true);
        lblError.setVisible(false);
        lblError.setManaged(false);

        VBox contenido = new VBox(8, desplazable, lblMerma, new Label("Motivo:"), cmbMotivo, lblTotal, lblForma, chkImprimir, lblError);
        contenido.setPadding(new Insets(10));
        contenido.setPrefWidth(760);
        dlg.getDialogPane().setContent(contenido);
        recalcular.run();

        ButtonType devolver = new ButtonType("Devolver y reembolsar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(devolver, ButtonType.CANCEL);
        javafx.scene.control.Button btn = (javafx.scene.control.Button) dlg.getDialogPane().lookupButton(devolver);
        final Devolucion[] resultado = { null };
        btn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ocultar(lblError);
            List<DevolucionService.Linea> lineas = new ArrayList<>();
            for (Fila f : filas) {
                if (f.cantidad().isDisabled()) continue;
                double c = CalculoCobro.parsearNumero(f.cantidad().getText());
                if (Double.isNaN(c) || c < 0) {
                    mostrar(lblError, "Escribe la cantidad a devolver de \"" + f.detalle().getDescripcionMostrada() + "\" como número.");
                    f.cantidad().requestFocus();
                    ev.consume();
                    return;
                }
                if (c > 0) lineas.add(new DevolucionService.Linea(f.detalle().getId(), c, f.regresa().isSelected()));
            }
            if (lineas.isEmpty()) {
                mostrar(lblError, "Escribe cuántas piezas (o kilos) devuelve el cliente en al menos un artículo.");
                ev.consume();
                return;
            }
            String motivo = cmbMotivo.getEditor().getText();
            if (motivo == null || motivo.isBlank()) {
                mostrar(lblError, "Indica el motivo de la devolución.");
                cmbMotivo.requestFocus();
                ev.consume();
                return;
            }
            double total = totalEstimado(filas);
            double[] reparto = est.reparto(total);
            String comoSeReembolsa = reparto[0] > 0.004
                    ? (reparto[1] > 0.004
                            ? String.format("bajar %s de la cuenta del cliente y entregarle %s en efectivo",
                                    Moneda.formato(reparto[0]), Moneda.formato(reparto[1]))
                            : String.format("bajar %s de la cuenta del cliente (no sale efectivo)", Moneda.formato(reparto[0])))
                    : String.format("entregar %s en efectivo al cliente", Moneda.formato(total));
            if (!Ventanas.confirmar("Devolver artículos", String.format(
                    "¿Devolver %d artículo(s) de la venta %s y %s?",
                    lineas.size(), v.getFolioMostrado(), comoSeReembolsa))) {
                ev.consume();
                return;
            }
            List<Autorizacion> auts = List.of();
            if (!sesionService.tienePermiso(Permiso.DEVOLVER_VENTAS)) {
                Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, Permiso.DEVOLVER_VENTAS);
                if (aut.isEmpty()) { ev.consume(); return; }
                auts = List.of(aut.get());
            }
            try {
                resultado[0] = devolucionService.devolver(new DevolucionService.Solicitud(v.getId(), lineas, motivo.trim()), auts);
            } catch (NegocioException e) {
                mostrar(lblError, e.getMessage());
                ev.consume();
                return;
            }
            if (chkImprimir.isSelected()) imprimir(resultado[0].getId());
        });
        dlg.setResultConverter(b -> b == devolver ? resultado[0] : null);
        Platform.runLater(() -> filas.stream().filter(f -> !f.cantidad().isDisabled()).findFirst()
                .ifPresent(f -> { f.cantidad().requestFocus(); f.cantidad().selectAll(); }));
        return dlg.showAndWait().filter(d -> d != null);
    }

    /** "Reembolso en efectivo: $X" o, en una venta a cuenta (fiado), cuánto baja la deuda y cuánto se entrega */
    private static String textoReembolso(DevolucionService.Estado estado, double total) {
        double[] r = estado.reparto(total);
        if (r[0] <= 0.004) return "Reembolso en efectivo: " + Moneda.formato(total);
        return "Reembolso " + Moneda.formato(total) + ": " + Moneda.formato(r[0]) + " a su cuenta"
                + (r[1] > 0.004 ? " y " + Moneda.formato(r[1]) + " en efectivo" : " (no sale efectivo)");
    }

    /** Suma lo que se reembolsaría con lo tecleado (misma cuenta que DevolucionService) y lo pinta por renglón */
    private static double totalEstimado(List<Fila> filas) {
        double total = 0;
        for (Fila f : filas) {
            double c = f.cantidad().isDisabled() ? 0 : CalculoCobro.parsearNumero(f.cantidad().getText());
            double pendiente = f.detalle().getCantidadPorDevolver();
            double importe = 0;
            if (!Double.isNaN(c) && c > 0 && c <= pendiente + 0.0005) {
                importe = DevolucionService.reembolso(f.detalle(), c, c >= pendiente - 0.0005);
            }
            f.reembolso().setText(!Double.isNaN(c) && c > pendiente + 0.0005 ? "Máx. " + DevolucionService.cantidadTexto(pendiente)
                    : Moneda.formato(importe));
            total += importe;
        }
        return Math.round(total * 100.0) / 100.0;
    }

    /** Imprime el comprobante de una devolución ya registrada (fuera del hilo de JavaFX) */
    public void imprimir(Long devolucionId) {
        try {
            impresionService.imprimirDevolucionAsync(devolucionId).whenComplete((res, error) -> Platform.runLater(() -> {
                if (error != null || res == null || res.esFallo()) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Comprobante de devolución",
                            res != null ? res.mensaje() : "No se pudo imprimir el comprobante.");
                }
            }));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Comprobante de devolución", e.getMessage());
        }
    }

    private static void mostrar(Label lbl, String msg) {
        lbl.setText(msg);
        lbl.setVisible(true);
        lbl.setManaged(true);
    }

    private static void ocultar(Label lbl) {
        lbl.setVisible(false);
        lbl.setManaged(false);
    }
}
