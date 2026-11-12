package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.util.ClaveDonador;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.Optional;
import java.util.function.Function;

/**
 * "Ya doné, tengo mi clave": cuadro para pegar la clave de donador. La valida
 * y guarda {@code activador} (DonadorService.activar: solo ADMIN, auditado);
 * si la rechaza, el error se muestra en el mismo cuadro para corregir.
 */
public final class ClaveDonadorDialogo {

    private ClaveDonadorDialogo() { }

    /** @return los datos del donador si se activó una clave válida */
    public static Optional<ClaveDonador.Datos> pedir(Window owner, Function<String, ClaveDonador.Datos> activador) {
        Dialog<ClaveDonador.Datos> dlg = new Dialog<>();
        dlg.setTitle("Clave de donador");
        dlg.setHeaderText("Pega la clave que recibiste del desarrollador");
        if (owner != null) dlg.initOwner(owner);

        Label ayuda = new Label("Empieza con " + ClaveDonador.PREFIJO + ". (o " + ClaveDonador.PREFIJO_V1 + ". si es antigua) y es "
                + "una sola línea larga; no importa si al copiarla se parte en varias. Vale para todas las cajas del negocio.");
        ayuda.setWrapText(true);
        ayuda.getStyleClass().add("hint");
        TextArea txt = new TextArea();
        txt.setPromptText(ClaveDonador.PREFIJO + ".xxxxxxxx.xxxxxxxx");
        txt.setWrapText(true);
        txt.setPrefRowCount(4);
        txt.setPrefWidth(460);
        txt.getStyleClass().add("text-mono");
        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("field-error-label");
        error.setVisible(false);
        error.setManaged(false);
        VBox caja = new VBox(8, ayuda, txt, error);
        caja.setPrefWidth(480);
        dlg.getDialogPane().setContent(caja);

        ButtonType activar = new ButtonType("Activar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(activar, ButtonType.CANCEL);
        Button btn = (Button) dlg.getDialogPane().lookupButton(activar);
        btn.getStyleClass().add("button-primary");
        btn.disableProperty().bind(txt.textProperty().isEmpty());
        final ClaveDonador.Datos[] resultado = {null};
        btn.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                resultado[0] = activador.apply(txt.getText());
            } catch (NegocioException | ClaveDonador.ClaveInvalidaException e) {
                mostrarError(error, e.getMessage());
                ev.consume();
            } catch (RuntimeException e) {
                mostrarError(error, "No se pudo guardar la clave: " + e.getMessage());
                ev.consume();
            }
        });
        txt.textProperty().addListener((o, a, b) -> { error.setVisible(false); error.setManaged(false); });
        dlg.setResultConverter(b -> b == activar ? resultado[0] : null);
        Ventanas.estilizar(dlg);
        Platform.runLater(txt::requestFocus);
        return dlg.showAndWait().filter(d -> d != null);
    }

    private static void mostrarError(Label error, String mensaje) {
        error.setText(mensaje);
        error.setVisible(true);
        error.setManaged(true);
    }
}
