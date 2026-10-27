package me.eliassanchezfernandez.puntodeventa.impresion;

/**
 * Impresora de tickets. Implementaciones: térmica ESC/POS ({@link ImpresoraEscPos}),
 * impresora normal del sistema ({@link ImpresoraSistema}) y {@link ImpresoraNinguna}.
 * Todas son bloqueantes: se llaman SIEMPRE fuera del hilo de JavaFX (ver ImpresionService).
 */
public interface ImpresoraTickets {

    /** Imprime el documento {@code copias} veces; {@code abrirCajon} manda además el pulso al cajón (si la impresora lo soporta) */
    void imprimir(DocumentoImpresion documento, int copias, boolean abrirCajon) throws ImpresionException;

    /** Pulso al cajón de dinero conectado a la impresora (solo la térmica lo soporta) */
    void abrirCajon() throws ImpresionException;

    /** ¿El cajón se abre a través de esta impresora? */
    boolean soportaCajon();

    String descripcion();
}
