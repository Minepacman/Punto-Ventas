package me.eliassanchezfernandez.puntodeventa.config;

import me.eliassanchezfernandez.puntodeventa.util.CalentadorCds;
import me.eliassanchezfernandez.puntodeventa.util.InicioAutomatico;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Encendido de fábrica de "abrir al encender la computadora" ({@link InicioAutomatico}), en el
 * hilo diferido del arranque (en Windows corre reg.exe). Solo con el programa instalado.
 */
@Component
public class InicioAutomaticoAlArrancar {

    @Autowired private ArranqueDiferido arranqueDiferido;

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (CalentadorCds.esProcesoCalentador() || InicioAutomatico.lanzador().isEmpty()) return;
        arranqueDiferido.ejecutar("abrir al encender", () -> InicioAutomatico.alArrancar(RutasApp.carpetaAplicacion()));
    }
}
