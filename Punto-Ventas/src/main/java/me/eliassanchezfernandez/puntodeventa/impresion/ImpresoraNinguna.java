package me.eliassanchezfernandez.puntodeventa.impresion;

/** Modo "Ninguna": no imprime. Imprimir o abrir el cajón avisa que no hay impresora. */
public class ImpresoraNinguna implements ImpresoraTickets {

    @Override public void imprimir(DocumentoImpresion documento, int copias, boolean abrirCajon) throws ImpresionException {
        throw new ImpresionException("No hay una impresora configurada.");
    }

    @Override public void abrirCajon() throws ImpresionException {
        throw new ImpresionException("No hay una impresora configurada: el cajón se abre desde la impresora de tickets.");
    }

    @Override public boolean soportaCajon() { return false; }

    @Override public String descripcion() { return "Ninguna"; }
}
