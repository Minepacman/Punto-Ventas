package me.eliassanchezfernandez.puntodeventa.util;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/**
 * Icono Lucide (ver {@link Iconos}) como nodo JavaFX: trazos SVG sin relleno escalados desde el viewBox
 * de 24 unidades al tamaño pedido (16 px por omisión). El color del trazo sigue al color de texto
 * ({@code textFill}) del botón, pestaña, etiqueta o enlace que lo contiene, así se ve bien en botones
 * primarios/peligro/éxito y en la franja oscura de arriba, también al pasar el ratón o deshabilitarlo.
 *
 * <p>Se puede usar en FXML: {@code <graphic><Icono nombre="buscar" tamano="18"/></graphic>}.</p>
 */
public class Icono extends Pane {

    private final ObjectProperty<Paint> color = new SimpleObjectProperty<>(Color.web("#4c566a"));
    private final Scale escala = new Scale(1, 1, 0, 0);
    private String nombre;
    private double tamano = 16;
    private Labeled duenio;

    public Icono() {
        getStyleClass().add("icono");
        setMouseTransparent(true);
        getChildren().add(new javafx.scene.Group());
        parentProperty().addListener((o, a, b) -> enlazar());
        sceneProperty().addListener((o, a, b) -> enlazar());
        dimensionar();
    }

    public Icono(String nombre) {
        this();
        setNombre(nombre);
    }

    public Icono(String nombre, double tamano) {
        this();
        this.tamano = tamano;
        setNombre(nombre);
    }

    public String getNombre() { return nombre; }

    public void setNombre(String nombre) {
        this.nombre = nombre;
        javafx.scene.Group g = new javafx.scene.Group();
        for (String d : Iconos.trazos(nombre)) {
            SVGPath p = new SVGPath();
            p.setContent(d);
            p.setFill(null);
            p.setStrokeWidth(2);
            p.setStrokeLineCap(StrokeLineCap.ROUND);
            p.setStrokeLineJoin(StrokeLineJoin.ROUND);
            p.strokeProperty().bind(color);
            g.getChildren().add(p);
        }
        g.getTransforms().add(escala);
        getChildren().setAll(g);
    }

    public double getTamano() { return tamano; }

    public void setTamano(double tamano) {
        this.tamano = tamano;
        dimensionar();
    }

    private void dimensionar() {
        setMinSize(tamano, tamano);
        setPrefSize(tamano, tamano);
        setMaxSize(tamano, tamano);
        escala.setX(tamano / 24.0);
        escala.setY(tamano / 24.0);
    }

    /** Busca el control (Button, Label de la pestaña, Hyperlink...) que lo muestra y copia su color de texto. */
    private void enlazar() {
        if (duenio != null) {
            color.unbind();
            duenio = null;
        }
        for (Parent p = getParent(); p != null; p = p.getParent()) {
            if (p instanceof Labeled l) {
                duenio = l;
                color.bind(l.textFillProperty());
                return;
            }
        }
    }

    @Override
    public String toString() {
        return "Icono[" + nombre + "]";
    }
}
