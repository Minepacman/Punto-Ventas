package me.eliassanchezfernandez.puntodeventa.ocr;

/** Lo que leyó el motor: renglones en orden vertical y el texto completo */
public record ResultadoOcr(java.util.List<Renglon> renglones, String textoCompleto) {}
