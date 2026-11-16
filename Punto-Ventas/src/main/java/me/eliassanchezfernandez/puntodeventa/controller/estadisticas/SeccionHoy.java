package me.eliassanchezfernandez.puntodeventa.controller.estadisticas;

import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.AlcanceComparacion;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.CifrasDia;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.ComparacionDia;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.ResumenHoy;
import me.eliassanchezfernandez.puntodeventa.util.FechasComparacion;
import me.eliassanchezfernandez.puntodeventa.util.Iconos;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Vista simple «Hoy» (lo primero que se ve en Estadísticas): dos tarjetas
 * grandes, la venta de hoy en dinero y los tickets de hoy, cada una con tres
 * renglones llanos: vs. ayer, vs. el mismo día de la semana pasada y vs. hoy
 * hace un año, con flecha verde/roja. Los días de referencia se cortan a la
 * misma hora de hoy ("hasta las 15:00") para que la comparación sea justa;
 * si de un día viejo solo quedó el resumen, se compara el día completo y se dice.
 *
 * La pantalla la recarga al entrar y cada minuto mientras está visible.
 */
public final class SeccionHoy implements SeccionEstadisticas<ResumenHoy> {

    public static final String CLAVE = "hoy";

    private static final Locale ES_MX = new Locale("es", "MX");
    private static final DateTimeFormatter FMT_HORA  = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FMT_CORTO = DateTimeFormatter.ofPattern("EEE d/MM", ES_MX);
    private static final DateTimeFormatter FMT_LARGO = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES_MX);

    private final EstadisticasService estadisticasService;

    private Label lblFecha;
    private Label lblActualizado;
    private Label lblVenta;
    private Label lblTickets;
    private Label lblNota;
    private GridPane filasVenta;
    private GridPane filasTickets;

    public SeccionHoy(EstadisticasService estadisticasService) {
        this.estadisticasService = estadisticasService;
    }

    @Override public String clave() { return CLAVE; }
    @Override public Ubicacion ubicacion() { return Ubicacion.HOY; }

    @Override
    public Node construir(Acciones acciones) {
        lblFecha = new Label("Hoy");
        lblFecha.getStyleClass().add("card-title");
        lblActualizado = new Label("Cargando…");
        lblActualizado.getStyleClass().add("hint");
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox cabecera = new HBox(8, lblFecha, Tarjetas.botonAyuda(CLAVE, acciones), espacio, lblActualizado);
        cabecera.setAlignment(Pos.CENTER_LEFT);

        lblVenta = new Label("—");
        lblVenta.getStyleClass().add("metric-value-xl");
        filasVenta = rejilla();
        VBox tarjetaVenta = tarjeta("Venta de hoy", lblVenta, filasVenta);

        lblTickets = new Label("—");
        lblTickets.getStyleClass().add("metric-value-xl");
        filasTickets = rejilla();
        VBox tarjetaTickets = tarjeta("Ventas (tickets) de hoy", lblTickets, filasTickets);

        HBox tarjetas = new HBox(12, tarjetaVenta, tarjetaTickets);

        lblNota = new Label("");
        lblNota.getStyleClass().add("hint");
        lblNota.setWrapText(true);

        VBox caja = new VBox(8, cabecera, tarjetas, lblNota);
        return caja;
    }

    private static VBox tarjeta(String titulo, Label valor, GridPane filas) {
        Label lbl = new Label(titulo);
        lbl.getStyleClass().add("metric-label");
        VBox v = Tarjetas.tarjeta(lbl, valor, filas);
        v.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(v, Priority.ALWAYS);
        return v;
    }

    private static GridPane rejilla() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        ColumnConstraints etiqueta = new ColumnConstraints();
        etiqueta.setMinWidth(160);
        ColumnConstraints insignia = new ColumnConstraints();
        insignia.setMinWidth(90);
        insignia.setHalignment(HPos.LEFT);
        ColumnConstraints detalle = new ColumnConstraints();
        detalle.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(etiqueta, insignia, detalle);
        return g;
    }

    @Override
    public ResumenHoy cargar(ContextoEstadisticas contexto) {
        return estadisticasService.resumenHoy();
    }

    @Override
    public void pintar(ResumenHoy r) {
        lblFecha.setText("Hoy, " + r.hoy().format(FMT_LARGO));
        lblActualizado.setText("Actualizado a las " + LocalTime.now().format(FMT_HORA) + " · se actualiza solo cada minuto");
        lblVenta.setText(Moneda.formato(r.cifras().total()));
        lblTickets.setText(tickets(r.cifras().tickets()));

        filasVenta.getChildren().clear();
        filasTickets.getChildren().clear();
        int fila = 0;
        boolean algunDiaCompleto = false;
        for (ComparacionDia c : r.comparaciones()) {
            algunDiaCompleto |= c.alcance() == AlcanceComparacion.DIA_COMPLETO;
            filaVenta(filasVenta, fila, c, r);
            filaTickets(filasTickets, fila, c, r);
            fila++;
        }
        lblNota.setText(nota(r.hasta(), algunDiaCompleto));
    }

    static String nota(LocalTime hasta, boolean algunDiaCompleto) {
        String n = "Para comparar parejo, de cada día de referencia solo se cuenta lo vendido hasta las "
                + hasta.format(FMT_HORA) + " (la hora de ahora). Las ventas canceladas no cuentan.";
        if (algunDiaCompleto) n += " Donde dice «día completo» ya no quedan los tickets de ese día y se compara con todo el día.";
        return n;
    }

    // ── Renglones ─────────────────────────────────────────────────────────

    private void filaVenta(GridPane g, int fila, ComparacionDia c, ResumenHoy r) {
        CifrasDia hoy = r.cifras();
        Label insignia;
        String detalle;
        if (!c.hayReferencia()) {
            insignia = insigniaSinDatos();
            detalle = motivoSinDatos(c);
        } else {
            double dif = c.diferenciaTotal(hoy);
            Double pct = c.variacionTotalPct(hoy);
            String texto = pct != null ? porcentaje(pct) : (dif == 0 ? "Igual" : conSigno(dif));
            insignia = insignia(texto, dif);
            detalle = (pct != null && dif != 0 ? conSigno(dif) + " · " : "")
                    + referencia(c) + ": " + Moneda.formato(c.referencia().total()) + alcance(c);
        }
        g.addRow(fila, etiqueta(c), insignia, detalleLabel(detalle));
    }

    private void filaTickets(GridPane g, int fila, ComparacionDia c, ResumenHoy r) {
        CifrasDia hoy = r.cifras();
        Label insignia;
        String detalle;
        if (!c.hayReferencia()) {
            insignia = insigniaSinDatos();
            detalle = motivoSinDatos(c);
        } else {
            int dif = c.diferenciaTickets(hoy);
            Double pct = c.variacionTicketsPct(hoy);
            String texto = dif == 0 ? "Igual" : (dif > 0 ? "+" : "−") + Math.abs(dif);
            insignia = insignia(texto, dif);
            detalle = (pct != null && dif != 0 ? porcentaje(pct) + " · " : "")
                    + referencia(c) + ": " + tickets(c.referencia().tickets()) + alcance(c);
        }
        g.addRow(fila, etiqueta(c), insignia, detalleLabel(detalle));
    }

    private static Label etiqueta(ComparacionDia c) {
        Label l = new Label(c.etiqueta());
        l.getStyleClass().add("text-muted");
        return l;
    }

    private static Label detalleLabel(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        return l;
    }

    /** Insignia con flecha verde (sube), roja (baja) o gris (igual) */
    private static Label insignia(String texto, double diferencia) {
        Label b = new Label(texto);
        b.getStyleClass().add("badge");
        if (diferencia > 0) {
            b.getStyleClass().add("badge-success");
            b.setGraphic(Iconos.de("sube", 12));
        } else if (diferencia < 0) {
            b.getStyleClass().add("badge-danger");
            b.setGraphic(Iconos.de("baja", 12));
        }
        b.setContentDisplay(ContentDisplay.LEFT);
        b.setGraphicTextGap(4);
        return b;
    }

    private static Label insigniaSinDatos() {
        Label b = new Label("Sin datos");
        b.getStyleClass().add("badge");
        return b;
    }

    /** "Ayer (vie 13/11)", "El sábado pasado (sáb 7/11)", "Hace un año (vie 14/11)" */
    private static String referencia(ComparacionDia c) {
        return nombreCorto(c) + " (" + c.dia().format(FMT_CORTO).replace(".", "") + ")";
    }

    /** Qué parte del día de referencia se tomó: " hasta esa hora" (la de ahora) o " (día completo)" */
    private static String alcance(ComparacionDia c) {
        return c.alcance() == AlcanceComparacion.DIA_COMPLETO ? " (día completo)" : " hasta esa hora";
    }

    private static String nombreCorto(ComparacionDia c) {
        String e = c.etiqueta();
        if (e.equals(FechasComparacion.etiquetaAyer())) return "Ayer";
        if (e.equals(FechasComparacion.etiquetaHaceUnAnio())) return "Hace un año";
        return "El " + FechasComparacion.nombreDia(c.dia().getDayOfWeek()) + " pasado";
    }

    static String motivoSinDatos(ComparacionDia c) {
        return c.alcance() == AlcanceComparacion.LA_TIENDA_NO_VENDIA
                ? "Ese día la tienda aún no registraba ventas."
                : "Ese día no hubo ventas.";
    }

    /** "+12.5 %", "−25 %", "+1700 %": un decimal solo por debajo del 10 % */
    static String porcentaje(double pct) {
        double abs = Math.abs(pct);
        String n = abs >= 10 ? String.format(Locale.US, "%.0f", abs) : String.format(Locale.US, "%.1f", abs);
        return (pct > 0 ? "+" : pct < 0 ? "−" : "") + n + " %";
    }

    static String conSigno(double monto) {
        return (monto > 0 ? "+" : monto < 0 ? "−" : "") + Moneda.formato(Math.abs(monto));
    }

    static String tickets(int n) {
        return n == 1 ? "1 ticket" : n + " tickets";
    }

    @Override
    public void pintarError(String mensaje) {
        lblActualizado.setText("No se pudo leer la venta de hoy: " + mensaje);
        lblVenta.setText("—");
        lblTickets.setText("—");
        filasVenta.getChildren().clear();
        filasTickets.getChildren().clear();
        lblNota.setText("");
    }
}
