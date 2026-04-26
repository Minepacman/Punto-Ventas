package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import org.springframework.stereotype.Component;
import java.net.URL;
import java.util.ResourceBundle;

/** Controlador de Configuración */
@Component
public class ConfiguracionController implements Initializable {
    @Override public void initialize(URL url, ResourceBundle rb) { }

    @FXML void onOpciones()       { }
    @FXML void onCajeros()        { }
    @FXML void onBaseDatos()      { }
    @FXML void onArticulosPrec()  { }
    @FXML void onFacturacion()    { }
    @FXML void onFolios()         { }
    @FXML void onLogotipo()       { }
    @FXML void onTicket()         { }
    @FXML void onFormasPago()     { }
    @FXML void onImpuestos()      { }
    @FXML void onMoneda()         { }
    @FXML void onUnidades()       { }
    @FXML void onImpresora()      { }
    @FXML void onLector()         { }
    @FXML void onCajon()          { }
    @FXML void onBascula()        { }
    @FXML void onRespaldo()       { }
    @FXML void onLicencia()       { }
    @FXML void onActualizaciones(){ }
}
