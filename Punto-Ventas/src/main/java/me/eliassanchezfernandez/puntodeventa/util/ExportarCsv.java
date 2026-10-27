package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exportación a CSV de una tabla de la pantalla (Estadísticas). Separador
 * coma, comillas dobles cuando hace falta, UTF-8 con BOM para que Excel abra
 * los acentos bien. La parte que arma filas de texto es pura (probada); el
 * controlador solo traduce su TableView a listas de cadenas.
 */
public final class ExportarCsv {

    private ExportarCsv() { }

    /** Texto CSV completo (encabezados + filas), con fin de línea \r\n */
    public static String generar(List<String> encabezados, List<List<String>> filas) {
        StringBuilder sb = new StringBuilder();
        sb.append(linea(encabezados));
        for (List<String> fila : filas) sb.append(linea(fila));
        return sb.toString();
    }

    public static void escribir(Path destino, List<String> encabezados, List<List<String>> filas) throws IOException {
        byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
        byte[] cuerpo = generar(encabezados, filas).getBytes(StandardCharsets.UTF_8);
        byte[] todo = new byte[bom.length + cuerpo.length];
        System.arraycopy(bom, 0, todo, 0, bom.length);
        System.arraycopy(cuerpo, 0, todo, bom.length, cuerpo.length);
        Files.write(destino, todo);
    }

    static String linea(List<String> celdas) {
        List<String> escapadas = new ArrayList<>();
        for (String c : celdas) escapadas.add(escapar(c));
        return String.join(",", escapadas) + "\r\n";
    }

    private static final java.util.regex.Pattern NUMERO = java.util.regex.Pattern.compile("^[+-]?[0-9][0-9,]*(\\.[0-9]+)?$");

    /**
     * Inyección de fórmulas: Excel/LibreOffice ejecutan celdas que empiezan con
     * = + - @ (o tabulador / retorno). Se antepone una comilla simple. Los
     * números legítimos (p. ej. -5.25) no se tocan.
     */
    static String neutralizarFormula(String valor) {
        if (valor.isEmpty()) return valor;
        char c = valor.charAt(0);
        boolean peligrosa = c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
        if (peligrosa && !NUMERO.matcher(valor.trim()).matches()) return "'" + valor;
        return valor;
    }

    /** Comillas si hay coma, comillas, saltos de línea o espacios en los extremos */
    static String escapar(String valor) {
        if (valor == null) return "";
        valor = neutralizarFormula(valor);
        boolean necesita = valor.contains(",") || valor.contains("\"") || valor.contains("\n")
                || valor.contains("\r") || !valor.equals(valor.trim());
        if (!necesita) return valor;
        return "\"" + valor.replace("\"", "\"\"") + "\"";
    }
}
