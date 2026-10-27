package me.eliassanchezfernandez.puntodeventa.terminal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Registro de las terminales bancarias disponibles. Elige la activa por la
 * propiedad {@code app.terminal.proveedor} (por defecto "manual"); si el id no
 * existe cae en la manual. Ver {@link TerminalBancaria} para enchufar un proveedor.
 */
@Service
public class TerminalesBancarias {

    @Autowired private List<TerminalBancaria> terminales;

    @Value("${app.terminal.proveedor:manual}")
    private String proveedor;

    public TerminalBancaria activa() {
        return terminales.stream().filter(t -> t.id().equalsIgnoreCase(proveedor)).findFirst()
                .orElseGet(() -> terminales.stream().filter(t -> ManualTerminal.ID.equals(t.id())).findFirst()
                        .orElseThrow());
    }

    public List<TerminalBancaria> todas() {
        return List.copyOf(terminales);
    }
}
