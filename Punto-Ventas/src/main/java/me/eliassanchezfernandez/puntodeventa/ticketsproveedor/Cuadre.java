package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import java.util.List;

/**
 * ¿La suma de los renglones cuadra con los totales del ticket?
 *
 * @param sumaLineas          suma de los importes de los renglones
 * @param referencia          total (o subtotal, si el ticket no trae total) contra el que se comparó; null si no hay
 * @param cuadra              la suma explica el total (directo o sumando impuestos/cargos y restando descuento)
 * @param impuestosIncluidos  los precios de los renglones ya traen los impuestos (el total = la suma)
 * @param factorCosto         total / suma: lo que hay que multiplicar a cada importe para repartir impuestos no
 *                            incluidos, cargos (flete, distribución) y descuentos globales; 1 si no aplica
 * @param diferencia          lo que falta (positivo) o sobra (negativo) para llegar a la referencia
 * @param mensaje             explicación para el usuario
 * @param renglonesSospechosos índices (en la lista de líneas) de los renglones que probablemente rompen la suma
 */
public record Cuadre(double sumaLineas, Double referencia, boolean cuadra, boolean impuestosIncluidos,
                     double factorCosto, double diferencia, String mensaje, List<Integer> renglonesSospechosos) {
}
