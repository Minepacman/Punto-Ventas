package me.eliassanchezfernandez.puntodeventa.ocr;

/**
 * Motor de lectura de texto (OCR): de una foto a renglones de texto.
 * Contrato compartido con el lector de tickets de proveedor ({@code ticketsproveedor}).
 */
public interface MotorOcr { ResultadoOcr leer(byte[] imagen) throws java.io.IOException; String nombre(); }
