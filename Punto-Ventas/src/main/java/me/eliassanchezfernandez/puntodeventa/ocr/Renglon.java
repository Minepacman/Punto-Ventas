package me.eliassanchezfernandez.puntodeventa.ocr;

/** Un renglón leído: texto, confianza 0..1 y posición vertical (y, en píxeles de la foto ya enderezada). */
public record Renglon(String texto, double confianza, int y) {}
