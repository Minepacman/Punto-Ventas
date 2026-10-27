package me.eliassanchezfernandez.puntodeventa.util;

import io.nayuki.qrcodegen.QrCode;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Códigos QR para el acceso móvil, con la librería Nayuki (MIT, ~50 KB) y
 * corrección de errores nivel M. Toda la generación trabaja con matrices y
 * arreglos de píxeles (no necesita el toolkit de JavaFX): solo
 * {@link #imagen} crea un WritableImage y eso sí requiere JavaFX iniciado.
 */
public final class CodigoQr {

    /** Zona de silencio mínima exigida por el estándar: 4 módulos por lado */
    public static final int BORDE = 4;

    private static final int NEGRO = 0xFF000000;
    private static final int BLANCO = 0xFFFFFFFF;

    private CodigoQr() { }

    /**
     * Matriz de módulos (true = oscuro) CON la zona de silencio incluida, de
     * tamaño (lado del QR + 2 * borde) en ambas dimensiones.
     */
    public static boolean[][] matriz(String texto, int borde) {
        if (texto == null || texto.isEmpty()) throw new IllegalArgumentException("Falta el texto del código QR.");
        if (borde < 0) throw new IllegalArgumentException("El borde no puede ser negativo.");
        QrCode qr = QrCode.encodeText(texto, QrCode.Ecc.MEDIUM);
        int n = qr.size + 2 * borde;
        boolean[][] m = new boolean[n][n];
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) m[y + borde][x + borde] = qr.getModule(x, y);
        }
        return m;
    }

    public static boolean[][] matriz(String texto) {
        return matriz(texto, BORDE);
    }

    /** Píxeles ARGB (fila a fila) de la matriz ampliada {@code escala} veces: lado = matriz.length * escala */
    public static int[] pixeles(boolean[][] matriz, int escala) {
        if (escala < 1) throw new IllegalArgumentException("La escala debe ser al menos 1.");
        int lado = matriz.length * escala;
        int[] px = new int[lado * lado];
        for (int y = 0; y < lado; y++) {
            boolean[] fila = matriz[y / escala];
            for (int x = 0; x < lado; x++) px[y * lado + x] = fila[x / escala] ? NEGRO : BLANCO;
        }
        return px;
    }

    /** Lado en píxeles de la imagen para ese texto y escala (con zona de silencio) */
    public static int lado(String texto, int escala) {
        return matriz(texto).length * escala;
    }

    /** Escala entera para que el QR (con borde) mida lo más cerca posible de {@code ladoDeseado} px sin pasarse */
    public static int escalaPara(String texto, int ladoDeseado) {
        return Math.max(1, ladoDeseado / matriz(texto).length);
    }

    /** Imagen de JavaFX (requiere el toolkit iniciado). Nunca devuelve null. */
    public static WritableImage imagen(String texto, int escala) {
        boolean[][] m = matriz(texto);
        int lado = m.length * escala;
        WritableImage img = new WritableImage(lado, lado);
        img.getPixelWriter().setPixels(0, 0, lado, lado, PixelFormat.getIntArgbInstance(), pixeles(m, escala), 0, lado);
        return img;
    }

    /** Imagen AWT (sin JavaFX): sirve para guardar el PNG y para pruebas sin pantalla */
    public static BufferedImage imagenAwt(String texto, int escala) {
        boolean[][] m = matriz(texto);
        int lado = m.length * escala;
        BufferedImage img = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, lado, lado, pixeles(m, escala), 0, lado);
        return img;
    }

    /** Guarda el QR como PNG */
    public static void guardarPng(String texto, int escala, File destino) throws IOException {
        if (!javax.imageio.ImageIO.write(imagenAwt(texto, escala), "png", destino)) {
            throw new IOException("No se pudo escribir el PNG.");
        }
    }
}
