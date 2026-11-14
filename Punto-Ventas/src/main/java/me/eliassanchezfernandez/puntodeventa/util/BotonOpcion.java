package me.eliassanchezfernandez.puntodeventa.util;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Botón de la pestaña Configuración: icono a la izquierda, título en negritas y
 * una línea corta que dice en palabras llanas para qué sirve. En FXML:
 * {@code <BotonOpcion icono="usuario" titulo="Cajeros" descripcion="Usuarios y permisos" onAction="#onCajeros"/>}.
 * El estilo está en app.css ({@code .boton-opcion}); la prueba IconosFxmlTest
 * revisa que el icono exista.
 */
public class BotonOpcion extends Button {

    private final StringProperty titulo = new SimpleStringProperty(this, "titulo", "");
    private final StringProperty descripcion = new SimpleStringProperty(this, "descripcion", "");
    private final StringProperty icono = new SimpleStringProperty(this, "icono", null);

    private final Icono vistaIcono = new Icono();
    private final Label lblTitulo = new Label();
    private final Label lblDescripcion = new Label();

    public BotonOpcion() {
        getStyleClass().add("boton-opcion");
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE);
        setMnemonicParsing(false);

        vistaIcono.setTamano(22);
        // El icono copia el color de texto del Labeled más cercano: una etiqueta propia permite
        // darle el color de acento desde CSS (.opcion-icono) sin tocar el del título
        Label marcoIcono = new Label();
        marcoIcono.setGraphic(vistaIcono);
        marcoIcono.getStyleClass().add("opcion-icono");
        marcoIcono.setMinWidth(30);
        marcoIcono.setAlignment(Pos.CENTER);
        lblTitulo.getStyleClass().add("opcion-titulo");
        lblDescripcion.getStyleClass().add("opcion-desc");
        lblDescripcion.setWrapText(true);
        lblTitulo.textProperty().bind(titulo);
        lblDescripcion.textProperty().bind(descripcion);
        lblDescripcion.visibleProperty().bind(descripcion.isNotEmpty());
        lblDescripcion.managedProperty().bind(descripcion.isNotEmpty());

        VBox textos = new VBox(1, lblTitulo, lblDescripcion);
        textos.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textos, Priority.ALWAYS);
        HBox fila = new HBox(10, marcoIcono, textos);
        fila.setAlignment(Pos.CENTER_LEFT);
        fila.setMaxWidth(Double.MAX_VALUE);
        setGraphic(fila);

        icono.addListener((obs, a, nombre) -> vistaIcono.setNombre(nombre));
        // El tooltip repite la descripción por si el texto se corta en pantallas angostas
        descripcion.addListener((obs, a, d) -> setTooltip(d == null || d.isBlank() ? null : new javafx.scene.control.Tooltip(d)));
    }

    public String getTitulo() { return titulo.get(); }
    public void setTitulo(String valor) { titulo.set(valor); }
    public StringProperty tituloProperty() { return titulo; }

    public String getDescripcion() { return descripcion.get(); }
    public void setDescripcion(String valor) { descripcion.set(valor); }
    public StringProperty descripcionProperty() { return descripcion; }

    public String getIcono() { return icono.get(); }
    public void setIcono(String valor) { icono.set(valor); }
    public StringProperty iconoProperty() { return icono; }
}
