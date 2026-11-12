package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.util.Donacion;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

/**
 * Ventana de donación (software libre): la ve el administrador en cada
 * inicio de sesión. Durante {@link Donacion#SEGUNDOS_BLOQUEO} segundos no se
 * puede cerrar: el botón está deshabilitado con cuenta regresiva, la X de la
 * ventana, Esc y Alt+F4 / Cmd+W se ignoran (el cierre se consume). La cuenta
 * corre con un Timeline, sin bloquear el hilo de JavaFX. Los datos vienen de
 * donacion.properties a través de {@link Donacion}.
 */
public final class DonacionDialogo {

    private DonacionDialogo() { }

    /** Muestra la ventana y espera a que el administrador la cierre */
    public static void mostrar(Window owner, Donacion.Datos datos) {
        Stage stage = new Stage();
        stage.setTitle("Apoya este proyecto");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setResizable(false);

        Label titulo = new Label("Este Punto de Venta es software libre");
        titulo.getStyleClass().add("title");
        Label texto = new Label("Lo puedes usar, copiar y compartir sin pagar licencias. Lo desarrolla una sola persona "
                + "en su tiempo libre; si te sirve, una donación —del monto que tú decidas— ayuda muchísimo a "
                + "seguir mejorándolo. Gracias por leer esto.");
        texto.setWrapText(true);
        texto.setMaxWidth(440);
        texto.getStyleClass().add("donacion-texto");

        boolean configurada = datos.clabeConfigurada();
        Label lblClabe = new Label(configurada ? Donacion.formatear(datos.clabe()) : "CLABE no configurada");
        lblClabe.getStyleClass().add(configurada ? "donacion-clabe" : "donacion-clabe-pendiente");
        Button btnCopiar = new Button("Copiar CLABE");
        btnCopiar.setDisable(!configurada);
        btnCopiar.setOnAction(e -> {
            ClipboardContent contenido = new ClipboardContent();
            contenido.putString(Donacion.limpiarClabe(datos.clabe()));
            Clipboard.getSystemClipboard().setContent(contenido);
            btnCopiar.setText("Copiada");
        });
        HBox filaClabe = new HBox(12, lblClabe, btnCopiar);
        filaClabe.setAlignment(Pos.CENTER_LEFT);

        GridPane cuenta = new GridPane();
        cuenta.setHgap(12);
        cuenta.setVgap(6);
        cuenta.addRow(0, etiqueta("CLABE:"), filaClabe);
        cuenta.addRow(1, etiqueta("Banco:"), valor(datos.banco()));
        cuenta.addRow(2, etiqueta("Beneficiario:"), valor(datos.beneficiario()));
        VBox cajaCuenta = new VBox(cuenta);
        cajaCuenta.getStyleClass().add("donacion-cuenta");

        Label contador = new Label();
        contador.getStyleClass().add("donacion-contador");
        Button btnCerrar = new Button("Cerrar");
        btnCerrar.getStyleClass().add("button-primary");
        btnCerrar.setDisable(true);
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox pie = new HBox(12, contador, espacio, btnCerrar);
        pie.setAlignment(Pos.CENTER_LEFT);

        VBox raiz = new VBox(14, titulo, texto, cajaCuenta, pie);
        raiz.setPadding(new Insets(22, 24, 18, 24));
        raiz.getStyleClass().add("donacion-root");

        Scene scene = new Scene(raiz);
        Ventanas.aplicarCss(scene);
        stage.setScene(scene);

        final boolean[] bloqueada = {true};
        int[] restantes = {Donacion.SEGUNDOS_BLOQUEO};
        contador.setText(textoContador(restantes[0]));
        Timeline cuentaRegresiva = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            restantes[0]--;
            if (restantes[0] <= 0) {
                bloqueada[0] = false;
                contador.setText("Gracias por tu tiempo.");
                btnCerrar.setDisable(false);
                btnCerrar.requestFocus();
            } else {
                contador.setText(textoContador(restantes[0]));
            }
        }));
        cuentaRegresiva.setCycleCount(Donacion.SEGUNDOS_BLOQUEO);
        cuentaRegresiva.play();

        // X, Alt+F4, Cmd+W: mientras está bloqueada no se cierra
        stage.setOnCloseRequest(e -> {
            if (bloqueada[0]) e.consume();
        });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (bloqueada[0] && (e.getCode() == KeyCode.ESCAPE
                    || (e.getCode() == KeyCode.W && e.isShortcutDown()))) {
                e.consume();
            }
        });
        btnCerrar.setOnAction(e -> {
            if (!bloqueada[0]) stage.close();
        });

        try {
            stage.showAndWait();
        } finally {
            cuentaRegresiva.stop();
        }
    }

    static String textoContador(int segundos) {
        return "Podrás cerrar en " + segundos + " s";
    }

    private static Label etiqueta(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("field-label");
        return l;
    }

    private static Label valor(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("text-bold");
        return l;
    }
}
