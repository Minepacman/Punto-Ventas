package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * Logotipo del negocio: un PNG o JPG de hasta 1 MB que se copia a la carpeta
 * "datos" junto a datos.db (datos/logo.png o datos/logo.jpg). El archivo es el
 * único registro: si existe, hay logotipo. Se muestra en el login y en la
 * vista previa del ticket. Solo ADMIN lo cambia.
 */
@Service
public class LogotipoService {

    public static final long TAMANO_MAXIMO_BYTES = 1024 * 1024;
    /** Lado máximo en píxeles: una imagen de 1 MB puede declarar decenas de miles de píxeles y agotar la memoria al decodificarla */
    public static final int LADO_MAXIMO_PX = 2000;

    @Autowired private RutasApp         rutas;
    @Autowired private SesionService    sesionService;
    @Autowired private AuditoriaService auditoriaService;

    /** Logotipo guardado, si hay (lectura libre: lo usa la pantalla de login) */
    public Optional<Path> logoActual() {
        Path dir = rutas.carpetaDatos();
        for (String nombre : new String[] {"logo.png", "logo.jpg"}) {
            Path p = dir.resolve(nombre);
            if (Files.isRegularFile(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Valida y copia la imagen como logotipo; reemplaza el anterior */
    public Path establecer(Path origen) {
        sesionService.verificarAdmin();
        String extension = validar(origen);
        Path dir = rutas.carpetaDatos();
        Path destino = dir.resolve("logo." + extension);
        try {
            Files.createDirectories(dir);
            Path temporal = dir.resolve("logo.tmp");
            Files.copy(origen, temporal, StandardCopyOption.REPLACE_EXISTING);
            quitarArchivos();
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new NegocioException("No se pudo guardar el logotipo: " + e.getMessage());
        }
        auditoriaService.registrar(sesionService.getCajeroActual(), "LOGOTIPO_CAMBIADO",
                origen.getFileName() + " → " + destino.getFileName(), (Cajero) null);
        return destino;
    }

    public void quitar() {
        sesionService.verificarAdmin();
        if (logoActual().isEmpty()) throw new NegocioException("No hay logotipo para quitar.");
        try {
            quitarArchivos();
        } catch (IOException e) {
            throw new NegocioException("No se pudo quitar el logotipo: " + e.getMessage());
        }
        auditoriaService.registrar(sesionService.getCajeroActual(), "LOGOTIPO_QUITADO", "logotipo eliminado", (Cajero) null);
    }

    /**
     * PNG o JPG, hasta 1 MB y con el contenido que dice su extensión.
     * @return "png" o "jpg"
     */
    static String validar(Path origen) {
        if (origen == null || !Files.isRegularFile(origen)) {
            throw new NegocioException("Elige un archivo de imagen.");
        }
        String nombre = origen.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = nombre.endsWith(".png") ? "png"
                : (nombre.endsWith(".jpg") || nombre.endsWith(".jpeg")) ? "jpg" : null;
        if (extension == null) {
            throw new NegocioException("El logotipo debe ser una imagen PNG o JPG.");
        }
        try {
            if (Files.size(origen) > TAMANO_MAXIMO_BYTES) {
                throw new NegocioException("El logotipo no puede pesar más de 1 MB.");
            }
            int[] dim;
            try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(origen))) {
                dim = extension.equals("png") ? dimensionesPng(in) : dimensionesJpg(in);
            }
            if (dim == null) {
                throw new NegocioException("El archivo no es una imagen " + extension.toUpperCase() + " válida.");
            }
            if (dim[0] <= 0 || dim[1] <= 0 || dim[0] > LADO_MAXIMO_PX || dim[1] > LADO_MAXIMO_PX) {
                throw new NegocioException("El logotipo no puede medir más de " + LADO_MAXIMO_PX + " × "
                        + LADO_MAXIMO_PX + " píxeles (la imagen mide " + dim[0] + " × " + dim[1] + ").");
            }
        } catch (IOException e) {
            throw new NegocioException("No se pudo leer la imagen: " + e.getMessage());
        }
        return extension;
    }

    /** [ancho, alto] leído del bloque IHDR, o null si no es un PNG */
    static int[] dimensionesPng(InputStream in) throws IOException {
        byte[] b = in.readNBytes(24);
        if (b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
        if (b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;
        return new int[] {entero32(b, 16), entero32(b, 20)};
    }

    /** [ancho, alto] leído del primer bloque SOFn, o null si no es un JPEG */
    static int[] dimensionesJpg(InputStream in) throws IOException {
        if (in.read() != 0xFF || in.read() != 0xD8) return null;
        for (int i = 0; i < 200; i++) {                       // recorre los bloques hasta el SOF
            int m = in.read();
            while (m == 0xFF) m = in.read();                  // relleno
            if (m < 0) return null;
            if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) continue;   // sin longitud
            byte[] len = in.readNBytes(2);
            if (len.length < 2) return null;
            int largo = ((len[0] & 0xFF) << 8) | (len[1] & 0xFF);
            boolean sof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (sof) {
                byte[] d = in.readNBytes(5);
                if (d.length < 5) return null;
                return new int[] {((d[3] & 0xFF) << 8) | (d[4] & 0xFF), ((d[1] & 0xFF) << 8) | (d[2] & 0xFF)};
            }
            if (largo < 2) return null;
            long saltar = largo - 2;
            while (saltar > 0) {
                long n = in.skip(saltar);
                if (n <= 0) { if (in.read() < 0) return null; n = 1; }
                saltar -= n;
            }
        }
        return null;
    }

    private static int entero32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private void quitarArchivos() throws IOException {
        Path dir = rutas.carpetaDatos();
        Files.deleteIfExists(dir.resolve("logo.png"));
        Files.deleteIfExists(dir.resolve("logo.jpg"));
    }
}
