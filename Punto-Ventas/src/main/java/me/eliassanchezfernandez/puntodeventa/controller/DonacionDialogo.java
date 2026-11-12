package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import me.eliassanchezfernandez.puntodeventa.PuntoDeVentaApp;
import me.eliassanchezfernandez.puntodeventa.service.DonadorService;
import me.eliassanchezfernandez.puntodeventa.util.ClaveDonador;
import me.eliassanchezfernandez.puntodeventa.util.Donacion;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Ventana de donación (software libre): la ve el administrador en cada
 * inicio de sesión (y al desbloquear) mientras el negocio no tenga una clave
 * de apoyo vigente. Durante {@code segundosBloqueo} no se puede cerrar: el
 * botón está deshabilitado con cuenta regresiva, la X de la ventana, Esc y
 * Alt+F4 / Cmd+W se ignoran. La cuenta corre con un Timeline, sin bloquear
 * el hilo de JavaFX. Los datos vienen de donacion.properties a través de
 * {@link Donacion}.
 *
 * Lo que se "vende" son servicios (soporte, prioridad, betas), no candados:
 * nada del programa se bloquea nunca. "Ya doné: pedir mi clave" arma un
 * mensaje prellenado (negocio, monto, fecha, clave de rastreo, id de
 * instalación y versión) que el donador manda por WhatsApp o correo; "Ya doné,
 * tengo mi clave" funciona también durante la cuenta: una clave válida cierra
 * la ventana de inmediato.
 */
public final class DonacionDialogo {

    private DonacionDialogo() { }

    /** Lo que ofrece el programa a quien dona (también lo lee "Acerca de") */
    public static final String[] VENTAJAS = {
            "Soporte personal por WhatsApp o correo, con prioridad para corregir errores.",
            "Funciones a la medida que tu negocio necesite (según el tiempo disponible).",
            "Aviso y acceso anticipado a las versiones de prueba.",
            "Tu nombre en «Acerca de» como negocio que apoya el proyecto.",
    };

    /**
     * Muestra la ventana y espera a que el administrador la cierre.
     * @param segundosBloqueo 0 = se puede cerrar desde el principio (cuando se abre a propósito desde "Acerca de")
     * @param donadorService  arma los mensajes y activa la clave (si {@code puedeActivar})
     * @param puedeActivar    true solo para el administrador: muestra "Ya doné, tengo mi clave"
     * @return los datos del donador si activó una clave válida
     */
    public static Optional<ClaveDonador.Datos> mostrar(Window owner, Donacion.Datos datos, int segundosBloqueo,
                                                       DonadorService donadorService, boolean puedeActivar) {
        Stage stage = new Stage();
        stage.setTitle("Apoya este proyecto");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        Ventanas.aplicarIcono(stage);
        stage.setResizable(false);

        Label titulo = new Label("Este Punto de Venta es software libre");
        titulo.getStyleClass().add("title");
        Label texto = new Label("Lo puedes usar, copiar y compartir sin pagar licencias, y nada se bloquea nunca. Lo desarrolla "
                + "una sola persona en su tiempo libre; si te sirve, un donativo —del monto que tú decidas— ayuda "
                + "muchísimo a seguir mejorándolo.");
        texto.setWrapText(true);
        texto.setMaxWidth(520);
        texto.getStyleClass().add("donacion-texto");

        // Ventajas
        Label subVentajas = new Label("Con tu apoyo recibes");
        subVentajas.getStyleClass().add("donacion-subtitulo");
        VBox ventajas = new VBox(4, subVentajas);
        for (String v : VENTAJAS) {
            Label l = new Label("✓  " + v);
            l.setWrapText(true);
            l.setMaxWidth(520);
            l.getStyleClass().add("donacion-ventaja");
            ventajas.getChildren().add(l);
        }

        // Cuenta
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
        cuenta.addRow(3, etiqueta("Concepto:"), valor("PDV + el nombre de tu negocio (así se identifica tu abono)"));
        VBox cajaCuenta = new VBox(cuenta);
        cajaCuenta.getStyleClass().add("donacion-cuenta");

        // Cómo recibir la clave
        VBox comoRecibir = new VBox(4);
        Label nota = new Label("Después de donar, pulsa «Ya doné: pedir mi clave» y envía el mensaje al desarrollador; "
                + "recibirás tu clave de apoyo (vale un año, renovable, y para todas las cajas del negocio).");
        nota.setWrapText(true);
        nota.setMaxWidth(520);
        nota.getStyleClass().add("donacion-nota");
        comoRecibir.getChildren().add(nota);
        comoRecibir.getChildren().add(filaContacto(datos));

        // Pie: contador, pedir clave, tengo clave, cerrar
        Label contador = new Label();
        contador.getStyleClass().add("donacion-contador");
        Button btnPedir = new Button("Ya doné: pedir mi clave");
        btnPedir.setDisable(!datos.soporteConfigurado());
        Button btnClave = new Button("Ya tengo mi clave");
        btnClave.setVisible(puedeActivar);
        btnClave.setManaged(puedeActivar);
        Button btnCerrar = new Button("Cerrar");
        btnCerrar.getStyleClass().add("button-primary");
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox pie = new HBox(12, contador, espacio, btnPedir, btnClave, btnCerrar);
        pie.setAlignment(Pos.CENTER_LEFT);

        VBox raiz = new VBox(12, titulo, texto, ventajas, cajaCuenta, comoRecibir, pie);
        raiz.setPadding(new Insets(20, 24, 16, 24));
        raiz.setPrefWidth(600);
        raiz.getStyleClass().add("donacion-root");

        Scene scene = new Scene(raiz);
        Ventanas.aplicarCss(scene);
        stage.setScene(scene);

        final boolean[] bloqueada = {segundosBloqueo > 0};
        final Optional<ClaveDonador.Datos>[] activada = new Optional[] {Optional.empty()};
        btnCerrar.setDisable(bloqueada[0]);
        int[] restantes = {segundosBloqueo};
        Timeline cuentaRegresiva = new Timeline();
        if (bloqueada[0]) {
            contador.setText(textoContador(restantes[0]));
            cuentaRegresiva.getKeyFrames().add(new KeyFrame(Duration.seconds(1), e -> {
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
            cuentaRegresiva.setCycleCount(segundosBloqueo);
            cuentaRegresiva.play();
        }

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
        btnPedir.setOnAction(e -> pedirClave(stage, datos, donadorService));
        btnClave.setOnAction(e -> {
            Optional<ClaveDonador.Datos> d = ClaveDonadorDialogo.pedir(stage, donadorService::activar);
            if (d.isPresent()) {
                activada[0] = d;
                bloqueada[0] = false;        // la clave válida libera la ventana de inmediato
                cuentaRegresiva.stop();
                stage.close();
            }
        });

        try {
            stage.showAndWait();
        } finally {
            cuentaRegresiva.stop();
        }
        return activada[0];
    }

    /** Formulario "ya doné": arma el mensaje con lo que el desarrollador necesita para ubicar el abono y emitir la clave */
    static void pedirClave(Window owner, Donacion.Datos datos, DonadorService donadorService) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Pedir mi clave de apoyo");
        dlg.setHeaderText("Con estos datos el desarrollador ubica tu abono y te manda la clave");
        if (owner != null) dlg.initOwner(owner);
        TextField negocio = new TextField();
        negocio.setPromptText("Como quieres que aparezca en la clave");
        TextField monto = new TextField();
        monto.setPromptText("p. ej. 300");
        TextField fecha = new TextField(LocalDate.now().getDayOfMonth() + "/" + LocalDate.now().getMonthValue() + "/" + LocalDate.now().getYear());
        TextField rastreo = new TextField();
        rastreo.setPromptText("Opcional: la trae el comprobante del SPEI");
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.addRow(0, etiqueta("Negocio:"), negocio);
        g.addRow(1, etiqueta("Monto:"), monto);
        g.addRow(2, etiqueta("Fecha:"), fecha);
        g.addRow(3, etiqueta("Clave de rastreo:"), rastreo);
        TextArea vista = new TextArea();
        vista.setEditable(false);
        vista.setWrapText(true);
        vista.setPrefRowCount(6);
        vista.getStyleClass().add("text-mono");
        Runnable refrescar = () -> vista.setText(donadorService.mensajeDonacion(negocio.getText(), monto.getText(),
                fecha.getText(), rastreo.getText()));
        for (TextField t : new TextField[] {negocio, monto, fecha, rastreo}) t.textProperty().addListener((o, a, b) -> refrescar.run());
        refrescar.run();
        Label nota = new Label("Tus datos solo se usan para emitir tu clave y atender tu soporte. Revisa el mensaje antes de enviarlo.");
        nota.setWrapText(true);
        nota.getStyleClass().add("hint");
        VBox caja = new VBox(8, g, new Label("Mensaje que se enviará:"), vista, nota);
        caja.setPrefWidth(520);
        dlg.getDialogPane().setContent(caja);
        ButtonType wa = new ButtonType("Enviar por WhatsApp", ButtonBar.ButtonData.OK_DONE);
        ButtonType correo = new ButtonType("Enviar por correo", ButtonBar.ButtonData.OTHER);
        ButtonType copiar = new ButtonType("Copiar mensaje", ButtonBar.ButtonData.OTHER);
        if (datos.whatsappConfigurado()) dlg.getDialogPane().getButtonTypes().add(wa);
        if (datos.correoConfigurado()) dlg.getDialogPane().getButtonTypes().add(correo);
        dlg.getDialogPane().getButtonTypes().addAll(copiar, ButtonType.CANCEL);
        Ventanas.estilizar(dlg);
        Platform.runLater(negocio::requestFocus);
        dlg.showAndWait().ifPresent(b -> {
            String mensaje = vista.getText();
            if (b == wa) PuntoDeVentaApp.abrirEnlace(Donacion.whatsappUrl(datos.soporteWhatsapp(), mensaje));
            else if (b == correo) PuntoDeVentaApp.abrirEnlace(Donacion.correoUrl(datos.soporteCorreo(), "Donación al Punto de Venta", mensaje));
            else if (b == copiar) {
                ClipboardContent contenido = new ClipboardContent();
                contenido.putString(mensaje);
                Clipboard.getSystemClipboard().setContent(contenido);
            }
        });
    }

    /** Contactos del desarrollador (solo informativos; el envío va por "Ya doné: pedir mi clave") */
    static HBox filaContacto(Donacion.Datos datos) {
        HBox fila = new HBox(14);
        fila.setAlignment(Pos.CENTER_LEFT);
        Label l = new Label("Contacto:");
        l.getStyleClass().add("field-label");
        fila.getChildren().add(l);
        if (!datos.soporteConfigurado()) {
            Label pendiente = new Label("Datos de contacto por configurar");
            pendiente.getStyleClass().add("donacion-clabe-pendiente");
            fila.getChildren().add(pendiente);
            return fila;
        }
        if (datos.whatsappConfigurado()) {
            Hyperlink wa = new Hyperlink("WhatsApp " + datos.soporteWhatsapp());
            wa.setOnAction(e -> PuntoDeVentaApp.abrirEnlace(Donacion.whatsappUrl(datos.soporteWhatsapp(), null)));
            fila.getChildren().add(wa);
        }
        if (datos.correoConfigurado()) {
            Hyperlink correo = new Hyperlink(datos.soporteCorreo());
            correo.setOnAction(e -> PuntoDeVentaApp.abrirEnlace(Donacion.correoUrl(datos.soporteCorreo(), "Donación al Punto de Venta")));
            fila.getChildren().add(correo);
        }
        return fila;
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
        l.setWrapText(true);
        l.setMaxWidth(420);
        l.getStyleClass().add("text-bold");
        return l;
    }
}
