package me.eliassanchezfernandez.puntodeventa.controller.estadisticas;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.GrupoSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.LineaSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.PlanSurtido;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService.Urgencia;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.ExportadorTablas;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sección «Frecuencia de surtido y pedido sugerido por proveedor»: un bloque
 * por proveedor (o por departamento sin proveedor) con la venta diaria, la
 * cobertura, cada cuánto surtir y cuánto pedir de cada producto, y el botón
 * para crear la orden de compra en borrador.
 */
public final class SeccionSurtido implements SeccionEstadisticas<PlanSurtido> {

    public static final String CLAVE = "surtido";

    public record Fila(String grupo, String producto, double ventaDiaria, double existencia, double minimo,
                       String cobertura, String frecuencia, double pedido, String urgencia, double costo, String motivo) { }

    private final ReabastecimientoService reabastecimientoService;
    private final ProveedorService proveedorService;
    private final SesionService sesionService;
    private Acciones acciones;
    private Label lblResumen;
    private CheckBox chkSoloPedir;
    private VBox boxGrupos;
    private TableView<Fila> tablaExportar;
    private PlanSurtido plan;

    public SeccionSurtido(ReabastecimientoService reabastecimientoService, ProveedorService proveedorService,
                          SesionService sesionService) {
        this.reabastecimientoService = reabastecimientoService;
        this.proveedorService = proveedorService;
        this.sesionService = sesionService;
    }

    @Override public String clave() { return CLAVE; }
    @Override public Ubicacion ubicacion() { return Ubicacion.REABASTECIMIENTO; }

    @Override
    public Node construir(Acciones acciones) {
        this.acciones = acciones;
        chkSoloPedir = new CheckBox("Solo lo que hay que pedir");
        chkSoloPedir.setSelected(true);
        chkSoloPedir.setOnAction(e -> { if (plan != null) pintar(plan); });
        Button exportar = new Button("Exportar…");
        exportar.setOnAction(e -> ExportadorTablas.exportar(tablaExportar, "pedido-sugerido",
                "Pedido sugerido por proveedor" + (chkSoloPedir.isSelected() ? " (solo lo que hay que pedir)" : "")));
        HBox cabecera = Tarjetas.cabecera("Frecuencia de surtido y pedido sugerido por proveedor", CLAVE, acciones,
                chkSoloPedir, exportar);

        lblResumen = new Label("Cargando…");
        lblResumen.getStyleClass().add("subtitle");
        lblResumen.setWrapText(true);
        boxGrupos = new VBox(8);
        tablaExportar = new TableView<>();
        tablaExportar.getColumns().setAll(columnas(true));
        return Tarjetas.tarjeta(cabecera, lblResumen, boxGrupos);
    }

    private List<TableColumn<Fila, String>> columnas(boolean conGrupo) {
        List<TableColumn<Fila, String>> c = new ArrayList<>();
        if (conGrupo) c.add(Tarjetas.columna("Proveedor", 150, false, Fila::grupo));
        c.add(Tarjetas.columna("Producto",         230, false, Fila::producto));
        c.add(Tarjetas.columna("Venta diaria",     90,  true,  f -> Tarjetas.cantidad(f.ventaDiaria())));
        c.add(Tarjetas.columna("Existencia",       80,  true,  f -> Tarjetas.cantidad(f.existencia())));
        c.add(Tarjetas.columna("Mínimo",           70,  true,  f -> Tarjetas.cantidad(f.minimo())));
        c.add(Tarjetas.columna("Alcanza (días)",   95,  true,  Fila::cobertura));
        c.add(Tarjetas.columna("Surtir cada (días)", 110, true, Fila::frecuencia));
        c.add(Tarjetas.columna("Pedir",            70,  true,  f -> f.pedido() > 0 ? Tarjetas.cantidad(f.pedido()) : "—"));
        c.add(Tarjetas.columna("Urgencia",         150, false, Fila::urgencia));
        if (plan == null || plan.conCostos()) c.add(Tarjetas.columna("Costo del pedido", 110, true, f -> f.pedido() > 0 ? Moneda.formato(f.costo()) : "—"));
        c.add(Tarjetas.columna("Motivo",           230, false, Fila::motivo));
        return c;
    }

    @Override
    public PlanSurtido cargar(ContextoEstadisticas contexto) {
        return reabastecimientoService.planSurtido();
    }

    @Override
    public void pintar(PlanSurtido p) {
        this.plan = p;
        boolean soloPedir = chkSoloPedir.isSelected();
        boolean puedeOrdenar = sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES);
        boxGrupos.getChildren().clear();
        List<Fila> todas = new ArrayList<>();
        int gruposMostrados = 0;
        for (GrupoSurtido g : p.grupos()) {
            List<LineaSurtido> lineas = soloPedir ? g.aPedir() : g.lineas();
            if (lineas.isEmpty()) continue;
            gruposMostrados++;
            List<Fila> filas = new ArrayList<>();
            for (LineaSurtido l : lineas) filas.add(fila(g, l));
            todas.addAll(filas);

            TableView<Fila> tabla = new TableView<>(FXCollections.observableArrayList(filas));
            tabla.getColumns().setAll(columnas(false));
            tabla.setPlaceholder(new Label("Nada que pedir por ahora."));
            tabla.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
                @Override protected void updateItem(Fila f, boolean vacio) {
                    super.updateItem(f, vacio);
                    getStyleClass().removeAll("fila-agotado", "fila-stock-bajo");
                    if (vacio || f == null) return;
                    if (Urgencia.AGOTADO.getEtiqueta().equals(f.urgencia())) getStyleClass().add("fila-agotado");
                    else if (Urgencia.BAJO_MINIMO.getEtiqueta().equals(f.urgencia())) getStyleClass().add("fila-stock-bajo");
                }
            });
            Tarjetas.ajustarAlto(tabla, filas.size(), 90, 320);

            Button crear = new Button("Crear orden de compra en borrador…");
            crear.getStyleClass().add("button-primary");
            crear.setDisable(!puedeOrdenar || g.productosAPedir() == 0);
            crear.setOnAction(e -> crearOrden(g, crear));
            Label nota = new Label(!puedeOrdenar ? "Requiere el permiso «Gestionar proveedores y órdenes de compra»."
                    : g.productosAPedir() == 0 ? "Nada que pedir por ahora."
                    : g.tieneProveedor() ? "Se guarda como borrador con " + g.productosAPedir() + " producto(s); revísala en Inventario → Órdenes de compra."
                    : "Este grupo no tiene proveedor: al crear la orden se te pedirá elegir uno.");
            nota.getStyleClass().add("hint");
            nota.setWrapText(true);
            HBox pie = new HBox(10, crear, nota);
            pie.setAlignment(Pos.CENTER_LEFT);

            String titulo = g.nombre() + "  —  " + g.productosAPedir() + " por pedir"
                    + (p.conCostos() && g.costoPedido() > 0 ? " · " + Moneda.formato(g.costoPedido()) : "")
                    + " · visita: " + g.diasVisita() + " · próximo surtido en " + g.horizonteDias() + " día(s)";
            TitledPane tp = new TitledPane(titulo, new VBox(8, tabla, pie));
            tp.setExpanded(g.productosAPedir() > 0);
            tp.setAnimated(false);
            boxGrupos.getChildren().add(tp);
        }
        tablaExportar.getColumns().setAll(columnas(true));
        tablaExportar.setItems(FXCollections.observableArrayList(todas));

        if (p.grupos().isEmpty()) {
            lblResumen.setText("No hay productos activos con inventario.");
        } else if (gruposMostrados == 0) {
            lblResumen.setText("Nada que pedir por ahora: ningún producto está agotado, bajo el mínimo ni se acaba antes "
                    + "del próximo surtido. Quita «Solo lo que hay que pedir» para ver la frecuencia de todos.");
        } else {
            lblResumen.setText(p.productosAPedir() + " producto(s) por pedir en " + gruposMostrados + " grupo(s)"
                    + (p.conCostos() ? " · costo estimado " + Moneda.formato(p.costoPedido()) : "")
                    + ". Venta diaria calculada con los últimos " + p.diasVentana() + " día(s)"
                    + (p.diasVentana() < ReabastecimientoService.VENTANA_DIAS ? " (pocos días de historial: revisa las cantidades)" : "")
                    + ".");
        }
        Region relleno = new Region();
        relleno.setMinHeight(2);
        boxGrupos.getChildren().add(relleno);
    }

    private static Fila fila(GrupoSurtido g, LineaSurtido l) {
        return new Fila(g.nombre(), l.producto().getDescripcion(), l.ventaDiaria(), l.existencia(), l.minimo(),
                Tarjetas.dias(l.diasCobertura()), l.frecuenciaDias() == null ? "—" : String.valueOf(l.frecuenciaDias()),
                l.pedido(), l.urgencia().getEtiqueta(), l.costoPedido(), l.motivo());
    }

    /** Crea la orden borrador del grupo (si no tiene proveedor, pide elegir uno) */
    private void crearOrden(GrupoSurtido g, Button boton) {
        Proveedor proveedor = g.proveedor();
        if (proveedor == null) {
            List<Proveedor> activos;
            try {
                activos = proveedorService.listarActivos();
            } catch (RuntimeException e) {
                Ventanas.alerta(Alert.AlertType.ERROR, "Proveedores", "No se pudieron leer los proveedores: " + e.getMessage());
                return;
            }
            if (activos.isEmpty()) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Orden de compra",
                        "No hay proveedores activos. Da de alta uno en Inventario → Proveedores.");
                return;
            }
            ChoiceDialog<Proveedor> dlg = new ChoiceDialog<>(activos.get(0), activos);
            dlg.setTitle("Orden de compra");
            dlg.setHeaderText("¿A qué proveedor se le pide «" + g.nombre() + "»?");
            dlg.setContentText("Proveedor:");
            Ventanas.estilizar(dlg);
            Optional<Proveedor> elegido = dlg.showAndWait();
            if (elegido.isEmpty()) return;
            proveedor = elegido.get();
        }
        if (!Ventanas.confirmar("Orden de compra", "¿Crear una orden de compra en borrador a «" + proveedor.getNombre()
                + "» con " + g.productosAPedir() + " producto(s)?")) return;
        try {
            OrdenCompra o = reabastecimientoService.crearOrdenBorrador(proveedor.getId(), g.lineas(),
                    "Pedido sugerido por Estadísticas → Reabastecimiento");
            boton.setDisable(true);
            Ventanas.alerta(Alert.AlertType.INFORMATION, "Orden de compra",
                    "Orden #" + o.getId() + " creada en borrador a " + proveedor.getNombre() + " con " + o.getLineas().size()
                    + " producto(s)" + (plan != null && plan.conCostos() ? " (" + Moneda.formato(o.getTotal()) + ")" : "")
                    + ".\nRevísala, ajústala y márcala como enviada en Inventario → Órdenes de compra.");
        } catch (NegocioException e) {   // incluye PermisoRequeridoException
            Ventanas.alerta(Alert.AlertType.WARNING, "Orden de compra", e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            Ventanas.alerta(Alert.AlertType.ERROR, "Orden de compra", "No se pudo crear la orden: " + e.getMessage());
        }
    }

    @Override
    public void pintarError(String mensaje) {
        lblResumen.setText("No se pudo calcular el plan de surtido: " + mensaje);
        boxGrupos.getChildren().clear();
    }
}
