package me.eliassanchezfernandez.puntodeventa.importacion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Punto de entrada: elige el lector por la extensión (o por el contenido si la extensión engaña) */
public final class LectorArchivo {

    private LectorArchivo() { }

    public static TablaImportada leer(Path archivo) throws IOException {
        if (archivo == null || !Files.isRegularFile(archivo)) {
            throw new IOException("No se encontró el archivo.");
        }
        String nombre = archivo.getFileName().toString().toLowerCase(Locale.ROOT);
        byte[] firma = firma(archivo);
        // Por contenido primero: eleventa exporta .xls aunque el usuario lo renombre, y Excel guarda .xlsx como zip
        if (esOle2(firma)) return LectorXls.leer(archivo);
        if (esZip(firma)) return LectorXlsx.leer(archivo);
        if (nombre.endsWith(".xls") || nombre.endsWith(".xlsx") || nombre.endsWith(".xlsm")) {
            // Extensión de Excel pero contenido de texto: algunos programas exportan CSV/HTML con nombre .xls
            if (pareceHtml(archivo)) {
                throw new IOException("El archivo es una página HTML con extensión de Excel. "
                        + "Ábrelo en Excel y guárdalo como \"Libro de Excel (.xlsx)\" o \"CSV\".");
            }
        }
        return LectorCsv.leer(archivo);
    }

    private static byte[] firma(Path archivo) throws IOException {
        try (var in = Files.newInputStream(archivo)) {
            return in.readNBytes(8);
        }
    }

    /** Un .xlsx es un zip: empieza con "PK" */
    static boolean esZip(byte[] firma) {
        return firma.length >= 2 && firma[0] == 'P' && firma[1] == 'K';
    }

    /** Un .xls (Excel 97-2003) es un contenedor OLE2: D0 CF 11 E0 A1 B1 1A E1 */
    static boolean esOle2(byte[] firma) {
        return firma.length >= 8 && (firma[0] & 0xFF) == 0xD0 && (firma[1] & 0xFF) == 0xCF
                && (firma[2] & 0xFF) == 0x11 && (firma[3] & 0xFF) == 0xE0;
    }

    private static boolean pareceHtml(Path archivo) {
        try (var in = Files.newInputStream(archivo)) {
            String inicio = new String(in.readNBytes(512), java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            return inicio.contains("<html") || inicio.contains("<table") || inicio.contains("<!doctype");
        } catch (IOException e) {
            return false;
        }
    }
}
