package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import me.eliassanchezfernandez.puntodeventa.PuntoDeVentaApp;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.service.ArticulosPrecargadosService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ResourceBundle;

/**
 * Controlador de Configuración (ADMIN y GERENTE; MainController oculta la
 * pestaña a los cajeros). "Cajeros" (usuarios y permisos) lo ven ambos; todo
 * lo demás es solo ADMIN y además lo verifica cada servicio. Las pantallas
 * grandes tienen su propio FXML/controlador; las chicas (formas de pago,
 * moneda, folios, respaldo automático, licencia, actualizaciones) son diálogos
 * armados aquí. Los dispositivos (impresora, báscula, lector, cajón y terminal bancaria) son solo ADMIN; Facturación avisa que es de una etapa posterior.
 */
@Component
@Scope("prototype")
public class ConfiguracionController implements Initializable {

    private static final String REPOSITORIO = "https://github.com/Minepacman/Punto-Ventas";

    @FXML private BorderPane raiz;

    @Autowired private SesionService      sesionService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.util.RutasApp rutas;
    @Autowired private ApplicationContext springContext;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ArticulosPrecargadosService articulosPrecargadosService;
    @Autowired private DispositivosDialogos dispositivosDialogos;

    @Value("${app.version:1.0.0}")
    private String version;

    @Override public void initialize(URL url, ResourceBundle rb) { }

    /** Administración de usuarios (ADMIN completo; GERENTE solo consulta y desactiva) */
    @FXML void onCajeros() {
        if (!sesionService.esAdmin() && !sesionService.esGerente()) {
            alerta(Alert.AlertType.ERROR, "Acceso denegado",
                    "Solo un administrador o gerente puede administrar los usuarios.");
            return;
        }
        abrir("/fxml/usuarios.fxml", "Usuarios del sistema", 1000, 640);
    }

    // ── Pantallas con FXML (solo ADMIN) ───────────────────────────────────

    @FXML void onOpciones()   { abrirAdmin("/fxml/configuracion_opciones.fxml", "Opciones habilitadas", 540, 520); }
    /** Datos del negocio, IVA y plazo de cancelación */
    @FXML void onImpuestos()  { abrirAdmin("/fxml/configuracion_negocio.fxml", "Configuración del negocio", 560, 460); }
    @FXML void onTicket()     { abrirAdmin("/fxml/configuracion_ticket.fxml", "Ticket", 860, 640); }
    @FXML void onLogotipo()   { abrirAdmin("/fxml/configuracion_logotipo.fxml", "Logotipo", 440, 440); }
    @FXML void onUnidades()   { abrirAdmin("/fxml/configuracion_unidades.fxml", "Unidades de medida", 780, 500); }
    @FXML void onBaseDatos()  { abrirAdmin("/fxml/configuracion_basedatos.fxml", "Base de datos", 560, 640); }
    /** Coordenadas del negocio y clima (Estadísticas y sugerencias) */
    /** Servidor web local para teléfonos y sus sesiones */
    @FXML void onAccesoMovil() { abrirAdmin("/fxml/configuracion_movil.fxml", "Acceso móvil", 820, 680); }
    @FXML void onClima()      { abrirAdmin("/fxml/configuracion_clima.fxml", "Ubicación del negocio y clima", 560, 620); }

    private void abrirAdmin(String fxml, String titulo, double ancho, double alto) {
        if (!sesionService.esAdmin()) {
            alerta(Alert.AlertType.ERROR, "Acceso denegado",
                    "Solo un administrador puede cambiar la configuración del negocio.");
            return;
        }
        abrir(fxml, titulo, ancho, alto);
    }

    // ── Diálogos ──────────────────────────────────────────────────────────

    @FXML void onFormasPago() {
        if (!soloAdmin()) return;
        Configuracion c = configuracionService.obtener();
        CheckBox efectivo = new CheckBox("Efectivo");
        CheckBox tarjeta = new CheckBox("Tarjeta");
        CheckBox transferencia = new CheckBox("Transferencia");
        CheckBox vales = new CheckBox("Vales");
        efectivo.setSelected(c.isPagoEfectivo());
        tarjeta.setSelected(c.isPagoTarjeta());
        transferencia.setSelected(c.isPagoTransferencia());
        vales.setSelected(c.isPagoVales());
        Label nota = new Label("Debe quedar al menos una forma de pago. \"Crédito\" no está disponible hasta contar con el módulo de clientes.");
        nota.setWrapText(true);
        nota.getStyleClass().add("hint");
        VBox contenido = new VBox(10, efectivo, tarjeta, transferencia, vales, nota);
        dialogoGuardar("Formas de pago", "Formas de pago que acepta la caja", contenido, () ->
                configuracionService.guardarFormasPago(efectivo.isSelected(), tarjeta.isSelected(),
                        transferencia.isSelected(), vales.isSelected()));
    }

    @FXML void onMoneda() {
        if (!soloAdmin()) return;
        Configuracion c = configuracionService.obtener();
        TextField simbolo = new TextField(c.getMonedaSimbolo());
        TextField codigo = new TextField(c.getMonedaCodigo());
        GridPane grid = rejilla();
        grid.addRow(0, new Label("Símbolo:"), simbolo);
        grid.addRow(1, new Label("Código (3 letras):"), codigo);
        Label nota = new Label("Solo cambia cómo se muestran los importes; no convierte precios.");
        nota.getStyleClass().add("hint");
        dialogoGuardar("Moneda", "Símbolo y código de la moneda", new VBox(10, grid, nota), () ->
                configuracionService.guardarMoneda(simbolo.getText(), codigo.getText()));
    }

    @FXML void onFolios() {
        if (!soloAdmin()) return;
        Configuracion c = configuracionService.obtener();
        TextField prefijo = new TextField(c.getFolioPrefijo());
        prefijo.setPromptText("ej: A-");
        TextField siguiente = new TextField(String.valueOf(configuracionService.siguienteNumeroFolio()));
        GridPane grid = rejilla();
        grid.addRow(0, new Label("Siguiente folio:"), new Label(configuracionService.siguienteFolio()));
        grid.addRow(1, new Label("Prefijo (opcional):"), prefijo);
        grid.addRow(2, new Label("Siguiente número:"), siguiente);
        Label nota = new Label("El prefijo y el número aplican a las ventas nuevas. Las ventas ya hechas conservan su folio; "
                + "el número no puede bajar del actual para no repetir folios.");
        nota.setWrapText(true);
        nota.setPrefWidth(360);
        nota.getStyleClass().add("hint");
        dialogoGuardar("Modificar folios", "Folio de las ventas", new VBox(10, grid, nota), () -> {
            long numero;
            try {
                numero = Long.parseLong(siguiente.getText().trim());
            } catch (NumberFormatException e) {
                throw new NegocioException("El siguiente número debe ser un entero.");
            }
            configuracionService.guardarFolios(prefijo.getText(), numero);
        });
    }

    @FXML void onRespaldo() {
        if (!soloAdmin()) return;
        Configuracion c = configuracionService.obtener();
        CheckBox activo = new CheckBox("Respaldar automáticamente");
        activo.setSelected(c.isRespaldoAuto());
        TextField carpeta = new TextField(c.getRespaldoCarpeta() != null ? c.getRespaldoCarpeta()
                : rutas.carpetaRespaldosSugerida().toString());
        carpeta.setPromptText("Carpeta de destino");
        HBox.setHgrow(carpeta, Priority.ALWAYS);
        Button elegir = new Button("Elegir...");
        elegir.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Carpeta de respaldos");
            File inicial = new File(carpeta.getText().isBlank() ? rutas.carpetaRespaldosSugerida().toString() : carpeta.getText());
            if (inicial.isDirectory()) dc.setInitialDirectory(inicial);
            File dir = dc.showDialog(raiz.getScene().getWindow());
            if (dir != null) carpeta.setText(dir.getAbsolutePath());
        });
        Spinner<Integer> copias = new Spinner<>(1, 60, c.getRespaldoCopias());
        copias.setEditable(true);
        Label ultimo = new Label(c.getRespaldoUltimo() != null
                ? "Último respaldo automático: " + c.getRespaldoUltimo() : "Aún no se ha hecho un respaldo automático.");
        ultimo.getStyleClass().add("hint");
        Label nota = new Label("Se guarda un archivo por día (respaldo-auto-AAAAMMDD.db) en el primer inicio de sesión del día "
                + "y al cerrar el programa. Se conservan solo las copias más recientes.");
        nota.setWrapText(true);
        nota.setPrefWidth(380);
        nota.getStyleClass().add("hint");
        GridPane grid = rejilla();
        grid.addRow(0, new Label("Carpeta:"), new HBox(6, carpeta, elegir));
        grid.addRow(1, new Label("Copias a conservar:"), copias);
        dialogoGuardar("Respaldo automático", "Copias de seguridad automáticas",
                new VBox(10, activo, grid, nota, ultimo), () -> {
                    copias.commitValue();   // toma lo escrito a mano en el campo del Spinner
                    configuracionService.guardarRespaldoAutomatico(activo.isSelected(), carpeta.getText(), copias.getValue());
                });
    }

    @FXML void onLicencia() {
        Label abierto = new Label("Este Punto de Venta es software de código abierto, publicado bajo la licencia GNU GPL v3.");
        abierto.setWrapText(true);
        abierto.getStyleClass().addAll("callout", "callout-info");
        TextArea texto = new TextArea(leerLicencia());
        texto.setEditable(false);
        texto.setWrapText(true);
        texto.setPrefSize(620, 360);
        Dialog<ButtonType> dlg = dialogo("Licencia", "Licencia del proyecto", new VBox(10, abierto, texto));
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.showAndWait();
    }

    @FXML void onActualizaciones() {
        Label actual = new Label("Versión instalada: " + version);
        Hyperlink enlace = new Hyperlink(REPOSITORIO);
        enlace.setOnAction(e -> PuntoDeVentaApp.abrirEnlace(REPOSITORIO));
        Label nota = new Label("El programa no se actualiza solo: consulta el repositorio para ver las versiones nuevas y descargarlas.");
        nota.setWrapText(true);
        nota.setPrefWidth(380);
        nota.getStyleClass().add("hint");
        Dialog<ButtonType> dlg = dialogo("Actualizaciones", "Versión del programa",
                new VBox(10, actual, new Label("Repositorio en GitHub:"), enlace, nota));
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.showAndWait();
    }

    @FXML void onArticulosPrec() {
        if (!soloAdmin()) return;
        if (!articulosPrecargadosService.puedeCargar()) {
            alerta(Alert.AlertType.INFORMATION, "Artículos precargados",
                    "Ya hay productos en el catálogo. Los artículos de ejemplo solo se ofrecen con el catálogo vacío.");
            return;
        }
        if (!Ventanas.confirmar("Artículos precargados",
                "Se cargarán unos " + articulosPrecargadosService.cantidadDeMuestra()
                + " productos de abarrotes de ejemplo (con existencia inicial) en varios departamentos. "
                + "Puedes editarlos o eliminarlos después.\n\n¿Cargarlos?")) {
            return;
        }
        try {
            int n = articulosPrecargadosService.cargar();
            alerta(Alert.AlertType.INFORMATION, "Artículos precargados",
                    "Se cargaron " + n + " productos. Revísalos en la pestaña Productos.");
        } catch (NegocioException e) {
            alerta(Alert.AlertType.ERROR, "No se pudo cargar", e.getMessage());
        }
    }

    // ── Etapa posterior ───────────────────────────────────────────────────

    @FXML void onFacturacion()    { proximamente("Facturación"); }

    // ── Dispositivos (solo ADMIN; cada servicio lo vuelve a verificar y audita) ──

    @FXML void onImpresora() { abrirAdmin("/fxml/configuracion_impresora.fxml", "Impresora de tickets", 600, 760); }
    @FXML void onBascula()   { abrirAdmin("/fxml/configuracion_bascula.fxml", "Báscula", 600, 780); }
    @FXML void onLector()    { if (soloAdmin()) dispositivosDialogos.lector(raiz.getScene().getWindow()); }
    @FXML void onCajon()     { if (soloAdmin()) dispositivosDialogos.cajon(raiz.getScene().getWindow()); }
    @FXML void onTerminalBancaria() { if (soloAdmin()) dispositivosDialogos.terminalBancaria(raiz.getScene().getWindow()); }

    private void proximamente(String opcion) {
        alerta(Alert.AlertType.INFORMATION, opcion,
                "\"" + opcion + "\" está planeada para una etapa posterior del proyecto.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean soloAdmin() {
        if (sesionService.esAdmin()) return true;
        alerta(Alert.AlertType.ERROR, "Acceso denegado",
                "Solo un administrador puede cambiar la configuración del negocio.");
        return false;
    }

    /** Diálogo con Guardar / Cancelar; si el servicio rechaza los datos, el error sale en el mismo diálogo */
    private void dialogoGuardar(String titulo, String encabezado, Node contenido, Runnable guardar) {
        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("field-error-label");
        error.setVisible(false);
        error.setManaged(false);
        Dialog<ButtonType> dlg = dialogo(titulo, encabezado, new VBox(10, contenido, error));
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
                ev.consume();   // el diálogo sigue abierto para corregir
            }
        });
        dlg.showAndWait();
    }

    private Dialog<ButtonType> dialogo(String titulo, String encabezado, Node contenido) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(titulo);
        dlg.setHeaderText(encabezado);
        if (raiz.getScene() != null) dlg.initOwner(raiz.getScene().getWindow());
        VBox caja = new VBox(contenido);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        Ventanas.estilizar(dlg);
        return dlg;
    }

    private static GridPane rejilla() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        return grid;
    }

    /** Texto de LICENSE (copia incluida en los recursos del programa) */
    private static String leerLicencia() {
        try (InputStream in = ConfiguracionController.class.getResourceAsStream("/LICENSE.txt")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignorada) { /* se muestra el aviso de abajo */ }
        return "GNU General Public License v3.\nTexto completo: https://www.gnu.org/licenses/gpl-3.0.html";
    }

    private void abrir(String fxml, String titulo, double ancho, double alto) {
        try {
            Ventanas.abrirModal(springContext, fxml, titulo, raiz.getScene().getWindow(), ancho, alto);
        } catch (Exception e) {
            e.printStackTrace();
            alerta(Alert.AlertType.ERROR, "Error", "No se pudo abrir la ventana: " + e.getMessage());
        }
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Ventanas.alerta(tipo, titulo, msg);
    }
}
