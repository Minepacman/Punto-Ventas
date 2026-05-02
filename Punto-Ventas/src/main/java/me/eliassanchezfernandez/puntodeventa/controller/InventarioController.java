package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controlador de Inventario (F4).
 *
 * Cinco vistas en StackPane:
 *  1. vistaAgregar     → agregar stock a un producto
 *  2. vistaTabla       → tabla completa de inventario
 *  3. vistaReporte     → reporte (placeholder)
 *  4. vistaMovimientos → historial de movimientos
 *  5. vistaImportar    → importar desde CSV
 */
@Component
public class InventarioController implements Initializable {

    // ── FXML – Vistas ─────────────────────────────────────────────────────
    @FXML private BorderPane vistaAgregar;
    @FXML private BorderPane vistaTabla;
    @FXML private BorderPane vistaReporte;
    @FXML private BorderPane vistaMovimientos;
    @FXML private BorderPane vistaImportar;

    // ── FXML – Vista Agregar ──────────────────────────────────────────────
    @FXML private TextField txtCodigo;
    @FXML private Label     lblNoEncontrado;
    @FXML private VBox      panelInfoProducto;
    @FXML private Label     lblDescripcion;
    @FXML private Label     lblDepartamento;
    @FXML private Label     lblStockActual;
    @FXML private Label     lblStockMinimo;
    @FXML private TextField txtCantidad;
    @FXML private Label     lblErrorCantidad;
    @FXML private TextField txtNota;

    // ── FXML – Vista Tabla ────────────────────────────────────────────────
    @FXML private TableView<Producto>           tablaInventario;
    @FXML private TableColumn<Producto, String> colCodigo;
    @FXML private TableColumn<Producto, String> colDescripcion;
    @FXML private TableColumn<Producto, Double> colActual;
    @FXML private TableColumn<Producto, Double> colMinimo;
    @FXML private TableColumn<Producto, String> colEstado;
    @FXML private TableColumn<Producto, String> colDepartamento;
    @FXML private TextField                     txtBuscarTabla;

    // ── FXML – Vista Movimientos ──────────────────────────────────────────
    @FXML private TableView<MovimientoInventario>           tablaMovimientos;
    @FXML private TableColumn<MovimientoInventario, String> colMovFecha;
    @FXML private TableColumn<MovimientoInventario, String> colMovProducto;
    @FXML private TableColumn<MovimientoInventario, String> colMovTipo;
    @FXML private TableColumn<MovimientoInventario, Double> colMovCantidad;
    @FXML private TableColumn<MovimientoInventario, Double> colMovAnterior;
    @FXML private TableColumn<MovimientoInventario, Double> colMovNueva;
    @FXML private TableColumn<MovimientoInventario, String> colMovUsuario;
    @FXML private TableColumn<MovimientoInventario, String> colMovNota;
    @FXML private DatePicker dateDesde;
    @FXML private DatePicker dateHasta;

    // ── FXML – Vista Importar ─────────────────────────────────────────────
    @FXML private TextField    txtRutaArchivo;
    @FXML private CheckBox     chkActualizarExistentes;
    @FXML private CheckBox     chkCrearDepartamentos;
    @FXML private VBox         panelProgreso;
    @FXML private Label        lblProgreso;
    @FXML private ProgressBar  progressBar;
    @FXML private VBox         panelResultado;
    @FXML private TextArea     txtLog;
    @FXML private Button       btnImportar;

    // ── Spring ────────────────────────────────────────────────────────────
    @Autowired private ProductoService                  productoService;
    @Autowired private DepartamentoRepository           departamentoRepo;
    @Autowired private MovimientoInventarioRepository   movimientoRepo;
    @Autowired private SesionService                    sesionService;

    // ── Estado ────────────────────────────────────────────────────────────
    private Producto productoActual = null;
    private File     archivoCsv     = null;

    private final ObservableList<Producto>            listaInventario  = FXCollections.observableArrayList();
    private final ObservableList<MovimientoInventario> listaMovimientos = FXCollections.observableArrayList();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // ─────────────────────────────────────────────────────────────────────
    //  INICIALIZACIÓN
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configurarTablaInventario();
        configurarTablaMovimientos();
        mostrarVista(vistaAgregar);

        // Fechas por defecto: hoy
        dateDesde.setValue(LocalDate.now());
        dateHasta.setValue(LocalDate.now());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  NAVEGACIÓN ENTRE VISTAS
    // ─────────────────────────────────────────────────────────────────────

    @FXML void onMostrarAgregar() {
        limpiarFormularioAgregar();
        mostrarVista(vistaAgregar);
    }

    @FXML void onMostrarTabla() {
        cargarTablaInventario();
        mostrarVista(vistaTabla);
    }

    @FXML void onMostrarReporte()      { mostrarVista(vistaReporte);     }
    @FXML void onMostrarMovimientos()  {
        onMovimientosHoy();
        mostrarVista(vistaMovimientos);
    }
    @FXML void onMostrarImportar()     { mostrarVista(vistaImportar);    }

    // ─────────────────────────────────────────────────────────────────────
    //  VISTA 1 – AGREGAR STOCK
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onBuscarProducto() {
        String codigo = txtCodigo.getText().trim();
        if (codigo.isEmpty()) return;

        Optional<Producto> encontrado = productoService.buscarPorCodigo(codigo);
        encontrado.ifPresentOrElse(p -> {
            productoActual = p;
            lblNoEncontrado.setVisible(false);
            lblNoEncontrado.setManaged(false);
            mostrarInfoProducto(p);
        }, () -> {
            productoActual = null;
            lblNoEncontrado.setVisible(true);
            lblNoEncontrado.setManaged(true);
            panelInfoProducto.setVisible(false);
            panelInfoProducto.setManaged(false);
        });
    }

    @FXML
    private void onAgregarCantidad() {
        if (productoActual == null) {
            alerta(Alert.AlertType.WARNING, "Sin producto",
                    "Busca primero un producto por su código.");
            return;
        }

        double cantidad;
        try {
            cantidad = Double.parseDouble(txtCantidad.getText().trim().replace(",", "."));
        } catch (NumberFormatException e) {
            mostrarErrorCantidad(true);
            return;
        }

        if (cantidad <= 0) {
            mostrarErrorCantidad(true);
            return;
        }
        mostrarErrorCantidad(false);

        double anterior = productoActual.getCantidadActual();
        double nueva    = anterior + cantidad;

        // Actualizar stock del producto
        productoActual.setCantidadActual(nueva);
        productoService.guardar(productoActual);

        // Registrar movimiento
        registrarMovimiento(productoActual,
                MovimientoInventario.TipoMovimiento.ENTRADA,
                cantidad, anterior, nueva,
                txtNota.getText().trim());

        // Actualizar UI
        lblStockActual.setText(String.valueOf(nueva));
        txtCantidad.clear();
        txtNota.clear();

        alerta(Alert.AlertType.INFORMATION, "Stock actualizado",
                String.format("Se agregaron %.2f unidades a \"%s\".\nNuevo stock: %.2f",
                        cantidad, productoActual.getDescripcion(), nueva));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  VISTA 2 – TABLA DE INVENTARIO
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onFiltrarBajos() {
        ObservableList<Producto> bajos = listaInventario.filtered(p ->
                p.isUsaInventario() &&
                p.getCantidadActual() <= p.getCantidadMinima());
        tablaInventario.setItems(bajos);
    }

    @FXML
    private void onVerTodos() {
        tablaInventario.setItems(listaInventario);
    }

    @FXML
    private void onBuscarEnTabla() {
        String texto = txtBuscarTabla.getText().trim().toLowerCase();
        if (texto.isEmpty()) {
            tablaInventario.setItems(listaInventario);
        } else {
            tablaInventario.setItems(listaInventario.filtered(p ->
                    p.getDescripcion().toLowerCase().contains(texto) ||
                    p.getCodigoBarras().toLowerCase().contains(texto)));
        }
    }

    @FXML void onReporteInventario()  { /* TODO: generar PDF */ }
    @FXML void onReporteMovimientos() { /* TODO: exportar PDF */ }

    // ─────────────────────────────────────────────────────────────────────
    //  VISTA 4 – MOVIMIENTOS
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onFiltrarMovimientos() {
        if (dateDesde.getValue() == null || dateHasta.getValue() == null) return;
        LocalDateTime inicio = dateDesde.getValue().atStartOfDay();
        LocalDateTime fin    = dateHasta.getValue().atTime(23, 59, 59);
        listaMovimientos.setAll(
                movimientoRepo.findByFechaBetweenOrderByFechaDesc(inicio, fin));
        tablaMovimientos.setItems(listaMovimientos);
    }

    @FXML
    private void onMovimientosHoy() {
        dateDesde.setValue(LocalDate.now());
        dateHasta.setValue(LocalDate.now());
        onFiltrarMovimientos();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  VISTA 5 – IMPORTAR CSV
    // ─────────────────────────────────────────────────────────────────────

    @FXML
    private void onElegirArchivo() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Seleccionar archivo de inventario");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Archivos soportados (CSV, TXT)", "*.csv", "*.txt"),
                new FileChooser.ExtensionFilter("Todos los archivos", "*.*"));
        File file = chooser.showOpenDialog(txtRutaArchivo.getScene().getWindow());
        if (file != null) {
            archivoCsv = file;
            txtRutaArchivo.setText(file.getAbsolutePath());
            // Pre-detectar delimitador y mostrar al usuario
            String delimitador = detectarDelimitador(file);
            lblProgreso.setText("Archivo seleccionado — Delimitador detectado: "
                    + (delimitador.equals("\t") ? "TAB" : "COMA"));
            panelProgreso.setVisible(true);
            panelProgreso.setManaged(true);
            progressBar.setProgress(0);
        }
    }

    @FXML
    private void onEjecutarImportacion() {
        if (archivoCsv == null || !archivoCsv.exists()) {
            alerta(Alert.AlertType.WARNING, "Sin archivo",
                    "Selecciona un archivo primero.");
            return;
        }

        btnImportar.setDisable(true);
        panelProgreso.setVisible(true);
        panelProgreso.setManaged(true);
        panelResultado.setVisible(true);
        panelResultado.setManaged(true);
        txtLog.clear();
        progressBar.setProgress(-1);

        boolean actualizarExistentes = chkActualizarExistentes.isSelected();
        boolean crearDeptos          = chkCrearDepartamentos.isSelected();
        String  delimitador          = detectarDelimitador(archivoCsv);

        new Thread(() -> {
            int total = 0, creados = 0, actualizados = 0, errores = 0;
            StringBuilder log = new StringBuilder();

            // Intentar UTF-8 primero; si falla caracteres, usar windows-1252 (común en Excel México)
            java.nio.charset.Charset charset = detectarCharset(archivoCsv);
            log.append("ℹ Codificación detectada: ").append(charset.displayName()).append("\n");
            log.append("ℹ Delimitador: ").append(delimitador.equals("\t") ? "TAB" : "COMA").append("\n\n");

            try (BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(archivoCsv), charset))) {

                String linea;
                boolean encabezadoSaltado = false;

                while ((linea = reader.readLine()) != null) {
                    // Quitar \r de líneas Windows
                    linea = linea.replace("\r", "").trim();
                    if (linea.isEmpty()) continue;

                    // Saltar la primera fila si es encabezado
                    if (!encabezadoSaltado) {
                        encabezadoSaltado = true;
                        String lower = linea.toLowerCase();
                        if (lower.startsWith("codigo") || lower.startsWith("código")
                                || lower.startsWith("\"codigo") || lower.startsWith("\"código")) {
                            log.append("ℹ Encabezado omitido.\n");
                            continue;
                        }
                    }

                    total++;
                    // Parsear respetando comillas (campos que contienen comas)
                    String[] partes = parsearLineaCsv(linea, delimitador);

                    if (partes.length < 2) {
                        errores++;
                        log.append("⚠ Línea ").append(total)
                           .append(" ignorada — pocas columnas: ").append(linea, 0, Math.min(40, linea.length())).append("\n");
                        continue;
                    }

                    try {
                        String col0 = partes[0].trim();
                        String col1 = partes[1].trim();

                        // ── Detectar qué columna es el código y cuál la descripción ──
                        // Regla:
                        //   • Si col0 parece número (dígito o E+) → código=col0, desc=col1
                        //   • Si col0 NO es número pero col1 SÍ   → código=col1, desc=col0
                        //   • Si ninguna es número                 → código=col0, desc=col1
                        //     (código puede ser una frase corta como "AZUCAR")
                        String codigoRaw;
                        String descripcion;

                        if (esNumerico(col0)) {
                            codigoRaw  = normalizarCodigo(col0);
                            descripcion = col1;
                        } else if (esNumerico(col1)) {
                            codigoRaw  = normalizarCodigo(col1);
                            descripcion = col0;
                        } else {
                            // Ambos son texto: col0=código-frase, col1=descripción
                            codigoRaw  = col0;
                            descripcion = col1;
                        }

                        if (codigoRaw.isEmpty() || descripcion.isEmpty()) {
                            errores++;
                            log.append("⚠ Línea ").append(total).append(" — código o descripción vacíos\n");
                            continue;
                        }

                        double precioCosto   = partes.length > 2 ? parsearMoneda(partes[2]) : 0;
                        double precioVenta   = partes.length > 3 ? parsearMoneda(partes[3]) : 0;
                        double precioMayoreo = partes.length > 4 ? parsearMoneda(partes[4]) : 0;
                        double inventario    = partes.length > 5 ? parsearDouble(partes[5]) : 0;
                        double invMinimo     = partes.length > 6 ? parsearDouble(partes[6]) : 0;
                        String deptoNombre   = partes.length > 7 ? limpiarDepto(partes[7])  : "";

                        // Ignorar departamentos eliminados
                        if (deptoNombre.contains("Eliminado")) deptoNombre = "";

                        // Buscar o crear departamento
                        Departamento depto = null;
                        if (!deptoNombre.isBlank() && crearDeptos) {
                            String finalDeptoNombre = deptoNombre;
                            depto = departamentoRepo.findAll().stream()
                                    .filter(d -> d.getNombre().equalsIgnoreCase(finalDeptoNombre))
                                    .findFirst()
                                    .orElseGet(() -> {
                                        Departamento nd = new Departamento();
                                        nd.setNombre(finalDeptoNombre);
                                        return departamentoRepo.save(nd);
                                    });
                        }

                        // Buscar producto existente
                        Optional<Producto> existente = productoService.buscarPorCodigo(codigoRaw);

                        if (existente.isPresent()) {
                            if (actualizarExistentes) {
                                Producto p = existente.get();
                                double anterior = p.getCantidadActual();
                                p.setPrecioCosto(precioCosto);
                                p.setPrecioVenta(precioVenta);
                                p.setPrecioMayoreo(precioMayoreo);
                                p.setCantidadActual(inventario);
                                p.setCantidadMinima(invMinimo);
                                if (depto != null) p.setDepartamento(depto);
                                productoService.guardar(p);
                                if (inventario != anterior) {
                                    registrarMovimiento(p,
                                            MovimientoInventario.TipoMovimiento.IMPORTACION,
                                            inventario - anterior, anterior, inventario,
                                            "Importación CSV");
                                }
                                actualizados++;
                                log.append("✏ Actualizado: ").append(descripcion).append("\n");
                            } else {
                                log.append("⏭ Omitido (ya existe): ").append(descripcion).append("\n");
                            }
                        } else {
                            // Crear nuevo
                            Producto p = new Producto();
                            p.setCodigoBarras(codigoRaw);
                            p.setDescripcion(descripcion);
                            p.setPrecioCosto(precioCosto);
                            p.setPrecioVenta(precioVenta);
                            p.setPrecioMayoreo(precioMayoreo);
                            p.setUsaInventario(true);
                            p.setCantidadActual(inventario);
                            p.setCantidadMinima(invMinimo);
                            p.setDepartamento(depto);
                            p.setTipoVenta(Producto.TipoVenta.UNIDAD);
                            productoService.guardar(p);
                            registrarMovimiento(p,
                                    MovimientoInventario.TipoMovimiento.IMPORTACION,
                                    inventario, 0, inventario, "Importación CSV");
                            creados++;
                            log.append("✅ Creado: ").append(descripcion).append("\n");
                        }

                    } catch (Exception ex) {
                        errores++;
                        log.append("❌ Error en línea ").append(total)
                           .append(": ").append(ex.getMessage()).append("\n");
                    }
                }

            } catch (Exception e) {
                log.append("❌ Error al leer el archivo: ").append(e.getMessage())
                   .append("\n  → Intenta guardar el archivo como CSV UTF-8 desde Excel.\n");
            }

            // Resumen final
            int finalCreados = creados, finalActualizados = actualizados, finalErrores = errores, finalTotal = total;
            String logFinal = "────────────────────────\n"
                    + "Total líneas procesadas: " + finalTotal + "\n"
                    + "✅ Creados: "     + finalCreados     + "\n"
                    + "✏ Actualizados: " + finalActualizados + "\n"
                    + "❌ Errores: "      + finalErrores     + "\n"
                    + "────────────────────────\n"
                    + log;

            Platform.runLater(() -> {
                txtLog.setText(logFinal);
                progressBar.setProgress(1.0);
                lblProgreso.setText("Importación completada.");
                btnImportar.setDisable(false);
            });

        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS PRIVADOS
    // ─────────────────────────────────────────────────────────────────────

    private void mostrarVista(BorderPane vista) {
        for (BorderPane v : new BorderPane[]{
                vistaAgregar, vistaTabla, vistaReporte, vistaMovimientos, vistaImportar}) {
            v.setVisible(v == vista);
            v.setManaged(v == vista);
        }
    }

    private void mostrarInfoProducto(Producto p) {
        lblDescripcion.setText(p.getDescripcion());
        lblDepartamento.setText(p.getDepartamento() != null
                ? p.getDepartamento().getNombre() : "Sin departamento");
        lblStockActual.setText(String.valueOf(p.getCantidadActual()));
        lblStockMinimo.setText(String.valueOf(p.getCantidadMinima()));
        panelInfoProducto.setVisible(true);
        panelInfoProducto.setManaged(true);
        txtCantidad.requestFocus();
    }

    private void limpiarFormularioAgregar() {
        productoActual = null;
        txtCodigo.clear();
        txtCantidad.clear();
        txtNota.clear();
        lblNoEncontrado.setVisible(false);
        lblNoEncontrado.setManaged(false);
        panelInfoProducto.setVisible(false);
        panelInfoProducto.setManaged(false);
        mostrarErrorCantidad(false);
    }

    private void mostrarErrorCantidad(boolean mostrar) {
        lblErrorCantidad.setVisible(mostrar);
        lblErrorCantidad.setManaged(mostrar);
    }

    private void configurarTablaInventario() {
        colCodigo.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getCodigoBarras()));
        colDescripcion.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getDescripcion()));
        colActual.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getCantidadActual()).asObject());
        colMinimo.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getCantidadMinima()).asObject());
        colEstado.setCellValueFactory(c -> {
            Producto p = c.getValue();
            if (!p.isUsaInventario()) return new SimpleStringProperty("—");
            return new SimpleStringProperty(
                    p.getCantidadActual() <= p.getCantidadMinima() ? "⚠ Stock bajo" : "✅ OK");
        });
        colEstado.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(v);
                setStyle(v.contains("⚠")
                        ? "-fx-text-fill:#C0392B;-fx-font-weight:bold"
                        : "-fx-text-fill:-color-success-fg");
            }
        });
        colDepartamento.setCellValueFactory(c -> {
            Departamento d = c.getValue().getDepartamento();
            return new SimpleStringProperty(d != null ? d.getNombre() : "—");
        });
        tablaInventario.setItems(listaInventario);
    }

    private void configurarTablaMovimientos() {
        colMovFecha.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getFecha().format(FMT)));
        colMovProducto.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getProducto().getDescripcion()));
        colMovTipo.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getTipo().name()));
        colMovCantidad.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getCantidad()).asObject());
        colMovAnterior.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getCantidadAnterior()).asObject());
        colMovNueva.setCellValueFactory(c ->
                new SimpleDoubleProperty(c.getValue().getCantidadNueva()).asObject());
        colMovUsuario.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getUsuario() != null
                        ? c.getValue().getUsuario() : "—"));
        colMovNota.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getNota() != null
                        ? c.getValue().getNota() : ""));
        tablaMovimientos.setItems(listaMovimientos);
    }

    private void cargarTablaInventario() {
        listaInventario.setAll(productoService.listarTodos());
    }

    private void registrarMovimiento(Producto producto,
                                      MovimientoInventario.TipoMovimiento tipo,
                                      double cantidad, double anterior, double nueva,
                                      String nota) {
        MovimientoInventario mov = new MovimientoInventario();
        mov.setProducto(producto);
        mov.setTipo(tipo);
        mov.setCantidad(cantidad);
        mov.setCantidadAnterior(anterior);
        mov.setCantidadNueva(nueva);
        mov.setUsuario(sesionService.getNombreCajero());
        mov.setNota(nota);
        movimientoRepo.save(mov);
    }

    // ── Helpers de importación ────────────────────────────────────────────

    /**
     * Detecta si el archivo usa coma o tabulación como delimitador
     * leyendo solo la primera línea.
     */
    private String detectarDelimitador(File file) {
        try (BufferedReader r = new BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(file),
                        detectarCharset(file)))) {
            String primera = r.readLine();
            if (primera == null) return ",";
            // Contar ocurrencias de cada delimitador
            long comas = primera.chars().filter(c -> c == ',').count();
            long tabs  = primera.chars().filter(c -> c == '\t').count();
            return tabs > comas ? "\t" : ",";
        } catch (Exception e) {
            return ","; // por defecto coma
        }
    }

    /**
     * Detecta la codificación del archivo:
     * Si empieza con BOM UTF-8 → UTF-8
     * Si tiene caracteres > 127 típicos de Latin-1 → windows-1252
     * De lo contrario → UTF-8
     */
    private java.nio.charset.Charset detectarCharset(File file) {
        try (java.io.InputStream is = new java.io.FileInputStream(file)) {
            byte[] bom = new byte[3];
            int read = is.read(bom, 0, 3);
            if (read >= 3 && bom[0] == (byte) 0xEF
                    && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                return java.nio.charset.StandardCharsets.UTF_8; // UTF-8 con BOM
            }
        } catch (Exception ignored) { }
        // Intentar leer con UTF-8; si hay errores usar windows-1252
        try (BufferedReader r = new BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            for (int i = 0; i < 5; i++) {
                String l = r.readLine();
                if (l == null) break;
                if (l.contains("\uFFFD")) { // carácter de reemplazo = mala codificación
                    return java.nio.charset.Charset.forName("windows-1252");
                }
            }
        } catch (Exception ignored) { }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    /**
     * Parsea una línea CSV respetando campos entre comillas.
     * Funciona con delimitador coma o tabulación.
     */
    private String[] parsearLineaCsv(String linea, String delimitador) {
        java.util.List<String> campos = new java.util.ArrayList<>();
        boolean dentroComillas = false;
        StringBuilder campo = new StringBuilder();

        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (c == '"') {
                // Comilla doble escapada dentro de campo ("" → ")
                if (dentroComillas && i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
                    campo.append('"');
                    i++;
                } else {
                    dentroComillas = !dentroComillas;
                }
            } else if (!dentroComillas && linea.regionMatches(i, delimitador, 0, delimitador.length())) {
                campos.add(campo.toString());
                campo.setLength(0);
                i += delimitador.length() - 1;
            } else {
                campo.append(c);
            }
        }
        campos.add(campo.toString());
        return campos.toArray(new String[0]);
    }

    /**
     * Determina si un valor de texto representa un número (código de barras
     * o notación científica como 7.57528E+11). Los códigos-frase no son numéricos.
     */
    private boolean esNumerico(String texto) {
        if (texto == null || texto.isBlank()) return false;
        String t = texto.trim();
        // Coincide con: dígitos, punto decimal, notación E+/E-
        return t.matches("[0-9]+\\.?[0-9]*([Ee][+\\-]?[0-9]+)?");
    }

    /**
     * Normaliza un código:
     * - Convierte notación científica a entero: "7.57528E+11" → "757528000000"
     * - Elimina ".00" de códigos como "7501055303878.00" → "7501055303878"
     * - Deja frases sin tocar.
     */
    private String normalizarCodigo(String codigo) {
        String t = codigo.trim();
        if (t.isEmpty()) return t;
        try {
            // Parsear como double para manejar notación científica
            double d = Double.parseDouble(t);
            // Convertir a long (sin decimales) si el valor es entero
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return t;
        } catch (NumberFormatException e) {
            return t; // ya es texto/frase
        }
    }

    // ── Parsers para importación CSV ──────────────────────────────────────

    /** Elimina $, espacios y comas del valor monetario */
    private double parsearMoneda(String texto) {
        try {
            return Double.parseDouble(
                    texto.trim().replace("$", "").replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) { return 0; }
    }

    private double parsearDouble(String texto) {
        try { return Double.parseDouble(texto.trim().replace(",", ".")); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Elimina "(Eliminado ...)" del nombre del departamento */
    private String limpiarDepto(String texto) {
        if (texto == null) return "";
        int idx = texto.indexOf("(Eliminado");
        if (idx >= 0) texto = texto.substring(0, idx);
        return texto.trim().replace("- Sin Departamento -", "").trim();
    }

    private void alerta(Alert.AlertType tipo, String titulo, String msg) {
        Alert a = new Alert(tipo);
        a.setTitle(titulo);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }
}