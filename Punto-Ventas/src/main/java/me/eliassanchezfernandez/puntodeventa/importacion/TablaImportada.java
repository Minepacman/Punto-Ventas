package me.eliassanchezfernandez.puntodeventa.importacion;

import java.util.ArrayList;
import java.util.List;

/**
 * Contenido de un archivo tabular (CSV o XLSX) ya leído: una lista de filas de
 * texto, todas con el mismo número de columnas. La primera fila suele ser el
 * encabezado, pero eso lo decide quien la usa ({@link #conEncabezado(boolean)}).
 *
 * @param nombreArchivo nombre del archivo (para el reporte y la bitácora)
 * @param filas         todas las filas, incluida la posible fila de encabezado
 * @param columnas      número de columnas (la fila más ancha manda)
 * @param detalle       cómo se leyó: "CSV, separador ; , Windows-1252" o "Excel, hoja Productos"
 */
public record TablaImportada(String nombreArchivo, List<List<String>> filas, int columnas, String detalle) {

    public TablaImportada {
        filas = List.copyOf(rellenar(filas, columnas));
    }

    public boolean estaVacia() {
        return filas.isEmpty();
    }

    /** Nombres de columna: la primera fila si es encabezado; si no, "Columna A", "Columna B"... */
    public List<String> encabezados(boolean primeraFilaEsEncabezado) {
        List<String> nombres = new ArrayList<>(columnas);
        for (int c = 0; c < columnas; c++) {
            String texto = primeraFilaEsEncabezado && !filas.isEmpty() ? filas.get(0).get(c).trim() : "";
            nombres.add(texto.isEmpty() ? "Columna " + letraColumna(c) : texto);
        }
        return nombres;
    }

    /** Filas de datos (sin el encabezado si lo hay) */
    public List<List<String>> datos(boolean primeraFilaEsEncabezado) {
        if (filas.isEmpty()) return List.of();
        return primeraFilaEsEncabezado ? filas.subList(1, filas.size()) : filas;
    }

    /** Número de fila tal como lo ve el usuario en Excel (1 = primera fila del archivo) */
    public static int numeroDeFila(int indiceDato, boolean primeraFilaEsEncabezado) {
        return indiceDato + (primeraFilaEsEncabezado ? 2 : 1);
    }

    /** 0 → A, 25 → Z, 26 → AA (como Excel) */
    public static String letraColumna(int indice) {
        StringBuilder sb = new StringBuilder();
        int n = indice;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static List<List<String>> rellenar(List<List<String>> filas, int columnas) {
        List<List<String>> salida = new ArrayList<>(filas.size());
        for (List<String> fila : filas) {
            List<String> f = new ArrayList<>(columnas);
            for (int c = 0; c < columnas; c++) f.add(c < fila.size() && fila.get(c) != null ? fila.get(c) : "");
            salida.add(List.copyOf(f));
        }
        return salida;
    }
}
