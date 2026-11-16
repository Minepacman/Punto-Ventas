package me.eliassanchezfernandez.puntodeventa.ocr;

import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Puente entre el lector de tickets de proveedor ({@code TicketProveedorService}, que pide un {@link MotorOcr}) y
 * {@link LectorTicketsService}, que elige Tesseract o PaddleOCR en cada computadora, corre en su propia hebra de
 * baja prioridad y corta las lecturas que pasan de {@link LectorTicketsService#LIMITE}. Es el único MotorOcr
 * de producción (sin @Primary, para que una prueba pueda poner el suyo con @Primary).
 */
@Component
public class MotorOcrSeleccionado implements MotorOcr {

    private final LectorTicketsService lector;

    public MotorOcrSeleccionado(LectorTicketsService lector) {
        this.lector = lector;
    }

    @Override
    public ResultadoOcr leer(byte[] imagen) throws IOException {
        return lector.leer(imagen, LectorTicketsService.LIMITE);
    }

    @Override
    public String nombre() {
        return lector.motor().map(MotorOcr::nombre).orElse(String.valueOf(lector.decision().motor()));
    }
}
