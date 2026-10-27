package me.eliassanchezfernandez.puntodeventa.facturacion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Archivos XML y PDF de las facturas en la carpeta de datos del usuario,
 * junto a la base de datos: datos/facturas/AAAA/SERIE-FOLIO-UUID.xml|pdf.
 * Se escriben a un temporal y se renombran: nunca queda un archivo a medias.
 */
public class AlmacenFacturas {

    public static final String CARPETA = "facturas";

    private final Path raiz;

    public AlmacenFacturas(Path carpetaDatos) {
        this.raiz = carpetaDatos.resolve(CARPETA);
    }

    public Path raiz() {
        return raiz;
    }

    /** Guarda el archivo y devuelve la ruta RELATIVA a la carpeta de facturas (para la BD) */
    public String guardar(int anio, String serie, String folio, String uuid, ProveedorCfdi.Formato formato, byte[] contenido) throws IOException {
        String ext = formato == ProveedorCfdi.Formato.PDF ? "pdf" : "xml";
        String nombre = nombreArchivo(serie, folio, uuid) + "." + ext;
        Path carpeta = raiz.resolve(String.valueOf(anio));
        Files.createDirectories(carpeta);
        Path destino = carpeta.resolve(nombre);
        Path temporal = carpeta.resolve(nombre + ".tmp");
        Files.write(temporal, contenido);
        Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return anio + "/" + nombre;
    }

    /** Ruta absoluta de un archivo guardado (puede no existir si se borró a mano) */
    public Path resolver(String rutaRelativa) {
        return raiz.resolve(rutaRelativa);
    }

    public boolean existe(String rutaRelativa) {
        return rutaRelativa != null && Files.isRegularFile(resolver(rutaRelativa));
    }

    public byte[] leer(String rutaRelativa) throws IOException {
        return Files.readAllBytes(resolver(rutaRelativa));
    }

    /** "A-12-UUID" con solo caracteres seguros para nombre de archivo */
    static String nombreArchivo(String serie, String folio, String uuid) {
        String base = (serie == null || serie.isBlank() ? "F" : serie) + "-" + (folio == null ? "0" : folio) + "-" + (uuid == null ? "sin-uuid" : uuid);
        return base.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "_");
    }
}
