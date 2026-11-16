package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Lo que el intérprete sacó de un ticket de proveedor: encabezado (pistas del proveedor, folio,
 * fecha), artículos y totales, más el cuadre de la suma. No toca la base de datos.
 */
@Getter
@Setter
public class TicketLeido {

    /** Formato reconocido (ver {@link InterpreteTicket.Formato}) */
    private InterpreteTicket.Formato formato = InterpreteTicket.Formato.GENERICO;
    /** Nombre del proveedor que sugiere el formato cuando el ticket no lo imprime ("Gamesa", "Lala"…) */
    private String proveedorPorFormato;
    /** Renglones del encabezado (antes de los artículos): ahí se busca el nombre del proveedor */
    private List<String> encabezado = new ArrayList<>();
    /** RFC encontrados (sin el genérico XAXX010101000) */
    private List<String> rfcs = new ArrayList<>();
    private String folio;
    private LocalDate fecha;
    private List<LineaTicket> lineas = new ArrayList<>();
    private Double subtotal;
    private Double descuento;
    private Double iva;
    private Double ieps;
    /** Impuestos impresos (IMPUESTOS, o IVA + IEPS) */
    private Double impuestos;
    /** Cargos aparte: costo de distribución, flete */
    private Double cargos;
    private Double total;
    /** Piezas que el ticket dice que entregó (PIEZAS, TOTAL CAJETILLAS…), para comprobar */
    private Double piezas;
    private Cuadre cuadre;
    /** Texto de este ticket (si la foto traía dos, solo el de este) */
    private String texto;
    private List<String> avisos = new ArrayList<>();

    public double sumaLineas() {
        double s = 0;
        for (LineaTicket l : lineas) s += l.getImporte();
        return TextoTicket.centavos(s);
    }
}
