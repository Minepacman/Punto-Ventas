package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.InterpreteTicket;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.LineaTicket;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketLeido;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService.ProductoNuevo;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService.RenglonAplicar;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService.RenglonRevision;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;
import me.eliassanchezfernandez.puntodeventa.util.Icono;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.EtiquetaBeta;
import me.eliassanchezfernandez.puntodeventa.util.FuncionBeta;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Ventana "Leer ticket de proveedor" (Inventario).
 *
 * 1) Foto: elegir archivo, arrastrar o pegar. Si esta computadora no tiene lector de fotos (OCR), la foto se
 *    muestra al lado para escribir o pegar el texto. 2) Leyendo… (en segundo plano). 3) Revisión: proveedor,
 *    folio y fecha editables; un renglón por artículo con su semáforo (verde = reconocido, amarillo = revisar,
 *    rojo = no se leyó o falta el producto), cantidad, piezas por unidad, importe, el producto de la tienda
 *    (Otro… / Crear…) y el cuadre contra el total. 4) Registrar entrada → "Subieron de costo" con
 *    los precios sugeridos para actualizar. Las reglas viven en {@link TicketProveedorService}.
 */
@Component
public class LeerTicketDialogo {

    @Autowired private TicketProveedorService servicio;
    @Autowired private ProveedorService proveedorService;
    @Autowired private ProductoService productoService;
    @Autowired private SesionService sesionService;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketsPorRevisarService porRevisar;

    /** Abre la ventana; regresa cuando se cierra (true si se registró al menos un ticket) */
    public boolean mostrar(Window owner) {
        Ventana v = new Ventana(owner);
        v.stage.showAndWait();
        return v.registrados > 0;
    }

    /**
     * «Por revisar»: tickets mandados desde el teléfono. Con uno lo abre directo; con varios deja elegir (o
     * descartar los que no sirven). Lo leído en el teléfono se vuelve a interpretar aquí con el intérprete de hoy.
     * @return true si se registró al menos un ticket
     */
    public boolean mostrarPorRevisar(Window owner) {
        boolean registrado = false;
        while (true) {
            List<me.eliassanchezfernandez.puntodeventa.repository.TicketPorRevisarRepository.Resumen> lista = porRevisar.pendientes();
            if (lista.isEmpty()) {
                if (!registrado) Ventanas.alerta(Alert.AlertType.INFORMATION, "Por revisar", "No hay tickets esperando revisión.");
                return registrado;
            }
            Long id = elegirPendiente(owner, lista);
            if (id == null) return registrado;
            if (id < 0) continue;                                   // se descartó uno: volver a la lista
            if (abrirPendiente(owner, id)) registrado = true;
            if (lista.size() == 1) return registrado;
        }
    }

    /** id elegido; -1 si se descartó uno; null si se cerró */
    private Long elegirPendiente(Window owner, List<me.eliassanchezfernandez.puntodeventa.repository.TicketPorRevisarRepository.Resumen> lista) {
        if (lista.size() == 1) return lista.get(0).id();
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm");
        Dialog<Long> dlg = new Dialog<>();
        dlg.setTitle("Tickets por revisar");
        dlg.setHeaderText("Fotos de tickets mandadas desde el teléfono");
        if (owner != null) dlg.initOwner(owner);
        ListView<me.eliassanchezfernandez.puntodeventa.repository.TicketPorRevisarRepository.Resumen> lv = new ListView<>();
        lv.getItems().setAll(lista);
        lv.setPrefSize(520, 260);
        lv.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(me.eliassanchezfernandez.puntodeventa.repository.TicketPorRevisarRepository.Resumen r, boolean vacio) {
                super.updateItem(r, vacio);
                setText(vacio || r == null ? null : r.creadoEn().format(f) + " · la mandó " + (r.usuarioNombre() == null ? "—" : r.usuarioNombre())
                        + (r.nota() == null ? "" : " · " + r.nota()));
            }
        });
        lv.getSelectionModel().selectFirst();
        lv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && lv.getSelectionModel().getSelectedItem() != null) {
                dlg.setResult(lv.getSelectionModel().getSelectedItem().id());
                dlg.close();
            }
        });
        dlg.getDialogPane().setContent(lv);
        ButtonType abrir = new ButtonType("Abrir", ButtonBar.ButtonData.OK_DONE);
        ButtonType descartar = new ButtonType("Descartar", ButtonBar.ButtonData.LEFT);
        dlg.getDialogPane().getButtonTypes().addAll(descartar, abrir, ButtonType.CANCEL);
        dlg.setResultConverter(b -> {
            var sel = lv.getSelectionModel().getSelectedItem();
            if (sel == null) return null;
            if (b == abrir) return sel.id();
            if (b == descartar) {
                if (!Ventanas.confirmar("Descartar", "¿Quitar este ticket de la lista sin registrarlo?")) return -1L;
                try {
                    porRevisar.descartar(sel.id());
                } catch (NegocioException e) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Descartar", e.getMessage());
                }
                return -1L;
            }
            return null;
        });
        Ventanas.estilizar(dlg);
        return dlg.showAndWait().orElse(null);
    }

    /** Abre «Leer ticket» con la foto (y lo leído) de un pendiente; true si se registró */
    boolean abrirPendiente(Window owner, Long id) {
        var p = porRevisar.obtener(id);
        Ventana v = new Ventana(owner);
        v.pendienteId = p.id();
        v.proveedorInicial = p.proveedorId();
        v.foto = p.foto();
        v.imagen = new Image(new ByteArrayInputStream(p.foto()));
        List<TicketLeido> ts = me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketsPorRevisarService.interpretar(p);
        if (!ts.isEmpty() && ts.stream().anyMatch(x -> !x.getLineas().isEmpty())) {
            v.origen = p.origen() == null ? "teléfono" : "teléfono · " + p.origen();
            v.empezarRevision(ts);
        } else {
            v.origen = "teléfono";
            v.leerFoto();
        }
        v.stage.showAndWait();
        return v.registrados > 0;
    }

    /** Para capturas de pantalla: la ventana en su primera pantalla, sin esperar a que se cierre */
    Stage abrirParaCaptura() {
        Ventana v = new Ventana(null);
        v.stage.show();
        return v.stage;
    }

    /** Para capturas de pantalla: la ventana ya en la revisión de un texto (con la foto al lado si se da) */
    Stage abrirEnRevisionParaCaptura(String texto, byte[] foto) {
        Ventana v = new Ventana(null);
        if (foto != null) {
            v.foto = foto;
            v.imagen = new Image(new ByteArrayInputStream(foto));
        }
        v.origen = "captura";
        v.empezarRevision(servicio.leerTexto(texto));
        v.stage.show();
        return v.stage;
    }

    // ── Un renglón en pantalla ─────────────────────────────────────────────

    private static final class Fila {
        LineaTicket linea;
        RenglonRevision rev;
        Long productoId;
        String productoNombre;
        ProductoNuevo nuevo;
        boolean dudoso;
        final TextField txtDescripcion = new TextField();
        final TextField txtCant = new TextField();
        final TextField txtPiezas = new TextField();
        final TextField txtImporte = new TextField();
        final Label lblEstado = new Label();
        final Label lblProducto = new Label();
        final Label lblCosto = new Label();
        HBox contenedor;
        final List<Node> nodos = new ArrayList<>();
    }

    private final class Ventana {
        final Stage stage = new Stage();
        final BorderPane raiz = new BorderPane();
        byte[] foto;
        Image imagen;
        List<TicketLeido> tickets = List.of();
        int actual;
        String origen;
        TicketProveedorService.Revision revision;
        int registrados;
        /** Ticket mandado desde el teléfono que se está revisando (se marca REGISTRADO al darle entrada) */
        Long pendienteId;
        Long proveedorInicial;

        // Revisión
        ComboBox<Proveedor> cmbProveedor;
        Label lblMotivo;
        TextField txtFolio;
        DatePicker dpFecha;
        Label lblRepetido;
        CheckBox chkRepetido;
        final List<Fila> filas = new ArrayList<>();
        GridPane grid;
        Label lblCuadre;
        ScrollPane scrollFoto;
        ImageView vistaFoto;
        boolean cambiandoProveedor;

        Ventana(Window owner) {
            stage.setTitle(EtiquetaBeta.tituloVentana("Leer ticket de proveedor", FuncionBeta.LECTOR_TICKETS));
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) stage.initOwner(owner);
            Ventanas.aplicarIcono(stage);
            var vis = Screen.getPrimary().getVisualBounds();
            Scene scene = new Scene(raiz, Math.min(1080, vis.getWidth() - 20), Math.min(720, vis.getHeight() - 20));
            Ventanas.aplicarCss(scene);
            scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) {
                    e.consume();
                    if (filas.isEmpty() || Ventanas.confirmar("Leer ticket", "¿Cerrar sin registrar este ticket?")) stage.close();
                } else if (e.getCode() == KeyCode.V && e.isShortcutDown() && revision == null
                        && !(scene.getFocusOwner() instanceof TextArea) && !(scene.getFocusOwner() instanceof TextField)) {
                    e.consume();
                    pegarFoto();
                }
            });
            stage.setScene(scene);
            stage.setMinWidth(760);
            stage.setMinHeight(520);
            pantallaInicio();
        }

        // ── 1. Elegir la foto ──────────────────────────────────────────────

        void pantallaInicio() {
            revision = null;
            filas.clear();
            Label titulo = new Label("Leer ticket de proveedor");
            titulo.getStyleClass().add("form-section-title");
            Label ayuda = new Label("Toma la foto con buena luz, de frente y con el ticket extendido (sin dobleces). "
                    + "Después revisas lo que se leyó y lo registras como entrada de mercancía.");
            ayuda.setWrapText(true);

            Label lblZona = new Label("Arrastra aquí la foto del ticket");
            Button btnElegir = new Button("Elegir foto…", new Icono("imagen"));
            btnElegir.getStyleClass().add("button-primary");
            btnElegir.setOnAction(e -> elegirFoto());
            Button btnPegar = new Button("Pegar foto", new Icono("portapapeles"));
            btnPegar.setOnAction(e -> pegarFoto());
            HBox botones = new HBox(10, btnElegir, btnPegar);
            botones.setAlignment(Pos.CENTER);
            VBox contenidoZona = new VBox(12, new Icono("camara", 40), lblZona, botones);
            contenidoZona.setAlignment(Pos.CENTER);
            StackPane zona = new StackPane(contenidoZona);
            zona.getStyleClass().add("imagen-zona");
            zona.setPrefHeight(240);
            zona.setOnDragOver(e -> {
                Dragboard db = e.getDragboard();
                if (db.hasFiles() || db.hasImage()) e.acceptTransferModes(TransferMode.COPY);
                e.consume();
            });
            zona.setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                boolean ok = false;
                if (db.hasFiles() && !db.getFiles().isEmpty()) { ok = usarArchivo(db.getFiles().get(0)); }
                else if (db.hasImage()) { ok = usarImagen(db.getImage()); }
                e.setDropCompleted(ok);
                e.consume();
            });

            HBox filaTitulo = new HBox(8, titulo, new EtiquetaBeta(FuncionBeta.LECTOR_TICKETS));
            filaTitulo.setAlignment(Pos.CENTER_LEFT);
            VBox caja = new VBox(14, filaTitulo, ayuda, zona);
            if (!servicio.hayLectorDeFotos()) {
                Label sin = new Label("Esta computadora todavía no puede leer el texto de las fotos. Puedes elegir la foto "
                        + "para tenerla a la vista mientras escribes o pegas el texto del ticket.");
                sin.setWrapText(true);
                sin.getStyleClass().add("estado-bajo");
                caja.getChildren().add(sin);
            }
            Button btnTexto = new Button("Escribir o pegar el texto del ticket", new Icono("editar"));
            btnTexto.setOnAction(e -> pantallaTexto());
            caja.getChildren().addAll(new Separator(), btnTexto);
            caja.setPadding(new Insets(20));
            caja.setMaxWidth(720);
            BorderPane.setAlignment(caja, Pos.TOP_CENTER);
            raiz.setCenter(caja);
            raiz.setBottom(barra(boton("Cerrar", e -> stage.close())));
            raiz.setTop(null);
        }

        void elegirFoto() {
            FileChooser fc = new FileChooser();
            fc.setTitle("Elegir la foto del ticket");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Fotos (JPG, PNG)", "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.gif"),
                    new FileChooser.ExtensionFilter("Todos los archivos", "*.*"));
            File f = fc.showOpenDialog(stage);
            if (f != null) usarArchivo(f);
        }

        void pegarFoto() {
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasFiles() && !cb.getFiles().isEmpty()) usarArchivo(cb.getFiles().get(0));
            else if (cb.hasImage()) usarImagen(cb.getImage());
            else if (cb.hasString() && cb.getString() != null && cb.getString().contains("\n")) {
                pantallaTexto();
            } else {
                Ventanas.alerta(Alert.AlertType.INFORMATION, "Pegar foto", "Copia primero la foto del ticket (o el archivo de la foto).");
            }
        }

        boolean usarArchivo(File f) {
            try {
                if (f.length() > 25L * 1024 * 1024) throw new NegocioException("La foto pesa demasiado (más de 25 MB).");
                byte[] b = Files.readAllBytes(f.toPath());
                Image img = new Image(new ByteArrayInputStream(b));
                if (img.isError() || img.getWidth() <= 0) throw new NegocioException("Ese archivo no es una foto que se pueda abrir.");
                foto = b;
                imagen = img;
                origen = f.getName();
                leerFoto();
                return true;
            } catch (NegocioException e) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Foto del ticket", e.getMessage());
            } catch (Exception e) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Foto del ticket", "No se pudo abrir la foto: " + e.getMessage());
            }
            return false;
        }

        boolean usarImagen(Image img) {
            byte[] b = ImagenProductoCache.aPng(img);
            if (b == null) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Foto del ticket", "No se pudo leer la imagen.");
                return false;
            }
            foto = b;
            imagen = img;
            origen = "foto pegada";
            leerFoto();
            return true;
        }

        // ── 2. Leyendo ─────────────────────────────────────────────────────

        void leerFoto() {
            if (!servicio.hayLectorDeFotos()) { pantallaTexto(); return; }
            ProgressIndicator pi = new ProgressIndicator();
            Label l = new Label("Leyendo el ticket… (puede tardar unos segundos)");
            VBox v = new VBox(16, pi, l);
            v.setAlignment(Pos.CENTER);
            raiz.setTop(null);
            raiz.setCenter(v);
            raiz.setBottom(null);
            byte[] b = foto;
            Thread t = new Thread(() -> {
                try {
                    TicketProveedorService.Lectura lectura = servicio.leerFoto(b);
                    Platform.runLater(() -> {
                        origen = lectura.origen();
                        if (lectura.tickets().isEmpty() || lectura.tickets().stream().allMatch(x -> x.getLineas().isEmpty())) {
                            Ventanas.alerta(Alert.AlertType.INFORMATION, "Leer ticket",
                                    "No se encontraron productos en la foto. Prueba con otra foto (más luz, de frente) "
                                    + "o escribe el texto del ticket.");
                            pantallaTexto();
                        } else {
                            empezarRevision(lectura.tickets());
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Ventanas.alerta(Alert.AlertType.WARNING, "Leer ticket", "No se pudo leer la foto: "
                                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                                + "\nPuedes escribir o pegar el texto del ticket.");
                        pantallaTexto();
                    });
                }
            }, "leer-ticket-proveedor");
            t.setDaemon(true);
            t.start();
        }

        // ── Texto escrito o pegado ─────────────────────────────────────────

        void pantallaTexto() {
            Label ayuda = new Label("Escribe el ticket renglón por renglón, como viene impreso (o pega el texto). "
                    + "Basta con los productos (cantidad, nombre, precio e importe) y el TOTAL.");
            ayuda.setWrapText(true);
            TextArea txt = new TextArea();
            txt.setPromptText("Ejemplo:\n1  ORBIT SURTIDO  89.00  89.00\n2  MAZAPAN GIGANTE  109.00  218.00\nTOTAL  307.00");
            txt.setWrapText(false);
            txt.setStyle("-fx-font-family: monospace;");
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasString() && cb.getString() != null && cb.getString().contains("\n")) txt.setText(cb.getString());
            VBox.setVgrow(txt, Priority.ALWAYS);
            Button leer = boton("Leer el texto", e -> {
                try {
                    List<TicketLeido> ts = servicio.leerTexto(txt.getText());
                    if (ts.isEmpty() || ts.stream().allMatch(x -> x.getLineas().isEmpty())) {
                        Ventanas.alerta(Alert.AlertType.INFORMATION, "Leer ticket",
                                "No se encontraron productos. Cada producto debe llevar su cantidad y su importe.");
                        return;
                    }
                    origen = foto != null ? "texto escrito (con foto)" : "texto escrito";
                    empezarRevision(ts);
                } catch (NegocioException ex) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Leer ticket", ex.getMessage());
                }
            });
            leer.getStyleClass().add("button-primary");
            VBox derecha = new VBox(10, ayuda, txt);
            derecha.setPadding(new Insets(12));
            raiz.setTop(null);
            raiz.setCenter(foto != null ? dividir(panelFoto(), derecha) : derecha);
            raiz.setBottom(barra(boton("Volver", e -> pantallaInicio()), leer));
            Platform.runLater(txt::requestFocus);
        }

        // ── 3. Revisión ────────────────────────────────────────────────────

        void empezarRevision(List<TicketLeido> ts) {
            tickets = ts;
            actual = 0;
            revisar(proveedorInicial);
        }

        void revisar(Long proveedorId) {
            TicketLeido t = tickets.get(actual);
            try {
                revision = servicio.preparar(t, proveedorId);
            } catch (NegocioException e) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Leer ticket", e.getMessage());
                return;
            }
            construirRevision();
        }

        void construirRevision() {
            TicketLeido t = revision.ticket();
            // Encabezado
            cmbProveedor = new ComboBox<>();
            cmbProveedor.getItems().setAll(proveedorService.listarActivos());
            cmbProveedor.setPromptText("Elige el proveedor");
            cmbProveedor.setPrefWidth(280);
            cmbProveedor.setConverter(new StringConverter<>() {
                @Override public String toString(Proveedor p) { return p == null ? "" : p.getNombre(); }
                @Override public Proveedor fromString(String s) { return null; }
            });
            if (revision.proveedorId() != null) {
                cmbProveedor.getItems().stream().filter(p -> p.getId().equals(revision.proveedorId())).findFirst()
                        .ifPresent(p -> cmbProveedor.getSelectionModel().select(p));
            }
            cmbProveedor.valueProperty().addListener((o, a, b) -> {
                if (cambiandoProveedor || b == null || (a != null && a.getId().equals(b.getId()))) return;
                cambiandoProveedor = true;
                try { revisar(b.getId()); } finally { cambiandoProveedor = false; }
            });
            lblMotivo = new Label();
            lblMotivo.getStyleClass().add("ticket-secundario");
            if (revision.proveedorId() != null) {
                lblMotivo.setText("Reconocido " + revision.motivoProveedor());
            } else {
                lblMotivo.setText(revision.nombreSugeridoNuevo() != null
                        ? "No está en tu lista: el ticket dice «" + revision.nombreSugeridoNuevo() + "»."
                        : "El ticket no trae el nombre del proveedor: elígelo.");
            }
            Button btnNuevoProv = new Button("Nuevo…", new Icono("mas"));
            btnNuevoProv.setOnAction(e -> nuevoProveedor());
            btnNuevoProv.setDisable(!sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES));
            if (btnNuevoProv.isDisabled()) Tooltip.install(btnNuevoProv, new Tooltip("Solo quien administra proveedores puede darlos de alta."));
            HBox filaProv = new HBox(8, new Label("Proveedor:"), cmbProveedor, btnNuevoProv, lblMotivo);
            filaProv.setAlignment(Pos.CENTER_LEFT);

            txtFolio = new TextField(t.getFolio() == null ? "" : t.getFolio());
            txtFolio.setPrefColumnCount(14);
            dpFecha = new DatePicker(t.getFecha());
            dpFecha.setPrefWidth(140);
            HBox filaFolio = new HBox(8, new Label("Folio:"), txtFolio, new Label("Fecha:"), dpFecha);
            filaFolio.setAlignment(Pos.CENTER_LEFT);
            if (tickets.size() > 1) {
                Label varios = new Label("Esta foto trae " + tickets.size() + " tickets: estás revisando el " + (actual + 1) + ".");
                varios.getStyleClass().add("estado-bajo");
                filaFolio.getChildren().add(varios);
            }
            lblRepetido = new Label(revision.avisoRepetido() == null ? "" : revision.avisoRepetido());
            lblRepetido.getStyleClass().add("estado-agotado");
            lblRepetido.setWrapText(true);
            chkRepetido = new CheckBox("Registrar de todos modos (es otra entrega)");
            HBox filaRepetido = new HBox(10, lblRepetido, chkRepetido);
            filaRepetido.setAlignment(Pos.CENTER_LEFT);
            boolean repetido = revision.avisoRepetido() != null;
            filaRepetido.setVisible(repetido);
            filaRepetido.setManaged(repetido);
            VBox top = new VBox(8, filaProv, filaFolio, filaRepetido);
            top.setPadding(new Insets(10, 12, 6, 12));

            // Renglones
            filas.clear();
            grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(6);
            grid.setPadding(new Insets(6, 18, 6, 8));
            grid.getColumnConstraints().addAll(new ColumnConstraints(24), new ColumnConstraints(150, 190, Double.MAX_VALUE, Priority.SOMETIMES, null, true),
                    new ColumnConstraints(56), new ColumnConstraints(60), new ColumnConstraints(76), new ColumnConstraints(76),
                    new ColumnConstraints(170, 220, Double.MAX_VALUE, Priority.ALWAYS, null, true), new ColumnConstraints(34));
            String[] titulos = { "", "En el ticket", "Cant.", "Piezas", "Importe", "Costo pieza", "Producto de la tienda", "" };
            for (int i = 0; i < titulos.length; i++) {
                Label l = new Label(titulos[i]);
                l.getStyleClass().add("form-section-title");
                if (i == 3) Tooltip.install(l, new Tooltip("Cuántas piezas trae cada unidad del ticket (1 si es pieza suelta; 24 si es un paquete de 24)."));
                if (i == 5) Tooltip.install(l, new Tooltip("Lo que te cuesta cada pieza, con los impuestos y cargos del ticket repartidos."));
                grid.add(l, i, 0);
            }
            for (RenglonRevision rr : revision.renglones()) agregarFila(rr.linea(), rr);
            ScrollPane scrollFilas = new ScrollPane(grid);
            scrollFilas.setFitToWidth(true);

            lblCuadre = new Label();
            lblCuadre.setWrapText(true);
            Button btnAgregar = new Button("Agregar renglón", new Icono("mas"));
            btnAgregar.setOnAction(e -> {
                LineaTicket l = new LineaTicket();
                l.setRenglon(Integer.MAX_VALUE);
                l.setCantidad(1);
                l.setEstado(LineaTicket.Estado.REVISAR);
                l.setMotivo("Renglón agregado a mano.");
                agregarFila(l, null);
                actualizarCuadre();
            });
            HBox pie = new HBox(12, btnAgregar, lblCuadre);
            pie.setAlignment(Pos.CENTER_LEFT);
            pie.setPadding(new Insets(6, 12, 0, 12));
            HBox.setHgrow(lblCuadre, Priority.ALWAYS);

            Button btnRegistrar = new Button("Registrar entrada", new Icono("listo"));
            btnRegistrar.getStyleClass().add("button-success");
            btnRegistrar.setOnAction(e -> registrar());
            Button btnCancelar = boton("Cancelar", e -> {
                if (Ventanas.confirmar("Leer ticket", "¿Descartar lo leído y volver a empezar?")) pantallaInicio();
            });

            VBox centro = new VBox(4, scrollFilas, pie);
            VBox.setVgrow(scrollFilas, Priority.ALWAYS);
            raiz.setTop(top);
            raiz.setCenter(foto != null ? dividir(panelFoto(), centro) : centro);
            raiz.setBottom(barra(btnCancelar, btnRegistrar));
            actualizarCuadre();
        }

        void agregarFila(LineaTicket l, RenglonRevision rr) {
            Fila f = new Fila();
            f.linea = l;
            f.rev = rr;
            if (rr != null && rr.elegida() != null) {
                f.productoId = rr.elegida().productoId();
                f.productoNombre = rr.elegida().descripcion();
                f.dudoso = rr.dudoso();
            }
            f.txtDescripcion.setText(l.getDescripcion() == null ? "" : l.getDescripcion());
            f.txtDescripcion.setEditable(rr == null);
            if (rr != null) {
                f.txtDescripcion.getStyleClass().add("ticket-secundario");
                Tooltip.install(f.txtDescripcion, new Tooltip((l.getCodigoProveedor() != null ? "Clave " + l.getCodigoProveedor() + "\n" : "")
                        + "Impreso: " + l.getTextoOriginal()));
            } else {
                f.txtDescripcion.setPromptText("Producto");
            }
            f.txtCant.setText(numero(l.getCantidad()));
            f.txtPiezas.setText(String.valueOf(rr != null ? rr.piezasPorUnidad() : 1));
            f.txtImporte.setText(l.getImporte() > 0 ? String.format(Locale.ROOT, "%.2f", l.getImporte()) : "");
            if (rr != null && rr.preguntarEmpaque()) {
                f.txtPiezas.getStyleClass().add("ticket-campo-revisar");
                Tooltip.install(f.txtPiezas, new Tooltip("El ticket sugiere un paquete de " + l.getEmpaque() + " piezas. "
                        + "Si llegó el paquete cerrado escribe " + l.getEmpaque() + "; si son piezas sueltas, 1. Se recuerda para la próxima."));
            }
            for (TextField tf : List.of(f.txtCant, f.txtPiezas, f.txtImporte)) {
                tf.setAlignment(Pos.CENTER_RIGHT);
                tf.textProperty().addListener((o, a, b) -> { pintarFila(f); actualizarCuadre(); });
            }
            f.txtDescripcion.textProperty().addListener((o, a, b) -> pintarFila(f));

            Button btnCambiar = new Button("Otro…", new Icono("buscar"));
            btnCambiar.setMinWidth(Region.USE_PREF_SIZE);
            Tooltip.install(btnCambiar, new Tooltip("Elegir otro producto de tu catálogo"));
            btnCambiar.setOnAction(e -> elegirProducto(f));
            Button btnCrear = new Button("Crear…", new Icono("mas"));
            btnCrear.setMinWidth(Region.USE_PREF_SIZE);
            btnCrear.setOnAction(e -> crearProducto(f));
            if (!sesionService.tienePermiso(Permiso.EDITAR_PRODUCTOS)) {
                btnCrear.setDisable(true);
                Tooltip.install(btnCrear, new Tooltip("Solo quien puede editar productos puede crearlos."));
            }
            f.lblProducto.setWrapText(true);
            VBox producto = new VBox(3, f.lblProducto, new HBox(6, btnCambiar, btnCrear));
            Button quitar = new Button(null, new Icono("eliminar"));
            Tooltip.install(quitar, new Tooltip("Quitar este renglón (no es un producto o ya no llegó)"));
            quitar.setOnAction(e -> {
                grid.getChildren().removeAll(f.nodos);
                filas.remove(f);
                actualizarCuadre();
            });
            f.lblCosto.setAlignment(Pos.CENTER_RIGHT);
            int r = filas.size() + 1;
            filas.add(f);
            Node[] celdas = { f.lblEstado, f.txtDescripcion, f.txtCant, f.txtPiezas, f.txtImporte, f.lblCosto, producto, quitar };
            for (int i = 0; i < celdas.length; i++) {
                grid.add(celdas[i], i, r);
                f.nodos.add(celdas[i]);
                GridPane.setValignment(celdas[i], javafx.geometry.VPos.TOP);
            }
            // Al enfocar un renglón, la foto se mueve a su altura
            for (Node n : List.of(f.txtCant, f.txtPiezas, f.txtImporte, f.txtDescripcion)) {
                n.focusedProperty().addListener((o, a, b) -> { if (b) enfocarFoto(f.linea.getY()); });
            }
            pintarFila(f);
        }

        /** Semáforo y textos de la fila según lo leído, lo elegido y lo capturado */
        void pintarFila(Fila f) {
            String estado;           // ok / revisar / falta
            List<String> motivos = new ArrayList<>();
            double cant = CalculoCobro.parsearNumero(f.txtCant.getText());
            double piezas = CalculoCobro.parsearNumero(f.txtPiezas.getText());
            double importe = CalculoCobro.parsearNumero(f.txtImporte.getText());
            boolean numerosOk = cant > 0 && piezas >= 1 && importe >= 0 && !Double.isNaN(importe);
            if (!numerosOk) motivos.add("Revisa la cantidad, las piezas por unidad y el importe.");
            if (f.productoId == null && f.nuevo == null) motivos.add("Falta elegir el producto de la tienda (Otro…) o crearlo (Crear…).");
            if (f.rev == null && f.txtDescripcion.getText().isBlank()) motivos.add("Escribe qué producto es.");
            if (!numerosOk || (f.productoId == null && f.nuevo == null)) {
                estado = "falta";
            } else {
                if (f.linea.getEstado() != LineaTicket.Estado.RECONOCIDO && f.linea.getMotivo() != null) motivos.add(f.linea.getMotivo());
                if (f.dudoso) motivos.add("¿Es este producto? Si no, elige el correcto con Otro….");
                if (f.rev != null && f.rev.preguntarEmpaque() && f.txtPiezas.getStyleClass().contains("ticket-campo-revisar")) {
                    motivos.add("Confirma cuántas piezas trae cada unidad.");
                }
                estado = motivos.isEmpty() ? "ok" : "revisar";
            }
            int semaforo = estado.equals("ok") ? 0 : estado.equals("revisar") ? 1 : 2;
            String[] iconos = { "listo", "alerta", "cerrar" };
            String[] clasesEstado = { "estado-ok", "estado-bajo", "estado-agotado" };
            String[] clasesFila = { null, "ticket-fila-amarilla", "ticket-fila-roja" };
            f.lblEstado.setGraphic(new Icono(iconos[semaforo], 18));
            f.lblEstado.getStyleClass().removeAll("estado-ok", "estado-bajo", "estado-agotado");
            f.lblEstado.getStyleClass().add(clasesEstado[semaforo]);
            Tooltip.install(f.lblEstado, new Tooltip(motivos.isEmpty() ? "Reconocido" : String.join("\n", motivos)));
            for (Node n : f.nodos) n.getStyleClass().removeAll("ticket-fila-amarilla", "ticket-fila-roja");
            if (semaforo > 0) f.txtDescripcion.getStyleClass().add(clasesFila[semaforo]);

            if (f.nuevo != null) {
                f.lblProducto.setText("Nuevo: " + f.nuevo.descripcion() + " (venta " + Moneda.formato(f.nuevo.precioVenta()) + ")");
            } else if (f.productoId != null) {
                f.lblProducto.setText((f.dudoso ? "¿" : "") + f.productoNombre + (f.dudoso ? "?" : ""));
            } else {
                f.lblProducto.setText("No se encontró en tu catálogo");
            }
            if (revision.costosVisibles() && numerosOk) {
                double costo = TicketProveedorService.costoPorPieza(importe, factor(), cant, (int) Math.round(piezas));
                f.lblCosto.setText(Moneda.formato(costo));
            } else {
                f.lblCosto.setText("—");
            }
        }

        double factor() {
            TicketLeido t = revision.ticket();
            return t.getCuadre() != null && t.getCuadre().cuadra() ? t.getCuadre().factorCosto() : 1.0;
        }

        /** Vuelve a cuadrar con los importes capturados */
        void actualizarCuadre() {
            if (lblCuadre == null || revision == null) return;
            TicketLeido base = revision.ticket();
            TicketLeido t = new TicketLeido();
            t.setSubtotal(base.getSubtotal());
            t.setDescuento(base.getDescuento());
            t.setImpuestos(base.getImpuestos());
            t.setCargos(base.getCargos());
            t.setTotal(base.getTotal());
            for (Fila f : filas) {
                LineaTicket l = new LineaTicket();
                double imp = CalculoCobro.parsearNumero(f.txtImporte.getText());
                l.setImporte(Double.isNaN(imp) ? 0 : imp);
                t.getLineas().add(l);
            }
            var c = InterpreteTicket.cuadrar(t);
            if (c.cuadra()) {
                lblCuadre.setText("La suma cuadra con el total del ticket.");
                lblCuadre.setGraphic(new Icono("listo"));
                lblCuadre.getStyleClass().setAll("label", "ticket-cuadra");
                if (c.referencia() != null && Math.abs(c.factorCosto() - 1) > 0.0001) {
                    lblCuadre.setText(c.mensaje());
                }
            } else {
                lblCuadre.setText(c.mensaje());
                lblCuadre.setGraphic(new Icono("alerta"));
                lblCuadre.getStyleClass().setAll("label", "ticket-no-cuadra");
            }
        }

        void elegirProducto(Fila f) {
            Dialog<Long> dlg = new Dialog<>();
            dlg.setTitle("Elegir producto");
            dlg.setHeaderText("¿Qué producto de tu tienda es «" + (f.linea.getDescripcion() == null ? f.txtDescripcion.getText() : f.linea.getDescripcion()) + "»?");
            dlg.initOwner(stage);
            TextField buscar = new TextField();
            buscar.setPromptText("Busca por nombre o código");
            ListView<Object[]> lista = new ListView<>();          // {id, texto}
            lista.setPrefHeight(280);
            lista.setCellFactory(v -> new ListCell<>() {
                @Override protected void updateItem(Object[] it, boolean vacio) {
                    super.updateItem(it, vacio);
                    setText(vacio || it == null ? null : (String) it[1]);
                }
            });
            if (f.rev != null) {
                for (TicketProveedorService.Propuesta p : f.rev.candidatos()) {
                    String como = switch (p.origen()) {
                        case "APRENDIDO" -> "ya lo habías confirmado";
                        case "CODIGO_BARRAS" -> "mismo código";
                        default -> "se parece " + Math.round(p.puntaje() * 100) + " %";
                    };
                    lista.getItems().add(new Object[] { p.productoId(), p.descripcion() + "   (" + como + ")" });
                }
            }
            List<Producto> catalogo = productoService.listarTodos().stream().filter(Producto::isActivo).toList();
            buscar.textProperty().addListener((o, a, b) -> {
                if (b == null || b.isBlank()) return;
                lista.getItems().setAll(ProductoService.filtrar(catalogo, b).stream().limit(80)
                        .map(p -> new Object[] { p.getId(), p.getDescripcion() + "   (" + p.getCodigoBarras() + ")" }).toList());
            });
            lista.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && lista.getSelectionModel().getSelectedItem() != null) {
                    dlg.setResult((Long) lista.getSelectionModel().getSelectedItem()[0]);
                    dlg.close();
                }
            });
            VBox v = new VBox(8, buscar, lista);
            v.setPadding(new Insets(10));
            v.setPrefWidth(520);
            dlg.getDialogPane().setContent(v);
            ButtonType elegir = new ButtonType("Es este", ButtonBar.ButtonData.OK_DONE);
            dlg.getDialogPane().getButtonTypes().addAll(elegir, ButtonType.CANCEL);
            dlg.setResultConverter(b -> b == elegir && lista.getSelectionModel().getSelectedItem() != null
                    ? (Long) lista.getSelectionModel().getSelectedItem()[0] : null);
            Ventanas.estilizar(dlg);
            Platform.runLater(buscar::requestFocus);
            Optional<Long> r = dlg.showAndWait();
            if (r.isEmpty() || r.get() == null) return;
            Producto p = catalogo.stream().filter(x -> x.getId().equals(r.get())).findFirst().orElse(null);
            if (p == null) return;
            f.productoId = p.getId();
            f.productoNombre = p.getDescripcion();
            f.nuevo = null;
            f.dudoso = false;
            f.txtPiezas.getStyleClass().remove("ticket-campo-revisar");
            pintarFila(f);
        }

        void crearProducto(Fila f) {
            String desc = f.linea.getDescripcion() != null ? f.linea.getDescripcion() : f.txtDescripcion.getText();
            double cant = CalculoCobro.parsearNumero(f.txtCant.getText());
            double piezas = CalculoCobro.parsearNumero(f.txtPiezas.getText());
            double importe = CalculoCobro.parsearNumero(f.txtImporte.getText());
            double costo = cant > 0 && piezas >= 1 && importe >= 0
                    ? TicketProveedorService.costoPorPieza(importe, factor(), cant, (int) Math.round(piezas)) : 0;

            Dialog<ProductoNuevo> dlg = new Dialog<>();
            dlg.setTitle("Crear producto");
            dlg.setHeaderText("Producto nuevo desde el ticket: «" + desc + "»");
            dlg.initOwner(stage);
            TextField txtNombre = new TextField(nombreLimpio(desc));
            TextField txtCodigo = new TextField();
            txtCodigo.setPromptText("Escanéalo o déjalo vacío (se pone un código interno)");
            ComboBox<Departamento> cmbDepto = new ComboBox<>();
            cmbDepto.getItems().setAll(productoService.listarDepartamentos());
            cmbDepto.setConverter(new StringConverter<>() {
                @Override public String toString(Departamento d) { return d == null ? "" : d.getNombre(); }
                @Override public Departamento fromString(String s) { return null; }
            });
            cmbDepto.setPromptText("(sin departamento)");
            Long deptoSugerido = f.rev != null ? f.rev.departamentoSugeridoId() : null;
            if (deptoSugerido != null) cmbDepto.getItems().stream().filter(d -> d.getId().equals(deptoSugerido)).findFirst()
                    .ifPresent(d -> cmbDepto.getSelectionModel().select(d));
            TextField txtPrecio = new TextField();
            Label lblSugerido = new Label();
            lblSugerido.getStyleClass().add("ticket-secundario");
            lblSugerido.setWrapText(true);
            Runnable sugerir = () -> {
                Long d = cmbDepto.getValue() == null ? null : cmbDepto.getValue().getId();
                double margen = servicio.margenHabitual(d);
                double sug = TicketProveedorService.redondearArriba50(costo * (1 + margen));
                txtPrecio.setText(String.format(Locale.ROOT, "%.2f", sug));
                lblSugerido.setText(revision.costosVisibles()
                        ? String.format(Locale.ROOT, "Te cuesta %s la pieza. Precio sugerido con tu ganancia habitual (%d %%): %s",
                                Moneda.formato(costo), Math.round(margen * 100), Moneda.formato(sug))
                        : "Precio sugerido con la ganancia habitual de la tienda.");
            };
            cmbDepto.valueProperty().addListener((o, a, b) -> sugerir.run());
            sugerir.run();
            GridPane g = new GridPane();
            g.setHgap(10);
            g.setVgap(8);
            g.setPadding(new Insets(10));
            g.addRow(0, new Label("Nombre:"), txtNombre);
            g.addRow(1, new Label("Código de barras:"), txtCodigo);
            g.addRow(2, new Label("Departamento:"), cmbDepto);
            g.addRow(3, new Label("Precio de venta:"), txtPrecio);
            g.add(lblSugerido, 0, 4, 2, 1);
            GridPane.setHgrow(txtNombre, Priority.ALWAYS);
            txtNombre.setPrefColumnCount(28);
            dlg.getDialogPane().setContent(g);
            ButtonType ok = new ButtonType("Usar este producto", ButtonBar.ButtonData.OK_DONE);
            dlg.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
            Button btnOk = (Button) dlg.getDialogPane().lookupButton(ok);
            btnOk.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                double pv = CalculoCobro.parsearNumero(txtPrecio.getText());
                if (txtNombre.getText().isBlank() || !(pv > 0)) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Crear producto", "Escribe el nombre y un precio de venta mayor a cero.");
                    e.consume();
                }
            });
            dlg.setResultConverter(b -> b != ok ? null : new ProductoNuevo(txtNombre.getText().trim(), txtCodigo.getText().trim(),
                    cmbDepto.getValue() == null ? null : cmbDepto.getValue().getId(), CalculoCobro.parsearNumero(txtPrecio.getText())));
            Ventanas.estilizar(dlg);
            Platform.runLater(txtNombre::requestFocus);
            dlg.showAndWait().ifPresent(n -> {
                f.nuevo = n;
                f.productoId = null;
                f.dudoso = false;
                pintarFila(f);
            });
        }

        /**
         * «Nuevo…»: nombre, RFC y teléfono (lo leído del ticket ya viene escrito). Si ya existe uno con ese RFC o
         * nombre se ofrece usar ese. Elegirlo NO vuelve a armar la revisión: lo que ya editaste en los renglones
         * se queda (un proveedor recién creado no tiene nada aprendido, la revisión sería la misma).
         */
        void nuevoProveedor() {
            var sugerencia = TicketProveedorService.sugerenciaProveedorNuevo(revision.ticket());
            Dialog<String[]> dlg = new Dialog<>();
            dlg.setTitle("Nuevo proveedor");
            dlg.setHeaderText("Datos del proveedor (llenados con lo que se leyó del ticket)");
            dlg.initOwner(stage);
            TextField txt = new TextField(sugerencia.nombre() == null ? "" : sugerencia.nombre());
            txt.setPrefColumnCount(30);
            TextField txtRfc = new TextField(sugerencia.rfc() == null ? "" : sugerencia.rfc());
            txtRfc.setPromptText("Opcional");
            TextField txtTel = new TextField();
            txtTel.setPromptText("Opcional");
            GridPane g = new GridPane();
            g.setHgap(8);
            g.setVgap(8);
            g.setPadding(new Insets(10));
            g.addRow(0, new Label("Nombre:"), txt);
            g.addRow(1, new Label("RFC:"), txtRfc);
            g.addRow(2, new Label("Teléfono:"), txtTel);
            dlg.getDialogPane().setContent(g);
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dlg.setResultConverter(b -> b == ButtonType.OK ? new String[] {txt.getText().trim(), txtRfc.getText().trim(), txtTel.getText().trim()} : null);
            Ventanas.estilizar(dlg);
            Platform.runLater(txt::requestFocus);
            Optional<String[]> datos = dlg.showAndWait().filter(d -> !d[0].isBlank());
            if (datos.isEmpty()) return;
            try {
                TicketProveedorService.AltaProveedor alta = servicio.altaProveedorDeTicket(datos.get()[0], datos.get()[1], datos.get()[2]);
                Proveedor p = alta.proveedor();
                if (!alta.creado()) {
                    if (!p.isActivo()) {
                        Ventanas.alerta(Alert.AlertType.WARNING, "Nuevo proveedor",
                                "Ya existe «" + p.getNombre() + "», pero está desactivado. Reactívalo en Proveedores.");
                        return;
                    }
                    Ventanas.alerta(Alert.AlertType.INFORMATION, "Nuevo proveedor",
                            "Ya tenías a «" + p.getNombre() + "»: se eligió ese en lugar de crear otro igual.");
                }
                cambiandoProveedor = true;
                try {
                    Proveedor enLista = cmbProveedor.getItems().stream().filter(x -> x.getId().equals(p.getId())).findFirst().orElse(null);
                    if (enLista == null) { cmbProveedor.getItems().add(p); enLista = p; }
                    cmbProveedor.getSelectionModel().select(enLista);
                } finally { cambiandoProveedor = false; }
                lblMotivo.setText("Elegido por ti.");
            } catch (NegocioException e) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Nuevo proveedor", e.getMessage());
            }
        }

        // ── 4. Registrar ───────────────────────────────────────────────────

        void registrar() {
            Proveedor prov = cmbProveedor.getValue();
            if (prov == null) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Registrar entrada", "Elige el proveedor del ticket.");
                return;
            }
            if (filas.isEmpty()) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Registrar entrada", "No hay renglones para registrar.");
                return;
            }
            List<RenglonAplicar> rs = new ArrayList<>();
            for (Fila f : filas) {
                double cant = CalculoCobro.parsearNumero(f.txtCant.getText());
                double piezas = CalculoCobro.parsearNumero(f.txtPiezas.getText());
                double importe = CalculoCobro.parsearNumero(f.txtImporte.getText());
                String desc = f.linea.getDescripcion() != null ? f.linea.getDescripcion() : f.txtDescripcion.getText().trim();
                if (!(cant > 0) || !(piezas >= 1) || piezas != Math.floor(piezas) || Double.isNaN(importe) || importe < 0) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Registrar entrada", "Revisa la cantidad, las piezas por unidad (número entero) y el importe de «" + desc + "».");
                    return;
                }
                if (f.productoId == null && f.nuevo == null) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Registrar entrada", "Falta elegir el producto de la tienda para «" + desc + "» (Otro… o Crear…), o quita ese renglón.");
                    return;
                }
                rs.add(new RenglonAplicar(f.linea.getCodigoProveedor(), desc, f.productoId, f.nuevo, cant, (int) piezas, importe, true));
            }
            TicketLeido t = revision.ticket();
            var cuadreActual = lblCuadre.getStyleClass().contains("ticket-no-cuadra");
            if (cuadreActual && !Ventanas.confirmar("Registrar entrada", lblCuadre.getText() + "\n\n¿Registrar de todos modos?")) return;
            List<Autorizacion> auts = null;
            if (!sesionService.tienePermiso(Permiso.AJUSTAR_INVENTARIO)) {
                Optional<Autorizacion> a = Ventanas.pedirAutorizacion(autorizacionService, Permiso.AJUSTAR_INVENTARIO);
                if (a.isEmpty()) return;
                auts = List.of(a.get());
            }
            TicketProveedorService.Solicitud s = new TicketProveedorService.Solicitud(prov.getId(), txtFolio.getText(),
                    dpFecha.getValue(), t.getTotal(), factor(), origen, t.getTexto(), rs, chkRepetido.isSelected());
            TicketProveedorService.Resultado res;
            try {
                res = servicio.aplicar(s, auts);
            } catch (NegocioException e) {
                Ventanas.alerta(Alert.AlertType.WARNING, "Registrar entrada", e.getMessage());
                return;
            }
            registrados++;
            if (pendienteId != null) {
                try {
                    porRevisar.marcarRegistrado(pendienteId, res.registroId());
                } catch (RuntimeException e) {
                    System.err.println("No se pudo marcar el ticket por revisar #" + pendienteId + ": " + e.getMessage());
                }
            }
            mostrarResultado(prov, res);
            if (actual + 1 < tickets.size()) {
                actual++;
                revisar(null);
            } else {
                stage.close();
            }
        }

        void mostrarResultado(Proveedor prov, TicketProveedorService.Resultado res) {
            Dialog<Void> dlg = new Dialog<>();
            dlg.setTitle("Entrada registrada");
            dlg.setHeaderText(String.format("Listo: entraron %d producto(s), %s pieza(s), de %s.", res.renglones(),
                    numero(res.piezas()), prov.getNombre()));
            dlg.initOwner(stage);
            dlg.getDialogPane().setContent(contenidoResultado(res));
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            Ventanas.estilizar(dlg);
            dlg.showAndWait();
        }

        // ── Foto al lado ───────────────────────────────────────────────────

        Node panelFoto() {
            vistaFoto = new ImageView(imagen);
            vistaFoto.setPreserveRatio(true);
            vistaFoto.setSmooth(true);
            scrollFoto = new ScrollPane(new StackPane(vistaFoto));
            scrollFoto.setPannable(true);
            Slider zoom = new Slider(0.2, 3.0, 1.0);
            vistaFoto.fitWidthProperty().bind(scrollFoto.widthProperty().subtract(20).multiply(zoom.valueProperty()));
            HBox controles = new HBox(8, new Icono("buscar"), zoom);
            controles.setAlignment(Pos.CENTER_LEFT);
            controles.setPadding(new Insets(4, 8, 4, 8));
            HBox.setHgrow(zoom, Priority.ALWAYS);
            VBox v = new VBox(controles, scrollFoto);
            VBox.setVgrow(scrollFoto, Priority.ALWAYS);
            v.setMinWidth(200);
            return v;
        }

        /** Lleva la foto a la altura del renglón (el OCR da su posición vertical) */
        void enfocarFoto(int y) {
            if (scrollFoto == null || imagen == null || y < 0) return;
            double alto = imagen.getHeight();
            if (alto <= 0) return;
            scrollFoto.setVvalue(Math.max(0, Math.min(1, y / alto)));
        }
    }

    /** "Subieron de costo" / "Bajaron de costo" con los precios sugeridos (también lo usan las capturas de pantalla) */
    Node contenidoResultado(TicketProveedorService.Resultado res) {
        VBox v = new VBox(10);
        v.setPadding(new Insets(10));
        List<Object[]> marcados = new ArrayList<>();      // {CheckBox, TextField, productoId}
        if (!res.subieron().isEmpty()) {
            Label t = new Label("Subieron de costo: conviene subir el precio de venta");
            t.getStyleClass().add("form-section-title");
            GridPane g = new GridPane();
            g.setHgap(12);
            g.setVgap(6);
            String[] tit = { "", "Producto", "Costo antes → ahora", "Vendes en", "Ganancia ahora", "Precio nuevo" };
            for (int i = 0; i < tit.length; i++) { Label l = new Label(tit[i]); l.getStyleClass().add("ticket-secundario"); g.add(l, i, 0); }
            int r = 1;
            for (TicketProveedorService.CambioCosto c : res.subieron()) {
                CheckBox chk = new CheckBox();
                chk.setSelected(c.precioSugerido() > c.precioVenta() + 0.004);
                TextField nuevo = new TextField(String.format(Locale.ROOT, "%.2f", c.precioSugerido()));
                nuevo.setPrefColumnCount(6);
                Label ganancia = new Label(c.gananciaAhora() == null ? "—" : Math.round(c.gananciaAhora() * 100) + " %"
                        + (c.gananciaAntes() == null ? "" : " (antes " + Math.round(c.gananciaAntes() * 100) + " %)"));
                if (c.gananciaAhora() != null && c.gananciaAhora() < TicketProveedorService.MARGEN_BAJO) ganancia.getStyleClass().add("estado-agotado");
                g.addRow(r++, chk, new Label(c.descripcion()),
                        new Label(Moneda.formato(c.costoAntes()) + " → " + Moneda.formato(c.costoAhora())
                                + String.format(Locale.ROOT, "  (+%.0f %%)", c.porcentaje() * 100)),
                        new Label(Moneda.formato(c.precioVenta())), ganancia, nuevo);
                marcados.add(new Object[] { chk, nuevo, c.productoId() });
            }
            Button actualizar = new Button("Actualizar precios seleccionados", new Icono("etiqueta"));
            actualizar.getStyleClass().add("button-primary");
            boolean puede = sesionService.tienePermiso(Permiso.EDITAR_PRODUCTOS) && sesionService.tienePermiso(Permiso.CAMBIAR_PRECIOS);
            actualizar.setDisable(!puede);
            Label sinPermiso = new Label(puede ? "" : "Para cambiar precios hace falta el permiso de cambiar precios: avisa a quien administra la tienda.");
            sinPermiso.setWrapText(true);
            actualizar.setOnAction(e -> {
                List<TicketProveedorService.NuevoPrecio> ps = new ArrayList<>();
                for (Object[] m : marcados) {
                    if (!((CheckBox) m[0]).isSelected()) continue;
                    double pv = CalculoCobro.parsearNumero(((TextField) m[1]).getText());
                    if (!(pv > 0)) {
                        Ventanas.alerta(Alert.AlertType.WARNING, "Precios", "Revisa los precios nuevos: deben ser mayores a cero.");
                        return;
                    }
                    ps.add(new TicketProveedorService.NuevoPrecio((Long) m[2], pv));
                }
                try {
                    int n = servicio.actualizarPreciosVenta(ps);
                    actualizar.setDisable(true);
                    actualizar.setText(n == 0 ? "No cambió ningún precio" : "Listo: " + n + " precio(s) actualizado(s)");
                } catch (NegocioException ex) {
                    Ventanas.alerta(Alert.AlertType.WARNING, "Precios", ex.getMessage());
                }
            });
            v.getChildren().addAll(t, g, new HBox(10, actualizar, sinPermiso));
        }
        if (!res.bajaron().isEmpty()) {
            Label t = new Label("Bajaron de costo");
            t.getStyleClass().add("form-section-title");
            VBox lista = new VBox(4);
            for (TicketProveedorService.CambioCosto c : res.bajaron()) {
                lista.getChildren().add(new Label(String.format(Locale.ROOT, "%s: %s → %s (%.0f %%); vendes en %s, ahora ganas %s",
                        c.descripcion(), Moneda.formato(c.costoAntes()), Moneda.formato(c.costoAhora()), c.porcentaje() * 100,
                        Moneda.formato(c.precioVenta()), c.gananciaAhora() == null ? "—" : Math.round(c.gananciaAhora() * 100) + " %")));
            }
            v.getChildren().addAll(t, lista);
        }
        if (res.cambiosOcultos() > 0) {
            Label l = new Label(res.cambiosOcultos() + " producto(s) cambiaron de costo. Pide a quien ve las ganancias que revise sus precios de venta.");
            l.setWrapText(true);
            v.getChildren().add(l);
        }
        if (!res.productosNuevos().isEmpty()) {
            v.getChildren().add(new Label("Se crearon " + res.productosNuevos().size() + " producto(s) nuevo(s) en tu catálogo."));
        }
        if (v.getChildren().isEmpty()) v.getChildren().add(new Label("Los costos no cambiaron."));
        v.getChildren().add(new Label("La próxima vez, los productos de este proveedor se reconocerán solos."));
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(Math.min(460, 140 + 34.0 * (res.subieron().size() + res.bajaron().size())));
        sp.setPrefViewportWidth(820);
        return sp;
    }

    // ── Ayudas ─────────────────────────────────────────────────────────────

    private static Node dividir(Node izquierda, Node derecha) {
        SplitPane sp = new SplitPane(izquierda, derecha);
        sp.setOrientation(Orientation.HORIZONTAL);
        sp.setDividerPositions(0.3);
        return sp;
    }

    private static Button boton(String texto, javafx.event.EventHandler<javafx.event.ActionEvent> accion) {
        Button b = new Button(texto);
        b.setOnAction(accion);
        return b;
    }

    private static Node barra(Node... botones) {
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox h = new HBox(10);
        h.getChildren().add(espacio);
        h.getChildren().addAll(botones);
        h.setPadding(new Insets(10, 12, 12, 12));
        h.setAlignment(Pos.CENTER_RIGHT);
        return h;
    }

    private static String numero(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** "MZ CHOCO GT" → "Mz Choco Gt": solo para no gritar en mayúsculas; el usuario lo corrige */
    static String nombreLimpio(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        if (!t.equals(t.toUpperCase(Locale.ROOT))) return t;
        StringBuilder sb = new StringBuilder();
        for (String p : t.toLowerCase(Locale.ROOT).split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        return sb.toString();
    }
}
