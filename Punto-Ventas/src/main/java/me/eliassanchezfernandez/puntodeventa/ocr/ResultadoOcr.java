package me.eliassanchezfernandez.puntodeventa.ocr;

/** Lo que leyó un {@link MotorOcr}: renglones en orden vertical y el texto completo (renglones unidos con "\n"). */
public record ResultadoOcr(java.util.List<Renglon> renglones, String textoCompleto) {}
