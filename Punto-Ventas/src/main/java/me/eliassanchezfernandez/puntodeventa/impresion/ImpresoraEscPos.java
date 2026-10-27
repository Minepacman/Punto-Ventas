package me.eliassanchezfernandez.puntodeventa.impresion;

/**
 * Impresora térmica de tickets (58/80 mm) que habla ESC/POS. Genera los bytes
 * con {@link EscPos} y los manda por un {@link EnvioRaw} (en producción,
 * {@link EnvioJavaxPrint}; en las pruebas, un canal que los captura).
 */
public class ImpresoraEscPos implements ImpresoraTickets {

    private final EnvioRaw envio;
    private final EscPos.CodePage codePage;
    private final String nombre;

    public ImpresoraEscPos(EnvioRaw envio, EscPos.CodePage codePage, String nombre) {
        this.envio = envio;
        this.codePage = codePage;
        this.nombre = nombre;
    }

    @Override
    public void imprimir(DocumentoImpresion documento, int copias, boolean abrirCajon) throws ImpresionException {
        int n = Math.max(1, copias);
        for (int i = 0; i < n; i++) {
            // El cajón se abre una sola vez, al terminar la última copia
            envio.enviar(EscPos.renderizar(documento, codePage, abrirCajon && i == n - 1, true));
        }
    }

    @Override
    public void abrirCajon() throws ImpresionException {
        envio.enviar(EscPos.soloCajon(codePage));
    }

    @Override public boolean soportaCajon() { return true; }

    @Override public String descripcion() { return "ESC/POS · " + nombre; }
}
