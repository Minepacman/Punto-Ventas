package me.eliassanchezfernandez.puntodeventa.importacion;

import me.eliassanchezfernandez.puntodeventa.util.ExportarCsv;
import me.eliassanchezfernandez.puntodeventa.util.ExportarXlsx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plantilla de Caja Libre para capturar el catálogo en Excel: los encabezados
 * son exactamente las etiquetas de {@link CampoProducto} (así el asistente la
 * reconoce como origen CAJA_LIBRE y mapea solo) más dos filas de ejemplo que el
 * usuario borra. Se ofrece en .xlsx y en .csv (UTF-8 con BOM, que Excel abre bien).
 */
public final class PlantillaImportacion {

    private PlantillaImportacion() { }

    public static List<String> encabezadosProductos() {
        List<String> h = new ArrayList<>();
        for (CampoProducto c : CampoProducto.values()) h.add(c.etiqueta());
        return h;
    }

    public static List<List<String>> ejemplosProductos() {
        return List.of(
                List.of("7501000123456", "Refresco de cola 600 ml", "12.50", "18.00", "16.50", "Bebidas", "Pieza", "24", "6", "Sí", "50202306", "H87"),
                List.of("2001", "Jitomate", "18.00", "32.00", "", "Frutas y verduras", "Kilogramo", "7.5", "2", "Sí", "50405700", "KGM"));
    }

    public static List<String> encabezadosProveedores() {
        List<String> h = new ArrayList<>();
        for (CampoProveedor c : CampoProveedor.values()) h.add(c.etiqueta());
        return h;
    }

    public static List<List<String>> ejemplosProveedores() {
        return List.of(List.of("Distribuidora La Central", "", "282 123 4567", "Don Luis (repartidor)", "Visita lunes y jueves"));
    }

    /** Escribe la plantilla según la extensión del destino (.xlsx o .csv) */
    public static void escribir(Path destino, boolean proveedores) throws IOException {
        List<String> encabezados = proveedores ? encabezadosProveedores() : encabezadosProductos();
        List<List<String>> ejemplos = proveedores ? ejemplosProveedores() : ejemplosProductos();
        String nombre = destino.getFileName().toString().toLowerCase();
        if (nombre.endsWith(".xlsx")) {
            ExportarXlsx.escribir(destino, proveedores ? "Proveedores" : "Productos", encabezados, ejemplos);
        } else {
            ExportarCsv.escribir(destino, encabezados, ejemplos);
        }
    }

    /** Texto de ayuda que acompaña a la plantilla (lo muestra el asistente) */
    public static String ayuda(boolean proveedores) {
        StringBuilder sb = new StringBuilder();
        Campo[] campos = proveedores ? CampoProveedor.values() : CampoProducto.values();
        for (Campo c : campos) {
            sb.append("• ").append(c.etiqueta()).append(c.obligatorio() ? " (obligatorio)" : "").append(": ").append(c.ayuda()).append('\n');
        }
        return sb.toString().trim();
    }
}
