package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.NivelDatos;
import me.eliassanchezfernandez.puntodeventa.model.ParProductos;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.ProductoClima;
import me.eliassanchezfernandez.puntodeventa.service.AgregadosService;
import me.eliassanchezfernandez.puntodeventa.service.ClimaService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Comparacion;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Totales;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.RecomendacionService;
import me.eliassanchezfernandez.puntodeventa.service.RecomendacionService.Sugerencia;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.ExportarCsv;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Function;

/**
 * Controlador de Estadísticas (CRT-10). Todo sale de los agregados que
 * mantiene AgregadosService (más "hoy" en vivo); aquí solo se presenta.
 *
 * main.fxml incluye esta vista para todos, pero MainController solo muestra
 * la pestaña a quien tiene VER_HISTORIAL; sin ese permiso no se consulta nada
 * en initialize(). No se muestran costos ni ganancias (no hace falta
 * VER_GANANCIAS); los totales sí, como en el historial de ventas.
 */
@Component
@Scope("prototype")
public class EstadisticasController implements Initializable {

    private static final Locale ES_MX = new Locale("es", "MX");
    private static final DateTimeFormatter FMT_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FMT_DIA   = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FMT_HORA  = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final String[] DIAS = {"Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"};
    private static final String[] CLASES_NIVEL = {"badge-warning", "badge-accent", "badge-success", "badge-danger"};
    private static final int TOP_PRODUCTOS = 25;

    @Autowired private SesionService        sesionService;
    @Autowired private EstadisticasService  estadisticasService;
    @Autowired private AgregadosService     agregadosService;
    @Autowired private RecomendacionService recomendacionService;
    @Autowired private ClimaService         climaService;
    @Autowired private ConfiguracionService configuracionService;

    // Cabecera
    @FXML private Label  lblNivel;
    @FXML private Label  lblNivelMensaje;
    @FXML private Label  lblActualizado;
    @FXML private Button btnRecalcular;

    // Periodo
    @FXML private ToggleGroup  grupoPeriodo;
    @FXML private ToggleButton tgHoy;
    @FXML private ToggleButton tgSemana;
    @FXML private ToggleButton tgMes;
    @FXML private ToggleButton tgAnio;
    @FXML private ToggleButton tgPersonalizado;
    @FXML private HBox         boxPersonalizado;
    @FXML private DatePicker   dpDesde;
    @FXML private DatePicker   dpHasta;
    @FXML private Button       btnAplicar;
    @FXML private Label        lblPeriodo;

    // Resumen
    @FXML private Label lblTotal;
    @FXML private Label lblTransacciones;
    @FXML private Label lblTicketPromedio;
    @FXML private Label lblPiezas;
    @FXML private HBox  boxComparaciones;
    @FXML private Label lblTituloSerie;
    @FXML private LineChart<String, Number> chartVentasDia;
    @FXML private BarChart<String, Number>  chartDiaSemana;
    @FXML private Label lblDiaPico;

    // Horarios pico
    @FXML private BarChart<String, Number>  chartFranjas;
    @FXML private Label    lblFranjaPico;
    @FXML private GridPane gridCalor;

    // Productos
    @FXML private Label  lblProductosTitulo;
    @FXML private Button btnExportarProductos;
    @FXML private TableView<FilaProducto> tablaProductos;

    // Recomendaciones
    @FXML private Label lblNivelJuntos;
    @FXML private Label lblNivelDiaHora;
    @FXML private Label lblNivelClima;
    @FXML private Label lblMomento;
    @FXML private Label lblClimaMotivo;
    @FXML private ListView<String> listaJuntos;
    @FXML private ListView<String> listaDiaHora;
    @FXML private ListView<String> listaClima;
    @FXML private TableView<FilaCorrelacion> tablaCorrelacion;
    @FXML private Button btnExportarCorrelacion;

    // Clima
    @FXML private Label lblClimaIcono;
    @FXML private Label lblClimaTemperatura;
    @FXML private Label lblClimaCondicion;
    @FXML private Label lblClimaCiudad;
    @FXML private Label lblClimaActualizado;
    @FXML private Label lblClimaAviso;
    @FXML private TableView<FilaClima> tablaClima;
    @FXML private Button btnExportarClima;

    /** Periodo personalizado vigente (null = usar el botón seleccionado) */
    private Periodo personalizado;

    public record FilaProducto(int pos, String producto, String departamento, double piezas, double importe) { }
    public record FilaCorrelacion(String producto, String tipo, String clave, double piezas, double indice) { }
    public record FilaClima(String condicion, int ventas, double importe) { }

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configurarTablas();
        gridCalor.getChildren().clear();

        if (sesionService.tienePermiso(Permiso.VER_HISTORIAL)) {
            recargar();
        } else {
            quitarColorDeNivel();
            lblNivel.setText("—");
            lblNivelMensaje.setText("Requiere el permiso «Ver historial de ventas».");
            btnRecalcular.setDisable(true);
        }
    }

    // ── Acciones FXML ─────────────────────────────────────────────────────

    @FXML
    private void onPeriodo(ActionEvent e) {
        // Volver a pulsar el botón elegido no debe dejar el grupo sin selección
        if (grupoPeriodo.getSelectedToggle() == null && e.getSource() instanceof ToggleButton tb) tb.setSelected(true);
        boolean personal = grupoPeriodo.getSelectedToggle() == tgPersonalizado;
        boxPersonalizado.setVisible(personal);
        boxPersonalizado.setManaged(personal);
        if (personal) {
            if (dpDesde.getValue() == null) dpDesde.setValue(estadisticasService.hoy().minusDays(6));
            if (dpHasta.getValue() == null) dpHasta.setValue(estadisticasService.hoy());
            onAplicarPersonalizado();
            return;
        }
        personalizado = null;
        cargarPeriodo();
    }

    @FXML
    private void onAplicarPersonalizado() {
        try {
            personalizado = Periodo.entre(dpDesde.getValue(), dpHasta.getValue());
        } catch (NegocioException e) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Periodo", e.getMessage());
            return;
        }
        tgPersonalizado.setSelected(true);
        cargarPeriodo();
    }

    /** Rehace todos los agregados en segundo plano y vuelve a cargar la pantalla */
    @FXML
    private void onRecalcular() {
        btnRecalcular.setDisable(true);
        lblActualizado.setText("Recalculando…");
        Thread t = new Thread(() -> {
            String error = null;
            try {
                agregadosService.recalcularManual();
            } catch (RuntimeException e) {
                e.printStackTrace();
                error = e.getMessage();
            }
            final String mensaje = error;
            Platform.runLater(() -> {
                btnRecalcular.setDisable(false);
                if (mensaje != null) {
                    Ventanas.alerta(Alert.AlertType.ERROR, "Recalcular", "No se pudieron recalcular las estadísticas: " + mensaje);
                }
                recargar();
            });
        }, "estadisticas-recalcular");
        t.setDaemon(true);
        t.start();
    }

    @FXML private void onExportarProductos()   { exportar(tablaProductos, "productos-mas-vendidos"); }
    @FXML private void onExportarCorrelacion() { exportar(tablaCorrelacion, "correlacion-clima"); }
    @FXML private void onExportarClima()       { exportar(tablaClima, "ventas-por-clima"); }

    // ── Carga ─────────────────────────────────────────────────────────────

    /** Número de la última carga pedida: una respuesta vieja (de otro periodo) se descarta. Solo el hilo de FX lo toca. */
    private int generacionCarga = 0;

    /** Lo que se lee de la base de datos para el periodo (en segundo plano) */
    private record DatosPeriodo(Totales totales, List<Comparacion> comparaciones, Map<LocalDate, Double> serie,
                                double[] porDia, Map<Franja, Totales> porFranja, double[][] mapa,
                                List<EstadisticasService.ProductoTop> top, List<EstadisticasService.VentasClima> porClima) { }

    /** Lo que se lee para las recomendaciones, el nivel y el clima de ahora */
    private record DatosGenerales(NivelDatos nivel, Optional<LocalDateTime> ultimaActualizacion, Optional<ClimaRegistro> clima,
                                  Configuracion configuracion, List<ParProductos> pares, List<Sugerencia> momento,
                                  List<ProductoClima> correlacion) { }

    /**
     * Recalcula todo lo visible (lo llama MainController al entrar a la pestaña).
     * Las consultas corren en un hilo aparte y solo se pinta en el de JavaFX:
     * en un disco lento la pantalla no se congela.
     */
    public void recargar() {
        if (!sesionService.tienePermiso(Permiso.VER_HISTORIAL)) return;
        final int gen = ++generacionCarga;
        final Periodo p = periodoActual();
        lblActualizado.setText("Cargando…");
        Thread t = new Thread(() -> {
            DatosGenerales g = null;
            DatosPeriodo d = null;
            try { g = leerGenerales(); } catch (RuntimeException e) { e.printStackTrace(); }
            try { d = leerPeriodo(p); } catch (RuntimeException e) { e.printStackTrace(); }
            final DatosGenerales gf = g;
            final DatosPeriodo df = d;
            Platform.runLater(() -> {
                if (gen != generacionCarga) return;
                pintarNivel(gf);
                pintarPeriodo(p, df);
                pintarRecomendaciones(gf);
                pintarClima(gf);
            });
        }, "estadisticas-cargar");
        t.setDaemon(true);
        t.start();
    }

    private DatosGenerales leerGenerales() {
        NivelDatos nivel = estadisticasService.nivelDatosActual();
        Optional<ClimaRegistro> clima = climaService.actual();
        Optional<LocalDateTime> ultima = agregadosService.ultimaActualizacion();
        Configuracion cfg = configuracionService.obtener();
        List<ParProductos> pares = estadisticasService.paresTop(12);
        List<Sugerencia> momento = recomendacionService.sugerir(List.of(), LocalDateTime.now(), clima.orElse(null), 8, nivel);
        List<ProductoClima> correlacion = estadisticasService.correlacionClima();
        return new DatosGenerales(nivel, ultima, clima, cfg, pares, momento, correlacion);
    }

    private DatosPeriodo leerPeriodo(Periodo p) {
        return new DatosPeriodo(estadisticasService.totales(p), estadisticasService.comparaciones(),
                estadisticasService.serieDiaria(p), estadisticasService.porDiaSemana(p),
                estadisticasService.porFranja(p), estadisticasService.mapaCalor(p),
                estadisticasService.topProductos(p, TOP_PRODUCTOS), estadisticasService.ventasPorClima(p));
    }

    private void pintarNivel(DatosGenerales g) {
        if (g == null) {
            // Un dato inconsistente no debe impedir abrir la ventana principal
            quitarColorDeNivel();
            lblNivel.setText("—");
            lblNivelMensaje.setText("No se pudo calcular el nivel de datos.");
            lblActualizado.setText("");
            return;
        }
        mostrarNivel(g.nivel());
        lblActualizado.setText(g.ultimaActualizacion()
                .map(f -> "Agregados: " + f.format(FMT_HORA)).orElse("Agregados: sin calcular"));
    }

    private Periodo periodoActual() {
        LocalDate hoy = estadisticasService.hoy();
        Object t = grupoPeriodo.getSelectedToggle();
        if (t == tgPersonalizado && personalizado != null) return personalizado;
        if (t == tgSemana) return Periodo.semana(hoy);
        if (t == tgMes)    return Periodo.mes(hoy);
        if (t == tgAnio)   return Periodo.anio(hoy);
        return Periodo.dia(hoy);
    }

    /** Resumen, horarios, productos y ventas por clima del periodo elegido (consulta en segundo plano) */
    private void cargarPeriodo() {
        if (!sesionService.tienePermiso(Permiso.VER_HISTORIAL)) return;
        final int gen = ++generacionCarga;
        final Periodo p = periodoActual();
        lblPeriodo.setText(etiquetaDe(p));
        Thread t = new Thread(() -> {
            DatosPeriodo d = null;
            String error = null;
            try {
                d = leerPeriodo(p);
            } catch (RuntimeException e) {
                e.printStackTrace();
                error = e.getMessage();
            }
            final DatosPeriodo df = d;
            final String mensaje = error;
            Platform.runLater(() -> {
                if (gen != generacionCarga) return;
                if (df == null) {
                    Ventanas.alerta(Alert.AlertType.ERROR, "Estadísticas",
                            "No se pudieron cargar las estadísticas: " + mensaje);
                    return;
                }
                pintarPeriodo(p, df);
            });
        }, "estadisticas-periodo");
        t.setDaemon(true);
        t.start();
    }

    private static String etiquetaDe(Periodo p) {
        return p.desde().equals(p.hasta()) ? p.desde().format(FMT_FECHA)
                : p.desde().format(FMT_FECHA) + " – " + p.hasta().format(FMT_FECHA);
    }

    private void pintarPeriodo(Periodo p, DatosPeriodo d) {
        lblPeriodo.setText(etiquetaDe(p));
        if (d == null) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Estadísticas", "No se pudieron cargar las estadísticas.");
            return;
        }
        Totales t = d.totales();
        lblTotal.setText(Moneda.formato(t.total()));
        lblTransacciones.setText(String.valueOf(t.transacciones()));
        lblTicketPromedio.setText(Moneda.formato(t.ticketPromedio()));
        lblPiezas.setText(cantidad(t.piezas()));

        mostrarComparaciones(d.comparaciones());
        mostrarSerie(p, d.serie());
        mostrarDiaSemana(d.porDia());
        mostrarFranjas(d.porFranja());
        construirMapaDeCalor(d.mapa());
        mostrarProductos(p, d.top());
        mostrarVentasPorClima(d.porClima());
    }

    private void mostrarComparaciones(List<Comparacion> comparaciones) {
        boxComparaciones.getChildren().clear();
        for (Comparacion c : comparaciones) {
            VBox tarjeta = new VBox(6);
            tarjeta.getStyleClass().add("card");
            tarjeta.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tarjeta, Priority.ALWAYS);

            Label titulo = new Label(c.etiqueta());
            titulo.getStyleClass().add("metric-label");
            Label badge = new Label();
            badge.getStyleClass().add("badge");
            Double pct = c.variacionPct();
            if (pct == null) {
                badge.setText("Sin referencia");
            } else {
                badge.setText(String.format("%s %.1f %%", pct >= 0 ? "▲" : "▼", Math.abs(pct)));
                badge.getStyleClass().add(pct >= 0 ? "badge-success" : "badge-danger");
            }
            Label montos = new Label(Moneda.formato(c.actual().total()) + "  vs  " + Moneda.formato(c.anterior().total()));
            montos.getStyleClass().add("text-muted");
            Label detalle = new Label(c.detalle() + (pct == null ? " (sin ventas en el periodo anterior)" : ""));
            detalle.getStyleClass().add("hint");
            detalle.setWrapText(true);
            tarjeta.getChildren().addAll(titulo, badge, montos, detalle);
            boxComparaciones.getChildren().add(tarjeta);
        }
    }

    /** Hasta ~2 meses se grafica por día; más largo, por mes */
    private void mostrarSerie(Periodo p, Map<LocalDate, Double> serie) {
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        if (p.dias() <= 62) {
            lblTituloSerie.setText("Ventas por día");
            serie.forEach((d, v) -> s.getData().add(new XYChart.Data<>(d.format(FMT_DIA), v)));
        } else {
            lblTituloSerie.setText("Ventas por mes");
            Map<String, Double> porMes = new java.util.LinkedHashMap<>();
            serie.forEach((d, v) -> porMes.merge(
                    d.getMonth().getDisplayName(TextStyle.SHORT, ES_MX) + " " + d.getYear(), v, Double::sum));
            porMes.forEach((m, v) -> s.getData().add(new XYChart.Data<>(m, v)));
        }
        chartVentasDia.getData().setAll(List.of(s));
    }

    private void mostrarDiaSemana(double[] porDia) {
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        for (int i = 0; i < 7; i++) s.getData().add(new XYChart.Data<>(DIAS[i], porDia[i]));
        chartDiaSemana.getData().setAll(List.of(s));
        DayOfWeek pico = EstadisticasService.diaConMasVentas(porDia);
        lblDiaPico.setText(pico == null ? "Sin ventas en el periodo."
                : "Día con más ventas: " + capitalizar(pico.getDisplayName(TextStyle.FULL, ES_MX))
                  + " (" + Moneda.formato(porDia[pico.getValue() - 1]) + ")");
    }

    private void mostrarFranjas(Map<Franja, Totales> porFranja) {
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        Franja pico = null;
        for (Franja f : Franja.values()) {
            Totales t = porFranja.get(f);
            if (f == Franja.MADRUGADA && t.total() <= 0) continue;   // solo si hubo ventas de madrugada
            s.getData().add(new XYChart.Data<>(f.getEtiquetaConHoras(), t.total()));
            if (t.total() > 0 && (pico == null || t.total() > porFranja.get(pico).total())) pico = f;
        }
        chartFranjas.getData().setAll(List.of(s));
        lblFranjaPico.setText(pico == null ? "Sin ventas en el periodo."
                : "Franja pico: " + pico.getEtiquetaConHoras() + " (" + Moneda.formato(porFranja.get(pico).total())
                  + ", " + porFranja.get(pico).transacciones() + " tickets)");
    }

    /** Encabezados y 7 × franjas celdas de color; la intensidad es relativa al máximo */
    private void construirMapaDeCalor(double[][] mapa) {
        gridCalor.getChildren().clear();
        Franja[] franjas = Franja.values();
        boolean hayMadrugada = false;
        double maximo = 0;
        for (double[] fila : mapa) {
            for (int f = 0; f < franjas.length; f++) {
                maximo = Math.max(maximo, fila[f]);
                if (franjas[f] == Franja.MADRUGADA && fila[f] > 0) hayMadrugada = true;
            }
        }
        int columnas = hayMadrugada ? franjas.length : franjas.length - 1;
        for (int f = 0; f < columnas; f++) {
            Label encabezado = new Label(franjas[f].getEtiquetaConHoras());
            encabezado.getStyleClass().add("text-small");
            gridCalor.add(encabezado, f + 1, 0);
        }
        for (int d = 0; d < 7; d++) {
            Label dia = new Label(DIAS[d]);
            dia.getStyleClass().add("text-muted");
            gridCalor.add(dia, 0, d + 1);
            for (int f = 0; f < columnas; f++) {
                double v = mapa[d][f];
                Label celda = new Label(v > 0 ? Moneda.formato(v) : "");
                celda.getStyleClass().addAll("heat-cell", "heat-" + EstadisticasService.intensidad(v, maximo));
                if (EstadisticasService.intensidad(v, maximo) == 3) celda.getStyleClass().add("heat-texto-claro");
                celda.setTooltip(new Tooltip(DIAS[d] + " · " + franjas[f].getEtiquetaConHoras() + ": " + Moneda.formato(v)));
                gridCalor.add(celda, f + 1, d + 1);
            }
        }
    }

    private void mostrarProductos(Periodo p, List<EstadisticasService.ProductoTop> top) {
        List<FilaProducto> filas = new ArrayList<>();
        int pos = 1;
        for (EstadisticasService.ProductoTop t : top) {
            filas.add(new FilaProducto(pos++, t.producto().getDescripcion(),
                    t.producto().getDepartamento() != null ? t.producto().getDepartamento().getNombre() : "—",
                    t.piezas(), t.importe()));
        }
        tablaProductos.setItems(FXCollections.observableArrayList(filas));
        lblProductosTitulo.setText("Ranking por importe · " + p.etiqueta());
    }

    private void mostrarVentasPorClima(List<EstadisticasService.VentasClima> porClimaVentas) {
        List<FilaClima> filas = new ArrayList<>();
        for (EstadisticasService.VentasClima vc : porClimaVentas) {
            filas.add(new FilaClima(vc.categoria(), vc.ventas(), vc.importe()));
        }
        tablaClima.setItems(FXCollections.observableArrayList(filas));
    }

    /** Pares, sugerencias de este momento y correlación con el clima */
    private void pintarRecomendaciones(DatosGenerales g) {
        if (g == null) {
            listaJuntos.setPlaceholder(new Label("No se pudieron cargar las recomendaciones."));
            return;
        }
        try {
            NivelDatos nivel = g.nivel();
            Optional<ClimaRegistro> clima = g.clima();
            LocalDateTime ahora = LocalDateTime.now();

            // Se compran juntos
            List<String> juntos = new ArrayList<>();
            for (ParProductos par : g.pares()) {
                juntos.add(String.format("%s + %s — %d veces · confianza %.0f %% · lift %.1f",
                        par.getProductoA().getDescripcion(), par.getProductoB().getDescripcion(),
                        par.getCoOcurrencias(), par.getConfianza() * 100, par.getLift()));
            }
            listaJuntos.setItems(FXCollections.observableArrayList(juntos));
            listaJuntos.setPlaceholder(new Label(nivel == NivelDatos.SIN_DATOS
                    ? "Aún no hay ventas." : "Aún no hay pares repetidos en las últimas 4 semanas."));
            etiquetaNivel(lblNivelJuntos, nivel);

            // Para este momento (ticket vacío)
            List<String> momento = new ArrayList<>();
            for (Sugerencia s : g.momento()) {
                momento.add(s.producto().getDescripcion() + " — " + s.motivo() + " (confianza " + s.nivelConfianza().toLowerCase(ES_MX) + ")");
            }
            listaDiaHora.setItems(FXCollections.observableArrayList(momento));
            listaDiaHora.setPlaceholder(new Label("Sin sugerencias para este momento."));
            lblMomento.setText(String.format("%s, %s%s.",
                    capitalizar(ahora.getDayOfWeek().getDisplayName(TextStyle.FULL, ES_MX)),
                    Franja.de(ahora).getEtiquetaConHoras().toLowerCase(ES_MX),
                    clima.map(c -> ", " + c.getDescripcion().toLowerCase(ES_MX)).orElse(", sin datos de clima")));
            etiquetaNivel(lblNivelDiaHora, nivel);

            // Según el clima: correlación aprendida para el clima de ahora
            List<ProductoClima> correlacion = g.correlacion();
            List<String> porClima = new ArrayList<>();
            if (clima.isPresent()) {
                for (ProductoClima pc : correlacion) {
                    boolean aplica = (pc.getTipo() == ProductoClima.Tipo.CONDICION && pc.getClave().equals(clima.get().getCategoria()))
                                  || (pc.getTipo() == ProductoClima.Tipo.BANDA && pc.getClave().equals(clima.get().getBanda()));
                    if (aplica && pc.getIndice() > 1.0) {
                        porClima.add(String.format("%s — %s · índice %.1f (%s piezas)", pc.getProducto().getDescripcion(),
                                pc.getClave(), pc.getIndice(), cantidad(pc.getPiezas())));
                    }
                    if (porClima.size() >= 10) break;
                }
                lblClimaMotivo.setText("Ahora: " + clima.get().getDescripcion() + ". "
                        + (porClima.isEmpty() ? "Aún no hay correlación aprendida; se usan reglas generales (caliente con lluvia o frío, fresco con calor)."
                                              : "Productos que pesan más de lo normal con este clima."));
            } else {
                lblClimaMotivo.setText("Sin datos de clima: las sugerencias usan día, hora y canasta.");
            }
            listaClima.setItems(FXCollections.observableArrayList(porClima));
            listaClima.setPlaceholder(new Label(clima.isPresent() ? "Sin correlación aprendida todavía." : "Sin datos de clima"));
            etiquetaNivel(lblNivelClima, clima.isPresent() ? nivel : null);

            List<FilaCorrelacion> filas = new ArrayList<>();
            for (ProductoClima pc : correlacion) {
                filas.add(new FilaCorrelacion(pc.getProducto().getDescripcion(),
                        pc.getTipo() == ProductoClima.Tipo.CONDICION ? "Condición" : "Temperatura",
                        pc.getClave(), pc.getPiezas(), pc.getIndice()));
            }
            tablaCorrelacion.setItems(FXCollections.observableArrayList(filas));
        } catch (RuntimeException e) {
            e.printStackTrace();
            listaJuntos.setPlaceholder(new Label("No se pudieron cargar las recomendaciones."));
        }
    }

    private void pintarClima(DatosGenerales g) {
        if (g == null) {
            lblClimaCondicion.setText("Sin datos de clima");
            return;
        }
        try {
            Configuracion c = g.configuracion();
            Optional<ClimaRegistro> clima = g.clima();
            lblClimaCiudad.setText("Ciudad: " + (c.getCiudad() != null ? c.getCiudad() : "—"));
            if (clima.isPresent()) {
                ClimaRegistro r = clima.get();
                lblClimaIcono.setText(ClimaService.iconoDe(r.getCategoria()));
                lblClimaTemperatura.setText(Math.round(r.getTemperatura()) + " °C");
                lblClimaCondicion.setText(r.getCategoria() + " · " + r.getBanda());
                lblClimaActualizado.setText("Registrado: " + r.getFechaHora().format(FMT_HORA));
            } else {
                lblClimaIcono.setText("☁");
                lblClimaTemperatura.setText("—");
                lblClimaCondicion.setText("Sin datos de clima");
                lblClimaActualizado.setText(c.isClimaActivo()
                        ? "Sin conexión al servicio de clima; se reintenta cada hora."
                        : "El clima está desactivado (Configuración → Ubicación y clima).");
            }
            tablaClima.setPlaceholder(new Label("Sin ventas en el periodo."));
        } catch (RuntimeException e) {
            e.printStackTrace();
            lblClimaCondicion.setText("Sin datos de clima");
        }
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    private void configurarTablas() {
        tablaProductos.getColumns().setAll(
                columna("Pos.",         60,  true,  f -> String.valueOf(f.pos())),
                columna("Producto",     260, false, FilaProducto::producto),
                columna("Departamento", 160, false, FilaProducto::departamento),
                columna("Piezas",       90,  true,  f -> cantidad(f.piezas())),
                columna("Importe",      120, true,  f -> Moneda.formato(f.importe())));
        tablaProductos.setPlaceholder(new Label("Sin ventas en el periodo."));

        tablaCorrelacion.getColumns().setAll(
                columna("Producto",  260, false, FilaCorrelacion::producto),
                columna("Tipo",      110, false, FilaCorrelacion::tipo),
                columna("Clima",     120, false, FilaCorrelacion::clave),
                columna("Piezas",    90,  true,  f -> cantidad(f.piezas())),
                columna("Índice",    90,  true,  f -> String.format("%.2f", f.indice())));
        tablaCorrelacion.setPlaceholder(new Label("Sin correlación: hace falta clima registrado junto a las ventas."));

        tablaClima.getColumns().setAll(
                columna("Condición", 200, false, FilaClima::condicion),
                columna("Ventas",    100, true,  f -> String.valueOf(f.ventas())),
                columna("Importe",   140, true,  f -> Moneda.formato(f.importe())));
        tablaClima.setPlaceholder(new Label("Sin ventas en el periodo."));
    }

    private void mostrarNivel(NivelDatos nivel) {
        quitarColorDeNivel();
        String clase = claseNivel(nivel);
        if (clase != null) lblNivel.getStyleClass().add(clase);
        lblNivel.setText(nivel.getEtiqueta());
        lblNivelMensaje.setText(nivel.getMensaje());
    }

    private static String claseNivel(NivelDatos nivel) {
        if (nivel == null) return null;
        return switch (nivel) {
            case ENTRENAMIENTO -> "badge-warning";
            case PARCIAL       -> "badge-accent";
            case SUFICIENTE    -> "badge-success";
            case SIN_DATOS     -> null;
        };
    }

    /** Insignia de confianza de un bloque de recomendaciones (null = sin datos de clima) */
    private static void etiquetaNivel(Label etiqueta, NivelDatos nivel) {
        etiqueta.getStyleClass().removeAll(CLASES_NIVEL);
        if (nivel == null) {
            etiqueta.setText("Sin datos de clima");
            return;
        }
        etiqueta.setText(nivel.getEtiqueta());
        String clase = claseNivel(nivel);
        if (clase != null) etiqueta.getStyleClass().add(clase);
    }

    private void quitarColorDeNivel() {
        lblNivel.getStyleClass().removeAll(CLASES_NIVEL);
    }

    /** Exporta la tabla visible a CSV (FileChooser) */
    private <T> void exportar(TableView<T> tabla, String nombreBase) {
        if (tabla.getItems().isEmpty()) {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Exportar CSV", "No hay filas que exportar.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Exportar CSV");
        fc.setInitialFileName(nombreBase + "-" + LocalDate.now() + ".csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv"));
        File destino = fc.showSaveDialog(tabla.getScene().getWindow());
        if (destino == null) return;

        List<String> encabezados = new ArrayList<>();
        for (TableColumn<T, ?> c : tabla.getColumns()) encabezados.add(c.getText());
        List<List<String>> filas = new ArrayList<>();
        for (T item : tabla.getItems()) {
            List<String> fila = new ArrayList<>();
            for (TableColumn<T, ?> c : tabla.getColumns()) {
                Object v = c.getCellObservableValue(item) != null ? c.getCellObservableValue(item).getValue() : null;
                fila.add(v == null ? "" : v.toString());
            }
            filas.add(fila);
        }
        try {
            ExportarCsv.escribir(destino.toPath(), encabezados, filas);
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Exportar CSV", "Se exportaron " + filas.size() + " filas a:\n" + destino);
        } catch (IOException e) {
            Ventanas.alerta(Alert.AlertType.ERROR, "Exportar CSV", "No se pudo escribir el archivo: " + e.getMessage());
        }
    }

    private static <T> TableColumn<T, String> columna(String titulo, double ancho, boolean numerica,
                                                      Function<T, String> valor) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setPrefWidth(ancho);
        c.setCellValueFactory(d -> new SimpleStringProperty(valor.apply(d.getValue())));
        if (numerica) c.getStyleClass().add("align-right");
        return c;
    }

    private static String cantidad(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.3f", v).replaceAll("0+$", "");
    }

    private static String capitalizar(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
