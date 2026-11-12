import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Genera el icono de la aplicación (canasta de compras sobre azul marino, los
 * colores de app.css) en todos los formatos que piden jpackage y JavaFX:
 *
 *   java packaging/icono/GeneradorIcono.java
 *
 * Escribe en packaging/icono/: PuntoDeVenta.ico (Windows, varios tamaños),
 * PuntoDeVenta.iconset/ + PuntoDeVenta.icns (macOS, con iconutil si está
 * disponible), icono-512.png; y en src/main/resources/icono/ los PNG que usa
 * la ventana (Ventanas.aplicarIcono). Solo hace falta volver a correrlo si se
 * cambia el dibujo. No forma parte del programa instalado.
 */
public class GeneradorIcono {

    static final Color MARINO_ARRIBA = new Color(0x2A4566);
    static final Color MARINO_ABAJO  = new Color(0x213A58);
    static final Color BLANCO        = new Color(0xFFFFFF);
    static final Color ACENTO        = new Color(0x36895F);   // -pos-success (etiqueta de precio)

    public static void main(String[] args) throws IOException, InterruptedException {
        Path raiz = args.length > 0 ? Path.of(args[0]) : Path.of("");
        Path salida = raiz.resolve("packaging/icono");
        Path recursos = raiz.resolve("src/main/resources/icono");
        Files.createDirectories(salida);
        Files.createDirectories(recursos);

        // Windows .ico: PNG comprimidos dentro del contenedor ICO (Windows Vista en adelante)
        int[] tamanosIco = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int t : tamanosIco) pngs.add(png(dibujar(t)));
        Files.write(salida.resolve("PuntoDeVenta.ico"), ico(tamanosIco, pngs));

        // macOS .icns: iconset con los tamaños que exige iconutil
        Path iconset = salida.resolve("PuntoDeVenta.iconset");
        Files.createDirectories(iconset);
        int[] base = {16, 32, 128, 256, 512};
        for (int t : base) {
            ImageIO.write(dibujar(t), "png", iconset.resolve("icon_" + t + "x" + t + ".png").toFile());
            ImageIO.write(dibujar(t * 2), "png", iconset.resolve("icon_" + t + "x" + t + "@2x.png").toFile());
        }
        ImageIO.write(dibujar(512), "png", salida.resolve("icono-512.png").toFile());

        // PNG para Stage.getIcons() (JavaFX elige el más cercano al tamaño que pide el sistema)
        for (int t : new int[] {16, 32, 64, 128, 256}) {
            ImageIO.write(dibujar(t), "png", recursos.resolve("icono-" + t + ".png").toFile());
        }

        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            Process p = new ProcessBuilder("iconutil", "-c", "icns", iconset.toString(),
                    "-o", salida.resolve("PuntoDeVenta.icns").toString()).inheritIO().start();
            if (p.waitFor() != 0) System.err.println("iconutil falló: el .icns no se generó");
        } else {
            System.out.println("No es macOS: el .icns se genera en un Mac con iconutil (o con png2icns).");
        }
        System.out.println("Iconos escritos en " + salida.toAbsolutePath() + " y " + recursos.toAbsolutePath());
    }

    /** Canasta blanca con asa y una etiqueta verde, sobre un cuadrado redondeado azul marino */
    static BufferedImage dibujar(int t) {
        BufferedImage img = new BufferedImage(t, t, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double s = t / 100.0;   // todo se dibuja en una cuadrícula de 100 × 100

        // Fondo
        g.setPaint(new GradientPaint(0, 0, MARINO_ARRIBA, 0, t, MARINO_ABAJO));
        g.fill(new RoundRectangle2D.Double(0, 0, t, t, 24 * s, 24 * s));

        // Asa (arco) — grosor proporcional, extremos redondeados
        float grueso = (float) Math.max(1.5, 7 * s);
        g.setStroke(new BasicStroke(grueso, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(BLANCO);
        g.draw(new Arc2D.Double(30 * s, 18 * s, 40 * s, 40 * s, 20, 140, Arc2D.OPEN));

        // Cuerpo de la canasta: trapecio con esquinas suaves
        Path2D cuerpo = new Path2D.Double();
        cuerpo.moveTo(16 * s, 44 * s);
        cuerpo.lineTo(84 * s, 44 * s);
        cuerpo.lineTo(74 * s, 82 * s);
        cuerpo.quadTo(73 * s, 86 * s, 69 * s, 86 * s);
        cuerpo.lineTo(31 * s, 86 * s);
        cuerpo.quadTo(27 * s, 86 * s, 26 * s, 82 * s);
        cuerpo.closePath();
        g.fill(cuerpo);

        // Borde superior más grueso (el aro de la canasta)
        g.fill(new RoundRectangle2D.Double(12 * s, 40 * s, 76 * s, 10 * s, 6 * s, 6 * s));

        // Rejilla: tres ranuras verticales en azul marino
        g.setColor(MARINO_ABAJO);
        float ranura = (float) Math.max(1.0, 4.5 * s);
        g.setStroke(new BasicStroke(ranura, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = -1; i <= 1; i++) {
            double x0 = 50 * s + i * 15 * s;
            double x1 = 50 * s + i * 12 * s;
            g.draw(new java.awt.geom.Line2D.Double(x0, 56 * s, x1, 78 * s));
        }

        // Etiqueta verde (precio) colgando del asa, arriba a la derecha
        g.setColor(ACENTO);
        g.fill(new Ellipse2D.Double(64 * s, 14 * s, 22 * s, 22 * s));
        // Signo de pesos centrado en la etiqueta (en 16 px queda solo el punto verde: ya no se distingue)
        if (t >= 24) {
            g.setColor(BLANCO);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, (int) Math.round(17 * s)));
            java.awt.font.GlyphVector gv = g.getFont().createGlyphVector(g.getFontRenderContext(), "$");
            java.awt.geom.Rectangle2D caja = gv.getVisualBounds();
            double cx = 75 * s, cy = 25 * s;
            g.fill(gv.getOutline((float) (cx - caja.getCenterX()), (float) (cy - caja.getCenterY())));
        }

        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Contenedor ICO con una entrada PNG por tamaño */
    static byte[] ico(int[] tamanos, List<byte[]> pngs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(0);                       // reservado
        out.writeShort(Short.reverseBytes((short) 1));           // tipo 1 = icono
        out.writeShort(Short.reverseBytes((short) tamanos.length));
        int desplazamiento = 6 + 16 * tamanos.length;
        for (int i = 0; i < tamanos.length; i++) {
            int t = tamanos[i];
            out.writeByte(t >= 256 ? 0 : t);     // 0 significa 256
            out.writeByte(t >= 256 ? 0 : t);
            out.writeByte(0);                    // paleta
            out.writeByte(0);                    // reservado
            out.writeShort(Short.reverseBytes((short) 1));      // planos
            out.writeShort(Short.reverseBytes((short) 32));     // bits por píxel
            out.writeInt(Integer.reverseBytes(pngs.get(i).length));
            out.writeInt(Integer.reverseBytes(desplazamiento));
            desplazamiento += pngs.get(i).length;
        }
        for (byte[] p : pngs) out.write(p);
        out.flush();
        return bytes.toByteArray();
    }
}
