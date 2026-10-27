package me.eliassanchezfernandez.puntodeventa.terminal;

import me.eliassanchezfernandez.puntodeventa.service.DispositivosService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Terminal de captura manual: el cajero cobró en la terminal física y teclea el
 * voucher. No se comunica con ningún aparato; solo valida los datos contra la
 * lista de terminales de Configuración.
 */
@Component
public class ManualTerminal implements TerminalBancaria {

    public static final String ID = "manual";

    @Autowired private DispositivosService dispositivos;

    @Override public String id() { return ID; }

    @Override public String nombre() { return "Captura manual del voucher"; }

    @Override public boolean capturaManual() { return true; }

    @Override
    public ResultadoTerminal cobrar(SolicitudTerminal s) throws TerminalException {
        try {
            DatosTarjeta datos = DatosTarjeta.validar(s.terminal(), s.ultimos4(), s.autorizacion(),
                    dispositivos.obtener().getListaTerminales());
            return new ResultadoTerminal(true, datos, "Datos del voucher capturados.", null);
        } catch (NegocioException e) {
            throw new TerminalException(e.getMessage(), e);
        }
    }
}
