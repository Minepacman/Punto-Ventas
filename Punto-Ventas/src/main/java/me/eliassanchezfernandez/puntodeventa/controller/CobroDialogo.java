package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.RafagaLector;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ventana de cobro (F12). Pensada para no soltar el teclado:
 *  - abre con el cursor en "Efectivo recibido";
 *  - Enter cobra (si todo está vacío, cobra con efectivo exacto);
 *  - F1…F5 ponen los billetes "que siguen" al total (exacto, decena, 50, 100, 200…);
 *  - F8/F9/F10 pasan todo el total a tarjeta / transferencia / vales;
 *  - Tab cambia de forma de pago (pago dividido: la suma debe cubrir el total y
 *    lo que no es efectivo no puede excederlo);
 *  - Esc regresa al ticket sin cobrar nada;
 *  - un pago sospechoso (cambio de $1,000 o más, o efectivo tecleado al ritmo de un
 *    lector de códigos: {@link RafagaLector}) no se cobra con Enter: se muestra el
 *    motivo y hay que confirmar con F12 o un clic en "Sí, cobrar"
 *    ({@link CalculoCobro#motivoParaConfirmar}).
 * Las cuentas (pagado, falta, cambio) son de {@link CalculoCobro}; el servicio
 * vuelve a validar al guardar. Devuelve los montos por forma de pago, o vacío
 * si el cajero canceló.
 */
public final class CobroDialogo {

    private static final KeyCode[] TECLAS_BILLETES = { KeyCode.F1, KeyCode.F2, KeyCode.F3, KeyCode.F4, KeyCode.F5 };

    private CobroDialogo() { }

    /**
     * @param total       total del ticket
     * @param habilitadas formas de pago activas en Configuración (en orden de pantalla)
     * @param iniciales   montos ya capturados en el panel de Ventas (pueden ir vacíos)
     */
    public static Optional<Map<Venta.FormaPago, Double>> mostrar(Window owner, double total,
                                                                 List<Venta.FormaPago> habilitadas,
                                                                 Map<Venta.FormaPago, Double> iniciales) {
        return mostrar(owner, total, habilitadas, iniciales, false);
    }

    /**
     * @param inicialesPorLector los montos iniciales se teclearon al ritmo de un lector de códigos
     *        (el panel de Ventas lo detectó): se pide confirmación antes de cobrarlos
     */
    public static Optional<Map<Venta.FormaPago, Double>> mostrar(Window owner, double total,
                                                                 List<Venta.FormaPago> habilitadas,
                                                                 Map<Venta.FormaPago, Double> iniciales,
                                                                 boolean inicialesPorLector) {
        Dialog<Map<Venta.FormaPago, Double>> dlg = new Dialog<>();
        dlg.setTitle("Cobrar");
        dlg.setHeaderText(null);
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        // ── Total ────────────────────────────────────────────────────────
        Label lblTotalTitulo = new Label("TOTAL A COBRAR");
        lblTotalTitulo.getStyleClass().add("total-label-title");
        Label lblTotal = new Label(Moneda.formato(total));
        lblTotal.getStyleClass().add("total-amount");
        VBox cajaTotal = new VBox(2, lblTotalTitulo, lblTotal);
        cajaTotal.setAlignment(Pos.CENTER);
        cajaTotal.getStyleClass().add("total-box");   // fondo oscuro: las etiquetas .total-amount son blancas

        // ── Campos por forma de pago ─────────────────────────────────────
        Map<Venta.FormaPago, TextField> campos = new LinkedHashMap<>();
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        int fila = 0;
        List<Venta.FormaPago> formas = new ArrayList<>(habilitadas);
        if (formas.isEmpty()) formas.add(Venta.FormaPago.EFECTIVO);
        for (Venta.FormaPago forma : formas) {
            TextField campo = new TextField();
            campo.setPromptText("0.00");
            campo.setPrefColumnCount(10);
            Double inicial = iniciales != null ? iniciales.get(forma) : null;
            if (inicial != null && inicial > 0) campo.setText(CalculoCobro.formatoCampo(inicial));
            campos.put(forma, campo);
            String titulo = forma == Venta.FormaPago.EFECTIVO ? "Efectivo recibido:" : forma.getEtiqueta() + ":";
            grid.addRow(fila++, new Label(titulo), campo);
            GridPane.setHgrow(campo, Priority.ALWAYS);
        }

        // ── Botones rápidos ──────────────────────────────────────────────
        FlowPane rapidos = new FlowPane(6, 6);
        List<Double> sugerencias = CalculoCobro.sugerenciasDeEfectivo(total);
        TextField txtEfectivo = campos.get(Venta.FormaPago.EFECTIVO);
        Map<KeyCode, Runnable> teclasRapidas = new LinkedHashMap<>();
        if (txtEfectivo != null) {
            for (int i = 0; i < sugerencias.size() && i < TECLAS_BILLETES.length; i++) {
                double monto = sugerencias.get(i);
                String texto = (i == 0 ? "Exacto " : "") + Moneda.formato(monto);
                Button b = boton(texto + "  " + TECLAS_BILLETES[i].getName(), "Poner " + Moneda.formato(monto) + " en efectivo");
                Runnable accion = () -> {
                    campos.values().forEach(c -> { if (c != txtEfectivo) c.clear(); });
                    txtEfectivo.setText(CalculoCobro.formatoCampo(monto));
                    txtEfectivo.requestFocus();
                    txtEfectivo.selectAll();
                };
                b.setOnAction(e -> accion.run());
                teclasRapidas.put(TECLAS_BILLETES[i], accion);
                rapidos.getChildren().add(b);
            }
        }
        KeyCode[] teclasTodo = { KeyCode.F8, KeyCode.F9, KeyCode.F10 };
        int n = 0;
        for (Venta.FormaPago forma : formas) {
            if (forma == Venta.FormaPago.EFECTIVO || n >= teclasTodo.length) continue;
            TextField campo = campos.get(forma);
            KeyCode tecla = teclasTodo[n++];
            Button b = boton("Todo " + forma.getEtiqueta().toLowerCase() + "  " + tecla.getName(),
                    "Cobrar el total completo con " + forma.getEtiqueta().toLowerCase());
            Runnable accion = () -> {
                campos.values().forEach(TextField::clear);
                campo.setText(CalculoCobro.formatoCampo(total));
                campo.requestFocus();
                campo.selectAll();
            };
            b.setOnAction(e -> accion.run());
            teclasRapidas.put(tecla, accion);
            rapidos.getChildren().add(b);
        }

        // ── Pagado / cambio / error ──────────────────────────────────────
        Label lblPagado = new Label();
        Label lblCambioTitulo = new Label("Cambio:");
        Label lblCambio = new Label();
        lblCambio.getStyleClass().add("cambio-label");
        Label lblError = new Label();
        lblError.setWrapText(true);
        lblError.getStyleClass().add("field-error-label");
        lblError.setMaxWidth(380);
        HBox filaPagado = new HBox(8, new Label("Pagado:"), new Region(), lblPagado);
        HBox.setHgrow(filaPagado.getChildren().get(1), Priority.ALWAYS);
        HBox filaCambio = new HBox(8, lblCambioTitulo, new Region(), lblCambio);
        HBox.setHgrow(filaCambio.getChildren().get(1), Priority.ALWAYS);
        filaCambio.setAlignment(Pos.CENTER_LEFT);

        Label ayuda = new Label("Enter cobra · Esc regresa · Tab cambia de forma de pago · Enter sin montos = efectivo exacto");
        ayuda.getStyleClass().add("hint");
        ayuda.setWrapText(true);

        VBox contenido = new VBox(10, cajaTotal, grid, rapidos, filaPagado, filaCambio, lblError, ayuda);
        contenido.setPadding(new Insets(12));
        contenido.setPrefWidth(420);
        dlg.getDialogPane().setContent(contenido);

        ButtonType cobrar = new ButtonType("Cobrar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(cobrar, ButtonType.CANCEL);
        Button btnCobrar = (Button) dlg.getDialogPane().lookupButton(cobrar);
        btnCobrar.getStyleClass().addAll("button-primary", "btn-cobrar");
        btnCobrar.setText("Cobrar  (Enter)");
        Button btnCancelar = (Button) dlg.getDialogPane().lookupButton(ButtonType.CANCEL);
        btnCancelar.setText("Regresar  (Esc)");

        // ── Cuentas en vivo ──────────────────────────────────────────────
        final CalculoCobro.Resultado[] actual = { null };
        // Confirmación explícita de un pago sospechoso (cambio enorme o monto tecleado por el lector):
        // el primer intento de cobrar solo muestra el aviso; se completa con F12 o clic, nunca con Enter
        final boolean[] capturadoPorLector   = { inicialesPorLector };
        final boolean[] confirmacionPendiente = { false };
        final boolean[] confirmadoPorUsuario  = { false };
        final String textoCobrar = "Cobrar  (Enter)", textoConfirmar = "Sí, cobrar  (F12)";
        RafagaLector lector = new RafagaLector();
        Runnable recalcular = () -> {
            if (confirmacionPendiente[0]) {           // el cajero cambió un monto: el aviso ya no aplica
                confirmacionPendiente[0] = false;
                confirmadoPorUsuario[0] = false;
                btnCobrar.setText(textoCobrar);
            }
            Map<Venta.FormaPago, String> textos = new LinkedHashMap<>();
            campos.forEach((f, c) -> textos.put(f, c.getText()));
            Map<Venta.FormaPago, Double> montos = CalculoCobro.leerMontos(textos);
            if (montos == null) {
                actual[0] = null;
                lblPagado.setText("—");
                lblCambio.setText("—");
                lblError.setText("Escribe montos válidos (ej: 200).");
                btnCobrar.setDisable(true);
                return;
            }
            CalculoCobro.Resultado r = CalculoCobro.calcular(total, montos);
            actual[0] = r;
            lblPagado.setText(Moneda.formato(r.pagado()));
            if (!r.hayPago()) {
                lblCambio.setText(Moneda.formato(0));
                lblError.setText("");
                btnCobrar.setDisable(false);        // Enter = efectivo exacto
            } else if (r.esValido()) {
                lblCambio.setText(Moneda.formato(r.cambio()));
                lblError.setText("");
                btnCobrar.setDisable(false);
            } else {
                lblCambio.setText(r.faltante() > 0 ? "Faltan " + Moneda.formato(r.faltante()) : "—");
                lblError.setText(r.error());
                btnCobrar.setDisable(true);
            }
        };
        campos.values().forEach(c -> c.textProperty().addListener((o, a, b) -> {
            capturadoPorLector[0] = false;            // la ráfaga, si la hay, lo vuelve a marcar en su Enter
            recalcular.run();
        }));
        recalcular.run();

        // Ritmo de las teclas en los campos: un código escaneado ahí llega como ráfaga + Enter
        campos.values().forEach(c -> {
            c.addEventFilter(KeyEvent.KEY_TYPED, e -> {
                String ch = e.getCharacter();
                if (ch != null && !ch.isEmpty()) lector.caracter(ch.charAt(0), System.nanoTime());
            });
            c.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                // Solo marca (nunca desmarca): el "false" lo pone cualquier edición del monto. Así un
                // Enter humano no borra la sospecha que traían los montos iniciales del panel.
                if (e.getCode() == KeyCode.ENTER && lector.enter(System.nanoTime()).isPresent()) capturadoPorLector[0] = true;
            });
        });

        // Enter en cualquier campo = Cobrar (el botón por defecto no recibe el Enter del TextField)
        campos.values().forEach(c -> c.setOnAction(e -> { if (!btnCobrar.isDisable()) btnCobrar.fire(); }));
        // Solo un clic del ratón o F12 cuentan como confirmación del aviso (el lector solo sabe mandar Enter)
        btnCobrar.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> { if (confirmacionPendiente[0]) confirmadoPorUsuario[0] = true; });
        Runnable confirmarConF12 = () -> {
            if (confirmacionPendiente[0] && !btnCobrar.isDisable()) {
                confirmadoPorUsuario[0] = true;
                btnCobrar.fire();
            }
        };
        java.util.function.Consumer<String> pedirConfirmacion = motivo -> {
            confirmacionPendiente[0] = true;
            lblError.setText(motivo + "\nSi es correcto, pulsa F12 o haz clic en \"" + textoConfirmar.trim()
                    + "\"; si no, corrige el monto.");
            btnCobrar.setText(textoConfirmar);
            TextField campo = txtEfectivo != null ? txtEfectivo : campos.values().iterator().next();
            campo.requestFocus();
            campo.selectAll();
        };

        // Validación final: con todo vacío se cobra con efectivo exacto; si no alcanza no se cierra
        btnCobrar.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (actual[0] != null && !actual[0].hayPago() && txtEfectivo != null) {
                txtEfectivo.setText(CalculoCobro.formatoCampo(total));
                recalcular.run();
            }
            if (actual[0] == null || !actual[0].esValido()) {
                ev.consume();
                if (actual[0] != null && lblError.getText().isEmpty()) lblError.setText(actual[0].error());
                (txtEfectivo != null ? txtEfectivo : campos.values().iterator().next()).requestFocus();
                return;
            }
            String motivo = CalculoCobro.motivoParaConfirmar(actual[0], capturadoPorLector[0]);
            boolean confirmado = confirmadoPorUsuario[0];
            confirmadoPorUsuario[0] = false;
            if (motivo != null && !confirmado) {
                ev.consume();
                pedirConfirmacion.accept(motivo);
            }
        });
        // Si los montos que traía el panel ya son sospechosos, el aviso se ve desde que abre la ventana
        String motivoInicial = CalculoCobro.motivoParaConfirmar(actual[0], capturadoPorLector[0]);
        if (motivoInicial != null) pedirConfirmacion.accept(motivoInicial);
        dlg.setResultConverter(b -> {
            if (b != cobrar || actual[0] == null || !actual[0].esValido()) return null;
            Map<Venta.FormaPago, String> textos = new LinkedHashMap<>();
            campos.forEach((f, c) -> textos.put(f, c.getText()));
            return CalculoCobro.leerMontos(textos);
        });

        // Atajos de la ventana (su escena es propia: no chocan con los de Ventas)
        dlg.setOnShown(e -> {
            Scene scene = dlg.getDialogPane().getScene();
            if (scene != null) {
                teclasRapidas.forEach((tecla, accion) -> scene.getAccelerators().put(new KeyCodeCombination(tecla), accion));
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F12), confirmarConF12);
            }
            TextField primero = txtEfectivo != null ? txtEfectivo : campos.values().iterator().next();
            Platform.runLater(() -> { primero.requestFocus(); primero.selectAll(); });
        });

        return dlg.showAndWait();
    }

    private static Button boton(String texto, String tooltip) {
        Button b = new Button(texto);
        b.getStyleClass().addAll("button-ghost", "button-sm");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }
}
