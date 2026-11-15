package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Hyperlink;
import javafx.stage.Stage;
import javafx.util.Duration;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.service.ActualizacionService;
import me.eliassanchezfernandez.puntodeventa.service.BienvenidaService;
import me.eliassanchezfernandez.puntodeventa.service.ClimaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.DonadorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Atajo;
import me.eliassanchezfernandez.puntodeventa.util.ClimaBarra;
import me.eliassanchezfernandez.puntodeventa.util.Donacion;
import me.eliassanchezfernandez.puntodeventa.util.Icono;
import me.eliassanchezfernandez.puntodeventa.util.PreferenciasActualizacion;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Controlador de la ventana principal.
 *
 * Responsabilidades:
 *  - Actualizar el reloj de la cabecera (hora y fecha)
 *  - Controlar accesos por rol/permiso (pestañas Configuración —la última— y Corte)
 *  - Registrar los atajos de teclado del catálogo util/Atajo (F1–F5 cambian de
 *    pestaña, F10 muestra la lista; los de Ventas se delegan a VentasController
 *    solo cuando esa pestaña está activa)
 *  - Cerrar sesión / salir conservando el ticket sin cobrar (USR-15)
 */
@Component
@Scope("prototype")
public class MainController implements Initializable {

    @FXML private javafx.scene.control.ToolBar toolbar;
    @FXML private TabPane tabPane;
    @FXML private Tab     tabVentas;
    @FXML private Tab     tabProductos;
    @FXML private Tab     tabInventario;
    @FXML private Tab     tabConfig;
    @FXML private Tab     tabCorte;
    @FXML private Tab     tabEstadisticas;
    @FXML private Label   labelUsuario;
    @FXML private javafx.scene.layout.VBox relojBox;
    @FXML private javafx.scene.layout.HBox filaReloj;
    @FXML private Label   labelHora;
    @FXML private Label   labelFecha;
    @FXML private Label   labelClima;
    @FXML private Label   labelVersion;
    @FXML private Label   labelCaja;
    @FXML private Label   labelAtajos;
    @FXML private Hyperlink lnkAtajos;
    @FXML private Hyperlink lnkTutorial;
    @FXML private Hyperlink lnkAcercaDe;
    @FXML private Hyperlink lnkActualizacion;

    // Ventas se incluye en main.fxml (fx:id del include + "Controller"); el resto se carga la primera vez que se abre su pestaña
    @FXML private VentasController     ventasViewController;
    private ProductosController  productosViewController;
    private InventarioController inventarioViewController;
    private CorteController      corteViewController;
    private EstadisticasController estadisticasViewController;
    private ConfiguracionController configViewController;

    @Autowired private SesionService      sesionService;
    @Autowired private BienvenidaService  bienvenidaService;
    @Autowired private ApplicationContext springContext;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.IdentidadCaja identidadCaja;
    @Autowired private DonadorService       donadorService;
    @Autowired private ActualizacionService actualizacionService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.CatalogoCodigosService catalogoCodigosService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AcercaDeDialogo      acercaDeDialogo;
    @Autowired private ClimaService         climaService;

    /** Versión nueva encontrada en segundo plano (la abre el enlace de la barra de estado) */
    private ActualizacionService.Actualizacion actualizacionDisponible;

    private Timeline reloj;
    /** Relectura del clima de la cabecera cada 10 min (además del aviso de ClimaService al guardar un registro) */
    private Timeline refrescoClima;
    private final Runnable oyenteClima = this::refrescarClima;
    private static final String CLASE_CLIMA_VIEJO = "clima-desactualizado";
    /** Lo último que se pintó (null = nada que mostrar); acomodarClima decide si cabe en la cabecera */
    private ClimaBarra.Indicador climaMostrado;

    private static final Locale ES_MX = new Locale("es", "MX");
    private static final DateTimeFormatter FMT_HORA =
            DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FMT_FECHA =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES_MX);

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        actualizarReloj();

        Cajero cajero = sesionService.getCajeroActual();
        labelUsuario.setText(cajero != null
                ? "Usuario: " + cajero.getNombreCompleto() + "  (" + cajero.getRol().getEtiqueta() + ")"
                : "Usuario: —");
        labelVersion.setText(VersionApp.etiqueta());
        lnkActualizacion.setVisible(false);
        lnkActualizacion.setManaged(false);
        // Qué caja es esta instalación (con varias cajas, además el número si el nombre no lo dice)
        String caja = identidadCaja.nombre();
        if (identidadCaja.esMultiCaja() && !caja.contains(String.valueOf(identidadCaja.numero()))) {
            caja += " (" + identidadCaja.numero() + ")";
        }
        labelCaja.setText(caja);
        labelCaja.setTooltip(new javafx.scene.control.Tooltip(identidadCaja.esMultiCaja()
                ? "Caja " + identidadCaja.numero() + " conectada al servidor" : "Una sola caja (base de datos local)"));

        aplicarPermisos();
        configurarAtajosTeclado();
        boolean mac = Atajo.esMac();
        labelAtajos.setText(resumenAtajos(mac));
        lnkAtajos.setText("Atajos " + Atajo.AYUDA_ATAJOS.etiquetaCorta(mac));
        configurarRefrescoDePestanas();
        configurarCierreDeVentana();
        configurarClima();
        // Ya con la ventana en pantalla: asistente inicial → tutorial → donación (BienvenidaService decide cuáles)
        Platform.runLater(this::flujoDeBienvenida);
    }

    // ── Clima junto al reloj ──────────────────────────────────────────────

    /**
     * "24 °C · Soleado" con icono, del último registro que ClimaService guardó
     * (nunca consulta internet aquí). Se relee cuando el servicio avisa que
     * guardó uno nuevo o cambió la configuración, y cada 10 min por si acaso.
     * Oculto si el clima está apagado, sin ubicación o sin registros.
     */
    private void configurarClima() {
        labelClima.setVisible(false);
        labelClima.setManaged(false);
        if (sesionService.esAdmin()) labelClima.setCursor(javafx.scene.Cursor.HAND);
        // Si la ventana se angosta (o el nombre del usuario es largo) el clima cede antes que cualquier botón
        toolbar.widthProperty().addListener((o, a, b) -> acomodarClima());
        climaService.alCambiar(oyenteClima);
        refrescoClima = new Timeline(new KeyFrame(Duration.minutes(10), e -> refrescarClima()));
        refrescoClima.setCycleCount(Animation.INDEFINITE);
        refrescoClima.play();
        refrescarClima();
    }

    /** Lee la base de datos fuera del hilo de JavaFX y pinta en él. Se puede llamar desde cualquier hilo. */
    private void refrescarClima() {
        Thread t = new Thread(() -> {
            ClimaBarra.Indicador indicador = null;
            try {
                if (climaService.estaActivo()) {
                    String ciudad = climaService.ciudad();
                    indicador = climaService.ultimo()
                            .map(r -> ClimaBarra.de(r, ciudad, LocalDateTime.now()))
                            .orElse(null);
                }
            } catch (RuntimeException e) {
                System.err.println("Clima: no se pudo leer para la cabecera (" + e.getMessage() + ").");
            }
            final ClimaBarra.Indicador listo = indicador;
            Platform.runLater(() -> pintarClima(listo));
        }, "clima-cabecera");
        t.setDaemon(true);
        t.start();
    }

    private void pintarClima(ClimaBarra.Indicador indicador) {
        climaMostrado = indicador;
        if (indicador == null) {
            acomodarClima();
            return;
        }
        labelClima.setText(indicador.texto());
        if (labelClima.getGraphic() instanceof Icono icono) {
            if (!indicador.icono().equals(icono.getNombre())) icono.setNombre(indicador.icono());
        } else {
            labelClima.setGraphic(new Icono(indicador.icono(), 16));
        }
        labelClima.setTooltip(new javafx.scene.control.Tooltip(indicador.tooltip()));
        if (indicador.desactualizado()) {
            if (!labelClima.getStyleClass().contains(CLASE_CLIMA_VIEJO)) labelClima.getStyleClass().add(CLASE_CLIMA_VIEJO);
        } else {
            labelClima.getStyleClass().remove(CLASE_CLIMA_VIEJO);
        }
        acomodarClima();
    }

    /**
     * Muestra el clima solo si cabe en el ancho actual de la cabecera. Va en el
     * renglón de la hora, que es más corto que la fecha, así que normalmente no
     * ensancha nada; si aun así no cabe (ventana angosta, nombre de usuario muy
     * largo) cede el clima, nunca los botones: el cálculo usa los anchos
     * preferidos, no la posición, así mostrar u ocultar el clima no lo altera.
     */
    private void acomodarClima() {
        boolean cabe = climaMostrado != null
                && climaCabe(toolbar, relojBox, filaReloj.getSpacing(), labelClima, labelHora, labelFecha);
        labelClima.setVisible(cabe);
        labelClima.setManaged(cabe);
    }

    /**
     * @param reloj  el VBox del reloj (hora arriba con el clima a su izquierda, fecha abajo)
     * @param gap    separación entre clima y hora
     * @return true si el clima cabe con los demás elementos de la barra (o si la barra aún no tiene ancho)
     */
    static boolean climaCabe(javafx.scene.control.ToolBar barra, javafx.scene.layout.Region reloj, double gap,
                             Label clima, Label hora, Label fecha) {
        double ancho = barra.getWidth();
        if (ancho <= 0) return true;          // todavía sin dibujar: se recalcula cuando tenga ancho
        javafx.geometry.Insets p = barra.getPadding();
        double ocupado = p.getLeft() + p.getRight();
        int visibles = 0;
        for (javafx.scene.Node item : barra.getItems()) {
            if (!item.isManaged()) continue;
            visibles++;
            if (item != reloj) ocupado += item.prefWidth(-1);
        }
        // La ToolBar no expone su -fx-spacing: lo lleva el HBox interno de su skin (clase "container")
        javafx.scene.Node caja = barra.lookup(".container");
        double espacio = caja instanceof javafx.scene.layout.HBox h ? h.getSpacing() : 12;
        ocupado += espacio * Math.max(0, visibles - 1);
        double relojConClima = Math.max(clima.prefWidth(-1) + gap + hora.prefWidth(-1), fecha.prefWidth(-1));
        return ocupado + relojConClima <= ancho;
    }

    /** Al salir de esta ventana (bloquear, cerrar sesión, salir) se dejan de escuchar avisos y de releer */
    private void detenerClima() {
        climaService.quitarOyente(oyenteClima);
        if (refrescoClima != null) refrescoClima.stop();
    }

    /** Clic en el clima: solo el ADMIN abre Configuración → Ubicación y clima; para los demás no hace nada */
    @FXML
    private void onClima() {
        if (!sesionService.esAdmin() || !tabPane.getTabs().contains(tabConfig)) return;
        tabPane.getSelectionModel().select(tabConfig);     // la carga perezosa crea ConfiguracionController
        if (configViewController == null) return;
        // Un pulso después para que el contenido de la pestaña ya esté en la escena (la modal necesita su ventana)
        Platform.runLater(() -> configViewController.onClima());
    }

    // ── Bienvenida: asistente inicial, tutorial y donación ───────────────

    /**
     * Pasos que faltan justo después de entrar, en orden, cada uno modal
     * sobre la principal. Un fallo en uno no impide los siguientes ni el uso
     * de la caja. La donación la ve el ADMIN cada vez que se abre esta
     * ventana (también al volver de «Bloquear»).
     */
    private void flujoDeBienvenida() {
        Stage stage = ventana();
        if (stage == null || !stage.isShowing()) return;
        // La búsqueda de versiones nuevas corre en segundo plano mientras se muestran los pasos
        revisarActualizacionesEnSegundoPlano();
        // Catálogo de códigos de barras: revisión semanal, un par de minutos después de entrar (nunca en el arranque)
        catalogoCodigosService.programarRevisionSemanal();
        java.util.List<BienvenidaService.Paso> pasos;
        try {
            pasos = bienvenidaService.pasosPendientes();
        } catch (RuntimeException e) {
            System.err.println("Bienvenida: no se pudo consultar qué falta: " + e.getMessage());
            return;
        }
        for (BienvenidaService.Paso paso : pasos) {
            try {
                switch (paso) {
                    case ASISTENTE_INICIAL -> Ventanas.abrirModal(springContext, "/fxml/asistente_inicial.fxml",
                            "Bienvenido a Caja Libre", stage, 640, 700);
                    case TUTORIAL_GENERAL -> mostrarTutorial(true);
                    case DONACION -> DonacionDialogo.mostrar(stage, Donacion.cargar(), Donacion.SEGUNDOS_BLOQUEO,
                            donadorService, sesionService.esAdmin());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ── Actualizaciones y "Acerca de" ─────────────────────────────────────

    /**
     * A lo más una vez al día y solo si el administrador no lo apagó: consulta
     * GitHub fuera del hilo de JavaFX y, si hay versión nueva, enciende un
     * enlace discreto en la barra de estado (nada de ventanas emergentes).
     * Solo para quien podría instalarla (ADMIN o GERENTE). Sin internet no pasa nada.
     */
    private void revisarActualizacionesEnSegundoPlano() {
        if (!sesionService.esAdmin() && !sesionService.esGerente()) return;
        try {
            if (!configuracionService.isActualizacionesAuto()) return;
        } catch (RuntimeException e) {
            return;
        }
        java.nio.file.Path carpeta = RutasApp.carpetaAplicacion();
        if (!PreferenciasActualizacion.tocaRevisar(carpeta, java.time.Instant.now())) return;
        actualizacionService.buscarEnSegundoPlano().thenAccept(r -> {
            if (r.estado() != ActualizacionService.Estado.SIN_CONEXION) {
                PreferenciasActualizacion.anotarRevision(carpeta, java.time.Instant.now());
            }
            r.actualizacion().ifPresent(a -> Platform.runLater(() -> mostrarAvisoDeActualizacion(a)));
        });
    }

    private void mostrarAvisoDeActualizacion(ActualizacionService.Actualizacion a) {
        actualizacionDisponible = a;
        lnkActualizacion.setText("Nueva versión " + a.version() + (a.prelanzamiento() ? " (prueba)" : "") + " disponible");
        lnkActualizacion.setVisible(true);
        lnkActualizacion.setManaged(true);
    }

    @FXML
    private void onActualizacion() {
        if (actualizacionDisponible != null) ActualizacionDialogo.mostrar(ventana(), actualizacionDisponible);
    }

    @FXML
    private void onAcercaDe() {
        acercaDeDialogo.mostrar(ventana());
    }

    /** «Tutorial» en la barra de estado: el mismo recorrido, las veces que se quiera */
    @FXML
    private void onTutorial() {
        mostrarTutorial(false);
    }

    /** @param primeraVez true = al terminar (o saltar) se marca como visto para este usuario */
    private void mostrarTutorial(boolean primeraVez) {
        Stage stage = ventana();
        if (stage == null) return;
        // fx:id → pestaña, solo las que este usuario conserva (aplicarPermisos quita las demás)
        java.util.Map<String, Tab> todas = new java.util.LinkedHashMap<>();
        todas.put("tabVentas", tabVentas);
        todas.put("tabProductos", tabProductos);
        todas.put("tabInventario", tabInventario);
        todas.put("tabCorte", tabCorte);
        todas.put("tabEstadisticas", tabEstadisticas);
        todas.put("tabConfig", tabConfig);
        java.util.Map<String, Tab> visibles = new java.util.LinkedHashMap<>();
        todas.forEach((id, t) -> { if (tabPane.getTabs().contains(t)) visibles.put(id, t); });
        try {
            TutorialGeneralDialogo.mostrar(stage, tabPane, visibles);
        } finally {
            if (primeraVez) {
                try {
                    bienvenidaService.marcarTutorialGeneralVisto();
                } catch (RuntimeException e) {
                    System.err.println("Tutorial: no se pudo marcar como visto: " + e.getMessage());
                }
            }
        }
    }

    private Stage ventana() {
        if (tabPane.getScene() == null) return null;
        return tabPane.getScene().getWindow() instanceof Stage s ? s : null;
    }

    /** El ticket sin cobrar se guarda también si cierran la ventana (X) o la app (Cmd+Q) */
    private void configurarCierreDeVentana() {
        Ventanas.setAccionAlCerrar(this::guardarTicketPendiente);
        tabPane.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) return;
            scene.windowProperty().addListener((o, w0, window) -> {
                if (window instanceof Stage stage) {
                    stage.setOnCloseRequest(e -> guardarTicketPendiente());
                }
            });
            if (scene.getWindow() instanceof Stage stage) {
                stage.setOnCloseRequest(e -> guardarTicketPendiente());
            }
        });
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    @FXML
    private void onCerrarSesion() {
        if (!Ventanas.confirmar("Cerrar sesión",
                "¿Cerrar la sesión de " + sesionService.getNombreCajero() + "?"
                + (ventasViewController != null && ventasViewController.hayTicketEnCaptura()
                   ? "\nEl ticket sin cobrar se conservará para cuando vuelvas a entrar." : ""))) {
            return;
        }
        volverAlLogin();
    }

    /**
     * Bloquear / cambio rápido de cajero (Ctrl+L): vuelve al inicio de sesión SIN
     * confirmar y sin cerrar el programa. El ticket en captura se guarda como
     * pendiente y se restaura cuando el mismo cajero vuelve a entrar (USR-15);
     * el login recuerda al último usuario, así que solo teclea su contraseña.
     */
    @FXML
    private void onBloquear() {
        // El ticket en captura queda "en espera" para que cualquier cajero de la caja lo recupere
        if (ventasViewController != null) ventasViewController.ponerEnEsperaPorBloqueo();
        volverAlLogin();
    }

    private void volverAlLogin() {
        try {
            if (reloj != null) reloj.stop();
            detenerClima();
            guardarTicketPendiente();
            Ventanas.setAccionAlCerrar(null);
            sesionService.cerrarSesion();
            Stage stage = (Stage) tabPane.getScene().getWindow();
            stage.setOnCloseRequest(null);
            Ventanas.mostrarLogin(stage, springContext);
        } catch (Exception e) {
            e.printStackTrace();
            Ventanas.alerta(Alert.AlertType.ERROR, "Error",
                    "No se pudo regresar a la pantalla de inicio de sesión.");
        }
    }

    @FXML
    private void onSalir() {
        if (Ventanas.confirmar("Salir", "¿Salir de Caja Libre?")) {
            detenerClima();
            guardarTicketPendiente();
            Ventanas.setAccionAlCerrar(null);
            sesionService.cerrarSesion();
            Platform.exit(); // PuntoDeVentaApp.stop() cierra el contexto de Spring
        }
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    /** El ticket en captura se guarda para el mismo cajero (USR-15) */
    private void guardarTicketPendiente() {
        try {
            if (ventasViewController != null) ventasViewController.guardarTicketPendiente();
        } catch (Exception e) {
            e.printStackTrace();   // no impedir el cierre de sesión por esto
        }
    }

    /**
     * Configuración: ADMIN y GERENTE (usuarios). Corte: quien pueda hacer el
     * corte o ver ganancias. Lo demás se restringe dentro de cada pantalla.
     */
    private void aplicarPermisos() {
        if (!sesionService.esAdmin() && !sesionService.esGerente()) {
            tabPane.getTabs().remove(tabConfig);
        }
        if (!sesionService.tienePermiso(Permiso.HACER_CORTE)
                && !sesionService.tienePermiso(Permiso.VER_GANANCIAS)) {
            tabPane.getTabs().remove(tabCorte);
        }
        if (!sesionService.tienePermiso(Permiso.VER_HISTORIAL)) {
            tabPane.getTabs().remove(tabEstadisticas);
        }
    }

    private void actualizarReloj() {
        refrescarReloj();
        // Tick cada segundo: la hora mostrada nunca se queda atrasada
        reloj = new Timeline(new KeyFrame(Duration.seconds(1), e -> refrescarReloj()));
        reloj.setCycleCount(Animation.INDEFINITE);
        reloj.play();
    }

    /** Hora (HH:mm) y fecha en español con el día de la semana en mayúscula inicial */
    private void refrescarReloj() {
        LocalDateTime ahora = LocalDateTime.now();
        labelHora.setText(ahora.format(FMT_HORA));
        String fecha = ahora.format(FMT_FECHA);
        labelFecha.setText(Character.toUpperCase(fecha.charAt(0)) + fecha.substring(1));
    }

    /** Texto corto de la barra de estado: las pestañas y lo esencial de Ventas */
    static String resumenAtajos(boolean mac) {
        StringBuilder sb = new StringBuilder();
        for (Atajo a : java.util.List.of(Atajo.IR_VENTAS, Atajo.IR_PRODUCTOS, Atajo.IR_INVENTARIO,
                Atajo.IR_ESTADISTICAS, Atajo.BUSCAR_PRODUCTO, Atajo.PONER_EN_ESPERA, Atajo.COBRAR)) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(a.etiquetaCorta(mac)).append(' ').append(nombreCorto(a));
        }
        return sb.toString();
    }

    private static String nombreCorto(Atajo a) {
        return switch (a) {
            case IR_VENTAS -> "Ventas";
            case IR_PRODUCTOS -> "Productos";
            case IR_INVENTARIO -> "Inventario";
            case IR_ESTADISTICAS -> "Estadísticas";
            case BUSCAR_PRODUCTO -> "Buscar";
            case PONER_EN_ESPERA -> "En espera";
            case COBRAR -> "Cobrar";
            default -> a.getDescripcion();
        };
    }

    @FXML
    private void onAtajos() {
        AtajosDialogo.mostrar(tabPane.getScene() != null ? tabPane.getScene().getWindow() : null);
    }

    /**
     * Aceleradores de la escena: los globales cambian de pestaña o abren la ayuda;
     * los de Ventas se delegan a VentasController solo con esa pestaña activa.
     * Un acelerador no se dispara si el control con el foco consumió la tecla
     * (p. ej. Supr dentro de un campo de texto) ni mientras hay un diálogo modal
     * abierto (tiene su propia escena); las teclas que compiten con la escritura
     * las resuelve VentasController con filtros según el foco.
     */
    private void configurarAtajosTeclado() {
        // La Scene aún no existe en initialize(); se registran cuando aparece
        tabPane.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) registrarAtajos(scene);
        });
    }

    private void registrarAtajos(Scene scene) {
        for (Atajo atajo : Atajo.values()) {
            if (atajo.compiteConEscritura()) continue;     // Supr, +, −: los atiende VentasController según el foco
            Runnable accion = atajo.getAmbito() == Atajo.Ambito.GLOBAL
                    ? () -> ejecutarGlobal(atajo)
                    : () -> {
                        if (tabPane.getSelectionModel().getSelectedItem() == tabVentas && ventasViewController != null) {
                            ventasViewController.ejecutarAtajo(atajo);
                        }
                    };
            for (Atajo.Tecla tecla : atajo.teclas()) {
                scene.getAccelerators().put(tecla.aCombinacion(), accion);
            }
        }
    }

    private void ejecutarGlobal(Atajo atajo) {
        switch (atajo) {
            case IR_VENTAS       -> tabPane.getSelectionModel().select(tabVentas);
            case IR_PRODUCTOS    -> seleccionarSiExiste(tabProductos);
            case IR_INVENTARIO   -> seleccionarSiExiste(tabInventario);
            case IR_ESTADISTICAS -> seleccionarSiExiste(tabEstadisticas);
            case AYUDA_ATAJOS    -> onAtajos();
            case BLOQUEAR        -> onBloquear();
            default -> { }
        }
    }

    /** Las pestañas que el usuario no puede ver se quitaron del TabPane: el atajo no hace nada */
    private void seleccionarSiExiste(Tab tab) {
        if (tabPane.getTabs().contains(tab)) tabPane.getSelectionModel().select(tab);
    }

    /**
     * Al cambiar de pestaña se recargan los datos de la vista destino,
     * porque una venta modifica existencias que Productos/Inventario/Corte
     * ya tenían cargadas. Ventas relee las formas de pago habilitadas.
     */
    private void configurarRefrescoDePestanas() {
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == null) return;
            // Primera vez: se construye la pantalla (que ya carga sus datos al iniciar), sin recargar dos veces
            boolean recienCargada = cargarPestanaPerezosa(tab);
            if (tab == tabVentas     && ventasViewController     != null) ventasViewController.aplicarConfiguracion();
            if (recienCargada) return;
            if (tab == tabProductos  && productosViewController  != null) productosViewController.recargar();
            if (tab == tabInventario && inventarioViewController != null) inventarioViewController.recargar();
            if (tab == tabCorte      && corteViewController      != null) corteViewController.recargar();
            if (tab == tabEstadisticas && estadisticasViewController != null) estadisticasViewController.recargar();
        });
    }

    /** FXML de cada pestaña que se carga al abrirla por primera vez */
    private String fxmlDe(Tab tab) {
        // Clientes (F2) se agregará aquí cuando exista el módulo: "/fxml/clientes.fxml"
        if (tab == tabProductos)    return "/fxml/productos.fxml";
        if (tab == tabInventario)   return "/fxml/inventario.fxml";
        if (tab == tabConfig)       return "/fxml/configuracion.fxml";
        if (tab == tabCorte)        return "/fxml/corte.fxml";
        if (tab == tabEstadisticas) return "/fxml/estadisticas.fxml";
        return null;
    }

    /** @return true si la pestaña acaba de construirse en esta llamada */
    private boolean cargarPestanaPerezosa(Tab tab) {
        String fxml = fxmlDe(tab);
        if (fxml == null || tab.getContent() != null) return false;
        try {
            FXMLLoader loader = new FXMLLoader(MainController.class.getResource(fxml));
            loader.setControllerFactory(springContext::getBean);
            Parent contenido = loader.load();
            Object controlador = loader.getController();
            if (controlador instanceof ProductosController c)    productosViewController = c;
            if (controlador instanceof InventarioController c)   inventarioViewController = c;
            if (controlador instanceof CorteController c)        corteViewController = c;
            if (controlador instanceof EstadisticasController c) estadisticasViewController = c;
            if (controlador instanceof ConfiguracionController c) configViewController = c;
            tab.setContent(contenido);
            return true;
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            Ventanas.alerta(Alert.AlertType.ERROR, "Error", "No se pudo abrir la pantalla: " + e.getMessage());
            return false;
        }
    }
}
