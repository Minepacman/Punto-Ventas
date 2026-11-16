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
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
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
import me.eliassanchezfernandez.puntodeventa.service.CatalogoCodigosService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
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
 * armados aquí. Los dispositivos (impresora, báscula, lector, cajón y terminal bancaria) y Facturación son solo ADMIN.
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
    @Autowired private AcercaDeDialogo acercaDeDialogo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.service.ActualizacionService actualizacionService;
    @Autowired private CatalogoCodigosService catalogoCodigosService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.ocr.LectorTicketsService lectorTicketsService;

    @Override public void initialize(URL url, ResourceBundle rb) { }

    /** Administración de usuarios (ADMIN completo; GERENTE solo consulta y desactiva) */
    @FXML void onCajeros() {
        if (!sesionService.esAdmin() && !sesionService.esGerente()) {
            alerta(Alert.AlertType.ERROR, "Acceso denegado",
                    "Solo un administrador o gerente puede administrar los usuarios.");
            return;
        }
        abrir("/fxml/usuarios.fxml", "Usuarios del sistema", 1000, 690);
    }

    // ── Pantallas con FXML (solo ADMIN) ───────────────────────────────────

    /** Bitácora de acciones sensibles: aperturas del cajón, efectivo, cancelaciones, usuarios... */
    @FXML void onBitacora()   { abrirAdmin("/fxml/bitacora.fxml", "Bitácora", 1100, 640); }
    @FXML void onOpciones()   { abrirAdmin("/fxml/configuracion_opciones.fxml", "Opciones habilitadas", 540, 520); }
    /** Datos del negocio, IVA y plazo de cancelación */
    @FXML void onImpuestos()  { abrirAdmin("/fxml/configuracion_negocio.fxml", "Configuración del negocio", 640, 660); }
    @FXML void onTicket()     { abrirAdmin("/fxml/configuracion_ticket.fxml", "Ticket", 860, 640); }
    @FXML void onLogotipo()   { abrirAdmin("/fxml/configuracion_logotipo.fxml", "Logotipo", 440, 440); }
    @FXML void onUnidades()   { abrirAdmin("/fxml/configuracion_unidades.fxml", "Unidades de medida", 780, 530); }
    @FXML void onBaseDatos()  { abrirAdmin("/fxml/configuracion_basedatos.fxml", "Base de datos", 940, 669); }
    /** Servidor web local para teléfonos y sus sesiones (QR primero, interruptor, teléfonos, avanzadas) */
    @FXML void onAccesoMovil() { abrirAdmin("/fxml/configuracion_movil.fxml", "Acceso móvil", 960, 720); }
    /** Ubicación del negocio (detectar / buscar ciudad) y clima (Estadísticas y sugerencias) */
    @FXML void onClima()      { abrirAdmin("/fxml/configuracion_clima.fxml", "Ubicación del negocio y clima", 640, 620); }

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
        Label nota = new Label("Viene encendido de fábrica. Se guarda un archivo por día (respaldo-auto-AAAAMMDD.db) en el "
                + "primer inicio de sesión del día y al cerrar el programa. Se conservan solo las copias más recientes.");
        nota.setWrapText(true);
        nota.setPrefWidth(380);
        nota.getStyleClass().add("hint");
        // Consejo: otro disco. Un respaldo en el mismo disco salva de borrados y archivos dañados, no de un disco muerto.
        Label consejo = new Label("Consejo: elige una carpeta en una memoria USB u otro disco. Si los respaldos están en el "
                + "mismo disco que la base de datos, una falla del disco se lleva los dos.");
        consejo.setWrapText(true);
        HBox cajaConsejo = new HBox(consejo);
        cajaConsejo.getStyleClass().addAll("callout", "callout-info");
        cajaConsejo.setMaxWidth(400);
        Label mismoDisco = new Label("Esta carpeta está en el mismo disco que la base de datos.");
        mismoDisco.setWrapText(true);
        HBox cajaMismoDisco = new HBox(mismoDisco);
        cajaMismoDisco.getStyleClass().addAll("callout", "callout-warning");
        cajaMismoDisco.setMaxWidth(400);
        Runnable revisarDisco = () -> {
            boolean mismo = false;
            try {
                mismo = !carpeta.getText().isBlank() && rutas.enElMismoDiscoQueLaBd(java.nio.file.Path.of(carpeta.getText().trim()));
            } catch (RuntimeException rutaInvalida) { /* se avisará al guardar */ }
            cajaMismoDisco.setVisible(mismo);
            cajaMismoDisco.setManaged(mismo);
        };
        carpeta.textProperty().addListener((o, a, b) -> revisarDisco.run());
        revisarDisco.run();
        GridPane grid = rejilla();
        grid.addRow(0, new Label("Carpeta:"), new HBox(6, carpeta, elegir));
        grid.addRow(1, new Label("Copias a conservar:"), copias);
        dialogoGuardar("Respaldo automático", "Copias de seguridad automáticas",
                new VBox(10, activo, grid, cajaConsejo, cajaMismoDisco, nota, ultimo), () -> {
                    Ventanas.confirmarSpinner(copias, "Copias a conservar");   // lo escrito a mano en el campo
                    configuracionService.guardarRespaldoAutomatico(activo.isSelected(), carpeta.getText(), copias.getValue());
                });
    }

    @FXML void onLicencia() {
        Label abierto = new Label("Caja Libre es software libre, publicado bajo la licencia GNU GPL v3. El texto completo está abajo; los avisos de terceros, la marca y los canales oficiales se ven en «Acerca de».");
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

    /** Búsqueda automática (una vez al día) y búsqueda manual; solo ADMIN cambia la opción */
    @FXML void onActualizaciones() {
        Configuracion c = configuracionService.obtener();
        Label actual = new Label("Versión instalada: " + me.eliassanchezfernandez.puntodeventa.util.VersionApp.etiqueta());
        actual.getStyleClass().add("text-bold");
        CheckBox auto = new CheckBox("Buscar versiones nuevas automáticamente (una vez al día, en segundo plano)");
        auto.setSelected(c.isActualizacionesAuto());
        auto.setWrapText(true);
        auto.setDisable(!sesionService.esAdmin());
        Label nota = new Label("Solo se consulta la lista pública de versiones en GitHub; el programa nunca descarga ni "
                + "instala nada por su cuenta. Si hay una versión nueva aparece un enlace en la barra de estado.");
        nota.setWrapText(true);
        nota.setPrefWidth(420);
        nota.getStyleClass().add("hint");
        Label resultado = new Label();
        resultado.setWrapText(true);
        resultado.setPrefWidth(420);
        Button buscar = new Button("Buscar ahora");
        Button ver = new Button("Ver y descargar");
        ver.getStyleClass().add("button-primary");
        ver.setVisible(false);
        ver.setManaged(false);
        HBox fila = new HBox(10, buscar, ver);
        buscar.setOnAction(e -> {
            buscar.setDisable(true);
            resultado.setText("Buscando en GitHub…");
            actualizacionService.buscarEnSegundoPlano().thenAccept(r -> javafx.application.Platform.runLater(() -> {
                buscar.setDisable(false);
                switch (r.estado()) {
                    case ACTUALIZADO -> resultado.setText("Tienes la versión más reciente.");
                    case DISPONIBLE -> {
                        var a = r.actualizacion().orElseThrow();
                        resultado.setText("Nueva versión disponible: v" + a.version() + (a.prelanzamiento() ? " (versión de prueba)" : ""));
                        ver.setVisible(true);
                        ver.setManaged(true);
                        ver.setOnAction(ev -> ActualizacionDialogo.mostrar(raiz.getScene().getWindow(), a));
                    }
                    case SIN_CONEXION -> resultado.setText("No se pudo consultar GitHub (¿sin internet?).");
                    case VERSION_DESCONOCIDA -> resultado.setText("Versión de desarrollo: no se compara con las publicadas.");
                }
            }));
        });
        Hyperlink enlace = new Hyperlink("Todas las versiones en GitHub");
        enlace.setOnAction(e -> PuntoDeVentaApp.abrirEnlace(REPOSITORIO + "/releases"));
        VBox contenido = new VBox(10, actual, auto, nota, fila, resultado, enlace);
        if (sesionService.esAdmin()) {
            dialogoGuardar("Actualizaciones", "Versión del programa", contenido,
                    () -> configuracionService.guardarActualizacionesAutomaticas(auto.isSelected()));
        } else {
            Dialog<ButtonType> dlg = dialogo("Actualizaciones", "Versión del programa", contenido);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dlg.showAndWait();
        }
    }

    /**
     * Catálogo de códigos de barras (codigos-barras-mx / Open Food Facts): interruptor de la
     * consulta en internet, estado de la copia local y "Actualizar ahora". Solo ADMIN cambia la opción.
     */
    @FXML void onCatalogoCodigos() {
        CatalogoCodigosService.Estado e = catalogoCodigosService.estado();
        CheckBox enLinea = new CheckBox("Buscar datos del producto por código de barras (internet)");
        enLinea.setSelected(e.enLinea());
        enLinea.setWrapText(true);
        enLinea.setDisable(!sesionService.esAdmin());
        Label nota = new Label("Al dar de alta un producto por su código de barras, el programa propone descripción, departamento "
                + "y foto a partir de un catálogo libre de productos que se venden en México (unos 25 000, ya incluido en el "
                + "programa). Encendido, una vez por semana se baja la versión más nueva y, si un código no está, se consulta en "
                + "internet (catálogo y Open Food Facts); la foto también se baja de Open Food Facts. Apagado, solo se usa el "
                + "catálogo incluido y no se baja ninguna foto. Nunca se envía nada de la tienda: solo se pide el código.");
        nota.setWrapText(true);
        nota.setPrefWidth(460);
        nota.getStyleClass().add("hint");
        Label estado = new Label(describirEstadoCatalogo(e));
        estado.setWrapText(true);
        estado.setPrefWidth(460);
        Button actualizar = new Button("Actualizar ahora");
        actualizar.setDisable(!sesionService.esAdmin());
        Label resultado = new Label();
        resultado.setWrapText(true);
        resultado.setPrefWidth(460);
        actualizar.setOnAction(ev -> {
            actualizar.setDisable(true);
            resultado.setText("Descargando el catálogo… (puede tardar un minuto en una conexión lenta)");
            try {
                catalogoCodigosService.actualizarAhoraEnSegundoPlano().thenAccept(r -> javafx.application.Platform.runLater(() -> {
                    actualizar.setDisable(false);
                    resultado.setText(switch (r.estado()) {
                        case ACTUALIZADO -> "Catálogo actualizado: versión " + r.version() + ", "
                                + String.format("%,d", r.productos()) + " productos.";
                        case YA_AL_DIA -> "La copia local ya es la versión más reciente (" + r.version() + ").";
                        case SIN_CONEXION -> "No se pudo descargar el catálogo (¿sin internet?). " + r.detalle();
                        case RECHAZADO -> "El catálogo descargado no se aceptó: " + r.detalle();
                    });
                    estado.setText(describirEstadoCatalogo(catalogoCodigosService.estado()));
                }));
            } catch (NegocioException ex) {
                actualizar.setDisable(false);
                resultado.setText(ex.getMessage());
            }
        });
        Label atribucion = new Label(CatalogoCodigosService.ATRIBUCION + ". Catálogo: " + CatalogoCodigosService.REPOSITORIO);
        atribucion.setWrapText(true);
        atribucion.setPrefWidth(460);
        atribucion.getStyleClass().add("hint");
        Hyperlink enlace = new Hyperlink("Ver el catálogo en GitHub");
        enlace.setOnAction(ev -> PuntoDeVentaApp.abrirEnlace(CatalogoCodigosService.REPOSITORIO));
        VBox contenido = new VBox(10, enLinea, nota, estado, new HBox(10, actualizar), resultado, atribucion, enlace);
        if (sesionService.esAdmin()) {
            dialogoGuardar("Códigos de barras", "Catálogo de códigos de barras", contenido,
                    () -> configuracionService.guardarCodigosEnLinea(enLinea.isSelected()));
        } else {
            Dialog<ButtonType> dlg = dialogo("Códigos de barras", "Catálogo de códigos de barras", contenido);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dlg.showAndWait();
        }
    }

    private static String describirEstadoCatalogo(CatalogoCodigosService.Estado e) {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        if (!e.hayCatalogo()) {
            return "Copia local: todavía no se ha descargado el catálogo."
                    + (e.revisado() != null ? " Última revisión: " + e.revisado().format(f) + "." : "");
        }
        return "Copia local: versión " + e.version() + " · " + String.format("%,d", e.filasLocales()) + " productos"
                + (e.descargado() != null ? " · descargada el " + e.descargado().format(f) : "")
                + (e.revisado() != null ? " · última revisión " + e.revisado().format(f) : "") + ".";
    }

    /** Estado de donador, clave y soporte técnico: lo mismo que "Acerca de" en la barra de estado */
    @FXML void onDonador() {
        acercaDeDialogo.mostrar(raiz.getScene().getWindow());
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

    /** Emisor, credenciales del PAC y claves SAT por defecto (solo ADMIN) */
    @FXML void onFacturacion()    { abrirAdmin("/fxml/configuracion_facturacion.fxml", "Facturación electrónica (CFDI 4.0)", 960, 720); }

    // ── Dispositivos (solo ADMIN; cada servicio lo vuelve a verificar y audita) ──

    @FXML void onImpresora() { abrirAdmin("/fxml/configuracion_impresora.fxml", "Impresora de tickets", 600, 760); }
    @FXML void onBascula()   { abrirAdmin("/fxml/configuracion_bascula.fxml", "Báscula", 600, 620); }
    /**
     * Abrir Caja Libre al encender la computadora (por computadora; ver util/InicioAutomatico).
     * Viene encendido de fábrica en el programa instalado. reg.exe en Windows: fuera del hilo de JavaFX.
     */
    @FXML void onAbrirAlEncender() {
        if (!soloAdmin()) return;
        var lanzador = me.eliassanchezfernandez.puntodeventa.util.InicioAutomatico.lanzador();
        var carpeta = me.eliassanchezfernandez.puntodeventa.util.RutasApp.carpetaAplicacion();
        CheckBox activo = new CheckBox("Abrir " + me.eliassanchezfernandez.puntodeventa.util.Marca.NOMBRE
                + " al encender esta computadora");
        activo.setSelected(me.eliassanchezfernandez.puntodeventa.util.InicioAutomatico.decidido(carpeta).orElse(true));
        activo.setDisable(lanzador.isEmpty());
        Label nota = new Label(lanzador.isEmpty()
                ? "Solo disponible en el programa instalado (no al correrlo desde el código)."
                : "Al prender la computadora e iniciar sesión, el programa se abre solo y queda listo para cobrar. "
                + "Viene encendido de fábrica; se decide en cada computadora.");
        nota.setWrapText(true);
        nota.setPrefWidth(420);
        nota.getStyleClass().add("hint");
        dialogoGuardar("Abrir al encender", "Iniciar con la computadora", new VBox(10, activo, nota), () -> {
            if (lanzador.isEmpty()) return;
            boolean encendido = activo.isSelected();
            me.eliassanchezfernandez.puntodeventa.util.InicioAutomatico.guardarDecision(carpeta, encendido);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                String error = me.eliassanchezfernandez.puntodeventa.util.InicioAutomatico.aplicar(encendido, lanzador.get(),
                        me.eliassanchezfernandez.puntodeventa.util.InicioAutomatico.Entorno.actual());
                if (error != null) javafx.application.Platform.runLater(() -> alerta(Alert.AlertType.WARNING, "Abrir al encender", error));
            });
        });
    }

    /**
     * Lector de tickets de proveedor (OCR): Automático / Rápido / Preciso, con una línea que dice qué
     * motor se usa en esta computadora y por qué. Es por computadora (ocr.properties), no de la BD.
     * Buscar Tesseract corre procesos: se hace fuera del hilo de JavaFX.
     */
    @FXML void onLectorTickets() {
        if (!soloAdmin()) return;
        var preferencias = me.eliassanchezfernandez.puntodeventa.ocr.SeleccionMotorOcr.Preferencia.values();
        ToggleGroup grupo = new ToggleGroup();
        VBox opciones = new VBox(8);
        String[] ayudas = {
                "Elige solo según la computadora: memoria, procesador y una prueba rápida.",
                "Tesseract: funciona en cualquier computadora, incluso de 32 bits o con poca memoria.",
                "PaddleOCR: lee mejor las fotos difíciles, pero pide más memoria y una computadora de 64 bits."};
        for (int i = 0; i < preferencias.length; i++) {
            RadioButton rb = new RadioButton(preferencias[i].etiqueta());
            rb.setUserData(preferencias[i]);
            rb.setToggleGroup(grupo);
            Label ayuda = new Label(ayudas[i]);
            ayuda.getStyleClass().add("hint");
            ayuda.setWrapText(true);
            ayuda.setPadding(new Insets(0, 0, 0, 24));
            opciones.getChildren().add(new VBox(2, rb, ayuda));
        }
        var actual = lectorTicketsService.preferencia();
        grupo.getToggles().stream().filter(t -> t.getUserData() == actual).findFirst().ifPresent(grupo::selectToggle);
        Label motivo = new Label("Revisando esta computadora…");
        motivo.setWrapText(true);
        motivo.setPrefWidth(460);
        motivo.getStyleClass().add("subtitle");
        Label nota = new Label("Sirve para capturar la mercancía con una foto del ticket del proveedor desde el celular. "
                + "La foto se lee en esta computadora y no se envía a nadie. PaddleOCR se descarga una sola vez "
                + "(unos 80 MB) solo si esta computadora puede usarlo.");
        nota.setWrapText(true);
        nota.setPrefWidth(460);
        nota.getStyleClass().add("hint");
        java.util.Map<Object, String> motivos = new java.util.concurrent.ConcurrentHashMap<>();
        Runnable mostrar = () -> {
            Toggle t = grupo.getSelectedToggle();
            String m = t == null ? null : motivos.get(t.getUserData());
            motivo.setText(m == null ? "Revisando esta computadora…" : m);
        };
        grupo.selectedToggleProperty().addListener((o, a, b) -> mostrar.run());
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            for (var p : preferencias) {
                var d = lectorTicketsService.decision(p);
                String estado = lectorTicketsService.estadoPreparacion();
                motivos.put(p, "Se usará: " + d.motor().etiqueta() + ". " + d.motivo() + (estado.isBlank() ? "" : "\n" + estado));
            }
            javafx.application.Platform.runLater(mostrar);
        });
        VBox contenido = new VBox(12, opciones, motivo, nota);
        dialogoGuardar("Lector de tickets", "Cómo se leen las fotos de tickets de proveedor", contenido, () -> {
            Toggle t = grupo.getSelectedToggle();
            if (t != null) lectorTicketsService.guardarPreferencia(
                    (me.eliassanchezfernandez.puntodeventa.ocr.SeleccionMotorOcr.Preferencia) t.getUserData());
        });
    }

    @FXML void onLector()    { if (soloAdmin()) dispositivosDialogos.lector(raiz.getScene().getWindow()); }
    @FXML void onCajon()     { if (soloAdmin()) dispositivosDialogos.cajon(raiz.getScene().getWindow()); }
    @FXML void onTerminalBancaria() { if (soloAdmin()) dispositivosDialogos.terminalBancaria(raiz.getScene().getWindow()); }

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
