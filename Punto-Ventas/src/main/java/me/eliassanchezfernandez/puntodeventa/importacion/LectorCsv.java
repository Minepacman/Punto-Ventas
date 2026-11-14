package me.eliassanchezfernandez.puntodeventa.importacion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lector de CSV / TXT tolerante, pensado para exportaciones de otros puntos de
 * venta y de Excel:
 *  - Codificación: BOM (UTF-8, UTF-16 LE/BE); sin BOM se intenta UTF-8 estricto y,
 *    si hay bytes inválidos, Windows-1252 (lo que produce Excel y los programas
 *    de Windows en México: "ñ", "á" como un solo byte).
 *  - Separador: el que dé un número de columnas consistente en las primeras filas
 *    (coma, punto y coma, tabulador o barra vertical). Excel en español exporta con ";".
 *  - Comillas dobles al estilo RFC 4180 (campos con separador, saltos de línea
 *    o comillas repetidas).
 *  - Filas completamente vacías se descartan.
 */
public final class LectorCsv {

    /** Tamaño máximo que se acepta leer (los catálogos de 20 000 productos pesan ~2 MB) */
    public static final long TAMANO_MAXIMO = 64L * 1024 * 1024;
    private static final char[] SEPARADORES = {',', ';', '\t', '|'};
    private static final int FILAS_PARA_DETECTAR = 20;

    private LectorCsv() { }

    public static TablaImportada leer(Path archivo) throws IOException {
        long tamano = Files.size(archivo);
        if (tamano > TAMANO_MAXIMO) {
            throw new IOException("El archivo pesa más de 64 MB. Divídelo o exporta solo el catálogo de productos.");
        }
        byte[] bytes = Files.readAllBytes(archivo);
        Decodificado texto = decodificar(bytes);
        return leerTexto(archivo.getFileName().toString(), texto.texto(), texto.codificacion());
    }

    /** Separa la detección del separador y el troceado para poder probarlos sin archivo */
    public static TablaImportada leerTexto(String nombreArchivo, String texto, String codificacion) {
        char separador = detectarSeparador(texto);
        List<List<String>> filas = trocear(texto, separador);
        int columnas = 0;
        for (List<String> f : filas) columnas = Math.max(columnas, f.size());
        String detalle = "CSV, separador " + nombreSeparador(separador) + ", " + codificacion;
        return new TablaImportada(nombreArchivo, filas, columnas, detalle);
    }

    // ── Codificación ─────────────────────────────────────────────────────

    record Decodificado(String texto, String codificacion) { }

    static Decodificado decodificar(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Decodificado(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), "UTF-8 con BOM");
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new Decodificado(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), "UTF-16");
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new Decodificado(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), "UTF-16");
        }
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return new Decodificado(utf8, "UTF-8");
        } catch (CharacterCodingException e) {
            Charset cp1252 = Charset.forName("windows-1252");
            return new Decodificado(new String(bytes, cp1252), "Windows-1252 (ANSI)");
        }
    }

    // ── Separador ────────────────────────────────────────────────────────

    /**
     * Para cada candidato se cuenta cuántas celdas da en las primeras filas
     * (fuera de comillas); gana el que dé más de una columna con el conteo más
     * consistente. Empate: coma, punto y coma, tabulador, barra.
     */
    static char detectarSeparador(String texto) {
        List<String> lineas = primerasLineas(texto, FILAS_PARA_DETECTAR);
        char mejor = ',';
        double mejorPuntaje = -1;
        for (char sep : SEPARADORES) {
            int[] conteos = new int[lineas.size()];
            int i = 0;
            for (String linea : lineas) conteos[i++] = contarFueraDeComillas(linea, sep);
            double puntaje = puntuar(conteos);
            if (puntaje > mejorPuntaje) {
                mejorPuntaje = puntaje;
                mejor = sep;
            }
        }
        return mejor;
    }

    /** Más columnas y menos variación entre filas = mejor. Cero separadores = 0. */
    private static double puntuar(int[] conteos) {
        if (conteos.length == 0) return 0;
        int min = Integer.MAX_VALUE, max = 0;
        long suma = 0;
        for (int c : conteos) {
            min = Math.min(min, c);
            max = Math.max(max, c);
            suma += c;
        }
        if (max == 0) return 0;
        double promedio = (double) suma / conteos.length;
        // La fila de encabezado y alguna fila corta pueden variar: se penaliza la dispersión, no se descarta
        double consistencia = 1.0 / (1 + (max - min));
        return promedio * (0.5 + consistencia);
    }

    private static int contarFueraDeComillas(String linea, char sep) {
        int n = 0;
        boolean enComillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (c == '"') enComillas = !enComillas;
            else if (c == sep && !enComillas) n++;
        }
        return n;
    }

    private static List<String> primerasLineas(String texto, int maximo) {
        List<String> lineas = new ArrayList<>();
        int inicio = 0;
        while (inicio < texto.length() && lineas.size() < maximo) {
            int fin = texto.indexOf('\n', inicio);
            if (fin < 0) fin = texto.length();
            String linea = texto.substring(inicio, fin).replace("\r", "");
            if (!linea.isBlank()) lineas.add(linea);
            inicio = fin + 1;
        }
        return lineas;
    }

    static String nombreSeparador(char sep) {
        return switch (sep) {
            case ';' -> "punto y coma";
            case '\t' -> "tabulador";
            case '|' -> "barra vertical";
            default -> "coma";
        };
    }

    // ── Troceado ─────────────────────────────────────────────────────────

    /** RFC 4180: comillas dobles envuelven campos; "" dentro = una comilla; saltos de línea dentro de comillas se conservan */
    static List<List<String>> trocear(String texto, char separador) {
        List<List<String>> filas = new ArrayList<>();
        List<String> fila = new ArrayList<>();
        StringBuilder celda = new StringBuilder();
        boolean enComillas = false;
        boolean filaConContenido = false;
        int n = texto.length();
        for (int i = 0; i < n; i++) {
            char c = texto.charAt(i);
            if (enComillas) {
                if (c == '"') {
                    if (i + 1 < n && texto.charAt(i + 1) == '"') {
                        celda.append('"');
                        i++;
                    } else {
                        enComillas = false;
                    }
                } else {
                    celda.append(c);
                }
                continue;
            }
            if (c == '"') {
                enComillas = true;
                filaConContenido = true;
            } else if (c == separador) {
                fila.add(celda.toString());
                celda.setLength(0);
                filaConContenido = true;
            } else if (c == '\r') {
                // se ignora: el \n que sigue cierra la fila
            } else if (c == '\n') {
                fila.add(celda.toString());
                celda.setLength(0);
                if (filaConContenido || tieneTexto(fila)) filas.add(fila);
                fila = new ArrayList<>();
                filaConContenido = false;
            } else {
                celda.append(c);
            }
        }
        if (celda.length() > 0 || filaConContenido) {
            fila.add(celda.toString());
            if (tieneTexto(fila) || filaConContenido) filas.add(fila);
        }
        // Filas sin ningún texto (solo separadores) sobran
        filas.removeIf(f -> !tieneTexto(f));
        return filas;
    }

    private static boolean tieneTexto(List<String> fila) {
        for (String s : fila) if (s != null && !s.isBlank()) return true;
        return false;
    }
}
