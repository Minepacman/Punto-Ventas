package me.eliassanchezfernandez.puntodeventa.ocr;

/** Un renglón leído: texto, confianza 0..1 y posición vertical y (después de enderezar la foto) */
public record Renglon(String texto, double confianza, int y) {}
