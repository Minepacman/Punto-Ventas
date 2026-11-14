package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.importacion.Campo;
import me.eliassanchezfernandez.puntodeventa.importacion.CampoProducto;
import me.eliassanchezfernandez.puntodeventa.importacion.CampoProveedor;
import me.eliassanchezfernandez.puntodeventa.importacion.ImportacionService;
import me.eliassanchezfernandez.puntodeventa.importacion.Mapeo;
import me.eliassanchezfernandez.puntodeventa.importacion.OpcionesImportacion;
import me.eliassanchezfernandez.puntodeventa.importacion.Origen;
import me.eliassanchezfernandez.puntodeventa.importacion.PlanImportacion;
import me.eliassanchezfernandez.puntodeventa.importacion.PlanImportacion.ErrorFila;
import me.eliassanchezfernandez.puntodeventa.importacion.PlantillaImportacion;
import me.eliassanchezfernandez.puntodeventa.importacion.ReporteImportacion;
import me.eliassanchezfernandez.puntodeventa.importacion.ResumenImportacion;
import me.eliassanchezfernandez.puntodeventa.importacion.TablaImportada;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Icono;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Asistente "Importar productos" (Productos → Importar): migrar el catálogo
 * desde eleventa, SICAR, Abarrotes PDV, MyBusiness, Loyverse o cualquier Excel/CSV.
 *
 * Cuatro pasos en una ventana modal construida en código (sin FXML):
 *  1. Archivo: elegir (o arrastrar), se lee en segundo plano y se reconoce el origen.
 *  2. Columnas: mapeo propuesto, editable, con un ejemplo de cada columna.
 *  3. Opciones y validación: duplicados, departamentos, existencias; reporte de errores.
 *  4. Importación: barra de progreso, resumen, reporte copiable / guardable.
 * Todo lo pesado (leer, validar, escribir) corre en un hilo aparte; la ventana
 * solo recibe avisos de progreso. Los permisos los exige el servicio; aquí solo
 * se pide la autorización de administrador cuando el usuario no los tiene.
 */
public final class ImportarDialogo {

    private static final String NO_IMPORTAR = "(No importar)";
    private static final int ANCHO = 900, ALTO = 640;

    private final ImportacionService servicio;
    private final SesionService sesion;
    private final AutorizacionService autorizaciones;
    private final Stage stage;

    private final Label lblTitulo = new Label();
    private final Label lblPaso = new Label();
    private final StackPane contenido = new StackPane();
    private final Button btnAtras = new Button("Atrás");
    private final Button btnSiguiente = new Button("Siguiente");
    private final Button btnCancelar = new Button("Cancelar");

    // Paso 1
    private final ComboBox<String> comboTipo = new ComboBox<>(FXCollections.observableArrayList("Productos", "Proveedores"));
    private final Label lblArchivo = new Label("Ningún archivo elegido.");
    private final Label lblLectura = new Label();
    private final ProgressIndicator indicadorLectura = new ProgressIndicator();
    private final CheckBox chkEncabezado = new CheckBox("La primera fila son los nombres de las columnas");
    // Paso 2
    private final ComboBox<Origen> comboOrigen = new ComboBox<>(FXCollections.observableArrayList(Origen.values()));
    private final Label lblNotaOrigen = new Label();
    private final GridPane gridMapeo = new GridPane();
    private final Label lblFaltan = new Label();
    private final List<ComboBox<Object>> combosMapeo = new ArrayList<>();
    // Paso 3
    private final ToggleGroup grupoDuplicados = new ToggleGroup();
    private final CheckBox chkDepartamentos = new CheckBox("Crear los departamentos que no existan");
    private final CheckBox chkExistencias = new CheckBox("Importar existencias (queda como movimiento de inventario)");
    private final Label lblPermisos = new Label();
    private final Label lblResumenPlan = new Label();
    private final TableView<ErrorFila> tablaErrores = new TableView<>();
    private final Button btnValidar = new Button("Validar archivo");
    private final Button btnCopiarPlan = new Button("Copiar reporte");
    private final Button btnGuardarPlan = new Button("Guardar reporte...");
    private final ProgressIndicator indicadorValidacion = new ProgressIndicator();
    private final VBox opcionesProductos = new VBox(8);
    // Paso 4
    private final ProgressBar barra = new ProgressBar(0);
    private final Label lblProgreso = new Label();
    private final Label lblResultado = new Label();
    private final TableView<ErrorFila> tablaErroresFinal = new TableView<>();
    private final Button btnCopiarResultado = new Button("Copiar reporte");
    private final Button btnGuardarResultado = new Button("Guardar reporte...");
    private final HBox accionesResultado;

    private int paso;
    private Path archivo;
    private TablaImportada tabla;
    private Origen origen = Origen.GENERICO;
    private Mapeo mapeo = new Mapeo();
    private PlanImportacion plan;
    private ImportacionService.PlanProveedores planProveedores;
    private ResumenImportacion resumen;
    private boolean ocupado;
    private boolean huboCambios;

    /** @return true si se importó algo (la pantalla debe recargar) */
    public static boolean mostrar(ApplicationContext ctx, Window owner) {
        ImportarDialogo d = new ImportarDialogo(ctx.getBean(ImportacionService.class), ctx.getBean(SesionService.class),
                ctx.getBean(AutorizacionService.class), owner);
        d.stage.showAndWait();
        return d.huboCambios;
    }

    private ImportarDialogo(ImportacionService servicio, SesionService sesion, AutorizacionService autorizaciones, Window owner) {
        this.servicio = servicio;
        this.sesion = sesion;
        this.autorizaciones = autorizaciones;

        stage = new Stage();
        stage.setTitle("Importar desde otro punto de venta");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        Ventanas.aplicarIcono(stage);
        stage.setOnCloseRequest(ev -> { if (ocupado) ev.consume(); });

        lblTitulo.getStyleClass().add("section-title");
        lblPaso.getStyleClass().add("text-muted");
        VBox cabecera = new VBox(2, lblTitulo, lblPaso);
        cabecera.setPadding(new Insets(14, 16, 8, 16));

        accionesResultado = new HBox(8, btnCopiarResultado, btnGuardarResultado);
        contenido.getChildren().addAll(construirPaso1(), construirPaso2(), construirPaso3(), construirPaso4());
        contenido.setPadding(new Insets(0, 16, 0, 16));

        btnSiguiente.getStyleClass().add("button-primary");
        btnSiguiente.setDefaultButton(true);
        btnAtras.setOnAction(e -> irA(paso - 1));
        btnSiguiente.setOnAction(e -> siguiente());
        btnCancelar.setOnAction(e -> { if (!ocupado) stage.close(); });
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox pie = new HBox(8, btnCancelar, espacio, btnAtras, btnSiguiente);
        pie.setPadding(new Insets(10, 16, 14, 16));
        pie.setAlignment(Pos.CENTER_RIGHT);

        BorderPane raiz = new BorderPane(contenido, cabecera, null, pie, null);
        raiz.getStyleClass().add("page");
        Scene escena = new Scene(raiz, ANCHO, ALTO);
        Ventanas.aplicarCss(escena);
        escena.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == javafx.scene.input.KeyCode.ESCAPE && !ocupado) { stage.close(); ev.consume(); }
        });
        stage.setScene(escena);
        stage.setMinWidth(760);
        stage.setMinHeight(520);
        irA(0);
    }

    // ── Paso 1: archivo ──────────────────────────────────────────────────

    private VBox construirPaso1() {
        comboTipo.getSelectionModel().selectFirst();
        comboTipo.setOnAction(e -> { tabla = null; plan = null; planProveedores = null; actualizarLectura(); });
        Label ayuda = new Label("Exporta el catálogo desde tu programa actual (eleventa: Productos → Exportar; "
                + "Abarrotes PDV: Inventario → Reporte de Inventario → Exportar; SICAR, MyBusiness POS, Loyverse, "
                + "Square o cualquier Excel/CSV) y elige aquí ese archivo. Se aceptan .xls, .xlsx y .csv.");
        ayuda.setWrapText(true);
        ayuda.getStyleClass().add("hint");

        Button btnElegir = new Button("Elegir archivo...");
        btnElegir.setGraphic(new Icono("carpeta"));
        btnElegir.setOnAction(e -> elegirArchivo());
        lblArchivo.setWrapText(true);
        lblArchivo.getStyleClass().add("text-bold");
        indicadorLectura.setPrefSize(22, 22);
        indicadorLectura.setVisible(false);
        indicadorLectura.setManaged(false);
        lblLectura.setWrapText(true);
        HBox filaArchivo = new HBox(10, btnElegir, lblArchivo, indicadorLectura);
        filaArchivo.setAlignment(Pos.CENTER_LEFT);

        VBox zona = new VBox(10, new Label("Arrastra aquí el archivo o pulsa \"Elegir archivo...\""), filaArchivo, lblLectura);
        zona.getStyleClass().add("imagen-zona");
        zona.setPadding(new Insets(18));
        zona.setOnDragOver(this::aceptarArrastre);
        zona.setOnDragDropped(ev -> {
            List<File> archivos = ev.getDragboard().getFiles();
            if (archivos != null && !archivos.isEmpty()) leerArchivo(archivos.get(0).toPath());
            ev.setDropCompleted(true);
            ev.consume();
        });
        chkEncabezado.setSelected(true);
        chkEncabezado.setOnAction(e -> { plan = null; planProveedores = null; });

        Label lblPlantilla = new Label("¿Vas a capturar el catálogo tú mismo en Excel? Descarga la plantilla de Caja Libre:");
        lblPlantilla.setWrapText(true);
        Button btnPlantillaXlsx = new Button("Plantilla Excel (.xlsx)");
        btnPlantillaXlsx.setGraphic(new Icono("bajar"));
        btnPlantillaXlsx.setOnAction(e -> guardarPlantilla(true));
        Button btnPlantillaCsv = new Button("Plantilla CSV");
        btnPlantillaCsv.setGraphic(new Icono("bajar"));
        btnPlantillaCsv.setOnAction(e -> guardarPlantilla(false));
        HBox plantillas = new HBox(8, btnPlantillaXlsx, btnPlantillaCsv);

        HBox tipo = new HBox(10, new Label("¿Qué vas a importar?"), comboTipo);
        tipo.setAlignment(Pos.CENTER_LEFT);
        VBox v = new VBox(12, tipo, ayuda, zona, chkEncabezado, new Separator(), lblPlantilla, plantillas);
        v.setPadding(new Insets(6, 0, 6, 0));
        return v;
    }

    private void aceptarArrastre(DragEvent ev) {
        if (ev.getDragboard().hasFiles() && !ocupado) ev.acceptTransferModes(TransferMode.COPY);
        ev.consume();
    }

    private void elegirArchivo() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Archivo exportado del otro punto de venta");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Excel o CSV (*.xls, *.xlsx, *.csv, *.txt)", "*.xls", "*.xlsx", "*.xlsm", "*.csv", "*.txt"),
                new FileChooser.ExtensionFilter("Todos los archivos", "*.*"));
        File f = fc.showOpenDialog(stage);
        if (f != null) leerArchivo(f.toPath());
    }

    private void leerArchivo(Path ruta) {
        archivo = ruta;
        tabla = null;
        plan = null;
        planProveedores = null;
        lblArchivo.setText(ruta.getFileName().toString());
        lblLectura.setText("Leyendo...");
        lblLectura.getStyleClass().removeAll("text-danger");
        ponerOcupado(true, indicadorLectura);
        enSegundoPlano(() -> servicio.leer(ruta), t -> {
            tabla = t;
            ponerOcupado(false, indicadorLectura);
            if (t.estaVacia()) {
                lblLectura.setText("El archivo no tiene filas con datos.");
                lblLectura.getStyleClass().add("text-danger");
                tabla = null;
            } else {
                origen = esProductos() ? Origen.detectar(t.encabezados(true)) : Origen.GENERICO;
                actualizarLectura();
            }
        }, error -> {
            ponerOcupado(false, indicadorLectura);
            lblLectura.setText(error);
            lblLectura.getStyleClass().add("text-danger");
        });
    }

    private void actualizarLectura() {
        if (tabla == null) {
            lblLectura.setText("");
            actualizarBotones();
            return;
        }
        int datos = tabla.datos(chkEncabezado.isSelected()).size();
        String texto = "Se leyeron " + datos + " filas y " + tabla.columnas() + " columnas (" + tabla.detalle() + ").";
        if (esProductos()) {
            texto += origen == Origen.GENERICO ? " No se reconoció el programa de origen: revisarás el mapeo de columnas."
                    : " Parece un archivo de " + origen.getNombre() + ".";
        }
        lblLectura.setText(texto);
        actualizarBotones();
    }

    private void guardarPlantilla(boolean xlsx) {
        boolean proveedores = !esProductos();
        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar plantilla de Caja Libre");
        fc.setInitialFileName((proveedores ? "plantilla-proveedores" : "plantilla-productos") + (xlsx ? ".xlsx" : ".csv"));
        fc.getExtensionFilters().add(xlsx ? new FileChooser.ExtensionFilter("Libro de Excel", "*.xlsx")
                : new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            PlantillaImportacion.escribir(f.toPath(), proveedores);
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle("Plantilla guardada");
            a.setHeaderText("Plantilla guardada en " + f.getName());
            a.setContentText("Borra las filas de ejemplo y captura tus productos. Columnas:\n\n" + PlantillaImportacion.ayuda(proveedores));
            Ventanas.estilizar(a);
            a.showAndWait();
        } catch (IOException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

    // ── Paso 2: columnas ─────────────────────────────────────────────────

    private VBox construirPaso2() {
        Label ayuda = new Label("Indica qué dato de Caja Libre va en cada columna del archivo. Las columnas marcadas "
                + "\"(No importar)\" se ignoran. Los campos con * son obligatorios.");
        ayuda.setWrapText(true);
        ayuda.getStyleClass().add("hint");
        comboOrigen.setOnAction(e -> {
            if (comboOrigen.getValue() != null && comboOrigen.getValue() != origen) {
                origen = comboOrigen.getValue();
                proponerMapeo();
            }
        });
        Button btnProponer = new Button("Volver a proponer");
        btnProponer.setOnAction(e -> proponerMapeo());
        HBox filaOrigen = new HBox(10, new Label("Programa de origen:"), comboOrigen, btnProponer);
        filaOrigen.setAlignment(Pos.CENTER_LEFT);
        lblNotaOrigen.setWrapText(true);
        lblNotaOrigen.getStyleClass().add("text-muted");

        gridMapeo.setHgap(12);
        gridMapeo.setVgap(6);
        gridMapeo.setPadding(new Insets(4, 4, 4, 4));
        ScrollPane scroll = new ScrollPane(gridMapeo);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("form-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        lblFaltan.setWrapText(true);
        lblFaltan.getStyleClass().add("field-error-label");
        VBox v = new VBox(10, ayuda, filaOrigen, lblNotaOrigen, scroll, lblFaltan);
        v.setPadding(new Insets(6, 0, 6, 0));
        return v;
    }

    private Campo[] campos() {
        return esProductos() ? CampoProducto.values() : CampoProveedor.values();
    }

    private void proponerMapeo() {
        List<String> encabezados = tabla.encabezados(chkEncabezado.isSelected());
        mapeo = esProductos() ? origen.mapeoAutomatico(encabezados, campos()) : Mapeo.automatico(encabezados, campos());
        pintarMapeo();
    }

    private void pintarMapeo() {
        comboOrigen.setValue(origen);
        comboOrigen.setDisable(!esProductos());
        lblNotaOrigen.setText(esProductos() && origen.getNota() != null ? origen.getNota() : "");
        lblNotaOrigen.setVisible(!lblNotaOrigen.getText().isEmpty());
        lblNotaOrigen.setManaged(lblNotaOrigen.isVisible());
        gridMapeo.getChildren().clear();
        combosMapeo.clear();
        List<String> encabezados = tabla.encabezados(chkEncabezado.isSelected());
        List<List<String>> datos = tabla.datos(chkEncabezado.isSelected());
        List<String> ejemplo = datos.isEmpty() ? List.of() : datos.get(0);

        Label h1 = new Label("Columna del archivo"), h2 = new Label("Ejemplo (fila 1)"), h3 = new Label("Importar como");
        for (Label h : List.of(h1, h2, h3)) h.getStyleClass().add("text-bold");
        gridMapeo.addRow(0, h1, h2, h3);
        List<Object> opciones = new ArrayList<>();
        opciones.add(NO_IMPORTAR);
        opciones.addAll(List.of(campos()));
        for (int c = 0; c < encabezados.size(); c++) {
            Label nombre = new Label(encabezados.get(c));
            nombre.setMaxWidth(260);
            Label ej = new Label(c < ejemplo.size() ? ejemplo.get(c) : "");
            ej.getStyleClass().add("text-muted");
            ej.setMaxWidth(260);
            ComboBox<Object> combo = new ComboBox<>(FXCollections.observableArrayList(opciones));
            combo.setPrefWidth(260);
            combo.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(Object o) {
                    if (o instanceof Campo campo) return campo.etiqueta() + (campo.obligatorio() ? " *" : "");
                    return o == null ? "" : o.toString();
                }
                @Override public Object fromString(String s) { return null; }
            });
            Campo asignado = mapeo.campoDeColumna(c);
            combo.setValue(asignado != null ? asignado : NO_IMPORTAR);
            final int columna = c;
            combo.setOnAction(e -> {
                Object v = combo.getValue();
                if (v instanceof Campo campo) {
                    mapeo.asignar(campo, columna);
                    // La columna que tenía ese campo antes se queda sin importar
                    for (int k = 0; k < combosMapeo.size(); k++) {
                        if (k != columna && combosMapeo.get(k).getValue() == campo) combosMapeo.get(k).setValue(NO_IMPORTAR);
                    }
                } else {
                    Campo antes = mapeo.campoDeColumna(columna);
                    if (antes != null) mapeo.asignar(antes, null);
                }
                plan = null;
                planProveedores = null;
                actualizarFaltantes();
            });
            combosMapeo.add(combo);
            gridMapeo.addRow(c + 1, nombre, ej, combo);
        }
        actualizarFaltantes();
    }

    private void actualizarFaltantes() {
        List<Campo> faltan = mapeo.obligatoriosSinMapear(campos());
        if (faltan.isEmpty()) {
            lblFaltan.setText("");
        } else {
            lblFaltan.setText("Falta indicar qué columna es: " + String.join(", ", faltan.stream().map(Campo::etiqueta).toList()) + ".");
        }
        lblFaltan.setVisible(!faltan.isEmpty());
        lblFaltan.setManaged(!faltan.isEmpty());
        actualizarBotones();
    }

    // ── Paso 3: opciones y validación ────────────────────────────────────

    private VBox construirPaso3() {
        Label titDup = new Label("Si un código ya existe en Caja Libre:");
        titDup.getStyleClass().add("text-bold");
        VBox radios = new VBox(4);
        for (OpcionesImportacion.Duplicados d : OpcionesImportacion.Duplicados.values()) {
            RadioButton rb = new RadioButton(d.getEtiqueta());
            rb.setUserData(d);
            rb.setToggleGroup(grupoDuplicados);
            rb.setOnAction(e -> invalidarPlan());
            if (d == OpcionesImportacion.Duplicados.OMITIR) rb.setSelected(true);
            radios.getChildren().add(rb);
        }
        chkDepartamentos.setSelected(true);
        chkDepartamentos.setOnAction(e -> invalidarPlan());
        chkExistencias.setSelected(true);
        chkExistencias.setOnAction(e -> invalidarPlan());
        lblPermisos.setWrapText(true);
        lblPermisos.getStyleClass().add("text-muted");
        opcionesProductos.getChildren().addAll(titDup, radios, new Separator(), chkDepartamentos, chkExistencias, lblPermisos);

        btnValidar.getStyleClass().add("button-success");
        btnValidar.setOnAction(e -> validar());
        indicadorValidacion.setPrefSize(22, 22);
        indicadorValidacion.setVisible(false);
        indicadorValidacion.setManaged(false);
        lblResumenPlan.setWrapText(true);
        HBox filaValidar = new HBox(10, btnValidar, indicadorValidacion, lblResumenPlan);
        filaValidar.setAlignment(Pos.CENTER_LEFT);

        configurarTablaErrores(tablaErrores);
        VBox.setVgrow(tablaErrores, Priority.ALWAYS);
        btnCopiarPlan.setOnAction(e -> copiar(ReporteImportacion.previo(plan)));
        btnGuardarPlan.setOnAction(e -> guardarTexto(ReporteImportacion.previo(plan), "validacion-importacion.txt"));
        HBox acciones = new HBox(8, btnCopiarPlan, btnGuardarPlan);
        acciones.setDisable(true);
        tablaErrores.itemsProperty().addListener((o, a, b) -> acciones.setDisable(plan == null && planProveedores == null));
        lblResumenPlan.textProperty().addListener((o, a, b) -> acciones.setDisable(plan == null && planProveedores == null));

        VBox v = new VBox(10, opcionesProductos, new Separator(), filaValidar, tablaErrores, acciones);
        v.setPadding(new Insets(6, 0, 6, 0));
        return v;
    }

    private void configurarTablaErrores(TableView<ErrorFila> t) {
        TableColumn<ErrorFila, Number> colFila = new TableColumn<>("Fila");
        colFila.setCellValueFactory(c -> new javafx.beans.property.SimpleIntegerProperty(c.getValue().numeroFila()));
        colFila.setPrefWidth(60);
        TableColumn<ErrorFila, String> colCodigo = new TableColumn<>("Código");
        colCodigo.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().codigo()));
        colCodigo.setPrefWidth(150);
        TableColumn<ErrorFila, String> colMensaje = new TableColumn<>("Problema (la fila no se importa)");
        colMensaje.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().mensaje()));
        colMensaje.setPrefWidth(560);
        t.getColumns().addAll(List.of(colFila, colCodigo, colMensaje));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        t.setPlaceholder(new Label("Sin errores."));
        t.setPrefHeight(200);
    }

    private OpcionesImportacion opciones() {
        OpcionesImportacion.Duplicados d = (OpcionesImportacion.Duplicados) grupoDuplicados.getSelectedToggle().getUserData();
        return new OpcionesImportacion(d, chkDepartamentos.isSelected(), chkExistencias.isSelected(), chkEncabezado.isSelected());
    }

    private void invalidarPlan() {
        plan = null;
        planProveedores = null;
        lblResumenPlan.setText("Las opciones cambiaron: vuelve a validar.");
        tablaErrores.setItems(FXCollections.observableArrayList());
        actualizarBotones();
    }

    private void prepararPaso3() {
        boolean productos = esProductos();
        opcionesProductos.setVisible(productos);
        opcionesProductos.setManaged(productos);
        chkExistencias.setDisable(!mapeo.tiene(CampoProducto.EXISTENCIA));
        if (!mapeo.tiene(CampoProducto.EXISTENCIA)) chkExistencias.setSelected(false);
        List<String> avisos = new ArrayList<>();
        if (productos) {
            if (!sesion.tienePermiso(Permiso.AJUSTAR_INVENTARIO)) {
                avisos.add("Importar existencias requiere el permiso \"" + Permiso.AJUSTAR_INVENTARIO.getDescripcion()
                        + "\": se pedirá autorización de un administrador.");
            }
            if (!sesion.tienePermiso(Permiso.CAMBIAR_PRECIOS)) {
                avisos.add("Cambiar precios de productos existentes requiere \"" + Permiso.CAMBIAR_PRECIOS.getDescripcion()
                        + "\": se pedirá autorización de un administrador.");
            }
        }
        lblPermisos.setText(String.join("\n", avisos));
        lblPermisos.setVisible(!avisos.isEmpty());
        lblPermisos.setManaged(!avisos.isEmpty());
        if (plan == null && planProveedores == null) {
            lblResumenPlan.setText("Pulsa \"Validar archivo\" para revisar las filas antes de importar.");
            tablaErrores.setItems(FXCollections.observableArrayList());
        }
    }

    private void validar() {
        OpcionesImportacion op = opciones();
        ponerOcupado(true, indicadorValidacion);
        lblResumenPlan.setText("Validando...");
        if (esProductos()) {
            enSegundoPlano(() -> servicio.preparar(tabla, origen, mapeo, op), p -> {
                plan = p;
                ponerOcupado(false, indicadorValidacion);
                lblResumenPlan.setText("Se crearán " + p.aCrear() + ", se actualizarán " + p.aActualizar() + ", se omitirán "
                        + p.aOmitir() + " (ya existen), con error " + p.errores().size()
                        + (p.conAvisos() > 0 ? ", con avisos " + p.conAvisos() : "")
                        + (p.departamentosNuevos().isEmpty() ? "" : ". Departamentos nuevos: " + String.join(", ", p.departamentosNuevos()))
                        + ".");
                tablaErrores.setItems(FXCollections.observableArrayList(p.errores()));
                actualizarBotones();
            }, error -> {
                ponerOcupado(false, indicadorValidacion);
                lblResumenPlan.setText(error);
                actualizarBotones();
            });
        } else {
            enSegundoPlano(() -> servicio.prepararProveedores(tabla, mapeo, op.primeraFilaEsEncabezado()), p -> {
                planProveedores = p;
                ponerOcupado(false, indicadorValidacion);
                lblResumenPlan.setText("Se crearán " + p.aCrear() + " proveedores, se omitirán " + p.aOmitir()
                        + " (ya existen), con error " + p.errores().size() + ".");
                tablaErrores.setItems(FXCollections.observableArrayList(p.errores()));
                actualizarBotones();
            }, error -> {
                ponerOcupado(false, indicadorValidacion);
                lblResumenPlan.setText(error);
                actualizarBotones();
            });
        }
    }

    // ── Paso 4: importar ─────────────────────────────────────────────────

    private VBox construirPaso4() {
        barra.setMaxWidth(Double.MAX_VALUE);
        lblProgreso.setWrapText(true);
        lblResultado.setWrapText(true);
        lblResultado.getStyleClass().add("text-bold");
        configurarTablaErrores(tablaErroresFinal);
        VBox.setVgrow(tablaErroresFinal, Priority.ALWAYS);
        btnCopiarResultado.setOnAction(e -> copiar(textoResultado()));
        btnGuardarResultado.setOnAction(e -> guardarTexto(textoResultado(), "reporte-importacion.txt"));
        accionesResultado.setVisible(false);
        VBox v = new VBox(10, barra, lblProgreso, lblResultado, tablaErroresFinal, accionesResultado);
        v.setPadding(new Insets(6, 0, 6, 0));
        return v;
    }

    private String textoResultado() {
        if (plan != null && resumen != null) return ReporteImportacion.resultado(plan, resumen);
        if (resumen != null) {
            StringBuilder sb = new StringBuilder("Caja Libre - importación de proveedores\n").append(resumen.resumenCorto()).append('\n');
            for (ErrorFila e : resumen.errores()) sb.append("  ").append(e).append('\n');
            return sb.toString().trim();
        }
        return "";
    }

    private void importar() {
        List<Autorizacion> aut = new ArrayList<>();
        if (esProductos()) {
            if (!pedirSiFalta(Permiso.EDITAR_PRODUCTOS, true, aut)) return;
            if (!pedirSiFalta(Permiso.CAMBIAR_PRECIOS, plan.requiereCambiarPrecios(), aut)) return;
            if (!pedirSiFalta(Permiso.AJUSTAR_INVENTARIO, plan.requiereAjustarInventario(), aut)) return;
        } else if (!pedirSiFalta(Permiso.GESTIONAR_PROVEEDORES, true, aut)) {
            return;
        }
        irA(3);
        int total = esProductos() ? plan.filasAAplicar().size() : planProveedores.filas().size();
        barra.setProgress(0);
        lblProgreso.setText("Importando 0 de " + total + "...");
        lblResultado.setText("");
        tablaErroresFinal.setItems(FXCollections.observableArrayList());
        accionesResultado.setVisible(false);
        ponerOcupado(true, null);
        Consumer<Integer> progreso = n -> Platform.runLater(() -> {
            barra.setProgress(total == 0 ? 1 : (double) n / total);
            lblProgreso.setText("Importando " + n + " de " + total + "...");
        });
        enSegundoPlano(() -> esProductos() ? servicio.aplicar(plan, aut, progreso::accept)
                        : servicio.aplicarProveedores(planProveedores, progreso::accept),
                r -> {
                    resumen = r;
                    huboCambios = r.importados() > 0 || r.departamentosCreados() > 0;
                    ponerOcupado(false, null);
                    barra.setProgress(1);
                    lblProgreso.setText("Listo en " + String.format("%.1f", r.duracionMs() / 1000.0) + " s.");
                    lblResultado.setText(r.resumenCorto());
                    tablaErroresFinal.setItems(FXCollections.observableArrayList(r.errores()));
                    accionesResultado.setVisible(true);
                    actualizarBotones();
                }, error -> {
                    ponerOcupado(false, null);
                    lblProgreso.setText("La importación se detuvo.");
                    lblResultado.setText(error);
                    huboCambios = true;   // pudo haber lotes ya escritos: la pantalla debe recargar
                    actualizarBotones();
                });
    }

    /** Si hace falta el permiso y el usuario no lo tiene, pide la autorización. false = canceló. */
    private boolean pedirSiFalta(Permiso permiso, boolean necesario, List<Autorizacion> aut) {
        if (!necesario || sesion.tienePermiso(permiso)) return true;
        Optional<Autorizacion> a = Ventanas.pedirAutorizacion(autorizaciones, permiso);
        a.ifPresent(aut::add);
        return a.isPresent();
    }

    // ── Navegación ───────────────────────────────────────────────────────

    private boolean esProductos() {
        return comboTipo.getSelectionModel().getSelectedIndex() != 1;
    }

    private void siguiente() {
        if (ocupado) return;
        switch (paso) {
            case 0 -> { if (tabla != null) { proponerMapeo(); irA(1); } }
            case 1 -> { if (mapeo.obligatoriosSinMapear(campos()).isEmpty()) { prepararPaso3(); irA(2); } }
            case 2 -> { if (hayPlan()) importar(); }
            default -> stage.close();
        }
    }

    private boolean hayPlan() {
        return esProductos() ? plan != null && plan.hayAlgoQueAplicar()
                : planProveedores != null && planProveedores.aCrear() > 0;
    }

    private void irA(int nuevo) {
        if (ocupado || nuevo < 0 || nuevo > 3) return;
        if (paso == 3 && nuevo < 3) return;   // tras importar no se vuelve atrás: se cierra y se vuelve a abrir
        paso = nuevo;
        for (int i = 0; i < contenido.getChildren().size(); i++) {
            boolean visible = i == paso;
            contenido.getChildren().get(i).setVisible(visible);
            contenido.getChildren().get(i).setManaged(visible);
        }
        String[] titulos = {"1. Elige el archivo", "2. Columnas", "3. Opciones y validación", "4. Importación"};
        lblTitulo.setText(titulos[paso]);
        lblPaso.setText("Paso " + (paso + 1) + " de 4" + (archivo != null && paso > 0 ? " — " + archivo.getFileName() : ""));
        if (paso == 1 && combosMapeo.isEmpty()) pintarMapeo();
        actualizarBotones();
    }

    private void actualizarBotones() {
        btnAtras.setDisable(ocupado || paso == 0 || paso == 3);
        btnCancelar.setDisable(ocupado);
        btnCancelar.setVisible(paso != 3);
        btnCancelar.setManaged(paso != 3);
        switch (paso) {
            case 0 -> { btnSiguiente.setText("Siguiente"); btnSiguiente.setDisable(ocupado || tabla == null); }
            case 1 -> { btnSiguiente.setText("Siguiente"); btnSiguiente.setDisable(ocupado || !mapeo.obligatoriosSinMapear(campos()).isEmpty()); }
            case 2 -> {
                long n = esProductos() ? (plan == null ? 0 : plan.aCrear() + plan.aActualizar())
                        : (planProveedores == null ? 0 : planProveedores.aCrear());
                btnSiguiente.setText(n > 0 ? "Importar " + n + (esProductos() ? " productos" : " proveedores") : "Importar");
                btnSiguiente.setDisable(ocupado || !hayPlan());
            }
            default -> { btnSiguiente.setText("Cerrar"); btnSiguiente.setDisable(ocupado); }
        }
    }

    private void ponerOcupado(boolean valor, ProgressIndicator indicador) {
        ocupado = valor;
        if (indicador != null) {
            indicador.setVisible(valor);
            indicador.setManaged(valor);
        }
        comboTipo.setDisable(valor);
        actualizarBotones();
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    private interface Trabajo<T> { T ejecutar() throws Exception; }

    /** Corre el trabajo fuera del hilo de JavaFX y entrega el resultado (o el mensaje de error) en él */
    private <T> void enSegundoPlano(Trabajo<T> trabajo, Consumer<T> alTerminar, Consumer<String> alFallar) {
        Thread t = new Thread(() -> {
            try {
                T r = trabajo.ejecutar();
                Platform.runLater(() -> alTerminar.accept(r));
            } catch (NegocioException | IOException e) {
                Platform.runLater(() -> alFallar.accept(e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> alFallar.accept("Ocurrió un error inesperado: " + e.getMessage()));
            }
        }, "importacion");
        t.setDaemon(true);
        t.start();
    }

    private void copiar(String texto) {
        ClipboardContent c = new ClipboardContent();
        c.putString(texto);
        Clipboard.getSystemClipboard().setContent(c);
    }

    private void guardarTexto(String texto, String nombre) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar reporte");
        fc.setInitialFileName(nombre);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Texto", "*.txt"));
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), texto, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
        }
    }

}
