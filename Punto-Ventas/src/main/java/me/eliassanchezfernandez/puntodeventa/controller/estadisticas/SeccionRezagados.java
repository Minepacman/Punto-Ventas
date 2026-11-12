package me.eliassanchezfernandez.puntodeventa.controller.estadisticas;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.ProductoRezagado;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.Rezagados;
import me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sección «Productos rezagados»: existencia sin venta en N días o con
 * rotación muy baja, con el dinero detenido. N se elige en la tarjeta y
 * recarga solo esta sección.
 */
public final class SeccionRezagados implements SeccionEstadisticas<Rezagados> {

    public static final String CLAVE = "rezagados";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record Fila(String producto, String departamento, double existencia, String ultimaVenta, long diasSinVenta,
                       double vendidos, String cobertura, double valorVenta, double valorCosto, String motivo) { }

    private final ReabastecimientoService reabastecimientoService;
    private Acciones acciones;
    private ComboBox<Integer> cmbDias;
    private Label lblResumen;
    private TableView<Fila> tabla;
    private TableColumn<Fila, String> colCosto;
    private int dias = ReabastecimientoService.DIAS_SIN_VENTA;

    public SeccionRezagados(ReabastecimientoService reabastecimientoService) {
        this.reabastecimientoService = reabastecimientoService;
    }

    @Override public String clave() { return CLAVE; }
    @Override public Ubicacion ubicacion() { return Ubicacion.REABASTECIMIENTO; }

    @Override
    public Node construir(Acciones acciones) {
        this.acciones = acciones;
        cmbDias = new ComboBox<>(FXCollections.observableArrayList(7, 15, 30, 45, 60, 90, 180));
        cmbDias.setValue(dias);
        cmbDias.setPrefWidth(90);
        cmbDias.setOnAction(e -> {
            if (cmbDias.getValue() != null && cmbDias.getValue() != dias) {
                dias = cmbDias.getValue();
                acciones.recargar(this);
            }
        });
        Label lblDias = new Label("Sin venta en (días):");
        lblDias.getStyleClass().add("text-muted");
        Button exportar = new Button("Exportar…");
        exportar.setOnAction(e -> ExportadorTablas.exportar(tabla, "productos-rezagados",
                "Productos rezagados — sin venta en " + dias + " días"));
        HBox cabecera = Tarjetas.cabecera("Productos rezagados (inventario inmovilizado)", CLAVE, acciones, lblDias, cmbDias, exportar);

        lblResumen = new Label("Cargando…");
        lblResumen.getStyleClass().add("subtitle");
        lblResumen.setWrapText(true);

        tabla = new TableView<>();
        colCosto = Tarjetas.columna("Valor al costo", 110, true, f -> Moneda.formato(f.valorCosto()));
        tabla.getColumns().setAll(List.of(
                Tarjetas.columna("Producto",        230, false, Fila::producto),
                Tarjetas.columna("Departamento",    120, false, Fila::departamento),
                Tarjetas.columna("Existencia",      80,  true,  f -> Tarjetas.cantidad(f.existencia())),
                Tarjetas.columna("Última venta",    95,  false, Fila::ultimaVenta),
                Tarjetas.columna("Días sin venta",  95,  true,  f -> String.valueOf(f.diasSinVenta())),
                Tarjetas.columna("Vendidos 30 d",   90,  true,  f -> Tarjetas.cantidad(f.vendidos())),
                Tarjetas.columna("Cobertura (días)", 100, true, Fila::cobertura),
                Tarjetas.columna("Valor a precio",  110, true,  f -> Moneda.formato(f.valorVenta())),
                colCosto,
                Tarjetas.columna("Motivo",          230, false, Fila::motivo)));
        tabla.setPlaceholder(new Label("Ningún producto rezagado con ese criterio."));
        tabla.setPrefHeight(300);

        VBox card = Tarjetas.tarjeta(cabecera, lblResumen, tabla);
        VBox.setVgrow(tabla, javafx.scene.layout.Priority.ALWAYS);
        return card;
    }

    @Override
    public Rezagados cargar(ContextoEstadisticas contexto) {
        return reabastecimientoService.productosRezagados(dias);
    }

    @Override
    public void pintar(Rezagados r) {
        List<Fila> filas = new ArrayList<>();
        for (ProductoRezagado p : r.productos()) {
            filas.add(new Fila(p.producto().getDescripcion(),
                    p.producto().getDepartamento() != null ? p.producto().getDepartamento().getNombre() : "—",
                    p.existencia(), p.ultimaVenta() != null ? p.ultimaVenta().format(FMT) : "Nunca",
                    p.diasSinVenta(), p.piezasVentana(), Tarjetas.dias(p.diasCobertura()),
                    p.valorVenta(), p.valorCosto(), p.motivo()));
        }
        tabla.setItems(FXCollections.observableArrayList(filas));
        colCosto.setVisible(r.conCostos());
        if (filas.isEmpty()) {
            lblResumen.setText("Ningún producto con existencia lleva " + r.diasSinVenta()
                    + " días o más sin venderse, ni tiene existencia para más de "
                    + ReabastecimientoService.COBERTURA_EXCESIVA_DIAS + " días.");
        } else {
            lblResumen.setText(filas.size() + " producto(s) · " + Moneda.formato(r.totalValorVenta()) + " a precio de venta"
                    + (r.conCostos() ? " · " + Moneda.formato(r.totalValorCosto()) + " al costo" : "")
                    + ". Sin venta en " + r.diasSinVenta() + " días o con existencia para más de "
                    + ReabastecimientoService.COBERTURA_EXCESIVA_DIAS + " días.");
        }
        Tarjetas.ajustarAlto(tabla, filas.size(), 120, 420);
    }

    @Override
    public void pintarError(String mensaje) {
        lblResumen.setText("No se pudieron calcular los productos rezagados: " + mensaje);
        tabla.getItems().clear();
    }
}
