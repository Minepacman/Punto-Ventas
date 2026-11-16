package me.eliassanchezfernandez.puntodeventa.ocr;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Preproceso con una "foto" sintética: ticket blanco con texto monoespaciado sobre un fondo morado
 * oscuro, con sombra, girado 90° y torcido unos grados (como las fotos reales de WhatsApp).
 */
class PreprocesadorTicketTest {

    static final String[] RENGLONES = {
            "DISTRIBUIDORA DE DULCES",
            "1   ORBIT SURTID   89.00    89.00",
            "1   MAZAPAN GIGA  109.00   109.00",
            "1   MZ CHOCO GT   126.00   126.00",
            "2   TUTSI 40PZS   138.00   276.00",
            "3   PALETA PAYASO  12.50    37.50",
            "SUBTOTAL               637.50",
            "IVA                      0.00",
            "TOTAL                  637.50",
            "GRACIAS POR SU COMPRA"};

    /** Ticket recto: papel blanco con el texto, sobre fondo morado; con una sombra en la parte baja */
    static BufferedImage ticket(int tamanoLetra) {
        int ancho = 900, alto = 1300;
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(70, 30, 110));
        g.fillRect(0, 0, ancho, alto);
        g.setColor(new Color(235, 225, 245));           // papel con tinte lila
        g.fillRect(150, 100, 600, 1100);
        g.setColor(new Color(150, 140, 160));           // sombra de la mano
        g.fillRect(150, 900, 600, 300);
        g.setColor(new Color(25, 20, 30));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, tamanoLetra));
        int y = 160;
        for (String r : RENGLONES) {
            g.drawString(r, 180, y);
            y += (int) (tamanoLetra * 1.9);
        }
        g.dispose();
        return img;
    }

    static BufferedImage girar(BufferedImage src, double grados) {
        double r = Math.toRadians(grados);
        int w = src.getWidth(), h = src.getHeight();
        int nw = (int) Math.ceil(Math.abs(w * Math.cos(r)) + Math.abs(h * Math.sin(r)));
        int nh = (int) Math.ceil(Math.abs(h * Math.cos(r)) + Math.abs(w * Math.sin(r)));
        BufferedImage d = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = d.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(70, 30, 110));
        g.fillRect(0, 0, nw, nh);
        g.translate(nw / 2.0, nh / 2.0);
        g.rotate(r);
        g.drawImage(src, -w / 2, -h / 2, null);
        g.dispose();
        return d;
    }

    static byte[] jpeg(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void recortaAlPapelYEnderezaUnaFotoRecta() throws IOException {
        PreprocesadorTicket.Preparada p = PreprocesadorTicket.preparar(jpeg(girar(ticket(22), 3)), PreprocesadorTicket.PREDETERMINADAS);
        assertThat(p.giro90()).isZero();
        assertThat(p.inclinacion()).isCloseTo(-3, within(0.8));
        // recorte: el papel (girado) mide ~660×1130 de una foto de ~970×1350
        assertThat(p.recorte()[2]).isLessThan(800);
        assertThat(p.alternativas()).containsExactly(180);
    }

    @Test
    void detectaTextoDeLadoAunqueLaFuenteSeaMonoespaciada() throws IOException {
        PreprocesadorTicket.Preparada p = PreprocesadorTicket.preparar(jpeg(girar(ticket(22), 90 + 2)), PreprocesadorTicket.PREDETERMINADAS);
        assertThat(p.giro90()).isIn(90, 270);
        assertThat(Math.abs(p.inclinacion())).isLessThan(3.5);
    }

    @Test
    void ampliaLaLetraChica() throws IOException {
        PreprocesadorTicket.Preparada p = PreprocesadorTicket.preparar(jpeg(ticket(14)), PreprocesadorTicket.PREDETERMINADAS);
        assertThat(p.alturaLetra()).isBetween(6.0, 20.0);
        assertThat(p.escala()).isGreaterThan(1.5);
        assertThat((long) p.imagen().ancho() * p.imagen().alto()).isLessThanOrEqualTo(PreprocesadorTicket.PIXELES_MAXIMOS_SALIDA);
    }

    @Test
    void elAplanadoBorraLaSombraPeroNoElTexto() throws IOException {
        PreprocesadorTicket.Rgb rgb = PreprocesadorTicket.decodificar(jpeg(ticket(22)));
        ImagenGris gris = PreprocesadorTicket.aGris(rgb, true);
        ImagenGris plano = PreprocesadorTicket.aplanarFondo(gris);
        // papel con sombra (x=700,y=1100) y sin sombra (x=700,y=500): antes muy distintos, después casi iguales
        assertThat(Math.abs(gris.get(700, 1100) - gris.get(700, 500))).isGreaterThan(50);
        assertThat(Math.abs(plano.get(700, 1100) - plano.get(700, 500))).isLessThan(25);
        // sigue habiendo tinta oscura
        int[] h = plano.histograma();
        int oscuros = 0;
        for (int i = 0; i < 80; i++) oscuros += h[i];
        assertThat(oscuros).isGreaterThan(1000);
    }

    @Test
    void sauvolaDejaSoloBlancoYNegro() throws IOException {
        PreprocesadorTicket.Opciones op = new PreprocesadorTicket.Opciones(true, true, true, true, 0, true);
        int[] h = PreprocesadorTicket.preparar(jpeg(ticket(22)), op).imagen().histograma();
        int otros = 0;
        for (int i = 1; i < 255; i++) otros += h[i];
        assertThat(otros).isZero();
        assertThat(h[0]).isPositive();
    }

    @Test
    void orientacionExifYGirosDeLaImagen() {
        // 2×1: [A B] con orientación 6 (girar 90° horario) → 1×2: [A; B]
        PreprocesadorTicket.Rgb r = PreprocesadorTicket.orientar(new PreprocesadorTicket.Rgb(new int[] {1, 2}, 2, 1), 6);
        assertThat(r.ancho()).isEqualTo(1);
        assertThat(r.px()).containsExactly(1, 2);
        ImagenGris g = new ImagenGris(new byte[] {10, 20, 30, 40, 50, 60}, 3, 2);   // 10 20 30 / 40 50 60
        assertThat(g.girar90(90).px()).containsExactly(40, 10, 50, 20, 60, 30);
        assertThat(g.girar90(180).px()).containsExactly(60, 50, 40, 30, 20, 10);
        assertThat(g.girar90(270).girar90(90).px()).containsExactly(g.px());
        assertThat(g.recortar(1, 0, 2, 2).px()).containsExactly(20, 30, 50, 60);
        assertThat(new String(g.aPgm(), 0, 11)).isEqualTo("P5\n3 2\n255\n");
    }

    @Test
    void bytesQueNoSonImagenSeRechazanConMensaje() {
        assertThatThrownBy(() -> PreprocesadorTicket.decodificar(new byte[] {1, 2, 3})).isInstanceOf(IOException.class)
                .hasMessageContaining("imagen");
        assertThatThrownBy(() -> PreprocesadorTicket.decodificar(new byte[0])).isInstanceOf(IOException.class);
    }
}
