package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Categoria;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoCaja.Tipo;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.MovimientoCajaService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Ventana "Entrada / salida de efectivo" (CRT-08). La usan Ventas (Ctrl/Cmd+E),
 * Corte, Órdenes de compra (pago al recibir) e Historial (devolución al
 * cancelar). Pide tipo, motivo, monto y concepto; si el cajero no tiene
 * MOVIMIENTOS_CAJA pide la autorización de un administrador, y al terminar
 * ofrece imprimir el comprobante si hay impresora. Sin estado.
 */
@Component
public class MovimientoCajaDialogo {

    @Autowired private MovimientoCajaService movimientoCajaService;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private ImpresionService impresionService;
    @Autowired private SesionService sesionService;

    /** Valores con los que se abre la ventana (todos opcionales) */
    public record Prellenado(Tipo tipo, Categoria categoria, Double monto, String concepto,
                             Long proveedorId, Long ordenCompraId, Long ventaId, boolean fijarCategoria) {
        public static Prellenado entrada() { return new Prellenado(Tipo.ENTRADA, Categoria.CAMBIO, null, null, null, null, null, false); }
        public static Prellenado salida()  { return new Prellenado(Tipo.SALIDA, Categoria.RETIRO, null, null, null, null, null, false); }
        public static Prellenado pagoProveedor(Long proveedorId, Long ordenId, double monto, String concepto) {
            return new Prellenado(Tipo.SALIDA, Categoria.PAGO_PROVEEDOR, monto, concepto, proveedorId, ordenId, null, true);
        }
        public static Prellenado devolucion(Long ventaId, double monto, String concepto) {
            return new Prellenado(Tipo.SALIDA, Categoria.DEVOLUCION, monto, concepto, null, null, ventaId, true);
        }
    }

    /** Abre la ventana; devuelve el movimiento registrado o vacío si se canceló */
    public Optional<MovimientoCaja> mostrar(Window owner, Prellenado prellenado) {
        final Prellenado pre = prellenado != null ? prellenado : Prellenado.salida();
        Dialog<MovimientoCaja> dlg = new Dialog<>();
        dlg.setTitle("Entrada / salida de efectivo");
        dlg.setHeaderText(null);
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        // ── Tipo ────────────────────────────────────────────────────────
        ToggleGroup grupoTipo = new ToggleGroup();
        RadioButton rbEntrada = new RadioButton("Entrada (meter dinero)");
        RadioButton rbSalida  = new RadioButton("Salida (sacar dinero)");
        rbEntrada.setToggleGroup(grupoTipo);
        rbSalida.setToggleGroup(grupoTipo);
        (pre.tipo() == Tipo.ENTRADA ? rbEntrada : rbSalida).setSelected(true);
        HBox tipos = new HBox(16, rbEntrada, rbSalida);

        ComboBox<Categoria> cmbCategoria = new ComboBox<>();
        cmbCategoria.setMaxWidth(Double.MAX_VALUE);
        cmbCategoria.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Categoria c) { return c == null ? "" : c.getEtiqueta(); }
            @Override public Categoria fromString(String s) { return null; }
        });
        Runnable cargarCategorias = () -> {
            Tipo t = rbEntrada.isSelected() ? Tipo.ENTRADA : Tipo.SALIDA;
            Categoria antes = cmbCategoria.getValue();
            cmbCategoria.setItems(FXCollections.observableArrayList(Categoria.de(t)));
            cmbCategoria.setValue(antes != null && antes.getTipo() == t ? antes : cmbCategoria.getItems().get(0));
        };
        cargarCategorias.run();
        if (pre.categoria() != null && pre.categoria().getTipo() == pre.tipo()) cmbCategoria.setValue(pre.categoria());
        grupoTipo.selectedToggleProperty().addListener((o, a, b) -> cargarCategorias.run());
        if (pre.fijarCategoria()) {
            rbEntrada.setDisable(true);
            rbSalida.setDisable(true);
            cmbCategoria.setDisable(true);
        }

        // ── Monto, concepto, proveedor ──────────────────────────────────
        TextField txtMonto = new TextField(pre.monto() != null ? String.format("%.2f", pre.monto()) : "");
        txtMonto.setPromptText("0.00");
        TextField txtConcepto = new TextField(pre.concepto() != null ? pre.concepto() : "");
        txtConcepto.setPromptText("Motivo (ej. «Pago de luz», «Cambio de la mañana»)");

        ComboBox<Proveedor> cmbProveedor = new ComboBox<>();
        cmbProveedor.setMaxWidth(Double.MAX_VALUE);
        cmbProveedor.setPromptText("Proveedor (opcional si escribes el motivo)");
        cmbProveedor.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Proveedor p) { return p == null ? "" : p.getNombre(); }
            @Override public Proveedor fromString(String s) { return null; }
        });
        Label lblProveedor = new Label("Proveedor:");
        Runnable mostrarProveedor = () -> {
            boolean ver = cmbCategoria.getValue() == Categoria.PAGO_PROVEEDOR;
            lblProveedor.setVisible(ver); lblProveedor.setManaged(ver);
            cmbProveedor.setVisible(ver); cmbProveedor.setManaged(ver);
            if (ver && cmbProveedor.getItems().isEmpty()) {
                try {
                    List<Proveedor> provs = proveedorService.listarActivos();
                    cmbProveedor.setItems(FXCollections.observableArrayList(provs));
                    if (pre.proveedorId() != null) {
                        provs.stream().filter(p -> pre.proveedorId().equals(p.getId())).findFirst().ifPresent(cmbProveedor::setValue);
                    }
                } catch (NegocioException e) {
                    // sin proveedores: se escribe el nombre en el motivo
                }
            }
        };
        cmbCategoria.valueProperty().addListener((o, a, b) -> mostrarProveedor.run());
        mostrarProveedor.run();

        // ── Efectivo esperado ahora (solo quien puede verlo) ────────────
        Label lblEnCaja = new Label();
        lblEnCaja.getStyleClass().add("hint");
        lblEnCaja.setWrapText(true);
        try {
            movimientoCajaService.efectivoEsperadoSiPuedeVerlo().ifPresent(v ->
                    lblEnCaja.setText("Efectivo que debe haber ahora en esta caja: " + Moneda.formato(v)));
        } catch (RuntimeException e) {
            lblEnCaja.setText("");
        }
        boolean hayImpresora = impresionService.hayImpresora();
        CheckBox chkImprimir = new CheckBox("Imprimir comprobante");
        chkImprimir.setSelected(hayImpresora);
        chkImprimir.setVisible(hayImpresora);
        chkImprimir.setManaged(hayImpresora);

        Label lblError = new Label();
        lblError.getStyleClass().add("field-error-label");
        lblError.setWrapText(true);
        lblError.setVisible(false);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(110),
                new javafx.scene.layout.ColumnConstraints(320, 320, Double.MAX_VALUE));
        int fila = 0;
        grid.add(tipos, 0, fila++, 2, 1);
        grid.addRow(fila++, new Label("Para qué:"), cmbCategoria);
        grid.addRow(fila++, new Label("Monto: " + Moneda.simbolo()), txtMonto);
        grid.addRow(fila++, new Label("Motivo:"), txtConcepto);
        grid.addRow(fila++, lblProveedor, cmbProveedor);
        grid.add(lblEnCaja, 0, fila++, 2, 1);
        grid.add(chkImprimir, 0, fila++, 2, 1);
        grid.add(lblError, 0, fila, 2, 1);
        dlg.getDialogPane().setContent(grid);

        ButtonType registrar = new ButtonType("Registrar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(registrar, ButtonType.CANCEL);
        javafx.scene.control.Button btn = (javafx.scene.control.Button) dlg.getDialogPane().lookupButton(registrar);
        final MovimientoCaja[] resultado = { null };
        btn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            lblError.setVisible(false);
            double monto;
            try {
                monto = CalculoCobro.parsearNumero(txtMonto.getText());
                if (Double.isNaN(monto)) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                mostrarError(lblError, "Escribe el monto como número (ej: 250 o 1,250.50).");
                txtMonto.requestFocus();
                ev.consume();
                return;
            }
            Tipo tipo = rbEntrada.isSelected() ? Tipo.ENTRADA : Tipo.SALIDA;
            Categoria cat = cmbCategoria.getValue();
            Proveedor prov = cat == Categoria.PAGO_PROVEEDOR ? cmbProveedor.getValue() : null;
            MovimientoCajaService.Solicitud s = new MovimientoCajaService.Solicitud(tipo, cat, monto, txtConcepto.getText(),
                    prov != null ? prov.getId() : pre.proveedorId(), pre.ordenCompraId(), pre.ventaId());
            List<Autorizacion> auts = List.of();
            if (!sesionService.tienePermiso(Permiso.MOVIMIENTOS_CAJA)) {
                Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, Permiso.MOVIMIENTOS_CAJA);
                if (aut.isEmpty()) { ev.consume(); return; }
                auts = List.of(aut.get());
            }
            try {
                resultado[0] = movimientoCajaService.registrar(s, auts);
            } catch (NegocioException e) {
                mostrarError(lblError, e.getMessage());
                ev.consume();
                return;
            }
            if (chkImprimir.isSelected() && resultado[0] != null) {
                imprimir(resultado[0].getId());
            }
        });
        dlg.setResultConverter(b -> b == registrar ? resultado[0] : null);
        Platform.runLater(() -> {
            if (pre.monto() != null) txtConcepto.requestFocus(); else txtMonto.requestFocus();
        });
        return dlg.showAndWait().filter(m -> m != null);
    }

    /** Imprime el comprobante de un movimiento ya registrado (fuera del hilo de JavaFX) */
    public void imprimir(Long movimientoId) {
        try {
            impresionService.imprimirMovimientoCajaAsync(movimientoId).whenComplete((res, error) -> Platform.runLater(() -> {
                if (error != null || res == null || res.esFallo()) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Comprobante",
                            res != null ? res.mensaje() : "No se pudo imprimir el comprobante.");
                }
            }));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Comprobante", e.getMessage());
        }
    }

    private static void mostrarError(Label lbl, String msg) {
        lbl.setText(msg);
        lbl.setVisible(true);
    }
}
