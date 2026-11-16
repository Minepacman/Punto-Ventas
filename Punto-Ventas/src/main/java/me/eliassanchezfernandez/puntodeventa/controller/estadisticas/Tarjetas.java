package me.eliassanchezfernandez.puntodeventa.controller.estadisticas;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/** Piezas de interfaz comunes a las secciones de Estadísticas (tarjeta con título, «?», columnas) */
public final class Tarjetas {

    private Tarjetas() { }

    /** Botón «?» pequeño que abre la ayuda de la clave */
    public static Button botonAyuda(String clave, SeccionEstadisticas.Acciones acciones) {
        Button b = new Button("?");
        b.getStyleClass().addAll("button-ghost", "button-sm", "ayuda-button");
        b.setTooltip(new Tooltip("¿Qué muestra esta sección?"));
        b.setFocusTraversable(false);
        b.setOnAction(e -> acciones.ayuda(clave));
        return b;
    }

    /** Fila de cabecera de una tarjeta: título, «?» y, a la derecha, los controles dados */
    public static HBox cabecera(String titulo, String clave, SeccionEstadisticas.Acciones acciones, Node... derecha) {
        Label lbl = new Label(titulo);
        lbl.getStyleClass().add("card-title");
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox h = new HBox(8, lbl, botonAyuda(clave, acciones), espacio);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getChildren().addAll(derecha);
        return h;
    }

    /** Tarjeta (.card) vertical con separación de 8 */
    public static VBox tarjeta(Node... hijos) {
        VBox v = new VBox(8, hijos);
        v.getStyleClass().add("card");
        return v;
    }

    public static Label subtitulo(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("subtitle");
        l.setWrapText(true);
        return l;
    }

    public static <T> TableColumn<T, String> columna(String titulo, double ancho, boolean numerica, Function<T, String> valor) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setPrefWidth(ancho);
        c.setCellValueFactory(d -> new SimpleStringProperty(valor.apply(d.getValue())));
        if (numerica) c.getStyleClass().add("align-right");
        return c;
    }

    /** ¿Se ve? (ella y todos sus contenedores visibles y en una escena; una pestaña no elegida no se ve) */
    public static boolean visibleEnPantalla(Node n) {
        if (n == null || n.getScene() == null) return false;
        for (Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return true;
    }

    /**
     * Corre {@code accion} (una sola vez) cuando el nodo se vea: ya, o cuando su pestaña se elija. Así las
     * secciones de pestañas que nadie abre no arman cientos de tablas en memoria (ver SeccionSurtido).
     */
    public static void cuandoSeVea(Node n, Runnable accion) {
        if (visibleEnPantalla(n)) {
            accion.run();
            return;
        }
        java.util.List<Node> vigilados = new java.util.ArrayList<>();
        for (Node p = n; p != null; p = p.getParent()) vigilados.add(p);
        boolean[] hecho = {false};
        javafx.beans.InvalidationListener[] oyente = new javafx.beans.InvalidationListener[1];
        oyente[0] = o -> {
            if (hecho[0] || !visibleEnPantalla(n)) return;
            hecho[0] = true;
            for (Node p : vigilados) p.visibleProperty().removeListener(oyente[0]);
            n.sceneProperty().removeListener(oyente[0]);
            accion.run();
        };
        for (Node p : vigilados) p.visibleProperty().addListener(oyente[0]);
        n.sceneProperty().addListener(oyente[0]);
    }

    /** Alto de una tabla según sus filas (encabezado + filas de 2.6em), acotado */
    public static void ajustarAlto(TableView<?> tabla, int filas, double minimo, double maximo) {
        double alto = 38 + Math.max(1, filas) * 34.0 + 2;
        tabla.setPrefHeight(Math.max(minimo, Math.min(maximo, alto)));
    }

    /** "3", "2.5", "0.125": sin residuos de coma flotante ("3." por 3.0000000000000004) */
    public static String cantidad(double v) {
        double r = Math.round(v * 1000.0) / 1000.0;
        if (r == Math.floor(r)) return String.valueOf((long) r);
        return String.format(java.util.Locale.US, "%.3f", r).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static String dias(Double d) {
        if (d == null) return "—";
        if (d > 999) return "+999";
        return String.valueOf(Math.round(d));
    }
}
