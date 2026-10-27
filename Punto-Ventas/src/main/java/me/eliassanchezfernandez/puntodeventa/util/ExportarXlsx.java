package me.eliassanchezfernandez.puntodeventa.util;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Exportación a Excel (.xlsx) con fastexcel (escritor mínimo, sin Apache POI).
 * Recibe lo mismo que {@link ExportarCsv}: encabezados y filas de texto.
 *
 * Seguridad: igual que en CSV, el texto que empieza con = + - @ (o tabulador /
 * retorno) y no es un número se guarda con una comilla simple delante
 * ({@link ExportarCsv#neutralizarFormula}), para que Excel no lo trate como
 * fórmula si alguien edita la celda. Todo valor se escribe como TEXTO o como
 * número; nunca como fórmula.
 *
 * Los importes ("$1,234.50") y decimales se guardan como número para poder
 * sumarlos; los códigos de barras y enteros se quedan como texto para no
 * perder ceros a la izquierda.
 */
public final class ExportarXlsx {

    private static final Pattern DINERO = Pattern.compile("^-?\\$[0-9][0-9,]*(\\.[0-9]+)?$|^-?[0-9]{1,9}\\.[0-9]+$");
    private static final int ANCHO_MAXIMO = 60;
    /** Límite de filas de una hoja de Excel (menos el encabezado) */
    private static final int MAX_FILAS = 1_048_575;

    private ExportarXlsx() { }

    public static void escribir(Path destino, String nombreHoja, List<String> encabezados, List<List<String>> filas)
            throws IOException {
        if (filas.size() > MAX_FILAS) throw new IOException("Hay demasiadas filas para una hoja de Excel.");
        try (OutputStream salida = Files.newOutputStream(destino)) {
            Workbook libro = new Workbook(salida, "Punto de Venta", "1.0");
            Worksheet hoja = libro.newWorksheet(nombreHojaValido(nombreHoja));

            int[] ancho = new int[encabezados.size()];
            for (int c = 0; c < encabezados.size(); c++) {
                hoja.value(0, c, ExportarCsv.neutralizarFormula(texto(encabezados.get(c))));
                hoja.style(0, c).bold().fillColor("E5E7EB").set();
                ancho[c] = texto(encabezados.get(c)).length();
            }
            int r = 1;
            for (List<String> fila : filas) {
                for (int c = 0; c < fila.size() && c < encabezados.size(); c++) {
                    String valor = texto(fila.get(c));
                    ancho[c] = Math.max(ancho[c], valor.length());
                    if (esNumero(valor)) {
                        hoja.value(r, c, Double.parseDouble(valor.replace("$", "").replace(",", "")));
                        hoja.style(r, c).format("#,##0.00").set();
                    } else {
                        hoja.value(r, c, ExportarCsv.neutralizarFormula(valor));
                    }
                }
                r++;
            }
            for (int c = 0; c < ancho.length; c++) hoja.width(c, Math.min(ANCHO_MAXIMO, ancho[c] + 2));
            hoja.freezePane(0, 1);
            libro.finish();
        }
    }

    static boolean esNumero(String valor) {
        return DINERO.matcher(valor.trim()).matches();
    }

    private static String texto(String s) {
        return s == null ? "" : s;
    }

    /** Excel exige hojas de hasta 31 caracteres y sin : \ / ? * [ ] */
    static String nombreHojaValido(String nombre) {
        String limpio = (nombre == null || nombre.isBlank() ? "Datos" : nombre).replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        if (limpio.isEmpty()) limpio = "Datos";
        return limpio.length() > 31 ? limpio.substring(0, 31) : limpio;
    }
}
