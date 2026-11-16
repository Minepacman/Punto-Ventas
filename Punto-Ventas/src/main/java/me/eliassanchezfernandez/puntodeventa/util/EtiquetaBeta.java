package me.eliassanchezfernandez.puntodeventa.util;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.util.Duration;

/**
 * Pastilla «Beta» para lo que aún no funciona al 100 % (la lista está en {@link FuncionBeta}).
 * Estilo en app.css ({@code .etiqueta-beta}): contorno suave del color de acento, letra pequeña.
 *
 * <ul>
 *   <li>FXML: {@code <EtiquetaBeta funcion="BASCULA"/>} (se oculta sola si la función ya no está en beta).</li>
 *   <li>{@link BotonOpcion}: {@code beta="FACTURACION_CFDI"}.</li>
 *   <li>Código: {@link #marcar(Labeled, FuncionBeta)} (botones, casillas, opciones),
 *       {@link #marcar(Dialog, FuncionBeta)}, {@link #marcar(Tab, FuncionBeta)}, {@link #marcar(MenuItem, FuncionBeta)}
 *       y {@link #tituloVentana(String, FuncionBeta)} para la barra de título.</li>
 * </ul>
 */
public class EtiquetaBeta extends Label {

    /** Texto de la pastilla (se muestra en mayúsculas) y su ayuda: viven en {@link FuncionBeta} */
    public static final String TEXTO = FuncionBeta.TEXTO;
    public static final String AYUDA = FuncionBeta.AYUDA;
    public static final String CLASE = "etiqueta-beta";

    private final StringProperty funcion = new SimpleStringProperty(this, "funcion", null);

    /** Pastilla siempre visible (sin función asociada) */
    public EtiquetaBeta() {
        super(TEXTO.toUpperCase(java.util.Locale.ROOT));
        getStyleClass().add(CLASE);
        setMinWidth(USE_PREF_SIZE);
        setMnemonicParsing(false);
        setFocusTraversable(false);
        setAccessibleText(AYUDA);
        Tooltip t = new Tooltip(AYUDA);
        t.setShowDelay(Duration.millis(250));
        t.setWrapText(true);
        t.setMaxWidth(320);
        setTooltip(t);
        funcion.addListener((o, a, nombre) -> {
            FuncionBeta f = FuncionBeta.buscar(nombre);
            boolean ver = f != null && f.enBeta();
            setVisible(ver);
            setManaged(ver);
        });
    }

    public EtiquetaBeta(FuncionBeta f) {
        this();
        setFuncion(f.name());
    }

    public String getFuncion() { return funcion.get(); }
    public void setFuncion(String valor) { funcion.set(valor); }
    public StringProperty funcionProperty() { return funcion; }

    /** Pastilla genérica */
    public static EtiquetaBeta crear() { return new EtiquetaBeta(); }

    /** Pastilla de una función; null si ya no está en beta */
    public static EtiquetaBeta crear(FuncionBeta f) {
        return f != null && f.enBeta() ? new EtiquetaBeta(f) : null;
    }

    /** Título de la ventana (barra del sistema): «Báscula (Beta)» mientras esté en beta */
    public static String tituloVentana(String titulo, FuncionBeta f) {
        return FuncionBeta.tituloVentana(titulo, f);
    }

    /**
     * Pone la pastilla a la derecha del texto de un botón, casilla, opción o etiqueta. Sin icono basta el
     * graphic a la derecha; con icono el texto pasa a una etiqueta interna (icono · texto · Beta) que
     * sigue al texto y al color del control, que conserva su {@code text} (atajos, pruebas, lectores de pantalla).
     */
    public static void marcar(Labeled control, FuncionBeta f) {
        EtiquetaBeta pastilla = crear(f);
        if (control == null || pastilla == null) return;
        Node icono = control.getGraphic();
        if (icono == null) {
            control.setGraphic(pastilla);
            control.setContentDisplay(ContentDisplay.RIGHT);
            control.setGraphicTextGap(6);
            return;
        }
        Label texto = new Label();
        texto.setMnemonicParsing(false);
        texto.setMinWidth(USE_PREF_SIZE);   // con la pastilla al lado, el texto no se corta a «L…»
        control.setMinWidth(USE_PREF_SIZE);
        texto.textProperty().bind(control.textProperty());
        Runnable copiarEstilo = () -> texto.setStyle(estiloTexto(control.getTextFill(), control.getFont()));
        control.textFillProperty().addListener((o, a, b) -> copiarEstilo.run());
        control.fontProperty().addListener((o, a, b) -> copiarEstilo.run());
        copiarEstilo.run();
        HBox fila = new HBox(Math.max(4, control.getGraphicTextGap()), icono, texto, pastilla);
        fila.setAlignment(Pos.CENTER_LEFT);
        control.setGraphic(fila);
        control.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    /** En el encabezado de un diálogo (a la derecha del texto del encabezado) */
    public static void marcar(Dialog<?> dialogo, FuncionBeta f) {
        EtiquetaBeta pastilla = crear(f);
        if (dialogo != null && pastilla != null) dialogo.getDialogPane().setGraphic(pastilla);
    }

    /** En el título de una pestaña */
    public static void marcar(Tab tab, FuncionBeta f) {
        EtiquetaBeta pastilla = crear(f);
        if (tab != null && pastilla != null) tab.setGraphic(pastilla);
    }

    /** En una opción de menú */
    public static void marcar(MenuItem item, FuncionBeta f) {
        EtiquetaBeta pastilla = crear(f);
        if (item != null && pastilla != null) item.setGraphic(pastilla);
    }

    private static String estiloTexto(Paint relleno, javafx.scene.text.Font fuente) {
        StringBuilder sb = new StringBuilder();
        if (relleno instanceof Color c) {
            sb.append(String.format(java.util.Locale.ROOT, "-fx-text-fill: rgba(%d,%d,%d,%.3f);",
                    Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255), c.getOpacity()));
        }
        if (fuente != null) {
            sb.append(String.format(java.util.Locale.ROOT, "-fx-font-size: %.1fpx;", fuente.getSize()));
            String estilo = fuente.getStyle().toLowerCase(java.util.Locale.ROOT);
            if (estilo.contains("bold")) sb.append("-fx-font-weight: bold;");
        }
        return sb.toString();
    }
}
