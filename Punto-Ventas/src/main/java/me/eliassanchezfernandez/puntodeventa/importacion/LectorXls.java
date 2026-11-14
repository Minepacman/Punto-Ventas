package me.eliassanchezfernandez.puntodeventa.importacion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector mínimo de libros de Excel 97-2003 (.xls, BIFF8) sin Apache POI.
 *
 * Hace falta porque eleventa y Abarrotes PDV —los dos programas de
 * abarrotes más usados en México— exportan su catálogo en .xls, y pedirle al
 * tendero que lo convierta a .xlsx es un paso más donde se pierden códigos
 * (Excel los convierte en 7.5E+12). Solo se leen textos, números y booleanos de
 * la primera hoja con datos; nada de estilos, fórmulas (se toma su resultado
 * guardado), imágenes ni macros.
 *
 * Estructura: el .xls es un contenedor OLE2 (sectores y una tabla FAT) con un
 * flujo "Workbook" de registros BIFF [tipo u16][largo u16][datos]. Las cadenas
 * viven en la tabla SST (compartida), que puede partirse en registros CONTINUE
 * a mitad de una cadena, cambiando entre caracteres de 8 y 16 bits: el lector
 * de segmentos ({@link Segmentos}) lo resuelve.
 */
public final class LectorXls {

    public static final long TAMANO_MAXIMO = 64L * 1024 * 1024;
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private static final int REG_BOF = 0x0809, REG_EOF = 0x000A, REG_BOUNDSHEET = 0x0085, REG_SST = 0x00FC,
            REG_CONTINUE = 0x003C, REG_LABELSST = 0x00FD, REG_LABEL = 0x0204, REG_RSTRING = 0x00D6,
            REG_NUMBER = 0x0203, REG_RK = 0x027E, REG_MULRK = 0x00BD, REG_FORMULA = 0x0006, REG_STRING = 0x0207,
            REG_BOOLERR = 0x0205, REG_FILEPASS = 0x002F, REG_CODEPAGE = 0x0042;

    private LectorXls() { }

    public static TablaImportada leer(Path archivo) throws IOException {
        if (Files.size(archivo) > TAMANO_MAXIMO) {
            throw new IOException("El archivo pesa más de 64 MB. Exporta solo el catálogo de productos.");
        }
        byte[] libro = flujoWorkbook(Files.readAllBytes(archivo));
        return leerWorkbook(archivo.getFileName().toString(), libro);
    }

    /** Separado para probarlo con un flujo BIFF armado a mano */
    static TablaImportada leerWorkbook(String nombreArchivo, byte[] libro) throws IOException {
        List<Registro> registros = registros(libro);
        if (registros.isEmpty() || registros.get(0).tipo != REG_BOF) {
            throw new IOException("El archivo .xls no tiene el formato esperado de Excel 97-2003.");
        }
        int version = registros.get(0).u16(0);
        boolean biff8 = version >= 0x0600;
        Charset codigo = CP1252;

        List<String> sst = new ArrayList<>();
        Map<String, Integer> hojas = new LinkedHashMap<>();   // nombre → posición del BOF de la hoja
        // Globales: hasta el primer EOF
        for (int i = 0; i < registros.size(); i++) {
            Registro r = registros.get(i);
            if (r.tipo == REG_EOF) break;
            switch (r.tipo) {
                case REG_FILEPASS -> throw new IOException("El libro de Excel está protegido con contraseña. "
                        + "Quítala en Excel (Guardar como → Herramientas → Opciones generales) e inténtalo de nuevo.");
                case REG_CODEPAGE -> codigo = codePage(r.u16(0));
                case REG_BOUNDSHEET -> {
                    int posicion = r.s32(0);
                    String nombre = biff8 ? cadenaCorta(r, 6) : new String(r.datos, 7, r.datos[6] & 0xFF, codigo);
                    hojas.putIfAbsent(nombre.isEmpty() ? "Hoja" + (hojas.size() + 1) : nombre, posicion);
                }
                case REG_SST -> {
                    List<Registro> partes = new ArrayList<>();
                    partes.add(r);
                    for (int j = i + 1; j < registros.size() && registros.get(j).tipo == REG_CONTINUE; j++) {
                        partes.add(registros.get(j));
                    }
                    sst = leerSst(partes);
                }
                default -> { }
            }
        }
        for (Map.Entry<String, Integer> hoja : hojas.entrySet()) {
            List<List<String>> filas = leerHoja(registros, hoja.getValue(), sst, biff8, codigo);
            if (filas.isEmpty()) continue;
            int columnas = 0;
            for (List<String> f : filas) columnas = Math.max(columnas, f.size());
            return new TablaImportada(nombreArchivo, filas, columnas, "Excel 97-2003, hoja \"" + hoja.getKey() + "\"");
        }
        return new TablaImportada(nombreArchivo, List.of(), 0, "Excel 97-2003, sin datos");
    }

    // ── Hoja ─────────────────────────────────────────────────────────────

    private static List<List<String>> leerHoja(List<Registro> registros, int posicionBof, List<String> sst,
                                               boolean biff8, Charset codigo) {
        int inicio = -1;
        for (int i = 0; i < registros.size(); i++) {
            if (registros.get(i).posicion == posicionBof) { inicio = i; break; }
        }
        if (inicio < 0) return List.of();
        Map<Integer, Map<Integer, String>> celdas = new java.util.TreeMap<>();
        for (int i = inicio + 1; i < registros.size(); i++) {
            Registro r = registros.get(i);
            if (r.tipo == REG_EOF) break;
            switch (r.tipo) {
                case REG_LABELSST -> {
                    int idx = r.s32(6);
                    poner(celdas, r.u16(0), r.u16(2), idx >= 0 && idx < sst.size() ? sst.get(idx) : "");
                }
                case REG_LABEL, REG_RSTRING -> {
                    String texto = biff8
                            ? new Segmentos(List.of(r), 8).cadena(r.u16(6), true)
                            : new String(r.datos, 8, Math.min(r.u16(6), r.datos.length - 8), codigo);
                    poner(celdas, r.u16(0), r.u16(2), texto);
                }
                case REG_NUMBER -> poner(celdas, r.u16(0), r.u16(2), numero(r.doble(6)));
                case REG_RK -> poner(celdas, r.u16(0), r.u16(2), numero(rk(r.s32(6))));
                case REG_MULRK -> {
                    int fila = r.u16(0), colInicial = r.u16(2);
                    int n = (r.datos.length - 6) / 6;
                    for (int k = 0; k < n; k++) poner(celdas, fila, colInicial + k, numero(rk(r.s32(4 + k * 6 + 2))));
                }
                case REG_BOOLERR -> {
                    if (r.datos[7] == 0) poner(celdas, r.u16(0), r.u16(2), r.datos[6] != 0 ? "Sí" : "No");
                }
                case REG_FORMULA -> {
                    int fila = r.u16(0), col = r.u16(2);
                    if (r.u16(12) == 0xFFFF) {
                        int tipo = r.datos[6] & 0xFF;
                        if (tipo == 0 && i + 1 < registros.size()) {
                            // El texto viene en el registro STRING que sigue (a veces tras un SHRFMLA/ARRAY)
                            for (int j = i + 1; j < Math.min(i + 3, registros.size()); j++) {
                                Registro s = registros.get(j);
                                if (s.tipo == REG_STRING) {
                                    poner(celdas, fila, col, biff8 ? new Segmentos(List.of(s), 2).cadena(s.u16(0), true)
                                            : new String(s.datos, 2, Math.min(s.u16(0), s.datos.length - 2), codigo));
                                    break;
                                }
                            }
                        } else if (tipo == 1) {
                            poner(celdas, fila, col, r.datos[8] != 0 ? "Sí" : "No");
                        }
                    } else {
                        poner(celdas, fila, col, numero(r.doble(6)));
                    }
                }
                default -> { }
            }
        }
        List<List<String>> filas = new ArrayList<>();
        for (Map<Integer, String> fila : celdas.values()) {
            List<String> f = new ArrayList<>();
            for (Map.Entry<Integer, String> c : fila.entrySet()) {
                while (f.size() < c.getKey()) f.add("");
                f.add(c.getValue());
            }
            boolean conTexto = false;
            for (String s : f) if (!s.isBlank()) { conTexto = true; break; }
            if (conTexto) filas.add(f);
        }
        return filas;
    }

    private static void poner(Map<Integer, Map<Integer, String>> celdas, int fila, int col, String texto) {
        celdas.computeIfAbsent(fila, k -> new java.util.TreeMap<>()).put(col, texto == null ? "" : texto);
    }

    private static String numero(double d) {
        return LectorXlsx.numeroATexto(Double.toString(d));
    }

    /** Número RK: entero o double recortado, opcionalmente dividido entre 100 */
    static double rk(int rk) {
        double v;
        if ((rk & 0x02) != 0) {
            v = rk >> 2;                                  // entero con signo (desplazamiento aritmético)
        } else {
            v = Double.longBitsToDouble(((long) (rk & 0xFFFFFFFC)) << 32);
        }
        return (rk & 0x01) != 0 ? v / 100 : v;
    }

    // ── Cadenas ──────────────────────────────────────────────────────────

    private static List<String> leerSst(List<Registro> partes) {
        List<String> cadenas = new ArrayList<>();
        Segmentos seg = new Segmentos(partes, 8);          // tras total(4) y únicas(4)
        int unicas = partes.get(0).s32(4);
        for (int k = 0; k < unicas && seg.quedan(3); k++) {
            cadenas.add(seg.cadena(seg.u16(), true));
        }
        return cadenas;
    }

    /** Cadena corta (largo en 1 byte) de un BOUNDSHEET BIFF8 */
    private static String cadenaCorta(Registro r, int desde) {
        int largo = r.datos[desde] & 0xFF;
        return new Segmentos(List.of(r), desde + 1).cadena(largo, false);
    }

    private static Charset codePage(int cp) {
        try {
            return switch (cp) {
                case 1200 -> StandardCharsets.UTF_16LE;
                case 367, 20127 -> StandardCharsets.US_ASCII;
                case 10000 -> Charset.forName("x-MacRoman");
                default -> Charset.forName((cp == 850 || cp == 437 ? "IBM" : "windows-") + cp);
            };
        } catch (RuntimeException e) {
            return CP1252;
        }
    }

    /**
     * Lector secuencial sobre varios registros encadenados (SST + CONTINUE). Al
     * cruzar a un registro nuevo a mitad de los caracteres de una cadena, Excel
     * escribe un byte de banderas nuevo (solo cuenta el bit de 16 bits).
     */
    static final class Segmentos {
        private final List<Registro> partes;
        private int indice;
        private int pos;

        Segmentos(List<Registro> partes, int posInicial) {
            this.partes = partes;
            this.pos = posInicial;
        }

        boolean quedan(int n) {
            int disponibles = 0;
            for (int i = indice; i < partes.size(); i++) {
                disponibles += partes.get(i).datos.length - (i == indice ? pos : 0);
                if (disponibles >= n) return true;
            }
            return false;
        }

        private boolean avanzarSiHaceFalta() {
            while (indice < partes.size() && pos >= partes.get(indice).datos.length) {
                indice++;
                pos = 0;
            }
            return indice < partes.size();
        }

        int u8() {
            if (!avanzarSiHaceFalta()) return 0;
            return partes.get(indice).datos[pos++] & 0xFF;
        }

        int u16() {
            int lo = u8();
            return lo | (u8() << 8);
        }

        int s32() {
            return u16() | (u16() << 16);
        }

        void saltar(long n) {
            for (long i = 0; i < n; i++) u8();
        }

        /**
         * Cadena Unicode BIFF8: byte de banderas (bit0 = 16 bits, bit2 = extensión
         * fonética, bit3 = texto enriquecido), [n° de tramos u16], [tamaño ext u32],
         * caracteres, tramos (4 bytes c/u), extensión.
         */
        String cadena(int caracteres, boolean completa) {
            int banderas = u8();
            boolean ancha = (banderas & 0x01) != 0;
            boolean rica = completa && (banderas & 0x08) != 0;
            boolean ext = completa && (banderas & 0x04) != 0;
            int tramos = rica ? u16() : 0;
            long tamanoExt = ext ? (s32() & 0xFFFFFFFFL) : 0;
            StringBuilder sb = new StringBuilder(caracteres);
            int leidos = 0;
            int registroActual = indice;          // registro donde terminó la cabecera de la cadena
            while (leidos < caracteres) {
                if (!avanzarSiHaceFalta()) break;
                byte[] d = partes.get(indice).datos;
                int disponibles = d.length - pos;
                if (disponibles <= 0) continue;
                if (indice != registroActual && pos == 0) {
                    // Los caracteres siguen en un registro CONTINUE: trae su propio byte de banderas
                    registroActual = indice;
                    ancha = (d[pos++] & 0x01) != 0;
                    disponibles--;
                    if (disponibles <= 0) continue;
                }
                int porLeer = Math.min(caracteres - leidos, ancha ? disponibles / 2 : disponibles);
                if (porLeer == 0) {
                    // Un byte suelto de un carácter de 16 bits al final del registro (no debería pasar): se descarta
                    pos = d.length;
                    continue;
                }
                if (ancha) {
                    sb.append(new String(d, pos, porLeer * 2, StandardCharsets.UTF_16LE));
                    pos += porLeer * 2;
                } else {
                    sb.append(new String(d, pos, porLeer, StandardCharsets.ISO_8859_1));
                    pos += porLeer;
                }
                leidos += porLeer;
            }
            saltar(4L * tramos);
            saltar(tamanoExt);
            return sb.toString();
        }
    }

    // ── Registros BIFF ───────────────────────────────────────────────────

    static final class Registro {
        final int tipo;
        final int posicion;
        final byte[] datos;

        Registro(int tipo, int posicion, byte[] datos) {
            this.tipo = tipo;
            this.posicion = posicion;
            this.datos = datos;
        }

        int u16(int o) {
            return o + 1 < datos.length ? (datos[o] & 0xFF) | ((datos[o + 1] & 0xFF) << 8) : 0;
        }

        int s32(int o) {
            return u16(o) | (u16(o + 2) << 16);
        }

        double doble(int o) {
            if (o + 8 > datos.length) return 0;
            return ByteBuffer.wrap(datos, o, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
        }
    }

    static List<Registro> registros(byte[] flujo) {
        List<Registro> lista = new ArrayList<>();
        int p = 0;
        while (p + 4 <= flujo.length) {
            int tipo = (flujo[p] & 0xFF) | ((flujo[p + 1] & 0xFF) << 8);
            int largo = (flujo[p + 2] & 0xFF) | ((flujo[p + 3] & 0xFF) << 8);
            int fin = Math.min(flujo.length, p + 4 + largo);
            byte[] datos = new byte[fin - (p + 4)];
            System.arraycopy(flujo, p + 4, datos, 0, datos.length);
            lista.add(new Registro(tipo, p, datos));
            p = fin;
            if (tipo == 0 && largo == 0 && lista.size() > 1) break;   // relleno al final del flujo
        }
        return lista;
    }

    // ── Contenedor OLE2 (CFB) ────────────────────────────────────────────

    /** Extrae el flujo "Workbook" (BIFF8) o "Book" (BIFF5) del contenedor OLE2 */
    static byte[] flujoWorkbook(byte[] archivo) throws IOException {
        if (archivo.length < 512 || (archivo[0] & 0xFF) != 0xD0 || (archivo[1] & 0xFF) != 0xCF
                || (archivo[2] & 0xFF) != 0x11 || (archivo[3] & 0xFF) != 0xE0) {
            throw new IOException("El archivo no es un libro de Excel 97-2003 (.xls) válido.");
        }
        ByteBuffer bb = ByteBuffer.wrap(archivo).order(ByteOrder.LITTLE_ENDIAN);
        int tamSector = 1 << bb.getShort(0x1E);
        int tamMini = 1 << bb.getShort(0x20);
        int numFat = bb.getInt(0x2C);
        int primerDir = bb.getInt(0x30);
        int corteMini = bb.getInt(0x38);
        int primerMiniFat = bb.getInt(0x3C);
        int numMiniFat = bb.getInt(0x40);
        int primerDifat = bb.getInt(0x44);
        int numDifat = bb.getInt(0x48);
        if (tamSector < 128 || tamSector > 4096 || tamMini < 8 || tamMini > tamSector) {
            throw new IOException("El archivo .xls está dañado (tamaño de sector).");
        }
        int porSector = tamSector / 4;

        // DIFAT: 109 entradas en la cabecera + sectores DIFAT encadenados
        List<Integer> sectoresFat = new ArrayList<>();
        for (int i = 0; i < 109 && sectoresFat.size() < numFat; i++) {
            int s = bb.getInt(0x4C + i * 4);
            if (s >= 0) sectoresFat.add(s);
        }
        int difat = primerDifat;
        for (int k = 0; k < numDifat && difat >= 0 && sectoresFat.size() < numFat; k++) {
            int base = offsetSector(difat, tamSector, archivo.length);
            for (int i = 0; i < porSector - 1 && sectoresFat.size() < numFat; i++) {
                int s = bb.getInt(base + i * 4);
                if (s >= 0) sectoresFat.add(s);
            }
            difat = bb.getInt(base + (porSector - 1) * 4);
        }
        int[] fat = new int[sectoresFat.size() * porSector];
        for (int i = 0; i < sectoresFat.size(); i++) {
            int base = offsetSector(sectoresFat.get(i), tamSector, archivo.length);
            for (int j = 0; j < porSector; j++) fat[i * porSector + j] = bb.getInt(base + j * 4);
        }

        // Directorio
        byte[] directorio = cadena(archivo, fat, primerDir, tamSector, Long.MAX_VALUE);
        int inicioRaiz = -1;
        long tamRaiz = 0;
        int inicioLibro = -1;
        long tamLibro = 0;
        for (int e = 0; e + 128 <= directorio.length; e += 128) {
            ByteBuffer d = ByteBuffer.wrap(directorio, e, 128).order(ByteOrder.LITTLE_ENDIAN);
            int largoNombre = d.getShort(e + 64) & 0xFFFF;
            int tipo = directorio[e + 66] & 0xFF;
            if (tipo == 0 || largoNombre < 2 || largoNombre > 64) continue;
            String nombre = new String(directorio, e, largoNombre - 2, StandardCharsets.UTF_16LE);
            int inicio = d.getInt(e + 116);
            long tam = d.getInt(e + 120) & 0xFFFFFFFFL;
            if (tipo == 5) {
                inicioRaiz = inicio;
                tamRaiz = tam;
            } else if (tipo == 2 && (nombre.equals("Workbook") || nombre.equals("Book")) && inicioLibro < 0) {
                inicioLibro = inicio;
                tamLibro = tam;
            } else if (tipo == 2 && nombre.equalsIgnoreCase("EncryptedPackage")) {
                throw new IOException("El libro de Excel está cifrado con contraseña. Quítala e inténtalo de nuevo.");
            }
        }
        if (inicioLibro < 0) {
            throw new IOException("El archivo .xls no contiene una hoja de cálculo de Excel (¿es un .doc u otro tipo?).");
        }
        if (tamLibro >= corteMini) {
            return cadena(archivo, fat, inicioLibro, tamSector, tamLibro);
        }
        // Flujo pequeño: vive en el "mini stream" del directorio raíz, con su propia mini-FAT
        byte[] miniStream = cadena(archivo, fat, inicioRaiz, tamSector, tamRaiz);
        byte[] miniFatBytes = cadena(archivo, fat, primerMiniFat, tamSector, (long) numMiniFat * tamSector);
        int[] miniFat = new int[miniFatBytes.length / 4];
        ByteBuffer mf = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < miniFat.length; i++) miniFat[i] = mf.getInt(i * 4);
        ByteArrayOutputStream salida = new ByteArrayOutputStream((int) tamLibro);
        int s = inicioLibro;
        int vueltas = 0;
        while (s >= 0 && s < miniFat.length && salida.size() < tamLibro && vueltas++ < miniFat.length + 1) {
            int desde = s * tamMini;
            int n = (int) Math.min(tamMini, Math.min(tamLibro - salida.size(), Math.max(0, miniStream.length - desde)));
            if (n <= 0) break;
            salida.write(miniStream, desde, n);
            s = miniFat[s];
        }
        return salida.toByteArray();
    }

    private static int offsetSector(int sector, int tamSector, int largoArchivo) throws IOException {
        long o = (long) (sector + 1) * tamSector;
        if (o < 0 || o + tamSector > largoArchivo) throw new IOException("El archivo .xls está truncado o dañado.");
        return (int) o;
    }

    /** Sigue una cadena de sectores en la FAT hasta ENDOFCHAIN (-2) o hasta leer {@code maximo} bytes */
    private static byte[] cadena(byte[] archivo, int[] fat, int inicio, int tamSector, long maximo) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        int s = inicio;
        int vueltas = 0;
        while (s >= 0 && s < fat.length && salida.size() < maximo) {
            if (vueltas++ > fat.length) throw new IOException("El archivo .xls está dañado (cadena de sectores circular).");
            int o = offsetSector(s, tamSector, archivo.length);
            int n = (int) Math.min(tamSector, maximo - salida.size());
            salida.write(archivo, o, n);
            s = fat[s];
        }
        return salida.toByteArray();
    }
}
