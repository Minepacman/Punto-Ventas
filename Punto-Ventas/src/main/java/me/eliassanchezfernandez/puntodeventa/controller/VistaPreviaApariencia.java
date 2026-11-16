package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import me.eliassanchezfernandez.puntodeventa.util.ColumnasTabla;
import me.eliassanchezfernandez.puntodeventa.util.Icono;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.AplicadorApariencia;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Apariencia;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.HojaApariencia;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Maqueta pequeña del programa (cabecera, ticket, total, botones, una tarjeta) que se pinta con la
 * apariencia que se está eligiendo, sin tocar el resto de la ventana: lleva su propia hoja limitada a
 * la clase {@code .vista-previa} (ver {@link HojaApariencia#css(Apariencia, String, String)}) y sus
 * iconos usan un estilo propio.
 */
public class VistaPreviaApariencia extends VBox {

    private static final String AMBITO = "vista-previa";
    private static String appCss;

    private final List<Icono> iconos = new ArrayList<>();
    private String hojaActual;

    /** Fila del ticket de muestra */
    public static class Fila {
        private final String producto;
        private final String cantidad;
        private final String importe;

        public Fila(String producto, String cantidad, String importe) {
            this.producto = producto;
            this.cantidad = cantidad;
            this.importe = importe;
        }

        public String getProducto() { return producto; }
        public String getCantidad() { return cantidad; }
        public String getImporte() { return importe; }
    }

    public VistaPreviaApariencia() {
        getStyleClass().add(AMBITO);
        setSpacing(10);
        setPadding(new Insets(0, 0, 12, 0));
        var css = VistaPreviaApariencia.class.getResource("/css/app.css");
        if (css != null) getStylesheets().add(css.toExternalForm());

        // Cabecera (banda oscura)
        Label marca = new Label("Caja Libre", icono("tienda", 18));
        marca.getStyleClass().add("brand-label");
        Region esp = new Region();
        HBox.setHgrow(esp, Priority.ALWAYS);
        Button usuario = new Button("Ana", icono("usuario", 16));
        Button salir = new Button("Salir", icono("salir", 16));
        salir.getStyleClass().add("button-danger");
        usuario.setFocusTraversable(false);
        salir.setFocusTraversable(false);
        HBox cabecera = new HBox(10, marca, esp, usuario, salir);
        cabecera.getStyleClass().add("main-toolbar");
        cabecera.setAlignment(Pos.CENTER_LEFT);

        // Ticket + total
        TableView<Fila> ticket = new TableView<>(FXCollections.observableArrayList(
                new Fila("Refresco 600 ml", "2", "$36.00"),
                new Fila("Pan grande", "1", "$48.50"),
                new Fila("Huevo 1 kg", "1", "$52.00")));
        ticket.getStyleClass().add("ticket-table");
        TableColumn<Fila, String> cProd = new TableColumn<>("Producto");
        cProd.setCellValueFactory(new PropertyValueFactory<>("producto"));
        cProd.setPrefWidth(200);
        cProd.setMinWidth(110);
        TableColumn<Fila, String> cCant = new TableColumn<>("Cant.");
        cCant.setCellValueFactory(new PropertyValueFactory<>("cantidad"));
        cCant.setPrefWidth(56);
        cCant.getStyleClass().add("align-right");
        TableColumn<Fila, String> cImp = new TableColumn<>("Importe");
        cImp.setCellValueFactory(new PropertyValueFactory<>("importe"));
        cImp.setPrefWidth(84);
        cImp.getStyleClass().add("align-right");
        ticket.getColumns().addAll(List.of(cProd, cCant, cImp));
        ticket.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        ticket.setPrefHeight(132);
        ticket.setMinHeight(110);
        ticket.getSelectionModel().select(1);
        ticket.setFocusTraversable(false);
        HBox.setHgrow(ticket, Priority.ALWAYS);
        ColumnasTabla.ajustar(ticket);   // con letra grande: ni encabezados ni importes con «…»

        Label tituloTotal = new Label("TOTAL");
        tituloTotal.getStyleClass().add("total-label-title");
        Label total = new Label("$136.50");
        total.getStyleClass().add("total-amount");
        VBox cajaTotal = new VBox(2, tituloTotal, total);
        cajaTotal.getStyleClass().add("total-box");
        Button cobrar = new Button("Cobrar  F12", icono("efectivo", 18));
        cobrar.getStyleClass().add("btn-cobrar");
        cobrar.setMaxWidth(Double.MAX_VALUE);
        cobrar.setFocusTraversable(false);
        VBox totales = new VBox(8, cajaTotal, cobrar);
        totales.getStyleClass().add("totales-panel");
        totales.setPadding(new Insets(10));
        totales.setPrefWidth(160);
        totales.setMinWidth(150);
        HBox centro = new HBox(10, ticket, totales);
        centro.setPadding(new Insets(0, 12, 0, 12));

        // Botones
        Button guardar = new Button("Guardar", icono("guardar", 16));
        guardar.getStyleClass().add("button-primary");
        Button buscar = new Button("Buscar", icono("buscar", 16));
        Button eliminar = new Button("Eliminar", icono("eliminar", 16));
        eliminar.getStyleClass().add("button-danger");
        Hyperlink enlace = new Hyperlink("Ver ayuda");
        enlace.setGraphic(icono("ayuda", 16));
        for (Node n : List.of(guardar, buscar, eliminar, enlace)) n.setFocusTraversable(false);
        HBox botones = new HBox(8, guardar, buscar, eliminar, enlace);
        botones.setAlignment(Pos.CENTER_LEFT);
        botones.setPadding(new Insets(0, 12, 0, 12));

        // Tarjeta
        Label tituloTarjeta = new Label("Productos", icono("paquete", 18));
        tituloTarjeta.getStyleClass().add("card-title");
        Label pista = new Label("Así se ven los textos de ayuda y las pistas.");
        pista.getStyleClass().add("hint");
        pista.setWrapText(true);
        TextField campo = new TextField();
        campo.setPromptText("Buscar producto o código…");
        campo.setFocusTraversable(false);
        CheckBox casilla = new CheckBox("Usa inventario");
        casilla.setSelected(true);
        casilla.setFocusTraversable(false);
        Label insignia = new Label("Con existencia");
        insignia.getStyleClass().addAll("badge", "badge-success");
        Label aviso = new Label("Quedan 3 piezas");
        aviso.getStyleClass().addAll("badge", "badge-warning");
        HBox fila = new HBox(8, casilla, insignia, aviso);
        fila.setAlignment(Pos.CENTER_LEFT);
        VBox tarjeta = new VBox(8, tituloTarjeta, pista, campo, fila);
        tarjeta.getStyleClass().add("card");
        VBox.setMargin(tarjeta, new Insets(0, 12, 0, 12));

        // Pie como el de la ventana principal (texto atenuado sobre la barra hundida)
        Label pie = new Label("Caja 1 · Listo");
        HBox barraEstado = new HBox(pie);
        barraEstado.getStyleClass().add("status-bar");
        barraEstado.setPadding(new Insets(4, 12, 4, 12));

        getChildren().addAll(cabecera, centro, botones, tarjeta, barraEstado);
    }

    private Icono icono(String nombre, double tamano) {
        Icono i = new Icono(nombre, tamano);
        iconos.add(i);
        return i;
    }

    /** Pinta la maqueta con esa apariencia */
    public void mostrar(Apariencia a) {
        AplicadorApariencia.cargarFuente(a.letra());
        String url = HojaApariencia.comoUrl(HojaApariencia.css(a, appCss(), AMBITO));
        if (hojaActual != null) getStylesheets().remove(hojaActual);
        getStylesheets().add(url);
        hojaActual = url;
        for (Icono i : iconos) {
            i.usarEstilo(a.paqueteIconos(), a.estiloIconos(), a.grosorIconos().ancho(), a.iconosGrandes() ? 1.25 : 1.0);
        }
    }

    private static String appCss() {
        if (appCss == null) {
            try (InputStream in = VistaPreviaApariencia.class.getResourceAsStream("/css/app.css")) {
                appCss = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                appCss = "";
            }
        }
        return appCss;
    }
}
