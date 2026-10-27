package me.eliassanchezfernandez.puntodeventa.terminal;

/**
 * Terminal bancaria (el aparato donde se pasa la tarjeta).
 *
 * HOY solo existe {@link ManualTerminal}: el cajero cobra en la terminal física
 * y captura a mano los datos del voucher (terminal, últimos 4 dígitos y
 * autorización), que se guardan en PagoVenta.
 *
 * PUNTO DE ENGANCHE para un proveedor (Mercado Pago Point, Clip, etc.):
 *  1. Crear una clase @Component que implemente esta interfaz. En {@link #cobrar}
 *     enviar el monto al proveedor (API de la terminal), esperar la aprobación y
 *     devolver un {@link ResultadoTerminal} con los {@link DatosTarjeta} que
 *     regresa el proveedor (ya no se teclean).
 *  2. Devolver un {@link #id()} propio (p. ej. "mercadopago-point") y
 *     {@code capturaManual() == false}.
 *  3. Elegirla con la propiedad {@code app.terminal.proveedor=mercadopago-point}
 *     (la lee {@link TerminalesBancarias}). Sin propiedad se usa "manual".
 *  4. En VentasController.onCobrar(), cuando {@code !terminal.capturaManual()},
 *     llamar a {@link #cobrar} (fuera del hilo de JavaFX) ANTES de
 *     VentaService.cobrar y armar el PagoVenta con los datos devueltos. Si el
 *     proveedor rechaza o no responde, no se cobra la venta.
 * VentaService.cobrar() sigue validando el formato de los datos y guardándolos,
 * venga la captura de donde venga.
 */
public interface TerminalBancaria {

    /** Identificador técnico ("manual", "mercadopago-point", ...) */
    String id();

    /** Nombre para mostrar */
    String nombre();

    /** true: los datos del voucher los teclea el cajero; false: los entrega el proveedor */
    boolean capturaManual();

    /**
     * Cobra {@code solicitud.monto()}. En la terminal manual solo valida lo
     * capturado; en una de proveedor habla con la terminal y puede tardar
     * (llamar fuera del hilo de JavaFX).
     */
    ResultadoTerminal cobrar(SolicitudTerminal solicitud) throws TerminalException;

    /** Lo que se le pide a la terminal. Los datos de captura solo vienen en el modo manual. */
    record SolicitudTerminal(double monto, String terminal, String ultimos4, String autorizacion) { }

    /** Respuesta: aprobado o no, los datos del voucher y el identificador del cobro en el proveedor (si lo hay) */
    record ResultadoTerminal(boolean aprobado, DatosTarjeta datos, String mensaje, String referenciaProveedor) { }

    /** El proveedor no respondió, rechazó la tarjeta o los datos no son válidos */
    class TerminalException extends Exception {
        public TerminalException(String mensaje) { super(mensaje); }
        public TerminalException(String mensaje, Throwable causa) { super(mensaje, causa); }
    }
}
