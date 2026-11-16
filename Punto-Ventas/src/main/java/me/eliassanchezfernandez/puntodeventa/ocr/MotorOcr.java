package me.eliassanchezfernandez.puntodeventa.ocr;

/**
 * Un lector de texto (OCR) para fotos de tickets de proveedor. Recibe los bytes de la foto tal
 * como llegan del teléfono (JPEG/PNG, con o sin orientación EXIF) y devuelve los renglones en
 * orden vertical. Cada implementación hace su propio preproceso. Puede tardar varios segundos en
 * una PC vieja: nunca llamarlo desde el hilo de JavaFX (ver {@link LectorTicketsService}).
 */
public interface MotorOcr {

    ResultadoOcr leer(byte[] imagen) throws java.io.IOException;

    /** Nombre corto para bitácora y pantalla, p. ej. "Tesseract 5.5.4" */
    String nombre();
}
