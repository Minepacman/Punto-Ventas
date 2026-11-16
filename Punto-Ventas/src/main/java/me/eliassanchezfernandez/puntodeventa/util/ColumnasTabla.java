package me.eliassanchezfernandez.puntodeventa.util;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * Anchos mínimos de columna que siguen a la letra de verdad (Apariencia: 90–130 %): ningún
 * encabezado ni cifra se recorta con «…».
 * <ul>
 *   <li>Cada columna llega, como mínimo, al ancho de su encabezado (con la flecha de orden).</li>
 *   <li>Las columnas de cifras ({@code styleClass="align-right"}: precios, importes, existencias)
 *       llegan además al ancho de la cifra más larga a la vista, o de una muestra fija
 *       ({@link #muestra(TableColumn, String)}), para que «$1,234.50» nunca quede en «$1,2…».</li>
 * </ul>
 * Los anchos mínimos del FXML se respetan (nunca se baja de ellos) y el ancho preferido sube si
 * queda por debajo del mínimo. Se recalcula cuando cambia la hoja de estilos de la escena (la
 * ventana de Apariencia cambia la letra sin reiniciar) y cuando cambian las filas. Solo mide los
 * textos de las celdas visibles (unas decenas), nunca toda la lista.
 */
public final class ColumnasTabla {

    private ColumnasTabla() { }

    /** Clave en {@link TableColumnBase#getProperties()} con el texto de muestra de una columna de cifras */
    public static final String MUESTRA = "pos.columna.muestra";
    /** Clave con el ancho mínimo que puso el FXML / el controlador (para no bajar de él) */
    private static final String MINIMO_ORIGINAL = "pos.columna.minimo";
    private static final String INSTALADO = "pos.columnas.ajuste";

    /** Relleno de la celda (izquierda + derecha) y holgura para el redondeo de la letra */
    private static final double RELLENO_CELDA = 18;
    /** Encabezado: relleno + flecha de orden */
    private static final double RELLENO_ENCABEZADO = 26;

    /** Muestra fija para una columna de cifras (p. ej. "$99,999.99"): su ancho mínimo nunca baja de ella */
    public static void muestra(TableColumn<?, ?> columna, String texto) {
        columna.getProperties().put(MUESTRA, texto);
    }

    /** Instala el ajuste en la tabla (una vez; seguro llamarlo de nuevo) */
    public static void ajustar(TableView<?> tabla) {
        if (tabla == null || tabla.getProperties().containsKey(INSTALADO)) return;
        tabla.getProperties().put(INSTALADO, Boolean.TRUE);
        Runnable pedir = () -> pedir(tabla);
        tabla.skinProperty().addListener((o, a, b) -> pedir.run());
        // La lista puede cambiar de instancia (setItems): se escucha la propiedad y la lista vigente
        tabla.itemsProperty().addListener((o, a, b) -> {
            if (b != null) b.addListener((ListChangeListener<Object>) c -> pedir.run());
            pedir.run();
        });
        if (tabla.getItems() != null) tabla.getItems().addListener((ListChangeListener<Object>) c -> pedir.run());
        ListChangeListener<String> hojas = c -> pedir.run();
        tabla.sceneProperty().addListener((o, vieja, nueva) -> {
            if (vieja != null) vieja.getStylesheets().removeListener(hojas);
            if (nueva != null) {
                nueva.getStylesheets().addListener(hojas);
                pedir.run();
            }
        });
        Scene escena = tabla.getScene();
        if (escena != null) escena.getStylesheets().addListener(hojas);
        pedir.run();
    }

    /**
     * La columna {@code elastica} (la descripción) toma todo el ancho que dejan las demás, sin bajar de su
     * ancho mínimo del FXML. A diferencia de CONSTRAINED_RESIZE_POLICY (que reparte
     * el sobrante entre todas y deja la descripción angosta), las cifras conservan su ancho justo y el
     * nombre del producto se lee completo. Si no cabe todo, la tabla se desplaza a lo ancho.
     */
    public static void llenarCon(TableView<?> tabla, TableColumn<?, ?> elastica) {
        double base = elastica.getMinWidth();   // nunca más angosta que su mínimo del FXML
        Runnable[] ajustar = new Runnable[1];
        ajustar[0] = () -> {
            double otras = 0;
            for (TableColumn<?, ?> c : tabla.getVisibleLeafColumns()) if (c != elastica) otras += c.getWidth();
            // Menos la barra vertical, solo si está a la vista (al aparecer o irse se vuelve a calcular)
            double barra = 0;
            for (Node n : tabla.lookupAll(".scroll-bar")) {
                if (n instanceof javafx.scene.control.ScrollBar sb && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                    if (sb.getProperties().putIfAbsent("pos.columnas.barra", Boolean.TRUE) == null) {
                        sb.visibleProperty().addListener((o, a, b) -> Platform.runLater(ajustar[0]));
                    }
                    if (sb.isVisible()) barra = Math.max(barra, sb.getWidth() > 0 ? sb.getWidth() : 14);
                }
            }
            double disponible = tabla.getWidth() - tabla.snappedLeftInset() - tabla.snappedRightInset() - otras - barra - 2;
            double ancho = Math.floor(Math.max(Math.max(base, elastica.getMinWidth()), disponible));
            if (Math.abs(elastica.getPrefWidth() - ancho) > 0.5) elastica.setPrefWidth(ancho);
        };
        Runnable ajustarUno = () -> ajustar[0].run();
        tabla.widthProperty().addListener((o, a, b) -> ajustarUno.run());
        tabla.skinProperty().addListener((o, a, b) -> Platform.runLater(ajustarUno));
        tabla.getVisibleLeafColumns().addListener((ListChangeListener<Object>) c -> ajustarUno.run());
        for (TableColumn<?, ?> c : tabla.getColumns()) {
            if (c != elastica) c.widthProperty().addListener((o, a, b) -> ajustarUno.run());
        }
        elastica.minWidthProperty().addListener((o, a, b) -> ajustarUno.run());
        ajustarUno.run();
    }

    private static final String PENDIENTE = "pos.columnas.pendiente";

    /** Junta varios avisos seguidos en un solo cálculo, después del siguiente pase de estilos */
    private static void pedir(TableView<?> tabla) {
        if (tabla.getProperties().containsKey(PENDIENTE)) return;
        tabla.getProperties().put(PENDIENTE, Boolean.TRUE);
        Platform.runLater(() -> {
            tabla.getProperties().remove(PENDIENTE);
            if (tabla.getScene() == null || tabla.getSkin() == null) return;
            tabla.applyCss();
            tabla.layout();
            recalcular(tabla);
        });
    }

    /** Calcula y aplica los mínimos (visible para pruebas) */
    static void recalcular(TableView<?> tabla) {
        Map<TableColumnBase<?, ?>, Double> necesario = new HashMap<>();
        Font letraCelda = null;
        for (Node n : tabla.lookupAll(".column-header")) {
            if (!(n instanceof TableColumnHeader encabezado)) continue;
            TableColumnBase<?, ?> col = encabezado.getTableColumn();
            if (col == null || !col.getColumns().isEmpty()) continue;
            Labeled etiqueta = etiqueta(encabezado);
            if (etiqueta == null) continue;
            Font f = etiqueta.getFont();
            double ancho = col.getText() == null || col.getText().isBlank() ? 0
                    : anchoTexto(col.getText(), f) + RELLENO_ENCABEZADO;
            if (esCifra(col)) {
                Object m = col.getProperties().get(MUESTRA);
                if (m != null) {
                    Font normal = Font.font(f.getFamily(), FontWeight.NORMAL, f.getSize());
                    ancho = Math.max(ancho, anchoTexto(m.toString(), normal) + RELLENO_CELDA);
                }
            }
            necesario.merge(col, ancho, Math::max);
        }
        for (Node n : tabla.lookupAll(".table-cell")) {
            if (!(n instanceof TableCell<?, ?> celda) || celda.isEmpty() || celda.getText() == null) continue;
            TableColumn<?, ?> col = celda.getTableColumn();
            if (col == null || !esCifra(col)) continue;
            if (letraCelda == null) letraCelda = celda.getFont();
            double ancho = anchoTexto(celda.getText(), celda.getFont())
                    + celda.snappedLeftInset() + celda.snappedRightInset() + 4;
            necesario.merge(col, Math.max(ancho, RELLENO_CELDA), Math::max);
        }
        boolean cambio = false;
        for (Map.Entry<TableColumnBase<?, ?>, Double> e : necesario.entrySet()) {
            TableColumnBase<?, ?> col = e.getKey();
            if (!col.isResizable()) continue;
            double original = (Double) col.getProperties().computeIfAbsent(MINIMO_ORIGINAL, k -> col.getMinWidth());
            double minimo = Math.ceil(Math.max(original, e.getValue()));
            if (Math.abs(col.getMinWidth() - minimo) > 0.5) {
                col.setMinWidth(minimo);
                cambio = true;
            }
            if (col.getPrefWidth() < minimo) col.setPrefWidth(minimo);
        }
        if (cambio && tabla.getColumnResizePolicy() == TableView.CONSTRAINED_RESIZE_POLICY) {
            // Que la política vuelva a repartir el ancho con los mínimos nuevos (lo hace sola al cambiar el ancho de la tabla)
            reaplicarPolitica(tabla);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void reaplicarPolitica(TableView tabla) {
        tabla.getColumnResizePolicy().call(new TableView.ResizeFeatures(tabla, null, 0.0));
    }

    private static boolean esCifra(TableColumnBase<?, ?> col) {
        return col.getStyleClass().contains("align-right");
    }

    private static Labeled etiqueta(Parent encabezado) {
        for (Node h : encabezado.getChildrenUnmodifiable()) if (h instanceof Labeled l) return l;
        return null;
    }

    private static final Text MEDIDOR = new Text();

    static double anchoTexto(String texto, Font letra) {
        MEDIDOR.setFont(letra);
        MEDIDOR.setText(texto);
        return MEDIDOR.getLayoutBounds().getWidth();
    }
}
