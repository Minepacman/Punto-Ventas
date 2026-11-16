package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.service.CuentaClienteService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Buscador de clientes para anotar una venta a cuenta (fiado): se escribe parte
 * del nombre o del teléfono, flechas para moverse y Enter elige. Si el cliente
 * no existe, "Nuevo cliente" lo da de alta solo con el nombre escrito.
 */
public final class ClienteSelectorDialogo {

    private ClienteSelectorDialogo() { }

    /**
     * @param crear alta rápida con el nombre escrito (puede pedir autorización); null si no se pudo
     */
    public static Optional<CobroDialogo.ClienteFiado> mostrar(Window owner, CuentaClienteService servicio,
                                                              Function<String, Cliente> crear) {
        Dialog<Cliente> dlg = new Dialog<>();
        dlg.setTitle("Cliente para fiado");
        dlg.setHeaderText(null);
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        TextField txtBuscar = new TextField();
        txtBuscar.setPromptText("Nombre o teléfono del cliente");
        ObservableList<Cliente> lista = FXCollections.observableArrayList();
        ListView<Cliente> vista = new ListView<>(lista);
        vista.setPrefHeight(260);
        vista.setPlaceholder(new Label("No hay clientes con ese nombre. Usa «Nuevo cliente» para darlo de alta."));
        vista.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Cliente c, boolean vacio) {
                super.updateItem(c, vacio);
                setText(vacio || c == null ? null : renglon(c));
            }
        });
        Label lblError = new Label();
        lblError.getStyleClass().add("field-error-label");
        lblError.setWrapText(true);
        Button btnNuevo = new Button("Nuevo cliente");
        btnNuevo.getStyleClass().add("button-sm");
        HBox filaNuevo = new HBox(8, new Label("¿No está?"), btnNuevo);
        Label ayuda = new Label("Escribe para buscar · ↑↓ para elegir · Enter confirma · Esc regresa");
        ayuda.getStyleClass().add("hint");
        VBox contenido = new VBox(8, txtBuscar, vista, filaNuevo, lblError, ayuda);
        contenido.setPadding(new Insets(12));
        contenido.setPrefWidth(460);
        VBox.setVgrow(vista, Priority.ALWAYS);
        dlg.getDialogPane().setContent(contenido);

        ButtonType elegir = new ButtonType("Elegir", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(elegir, ButtonType.CANCEL);
        Button btnElegir = (Button) dlg.getDialogPane().lookupButton(elegir);
        btnElegir.disableProperty().bind(vista.getSelectionModel().selectedItemProperty().isNull());

        Runnable filtrar = () -> {
            try {
                List<Cliente> encontrados = servicio.buscar(txtBuscar.getText(), false);
                lista.setAll(encontrados);
                if (!encontrados.isEmpty()) vista.getSelectionModel().selectFirst();
                lblError.setText("");
            } catch (NegocioException e) {
                lblError.setText(e.getMessage());
            }
            String t = txtBuscar.getText() == null ? "" : txtBuscar.getText().trim();
            btnNuevo.setText(t.isEmpty() ? "Nuevo cliente" : "Nuevo cliente: «" + (t.length() > 30 ? t.substring(0, 30) + "…" : t) + "»");
        };
        txtBuscar.textProperty().addListener((o, a, b) -> filtrar.run());
        filtrar.run();

        // Flechas desde el campo de búsqueda mueven la selección; Enter elige
        txtBuscar.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) { vista.getSelectionModel().selectNext(); e.consume(); }
            if (e.getCode() == KeyCode.UP)   { vista.getSelectionModel().selectPrevious(); e.consume(); }
        });
        txtBuscar.setOnAction(e -> { if (!btnElegir.isDisable()) btnElegir.fire(); });
        vista.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !btnElegir.isDisable()) btnElegir.fire(); });

        final Cliente[] creado = { null };
        btnNuevo.setOnAction(e -> {
            String nombre = txtBuscar.getText() == null ? "" : txtBuscar.getText().trim();
            if (nombre.isEmpty()) {
                nombre = Ventanas.pedirTexto("Nuevo cliente", "Nombre del cliente (con apellido o calle para no confundirlo)",
                        "Ej. Doña Lupe, calle Juárez").orElse("").trim();
                if (nombre.isEmpty()) return;
            }
            try {
                Cliente c = crear.apply(nombre);
                if (c == null) return;
                creado[0] = c;
                dlg.setResult(c);
                dlg.close();
            } catch (NegocioException ex) {
                lblError.setText(ex.getMessage());
            }
        });

        dlg.setResultConverter(b -> {
            if (creado[0] != null) return creado[0];
            return b == elegir ? vista.getSelectionModel().getSelectedItem() : null;
        });
        dlg.setOnShown(e -> Platform.runLater(txtBuscar::requestFocus));

        try {
            return dlg.showAndWait().map(ClienteSelectorDialogo::aFiado);
        } catch (RuntimeException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Clientes", e.getMessage());
            return Optional.empty();
        }
    }

    public static CobroDialogo.ClienteFiado aFiado(Cliente c) {
        return new CobroDialogo.ClienteFiado(c.getId(), c.getNombre(), c.getSaldoDeuda(), c.getLimiteCredito());
    }

    static String renglon(Cliente c) {
        StringBuilder sb = new StringBuilder(c.getNombre());
        if (c.getTelefono() != null) sb.append("  ·  ").append(c.getTelefono());
        double saldo = c.getSaldoDeuda();
        sb.append("  ·  ").append(saldo < -0.004 ? "a favor " + Moneda.formato(-saldo)
                : saldo > 0.004 ? "debe " + Moneda.formato(saldo) : "no debe");
        if (c.tieneLimite()) sb.append(" de ").append(Moneda.formato(c.getLimiteCredito()));
        return sb.toString();
    }
}
