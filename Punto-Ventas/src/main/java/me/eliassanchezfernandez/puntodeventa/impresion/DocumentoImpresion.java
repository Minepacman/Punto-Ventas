package me.eliassanchezfernandez.puntodeventa.impresion;

import java.util.ArrayList;
import java.util.List;

/**
 * Documento de texto listo para imprimir (ticket de venta, corte, orden de
 * compra), independiente del tipo de impresora. Cada impresora lo traduce a lo
 * suyo: ESC/POS lo convierte en bytes y la impresora normal lo dibuja en una
 * página. Las columnas dependen del ancho del papel (32 para 58 mm, 48 para 80 mm).
 */
public final class DocumentoImpresion {

    public enum Alineacion { IZQUIERDA, CENTRO, DERECHA }

    /** Una línea con su formato. {@code texto} va SIN relleno: la alineación la aplica quien imprime. */
    public record Linea(String texto, Alineacion alineacion, boolean negrita, boolean grande) { }

    private final List<Linea> lineas = new ArrayList<>();

    public DocumentoImpresion linea(String texto) {
        return agregar(texto, Alineacion.IZQUIERDA, false, false);
    }

    public DocumentoImpresion centrada(String texto) {
        return agregar(texto, Alineacion.CENTRO, false, false);
    }

    /** Título: centrado, negrita y tamaño doble */
    public DocumentoImpresion titulo(String texto) {
        return agregar(texto, Alineacion.CENTRO, true, true);
    }

    public DocumentoImpresion negrita(String texto) {
        return agregar(texto, Alineacion.IZQUIERDA, true, false);
    }

    public DocumentoImpresion vacia() {
        return agregar("", Alineacion.IZQUIERDA, false, false);
    }

    public DocumentoImpresion agregar(String texto, Alineacion alineacion, boolean negrita, boolean grande) {
        lineas.add(new Linea(texto == null ? "" : texto, alineacion, negrita, grande));
        return this;
    }

    public List<Linea> lineas() {
        return List.copyOf(lineas);
    }

    public boolean estaVacio() {
        return lineas.isEmpty();
    }

    /**
     * Texto plano con la alineación aplicada para {@code columnas} columnas
     * (impresora normal, pruebas y vista previa). Una línea "grande" ocupa el
     * doble de ancho en la impresora térmica, así que aquí se centra con la mitad de columnas.
     */
    public String aTexto(int columnas) {
        StringBuilder sb = new StringBuilder();
        for (Linea l : lineas) {
            int ancho = l.grande() ? Math.max(1, columnas / 2) : columnas;
            String t = l.texto();
            switch (l.alineacion()) {
                case CENTRO -> t = " ".repeat(Math.max(0, (ancho - t.length()) / 2)) + t;
                case DERECHA -> t = " ".repeat(Math.max(0, ancho - t.length())) + t;
                default -> { }
            }
            sb.append(t).append('\n');
        }
        return sb.toString();
    }
}
