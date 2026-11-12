package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.PuntoDeVentaApp;
import me.eliassanchezfernandez.puntodeventa.service.ActualizacionService;
import me.eliassanchezfernandez.puntodeventa.service.DonadorService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.ClaveDonador;
import me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados;
import me.eliassanchezfernandez.puntodeventa.util.Donacion;
import me.eliassanchezfernandez.puntodeventa.util.Marca;
import me.eliassanchezfernandez.puntodeventa.util.PreferenciasActualizacion;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.Instant;
import java.util.Optional;

/**
 * "Acerca de": versión, enlace al repositorio, estado de apoyo (vigente,
 * vencido o revocado, con "Pedir soporte" y el mensaje prellenado que el
 * usuario puede leer antes de enviar) o la invitación a apoyar en la versión
 * gratuita, y "Buscar actualizaciones". Se abre desde la barra de estado de
 * la ventana principal y desde Configuración. Lo que exige permiso (activar
 * o quitar la clave) lo verifica el servicio; aquí solo se oculta a quien no
 * es administrador.
 */
@Component
public class AcercaDeDialogo {

    public static final String REPOSITORIO = ActualizacionService.REPOSITORIO;

    @Autowired private SesionService        sesionService;
    @Autowired private DonadorService       donadorService;
    @Autowired private ActualizacionService actualizacionService;

    public void mostrar(Window owner) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Acerca de " + Marca.NOMBRE);
        dlg.setHeaderText(null);
        if (owner != null) dlg.initOwner(owner);

        VBox raiz = new VBox(12);
        raiz.setPrefWidth(540);
        raiz.setPadding(new Insets(6, 4, 0, 4));
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Ventanas.estilizar(dlg);
        // Los cuadros que se abran desde aquí cuelgan de esta ventana (ya existe aunque no se muestre todavía)
        Window propia = dlg.getDialogPane().getScene() != null && dlg.getDialogPane().getScene().getWindow() != null
                ? dlg.getDialogPane().getScene().getWindow() : owner;
        raiz.getChildren().addAll(cabecera(), new Separator(), seccionApoyo(dlg, propia), new Separator(),
                seccionOficial(propia), new Separator(), seccionActualizaciones(propia));
        dlg.getDialogPane().setContent(raiz);
        dlg.showAndWait();
    }

    // ── Versión oficial, marca y avisos legales ───────────────────────────

    /**
     * Canales oficiales, «la versión oficial es gratuita», estado de los datos de donación
     * (cotejados con los firmados por el autor) y los textos legales que viajan en el jar.
     */
    private VBox seccionOficial(Window owner) {
        Label titulo = new Label("Versión oficial y canales");
        titulo.getStyleClass().add("text-bold");
        Label gratuito = new Label(Marca.AVISO_GRATUITO + " Canales oficiales: el sitio, el repositorio de GitHub (versiones con "
                + "SHA256SUMS.txt y attestations) y el tap de Homebrew. " + Marca.AVISO_DONACION);
        gratuito.setWrapText(true);
        gratuito.getStyleClass().add("text-muted");
        DonadorService.EstadoDonacion v = donadorService.verificarDatosDonacion(Donacion.cargar());
        Label estado = new Label("Datos de donación: " + v.texto());
        estado.setWrapText(true);
        estado.getStyleClass().add(switch (v.verificacion()) {
            case VERIFICADOS -> "donacion-verificado";
            case NO_COINCIDEN -> "donacion-alerta";
            default -> "text-muted";
        });
        Label llave = new Label("Llave pública del autor (claves de apoyo, revocaciones y datos de donación): kid "
                + String.join(", ", ClaveDonador.oficial().kids()) + " · SHA-256 " + DatosDonacionFirmados.huellaLlaveOficial());
        llave.setWrapText(true);
        llave.getStyleClass().add("hint");
        Button terceros = new Button("Licencias de terceros");
        terceros.setOnAction(e -> mostrarTexto(owner, "Licencias de terceros", "/AVISOS-TERCEROS.md"));
        Button marca = new Button("Marca y versiones modificadas");
        marca.setOnAction(e -> mostrarTexto(owner, "Marca y términos adicionales (GPLv3 §7)", "/AVISOS-ADICIONALES.md", "/MARCA.md"));
        HBox botones = new HBox(10, terceros, marca);
        botones.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, titulo, gratuito, estado, llave, botones);
    }

    /** Muestra uno o varios archivos de texto del jar, uno tras otro, en un cuadro de solo lectura */
    private static void mostrarTexto(Window owner, String titulo, String... recursos) {
        StringBuilder sb = new StringBuilder();
        for (String r : recursos) {
            try (java.io.InputStream in = AcercaDeDialogo.class.getResourceAsStream(r)) {
                if (sb.length() > 0) sb.append("\n\n────────────────────────────────────────\n\n");
                sb.append(in == null ? "(No se encontró " + r + " en esta compilación; consulta el repositorio.)"
                        : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                sb.append("(No se pudo leer ").append(r).append(')');
            }
        }
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(titulo);
        dlg.setHeaderText(null);
        if (owner != null) dlg.initOwner(owner);
        TextArea texto = new TextArea(sb.toString());
        texto.setEditable(false);
        texto.setWrapText(true);
        texto.setPrefSize(640, 420);
        dlg.getDialogPane().setContent(texto);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Ventanas.estilizar(dlg);
        dlg.showAndWait();
    }

    // ── Cabecera ──────────────────────────────────────────────────────────

    private HBox cabecera() {
        VBox textos = new VBox(2);
        Label nombre = new Label(Marca.NOMBRE);
        nombre.getStyleClass().add("title");
        Label version = new Label("Versión " + VersionApp.version() + "  ·  software libre (GNU GPL v3)  ·  " + Marca.COPYRIGHT);
        version.getStyleClass().add("acerca-version");
        Label autor = new Label("Desarrollado por " + Marca.AUTOR + " para tiendas de abarrotes de México. "
                + "Úsalo, cópialo y compártelo: nada del programa se bloquea nunca.");
        autor.setWrapText(true);
        autor.getStyleClass().add("text-muted");
        Hyperlink sitio = new Hyperlink(Marca.SITIO_OFICIAL);
        sitio.setOnAction(e -> PuntoDeVentaApp.abrirEnlace(Marca.SITIO_OFICIAL));
        Hyperlink repo = new Hyperlink(REPOSITORIO);
        repo.setOnAction(e -> PuntoDeVentaApp.abrirEnlace(REPOSITORIO));
        textos.getChildren().addAll(nombre, version, autor, sitio, repo);
        HBox fila = new HBox(16, textos);
        fila.setAlignment(Pos.CENTER_LEFT);
        URL icono = AcercaDeDialogo.class.getResource("/icono/icono-128.png");
        if (icono != null) {
            ImageView img = new ImageView(new Image(icono.toExternalForm()));
            img.setFitWidth(72);
            img.setFitHeight(72);
            fila.getChildren().add(0, img);
        }
        return fila;
    }

    // ── Apoyo / soporte ───────────────────────────────────────────────────

    private VBox seccionApoyo(Dialog<ButtonType> dlg, Window owner) {
        VBox caja = new VBox(8);
        Optional<DonadorService.Apoyo> apoyo = donadorService.apoyo();
        Donacion.Datos datos = Donacion.cargar();
        boolean admin = sesionService.esAdmin();

        if (apoyo.isPresent()) {
            DonadorService.Apoyo a = apoyo.get();
            ClaveDonador.Datos d = a.datos();
            Label estado = new Label((a.vigente() ? "Negocio donador: " : "Apoyo: ") + a.resumen()
                    + (d.esPdv2() ? "  ·  nivel " + d.nivelApoyo().etiqueta() : d.tieneNivel() ? "  ·  nivel " + d.nivel() : "")
                    + "  ·  id " + d.id());
            estado.setWrapText(true);
            if (a.vigente()) estado.setGraphic(me.eliassanchezfernandez.puntodeventa.util.Iconos.de("corazon"));
            estado.getStyleClass().add(a.vigente() ? "acerca-donador" : "text-bold");
            caja.getChildren().add(estado);
            if (a.vigente()) {
                Label gracias = new Label("Gracias por apoyar este proyecto. Lo que recibes:");
                gracias.getStyleClass().add("text-bold");
                caja.getChildren().add(gracias);
                for (String v : DonacionDialogo.VENTAJAS) {
                    Label l = new Label(v);
                    l.setGraphic(me.eliassanchezfernandez.puntodeventa.util.Iconos.de("listo", 14));
                    l.setWrapText(true);
                    caja.getChildren().add(l);
                }
            } else {
                Label aviso = new Label(a.estado() == ClaveDonador.Estado.VENCIDA
                        ? "Tu periodo de apoyo terminó. Gracias por haber apoyado: el programa sigue funcionando igual; "
                          + "si quieres seguir con soporte personal y versiones de prueba, renueva tu apoyo cuando gustes."
                        : "Esta clave fue revocada por el desarrollador. El programa sigue funcionando igual; "
                          + "si crees que es un error, escríbele.");
                aviso.setWrapText(true);
                caja.getChildren().add(aviso);
                caja.getChildren().add(botonesApoyar(dlg, owner, datos, admin, "Renovar mi apoyo…"));
            }
            caja.getChildren().add(seccionSoporte(owner, datos, a));
            if (admin) {
                Hyperlink quitar = new Hyperlink("Quitar la clave de esta instalación");
                quitar.getStyleClass().add("text-small");
                quitar.setOnAction(e -> {
                    if (Ventanas.confirmar("Quitar clave de donador",
                            "El negocio volverá a la versión gratuita (y a ver la ventana de donación). ¿Quitar la clave?")) {
                        try {
                            donadorService.desactivar();
                            dlg.close();
                        } catch (NegocioException ex) {
                            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo quitar", ex.getMessage());
                        }
                    }
                });
                caja.getChildren().add(quitar);
            }
            return caja;
        }

        Label estado = new Label("Versión gratuita");
        estado.getStyleClass().add("text-bold");
        Label texto = new Label("Este programa es software libre: úsalo, cópialo y compártelo. Si te sirve, apoya con un "
                + "donativo del monto que quieras. Quienes apoyan reciben soporte personal por WhatsApp, prioridad para "
                + "corregir errores, aviso de versiones de prueba y su nombre aquí. Nada del programa se bloquea nunca.");
        texto.setWrapText(true);
        caja.getChildren().addAll(estado, texto, botonesApoyar(dlg, owner, datos, admin, "Cómo apoyar…"));
        return caja;
    }

    private HBox botonesApoyar(Dialog<ButtonType> dlg, Window owner, Donacion.Datos datos, boolean admin, String textoDonar) {
        HBox acciones = new HBox(10);
        acciones.setAlignment(Pos.CENTER_LEFT);
        Button donar = new Button(textoDonar);
        donar.setOnAction(e -> {
            Optional<ClaveDonador.Datos> activada = DonacionDialogo.mostrar(owner, datos, 0, donadorService, admin);
            if (activada.isPresent()) dlg.close();
        });
        acciones.getChildren().add(donar);
        if (admin) {
            Button clave = new Button("Ya doné, tengo mi clave");
            clave.setOnAction(e -> ClaveDonadorDialogo.pedir(owner, donadorService::activar).ifPresent(d -> {
                dlg.close();
                Ventanas.alerta(Alert.AlertType.INFORMATION, "¡Gracias!",
                        "Clave activada para " + d.nombre() + " (" + d.vigenciaTexto() + "). Desde ahora no verás la ventana "
                        + "de donación y tienes «Pedir soporte» en «Acerca de».");
            }));
            acciones.getChildren().add(clave);
        } else {
            Label soloAdmin = new Label("(la clave de donador la activa el administrador)");
            soloAdmin.getStyleClass().add("hint");
            acciones.getChildren().add(soloAdmin);
        }
        return acciones;
    }

    /** "Pedir soporte": abre WhatsApp/correo con el mensaje prellenado, que se muestra antes para que el usuario lo lea */
    private VBox seccionSoporte(Window owner, Donacion.Datos datos, DonadorService.Apoyo apoyo) {
        VBox caja = new VBox(4);
        caja.setPadding(new Insets(6, 0, 0, 0));
        Label titulo = new Label("Soporte");
        titulo.getStyleClass().add("text-bold");
        caja.getChildren().add(titulo);
        if (!datos.soporteConfigurado()) {
            Label pendiente = new Label("Datos de contacto por configurar");
            pendiente.getStyleClass().add("donacion-clabe-pendiente");
            caja.getChildren().add(pendiente);
            return caja;
        }
        Button pedir = new Button("Pedir soporte…");
        pedir.setOnAction(e -> pedirSoporte(owner, datos));
        Label pista = new Label(apoyo.vigente()
                ? "El mensaje lleva tu id de clave, la versión y el id de esta instalación: así el desarrollador te identifica sin preguntar."
                : "Sin apoyo vigente el desarrollador atiende según le alcance el tiempo; con apoyo, tienes prioridad.");
        pista.setWrapText(true);
        pista.getStyleClass().add("hint");
        caja.getChildren().addAll(pedir, pista);
        return caja;
    }

    private void pedirSoporte(Window owner, Donacion.Datos datos) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Pedir soporte");
        dlg.setHeaderText("Describe el problema; el resto del mensaje ya va escrito");
        if (owner != null) dlg.initOwner(owner);
        TextArea problema = new TextArea();
        problema.setPromptText("Qué pasa, desde cuándo, qué ya intentaste…");
        problema.setPrefRowCount(3);
        problema.setWrapText(true);
        TextArea vista = new TextArea(donadorService.mensajeSoporte(""));
        vista.setEditable(false);
        vista.setWrapText(true);
        vista.setPrefRowCount(6);
        vista.getStyleClass().add("text-mono");
        problema.textProperty().addListener((o, a, b) -> vista.setText(donadorService.mensajeSoporte(b)));
        Label nota = new Label("Tus datos solo se usan para atender tu soporte. Revisa el mensaje antes de enviarlo.");
        nota.setWrapText(true);
        nota.getStyleClass().add("hint");
        VBox caja = new VBox(8, problema, new Label("Mensaje que se enviará:"), vista, nota);
        caja.setPrefWidth(520);
        dlg.getDialogPane().setContent(caja);
        ButtonType wa = new ButtonType("Enviar por WhatsApp", ButtonBar.ButtonData.OK_DONE);
        ButtonType correo = new ButtonType("Enviar por correo", ButtonBar.ButtonData.OTHER);
        ButtonType copiar = new ButtonType("Copiar mensaje", ButtonBar.ButtonData.OTHER);
        if (datos.whatsappConfigurado()) dlg.getDialogPane().getButtonTypes().add(wa);
        if (datos.correoConfigurado()) dlg.getDialogPane().getButtonTypes().add(correo);
        dlg.getDialogPane().getButtonTypes().addAll(copiar, ButtonType.CANCEL);
        Ventanas.estilizar(dlg);
        Platform.runLater(problema::requestFocus);
        dlg.showAndWait().ifPresent(b -> {
            String mensaje = donadorService.mensajeSoporte(problema.getText());
            if (b == wa) PuntoDeVentaApp.abrirEnlace(Donacion.whatsappUrl(datos.soporteWhatsapp(), mensaje));
            else if (b == correo) PuntoDeVentaApp.abrirEnlace(Donacion.correoUrl(datos.soporteCorreo(), "Soporte Caja Libre", mensaje));
            else if (b == copiar) {
                ClipboardContent contenido = new ClipboardContent();
                contenido.putString(mensaje);
                Clipboard.getSystemClipboard().setContent(contenido);
            }
        });
    }

    // ── Actualizaciones ───────────────────────────────────────────────────

    private VBox seccionActualizaciones(Window owner) {
        Label resultado = new Label(donadorService.permiteBeta()
                ? "Como donador, también se te ofrecen las versiones de prueba (acceso anticipado)."
                : "Se buscan solo versiones finales.");
        resultado.setWrapText(true);
        resultado.getStyleClass().add("text-muted");
        Button buscar = new Button("Buscar actualizaciones");
        Button ver = new Button("Ver y descargar");
        ver.getStyleClass().add("button-primary");
        ver.setVisible(false);
        ver.setManaged(false);
        HBox fila = new HBox(10, buscar, ver);
        fila.setAlignment(Pos.CENTER_LEFT);
        buscar.setOnAction(e -> {
            buscar.setDisable(true);
            resultado.setText("Buscando en GitHub…");
            actualizacionService.buscarEnSegundoPlano().thenAccept(r -> Platform.runLater(() -> {
                buscar.setDisable(false);
                if (r.estado() != ActualizacionService.Estado.SIN_CONEXION) {
                    PreferenciasActualizacion.anotarRevision(RutasApp.carpetaAplicacion(), Instant.now());
                }
                switch (r.estado()) {
                    case ACTUALIZADO -> resultado.setText("Tienes la versión más reciente (" + VersionApp.etiqueta() + ").");
                    case DISPONIBLE -> {
                        ActualizacionService.Actualizacion a = r.actualizacion().orElseThrow();
                        resultado.setText("Nueva versión disponible: v" + a.version()
                                + (a.prelanzamiento() ? " (versión de prueba)" : ""));
                        ver.setVisible(true);
                        ver.setManaged(true);
                        ver.setOnAction(ev -> ActualizacionDialogo.mostrar(owner, a));
                    }
                    case SIN_CONEXION -> resultado.setText("No se pudo consultar GitHub (¿sin internet?). Inténtalo más tarde.");
                    case VERSION_DESCONOCIDA -> resultado.setText("Versión de desarrollo: no se compara con las publicadas.");
                }
            }));
        });
        Label titulo = new Label("Actualizaciones");
        titulo.getStyleClass().add("text-bold");
        return new VBox(8, titulo, fila, resultado);
    }
}
