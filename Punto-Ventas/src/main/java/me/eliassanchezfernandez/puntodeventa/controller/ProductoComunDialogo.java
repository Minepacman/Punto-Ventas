package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.ProductoComun;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.Optional;

/**
 * Ventana "Producto común" (Ctrl/Cmd+P en Ventas): vender algo que no está en
 * el catálogo. Pide nombre (propone "Producto común", seleccionado para que al
 * teclear se reemplace), precio unitario y cantidad (1; admite decimales para
 * kilos). Enter agrega el renglón, Esc cancela. Si algo no es válido, lo dice
 * debajo de los campos y la ventana sigue abierta. Sin Spring: la validación de
 * negocio real la hace VentaService al crear el renglón.
 */
public final class ProductoComunDialogo {

    private ProductoComunDialogo() { }

    /** Lo que tecleó el cajero, ya interpretado (precio y cantidad a la mexicana: "12,50" = 12.5) */
    public record Captura(String nombre, double precio, double cantidad) { }

    public static Optional<Captura> mostrar(Window owner) {
        Dialog<Captura> dlg = new Dialog<>();
        dlg.setTitle("Producto común");
        dlg.setHeaderText("Vender algo que no está en el catálogo");
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        TextField txtNombre = new TextField(ProductoComun.NOMBRE_POR_DEFECTO);
        txtNombre.setPromptText("ej. Bolsa de hielo");
        txtNombre.setPrefColumnCount(22);
        TextField txtPrecio = new TextField();
        txtPrecio.setPromptText("ej. 25.00");
        TextField txtCantidad = new TextField("1");
        txtCantidad.setPromptText("1  (o 1.5 si es por kilo)");

        Label error = new Label();
        error.getStyleClass().add("text-danger");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        Label nota = new Label("No descuenta inventario. En el corte se estima una ganancia del "
                + Math.round(ProductoComun.MARGEN_GANANCIA * 100) + " % sobre lo cobrado.");
        nota.getStyleClass().add("hint");
        nota.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Nombre:"), txtNombre);
        grid.addRow(1, new Label("Precio unitario ($):"), txtPrecio);
        grid.addRow(2, new Label("Cantidad:"), txtCantidad);
        grid.add(error, 0, 3, 2, 1);
        grid.add(nota, 0, 4, 2, 1);
        dlg.getDialogPane().setContent(grid);

        ButtonType agregar = new ButtonType("Agregar", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(agregar, ButtonType.CANCEL);

        // Validar antes de cerrar: si algo falla, se muestra el motivo y la ventana sigue abierta
        Button btnAgregar = (Button) dlg.getDialogPane().lookupButton(agregar);
        btnAgregar.addEventFilter(ActionEvent.ACTION, e -> {
            String problema = validar(txtPrecio.getText(), txtCantidad.getText());
            if (problema != null) {
                error.setText(problema);
                error.setVisible(true);
                error.setManaged(true);
                (problema.startsWith("Escribe el precio") || problema.startsWith("El precio") ? txtPrecio : txtCantidad).requestFocus();
                e.consume();
            }
        });
        dlg.setResultConverter(b -> b == agregar
                ? new Captura(ProductoComun.nombreNormalizado(txtNombre.getText()),
                              CalculoCobro.parsearNumero(txtPrecio.getText()),
                              CalculoCobro.parsearNumero(txtCantidad.getText()))
                : null);

        Platform.runLater(() -> {
            txtNombre.requestFocus();
            txtNombre.selectAll();      // teclear reemplaza "Producto común"
        });
        return dlg.showAndWait();
    }

    /** Mensaje de error, o null si precio y cantidad son válidos */
    static String validar(String precioTexto, String cantidadTexto) {
        double precio = CalculoCobro.parsearNumero(precioTexto);
        if (Double.isNaN(precio) || precio <= 0) return "Escribe el precio del producto (mayor que cero).";
        if (precio > 1_000_000) return "El precio es demasiado grande.";
        double cantidad = CalculoCobro.parsearNumero(cantidadTexto);
        if (Double.isNaN(cantidad) || cantidad <= 0) return "Escribe la cantidad (mayor que cero; puede llevar decimales).";
        if (cantidad > 100000) return "La cantidad es demasiado grande.";
        return null;
    }
}
