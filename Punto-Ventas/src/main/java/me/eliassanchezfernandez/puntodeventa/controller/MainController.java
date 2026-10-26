package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.stage.Stage;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

/**
 * Controlador de la ventana principal.
 *
 * Responsabilidades:
 *  - Actualizar el reloj de la barra de estado
 *  - Controlar accesos por rol (cajero / admin)
 *  - Escuchar atajos de teclado F1–F4 y F12
 *  - Cerrar sesión / salir
 */
@Component
@Scope("prototype")
public class MainController implements Initializable {

    @FXML private TabPane tabPane;
    @FXML private Tab     tabVentas;
    @FXML private Tab     tabClientes;
    @FXML private Tab     tabProductos;
    @FXML private Tab     tabInventario;
    @FXML private Tab     tabConfig;
    @FXML private Tab     tabCorte;
    @FXML private Label   labelUsuario;
    @FXML private Label   labelFecha;
    @FXML private Label   labelVersion;

    // Controladores de las vistas incluidas (fx:id del include + "Controller")
    @FXML private VentasController     ventasViewController;
    @FXML private ProductosController  productosViewController;
    @FXML private InventarioController inventarioViewController;
    @FXML private CorteController      corteViewController;

    @Autowired private SesionService      sesionService;
    @Autowired private ApplicationContext springContext;

    @Value("${app.version:1.0.0}")
    private String version;

    private Timeline reloj;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy  HH:mm");

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        actualizarReloj();

        Cajero cajero = sesionService.getCajeroActual();
        labelUsuario.setText(cajero != null
                ? "Usuario: " + cajero.getNombreCompleto() + "  (" + cajero.getRol() + ")"
                : "Usuario: —");
        labelVersion.setText("v" + version);

        aplicarPermisosPorRol();
        configurarAtajosTeclado();
        configurarRefrescoDePestanas();
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    @FXML
    private void onCerrarSesion() {
        if (!Ventanas.confirmar("Cerrar sesión",
                "¿Cerrar la sesión de " + sesionService.getNombreCajero() + "?")) {
            return;
        }
        try {
            if (reloj != null) reloj.stop();
            sesionService.cerrarSesion();
            Stage stage = (Stage) tabPane.getScene().getWindow();
            Ventanas.mostrarLogin(stage, springContext);
        } catch (Exception e) {
            e.printStackTrace();
            Ventanas.alerta(Alert.AlertType.ERROR, "Error",
                    "No se pudo regresar a la pantalla de inicio de sesión.");
        }
    }

    @FXML
    private void onSalir() {
        if (Ventanas.confirmar("Salir", "¿Salir del Punto de Venta?")) {
            sesionService.cerrarSesion();
            Platform.exit(); // PuntoDeVentaApp.stop() cierra el contexto de Spring
        }
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    /** Los cajeros no ven Configuración ni Corte (solo ADMIN) */
    private void aplicarPermisosPorRol() {
        if (!sesionService.esAdmin()) {
            tabPane.getTabs().removeAll(tabConfig, tabCorte);
        }
    }

    private void actualizarReloj() {
        labelFecha.setText(LocalDateTime.now().format(FMT));
        // Actualizar cada minuto
        reloj = new Timeline(new KeyFrame(Duration.minutes(1),
                e -> labelFecha.setText(LocalDateTime.now().format(FMT))));
        reloj.setCycleCount(Animation.INDEFINITE);
        reloj.play();
    }

    /** F1–F4 cambian de pestaña; F12 cobra el ticket si estamos en Ventas */
    private void configurarAtajosTeclado() {
        // La Scene aún no existe en initialize(); se registran cuando aparece
        tabPane.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) registrarAtajos(scene);
        });
    }

    private void registrarAtajos(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F1),
                () -> tabPane.getSelectionModel().select(tabVentas));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F2),
                () -> tabPane.getSelectionModel().select(tabClientes));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F3),
                () -> tabPane.getSelectionModel().select(tabProductos));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F4),
                () -> tabPane.getSelectionModel().select(tabInventario));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F12), () -> {
            if (tabPane.getSelectionModel().getSelectedItem() == tabVentas
                    && ventasViewController != null) {
                ventasViewController.cobrarDesdeAtajo();
            }
        });
    }

    /**
     * Al cambiar de pestaña se recargan los datos de la vista destino,
     * porque una venta modifica existencias que Productos/Inventario/Corte
     * ya tenían cargadas.
     */
    private void configurarRefrescoDePestanas() {
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == null) return;
            if (tab == tabProductos  && productosViewController  != null) productosViewController.recargar();
            if (tab == tabInventario && inventarioViewController != null) inventarioViewController.recargar();
            if (tab == tabCorte      && corteViewController      != null) corteViewController.recargar();
        });
    }

}
