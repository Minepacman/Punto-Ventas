package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompraLinea;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaOrden;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.LineaSugerida;
import me.eliassanchezfernandez.puntodeventa.service.OrdenCompraService.ModoSugerencia;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas;
import me.eliassanchezfernandez.puntodeventa.util.ExportarTabla.Formato;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Órdenes de compra (ventana modal de Inventario). Requiere GESTIONAR_PROVEEDORES
 * para abrirla; recibir mercancía pide AJUSTAR_INVENTARIO (o la autorización de un
 * administrador). Las reglas viven en OrdenCompraService; aquí solo hay pantalla.
 */
@Component
@Scope("prototype")
public class OrdenesCompraController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm");

    /** Línea en edición (se vuelve LineaOrden al guardar) */
    public static final class FilaLinea {
        final Producto producto;
        double cantidad;
        double costo;

        FilaLinea(Producto producto, double cantidad, double costo) {
            this.producto = producto;
            this.cantidad = cantidad;
            this.costo = costo;
        }

        double importe() { return Math.round(cantidad * costo * 100.0) / 100.0; }
    }

    @FXML private BorderPane raiz;
    @FXML private Label lblAviso;
    @FXML private TableView<OrdenCompra> tablaOrdenes;
    @FXML private TableColumn<OrdenCompra, String> colOrdFolio, colOrdFecha, colOrdProveedor, colOrdEstado, colOrdTotal;
    @FXML private Label lblTitulo;
    @FXML private Label lblEstado;
    @FXML private ComboBox<Proveedor> cmbProveedor;
    @FXML private ComboBox<ModoSugerencia> cmbModoSugerencia;
    @FXML private Button btnSugerir;
    @FXML private TextField txtProducto;
    @FXML private Button btnAgregar;
    @FXML private Button btnQuitar;
    @FXML private TableView<FilaLinea> tablaLineas;
    @FXML private TableColumn<FilaLinea, String> colCodigo, colProducto, colExistencia, colCantidad, colCosto, colImporte;
    @FXML private Label lblSugerenciaNota;
    @FXML private TextArea txtNotas;
    @FXML private Label lblTotal;
    @FXML private Label lblMensaje;
    @FXML private Button btnGuardar, btnEnviada, btnRecibir, btnCancelarOrden, btnImprimir, btnCsv, btnXlsx, btnPdf;

    @Autowired private OrdenCompraService ordenCompraService;
    @Autowired private ProveedorService proveedorService;
    @Autowired private ProductoService productoService;
    @Autowired private ImpresionService impresionService;
    @Autowired private SesionService sesionService;
    @Autowired private AutorizacionService autorizacionService;

    private final ObservableList<OrdenCompra> ordenes = FXCollections.observableArrayList();
    private final ObservableList<FilaLinea> lineas = FXCollections.observableArrayList();
    /** Orden abierta en el editor (null = orden nueva sin guardar) */
    private OrdenCompra actual;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (!sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES)) {
            raiz.setDisable(true);
            lblAviso.setText("Requiere el permiso «" + Permiso.GESTIONAR_PROVEEDORES.getDescripcion() + "».");
            return;
        }
        configurarTablas();
        cmbModoSugerencia.setItems(FXCollections.observableArrayList(ModoSugerencia.values()));
        cmbModoSugerencia.setValue(ModoSugerencia.BAJO_MINIMO);
        tablaOrdenes.setItems(ordenes);
        tablaOrdenes.setPlaceholder(new Label("Aún no hay órdenes de compra."));
        tablaOrdenes.getSelectionModel().selectedItemProperty().addListener((o, a, orden) -> {
            if (orden != null) abrir(orden.getId());
        });
        tablaLineas.setItems(lineas);
        tablaLineas.setPlaceholder(new Label("Agrega productos o usa «Sugerir pedido»."));
        recargarProveedores();
        recargarOrdenes();
        nueva();
    }

    private void configurarTablas() {
        colOrdFolio.setCellValueFactory(c -> new SimpleStringProperty("#" + c.getValue().getId()));
        colOrdFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFecha().format(FMT)));
        colOrdProveedor.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProveedor().getNombre()));
        colOrdEstado.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getEstado().getEtiqueta()));
        colOrdTotal.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(totalDe(c.getValue()))));

        colCodigo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().producto.getCodigoBarras()));
        colProducto.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().producto.getDescripcion()));
        colExistencia.setCellValueFactory(c -> new SimpleStringProperty(cantidad(c.getValue().producto.getCantidadActual())));
        colCantidad.setCellValueFactory(c -> new SimpleStringProperty(cantidad(c.getValue().cantidad)));
        colCosto.setCellValueFactory(c -> new SimpleStringProperty(String.format("%.2f", c.getValue().costo)));
        colImporte.setCellValueFactory(c -> new SimpleStringProperty(Moneda.formato(c.getValue().importe())));
        StringConverter<String> igual = new javafx.util.converter.DefaultStringConverter();
        colCantidad.setCellFactory(TextFieldTableCell.forTableColumn(igual));
        colCosto.setCellFactory(TextFieldTableCell.forTableColumn(igual));
        colCantidad.setOnEditCommit(e -> {
            double v = numero(e.getNewValue());
            if (Double.isNaN(v) || v <= 0) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Cantidad", "Escribe una cantidad mayor a cero.");
            } else {
                e.getRowValue().cantidad = v;
            }
            tablaLineas.refresh();
            actualizarTotal();
        });
        colCosto.setOnEditCommit(e -> {
            double v = numero(e.getNewValue());
            if (Double.isNaN(v)) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Costo", "Escribe el costo como número (ej. 12.50).");
            } else {
                e.getRowValue().costo = v;
            }
            tablaLineas.refresh();
            actualizarTotal();
        });
    }

    private static double totalDe(OrdenCompra o) {
        try {
            return o.getTotal();
        } catch (RuntimeException e) {
            return 0;   // líneas sin cargar en la lista: el total real se ve al abrirla
        }
    }

    // ── Carga ─────────────────────────────────────────────────────────────

    private void recargarProveedores() {
        List<Proveedor> activos = proveedorService.listarActivos();
        cmbProveedor.setItems(FXCollections.observableArrayList(activos));
    }

    private void recargarOrdenes() {
        try {
            ordenes.setAll(ordenCompraService.listar());
        } catch (NegocioException e) {
            ordenes.clear();
            Ventanas.alerta(Alert.AlertType.WARNING, "Órdenes de compra", e.getMessage());
        }
    }

    private void abrir(Long id) {
        try {
            actual = ordenCompraService.obtener(id);
            mostrarActual();
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Órdenes de compra", e.getMessage());
        }
    }

    private void mostrarActual() {
        lineas.clear();
        for (OrdenCompraLinea l : actual.getLineas()) lineas.add(new FilaLinea(l.getProducto(), l.getCantidad(), l.getCostoUnitario()));
        lblTitulo.setText("ORDEN #" + actual.getId() + " · " + actual.getFecha().format(FMT));
        lblEstado.setText(actual.getEstado().getEtiqueta());
        cmbProveedor.getItems().stream().filter(p -> p.getId().equals(actual.getProveedor().getId())).findFirst()
                .ifPresentOrElse(cmbProveedor::setValue, () -> {
                    cmbProveedor.getItems().add(actual.getProveedor());   // proveedor desactivado: se muestra igual
                    cmbProveedor.setValue(actual.getProveedor());
                });
        txtNotas.setText(actual.getNotas() != null ? actual.getNotas() : "");
        lblMensaje.setText(actual.getEstado() == OrdenCompra.Estado.RECIBIDA
                ? "Recibida el " + actual.getFechaRecepcion().format(FMT) + " por " + actual.getRecibidaPor() : "");
        aplicarEstado();
        actualizarTotal();
    }

    private void nueva() {
        actual = null;
        tablaOrdenes.getSelectionModel().clearSelection();
        lineas.clear();
        lblTitulo.setText("ORDEN NUEVA");
        lblEstado.setText("Sin guardar");
        cmbProveedor.setValue(null);
        txtNotas.clear();
        lblMensaje.setText("");
        aplicarEstado();
        actualizarTotal();
    }

    /** Habilita lo que corresponde al estado: solo el borrador se edita */
    private void aplicarEstado() {
        OrdenCompra.Estado e = actual == null ? OrdenCompra.Estado.BORRADOR : actual.getEstado();
        boolean editable = e == OrdenCompra.Estado.BORRADOR;
        boolean guardada = actual != null;
        cmbProveedor.setDisable(!editable || guardada);
        cmbModoSugerencia.setDisable(!editable);
        btnSugerir.setDisable(!editable);
        txtProducto.setDisable(!editable);
        btnAgregar.setDisable(!editable);
        btnQuitar.setDisable(!editable);
        txtNotas.setDisable(!editable);
        tablaLineas.setEditable(editable);
        lblSugerenciaNota.setVisible(editable);
        lblSugerenciaNota.setManaged(editable);
        btnGuardar.setDisable(!editable);
        btnEnviada.setDisable(!editable || !guardada);
        btnRecibir.setDisable(!guardada || (e != OrdenCompra.Estado.BORRADOR && e != OrdenCompra.Estado.ENVIADA));
        btnCancelarOrden.setDisable(!guardada || (e != OrdenCompra.Estado.BORRADOR && e != OrdenCompra.Estado.ENVIADA));
        for (Button b : List.of(btnImprimir, btnCsv, btnXlsx, btnPdf)) b.setDisable(!guardada);
    }

    private void actualizarTotal() {
        double total = lineas.stream().mapToDouble(FilaLinea::importe).sum();
        lblTotal.setText("Total: " + Moneda.formato(total) + "  ·  " + lineas.size() + " producto(s)");
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    @FXML private void onNueva() { nueva(); }

    @FXML private void onSugerir() {
        Proveedor p = cmbProveedor.getValue();
        if (p == null) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Sugerir pedido", "Primero elige el proveedor.");
            return;
        }
        try {
            List<LineaSugerida> sugeridas = ordenCompraService.sugerirPedido(p.getId(), cmbModoSugerencia.getValue());
            int agregadas = 0;
            for (LineaSugerida s : sugeridas) {
                if (lineas.stream().anyMatch(f -> f.producto.getId().equals(s.producto().getId()))) continue;
                lineas.add(new FilaLinea(s.producto(), s.cantidad(), s.costoUnitario()));
                agregadas++;
            }
            actualizarTotal();
            lblMensaje.setText(agregadas == 0 ? "No hay productos que sugerir con ese criterio."
                    : agregadas + " producto(s) sugerido(s). Revisa cantidades y costos antes de guardar.");
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Sugerir pedido", e.getMessage());
        }
    }

    @FXML private void onAgregarProducto() {
        String texto = txtProducto.getText() == null ? "" : txtProducto.getText().trim();
        if (texto.isEmpty()) return;
        Optional<Producto> exacto = productoService.buscarPorCodigo(texto);
        Producto elegido;
        if (exacto.isPresent()) {
            elegido = exacto.get();
        } else {
            List<Producto> coincidencias = productoService.buscar(texto).stream()
                    .filter(Producto::isUsaInventario).filter(Producto::isActivo).limit(30).toList();
            if (coincidencias.isEmpty()) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Producto no encontrado",
                        "No hay un producto con inventario que coincida con \"" + texto + "\".");
                return;
            }
            if (coincidencias.size() == 1) {
                elegido = coincidencias.get(0);
            } else {
                ChoiceDialog<Producto> dlg = new ChoiceDialog<>(coincidencias.get(0), coincidencias);
                dlg.setTitle("Agregar producto");
                dlg.setHeaderText("Hay varios productos que coinciden. Elige uno.");
                dlg.initOwner(raiz.getScene().getWindow());
                Ventanas.estilizar(dlg);
                Optional<Producto> r = dlg.showAndWait();
                if (r.isEmpty()) return;
                elegido = r.get();
            }
        }
        if (!elegido.isUsaInventario()) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Sin inventario", "\"" + elegido.getDescripcion() + "\" no maneja inventario.");
            return;
        }
        Optional<FilaLinea> ya = lineas.stream().filter(f -> f.producto.getId().equals(elegido.getId())).findFirst();
        if (ya.isPresent()) {
            ya.get().cantidad += 1;
            tablaLineas.refresh();
        } else {
            lineas.add(new FilaLinea(elegido, 1, elegido.getPrecioCosto()));
        }
        txtProducto.clear();
        actualizarTotal();
        txtProducto.requestFocus();
    }

    @FXML private void onQuitarLinea() {
        FilaLinea f = tablaLineas.getSelectionModel().getSelectedItem();
        if (f == null) return;
        lineas.remove(f);
        actualizarTotal();
    }

    @FXML private void onGuardar() {
        guardar(true);
    }

    /** Guarda el borrador; devuelve false si el servicio lo rechazó */
    private boolean guardar(boolean avisar) {
        try {
            List<LineaOrden> pedidas = new ArrayList<>();
            for (FilaLinea f : lineas) pedidas.add(new LineaOrden(f.producto.getId(), f.cantidad, f.costo));
            if (actual == null) {
                if (cmbProveedor.getValue() == null) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Orden de compra", "Elige el proveedor de la orden.");
                    return false;
                }
                actual = ordenCompraService.crear(cmbProveedor.getValue().getId(), pedidas, txtNotas.getText());
            } else {
                actual = ordenCompraService.actualizar(actual.getId(), pedidas, txtNotas.getText());
            }
            Long id = actual.getId();
            recargarOrdenes();
            abrir(id);
            if (avisar) lblMensaje.setText("Orden #" + id + " guardada.");
            return true;
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo guardar", e.getMessage());
            return false;
        }
    }

    @FXML private void onEnviar() {
        if (!guardar(false)) return;
        try {
            actual = ordenCompraService.marcarEnviada(actual.getId());
            Long id = actual.getId();
            recargarOrdenes();
            abrir(id);
            lblMensaje.setText("Orden #" + id + " marcada como enviada.");
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Órdenes de compra", e.getMessage());
        }
    }

    /** Suma todas las líneas al inventario de una sola vez (AJUSTAR_INVENTARIO o autorización) */
    @FXML private void onRecibir() {
        if (actual == null) return;
        if (!Ventanas.confirmar("Recibir mercancía", String.format(
                "¿Recibir la orden #%d de %s?\nSe sumarán %d producto(s) al inventario y el costo de cada uno se actualizará.",
                actual.getId(), actual.getProveedor().getNombre(), actual.getLineas().size()))) {
            return;
        }
        List<Autorizacion> auts = List.of();
        for (int intento = 0; intento < 2; intento++) {
            try {
                OrdenCompra recibida = ordenCompraService.recibir(actual.getId(), auts);
                Long id = recibida.getId();
                recargarOrdenes();
                abrir(id);
                Ventanas.alerta(Alert.AlertType.INFORMATION, "Mercancía recibida",
                        "La orden #" + id + " se recibió y el inventario se actualizó.");
                return;
            } catch (PermisoRequeridoException e) {
                if (intento == 1 || sesionService.tienePermiso(e.getPermiso())) {
                    Ventanas.alerta(Alert.AlertType.ERROR, "Recibir mercancía", e.getMessage());
                    return;
                }
                Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, e.getPermiso());
                if (aut.isEmpty()) return;
                auts = List.of(aut.get());
            } catch (NegocioException e) {
                Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo recibir", e.getMessage());
                return;
            }
        }
    }

    @FXML private void onCancelarOrden() {
        if (actual == null || !Ventanas.confirmar("Cancelar orden",
                "¿Cancelar la orden #" + actual.getId() + "? No se podrá recibir después.")) {
            return;
        }
        try {
            Long id = ordenCompraService.cancelar(actual.getId()).getId();
            recargarOrdenes();
            abrir(id);
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Órdenes de compra", e.getMessage());
        }
    }

    // ── Imprimir y exportar ───────────────────────────────────────────────

    @FXML private void onImprimir() {
        if (actual == null) return;
        try {
            impresionService.imprimirOrdenCompraAsync(actual.getId()).whenComplete((res, error) -> Platform.runLater(() -> {
                if (error != null || res == null || res.esFallo()) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Imprimir orden",
                            res != null ? res.mensaje() : "No se pudo imprimir la orden.");
                }
            }));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Imprimir orden", e.getMessage());
        }
    }

    @FXML private void onExportarCsv() {
        if (actual == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Exportar orden de compra");
        fc.setInitialFileName("orden-compra-" + actual.getId() + "-" + LocalDate.now() + ".csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv"));
        File destino = fc.showSaveDialog(raiz.getScene().getWindow());
        if (destino == null) return;
        try {
            ordenCompraService.exportarCsv(actual.getId(), destino.toPath());
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Exportar", "Se guardó la orden en:\n" + destino);
        } catch (IOException | NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Exportar", "No se pudo guardar el archivo: " + e.getMessage());
        }
    }

    @FXML private void onExportarXlsx() { exportarOtro(Formato.XLSX); }

    @FXML private void onExportarPdf() { exportarOtro(Formato.PDF); }

    private void exportarOtro(Formato formato) {
        if (actual == null) return;
        try {
            OrdenCompra o = ordenCompraService.obtener(actual.getId());
            ExportadorTablas.exportar(raiz.getScene().getWindow(), formato, "orden-compra-" + o.getId(),
                    "Orden de compra #" + o.getId() + " — " + o.getProveedor().getNombre(),
                    OrdenCompraService.ENCABEZADOS_CSV, OrdenCompraService.filas(o));
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Exportar", e.getMessage());
        }
    }

    @FXML private void onCerrar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static double numero(String texto) {
        if (texto == null) return Double.NaN;
        try {
            double v = Double.parseDouble(texto.trim().replace("$", "").replace(",", ""));
            return Double.isFinite(v) && v >= 0 ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String cantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }
}
