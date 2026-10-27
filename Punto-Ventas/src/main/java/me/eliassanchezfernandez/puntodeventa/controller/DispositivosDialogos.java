package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.DispositivosService;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.terminal.DatosTarjeta;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Diálogos chicos de los dispositivos que no necesitan una ventana propia:
 * lector de códigos, cajón de dinero y terminal bancaria, y la acción
 * "Abrir cajón" (la usan Configuración y la pantalla de Ventas). Sin estado.
 */
@Component
public class DispositivosDialogos {

    @Autowired private DispositivosService dispositivosService;
    @Autowired private ImpresionService impresionService;
    @Autowired private SesionService sesionService;
    @Autowired private AutorizacionService autorizacionService;

    // ── Lector de códigos ─────────────────────────────────────────────────

    /** El lector funciona como teclado: aquí solo se prueba y se anota si manda Enter al final */
    public void lector(Window owner) {
        ConfiguracionDispositivos c = dispositivosService.obtener();
        TextField prueba = new TextField();
        prueba.setPromptText("Haz clic aquí y escanea un código");
        Label resultado = new Label("Aún no se ha leído nada.");
        resultado.setWrapText(true);
        resultado.setPrefWidth(430);
        final long[] primera = {0};
        prueba.setOnKeyTyped(e -> { if (primera[0] == 0) primera[0] = System.nanoTime(); });
        prueba.setOnAction(e -> {
            String codigo = prueba.getText();
            long ms = primera[0] == 0 ? 0 : (System.nanoTime() - primera[0]) / 1_000_000;
            resultado.setText(codigo.isEmpty() ? "No llegó ningún código."
                    : "Código leído: " + codigo + "  (" + codigo.length() + " caracteres en " + ms + " ms).\n"
                      + (codigo.length() > 3 && ms < 250
                         ? "Llegó de golpe y con Enter: el lector está bien configurado."
                         : "Llegó lento: parece escrito a mano, no con el lector."));
            prueba.clear();
            primera[0] = 0;
        });
        CheckBox sufijo = new CheckBox("Mi lector manda Enter al final de cada código");
        sufijo.setSelected(c.isLectorSufijoEnter());
        Label consejos = new Label("""
                Un lector de códigos funciona como un teclado: no necesita controlador.
                • Conéctalo por USB y haz clic en el campo \"Código del producto\" de Ventas antes de escanear.
                • Si el código se escribe pero no se agrega, el lector no manda Enter: configúralo con el código de barras \"Enter / CR suffix\" de su manual.
                • Si salen caracteres raros o letras en vez de números, revisa que el idioma del teclado del lector sea el mismo que el del sistema.
                • Si el lector agrega letras al inicio (prefijo), quítalo con su manual.""");
        consejos.setWrapText(true);
        consejos.setPrefWidth(430);
        consejos.getStyleClass().add("hint");
        Label nota = new Label("Escanear no pasa por el programa hasta que el código llega al campo, así que no hay nada más que configurar. "
                + "La casilla de arriba solo deja anotado cómo está el lector.");
        nota.setWrapText(true);
        nota.setPrefWidth(430);
        nota.getStyleClass().add("hint");
        dialogoGuardar(owner, "Lector de códigos", "Probar el lector",
                new VBox(10, new Label("Campo de prueba:"), prueba, resultado, sufijo, consejos, nota),
                () -> dispositivosService.guardarLector(sufijo.isSelected()));
    }

    // ── Cajón de dinero ───────────────────────────────────────────────────

    public void cajon(Window owner) {
        ConfiguracionDispositivos c = dispositivosService.obtener();
        CheckBox alCobrar = new CheckBox("Abrir el cajón al cobrar en efectivo");
        alCobrar.setSelected(c.isAbrirCajonEfectivo());
        Label info = new Label("El cajón se conecta con un cable RJ11 a la impresora de tickets y se abre con un pulso que manda la impresora "
                + "(solo con impresora ESC/POS; configúrala en Impresora de tickets). Se abre cuando una venta incluye efectivo.");
        info.setWrapText(true);
        info.setPrefWidth(420);
        info.getStyleClass().add("hint");
        Button abrir = new Button("Abrir cajón ahora");
        abrir.setOnAction(e -> abrirCajon(owner));
        Label permiso = new Label("Abrirlo a mano exige el permiso \"" + Permiso.ABRIR_CAJON.getDescripcion()
                + "\" (o la autorización de un administrador) y queda en la bitácora.");
        permiso.setWrapText(true);
        permiso.setPrefWidth(420);
        permiso.getStyleClass().add("hint");
        dialogoGuardar(owner, "Cajón de dinero", "Cajón conectado a la impresora",
                new VBox(10, alCobrar, info, abrir, permiso),
                () -> dispositivosService.guardarCajon(alCobrar.isSelected()));
    }

    /**
     * Abre el cajón por el pulso de la impresora, con el permiso ABRIR_CAJON o la
     * autorización de un administrador. El pulso se manda en el hilo de impresión.
     */
    public void abrirCajon(Window owner) {
        List<Autorizacion> auts = List.of();
        for (int intento = 0; intento < 2; intento++) {
            try {
                impresionService.abrirCajonAsync(auts).whenComplete((res, error) -> Platform.runLater(() -> {
                    if (error != null || res == null || res.esFallo()) {
                        Ventanas.alerta(Alert.AlertType.WARNING, "Cajón de dinero",
                                res != null ? res.mensaje() : "No se pudo abrir el cajón.");
                    }
                }));
                return;
            } catch (PermisoRequeridoException e) {
                if (intento == 1 || sesionService.tienePermiso(Permiso.ABRIR_CAJON)) {
                    Ventanas.alerta(Alert.AlertType.ERROR, "Cajón de dinero", e.getMessage());
                    return;
                }
                Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, Permiso.ABRIR_CAJON);
                if (aut.isEmpty()) return;
                auts = List.of(aut.get());
            } catch (NegocioException e) {
                Ventanas.alerta(Alert.AlertType.ERROR, "Cajón de dinero", e.getMessage());
                return;
            }
        }
    }

    // ── Terminal bancaria ─────────────────────────────────────────────────

    public void terminalBancaria(Window owner) {
        ConfiguracionDispositivos c = dispositivosService.obtener();
        TextArea lista = new TextArea(String.join("\n", c.getListaTerminales()));
        lista.setPrefRowCount(5);
        lista.setPromptText("Una terminal por línea");
        CheckBox exigir = new CheckBox("Exigir terminal, últimos 4 dígitos y autorización en cada pago con tarjeta");
        exigir.setSelected(c.isExigirDatosTarjeta());
        exigir.setWrapText(true);
        Label nota = new Label("Al cobrar con tarjeta, el cajero elige la terminal de esta lista y captura los últimos 4 dígitos y "
                + "el número de autorización del voucher. Nunca se guarda el número completo de la tarjeta. "
                + "Más adelante se puede conectar una terminal de Mercado Pago o Clip sin cambiar esta pantalla.");
        nota.setWrapText(true);
        nota.setPrefWidth(420);
        nota.getStyleClass().add("hint");
        dialogoGuardar(owner, "Terminal bancaria", "Terminales con las que se cobra con tarjeta",
                new VBox(10, new Label("Terminales (máximo " + DispositivosService.MAX_TERMINALES + "):"), lista, exigir, nota),
                () -> dispositivosService.guardarTerminales(lista.getText(), exigir.isSelected()));
    }

    // ── Cobro con tarjeta ─────────────────────────────────────────────────

    /**
     * Pide los datos del voucher de un pago con tarjeta: terminal (de la lista de
     * Configuración), últimos 4 dígitos y autorización. Valida con
     * {@link DatosTarjeta#validar} antes de cerrar. Vacío si se cancela.
     * {@code previos} (puede ser null) rellena los campos al repetir un cobro.
     */
    public Optional<DatosTarjeta> capturarTarjeta(Window owner, double monto, DatosTarjeta previos) {
        List<String> terminales = dispositivosService.obtener().getListaTerminales();
        ComboBox<String> comboTerminal = new ComboBox<>(FXCollections.observableArrayList(terminales));
        comboTerminal.setMaxWidth(Double.MAX_VALUE);
        comboTerminal.setValue(previos != null ? previos.terminal() : terminales.size() == 1 ? terminales.get(0) : null);
        comboTerminal.setPromptText("Elige la terminal");
        TextField ultimos4 = new TextField(previos != null ? previos.ultimos4() : "");
        ultimos4.setPromptText("4 dígitos");
        ultimos4.textProperty().addListener((o, a, b) -> {
            String limpio = b.replaceAll("\\D", "");
            if (limpio.length() > 4) limpio = limpio.substring(0, 4);
            if (!limpio.equals(b)) ultimos4.setText(limpio);
        });
        TextField autorizacion = new TextField(previos != null ? previos.autorizacion() : "");
        autorizacion.setPromptText("Número de autorización del voucher");
        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("field-error-label");
        error.setVisible(false);
        error.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Terminal:"), comboTerminal);
        grid.addRow(1, new Label("Últimos 4 dígitos:"), ultimos4);
        grid.addRow(2, new Label("Autorización:"), autorizacion);
        Label nota = new Label("Solo los últimos 4 dígitos: nunca captures el número completo, el NIP ni el código de seguridad.");
        nota.setWrapText(true);
        nota.setPrefWidth(360);
        nota.getStyleClass().add("hint");
        grid.add(nota, 0, 3, 2, 1);
        grid.add(error, 0, 4, 2, 1);

        Dialog<DatosTarjeta> dlg = new Dialog<>();
        dlg.setTitle("Cobro con tarjeta");
        dlg.setHeaderText("Cobra " + Moneda.formato(monto) + " en la terminal y captura los datos del voucher");
        if (owner != null) dlg.initOwner(owner);
        dlg.getDialogPane().setContent(grid);
        Ventanas.estilizar(dlg);
        ButtonType aceptar = new ButtonType("Aceptar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(aceptar, ButtonType.CANCEL);
        final DatosTarjeta[] resultado = {null};
        Button boton = (Button) dlg.getDialogPane().lookupButton(aceptar);
        boton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                resultado[0] = DatosTarjeta.validar(comboTerminal.getValue(), ultimos4.getText(),
                        autorizacion.getText(), terminales);
            } catch (NegocioException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
            }
        });
        dlg.setResultConverter(b -> b == aceptar ? resultado[0] : null);
        Platform.runLater(() -> (previos == null && comboTerminal.getValue() != null ? ultimos4 : comboTerminal).requestFocus());
        return dlg.showAndWait();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Diálogo Guardar / Cancelar; si el servicio rechaza los datos el error sale en el mismo diálogo */
    private void dialogoGuardar(Window owner, String titulo, String encabezado, Node contenido, Runnable guardar) {
        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("field-error-label");
        error.setVisible(false);
        error.setManaged(false);
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(titulo);
        dlg.setHeaderText(encabezado);
        if (owner != null) dlg.initOwner(owner);
        VBox caja = new VBox(10, contenido, error);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        Ventanas.estilizar(dlg);
        ButtonType guardarBtn = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardarBtn, ButtonType.CANCEL);
        Button boton = (Button) dlg.getDialogPane().lookupButton(guardarBtn);
        boton.getStyleClass().add("button-primary");
        boton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                guardar.run();
            } catch (NegocioException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
            }
        });
        dlg.showAndWait();
    }
}
