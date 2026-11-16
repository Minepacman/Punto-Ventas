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
 * Icono (Lucide u otro paquete elegido en Apariencia, ver {@link PaquetesIconos} y {@link EstiloIconos})
 * como nodo JavaFX: rutas SVG escaladas desde el viewBox de 24 unidades al tamaño pedido (16 px por
 * omisión, ×1.25 con "iconos grandes"); de trazo o de relleno según el paquete. El color del trazo sigue al color de texto
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

    /** Se redibuja cuando cambia el estilo global ({@link EstiloIconos}); escucha débil */
    private final javafx.beans.InvalidationListener alCambiarEstilo = o -> redibujar();

    public Icono() {
        getStyleClass().add("icono");
        setMouseTransparent(true);
        getChildren().add(new javafx.scene.Group());
        parentProperty().addListener((o, a, b) -> enlazar());
        sceneProperty().addListener((o, a, b) -> enlazar());
        EstiloIconos.revisionProperty().addListener(new javafx.beans.WeakInvalidationListener(alCambiarEstilo));
        dimensionar();
    }

    public Icono(String nombre) {
        this();
        setNombre(nombre);
    }

    public Icono(String nombre, double tamano) {
        this();
        this.tamano = tamano;
        dimensionar();
        setNombre(nombre);
    }

    public String getNombre() { return nombre; }

    public void setNombre(String nombre) {
        this.nombre = nombre;
        dibujar();
    }

    /** Dibuja el icono con el paquete y estilo vigentes (ver Configuración → Apariencia) */
    private void dibujar() {
        javafx.scene.Group g = new javafx.scene.Group();
        if (nombre != null) {
            String paq = paquetePropio != null ? paquetePropio : EstiloIconos.paquete();
            String est = paquetePropio != null ? estiloPropio : EstiloIconos.estilo();
            double gro = paquetePropio != null ? grosorPropio : EstiloIconos.grosor();
            PaquetesIconos.Dibujo dib = PaquetesIconos.dibujo(paq, est, nombre);
            double ancho = dib.grosorBase() * gro / 2.0;
            for (PaquetesIconos.Ruta r : dib.rutas()) {
                SVGPath p = new SVGPath();
                p.setContent(r.d());
                if (r.parImpar()) p.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
                if (r.rellenar()) {
                    p.fillProperty().bind(color);
                    p.setStroke(null);
                } else {
                    p.setFill(null);
                    p.setStrokeWidth(ancho);
                    p.setStrokeLineCap(StrokeLineCap.ROUND);
                    p.setStrokeLineJoin(StrokeLineJoin.ROUND);
                    p.strokeProperty().bind(color);
                }
                if (r.translucida()) p.setOpacity(0.22);
                g.getChildren().add(p);
            }
        }
        g.getTransforms().add(escala);
        getChildren().setAll(g);
    }

    // Estilo propio (vista previa de Apariencia): ignora el global
    private String paquetePropio;
    private String estiloPropio;
    private double grosorPropio = 2;
    private double factorPropio = -1;

    /** Dibuja este icono con un paquete/estilo propio (para muestras y la vista previa); null = el global */
    public void usarEstilo(String paquete, String estilo, double grosor, double factorTamano) {
        this.paquetePropio = paquete;
        this.estiloPropio = estilo;
        this.grosorPropio = grosor;
        this.factorPropio = paquete == null ? -1 : factorTamano;
        redibujar();
    }

    private void redibujar() {
        dimensionar();
        dibujar();
    }

    public double getTamano() { return tamano; }

    public void setTamano(double tamano) {
        this.tamano = tamano;
        dimensionar();
    }

    private void dimensionar() {
        double t = Math.round(tamano * (factorPropio > 0 ? factorPropio : EstiloIconos.factorTamano()));
        setMinSize(t, t);
        setPrefSize(t, t);
        setMaxSize(t, t);
        escala.setX(t / 24.0);
        escala.setY(t / 24.0);
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
