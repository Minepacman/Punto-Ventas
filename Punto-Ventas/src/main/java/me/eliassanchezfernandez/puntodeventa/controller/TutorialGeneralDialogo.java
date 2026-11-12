package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import me.eliassanchezfernandez.puntodeventa.util.Atajo;
import me.eliassanchezfernandez.puntodeventa.util.TutorialGeneral;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.List;
import java.util.Map;

/**
 * Recorrido general por las pestañas: una tarjeta flotante (ventana sin marco,
 * modal sobre la principal) que en cada paso selecciona la pestaña real de la
 * que habla y resalta su encabezado. El contenido viene de
 * {@link TutorialGeneral}; aquí solo está la presentación.
 *
 * Teclado: Enter o → siguiente, ← anterior, Esc salir. Cabe en 1024×768.
 *
 * La pestaña Estadísticas solo se resalta, no se selecciona: al abrirla por
 * primera vez muestra su propio tutorial y los dos se encimarían.
 */
public final class TutorialGeneralDialogo {

    static final String CLASE_RESALTADA = "tutorial-resaltada";
    static final double ANCHO = 560;
    /** Distancia desde el borde superior de la ventana principal (bajo la barra y las pestañas) */
    static final double MARGEN_SUPERIOR = 150;
    static final String TAB_SIN_SELECCIONAR = "tabEstadisticas";

    private TutorialGeneralDialogo() { }

    /**
     * Muestra el recorrido y espera a que termine (o se salte).
     * @param tabs pestañas por fx:id que el usuario SÍ puede ver
     */
    public static void mostrar(Stage owner, TabPane tabPane, Map<String, Tab> tabs) {
        List<TutorialGeneral.Paso> pasos = TutorialGeneral.pasosPara(tabs.keySet());
        if (pasos.isEmpty()) return;
        boolean mac = Atajo.esMac();
        Tab pestanaInicial = tabPane.getSelectionModel().getSelectedItem();

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Tutorial");
        stage.setResizable(false);

        Label lblPaso   = new Label();
        lblPaso.getStyleClass().add("tutorial-paso");
        Label lblTitulo = new Label();
        lblTitulo.getStyleClass().add("tutorial-titulo");
        lblTitulo.setWrapText(true);
        Label lblTexto  = new Label();
        lblTexto.getStyleClass().add("tutorial-texto");
        lblTexto.setWrapText(true);
        lblTexto.setMaxWidth(ANCHO - 48);
        Label lblAtajos = new Label();
        lblAtajos.getStyleClass().add("tutorial-atajos");
        lblAtajos.setWrapText(true);
        lblAtajos.setMaxWidth(ANCHO - 48);

        Button btnSaltar    = new Button("Saltar (Esc)");
        btnSaltar.getStyleClass().add("button-ghost");
        Button btnAnterior  = new Button("Anterior", me.eliassanchezfernandez.puntodeventa.util.Iconos.de("izquierda"));
        Button btnSiguiente = new Button();
        btnSiguiente.getStyleClass().add("button-primary");
        btnSiguiente.setDefaultButton(true);
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox botones = new HBox(8, btnSaltar, espacio, btnAnterior, btnSiguiente);
        botones.setAlignment(Pos.CENTER_RIGHT);

        VBox tarjeta = new VBox(8, lblPaso, lblTitulo, lblTexto, lblAtajos, botones);
        tarjeta.getStyleClass().add("tutorial-card");
        tarjeta.setPrefWidth(ANCHO);
        tarjeta.setMaxWidth(ANCHO);
        VBox.setMargin(botones, new Insets(8, 0, 0, 0));

        Scene scene = new Scene(tarjeta);
        scene.setFill(Color.TRANSPARENT);
        Ventanas.aplicarCss(scene);
        stage.setScene(scene);

        int[] indice = {0};
        Runnable pintar = () -> {
            TutorialGeneral.Paso paso = pasos.get(indice[0]);
            quitarResaltado(tabs);
            if (paso.tabId() != null) {
                Tab tab = tabs.get(paso.tabId());
                if (tab != null) {
                    tab.getStyleClass().add(CLASE_RESALTADA);
                    if (!TAB_SIN_SELECCIONAR.equals(paso.tabId())) tabPane.getSelectionModel().select(tab);
                }
            }
            lblPaso.setText("Paso " + (indice[0] + 1) + " de " + pasos.size());
            lblTitulo.setText(paso.titulo());
            lblTexto.setText(paso.texto());
            String atajos = paso.textoAtajos(mac);
            lblAtajos.setText(atajos);
            lblAtajos.setVisible(!atajos.isEmpty());
            lblAtajos.setManaged(!atajos.isEmpty());
            boolean ultimo = indice[0] == pasos.size() - 1;
            btnAnterior.setDisable(indice[0] == 0);
            btnSiguiente.setText(ultimo ? "Terminar" : "Siguiente");
            btnSiguiente.setGraphic(ultimo ? null : me.eliassanchezfernandez.puntodeventa.util.Iconos.de("derecha"));
            btnSiguiente.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            btnSaltar.setVisible(!ultimo);
            btnSaltar.setManaged(!ultimo);
            stage.sizeToScene();
            colocar(stage, owner);
        };
        Runnable siguiente = () -> {
            if (indice[0] >= pasos.size() - 1) stage.close();
            else { indice[0]++; pintar.run(); }
        };
        Runnable anterior = () -> {
            if (indice[0] > 0) { indice[0]--; pintar.run(); }
        };
        btnSiguiente.setOnAction(e -> siguiente.run());
        btnAnterior.setOnAction(e -> anterior.run());
        btnSaltar.setOnAction(e -> stage.close());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case ENTER, RIGHT, SPACE -> { siguiente.run(); e.consume(); }
                case LEFT, BACK_SPACE    -> { anterior.run();  e.consume(); }
                case ESCAPE              -> { stage.close();   e.consume(); }
                default -> { }
            }
        });

        // Si mueven la ventana principal, la tarjeta la sigue
        ChangeListener<Number> seguir = (obs, a, b) -> colocar(stage, owner);
        owner.xProperty().addListener(seguir);
        owner.yProperty().addListener(seguir);
        owner.widthProperty().addListener(seguir);

        pintar.run();
        try {
            stage.showAndWait();
        } finally {
            owner.xProperty().removeListener(seguir);
            owner.yProperty().removeListener(seguir);
            owner.widthProperty().removeListener(seguir);
            quitarResaltado(tabs);
            if (pestanaInicial != null && tabPane.getTabs().contains(pestanaInicial)) {
                tabPane.getSelectionModel().select(pestanaInicial);
            }
        }
    }

    private static void quitarResaltado(Map<String, Tab> tabs) {
        for (Tab t : tabs.values()) t.getStyleClass().remove(CLASE_RESALTADA);
    }

    /** Centrada horizontalmente sobre la principal, debajo de la barra de pestañas; sin salirse de la pantalla */
    static void colocar(Stage stage, Stage owner) {
        double ancho = stage.getWidth() > 0 ? stage.getWidth() : ANCHO;
        double x = owner.getX() + (owner.getWidth() - ancho) / 2;
        double y = owner.getY() + MARGEN_SUPERIOR;
        stage.setX(Math.max(owner.getX(), x));
        stage.setY(Math.max(owner.getY(), y));
    }
}
