package me.eliassanchezfernandez.puntodeventa.controller.estadisticas;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.controller.estadisticas.AyudaEstadisticas.Pagina;
import me.eliassanchezfernandez.puntodeventa.controller.estadisticas.AyudaEstadisticas.Tema;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.List;

/**
 * Tutorial multipágina de Estadísticas (primera vez por usuario y botón
 * «? Ayuda») y la ventana de ayuda de una sección (botón «?»).
 *
 * Teclado: Enter = Siguiente (en la última página, Terminar); Esc = Cerrar;
 * ← → = página anterior / siguiente. Cabe en 1024×768 (700×460).
 */
public final class TutorialEstadisticasDialogo {

    private static final double ANCHO = 700;
    private static final double ALTO  = 460;

    private TutorialEstadisticasDialogo() { }

    /** Muestra el tutorial y espera a que se cierre (por cualquier botón o Esc) */
    public static void mostrar(Window owner, List<Pagina> paginas) {
        if (paginas == null || paginas.isEmpty()) return;
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Tutorial de Estadísticas");
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        ButtonType anterior  = new ButtonType("Anterior", ButtonBar.ButtonData.BACK_PREVIOUS);
        ButtonType siguiente = new ButtonType("Siguiente", ButtonBar.ButtonData.OK_DONE);
        ButtonType cerrar    = new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE);
        DialogPane pane = dlg.getDialogPane();
        pane.getButtonTypes().addAll(anterior, siguiente, cerrar);
        pane.getStyleClass().add("tutorial-pane");
        pane.setPrefSize(ANCHO, ALTO);

        Label titulo = new Label();
        titulo.getStyleClass().add("tutorial-titulo");
        titulo.setWrapText(true);
        Label paso = new Label();
        paso.getStyleClass().add("tutorial-paso");
        HBox puntos = new HBox(6);
        puntos.setAlignment(Pos.CENTER_LEFT);
        Label texto = new Label();
        texto.getStyleClass().add("tutorial-texto");
        texto.setWrapText(true);
        texto.setMaxWidth(Double.MAX_VALUE);
        ScrollPane scroll = new ScrollPane(texto);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("tutorial-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox cabecera = new HBox(10, titulo, espacio, paso);
        cabecera.setAlignment(Pos.CENTER_LEFT);
        VBox caja = new VBox(10, cabecera, puntos, scroll);
        caja.setPadding(new Insets(6, 4, 0, 4));
        pane.setContent(caja);

        Button btnAnterior  = (Button) pane.lookupButton(anterior);
        Button btnSiguiente = (Button) pane.lookupButton(siguiente);
        btnSiguiente.setDefaultButton(true);
        btnAnterior.setGraphic(me.eliassanchezfernandez.puntodeventa.util.Iconos.de("izquierda"));
        int[] indice = {0};
        Runnable pintar = () -> {
            Pagina p = paginas.get(indice[0]);
            titulo.setText(p.titulo());
            texto.setText(p.texto());
            paso.setText("Página " + (indice[0] + 1) + " de " + paginas.size());
            puntos.getChildren().clear();
            for (int i = 0; i < paginas.size(); i++) {
                Label punto = new Label();
                punto.getStyleClass().add(i == indice[0] ? "tutorial-punto-activo" : "tutorial-punto");
                puntos.getChildren().add(punto);
            }
            boolean ultima = indice[0] == paginas.size() - 1;
            btnAnterior.setDisable(indice[0] == 0);
            btnSiguiente.setText(ultima ? "Terminar" : "Siguiente");
            btnSiguiente.setGraphic(ultima ? null : me.eliassanchezfernandez.puntodeventa.util.Iconos.de("derecha"));
            btnSiguiente.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            scroll.setVvalue(0);
        };
        // Anterior / Siguiente NO cierran el diálogo (se consume el evento), salvo "Terminar"
        btnAnterior.addEventFilter(ActionEvent.ACTION, e -> {
            if (indice[0] > 0) { indice[0]--; pintar.run(); }
            e.consume();
        });
        btnSiguiente.addEventFilter(ActionEvent.ACTION, e -> {
            if (indice[0] < paginas.size() - 1) { indice[0]++; pintar.run(); e.consume(); }
        });
        pane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.RIGHT && indice[0] < paginas.size() - 1) { indice[0]++; pintar.run(); e.consume(); }
            else if (e.getCode() == KeyCode.LEFT && indice[0] > 0)               { indice[0]--; pintar.run(); e.consume(); }
        });
        pintar.run();
        dlg.setOnShown(e -> btnSiguiente.requestFocus());
        dlg.showAndWait();
    }

    /** Ventana de ayuda de UNA sección (botón «?»): título y explicación; Enter o Esc la cierran */
    public static void mostrarAyuda(Window owner, Tema tema) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Ayuda · " + tema.titulo());
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);
        DialogPane pane = dlg.getDialogPane();
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.getStyleClass().add("tutorial-pane");
        pane.setPrefSize(560, 360);

        Label titulo = new Label(tema.titulo());
        titulo.getStyleClass().add("tutorial-titulo");
        titulo.setWrapText(true);
        Label texto = new Label(tema.texto());
        texto.getStyleClass().add("tutorial-texto");
        texto.setWrapText(true);
        texto.setMaxWidth(Double.MAX_VALUE);
        ScrollPane scroll = new ScrollPane(texto);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("tutorial-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox caja = new VBox(10, titulo, scroll);
        caja.setPadding(new Insets(6, 4, 0, 4));
        pane.setContent(caja);
        Button cerrar = (Button) pane.lookupButton(ButtonType.CLOSE);
        cerrar.setDefaultButton(true);
        dlg.showAndWait();
    }
}
