package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoImagen;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoImagenRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fotos de los productos (tabla producto_imagen, ver {@link ProductoImagen}).
 *
 * Toda imagen que entra (archivo, portapapeles, URL) se NORMALIZA antes de
 * guardarse: se decodifica (así se comprueba que de verdad es una imagen), se
 * corrige la orientación EXIF de las fotos de teléfono, se aplana sobre blanco,
 * se reduce a {@value #LADO_MAXIMO_PX} px por el lado mayor y se recodifica
 * como JPEG; además se genera una miniatura de {@value #LADO_MINIATURA_PX} px.
 * Así una foto de 5 MB queda en unos 40-60 KB y la base no engorda.
 *
 * Permiso: el mismo que editar productos (EDITAR_PRODUCTOS). Cada cambio
 * queda en la bitácora. Las lecturas son libres (Ventas las usa al buscar).
 */
@Service
public class ProductoImagenService {

    /** Peso máximo del archivo original que se acepta */
    public static final long TAMANO_MAXIMO_BYTES = 10L * 1024 * 1024;
    /** Píxeles máximos (ancho × alto) que se decodifican: protege la memoria de PCs con poca RAM */
    public static final long PIXELES_MAXIMOS = 40_000_000L;
    public static final int LADO_MAXIMO_PX = 512;
    public static final int LADO_MINIATURA_PX = 96;
    public static final float CALIDAD_JPEG = 0.85f;
    public static final String MIME = "image/jpeg";

    static final Duration TIEMPO_CONEXION = Duration.ofSeconds(8);
    static final Duration TIEMPO_DESCARGA = Duration.ofSeconds(20);

    @Autowired private ProductoImagenRepository imagenRepo;
    @Autowired private ProductoRepository       productoRepo;
    @Autowired private SesionService            sesionService;
    @Autowired private AuditoriaService         auditoriaService;

    /** Imagen ya normalizada, lista para guardar o para mostrar como vista previa */
    public record Normalizada(byte[] imagen, byte[] miniatura, int ancho, int alto, String sha256) { }

    // ── Lecturas (libres) ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<byte[]> imagen(Long productoId) {
        if (productoId == null) return Optional.empty();
        return imagenRepo.imagen(productoId);
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> miniatura(Long productoId) {
        if (productoId == null) return Optional.empty();
        return imagenRepo.miniatura(productoId);
    }

    /** La fila completa (bytes, sha256, fecha); la usa el acceso móvil para el ETag */
    @Transactional(readOnly = true)
    public Optional<ProductoImagen> obtener(Long productoId) {
        if (productoId == null) return Optional.empty();
        return imagenRepo.findById(productoId);
    }

    @Transactional(readOnly = true)
    public boolean tieneImagen(Long productoId) {
        return productoId != null && imagenRepo.existsById(productoId);
    }

    /** De estos productos, los que tienen imagen (una consulta) */
    @Transactional(readOnly = true)
    public Set<Long> idsConImagen(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        return new TreeSet<>(imagenRepo.idsConImagen(ids));
    }

    // ── Cambios (EDITAR_PRODUCTOS) ────────────────────────────────────────

    /** Normaliza y guarda (reemplaza) la imagen del producto. {@code origen} es solo para la bitácora. */
    @Transactional
    public ProductoImagen guardar(Long productoId, byte[] original, String origen) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        return guardar(productoId, normalizar(original), origen);
    }

    /** Guarda una imagen ya normalizada (p. ej. la vista previa que el formulario ya procesó) */
    @Transactional
    public ProductoImagen guardar(Long productoId, Normalizada n, String origen) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        if (n == null) throw new NegocioException("No hay imagen que guardar.");
        Producto p = productoRepo.findById(productoId == null ? -1L : productoId)
                .orElseThrow(() -> new NegocioException("El producto ya no existe."));
        ProductoImagen fila = imagenRepo.findById(p.getId()).orElseGet(ProductoImagen::new);
        boolean nueva = fila.getProductoId() == null;
        fila.setProductoId(p.getId());
        fila.setImagen(n.imagen());
        fila.setMiniatura(n.miniatura());
        fila.setMime(MIME);
        fila.setSha256(n.sha256());
        fila.setAncho(n.ancho());
        fila.setAlto(n.alto());
        fila.setActualizado(LocalDateTime.now());
        ProductoImagen guardada = imagenRepo.save(fila);
        auditoriaService.registrar(sesionService.getCajeroActual(),
                nueva ? "PRODUCTO_IMAGEN_AGREGADA" : "PRODUCTO_IMAGEN_CAMBIADA",
                p.getCodigoBarras() + " " + p.getDescripcion() + " · " + n.ancho() + "×" + n.alto() + " px, "
                        + (n.imagen().length / 1024) + " KB" + (origen == null || origen.isBlank() ? "" : " · " + origen),
                (Cajero) null);
        return guardada;
    }

    /** Lee el archivo (comprobando antes su peso) y lo guarda como imagen del producto */
    @Transactional
    public ProductoImagen guardarDesdeArchivo(Long productoId, Path archivo) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        byte[] bytes = leerArchivo(archivo);
        return guardar(productoId, normalizar(bytes), "archivo " + archivo.getFileName());
    }

    /** Descarga la imagen de la URL y la guarda como imagen del producto */
    @Transactional
    public ProductoImagen guardarDesdeUrl(Long productoId, String url) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        byte[] bytes = descargarDesdeUrl(url);
        return guardar(productoId, normalizar(bytes), "URL " + url.trim());
    }

    @Transactional
    public void quitar(Long productoId) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        if (productoId == null || !imagenRepo.existsById(productoId)) {
            throw new NegocioException("El producto no tiene imagen.");
        }
        imagenRepo.deleteById(productoId);
        String detalle = productoRepo.findById(productoId)
                .map(p -> p.getCodigoBarras() + " " + p.getDescripcion()).orElse("producto " + productoId);
        auditoriaService.registrar(sesionService.getCajeroActual(), "PRODUCTO_IMAGEN_QUITADA", detalle, (Cajero) null);
    }

    /** Borra la imagen sin bitácora propia: lo llama ProductoService al eliminar el producto */
    @Transactional
    public void eliminarConProducto(Long productoId) {
        if (productoId != null && imagenRepo.existsById(productoId)) imagenRepo.deleteById(productoId);
    }

    // ── Archivo y descarga ────────────────────────────────────────────────

    /** Lee un archivo de imagen de hasta {@value #TAMANO_MAXIMO_BYTES} bytes */
    public static byte[] leerArchivo(Path archivo) {
        if (archivo == null || !Files.isRegularFile(archivo)) {
            throw new NegocioException("Elige un archivo de imagen.");
        }
        try {
            validarTamano(Files.size(archivo));
            return Files.readAllBytes(archivo);
        } catch (IOException e) {
            throw new NegocioException("No se pudo leer la imagen: " + e.getMessage());
        }
    }

    static void validarTamano(long bytes) {
        if (bytes > TAMANO_MAXIMO_BYTES) {
            throw new NegocioException("La imagen no puede pesar más de " + (TAMANO_MAXIMO_BYTES / (1024 * 1024))
                    + " MB (esta pesa " + String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024)) + " MB).");
        }
    }

    /** Saltos (redirecciones) que se siguen como máximo; cada destino se revisa igual que la URL inicial */
    static final int MAX_REDIRECCIONES = 5;

    /**
     * Solo para pruebas: permite http:// y direcciones locales (el servidor de prueba vive en 127.0.0.1).
     * En producción siempre es false.
     */
    static volatile boolean permitirRedLocalParaPruebas = false;

    /**
     * Descarga una imagen por https con tiempos de espera y tope de tamaño; devuelve
     * los bytes tal cual (sin normalizar). Pensado también para el autollenado por
     * código de barras (Open Food Facts), que da URLs https a sus imágenes.
     *
     * Protección contra SSRF: solo https, y el host NO puede resolver a una dirección
     * de loopback, privada, de enlace local o multicast (127.0.0.1, 10.x, 192.168.x,
     * 169.254.x —metadatos de nube—, ::1...): la URL la escribe un usuario y la descarga
     * la hace la caja desde la red de la tienda (donde está el servidor PostgreSQL, el
     * router, etc.). Las redirecciones se siguen a mano para revisar cada destino igual.
     */
    public byte[] descargarDesdeUrl(String url) {
        URI uri = validarUrl(url);
        HttpClient cliente = HttpClient.newBuilder()
                .connectTimeout(TIEMPO_CONEXION)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            for (int salto = 0; ; salto++) {
                HttpRequest peticion = HttpRequest.newBuilder(uri)
                        .timeout(TIEMPO_DESCARGA)
                        .header("User-Agent", VersionApp.agenteUsuario())
                        .header("Accept", "image/*")
                        .GET().build();
                HttpResponse<InputStream> r = cliente.send(peticion, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = r.body()) {
                    int estado = r.statusCode();
                    if (estado == 301 || estado == 302 || estado == 303 || estado == 307 || estado == 308) {
                        if (salto >= MAX_REDIRECCIONES) {
                            throw new NegocioException("La dirección redirige demasiadas veces.");
                        }
                        String destino = r.headers().firstValue("Location")
                                .orElseThrow(() -> new NegocioException("La dirección redirige sin decir a dónde."));
                        URI siguiente;
                        try {
                            siguiente = uri.resolve(destino.trim());
                        } catch (IllegalArgumentException e) {
                            throw new NegocioException("La dirección redirige a un destino no válido.");
                        }
                        uri = validarUrl(siguiente.toString());   // el destino se revisa como la URL original
                        continue;
                    }
                    if (estado != 200) {
                        throw new NegocioException("No se pudo descargar la imagen (respuesta " + estado + ").");
                    }
                    r.headers().firstValueAsLong("Content-Length").ifPresent(ProductoImagenService::validarTamano);
                    return leerAcotado(in);
                }
            }
        } catch (java.net.http.HttpTimeoutException e) {
            throw new NegocioException("La descarga tardó demasiado. Revisa la conexión a internet.");
        } catch (IOException e) {
            throw new NegocioException("No se pudo descargar la imagen: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NegocioException("Descarga interrumpida.");
        }
    }

    /**
     * Sintaxis, esquema (https) y host seguro. Resuelve el nombre y rechaza las direcciones
     * que no son de internet público. Lanza NegocioException con un mensaje para el usuario.
     */
    static URI validarUrl(String url) {
        if (url == null || url.isBlank()) throw new NegocioException("Escribe la dirección (URL) de la imagen.");
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new NegocioException("La dirección no es válida.");
        }
        String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean esquemaOk = esquema.equals("https") || (permitirRedLocalParaPruebas && esquema.equals("http"));
        if (!esquemaOk) {
            throw new NegocioException("La dirección debe empezar con https:// (solo se descargan imágenes por conexión segura).");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new NegocioException("La dirección no es válida.");
        if (uri.getUserInfo() != null) throw new NegocioException("La dirección no puede llevar usuario ni contraseña.");
        if (!permitirRedLocalParaPruebas) validarHostPublico(uri.getHost());
        return uri;
    }

    /** El host debe resolver SOLO a direcciones públicas (ninguna local, privada ni de enlace local) */
    static void validarHostPublico(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            throw new NegocioException("La dirección apunta a esta misma red; escribe una dirección de internet.");
        }
        java.net.InetAddress[] direcciones;
        try {
            direcciones = java.net.InetAddress.getAllByName(h);
        } catch (java.net.UnknownHostException e) {
            throw new NegocioException("No se encontró el sitio \"" + host + "\". Revisa la dirección y la conexión a internet.");
        }
        for (java.net.InetAddress d : direcciones) {
            if (!esDireccionPublica(d)) {
                throw new NegocioException("La dirección apunta a esta misma red; escribe una dirección de internet.");
            }
        }
    }

    /** false para loopback, privadas (10/8, 172.16/12, 192.168/16, fc00::/7), enlace local, multicast, no especificadas y 100.64/10 */
    static boolean esDireccionPublica(java.net.InetAddress d) {
        if (d.isAnyLocalAddress() || d.isLoopbackAddress() || d.isLinkLocalAddress() || d.isSiteLocalAddress()
                || d.isMulticastAddress()) {
            return false;
        }
        byte[] b = d.getAddress();
        if (b.length == 4) {
            int a = b[0] & 0xFF, c = b[1] & 0xFF;
            if (a == 100 && c >= 64 && c <= 127) return false;   // 100.64.0.0/10 (CGNAT)
            if (a == 0 || a == 127 || a >= 224) return false;      // "esta red", loopback, multicast/reservado
            if (a == 192 && c == 0 && (b[2] & 0xFF) == 0) return false;   // 192.0.0.0/24
            return true;
        }
        if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) return false;   // fc00::/7 (únicas locales)
            // IPv4 mapeada (::ffff:a.b.c.d): se juzga la IPv4
            boolean mapeada = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapeada = false; break; }
            if (mapeada && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return esDireccionPublica(java.net.InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] leerAcotado(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        byte[] tmp = new byte[16 * 1024];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
            validarTamano(buf.size());
        }
        return buf.toByteArray();
    }

    // ── Normalización (pura; también la usa el formulario para la vista previa) ──

    /**
     * Decodifica, orienta, aplana sobre blanco, reduce a {@value #LADO_MAXIMO_PX} px y recodifica
     * como JPEG; genera además la miniatura. Lanza NegocioException con un mensaje claro si
     * los bytes no son una imagen que Java sepa leer (PNG, JPG, GIF, BMP; WEBP no).
     */
    public static Normalizada normalizar(byte[] original) {
        if (original == null || original.length == 0) throw new NegocioException("La imagen está vacía.");
        validarTamano(original.length);
        if (esWebp(original)) {
            throw new NegocioException("Las imágenes WEBP no son compatibles. Conviértela a JPG o PNG e inténtalo de nuevo.");
        }
        BufferedImage decodificada = decodificar(original);
        // Todo lo demás son operaciones sobre arreglos de píxeles: sin Graphics2D ni el entorno
        // gráfico de AWT (que en macOS, dentro de una app JavaFX, es fuente de bloqueos).
        Pixeles orientada = aplicarOrientacion(sobreBlanco(decodificada), orientacionExif(original));
        Pixeles grande = reducir(orientada, LADO_MAXIMO_PX);
        Pixeles mini = reducir(grande, LADO_MINIATURA_PX);
        byte[] jpeg = aJpeg(grande);
        byte[] jpegMini = aJpeg(mini);
        return new Normalizada(jpeg, jpegMini, grande.ancho(), grande.alto(), sha256Hex(jpeg));
    }

    /** Imagen RGB (sin alfa) como arreglo de píxeles 0xRRGGBB, fila por fila */
    record Pixeles(int[] rgb, int ancho, int alto) {
        BufferedImage aImagen() {
            BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, ancho, alto, rgb, 0, ancho);
            return img;
        }
    }

    static boolean esWebp(byte[] b) {
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /**
     * Decodifica con ImageIO. Si la imagen es mucho mayor que el tamaño final se pide al
     * lector que la submuestree al leer: una foto de 4000×3000 se decodifica a 2000×1500 en
     * vez de ocupar 48 MB de memoria.
     */
    private static BufferedImage decodificar(byte[] bytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) throw new NegocioException(MENSAJE_NO_IMAGEN);
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(iis);
            if (!lectores.hasNext()) throw new NegocioException(MENSAJE_NO_IMAGEN);
            ImageReader lector = lectores.next();
            try {
                lector.setInput(iis, true, true);
                int ancho = lector.getWidth(0);
                int alto = lector.getHeight(0);
                if (ancho <= 0 || alto <= 0) throw new NegocioException(MENSAJE_NO_IMAGEN);
                if ((long) ancho * alto > PIXELES_MAXIMOS) {
                    throw new NegocioException("La imagen es demasiado grande (" + ancho + " × " + alto
                            + " píxeles). Redúcela antes de agregarla.");
                }
                ImageReadParam param = lector.getDefaultReadParam();
                // Submuestreo solo para fotos de más de 3× el tamaño final (una de 12 MP queda en 2000×1500):
                // ahorra memoria sin que el salto sea tan grande como para notar aliasing tras el promediado
                int factor = Math.max(1, Math.max(ancho, alto) / (3 * LADO_MAXIMO_PX));
                if (factor > 1) param.setSourceSubsampling(factor, factor, 0, 0);
                BufferedImage img = lector.read(0, param);
                if (img == null) throw new NegocioException(MENSAJE_NO_IMAGEN);
                return img;
            } finally {
                lector.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof NegocioException ne) throw ne;
            // "Unsupported Image Type" (JPEG CMYK), archivos truncados, etc.
            throw new NegocioException(MENSAJE_NO_IMAGEN);
        }
    }

    static final String MENSAJE_NO_IMAGEN =
            "El archivo no es una imagen válida. Usa una foto JPG o PNG (también GIF o BMP).";

    /** Aplana la transparencia sobre blanco (PNG con fondo transparente, GIF) y deja RGB puro */
    static Pixeles sobreBlanco(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        boolean conAlfa = img.getColorModel().hasAlpha();
        if (conAlfa) {
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                int a = (p >>> 24) & 0xFF;
                if (a == 255) { argb[i] = p & 0xFFFFFF; continue; }
                int r = ((p >> 16) & 0xFF) * a / 255 + (255 - a);
                int g = ((p >> 8) & 0xFF) * a / 255 + (255 - a);
                int b = (p & 0xFF) * a / 255 + (255 - a);
                argb[i] = (r << 16) | (g << 8) | b;
            }
        } else {
            for (int i = 0; i < argb.length; i++) argb[i] &= 0xFFFFFF;
        }
        return new Pixeles(argb, w, h);
    }

    /**
     * Reduce (nunca agranda) para que el lado mayor mida como máximo {@code lado}, promediando
     * el área de origen que cae en cada píxel destino (filtro de caja): sin "pixelado" aunque
     * se baje de 4000 a 512 px.
     */
    static Pixeles reducir(Pixeles origen, int lado) {
        int w = origen.ancho(), h = origen.alto();
        double escala = Math.min(1.0, (double) lado / Math.max(w, h));
        int dw = Math.max(1, (int) Math.round(w * escala));
        int dh = Math.max(1, (int) Math.round(h * escala));
        if (dw == w && dh == h) return origen;
        int[] src = origen.rgb();
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int y0 = y * h / dh, y1 = Math.max(y0 + 1, (y + 1) * h / dh);
            for (int x = 0; x < dw; x++) {
                int x0 = x * w / dw, x1 = Math.max(x0 + 1, (x + 1) * w / dw);
                long r = 0, g = 0, b = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int base = sy * w;
                    for (int sx = x0; sx < x1; sx++) {
                        int p = src[base + sx];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                int n = (y1 - y0) * (x1 - x0);
                dst[y * dw + x] = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
            }
        }
        return new Pixeles(dst, dw, dh);
    }

    static byte[] aJpeg(Pixeles pixeles) {
        return aJpeg(pixeles.aImagen());
    }

    static byte[] aJpeg(BufferedImage img) {
        Iterator<ImageWriter> escritores = ImageIO.getImageWritersByFormatName("jpeg");
        if (!escritores.hasNext()) throw new NegocioException("Este Java no puede escribir JPEG.");
        ImageWriter escritor = escritores.next();
        ByteArrayOutputStream salida = new ByteArrayOutputStream(32 * 1024);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(salida)) {
            escritor.setOutput(ios);
            ImageWriteParam param = escritor.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(CALIDAD_JPEG);
            escritor.write(null, new IIOImage(img, null, null), param);
        } catch (IOException e) {
            throw new NegocioException("No se pudo procesar la imagen: " + e.getMessage());
        } finally {
            escritor.dispose();
        }
        return salida.toByteArray();
    }

    static String sha256Hex(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Orientación EXIF (fotos de teléfono) ──────────────────────────────

    /**
     * Orientación EXIF (1..8) de un JPEG, o 1 si no es JPEG o no la trae. Lee solo el segmento
     * APP1 "Exif": cabecera TIFF (orden de bytes), IFD0 y la etiqueta 0x0112. Cualquier rareza = 1.
     */
    static int orientacionExif(byte[] b) {
        try {
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
            int pos = 2;
            while (pos + 4 <= b.length) {
                if ((b[pos] & 0xFF) != 0xFF) return 1;
                int marcador = b[pos + 1] & 0xFF;
                if (marcador == 0xD8 || (marcador >= 0xD0 && marcador <= 0xD7) || marcador == 0x01) { pos += 2; continue; }
                if (marcador == 0xDA || marcador == 0xD9) return 1;          // empezó la imagen: ya no hay EXIF
                int largo = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
                if (largo < 2) return 1;
                if (marcador == 0xE1 && pos + 10 <= b.length
                        && b[pos + 4] == 'E' && b[pos + 5] == 'x' && b[pos + 6] == 'i' && b[pos + 7] == 'f') {
                    int tiff = pos + 10;
                    if (tiff + 8 > b.length) return 1;
                    boolean little = b[tiff] == 'I' && b[tiff + 1] == 'I';
                    boolean big = b[tiff] == 'M' && b[tiff + 1] == 'M';
                    if (!little && !big) return 1;
                    long ifd = leer32(b, tiff + 4, little);
                    long ini = tiff + ifd;
                    if (ini < 0 || ini + 2 > b.length) return 1;
                    int entradas = leer16(b, (int) ini, little);
                    for (int i = 0; i < entradas; i++) {
                        int e = (int) ini + 2 + i * 12;
                        if (e + 12 > b.length) return 1;
                        if (leer16(b, e, little) == 0x0112) {
                            int valor = leer16(b, e + 8, little);
                            return valor >= 1 && valor <= 8 ? valor : 1;
                        }
                    }
                    return 1;
                }
                pos += 2 + largo;
            }
        } catch (RuntimeException e) {
            // cualquier índice fuera de rango: sin orientación
        }
        return 1;
    }

    private static int leer16(byte[] b, int o, boolean little) {
        int a = b[o] & 0xFF, c = b[o + 1] & 0xFF;
        return little ? (c << 8) | a : (a << 8) | c;
    }

    private static long leer32(byte[] b, int o, boolean little) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            int v = b[o + (little ? 3 - i : i)] & 0xFF;
            r = (r << 8) | v;
        }
        return r;
    }

    /**
     * Gira/voltea según la orientación EXIF para que la foto se vea como en el teléfono.
     * Para cada píxel destino (x, y) se calcula de qué píxel de origen viene.
     */
    static Pixeles aplicarOrientacion(Pixeles img, int orientacion) {
        if (orientacion <= 1 || orientacion > 8) return img;
        int w = img.ancho(), h = img.alto();
        boolean gira = orientacion >= 5;                         // 5..8 intercambian ancho y alto
        int dw = gira ? h : w, dh = gira ? w : h;
        int[] src = img.rgb();
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx, sy;
                switch (orientacion) {
                    case 2 -> { sx = w - 1 - x; sy = y; }                 // espejo horizontal
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }         // 180°
                    case 4 -> { sx = x; sy = h - 1 - y; }                 // espejo vertical
                    case 5 -> { sx = y; sy = x; }                         // transpuesta
                    case 6 -> { sx = y; sy = h - 1 - x; }                 // 90° horario
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }         // transpuesta + 180°
                    default -> { sx = w - 1 - y; sy = x; }                // 8: 90° antihorario
                }
                dst[y * dw + x] = src[sy * w + sx];
            }
        }
        return new Pixeles(dst, dw, dh);
    }

    /** Dimensiones [ancho, alto] de unos bytes ya normalizados (JPEG); para pruebas y vistas previas */
    public static int[] dimensiones(byte[] jpeg) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            return img == null ? null : new int[] {img.getWidth(), img.getHeight()};
        } catch (IOException e) {
            return null;
        }
    }

    /** Lista con los nombres de extensiones aceptadas (para el selector de archivos) */
    public static List<String> extensiones() {
        return List.of("*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp");
    }
}
