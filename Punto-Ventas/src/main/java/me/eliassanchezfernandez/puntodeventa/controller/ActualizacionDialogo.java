package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.PuntoDeVentaApp;
import me.eliassanchezfernandez.puntodeventa.service.ActualizacionService;
import me.eliassanchezfernandez.puntodeventa.service.ActualizacionService.Actualizacion;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;

/**
 * "Hay una versión nueva": número, notas de la versión y el botón Descargar,
 * que abre en el navegador el instalador del sistema (o la página de la
 * versión si no hay uno para este equipo). El programa nunca descarga ni
 * ejecuta instaladores por su cuenta.
 */
public final class ActualizacionDialogo {

    private ActualizacionDialogo() { }

    public static void mostrar(Window owner, Actualizacion a) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Actualización disponible");
        dlg.setHeaderText("Nueva versión " + a.version() + (a.prelanzamiento() ? "  (versión de prueba, acceso anticipado)" : ""));
        if (owner != null) dlg.initOwner(owner);

        Label instalada = new Label("Instalada: " + VersionApp.etiqueta() + "   →   Nueva: v" + a.version()
                + (a.nombre().isBlank() || a.nombre().equals(a.etiqueta()) ? "" : "  ·  " + a.nombre()));
        instalada.getStyleClass().add("text-bold");
        TextArea notas = new TextArea(a.notas().isBlank() ? "(Sin notas de la versión)" : a.notas());
        notas.setEditable(false);
        notas.setWrapText(true);
        notas.setPrefSize(560, 260);
        Label como = new Label(a.urlDescarga().isPresent()
                ? "«Descargar» abre el instalador en tu navegador. Cierra el programa, ejecuta el instalador y listo: "
                  + "tus datos y tu configuración se conservan."
                : "No hay instalador para este equipo en esa versión; «Ver en GitHub» abre la página de la versión.");
        como.setWrapText(true);
        como.setPrefWidth(560);
        como.getStyleClass().add("hint");
        dlg.getDialogPane().setContent(new VBox(10, instalada, notas, como));

        ButtonType descargar = new ButtonType(a.urlDescarga().isPresent() ? "Descargar" : "Ver en GitHub", ButtonBar.ButtonData.OK_DONE);
        ButtonType pagina = new ButtonType("Ver en GitHub", ButtonBar.ButtonData.OTHER);
        dlg.getDialogPane().getButtonTypes().add(descargar);
        if (a.urlDescarga().isPresent()) dlg.getDialogPane().getButtonTypes().add(pagina);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) dlg.getDialogPane().lookupButton(descargar)).getStyleClass().add("button-primary");
        Ventanas.estilizar(dlg);
        dlg.showAndWait().ifPresent(b -> {
            if (b == descargar) abrir(a.urlParaAbrir());
            else if (b == pagina) abrir(a.urlPagina());
        });
    }

    /** Segunda barrera: solo enlaces del repositorio (el servicio ya los filtró, pero no cuesta nada) */
    private static void abrir(String url) {
        PuntoDeVentaApp.abrirEnlace(ActualizacionService.enlacePermitido(url) ? url : ActualizacionService.PAGINA_RELEASES);
    }
}
