package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.Entrada;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Buscador de claves del SAT (producto/servicio y unidad) sobre el
 * subconjunto incluido en el programa. Escribe parte de la clave o de la
 * descripción y elige con doble clic o Aceptar.
 */
public final class CatalogoSatDialogo {

    private CatalogoSatDialogo() { }

    public static Optional<Entrada> elegirProdServ(String textoInicial) {
        return elegir("Clave de producto o servicio (SAT)",
                "Las más comunes en abarrotes. Si no está la tuya, escribe la clave de 8 dígitos directamente en el campo.",
                textoInicial, CatalogoSat::buscarProdServ);
    }

    public static Optional<Entrada> elegirUnidad(String textoInicial) {
        return elegir("Clave de unidad (SAT)",
                "Unidades de medida más usadas. Puedes escribir otra clave del catálogo directamente en el campo.",
                textoInicial, CatalogoSat::buscarUnidad);
    }

    private static Optional<Entrada> elegir(String titulo, String ayuda, String textoInicial,
                                            Function<String, List<Entrada>> buscador) {
        Dialog<Entrada> dlg = new Dialog<>();
        dlg.setTitle(titulo);
        dlg.setHeaderText(titulo);
        TextField txt = new TextField();
        txt.setPromptText("Buscar por clave o descripción…");
        ListView<Entrada> lista = new ListView<>();
        lista.setPrefSize(520, 320);
        Label nota = new Label(ayuda);
        nota.setWrapText(true);
        nota.getStyleClass().add("hint");

        Runnable filtrar = () -> lista.setItems(FXCollections.observableArrayList(buscador.apply(txt.getText())));
        txt.textProperty().addListener((o, a, b) -> filtrar.run());
        String inicial = textoInicial == null ? "" : textoInicial.trim();
        // Si ya hay una clave exacta no filtra por ella (se vería solo esa); muestra todo
        txt.setText(inicial.matches("\\d{8}|[A-Za-z0-9]{2,3}") ? "" : inicial);
        filtrar.run();

        VBox caja = new VBox(8, txt, lista, nota);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(b -> b == ButtonType.OK ? lista.getSelectionModel().getSelectedItem() : null);
        lista.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && lista.getSelectionModel().getSelectedItem() != null) {
                dlg.setResult(lista.getSelectionModel().getSelectedItem());
                dlg.close();
            }
        });
        Ventanas.estilizar(dlg);
        javafx.application.Platform.runLater(txt::requestFocus);
        return dlg.showAndWait();
    }
}
