package me.eliassanchezfernandez.puntodeventa.importacion;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Escritor mínimo de .xls (BIFF8 dentro de OLE2) SOLO para las pruebas del
 * lector: produce lo que producen Excel y las librerías que usan eleventa y
 * compañía (SST con CONTINUE a mitad de cadena, cadenas de 8 y 16 bits, RK,
 * MULRK, NUMBER, BOOLERR), tanto en flujo normal (≥ 4096 bytes) como en el
 * "mini stream" de los libros chicos.
 */
final class BiffPrueba {

    private BiffPrueba() { }

    /** Celdas: String = texto (va a la SST), Double = número, Boolean = booleano, null = vacía */
    static byte[] libro(String hoja, Object[][] filas, boolean forzarContinue) {
        Map<String, Integer> sst = new LinkedHashMap<>();
        for (Object[] fila : filas) for (Object c : fila) if (c instanceof String s) sst.putIfAbsent(s, sst.size());

        ByteArrayOutputStream globales = new ByteArrayOutputStream();
        registro(globales, 0x0809, bof(0x0005));
        int posBoundsheet = globales.size();
        registro(globales, 0x0085, boundsheet(0, hoja));
        escribirSst(globales, new ArrayList<>(sst.keySet()), forzarContinue);
        registro(globales, 0x000A, new byte[0]);

        ByteArrayOutputStream hojaBytes = new ByteArrayOutputStream();
        registro(hojaBytes, 0x0809, bof(0x0010));
        for (int r = 0; r < filas.length; r++) {
            Object[] fila = filas[r];
            // Números consecutivos enteros se agrupan en un MULRK para probar ese registro
            int c = 0;
            while (c < fila.length) {
                Object v = fila[c];
                if (v == null) { c++; continue; }
                if (v instanceof String s) {
                    registro(hojaBytes, 0x00FD, celda(r, c, 6 + 4, b -> b.putInt(sst.get(s))));
                    c++;
                } else if (v instanceof Boolean bo) {
                    registro(hojaBytes, 0x0205, celda(r, c, 6 + 2, b -> { b.put((byte) (bo ? 1 : 0)); b.put((byte) 0); }));
                    c++;
                } else if (v instanceof Double d) {
                    int fin = c;
                    while (fin + 1 < fila.length && fila[fin + 1] instanceof Double d2 && esRkEntero(d2) && esRkEntero(d)) fin++;
                    if (fin > c) {
                        int n = fin - c + 1;
                        ByteBuffer b = ByteBuffer.allocate(4 + 6 * n + 2).order(ByteOrder.LITTLE_ENDIAN);
                        b.putShort((short) r).putShort((short) c);
                        for (int k = 0; k < n; k++) { b.putShort((short) 0); b.putInt(rkEntero((Double) fila[c + k])); }
                        b.putShort((short) fin);
                        registro(hojaBytes, 0x00BD, b.array());
                        c = fin + 1;
                    } else if (esRkCentavos(d)) {
                        registro(hojaBytes, 0x027E, celda(r, c, 6 + 4, b -> b.putInt(rkCentavos(d))));
                        c++;
                    } else {
                        registro(hojaBytes, 0x0203, celda(r, c, 6 + 8, b -> b.putDouble(d)));
                        c++;
                    }
                } else {
                    c++;
                }
            }
        }
        registro(hojaBytes, 0x000A, new byte[0]);

        byte[] g = globales.toByteArray();
        ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN).putInt(posBoundsheet + 4, g.length);   // posición del BOF de la hoja
        ByteArrayOutputStream flujo = new ByteArrayOutputStream();
        flujo.writeBytes(g);
        flujo.writeBytes(hojaBytes.toByteArray());
        return ole2(flujo.toByteArray());
    }

    // ── Registros ────────────────────────────────────────────────────────

    private interface Relleno { void aplicar(ByteBuffer b); }

    private static byte[] celda(int fila, int col, int largo, Relleno relleno) {
        ByteBuffer b = ByteBuffer.allocate(largo).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) fila).putShort((short) col).putShort((short) 0);
        relleno.aplicar(b);
        return b.array();
    }

    private static byte[] bof(int tipo) {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0600).putShort((short) tipo).putShort((short) 0x0DBB).putShort((short) 0x07CC).putInt(0).putInt(0);
        return b.array();
    }

    private static byte[] boundsheet(int posicion, String nombre) {
        byte[] n = nombre.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer b = ByteBuffer.allocate(4 + 2 + 2 + n.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(posicion).put((byte) 0).put((byte) 0).put((byte) n.length).put((byte) 0).put(n);
        return b.array();
    }

    /** SST partida en CONTINUE: si se fuerza, el corte cae a mitad de la segunda cadena */
    private static void escribirSst(ByteArrayOutputStream salida, List<String> cadenas, boolean forzarContinue) {
        List<byte[]> partes = new ArrayList<>();
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        actual.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(cadenas.size()).putInt(cadenas.size()).array());
        int limite = forzarContinue ? 40 : 8000;
        for (String s : cadenas) {
            boolean ancha = s.chars().anyMatch(ch -> ch > 0xFF);
            byte[] chars = s.getBytes(ancha ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
            ByteBuffer cab = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
            cab.putShort((short) s.length()).put((byte) (ancha ? 1 : 0));
            if (actual.size() + 3 > limite) {
                partes.add(actual.toByteArray());
                actual = new ByteArrayOutputStream();
            }
            actual.writeBytes(cab.array());
            int porChar = ancha ? 2 : 1;
            int escrito = 0;
            while (escrito < chars.length) {
                int caben = (limite - actual.size()) / porChar * porChar;
                if (caben <= 0) {
                    partes.add(actual.toByteArray());
                    actual = new ByteArrayOutputStream();
                    actual.write(ancha ? 1 : 0);                 // byte de banderas del CONTINUE
                    caben = (limite - actual.size()) / porChar * porChar;
                }
                int n = Math.min(caben, chars.length - escrito);
                actual.write(chars, escrito, n);
                escrito += n;
            }
        }
        partes.add(actual.toByteArray());
        registro(salida, 0x00FC, partes.get(0));
        for (int i = 1; i < partes.size(); i++) registro(salida, 0x003C, partes.get(i));
    }

    private static void registro(ByteArrayOutputStream out, int tipo, byte[] datos) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) tipo).putShort((short) datos.length).array());
        out.writeBytes(datos);
    }

    private static boolean esRkEntero(double d) {
        return d == Math.rint(d) && Math.abs(d) < (1 << 28);
    }

    private static int rkEntero(double d) {
        return ((int) d << 2) | 0x02;
    }

    private static boolean esRkCentavos(double d) {
        double c = d * 100;
        return c == Math.rint(c) && Math.abs(c) < (1 << 28);
    }

    private static int rkCentavos(double d) {
        return ((int) Math.rint(d * 100) << 2) | 0x03;
    }

    // ── Contenedor OLE2 ──────────────────────────────────────────────────

    private static final int FREESECT = -1, ENDOFCHAIN = -2, FATSECT = -3;

    /** Sector 0 = FAT, 1 = directorio, 2 = mini-FAT (si aplica), 3.. = datos */
    static byte[] ole2(byte[] workbook) {
        boolean mini = workbook.length < 4096;
        int sector = 512;
        List<byte[]> datos = new ArrayList<>();
        int[] fat = new int[128];
        java.util.Arrays.fill(fat, FREESECT);
        fat[0] = FATSECT;
        fat[1] = ENDOFCHAIN;

        int inicioLibro, tamLibro = workbook.length, inicioRaiz = ENDOFCHAIN, tamRaiz = 0;
        byte[] miniFat = null;
        if (mini) {
            int miniSectores = (workbook.length + 63) / 64;
            byte[] miniStream = new byte[miniSectores * 64];
            System.arraycopy(workbook, 0, miniStream, 0, workbook.length);
            ByteBuffer mf = ByteBuffer.allocate(sector).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < miniSectores; i++) mf.putInt(i + 1 < miniSectores ? i + 1 : ENDOFCHAIN);
            while (mf.hasRemaining()) mf.putInt(FREESECT);
            miniFat = mf.array();
            fat[2] = ENDOFCHAIN;
            inicioRaiz = 3;
            tamRaiz = miniStream.length;
            int n = (miniStream.length + sector - 1) / sector;
            for (int i = 0; i < n; i++) {
                byte[] s = new byte[sector];
                System.arraycopy(miniStream, i * sector, s, 0, Math.min(sector, miniStream.length - i * sector));
                datos.add(s);
                fat[3 + i] = i + 1 < n ? 4 + i : ENDOFCHAIN;
            }
            inicioLibro = 0;   // mini sector 0
        } else {
            int n = (workbook.length + sector - 1) / sector;
            for (int i = 0; i < n; i++) {
                byte[] s = new byte[sector];
                System.arraycopy(workbook, i * sector, s, 0, Math.min(sector, workbook.length - i * sector));
                datos.add(s);
                fat[2 + i] = i + 1 < n ? 3 + i : ENDOFCHAIN;
            }
            inicioLibro = 2;
        }

        ByteBuffer cab = ByteBuffer.allocate(sector).order(ByteOrder.LITTLE_ENDIAN);
        cab.put(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        cab.position(0x18);
        cab.putShort((short) 0x003E).putShort((short) 0x0003).putShort((short) 0xFFFE).putShort((short) 9).putShort((short) 6);
        cab.position(0x2C);
        cab.putInt(1).putInt(1).putInt(0).putInt(4096).putInt(mini ? 2 : ENDOFCHAIN).putInt(mini ? 1 : 0).putInt(ENDOFCHAIN).putInt(0);
        cab.putInt(0);
        while (cab.hasRemaining()) cab.putInt(FREESECT);

        ByteBuffer fatBytes = ByteBuffer.allocate(sector).order(ByteOrder.LITTLE_ENDIAN);
        for (int f : fat) fatBytes.putInt(f);

        ByteBuffer dir = ByteBuffer.allocate(sector).order(ByteOrder.LITTLE_ENDIAN);
        entrada(dir, 0, "Root Entry", 5, inicioRaiz, tamRaiz, 1);
        entrada(dir, 1, "Workbook", 2, inicioLibro, tamLibro, FREESECT);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(cab.array());
        out.writeBytes(fatBytes.array());
        out.writeBytes(dir.array());
        if (mini) out.writeBytes(miniFat);
        for (byte[] s : datos) out.writeBytes(s);
        return out.toByteArray();
    }

    private static void entrada(ByteBuffer dir, int indice, String nombre, int tipo, int inicio, int tam, int hijo) {
        int base = indice * 128;
        byte[] n = (nombre + "\0").getBytes(StandardCharsets.UTF_16LE);
        dir.position(base);
        dir.put(n);
        dir.putShort(base + 64, (short) n.length);
        dir.put(base + 66, (byte) tipo);
        dir.put(base + 67, (byte) 1);
        dir.putInt(base + 68, FREESECT);
        dir.putInt(base + 72, FREESECT);
        dir.putInt(base + 76, hijo);
        dir.putInt(base + 116, inicio);
        dir.putInt(base + 120, tam);
    }
}
