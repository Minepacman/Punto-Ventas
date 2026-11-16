package me.eliassanchezfernandez.puntodeventa.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Preferencia del lector de tickets y medida de PaddleOCR de ESTA computadora (archivo
 * {@code ocr.properties} en la carpeta de la aplicación, como {@code PreferenciasActualizacion}).
 * Es local y no de la BD: con varias cajas cada PC tiene su propio hardware y el OCR corre en la
 * que atiende al teléfono. Tolerante a fallos: si no se puede leer, vale "Automático" sin medida.
 */
public final class PreferenciasOcr {

    public static final String ARCHIVO = "ocr.properties";
    static final String PREFERENCIA = "preferencia";
    static final String PP_SEGUNDOS = "ppocr.segundos";
    static final String PP_HUELLA = "ppocr.huella";
    static final String PP_ONNX = "ppocr.onnx";

    /** Lo guardado (todo opcional) */
    public record Datos(SeleccionMotorOcr.Preferencia preferencia, Double segundosPpOcr, String huellaPpOcr, Boolean cargaOnnx) {}

    private PreferenciasOcr() { }

    public static Datos leer(Path carpeta) {
        Properties p = new Properties();
        Path archivo = carpeta.resolve(ARCHIVO);
        if (Files.isRegularFile(archivo)) {
            try (InputStream in = Files.newInputStream(archivo)) {
                p.load(in);
            } catch (IOException | RuntimeException e) {
                p.clear();
            }
        }
        Double segundos = null;
        try {
            String s = p.getProperty(PP_SEGUNDOS);
            if (s != null) segundos = Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            segundos = null;
        }
        String onnx = p.getProperty(PP_ONNX);
        return new Datos(SeleccionMotorOcr.Preferencia.de(p.getProperty(PREFERENCIA)), segundos,
                p.getProperty(PP_HUELLA), onnx == null ? null : Boolean.valueOf(onnx.trim()));
    }

    /** Guarda todo (escritura a .tmp + move). Nunca lanza: devuelve false si no se pudo. */
    public static boolean guardar(Path carpeta, Datos d) {
        try {
            Files.createDirectories(carpeta);
            Properties p = new Properties();
            p.setProperty(PREFERENCIA, d.preferencia().name());
            if (d.segundosPpOcr() != null) p.setProperty(PP_SEGUNDOS, String.valueOf(d.segundosPpOcr()));
            if (d.huellaPpOcr() != null) p.setProperty(PP_HUELLA, d.huellaPpOcr());
            if (d.cargaOnnx() != null) p.setProperty(PP_ONNX, String.valueOf(d.cargaOnnx()));
            Path temporal = carpeta.resolve(ARCHIVO + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporal)) {
                p.store(out, "Lector de tickets de proveedor de esta computadora");
            }
            Files.move(temporal, carpeta.resolve(ARCHIVO), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("No se pudo guardar la preferencia del lector de tickets: " + e.getMessage());
            return false;
        }
    }
}
