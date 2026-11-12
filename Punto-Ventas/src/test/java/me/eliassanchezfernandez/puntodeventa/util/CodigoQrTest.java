package me.eliassanchezfernandez.puntodeventa.util;

import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** El QR del acceso móvil: la matriz y los píxeles se prueban sin pantalla (sin toolkit de JavaFX). */
class CodigoQrTest {

    private static final String URL = "http://192.168.1.20:8085/";

    @Test
    void laMatrizTieneElTamanoEsperadoConZonaDeSilencio() {
        boolean[][] m = CodigoQr.matriz(URL);
        // 25 caracteres con corrección M caben en la versión 2 (25x25 módulos) + 4 de silencio por lado
        assertThat(m).hasDimensions(33, 33);
        assertThat(CodigoQr.matriz(URL, 0)).hasDimensions(25, 25);
        assertThat(CodigoQr.matriz(URL, 2)).hasDimensions(29, 29);
        for (int i = 0; i < m.length; i++) {          // la zona de silencio es blanca
            for (int b = 0; b < CodigoQr.BORDE; b++) {
                assertThat(m[b][i]).isFalse();
                assertThat(m[i][b]).isFalse();
                assertThat(m[m.length - 1 - b][i]).isFalse();
                assertThat(m[i][m.length - 1 - b]).isFalse();
            }
        }
        assertThat(m[CodigoQr.BORDE][CodigoQr.BORDE]).isTrue();   // esquina del patrón de búsqueda
    }

    @Test
    void unaUrlMasLargaDaUnaVersionMayor() {
        String larga = "http://192.168.100.250:8085/" + "x".repeat(60);
        assertThat(CodigoQr.matriz(larga).length).isGreaterThan(CodigoQr.matriz(URL).length);
    }

    @Test
    void losPixelesSeAmplianPorLaEscalaPedida() {
        int[] px = CodigoQr.pixeles(CodigoQr.matriz(URL), 6);
        assertThat(px).hasSize(33 * 6 * 33 * 6);
        assertThat(px[0]).isEqualTo(0xFFFFFFFF);                                       // esquina: blanco
        int centroDelPrimerModulo = (CodigoQr.BORDE * 6 + 3) * (33 * 6) + (CodigoQr.BORDE * 6 + 3);
        assertThat(px[centroDelPrimerModulo]).isEqualTo(0xFF000000);                   // módulo oscuro
        assertThat(CodigoQr.lado(URL, 6)).isEqualTo(33 * 6);
        assertThat(CodigoQr.escalaPara(URL, 330)).isEqualTo(10);
        assertThat(CodigoQr.escalaPara(URL, 5)).isEqualTo(1);
    }

    @Test
    void laImagenAwtYElPngSeGeneranSinPantalla(@TempDir Path dir) throws Exception {
        BufferedImage img = CodigoQr.imagenAwt(URL, 4);
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(33 * 4);
        assertThat(img.getHeight()).isEqualTo(33 * 4);

        File png = dir.resolve("qr.png").toFile();
        CodigoQr.guardarPng(URL, 8, png);
        assertThat(javax.imageio.ImageIO.read(png).getWidth()).isEqualTo(33 * 8);
    }

    @Test
    void laImagenDeJavaFxTieneLaEscalaPedidaSiHayToolkit() {
        WritableImage img = null;
        try {
            img = CodigoQr.imagen(URL, 5);
        } catch (Throwable sinPantalla) {      // sin toolkit de JavaFX (equipo sin pantalla)
            assumeTrue(false, "JavaFX no disponible: " + sinPantalla);
        }
        assertThat(img).isNotNull();
        assertThat((int) img.getWidth()).isEqualTo(33 * 5);
    }

    @Test
    void rechazaTextoVacioYEscalaInvalida() {
        assertThatThrownBy(() -> CodigoQr.matriz("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodigoQr.matriz(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodigoQr.pixeles(CodigoQr.matriz(URL), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
