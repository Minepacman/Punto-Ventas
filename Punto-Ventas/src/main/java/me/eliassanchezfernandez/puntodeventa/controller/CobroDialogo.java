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
import me.eliassanchezfernandez.puntodeventa.service.ValeService;
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
 *  - F11 lleva al código del «Vale de la tienda»: se escanea o se teclea y Enter lo busca (ese
 *    Enter nunca cobra); se usa lo que tenga, hasta el total, y el resto se paga con otra forma.
 *    Un vale escaneado por error en un campo de monto se pasa solo a su campo;
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
        return mostrar(owner, total, habilitadas, iniciales, inicialesPorLector, null);
    }

    /** Cliente elegido para la parte "A cuenta (fiado)"; {@code limite} 0 = sin límite */
    public record ClienteFiado(Long id, String nombre, double saldo, double limite) {
        /** Cuánto más se le puede fiar (null = sin límite) */
        public Double disponible() {
            return limite > 0.004 ? Math.max(0, Math.round((limite - saldo) * 100.0) / 100.0) : null;
        }

        /** "Juan Pérez · debe $120.00 · límite $500.00" */
        public String resumen() {
            return nombre + " · " + (saldo < -0.004 ? "a favor " + Moneda.formato(-saldo) : "debe " + Moneda.formato(saldo))
                    + (limite > 0.004 ? " · límite " + Moneda.formato(limite) : " · sin límite");
        }
    }

    /**
     * Lo que la ventana necesita para ofrecer "A cuenta (fiado)": cómo abrir el buscador de
     * clientes. Después de cerrar, {@link #elegido()} dice a quién se le anotó.
     */
    public static final class Fiado {
        private final java.util.function.Function<Window, Optional<ClienteFiado>> selector;
        private ClienteFiado elegido;

        public Fiado(java.util.function.Function<Window, Optional<ClienteFiado>> selector, ClienteFiado inicial) {
            this.selector = selector;
            this.elegido = inicial;
        }

        public ClienteFiado elegido() { return elegido; }
    }

    /**
     * Lo que la ventana necesita para aceptar un «Vale de la tienda» (vale de compra de una
     * devolución): cómo buscar un código. Después de cerrar, {@link #codigo()} dice qué vale se usó.
     */
    public static final class ValeTienda {
        private final java.util.function.Function<String, ValeService.Consulta> buscador;
        private ValeService.Consulta elegido;

        public ValeTienda(java.util.function.Function<String, ValeService.Consulta> buscador) {
            this.buscador = buscador;
        }

        public ValeService.Consulta elegido() { return elegido; }

        /** Código del vale elegido (null si no se usó ninguno) */
        public String codigo() { return elegido != null ? elegido.codigo() : null; }
    }

    /**
     * @param fiado null = sin la forma "A cuenta (fiado)". Con ella: F6 elige el cliente, F7 pone
     *        todo a cuenta; el resto puede ir en efectivo u otra forma (pago dividido). Si lo
     *        fiado rebasa el límite del cliente se avisa aquí y el servicio pide la autorización.
     */
    public static Optional<Map<Venta.FormaPago, Double>> mostrar(Window owner, double total,
                                                                 List<Venta.FormaPago> habilitadas,
                                                                 Map<Venta.FormaPago, Double> iniciales,
                                                                 boolean inicialesPorLector, Fiado fiado) {
        return mostrar(owner, total, habilitadas, iniciales, inicialesPorLector, fiado, null);
    }

    /** @param vale null = sin «Vale de la tienda» */
    public static Optional<Map<Venta.FormaPago, Double>> mostrar(Window owner, double total,
                                                                 List<Venta.FormaPago> habilitadas,
                                                                 Map<Venta.FormaPago, Double> iniciales,
                                                                 boolean inicialesPorLector, Fiado fiado,
                                                                 ValeTienda vale) {
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
        formas.remove(Venta.FormaPago.VALE_COMPRA);   // tiene su propio renglón (código + monto)
        formas.remove(Venta.FormaPago.CREDITO);
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
        // Fiado: campo propio con el cliente debajo (no es una forma de pago de Configuración)
        TextField txtFiado = null;
        Label lblCliente = new Label();
        lblCliente.setWrapText(true);
        lblCliente.getStyleClass().add("hint");
        lblCliente.setMaxWidth(380);
        Runnable elegirCliente = () -> { };
        final Runnable[] alElegirCliente = { () -> { } };   // = recalcular, una vez definido
        if (fiado != null) {
            TextField campo = new TextField();
            campo.setPromptText("0.00");
            campo.setPrefColumnCount(10);
            Double inicial = iniciales != null ? iniciales.get(Venta.FormaPago.CREDITO) : null;
            if (inicial != null && inicial > 0) campo.setText(CalculoCobro.formatoCampo(inicial));
            campos.put(Venta.FormaPago.CREDITO, campo);
            txtFiado = campo;
            Button btnCliente = new Button("Cliente…  F6");
            btnCliente.getStyleClass().addAll("button-ghost", "button-sm");
            btnCliente.setFocusTraversable(false);
            btnCliente.setTooltip(new Tooltip("Elegir (o dar de alta) el cliente al que se le anota la cuenta"));
            HBox filaFiado = new HBox(6, campo, btnCliente);
            HBox.setHgrow(campo, Priority.ALWAYS);
            grid.addRow(fila++, new Label("A cuenta (fiado):"), filaFiado);
            grid.add(lblCliente, 1, fila++);
            final TextField campoFiado = campo;
            elegirCliente = () -> {
                Optional<ClienteFiado> c = fiado.selector.apply(dlg.getDialogPane().getScene() != null
                        ? dlg.getDialogPane().getScene().getWindow() : owner);
                c.ifPresent(x -> {
                    fiado.elegido = x;
                    alElegirCliente[0].run();                    // recalcula con el cliente nuevo
                });
                campoFiado.requestFocus();
                campoFiado.selectAll();
            };
            final Runnable elegir = elegirCliente;
            btnCliente.setOnAction(e -> elegir.run());
        }

        // Vale de la tienda: código (escaneado o tecleado) + cuánto se usa de él
        TextField txtCodigoVale = null;
        TextField campoVale = null;
        Label lblVale = new Label();
        lblVale.setWrapText(true);
        lblVale.getStyleClass().add("hint");
        lblVale.setMaxWidth(380);
        if (vale != null) {
            txtCodigoVale = new TextField();
            txtCodigoVale.setPromptText("Escanea o escribe el código");
            txtCodigoVale.setPrefColumnCount(11);
            campoVale = new TextField();
            campoVale.setPromptText("0.00");
            campoVale.setPrefColumnCount(7);
            campoVale.setTooltip(new Tooltip("Cuánto se paga con el vale (se pone solo al buscarlo)"));
            campos.put(Venta.FormaPago.VALE_COMPRA, campoVale);
            HBox filaVale = new HBox(6, txtCodigoVale, campoVale);
            HBox.setHgrow(txtCodigoVale, Priority.ALWAYS);
            grid.addRow(fila++, new Label("Vale de la tienda:"), filaVale);
            grid.add(lblVale, 1, fila++);
            lblVale.setText("F11: escanea el vale que trae el cliente y pulsa Enter.");
        }

        // ── Botones rápidos ──────────────────────────────────────────────
        FlowPane rapidos = new FlowPane(6, 6);
        TextField txtEfectivo = campos.get(Venta.FormaPago.EFECTIVO);
        Map<KeyCode, Runnable> teclasRapidas = new LinkedHashMap<>();
        // Billetes "que siguen" a lo que falta pagar en efectivo (cambia al usar un vale)
        final double[] montosBillete = new double[TECLAS_BILLETES.length];
        final List<Button> botonesBillete = new ArrayList<>();
        final TextField campoValeFinal = campoVale;
        final TextField codigoValeFinal = txtCodigoVale;
        java.util.function.DoubleConsumer actualizarBilletes = restante -> {
            List<Double> sug = CalculoCobro.sugerenciasDeEfectivo(Math.max(0, restante));
            for (int i = 0; i < botonesBillete.size(); i++) {
                Button b = botonesBillete.get(i);
                boolean hay = i < sug.size() && restante > 0.004;
                b.setVisible(hay);
                b.setManaged(hay);
                if (!hay) continue;
                montosBillete[i] = sug.get(i);
                b.setText((i == 0 ? "Exacto " : "") + Moneda.formato(sug.get(i)) + "  " + TECLAS_BILLETES[i].getName());
                b.getTooltip().setText("Poner " + Moneda.formato(sug.get(i)) + " en efectivo");
            }
        };
        if (txtEfectivo != null) {
            for (int i = 0; i < TECLAS_BILLETES.length; i++) {
                final int indice = i;
                Button b = boton("", "");
                Runnable accion = () -> {
                    if (!b.isVisible()) return;
                    campos.values().forEach(c -> { if (c != txtEfectivo && c != campoValeFinal) c.clear(); });
                    txtEfectivo.setText(CalculoCobro.formatoCampo(montosBillete[indice]));
                    txtEfectivo.requestFocus();
                    txtEfectivo.selectAll();
                };
                b.setOnAction(e -> accion.run());
                teclasRapidas.put(TECLAS_BILLETES[i], accion);
                botonesBillete.add(b);
                rapidos.getChildren().add(b);
            }
            actualizarBilletes.accept(total);
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

        if (txtFiado != null) {
            final TextField campoFiado = txtFiado;
            final Runnable elegir = elegirCliente;
            Button b = boton("Todo a cuenta  F7", "Anotar el total completo a la cuenta del cliente");
            Runnable accion = () -> {
                campos.values().forEach(TextField::clear);
                campoFiado.setText(CalculoCobro.formatoCampo(total));
                if (fiado.elegido == null) elegir.run();
                campoFiado.requestFocus();
                campoFiado.selectAll();
            };
            b.setOnAction(e -> accion.run());
            teclasRapidas.put(KeyCode.F7, accion);
            teclasRapidas.put(KeyCode.F6, elegir);
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

        Label ayuda = new Label("Enter cobra · Esc regresa · Tab cambia de forma de pago · Enter sin montos = efectivo exacto"
                + (fiado != null ? " · F6 cliente · F7 todo a cuenta" : "")
                + (vale != null ? " · F11 vale de la tienda" : ""));
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
            if (fiado != null) {
                double aCuenta = montos.getOrDefault(Venta.FormaPago.CREDITO, 0.0);
                ClienteFiado c = fiado.elegido;
                lblCliente.setText(textoCliente(c, aCuenta));
                if (aCuenta > 0 && c == null && r.esValido()) {
                    r = new CalculoCobro.Resultado(r.total(), r.pagado(), r.efectivo(), r.noEfectivo(), r.faltante(), r.cambio(),
                            "Elige a qué cliente se le anota la cuenta (F6).");
                }
            }
            if (vale != null) {
                double usoVale = montos.getOrDefault(Venta.FormaPago.VALE_COMPRA, 0.0);
                String problemaVale = problemaVale(vale.elegido, usoVale);
                lblVale.setText(textoVale(vale.elegido, usoVale));
                if (problemaVale != null && r.esValido()) {
                    r = new CalculoCobro.Resultado(r.total(), r.pagado(), r.efectivo(), r.noEfectivo(), r.faltante(), r.cambio(),
                            problemaVale);
                }
            }
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
        alElegirCliente[0] = recalcular;

        // ── Vale de la tienda: buscar el código (Enter en su campo; nunca cobra) ──
        final Runnable[] buscarVale = { () -> { } };
        if (vale != null) {
            final TextField codigoVale = txtCodigoVale;
            final TextField montoVale = campoVale;
            buscarVale[0] = () -> {
                String texto = codigoVale.getText();
                if (texto == null || texto.isBlank()) {
                    vale.elegido = null;
                    montoVale.clear();
                    actualizarBilletes.accept(total);
                    recalcular.run();
                    return;
                }
                ValeService.Consulta c;
                try {
                    c = vale.buscador.apply(texto);
                } catch (RuntimeException ex) {
                    c = new ValeService.Consulta(null, ValeService.normalizar(texto), 0, 0, null,
                            "No se pudo buscar el vale: " + ex.getMessage());
                }
                if (c == null || !c.sePuedeUsar()) {
                    vale.elegido = null;
                    montoVale.clear();
                    recalcular.run();
                    lblVale.setText(c != null ? c.problema() : "No se encontró el vale.");
                    codigoVale.requestFocus();
                    codigoVale.selectAll();
                    return;
                }
                vale.elegido = c;
                double otros = 0;
                for (Map.Entry<Venta.FormaPago, TextField> e : campos.entrySet()) {
                    if (e.getKey() == Venta.FormaPago.EFECTIVO || e.getKey() == Venta.FormaPago.VALE_COMPRA) continue;
                    double m = CalculoCobro.parsearMonto(e.getValue().getText(), 0);
                    if (!Double.isNaN(m) && m > 0) otros += m;
                }
                double usar = c.aUsar(Math.max(0, total - otros));
                codigoVale.setText(c.codigoMostrado());
                montoVale.setText(CalculoCobro.formatoCampo(usar));
                double restante = Math.round((total - otros - usar) * 100.0) / 100.0;
                actualizarBilletes.accept(restante);
                recalcular.run();
                if (restante > 0.004 && txtEfectivo != null) {
                    txtEfectivo.requestFocus();
                    txtEfectivo.selectAll();
                } else {
                    montoVale.requestFocus();
                    montoVale.selectAll();
                }
            };
            // Si cambia el código ya buscado, el vale deja de valer hasta buscarlo otra vez
            codigoVale.textProperty().addListener((o, a, b) -> {
                if (vale.elegido != null && !ValeService.normalizar(b).equals(vale.elegido.codigo())) {
                    vale.elegido = null;
                    recalcular.run();
                }
            });
            // Enter (tecleado o del lector) en el código: buscar, nunca cobrar
            codigoVale.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ENTER) {
                    e.consume();
                    buscarVale[0].run();
                }
            });
            codigoVale.setOnAction(javafx.event.Event::consume);
            teclasRapidas.put(KeyCode.F11, () -> { codigoVale.requestFocus(); codigoVale.selectAll(); });
        }

        // Ritmo de las teclas en los campos: un código escaneado ahí llega como ráfaga + Enter
        campos.values().forEach(c -> {
            c.addEventFilter(KeyEvent.KEY_TYPED, e -> {
                String ch = e.getCharacter();
                if (ch != null && !ch.isEmpty()) lector.caracter(ch.charAt(0), System.nanoTime());
            });
            c.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() != KeyCode.ENTER) return;
                // Un vale escaneado (o tecleado) en un campo de monto: se pasa a su campo y se busca, sin cobrar
                if (vale != null && c != campoValeFinal && ValeService.pareceCodigo(c.getText())) {
                    e.consume();
                    lector.enter(System.nanoTime());
                    String codigo = c.getText();
                    c.clear();
                    capturadoPorLector[0] = false;
                    codigoValeFinal.setText(codigo);
                    buscarVale[0].run();
                    return;
                }
                // Solo marca (nunca desmarca): el "false" lo pone cualquier edición del monto. Así un
                // Enter humano no borra la sospecha que traían los montos iniciales del panel.
                if (lector.enter(System.nanoTime()).isPresent()) capturadoPorLector[0] = true;
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

    /** Por qué no se puede cobrar con lo puesto en el vale (null = está bien) */
    static String problemaVale(ValeService.Consulta elegido, double usoVale) {
        if (usoVale <= 0) return null;
        if (elegido == null) return "Escanea o escribe el código del vale y pulsa Enter para buscarlo (F11).";
        if (usoVale > elegido.saldo() + 0.004) {
            return "Al vale " + elegido.codigoMostrado() + " solo le quedan " + Moneda.formato(elegido.saldo()) + ".";
        }
        return null;
    }

    /** Línea bajo el vale: cuánto tiene, cuánto se usa y cuánto le quedará */
    static String textoVale(ValeService.Consulta elegido, double usoVale) {
        if (elegido == null) return "F11: escanea el vale que trae el cliente y pulsa Enter.";
        String texto = "Vale " + elegido.codigoMostrado() + ": tiene " + Moneda.formato(elegido.saldo());
        if (usoVale > 0 && usoVale <= elegido.saldo() + 0.004) {
            double queda = Math.max(0, Math.round((elegido.saldo() - usoVale) * 100.0) / 100.0);
            texto += " · se usan " + Moneda.formato(usoVale)
                    + (queda > 0.004 ? " · le quedarán " + Moneda.formato(queda) : " · se usa completo");
        }
        return texto;
    }

    /** Línea bajo el campo de fiado: el cliente elegido, lo que debe y si esta venta rebasa su límite */
    static String textoCliente(ClienteFiado c, double aCuenta) {
        if (c == null) return aCuenta > 0 ? "Sin cliente: pulsa F6 para elegirlo o darlo de alta." : "F6 elige el cliente · F7 todo a cuenta";
        String texto = c.resumen();
        Double disponible = c.disponible();
        if (aCuenta > 0) {
            double despues = Math.round((c.saldo() + aCuenta) * 100.0) / 100.0;
            texto += "\nCon esta venta debería " + Moneda.formato(despues) + ".";
            if (disponible != null && aCuenta > disponible + 0.004) {
                texto += " Rebasa su límite: se pedirá la autorización de un administrador.";
            }
        } else if (disponible != null) {
            texto += " · puede fiársele " + Moneda.formato(disponible);
        }
        return texto;
    }

    private static Button boton(String texto, String tooltip) {
        Button b = new Button(texto);
        b.getStyleClass().addAll("button-ghost", "button-sm");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }
}
