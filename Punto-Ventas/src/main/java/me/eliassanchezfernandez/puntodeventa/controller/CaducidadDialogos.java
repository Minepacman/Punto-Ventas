package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompraLinea;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.CaducidadService;
import me.eliassanchezfernandez.puntodeventa.service.CaducidadService.PorCaducar;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.Iconos;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ventanas de caducidades (sin estado):
 *  - "Por caducar" (Inventario): lo que vence en los próximos N días o ya venció, con la cantidad
 *    estimada, y las acciones "Retirar" (merma "Caducado") y "Ya no queda".
 *  - Fechas al recibir una orden de compra (solo productos que controlan caducidad; opcionales).
 */
@Component
public class CaducidadDialogos {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired private CaducidadService     caducidadService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionService        sesionService;
    @Autowired private AutorizacionService  autorizacionService;

    // ── Utilidades para otras pantallas ───────────────────────────────────

    /** DatePicker "Caduca el" con formato dd/mm/aaaa (vacío = sin fecha) */
    public static DatePicker selectorFecha() {
        DatePicker dp = new DatePicker();
        configurar(dp);
        return dp;
    }

    /** Formato dd/mm/aaaa y aviso de texto en un DatePicker ya creado (p. ej. desde FXML) */
    public static void configurar(DatePicker dp) {
        dp.setPromptText("dd/mm/aaaa");
        dp.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(LocalDate d) { return d == null ? "" : d.format(FECHA); }
            @Override public LocalDate fromString(String s) { return parsear(s); }
        });
    }

    /**
     * Fecha escrita o elegida (lo escrito aunque no se haya confirmado con Enter). null si está vacío.
     * @throws NegocioException si lo escrito no es una fecha
     */
    public static LocalDate fechaDe(DatePicker dp) {
        String texto = dp.getEditor().getText();
        if (texto == null || texto.isBlank()) return null;
        LocalDate d = parsear(texto);
        if (d == null) throw new NegocioException("Escribe la fecha de caducidad como día/mes/año (ej. 25/12/2026).");
        return d;
    }

    /** "25/12/2026", "25-12-2026", "2026-12-25"; null si no se entiende */
    static LocalDate parsear(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().replace('-', '/').replace('.', '/');
        try {
            if (t.matches("\\d{4}/\\d{1,2}/\\d{1,2}")) {
                String[] p = t.split("/");
                return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            }
            if (t.matches("\\d{1,2}/\\d{1,2}/\\d{2,4}")) {
                String[] p = t.split("/");
                int anio = Integer.parseInt(p[2]);
                if (anio < 100) anio += 2000;
                return LocalDate.of(anio, Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
        } catch (java.time.DateTimeException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    // ── Por caducar ───────────────────────────────────────────────────────

    public void mostrarPorCaducar(Window owner) {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Por caducar");
        dlg.setHeaderText(null);
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);

        Label explicacion = new Label();
        explicacion.setWrapText(true);
        explicacion.getStyleClass().add("hint");

        ObservableList<PorCaducar> filas = FXCollections.observableArrayList();
        TableView<PorCaducar> tabla = new TableView<>(filas);
        tabla.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tabla.setPlaceholder(new Label("Nada por caducar en esos días."));
        TableColumn<PorCaducar, String> colProd = new TableColumn<>("Producto");
        colProd.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().producto()));
        colProd.setPrefWidth(260);
        TableColumn<PorCaducar, String> colFecha = new TableColumn<>("Caduca el");
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fechaCaducidad().format(FECHA)));
        TableColumn<PorCaducar, String> colDias = new TableColumn<>("Cuándo");
        colDias.setCellValueFactory(c -> new SimpleStringProperty(CaducidadService.textoDias(c.getValue().dias())));
        colDias.setPrefWidth(150);
        TableColumn<PorCaducar, String> colCant = new TableColumn<>("Cantidad (aprox.)");
        colCant.setCellValueFactory(c -> new SimpleStringProperty("~" + cantidad(c.getValue().estimado())
                + (c.getValue().unidad().equals("pza") ? "" : " " + c.getValue().unidad())));
        colCant.getStyleClass().add("align-right");
        tabla.getColumns().setAll(List.of(colProd, colFecha, colDias, colCant));
        tabla.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(PorCaducar p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().removeAll("fila-stock-bajo", "fila-agotado");
                if (empty || p == null) return;
                getStyleClass().add(p.caducado() ? "fila-agotado" : "fila-stock-bajo");
            }
        });

        // Días de anticipación (solo quien ajusta inventario lo cambia)
        boolean puedeAjustar = sesionService.tienePermiso(Permiso.AJUSTAR_INVENTARIO);
        Spinner<Integer> spDias = new Spinner<>(CaducidadService.DIAS_AVISO_MINIMO, CaducidadService.DIAS_AVISO_MAXIMO,
                caducidadService.diasAviso());
        spDias.setEditable(true);
        spDias.setPrefWidth(90);
        Button btnDias = new Button("Guardar");
        btnDias.setGraphic(Iconos.de("guardar"));
        HBox barraDias = new HBox(8, new Label("Avisar con"), spDias, new Label("días de anticipación"), btnDias);
        barraDias.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        spDias.setDisable(!puedeAjustar);
        btnDias.setVisible(puedeAjustar);
        btnDias.setManaged(puedeAjustar);

        Runnable recargar = () -> {
            int dias = caducidadService.diasAviso();
            explicacion.setText("Lo que caduca en los próximos " + dias + " días o ya caducó (en rojo). La cantidad es un "
                    + "cálculo aproximado: se supone que lo primero que se vende es lo que caduca primero. Si ya no queda "
                    + "nada de una fecha, márcala con «Ya no queda».");
            filas.setAll(caducidadService.porCaducar());
        };
        btnDias.setOnAction(e -> {
            try {
                Ventanas.confirmarSpinner(spDias, "Días de anticipación");
                configuracionService.guardarDiasAvisoCaducidad(spDias.getValue());
                recargar.run();
            } catch (NegocioException ex) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Por caducar", ex.getMessage());
            }
        });

        Button btnRetirar = new Button("Retirar (merma)…");
        btnRetirar.setGraphic(Iconos.de("eliminar"));
        Button btnYaNo = new Button("Ya no queda");
        btnYaNo.setGraphic(Iconos.de("listo"));
        btnRetirar.disableProperty().bind(tabla.getSelectionModel().selectedItemProperty().isNull());
        btnYaNo.disableProperty().bind(tabla.getSelectionModel().selectedItemProperty().isNull());
        btnRetirar.setOnAction(e -> {
            PorCaducar p = tabla.getSelectionModel().getSelectedItem();
            if (p != null && retirar(p)) recargar.run();
        });
        btnYaNo.setOnAction(e -> {
            PorCaducar p = tabla.getSelectionModel().getSelectedItem();
            if (p != null && yaNoQueda(p)) recargar.run();
        });
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox acciones = new HBox(8, btnRetirar, btnYaNo, espacio);

        VBox contenido = new VBox(10, explicacion, barraDias, tabla, acciones);
        VBox.setVgrow(tabla, Priority.ALWAYS);
        contenido.setPadding(new Insets(12));
        contenido.setPrefSize(720, 460);
        dlg.getDialogPane().setContent(contenido);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.setResizable(true);
        try {
            recargar.run();
        } catch (NegocioException ex) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Por caducar", ex.getMessage());
            return;
        }
        dlg.showAndWait();
    }

    /** Merma "Caducado" con la cantidad que se retira (por defecto lo estimado). true si se hizo. */
    private boolean retirar(PorCaducar p) {
        TextInputDialog pedir = new TextInputDialog(cantidad(p.estimado()));
        pedir.setTitle("Retirar por caducidad");
        pedir.setHeaderText("«" + p.producto() + "» (caduca el " + p.fechaCaducidad().format(FECHA) + ")\n"
                + "¿Cuánto retiras del anaquel? Se descuenta del inventario como merma «Caducado».");
        pedir.setContentText("Cantidad:");
        Ventanas.estilizar(pedir);
        Optional<String> texto = pedir.showAndWait();
        if (texto.isEmpty()) return false;
        double q = CalculoCobro.parsearNumero(texto.get());
        if (Double.isNaN(q) || q <= 0) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Cantidad inválida", "Escribe una cantidad mayor a cero.");
            return false;
        }
        return conPermiso(auts -> caducidadService.retirar(p.caducidadId(), q, auts), "No se pudo retirar");
    }

    private boolean yaNoQueda(PorCaducar p) {
        if (!Ventanas.confirmar("Ya no queda", "¿Ya no queda nada de «" + p.producto() + "» que caduca el "
                + p.fechaCaducidad().format(FECHA) + "?\nDeja de aparecer en «Por caducar»; el inventario no cambia.")) {
            return false;
        }
        return conPermiso(auts -> caducidadService.marcarSinExistencia(p.caducidadId(), auts), "No se pudo marcar");
    }

    /** Permiso propio o autorización de administrador (AJUSTAR_INVENTARIO) */
    private boolean conPermiso(java.util.function.Consumer<List<Autorizacion>> accion, String tituloError) {
        List<Autorizacion> auts = List.of();
        if (!sesionService.tienePermiso(Permiso.AJUSTAR_INVENTARIO)) {
            Optional<Autorizacion> aut = Ventanas.pedirAutorizacion(autorizacionService, Permiso.AJUSTAR_INVENTARIO);
            if (aut.isEmpty()) return false;
            auts = List.of(aut.get());
        }
        try {
            accion.accept(auts);
            return true;
        } catch (NegocioException ex) {
            Ventanas.alerta(Alert.AlertType.ERROR, tituloError, ex.getMessage());
            return false;
        }
    }

    // ── Fechas al recibir una orden de compra ─────────────────────────────

    /**
     * Pide la fecha de caducidad (opcional) de las líneas cuyos productos la controlan.
     * @return id de línea → fecha (sin las que se dejaron vacías); vacío si se canceló;
     *         mapa vacío si ninguna línea controla caducidad (no se muestra nada)
     */
    public Optional<Map<Long, LocalDate>> pedirFechasDeOrden(Window owner, List<OrdenCompraLinea> lineas) {
        List<OrdenCompraLinea> conControl = lineas.stream()
                .filter(l -> l.getProducto() != null && l.getProducto().isControlaCaducidad() && l.getId() != null).toList();
        if (conControl.isEmpty()) return Optional.of(Map.of());
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Fechas de caducidad");
        dlg.setHeaderText("Estos productos controlan caducidad. Anota la fecha que trae el empaque\n"
                + "(opcional: déjala vacía si no la sabes).");
        if (owner != null) dlg.initOwner(owner);
        Ventanas.estilizar(dlg);
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        Map<OrdenCompraLinea, DatePicker> selectores = new LinkedHashMap<>();
        int fila = 0;
        for (OrdenCompraLinea l : conControl) {
            Label nombre = new Label(l.getProducto().getDescripcion() + " (" + cantidad(l.getCantidad()) + ")");
            nombre.setWrapText(true);
            nombre.setMaxWidth(300);
            DatePicker dp = selectorFecha();
            grid.add(nombre, 0, fila);
            grid.add(dp, 1, fila);
            selectores.put(l, dp);
            fila++;
        }
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(360, 44.0 * conControl.size() + 20));
        dlg.getDialogPane().setContent(scroll);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        while (true) {
            Optional<ButtonType> r = dlg.showAndWait();
            if (r.isEmpty() || r.get() != ButtonType.OK) return Optional.empty();
            try {
                Map<Long, LocalDate> fechas = new LinkedHashMap<>();
                for (Map.Entry<OrdenCompraLinea, DatePicker> e : selectores.entrySet()) {
                    LocalDate d = fechaDe(e.getValue());
                    if (d != null) {
                        CaducidadService.validarFecha(d, caducidadService.hoy());
                        fechas.put(e.getKey().getId(), d);
                    }
                }
                return Optional.of(fechas);
            } catch (NegocioException ex) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Fecha de caducidad", ex.getMessage());
            }
        }
    }

    static String cantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }
}
