package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.util.Atajo;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.ArrayList;
import java.util.List;

/**
 * Diálogo "Atajos de teclado" (F10): una tabla con todos los atajos del
 * catálogo {@link Atajo}, con la tecla principal, la alternativa (Ctrl/Cmd para
 * laptops) y el permiso que exige la acción. Cabe en 1024×768.
 */
public final class AtajosDialogo {

    private AtajosDialogo() { }

    /** Un renglón de la tabla */
    record Fila(String donde, String teclas, String accion, String permiso) { }

    public static void mostrar(Window owner) {
        boolean mac = Atajo.esMac();
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Atajos de teclado");
        dlg.setHeaderText("Atajos de teclado" + (mac ? "  (en Mac, Cmd en lugar de Ctrl; las teclas F pueden requerir Fn)" : ""));
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        List<Fila> filas = new ArrayList<>();
        for (Atajo a : Atajo.values()) {
            String donde = a.getAmbito() == Atajo.Ambito.GLOBAL ? "General" : "Ventas";
            String accion = a.getDescripcion() + (a.getNota() != null ? "  (" + a.getNota() + ")" : "");
            String permiso = a.getPermiso() == null ? "" : a.getPermiso().getDescripcion() + " o autorización";
            filas.add(new Fila(donde, a.etiquetaTeclas(mac), accion, permiso));
        }
        filas.add(new Fila("Ventas", "cantidad*código + Enter", "Agregar varias piezas de un golpe (ej. 3*7501… o 1.5*código a granel)", ""));
        filas.add(new Fila("Ventas", "↓ / ↑ en el código", "Pasar al ticket y recorrerlo con las flechas", ""));
        filas.add(new Fila("Ventas", "Esc", "Limpiar el código / volver al campo de código; en diálogos, cerrar", ""));
        filas.add(new Fila("Ventas", "Enter", "Agregar el código tecleado; en diálogos, confirmar", ""));
        filas.add(new Fila("Cobro", "Enter / Esc", "Cobrar / regresar al ticket (Enter sin montos = efectivo exacto)", ""));
        filas.add(new Fila("Cobro", "F1 … F5", "Billetes rápidos: exacto, siguiente decena, 50, 100, 200…", ""));
        filas.add(new Fila("Cobro", "F8 / F9 / F10", "Todo el total con tarjeta / transferencia / vales", ""));
        filas.add(new Fila("Venta cobrada", "Enter / F", "Continuar / Facturar la venta (si está configurado)", "Facturar"));

        TableView<Fila> tabla = new TableView<>(FXCollections.observableArrayList(filas));
        TableColumn<Fila, String> cDonde = new TableColumn<>("Dónde");
        cDonde.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().donde()));
        cDonde.setPrefWidth(90);
        TableColumn<Fila, String> cTeclas = new TableColumn<>("Teclas");
        cTeclas.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().teclas()));
        cTeclas.setPrefWidth(190);
        TableColumn<Fila, String> cAccion = new TableColumn<>("Acción");
        cAccion.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().accion()));
        cAccion.setPrefWidth(420);
        TableColumn<Fila, String> cPermiso = new TableColumn<>("Permiso");
        cPermiso.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().permiso()));
        cPermiso.setPrefWidth(200);
        tabla.getColumns().add(cDonde);
        tabla.getColumns().add(cTeclas);
        tabla.getColumns().add(cAccion);
        tabla.getColumns().add(cPermiso);
        tabla.setPrefSize(920, 520);
        tabla.setFocusTraversable(false);

        Label nota = new Label("Los atajos que exigen un permiso lo piden al momento: si el usuario no lo tiene, "
                + "un administrador puede autorizarlo con su contraseña.");
        nota.setWrapText(true);
        nota.getStyleClass().add("hint");

        VBox caja = new VBox(8, tabla, nota);
        caja.setPadding(new Insets(6));
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.showAndWait();
    }
}
