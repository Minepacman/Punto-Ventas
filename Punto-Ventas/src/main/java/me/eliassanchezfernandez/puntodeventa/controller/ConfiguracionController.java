package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.layout.BorderPane;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controlador de Configuración (solo ADMIN; MainController oculta la pestaña
 * a los cajeros). Por ahora solo "Cajeros" (usuarios) está implementado;
 * el resto avisa que no está disponible en esta versión.
 */
@Component
@Scope("prototype")
public class ConfiguracionController implements Initializable {

    @FXML private BorderPane raiz;

    @Autowired private SesionService      sesionService;
    @Autowired private ApplicationContext springContext;

    @Override public void initialize(URL url, ResourceBundle rb) { }

    /** Administración de usuarios (cajeros y administradores) */
    @FXML void onCajeros() {
        if (!sesionService.esAdmin()) {
            alerta(Alert.AlertType.ERROR, "Acceso denegado",
                    "Solo un administrador puede administrar los usuarios.");
            return;
        }
        try {
            Ventanas.abrirModal(springContext, "/fxml/usuarios.fxml",
                    "Usuarios del sistema", raiz.getScene().getWindow(), 900, 560);
        } catch (Exception e) {
            e.printStackTrace();
            alerta(Alert.AlertType.ERROR, "Error", "No se pudo abrir la ventana: " + e.getMessage());
        }
    }

    @FXML void onOpciones()       { noDisponible("Opciones habilitadas"); }
    @FXML void onBaseDatos()      { noDisponible("Base de datos"); }
    @FXML void onArticulosPrec()  { noDisponible("Artículos precargados"); }
    @FXML void onFacturacion()    { noDisponible("Facturación"); }
    @FXML void onFolios()         { noDisponible("Modificar folios"); }
    @FXML void onLogotipo()       { noDisponible("Logotipo"); }
    @FXML void onTicket()         { noDisponible("Ticket"); }
    @FXML void onFormasPago()     { noDisponible("Formas de pago"); }
    @FXML void onImpuestos()      { noDisponible("Impuestos"); }
    @FXML void onMoneda()         { noDisponible("Moneda"); }
    @FXML void onUnidades()       { noDisponible("Unidades de medida"); }
    @FXML void onImpresora()      { noDisponible("Impresora de tickets"); }
    @FXML void onLector()         { noDisponible("Lector de códigos"); }
    @FXML void onCajon()          { noDisponible("Cajón de dinero"); }
    @FXML void onBascula()        { noDisponible("Báscula"); }
    @FXML void onRespaldo()       { noDisponible("Respaldo automático"); }
    @FXML void onLicencia()       { noDisponible("Licencia"); }
    @FXML void onActualizaciones(){ noDisponible("Actualizaciones"); }

    private void noDisponible(String opcion) {
        alerta(Alert.AlertType.INFORMATION, opcion,
                "\"" + opcion + "\" no está disponible en esta versión.");
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
