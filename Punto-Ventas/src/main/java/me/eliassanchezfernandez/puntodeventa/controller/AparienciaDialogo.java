package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.service.AparienciaService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Icono;
import me.eliassanchezfernandez.puntodeventa.util.PaquetesIconos;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import me.eliassanchezfernandez.puntodeventa.util.ModoLigero;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.AplicadorApariencia;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Apariencia;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Paleta;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.RecuerdoApariencia;
import me.eliassanchezfernandez.puntodeventa.util.apariencia.Rgb;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuración → Apariencia: tema, color, iconos, letra y efecto vidrio, con una vista previa que
 * cambia al instante. "Aplicar" la guarda (toda la tienda, solo el administrador; o "solo para mí",
 * cualquier usuario) y la pone en todas las ventanas abiertas sin reiniciar. "Restablecer" vuelve a lo
 * de fábrica en el formulario. La página móvil la toma al recargar. Los tickets impresos no cambian.
 */
@Component
public class AparienciaDialogo {

    /** Iconos de muestra de cada paquete */
    static final List<String> MUESTRA_ICONOS = List.of("tienda", "buscar", "efectivo", "paquete", "usuario",
            "impresora", "grafica", "ajustes");

    @Autowired private AparienciaService aparienciaService;
    @Autowired private SesionService sesionService;

    /** Abre la ventana (modal) */
    public void abrir(Window owner) {
        Stage stage = crear(owner);
        stage.showAndWait();
    }

    /** Crea la ventana sin mostrarla (también la usa la prueba de capturas) */
    public Stage crear(Window owner) {
        Formulario f = new Formulario();
        double[] tam = Ventanas.ajustarAPantalla(1000, 700, javafx.stage.Screen.getPrimary().getVisualBounds());
        Stage stage = new Stage();
        stage.setTitle("Apariencia");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        Ventanas.aplicarIcono(stage);
        Scene scene = new Scene(f.raiz, tam[0], tam[1]);
        Ventanas.aplicarCss(scene);
        Ventanas.instalarTecladoModal(scene, stage);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(tam[0], 760));
        stage.setMinHeight(Math.min(tam[1], 520));
        f.stage = stage;
        return stage;
    }

    /** Todo el estado de una ventana abierta */
    private final class Formulario {
        final BorderPane raiz = new BorderPane();
        final VistaPreviaApariencia vista = new VistaPreviaApariencia();
        final Label estado = new Label();
        final boolean admin = sesionService.esAdmin();
        Stage stage;
        Apariencia candidata;
        boolean cargando;

        final ToggleGroup grupoAmbito = new ToggleGroup();
        final RadioButton rbTienda = new RadioButton("Toda la tienda");
        final RadioButton rbPropia = new RadioButton("Solo para mí");
        final ToggleGroup grupoTema = new ToggleGroup();
        final FlowPane muestrasColor = new FlowPane(6, 6);
        final ColorPicker selectorColor = new ColorPicker();
        final Label infoContraste = new Label();
        final ToggleGroup grupoPaquete = new ToggleGroup();
        final ToggleGroup grupoEstilo = new ToggleGroup();
        final FlowPane botonesEstilo = new FlowPane(6, 6);
        final ToggleGroup grupoGrosor = new ToggleGroup();
        final ToggleGroup grupoTamanoIconos = new ToggleGroup();
        final ToggleGroup grupoLetra = new ToggleGroup();
        final ToggleGroup grupoEscala = new ToggleGroup();
        final CheckBox chkVidrio = new CheckBox("Paneles translúcidos (efecto vidrio)");
        final Label avisoVidrio = new Label();
        final Button btnQuitarPropia = new Button("Usar la de la tienda");

        Formulario() {
            raiz.getStyleClass().add("apariencia-ventana");
            boolean tienePropia = aparienciaService.tienePropia();
            Cajero yo = sesionService.getCajeroActual();
            rbPropia.setText("Solo para mí" + (yo != null ? " (" + yo.getUsuario() + ")" : ""));
            rbTienda.setToggleGroup(grupoAmbito);
            rbPropia.setToggleGroup(grupoAmbito);
            if (!admin) rbTienda.setDisable(true);
            (admin && !tienePropia ? rbTienda : rbPropia).setSelected(true);
            candidata = rbTienda.isSelected() ? aparienciaService.deTienda() : aparienciaService.vigente();

            VBox form = new VBox(14,
                    seccion("Para quién", "usuarios", ambito()),
                    seccion("Tema", "sol", temas()),
                    seccion("Color principal", "imagen", colores()),
                    seccion("Iconos", "estrella", iconos()),
                    seccion("Letra", "documento", letra()),
                    seccion("Efecto vidrio (Liquid Glass)", "idea", vidrio()),
                    seccion("Modo ligero (para computadoras lentas)", "energia", modoLigero()));
            form.setPadding(new Insets(14, 16, 14, 16));
            ScrollPane scroll = new ScrollPane(form);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefWidth(470);
            scroll.setMinWidth(380);

            Label tituloVista = new Label("Vista previa");
            tituloVista.getStyleClass().add("form-section-title");
            Label notaVista = new Label("Así se verá. Nada cambia hasta que pulses Aplicar. Los tickets impresos no cambian.");
            notaVista.getStyleClass().add("hint");
            notaVista.setWrapText(true);
            ScrollPane scrollVista = new ScrollPane(vista);
            scrollVista.setFitToWidth(true);
            scrollVista.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            VBox.setVgrow(scrollVista, Priority.ALWAYS);
            VBox derecha = new VBox(6, tituloVista, notaVista, scrollVista);
            derecha.setPadding(new Insets(14, 16, 8, 8));
            HBox.setHgrow(derecha, Priority.ALWAYS);
            HBox centro = new HBox(scroll, derecha);
            HBox.setHgrow(scroll, Priority.SOMETIMES);
            raiz.setCenter(centro);
            raiz.setBottom(botones(tienePropia));
            cargarEnFormulario(candidata);
        }

        // ── Secciones ───────────────────────────────────────────────────

        private VBox seccion(String titulo, String icono, Node contenido) {
            Label t = new Label(titulo, new Icono(icono, 16));
            t.getStyleClass().add("form-section-title");
            VBox v = new VBox(8, t, contenido);
            v.getStyleClass().add("apariencia-seccion");
            return v;
        }

        private Node ambito() {
            Label pista = new Label(admin
                    ? "\"Solo para mí\" sirve, por ejemplo, para que un cajero use letra más grande sin cambiar la de los demás."
                    : "Solo el administrador cambia la apariencia de toda la tienda. Tú puedes elegir una solo para ti.");
            pista.getStyleClass().add("hint");
            pista.setWrapText(true);
            grupoAmbito.selectedToggleProperty().addListener((o, a, b) -> {
                if (b == null || cargando) return;
                Apariencia nueva = b == rbTienda ? aparienciaService.deTienda() : aparienciaService.vigente();
                cargarEnFormulario(nueva);
            });
            return new VBox(6, new HBox(16, rbTienda, rbPropia), pista);
        }

        private Node temas() {
            FlowPane p = new FlowPane(6, 6);
            for (Apariencia.Tema t : Apariencia.Tema.values()) {
                ToggleButton b = new ToggleButton(t.nombre(), muestraTema(t));
                b.setUserData(t);
                b.setToggleGroup(grupoTema);
                b.getStyleClass().add("apariencia-opcion");
                if (t == Apariencia.Tema.AUTOMATICO) {
                    b.setTooltip(new Tooltip("En los teléfonos sigue al modo claro u oscuro del teléfono; en la computadora se ve Claro."));
                }
                p.getChildren().add(b);
            }
            grupoTema.selectedToggleProperty().addListener((o, a, b) -> {
                if (b != null) cambiar(candidata.conTema((Apariencia.Tema) b.getUserData()));
                else if (a != null) a.setSelected(true);   // siempre hay uno elegido
            });
            Label pista = new Label("Alto contraste: letras negras sobre blanco y bordes marcados, para ver mejor.");
            pista.getStyleClass().add("hint");
            pista.setWrapText(true);
            return new VBox(6, p, pista);
        }

        /** Cuadrito con el fondo y el texto del tema */
        private Node muestraTema(Apariencia.Tema t) {
            Map<String, String> tok = Paleta.tokens(Apariencia.porDefecto().conTema(t).conAcento("#2F6CAD").conAcento(
                    t == Apariencia.Tema.CLARO || t == Apariencia.Tema.AUTOMATICO ? "#2F6CAE" : Apariencia.ACENTO_DE_FABRICA));
            Rectangle fondo = new Rectangle(18, 14, Color.web(tok.getOrDefault("-pos-surface", "#FFFFFF")));
            fondo.setStroke(Color.web(tok.getOrDefault("-pos-text", "#1B232E")));
            fondo.setArcWidth(4);
            fondo.setArcHeight(4);
            return fondo;
        }

        private Node colores() {
            ToggleGroup grupo = new ToggleGroup();
            for (Apariencia.Muestra m : Apariencia.ACENTOS) {
                ToggleButton b = new ToggleButton();
                Circle c = new Circle(11, Color.web(m.color()));
                c.setStroke(Color.web("#00000033"));
                b.setGraphic(c);
                b.setUserData(m.color());
                b.setToggleGroup(grupo);
                b.setTooltip(new Tooltip(m.nombre()));
                b.getStyleClass().add("muestra-color");
                b.setAccessibleText(m.nombre());
                muestrasColor.getChildren().add(b);
            }
            grupo.selectedToggleProperty().addListener((o, a, b) -> {
                if (b != null && !cargando) cambiar(candidata.conAcento((String) b.getUserData()));
            });
            selectorColor.setOnAction(e -> {
                if (cargando) return;
                Color c = selectorColor.getValue();
                String hex = String.format(Locale.ROOT, "#%02X%02X%02X",
                        (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
                cambiar(candidata.conAcento(hex));
            });
            selectorColor.getStyleClass().add("button");
            Label personal = new Label("Personalizado:");
            HBox filaPersonal = new HBox(8, personal, selectorColor);
            filaPersonal.setAlignment(Pos.CENTER_LEFT);
            infoContraste.getStyleClass().add("hint");
            infoContraste.setWrapText(true);
            return new VBox(8, muestrasColor, filaPersonal, infoContraste);
        }

        private Node iconos() {
            VBox paquetes = new VBox(4);
            for (PaquetesIconos.Paquete p : PaquetesIconos.disponibles()) {
                Label nombre = new Label(p.nombre());
                nombre.setMinWidth(150);
                nombre.setPrefWidth(150);
                HBox tira = new HBox(8);
                tira.setAlignment(Pos.CENTER_LEFT);
                String estilo0 = p.estilos().get(0).id();
                for (String n : MUESTRA_ICONOS) {
                    Icono i = new Icono(n, 18);
                    i.usarEstilo(p.id(), estilo0, 2.0, 1.0);
                    tira.getChildren().add(i);
                }
                HBox contenido = new HBox(10, nombre, tira);
                contenido.setAlignment(Pos.CENTER_LEFT);
                ToggleButton b = new ToggleButton();
                b.setGraphic(contenido);
                b.setUserData(p);
                b.setToggleGroup(grupoPaquete);
                b.setMaxWidth(Double.MAX_VALUE);
                b.setAlignment(Pos.CENTER_LEFT);
                b.getStyleClass().add("apariencia-paquete");
                b.setTooltip(new Tooltip(p.nombre() + (p.licencia().isBlank() ? "" : " · licencia " + p.licencia())));
                paquetes.getChildren().add(b);
            }
            grupoPaquete.selectedToggleProperty().addListener((o, a, b) -> {
                if (b == null) {
                    if (a != null) a.setSelected(true);
                    return;
                }
                if (cargando) return;
                PaquetesIconos.Paquete p = (PaquetesIconos.Paquete) b.getUserData();
                String estilo = p.estilo(candidata.estiloIconos()).id();
                cambiar(candidata.conPaquete(p.id(), estilo));
                rehacerEstilos();
            });
            grupoEstilo.selectedToggleProperty().addListener((o, a, b) -> {
                if (b != null && !cargando) cambiar(candidata.conEstiloIconos((String) b.getUserData()));
            });
            FlowPane grosor = new FlowPane(6, 6);
            for (Apariencia.Grosor g : Apariencia.Grosor.values()) {
                ToggleButton b = new ToggleButton(g.nombre());
                b.setUserData(g);
                b.setToggleGroup(grupoGrosor);
                b.getStyleClass().add("apariencia-opcion");
                grosor.getChildren().add(b);
            }
            grupoGrosor.selectedToggleProperty().addListener((o, a, b) -> {
                if (b != null && !cargando) cambiar(candidata.conGrosor((Apariencia.Grosor) b.getUserData()));
            });
            FlowPane tamano = new FlowPane(6, 6);
            for (boolean grande : new boolean[]{false, true}) {
                ToggleButton b = new ToggleButton(grande ? "Grandes" : "Normales");
                b.setUserData(grande);
                b.setToggleGroup(grupoTamanoIconos);
                b.getStyleClass().add("apariencia-opcion");
                tamano.getChildren().add(b);
            }
            grupoTamanoIconos.selectedToggleProperty().addListener((o, a, b) -> {
                if (b != null && !cargando) cambiar(candidata.conIconosGrandes((Boolean) b.getUserData()));
            });
            return new VBox(8, paquetes,
                    fila("Estilo", botonesEstilo),
                    fila("Trazo", grosor),
                    fila("Tamaño", tamano));
        }

        /** Los estilos que ofrece el paquete elegido */
        private void rehacerEstilos() {
            boolean antes = cargando;
            cargando = true;
            try {
                botonesEstilo.getChildren().clear();
                grupoEstilo.getToggles().clear();
                PaquetesIconos.Paquete p = PaquetesIconos.paquete(candidata.paqueteIconos());
                if (p == null) p = PaquetesIconos.paquete("lucide");
                for (PaquetesIconos.Estilo e : p.estilos()) {
                    Icono muestra = new Icono("paquete", 16);
                    muestra.usarEstilo(p.id(), e.id(), 2.0, 1.0);
                    ToggleButton b = new ToggleButton(e.nombre(), muestra);
                    b.setUserData(e.id());
                    b.setToggleGroup(grupoEstilo);
                    b.getStyleClass().add("apariencia-opcion");
                    if (e.id().equals(candidata.estiloIconos())) b.setSelected(true);
                    botonesEstilo.getChildren().add(b);
                }
                if (grupoEstilo.getSelectedToggle() == null && !grupoEstilo.getToggles().isEmpty()) {
                    grupoEstilo.getToggles().get(0).setSelected(true);
                    candidata = candidata.conEstiloIconos((String) grupoEstilo.getToggles().get(0).getUserData());
                }
                actualizarGrosorDisponible();
            } finally {
                cargando = antes;
            }
        }

        /** El grosor solo aplica a iconos de trazo */
        private void actualizarGrosorDisponible() {
            PaquetesIconos.Paquete p = PaquetesIconos.paquete(candidata.paqueteIconos());
            boolean deTrazo = p == null || p.estilo(candidata.estiloIconos()).deTrazo();
            for (var t : grupoGrosor.getToggles()) ((ToggleButton) t).setDisable(!deTrazo);
        }

        private Node letra() {
            FlowPane familias = new FlowPane(6, 6);
            for (Apariencia.Letra l : Apariencia.Letra.values()) {
                AplicadorApariencia.cargarFuente(l);
                ToggleButton b = new ToggleButton(l.nombre());
                if (l.familia() != null) b.setStyle("-fx-font-family: \"" + l.familia() + "\";");
                b.setUserData(l);
                b.setToggleGroup(grupoLetra);
                b.getStyleClass().add("apariencia-opcion");
                familias.getChildren().add(b);
            }
            grupoLetra.selectedToggleProperty().addListener((o, a, b) -> {
                if (b != null && !cargando) cambiar(candidata.conLetra((Apariencia.Letra) b.getUserData()));
            });
            FlowPane escalas = new FlowPane(6, 6);
            String[] nombres = {"Pequeña", "Normal", "Grande", "Muy grande"};
            for (int i = 0; i < Apariencia.ESCALAS.size(); i++) {
                int e = Apariencia.ESCALAS.get(i);
                ToggleButton b = new ToggleButton(nombres[i] + " (" + e + " %)");
                b.setUserData(e);
                b.setToggleGroup(grupoEscala);
                b.getStyleClass().add("apariencia-opcion");
                escalas.getChildren().add(b);
            }
            grupoEscala.selectedToggleProperty().addListener((o, a, b) -> {
                if (b != null && !cargando) cambiar(candidata.conEscala((Integer) b.getUserData()));
            });
            Label pista = new Label("Atkinson Hyperlegible está hecha para personas con baja visión. Con letra Grande o Muy grande "
                    + "algunas pantallas necesitan desplazarse en monitores pequeños.");
            pista.getStyleClass().add("hint");
            pista.setWrapText(true);
            return new VBox(8, familias, fila("Tamaño", escalas), pista);
        }

        private Node vidrio() {
            chkVidrio.selectedProperty().addListener((o, a, b) -> {
                if (!cargando) cambiar(candidata.conVidrio(b));
            });
            Label pista = new Label("Paneles semitransparentes con borde de luz sobre un fondo de color. Es solo decorativo.");
            pista.getStyleClass().add("hint");
            pista.setWrapText(true);
            avisoVidrio.setText("Puede hacer lento este equipo (tiene pocos núcleos o poca memoria). "
                    + "Si lo notas lento, quita esta casilla o pulsa Restablecer.");
            avisoVidrio.setWrapText(true);
            avisoVidrio.getStyleClass().addAll("callout", "callout-warning");
            avisoVidrio.setGraphic(new Icono("alerta", 16));
            avisoVidrio.setVisible(false);
            avisoVidrio.setManaged(false);
            Label beta = new Label("Beta");
            beta.getStyleClass().add("etiqueta-beta");
            beta.setTooltip(new Tooltip("En etapa beta: puede cambiar o tener detalles."));
            HBox filaVidrio = new HBox(8, chkVidrio, beta);
            filaVidrio.setAlignment(Pos.CENTER_LEFT);
            return new VBox(6, filaVidrio, pista, avisoVidrio);
        }

        /**
         * Modo ligero: de ESTA computadora (no de la tienda ni del usuario) y se aplica al momento, sin
         * esperar a «Aplicar» (ver util/ModoLigero).
         */
        private Node modoLigero() {
            ToggleGroup grupo = new ToggleGroup();
            HBox opciones = new HBox(12);
            Label estadoLigero = new Label(ModoLigero.descripcionEstado());
            estadoLigero.getStyleClass().add("hint");
            estadoLigero.setWrapText(true);
            for (ModoLigero.Preferencia p : ModoLigero.Preferencia.values()) {
                RadioButton rb = new RadioButton(p == ModoLigero.Preferencia.AUTOMATICO ? "Automático (recomendado)" : p.nombre());
                rb.setToggleGroup(grupo);
                rb.setUserData(p);
                rb.setSelected(p == ModoLigero.preferencia());
                opciones.getChildren().add(rb);
            }
            grupo.selectedToggleProperty().addListener((o, a, b) -> {
                if (b == null) return;
                ModoLigero.guardarPreferencia((ModoLigero.Preferencia) b.getUserData());
                AplicadorApariencia.reaplicar();
                estadoLigero.setText(ModoLigero.descripcionEstado());
            });
            Label pista = new Label("Quita sombras, el efecto vidrio y las animaciones para que la pantalla responda más "
                    + "rápido. Automático lo enciende si esta computadora dibuja sin tarjeta de video. "
                    + "Solo cambia esta computadora y se aplica al momento.");
            pista.getStyleClass().add("hint");
            pista.setWrapText(true);
            return new VBox(6, opciones, estadoLigero, pista);
        }

        private HBox fila(String titulo, Node contenido) {
            Label l = new Label(titulo);
            l.getStyleClass().add("field-label");
            l.setMinWidth(60);
            HBox h = new HBox(10, l, contenido);
            h.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(contenido, Priority.ALWAYS);
            return h;
        }

        private Node botones(boolean tienePropia) {
            Button restablecer = new Button("Restablecer", new Icono("actualizar", 16));
            restablecer.setTooltip(new Tooltip("Vuelve a lo de fábrica (claro, azul, Lucide, letra normal, sin vidrio). Luego pulsa Aplicar."));
            restablecer.setOnAction(e -> cargarEnFormulario(Apariencia.porDefecto()));
            btnQuitarPropia.setTooltip(new Tooltip("Borra tu apariencia propia y vuelves a ver la de la tienda."));
            btnQuitarPropia.setOnAction(e -> quitarPropia());
            btnQuitarPropia.setVisible(tienePropia);
            btnQuitarPropia.setManaged(tienePropia);
            Button cerrar = new Button("Cerrar");
            cerrar.setCancelButton(true);
            cerrar.setOnAction(e -> stage.close());
            Button aplicar = new Button("Aplicar", new Icono("listo", 16));
            aplicar.getStyleClass().add("button-primary");
            aplicar.setDefaultButton(true);
            aplicar.setOnAction(e -> aplicar());
            estado.getStyleClass().add("text-success");
            Region esp = new Region();
            HBox.setHgrow(esp, Priority.ALWAYS);
            HBox barra = new HBox(8, restablecer, btnQuitarPropia, estado, esp, cerrar, aplicar);
            barra.setAlignment(Pos.CENTER_LEFT);
            barra.setPadding(new Insets(10, 16, 12, 16));
            barra.getStyleClass().add("ticket-footer");
            return barra;
        }

        // ── Estado ──────────────────────────────────────────────────────

        private void cambiar(Apariencia nueva) {
            candidata = nueva;
            estado.setText("");
            refrescar();
        }

        private void refrescar() {
            vista.mostrar(candidata);
            Rgb acento = candidata.colorAcento();
            Rgb texto = acento.textoEncima();
            double c = acento.contraste(texto);
            infoContraste.setText("Texto sobre este color: " + (texto.equals(Rgb.BLANCO) ? "blanco" : "negro")
                    + String.format(Locale.ROOT, " (contraste %.1f a 1%s).", c,
                    c >= Paleta.AA ? ", se lee bien" : "; se ajusta solo para que se lea"));
            boolean aviso = candidata.vidrio() && AplicadorApariencia.equipoModesto();
            avisoVidrio.setVisible(aviso);
            avisoVidrio.setManaged(aviso);
            actualizarGrosorDisponible();
            for (Node n : muestrasColor.getChildren()) {
                ToggleButton b = (ToggleButton) n;
                boolean es = candidata.acento().equalsIgnoreCase((String) b.getUserData());
                if (b.isSelected() != es) {
                    boolean antes = cargando;
                    cargando = true;
                    b.setSelected(es);
                    cargando = antes;
                }
            }
        }

        /** Pone una apariencia en todos los controles del formulario */
        private void cargarEnFormulario(Apariencia a) {
            cargando = true;
            try {
                candidata = a;
                elegir(grupoTema, a.tema());
                elegir(grupoGrosor, a.grosorIconos());
                elegir(grupoTamanoIconos, a.iconosGrandes());
                elegir(grupoLetra, a.letra());
                elegir(grupoEscala, a.escala());
                chkVidrio.setSelected(a.vidrio());
                selectorColor.setValue(Color.web(a.acento()));
                for (var t : grupoPaquete.getToggles()) {
                    if (((PaquetesIconos.Paquete) t.getUserData()).id().equals(a.paqueteIconos())) t.setSelected(true);
                }
                if (grupoPaquete.getSelectedToggle() == null && !grupoPaquete.getToggles().isEmpty()) {
                    grupoPaquete.getToggles().get(0).setSelected(true);
                    candidata = candidata.conPaquete("lucide", "contorno");
                }
            } finally {
                cargando = false;
            }
            rehacerEstilos();
            refrescar();
        }

        private void elegir(ToggleGroup grupo, Object valor) {
            for (var t : grupo.getToggles()) {
                if (valor.equals(t.getUserData())) {
                    t.setSelected(true);
                    return;
                }
            }
        }

        private void aplicar() {
            try {
                if (rbTienda.isSelected()) {
                    aparienciaService.guardarTienda(candidata);
                    RecuerdoApariencia.guardar(candidata);
                } else {
                    aparienciaService.guardarPropia(candidata);
                    btnQuitarPropia.setVisible(true);
                    btnQuitarPropia.setManaged(true);
                }
                AplicadorApariencia.aplicar(aparienciaService.vigente());
                estado.setText(rbTienda.isSelected() && aparienciaService.tienePropia()
                        ? "Guardada para la tienda (tú sigues viendo la tuya)."
                        : "Listo: aplicada. Los teléfonos la toman al recargar.");
            } catch (RuntimeException ex) {
                Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo aplicar", ex.getMessage());
            }
        }

        private void quitarPropia() {
            try {
                aparienciaService.guardarPropia(null);
                AplicadorApariencia.aplicar(aparienciaService.vigente());
                btnQuitarPropia.setVisible(false);
                btnQuitarPropia.setManaged(false);
                if (admin) rbTienda.setSelected(true);
                cargarEnFormulario(aparienciaService.deTienda());
                estado.setText("Ahora ves la apariencia de la tienda.");
            } catch (RuntimeException ex) {
                Ventanas.alerta(Alert.AlertType.ERROR, "No se pudo quitar", ex.getMessage());
            }
        }
    }

    /** Lista de nombres de paquetes (para Acerca de / pruebas) */
    static List<String> nombresPaquetes() {
        List<String> l = new ArrayList<>();
        for (PaquetesIconos.Paquete p : PaquetesIconos.disponibles()) l.add(p.nombre());
        return l;
    }
}
