package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.CatalogoCodigo;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Lector del {@code catalogo-mx.csv} del repositorio {@code codigos-barras-mx}
 * (UTF-8, encabezado, comillas RFC 4180 con {@code ""} como escape, puede
 * haber saltos de línea dentro de comillas). Columnas por nombre del
 * encabezado, así un reordenamiento o una columna nueva no rompen la carga:
 * {@code codigo,nombre,marca,cantidad,unidad,contenido,categoria,imagen,fuente,actualizado}.
 *
 * Cada fila válida se entrega ya recortada a las longitudes de
 * {@link CatalogoCodigo}; las filas sin código normalizable, sin nombre o
 * con imagen que no sea https se descartan (se cuentan en {@link Resultado}).
 */
public final class CatalogoCsv {

    private CatalogoCsv() { }

    public record Resultado(int leidas, int validas, int descartadas) { }

    /** Lee todo a una lista (para pruebas y catálogos pequeños) */
    public static List<CatalogoCodigo> leer(Reader entrada) throws IOException {
        List<CatalogoCodigo> filas = new ArrayList<>();
        leer(entrada, filas::add);
        return filas;
    }

    /** Lee en flujo: cada fila válida va al consumidor (la carga masiva no necesita las 25 000 en memoria a la vez) */
    public static Resultado leer(Reader entrada, Consumer<CatalogoCodigo> consumidor) throws IOException {
        Lector lector = new Lector(entrada);
        List<String> encabezado = lector.siguienteRegistro();
        if (encabezado == null) throw new IOException("El archivo del catálogo está vacío.");
        if (!encabezado.isEmpty() && !encabezado.get(0).isEmpty() && encabezado.get(0).charAt(0) == '﻿') {
            encabezado.set(0, encabezado.get(0).substring(1));   // BOM
        }
        int iCodigo = indice(encabezado, "codigo");
        int iNombre = indice(encabezado, "nombre");
        if (iCodigo < 0 || iNombre < 0) {
            throw new IOException("El archivo del catálogo no tiene las columnas esperadas (codigo, nombre).");
        }
        int iMarca = indice(encabezado, "marca");
        int iCantidad = indice(encabezado, "cantidad");
        int iUnidad = indice(encabezado, "unidad");
        int iContenido = indice(encabezado, "contenido");
        int iCategoria = indice(encabezado, "categoria");
        int iImagen = indice(encabezado, "imagen");
        int iFuente = indice(encabezado, "fuente");
        int iActualizado = indice(encabezado, "actualizado");

        int leidas = 0, validas = 0;
        List<String> campos;
        while ((campos = lector.siguienteRegistro()) != null) {
            if (campos.size() == 1 && campos.get(0).isBlank()) continue;   // línea vacía
            leidas++;
            CatalogoCodigo fila = aFila(campos, iCodigo, iNombre, iMarca, iCantidad, iUnidad, iContenido,
                    iCategoria, iImagen, iFuente, iActualizado);
            if (fila == null) continue;
            validas++;
            consumidor.accept(fila);
        }
        return new Resultado(leidas, validas, leidas - validas);
    }

    private static int indice(List<String> encabezado, String nombre) {
        for (int i = 0; i < encabezado.size(); i++) {
            if (encabezado.get(i).trim().toLowerCase(Locale.ROOT).equals(nombre)) return i;
        }
        return -1;
    }

    private static String campo(List<String> campos, int i) {
        if (i < 0 || i >= campos.size()) return "";
        String v = campos.get(i);
        return v == null ? "" : v.trim();
    }

    private static CatalogoCodigo aFila(List<String> c, int iCodigo, int iNombre, int iMarca, int iCantidad,
                                        int iUnidad, int iContenido, int iCategoria, int iImagen, int iFuente,
                                        int iActualizado) {
        String codigo = CodigoBarras.normalizar(campo(c, iCodigo)).orElse(null);
        String nombre = limpiarTexto(campo(c, iNombre));
        if (codigo == null || nombre.isEmpty()) return null;
        CatalogoCodigo f = new CatalogoCodigo();
        f.setCodigo(codigo);
        f.setNombre(recortar(nombre, CatalogoCodigo.NOMBRE_MAX));
        f.setMarca(vacioANull(recortar(limpiarTexto(campo(c, iMarca)), CatalogoCodigo.MARCA_MAX)));
        f.setCantidad(parsearCantidad(campo(c, iCantidad)));
        f.setUnidad(vacioANull(recortar(limpiarTexto(campo(c, iUnidad)), CatalogoCodigo.UNIDAD_MAX)));
        f.setContenido(vacioANull(recortar(limpiarTexto(campo(c, iContenido)), CatalogoCodigo.CONTENIDO_MAX)));
        f.setCategoria(vacioANull(recortar(limpiarTexto(campo(c, iCategoria)), CatalogoCodigo.CATEGORIA_MAX)));
        String imagen = campo(c, iImagen);
        f.setImagen(imagen.startsWith("https://") && imagen.length() <= CatalogoCodigo.IMAGEN_MAX
                && imagen.indexOf(' ') < 0 ? imagen : null);
        f.setFuente(vacioANull(recortar(limpiarTexto(campo(c, iFuente)), CatalogoCodigo.FUENTE_MAX)));
        f.setActualizado(vacioANull(recortar(limpiarTexto(campo(c, iActualizado)), CatalogoCodigo.ACTUALIZADO_MAX)));
        return f;
    }

    /** "600", "600.0", "1.5" → número; vacío, "null" o basura → null */
    static Double parsearCantidad(String texto) {
        if (texto == null) return null;
        String t = texto.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("null")) return null;
        try {
            double v = Double.parseDouble(t);
            return v > 0 && Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sin caracteres de control ni espacios repetidos */
    static String limpiarTexto(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean espacio = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                espacio = true;
                continue;
            }
            if (espacio && b.length() > 0) b.append(' ');
            espacio = false;
            b.append(ch);
        }
        return b.toString();
    }

    static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).trim();
    }

    private static String vacioANull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ── Lector RFC 4180 ───────────────────────────────────────────────────

    /** Un registro a la vez; comillas dobles, {@code ""} como escape, saltos dentro de comillas, CRLF o LF */
    static final class Lector {
        private final Reader in;
        private int siguiente = -2;   // -2 = sin leer aún

        Lector(Reader in) { this.in = in; }

        private int leer() throws IOException {
            if (siguiente != -2) {
                int c = siguiente;
                siguiente = -2;
                return c;
            }
            return in.read();
        }

        private int mirar() throws IOException {
            if (siguiente == -2) siguiente = in.read();
            return siguiente;
        }

        /** null al final del archivo */
        List<String> siguienteRegistro() throws IOException {
            if (mirar() == -1) return null;
            List<String> campos = new ArrayList<>(10);
            StringBuilder campo = new StringBuilder();
            boolean entreComillas = false;
            while (true) {
                int c = leer();
                if (entreComillas) {
                    if (c == -1) break;
                    if (c == '"') {
                        if (mirar() == '"') {
                            leer();
                            campo.append('"');
                        } else {
                            entreComillas = false;
                        }
                    } else {
                        campo.append((char) c);
                    }
                    continue;
                }
                if (c == -1 || c == '\n') break;
                if (c == '\r') {
                    if (mirar() == '\n') leer();
                    break;
                }
                if (c == '"' && campo.length() == 0) {
                    entreComillas = true;
                } else if (c == ',') {
                    campos.add(campo.toString());
                    campo.setLength(0);
                } else {
                    campo.append((char) c);
                }
            }
            campos.add(campo.toString());
            return campos;
        }
    }
}
