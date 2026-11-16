package me.eliassanchezfernandez.puntodeventa.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Archivos de PaddleOCR que se bajan bajo demanda (nunca en el repositorio ni en el instalador): ONNX
 * Runtime para Java y los modelos PP-OCRv6 small. Cada uno con tamaño y SHA-256 fijos: si no coincide
 * se borra y no se usa. Se guardan en {@code <carpeta de la aplicación>/ocr-ppocr/}.
 * <p>
 * Cada archivo tiene dos orígenes, en orden: el espejo en una release de GitHub de este proyecto
 * ({@link #ESPEJO}, etiqueta {@code ocr-modelos-1}, la arma {@code .github/workflows/ocr-modelos.yml} desde las
 * fuentes originales verificando el mismo SHA-256) y, si el espejo falla o aún no existe, la fuente original
 * (Maven Central y modelscope.cn). La lista de fuentes y sumas vive también en
 * {@code packaging/ocr-modelos/recursos.txt} (la usa ese flujo; {@code EspejoOcrModelosTest} revisa que coincidan).
 */
public final class RecursosPpOcr {

    /**
     * @param urls en orden de preferencia (espejo primero, fuente original después)
     */
    public record Archivo(String nombre, List<String> urls, long bytes, String sha256) {
        public Archivo {
            urls = List.copyOf(urls);
            if (urls.isEmpty()) throw new IllegalArgumentException("Sin URL para " + nombre);
        }

        /** El origen preferido (el espejo) */
        public String url() {
            return urls.get(0);
        }
    }

    /** Release de GitHub del proyecto con los tres archivos (no es una versión del programa: va marcada como prelanzamiento) */
    public static final String ETIQUETA_ESPEJO = "ocr-modelos-1";
    public static final String ESPEJO = "https://github.com/Minepacman/Punto-Ventas/releases/download/" + ETIQUETA_ESPEJO + "/";

    public static final Archivo ONNX_RUNTIME = conEspejo("onnxruntime-1.31.1.jar",
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.31.1/onnxruntime-1.31.1.jar",
            50_942_631L, "854b4fae9b5202371eb8cd978d2e872eafb75664ef2db2f3c91f2b095b518b62");
    public static final Archivo DETECCION = conEspejo("PP-OCRv6_det_small.onnx",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv6/det/PP-OCRv6_det_small.onnx",
            9_929_594L, "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f");
    public static final Archivo RECONOCIMIENTO = conEspejo("PP-OCRv6_rec_small.onnx",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv6/rec/PP-OCRv6_rec_small.onnx",
            21_234_383L, "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884");
    public static final List<Archivo> TODOS = List.of(ONNX_RUNTIME, DETECCION, RECONOCIMIENTO);

    private static Archivo conEspejo(String nombre, String original, long bytes, String sha256) {
        return new Archivo(nombre, List.of(ESPEJO + nombre, original), bytes, sha256);
    }

    public static final String CARPETA = "ocr-ppocr";

    private final Path carpeta;
    private final List<Archivo> archivos;

    public RecursosPpOcr(Path carpetaAplicacion) {
        this(carpetaAplicacion, TODOS);
    }

    /** Con otra lista de archivos (pruebas) */
    RecursosPpOcr(Path carpetaAplicacion, List<Archivo> archivos) {
        this.carpeta = carpetaAplicacion.resolve(CARPETA);
        this.archivos = List.copyOf(archivos);
    }

    public Path ruta(Archivo a) {
        return carpeta.resolve(a.nombre());
    }

    /** Están los tres con su tamaño (el SHA-256 se revisó al bajarlos; ver {@link #marca}) */
    public boolean listos() {
        for (Archivo a : archivos) {
            Path p = ruta(a);
            try {
                if (!Files.isRegularFile(p) || Files.size(p) != a.bytes() || !Files.isRegularFile(marca(a))) return false;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /** Archivo que dice "este ya se verificó" (revisar 80 MB de SHA-256 en cada arranque sería lento) */
    Path marca(Archivo a) {
        return carpeta.resolve(a.nombre() + ".sha256");
    }

    /**
     * Baja lo que falte (unos 82 MB la primera vez). Cada archivo se pide primero al espejo y, si falla (sin red
     * hacia GitHub, 404, tamaño o SHA-256 distinto), a la fuente original. Nunca reintenta en bucle: si todos los
     * orígenes fallan, lanza y la próxima vez que toque se intenta de nuevo. {@code progreso} recibe mensajes cortos.
     */
    public void descargar(Consumer<String> progreso) throws IOException {
        Files.createDirectories(carpeta);
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        for (Archivo a : archivos) {
            Path destino = ruta(a);
            if (Files.isRegularFile(destino) && Files.size(destino) == a.bytes() && Files.isRegularFile(marca(a))) continue;
            progreso.accept("Descargando " + a.nombre() + " (" + a.bytes() / 1_048_576 + " MB)…");
            IOException ultimo = null;
            for (String url : a.urls()) {
                try {
                    bajar(cliente, a, url, destino);
                    ultimo = null;
                    break;
                } catch (java.io.InterruptedIOException e) {
                    throw e;                                  // cancelado: no probar el siguiente origen
                } catch (IOException e) {
                    ultimo = e;
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Descarga cancelada");
                }
            }
            if (ultimo != null) throw ultimo;
        }
        progreso.accept("Modelos de PaddleOCR listos.");
    }

    /** Un origen: baja a ".parcial", revisa tamaño y SHA-256 y solo entonces lo pone en su lugar */
    private void bajar(HttpClient cliente, Archivo a, String url, Path destino) throws IOException {
        Path tmp = carpeta.resolve(a.nombre() + ".parcial");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(20))
                    .header("User-Agent", "PuntoDeVenta/" + me.eliassanchezfernandez.puntodeventa.util.VersionApp.version()).build();
            HttpResponse<InputStream> r = cliente.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                r.body().close();
                throw new IOException("HTTP " + r.statusCode() + " al bajar " + a.nombre() + " de " + URI.create(url).getHost());
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > a.bytes()) throw new IOException(a.nombre() + " es más grande de lo esperado");
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Descarga cancelada");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String sha = HexFormat.of().formatHex(md.digest());
            if (total != a.bytes() || !sha.equalsIgnoreCase(a.sha256())) {
                throw new IOException(a.nombre() + " no coincide con el archivo esperado (SHA-256)");
            }
            Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(marca(a), sha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Descarga cancelada");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Verificación completa de un archivo (pruebas y diagnóstico) */
    public static boolean coincide(Path p, Archivo a) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(a.sha256());
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }
}
